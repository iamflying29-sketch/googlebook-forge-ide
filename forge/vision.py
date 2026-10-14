"""Forge's eyes: the vision model that looks at images for Llama 3.1, which reads only text.

Order of preference for each look:
1. GPU: Qwen3-VL on the Intel graphics, in a llama.cpp server the Forge app starts on demand (4B when the Googlebook
   has room, otherwise 2B) and stops again after a few idle minutes so it only borrows memory while it is needed.
2. CPU: Qwen3-VL 2B through Ollama inside the Linux VM, unloaded right after each look, and only when memory allows.
3. Text recognition only (tesseract), which always works.

The Forge app learns what to do through a long poll on /api/engine/control, so no extra port is opened on the host.
"""
from __future__ import annotations

import base64
import os
import shutil
import threading
import time
from typing import Any, Callable, Dict, List, Optional

from .images import VISION_PROMPT, ImageStore, describe_with_vision, run_ocr, sniff
from .llamacpp_client import LlamaCppClient
from .ollama_client import OllamaError

VISION_PORT = 8082
IDLE_STOP_S = 180                 # the GPU vision server stops after this many idle seconds
CPU_VISION_NEED_MB = 3200         # free VM memory needed to run the CPU vision model next to nothing else
HOST_MEM_FOR_CPU_VISION_MB = 5000 # the VM borrows from the host: 2.5 GB for the model plus 2.5 GB that must stay free

# Best first. need_mb is the effective free memory (see GpuBudget in the app) required before launch. Rule from the
# Googlebook's history: after any GPU load at least 2.5 GB must stay free (Llama's engine ran stably for hours above
# that; the crash of 2026-10-14 happened below it). Measured visible drop while reading a screenshot: about 1.1 GB.
TIERS = [
    {"name": "Qwen3-VL 4B", "model": "Qwen3VL-4B-Instruct-Q4_K_M.gguf", "mmproj": "mmproj-Qwen3VL-4B-Instruct-F16.gguf",
     "need_mb": 3600, "ctx": 4096, "image_max_tokens": 1280},
    {"name": "Qwen3-VL 2B", "model": "Qwen3VL-2B-Instruct-Q4_K_M.gguf", "mmproj": "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf",
     "need_mb": 3500, "ctx": 4096, "image_max_tokens": 1280},
]


class VisionManager:
    def __init__(self, state: Any, data_dir: str):
        self.st = state
        self.models_dir = os.path.join(data_dir, "models", "vision")
        self.images = ImageStore(os.path.join(data_dir, "images"))
        self.cond = threading.Condition()
        self.wanted_until = 0.0
        self.app_seen = 0.0
        self.app: Dict[str, Any] = {}           # what the Forge app last reported about the GPU vision server
        self.app_report_ts = 0.0                # when the app last reported a vision state change
        self.busy = threading.Lock()            # one look at a time: the GPU and memory are shared
        self.holds = 0                          # while > 0 the vision model stays loaded (several images in one message)

    # ------------------------------------------------------------------ app side (long poll)
    def tiers(self) -> List[dict]:
        out = []
        for t in TIERS:
            mp, pp = os.path.join(self.models_dir, t["model"]), os.path.join(self.models_dir, t["mmproj"])
            if os.path.isfile(mp) and os.path.isfile(pp):
                out.append({**t, "model_size": os.path.getsize(mp), "mmproj_size": os.path.getsize(pp)})
        return out

    def want(self) -> bool:
        return time.time() < self.wanted_until

    def release(self) -> None:
        """Give the GPU back to Llama right away (the app stops the vision server and resumes Llama's engine)."""
        if self.holds > 0:
            return
        self.wanted_until = 0.0
        with self.cond:
            self.cond.notify_all()

    class _Hold:
        def __init__(self, vm: "VisionManager"):
            self.vm = vm
        def __enter__(self):
            self.vm.holds += 1
            return self
        def __exit__(self, *exc):
            self.vm.holds = max(0, self.vm.holds - 1)
            self.vm.release()

    def hold(self) -> "_Hold":
        return VisionManager._Hold(self)

    def control(self, report: Dict[str, Any], wait: float) -> dict:
        """Called by the Forge app every few seconds (long poll). Returns what the vision server should be doing."""
        self.app_seen = time.time()
        if report:
            self.app = dict(report)
        with self.cond:
            running = report.get("vstate") in ("running", "starting", "downloading")
            if self.want() == running:
                self.cond.wait(timeout=max(0.0, min(wait, 25.0)))
        self.app_seen = time.time()
        st = self.st
        return {"want": self.want(), "idle_stop_s": IDLE_STOP_S, "tiers": self.tiers(), "port": VISION_PORT,
                "host_ip": st.host_ip(), "api_key": st.settings.get("gpu_engine_key", ""),
                "file_url": "/api/engine/vision_file?name="}

    def report(self, data: Dict[str, Any]) -> None:
        self.app = {**self.app, **data}
        self.app_report_ts = time.time()
        self.app_seen = time.time()
        with self.cond:
            self.cond.notify_all()

    def file_path(self, name: str) -> Optional[str]:
        if not any(name in (t["model"], t["mmproj"]) for t in TIERS):
            return None
        p = os.path.join(self.models_dir, name)
        return p if os.path.isfile(p) else None

    def status(self) -> dict:
        return {"app_connected": time.time() - self.app_seen < 60, "app": self.app, "wanted": self.want(),
                "tiers_available": [t["name"] for t in self.tiers()], "ocr": bool(shutil.which("tesseract"))}

    # ------------------------------------------------------------------ looking at images
    def _client(self) -> LlamaCppClient:
        return LlamaCppClient(f"http://{self.st.host_ip()}:{VISION_PORT}", str(self.st.settings.get("gpu_engine_key", "")))

    def _ask_gpu(self, path: str, mime: str, question: str, emit: Callable[[str], None]) -> Optional[tuple]:
        if time.time() - self.app_seen > 60 or not self.tiers():
            return None
        requested = time.time()
        self.wanted_until = requested + IDLE_STOP_S
        with self.cond:
            self.cond.notify_all()
        c = self._client()
        deadline = time.time() + 300
        told = False
        while time.time() < deadline:
            if c.alive():
                break
            if self.app.get("vstate") == "error" and self.app_report_ts > requested:
                emit("The GPU vision model could not start (" + str(self.app.get("vdetail", "unknown reason")) + ").")
                self.release()
                return None
            if not told:
                emit("Starting the vision model on the Intel GPU…")
                told = True
            time.sleep(1.0)
        else:
            self.release()
            return None
        with open(path, "rb") as fh:
            b64 = base64.b64encode(fh.read()).decode()
        body = {"model": "vision", "temperature": 0.1, "max_tokens": 500, "cache_prompt": False,
                "messages": [{"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}},
                    {"type": "text", "text": question}]}]}
        try:
            r = c._json("POST", "/v1/chat/completions", body, timeout=300)
        except OllamaError as e:
            emit(f"The GPU vision model failed ({e}).")
            self.release()
            return None
        text = "".join((ch.get("message") or {}).get("content", "") or "" for ch in r.get("choices", []))
        self.release()
        tier = self.app.get("vtier") or "Qwen3-VL"
        return text.strip(), f"{tier} on the Intel GPU"

    def _ask_cpu(self, path: str, question: str, emit: Callable[[str], None]) -> Optional[tuple]:
        st = self.st
        model = str(st.settings.get("vision_cpu_model", "qwen3-vl:2b"))
        try:
            names = {m.get("name") for m in st.ollama.list_models()}
        except OllamaError:
            return None
        if model not in names and (model + ":latest") not in names:
            return None
        from .server import mem_info   # late import: server imports this module
        m = mem_info()
        try:
            loaded = [x.get("name") for x in st.ollama.running()]
        except OllamaError:
            loaded = []
        if loaded:
            emit("The CPU vision model was skipped because the main model is using the VM's memory.")
            return None
        if m.get("available_mb", 0) < CPU_VISION_NEED_MB:
            emit(f"The CPU vision model was skipped to protect memory ({m.get('available_mb')} MB free in the VM).")
            return None
        host = self.app.get("host_mem_mb") if time.time() - self.app_seen < 60 else None
        if st.active_engine() == "gpu" and not isinstance(host, (int, float)):
            emit("The CPU vision model was skipped because the Googlebook's free memory is unknown.")
            return None
        if isinstance(host, (int, float)) and host < HOST_MEM_FOR_CPU_VISION_MB:
            emit("The CPU vision model was skipped to protect the Googlebook's memory.")
            return None
        emit("Looking at the image with the CPU vision model…")
        try:
            text = describe_with_vision(st.ollama, model, path, keep_alive="0", prompt=question)
        except OllamaError as e:
            emit(f"The CPU vision model failed ({e}).")
            return None
        return text, f"{model} on the CPU"

    def ask(self, path: str, mime: str, question: str, settings: dict, emit: Callable[[str], None]) -> tuple:
        """Returns (answer, source). An empty answer means only text recognition is available."""
        mode = str(settings.get("vision", "auto"))
        if mode == "ocr":
            return "", "off"
        with self.busy:
            if mode in ("auto", "gpu"):
                r = self._ask_gpu(path, mime, question, emit)
                if r:
                    return r
            if mode in ("auto", "cpu"):
                r = self._ask_cpu(path, question, emit)
                if r:
                    return r
        return "", "unavailable"

    def analyze(self, img_id: str, settings: dict, emit: Callable[[str], None]) -> dict:
        """OCR plus the default description, cached per image."""
        found = self.images.find(img_id)
        if not found:
            return {"error": "image not found"}
        path, mime = found
        cached = self.images.cached_analysis(img_id)
        if cached.get("description") and "ocr" in cached:
            return cached
        out = dict(cached)
        if "ocr" not in out:
            emit("Reading the text in the image…")
            out["ocr"] = run_ocr(path)
        if not out.get("description"):
            desc, source = self.ask(path, mime, VISION_PROMPT, settings, emit)
            out["description"], out["source"] = desc, source
        self.images.save_analysis(img_id, out)
        return out

    def image_from_target(self, target: str, ws: Any) -> Optional[tuple]:
        """An image id from this chat, or an image file anywhere on the computer (copied into the store)."""
        target = (target or "").strip()
        t = target.lower().replace("image", "").replace("#", "").strip()
        found = self.images.find(t) or self.images.find(target)
        if found:
            return found
        try:
            p = ws.resolve(target, must_exist=True)
        except Exception:  # noqa: BLE001
            return None
        if not os.path.isfile(p) or os.path.getsize(p) > 15 * 1024 * 1024:
            return None
        with open(p, "rb") as fh:
            data = fh.read()
        if not sniff(data):
            return None
        meta = self.images.save(data, os.path.basename(p))
        return self.images.find(meta["id"])
