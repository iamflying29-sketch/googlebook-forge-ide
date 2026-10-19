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

from .images import VISION_PROMPT, ImageStore, run_ocr, sniff, vision_payload
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
     "need_mb": 3600, "ctx": 4096, "image_max_tokens": 1024},
    {"name": "Qwen3-VL 2B", "model": "Qwen3VL-2B-Instruct-Q4_K_M.gguf", "mmproj": "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf",
     "need_mb": 3500, "ctx": 4096, "image_max_tokens": 1024},
]


def _noop(*_a: Any, **_k: Any) -> None:
    return None


class VisionManager:
    def __init__(self, state: Any, data_dir: str):
        self.st = state
        self.models_dir = os.path.join(data_dir, "models", "vision")
        self.images = ImageStore(os.path.join(data_dir, "images"))
        self.cond = threading.Condition()
        self.wanted_until = 0.0
        self.app_seen = 0.0
        self.engine_poke = False   # set when a chat just failed without the GPU engine, to answer the app's poll at once
        self.app: Dict[str, Any] = {}           # what the Forge app last reported about the GPU vision server
        self.app_report_ts = 0.0                # when the app last reported a vision state change
        self.busy = threading.Lock()            # one look at a time: the GPU and memory are shared
        self.holds = 0                          # while > 0 the vision model stays loaded (several images in one message)
        # Live activity for the chat (model states, streamed answers, the GPU handoff with Llama). Set per look.
        self._ev: Callable[..., None] = _noop
        self._llama_lent_gpu = False
        self._label = "the vision model"

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
        was_wanted = self.wanted_until > 0
        self.wanted_until = 0.0
        with self.cond:
            self.cond.notify_all()
        if was_wanted:
            self._ev("model_activity", model="vision", state="released", label=self._label,
                     detail="Finished. The Forge app stops it and frees its memory.")
            if self._llama_lent_gpu:
                self._ev("model_activity", model="llama", state="resuming", label="Llama 3.1",
                         detail="The Intel GPU is free again, so Llama's GPU engine resumes.")
            self._llama_lent_gpu = False

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
            if self.want() == running and not self.engine_poke:
                self.cond.wait(timeout=max(0.0, min(wait, 25.0)))
            self.engine_poke = False
        self.app_seen = time.time()
        st = self.st
        return {"want": self.want(), "idle_stop_s": IDLE_STOP_S, "tiers": self.tiers(), "port": VISION_PORT,
                "host_ip": st.host_ip(), "api_key": st.settings.get("gpu_engine_key", ""),
                "file_url": "/api/engine/vision_file?name=", "engine_start": st.engine_start_due(report),
                "pool_credit_percent": int(st.settings.get("gpu_pool_credit", 0) or 0),
                "vision_extra_args": str(st.settings.get("vision_extra_args", "") or "")}

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
        ev = self._ev
        st = self.st
        tier = self.app.get("vtier") if self.app.get("vstate") == "running" else None
        self._label = f"{tier or self.tiers()[0]['name']} (Intel GPU)"
        llama_on_gpu = st.active_engine() == "gpu" and st.engine_stats.get("state") in ("running", "paused")
        requested = time.time()
        self.wanted_until = requested + IDLE_STOP_S
        with self.cond:
            self.cond.notify_all()
        c = self._client()
        deadline = time.time() + 300
        last_vstate, last_engine = None, st.engine_stats.get("state")
        if not c.alive():
            ev("model_activity", model="vision", state="starting", label=self._label,
               detail="Forge asks the Forge app to start the vision model on the Intel GPU.")
            if llama_on_gpu:
                self._llama_lent_gpu = True
                ev("model_activity", model="llama", state="pausing", label="Llama 3.1",
                   detail="Llama's GPU engine pauses so the vision model can use the Intel GPU (one model on the GPU at a time).")
        while time.time() < deadline:
            if c.alive():
                break
            vstate, engine = self.app.get("vstate"), st.engine_stats.get("state")
            if engine != last_engine:
                last_engine = engine
                if engine == "paused" and llama_on_gpu:
                    ev("model_activity", model="llama", state="paused", label="Llama 3.1",
                       detail="Llama's GPU engine is paused and has lent the Intel GPU to the vision model.")
            if vstate != last_vstate and self.app_report_ts > requested:
                last_vstate = vstate
                if self.app.get("vtier"):
                    self._label = f"{self.app.get('vtier')} (Intel GPU)"
                if vstate == "downloading":
                    ev("model_activity", model="vision", state="preparing", label=self._label,
                       detail="Copying the vision model files into the Forge app (only the first time).")
                elif vstate == "starting":
                    ev("model_activity", model="vision", state="loading", label=self._label,
                       detail="Loading the vision model onto the Intel GPU" + (f" ({self.app.get('vdetail')})" if self.app.get("vdetail") else "") + ".")
            if self.app.get("vstate") == "error" and self.app_report_ts > requested:
                why = str(self.app.get("vdetail", "unknown reason"))
                emit("The GPU vision model could not start (" + why + ").")
                ev("model_activity", model="vision", state="failed", label=self._label, detail="Could not start: " + why)
                self.release()
                return None
            time.sleep(1.0)
        else:
            ev("model_activity", model="vision", state="failed", label=self._label, detail="Did not start within five minutes.")
            self.release()
            return None
        if self.app.get("vtier"):
            self._label = f"{self.app.get('vtier')} (Intel GPU)"
        payload, pmime = vision_payload(path, mime)
        b64 = base64.b64encode(payload).decode()
        messages = [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": f"data:{pmime};base64,{b64}"}},
            {"type": "text", "text": question}]}]
        ev("model_activity", model="vision", state="looking", label=self._label,
           detail="Looking at the image (it turns the picture into up to 1,280 image tokens, then reads the question).")
        t0 = time.time()
        parts: List[str] = []
        try:
            for obj in c.chat_stream("vision", messages, options={"temperature": 0.1, "num_predict": 500}):
                delta = (obj.get("message") or {}).get("content", "")
                if delta:
                    if not parts:
                        ev("model_activity", model="vision", state="answering", label=self._label,
                           detail=f"Writing its answer (it looked for {time.time() - t0:.0f} s).")
                    parts.append(delta)
                    ev("vision_delta", text=delta)
                if obj.get("done"):
                    break
        except OllamaError as e:
            emit(f"The GPU vision model failed ({e}).")
            ev("model_activity", model="vision", state="failed", label=self._label, detail=f"Failed: {e}")
            self.release()
            return None
        text = "".join(parts).strip()
        ev("model_activity", model="vision", state="done", label=self._label,
           detail=f"Answered in {time.time() - t0:.0f} s ({len(text.split())} words).")
        self.release()
        return text, self._label.replace("(Intel GPU)", "on the Intel GPU").strip()

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
            self._ev("model_activity", model="vision", state="skipped", label=f"{model} (CPU)",
                     detail="Skipped: Llama is using the VM's memory, and two models must not be loaded at once.")
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
        ev = self._ev
        self._label = f"{model} (CPU)"
        ev("model_activity", model="vision", state="loading", label=self._label,
           detail="Loading the CPU vision model in the Linux VM (it is unloaded again right after this look).")
        payload, _pmime = vision_payload(path, mime)
        b64 = base64.b64encode(payload).decode()
        t0 = time.time()
        parts: List[str] = []
        try:
            for obj in st.ollama.chat_stream(model, [{"role": "user", "content": question, "images": [b64]}],
                                             options={"temperature": 0.1, "num_ctx": 4096, "num_predict": 450},
                                             keep_alive="0", think=False):
                delta = (obj.get("message") or {}).get("content", "")
                if delta:
                    if not parts:
                        ev("model_activity", model="vision", state="answering", label=self._label,
                           detail=f"Writing its answer (loading and looking took {time.time() - t0:.0f} s).")
                    parts.append(delta)
                    ev("vision_delta", text=delta)
                if obj.get("done"):
                    break
        except OllamaError as e:
            emit(f"The CPU vision model failed ({e}).")
            ev("model_activity", model="vision", state="failed", label=self._label, detail=f"Failed: {e}")
            return None
        text = "".join(parts).strip()
        ev("model_activity", model="vision", state="done", label=self._label,
           detail=f"Answered in {time.time() - t0:.0f} s and was unloaded from memory.")
        return text, f"{model} on the CPU"

    def ask(self, path: str, mime: str, question: str, settings: dict, emit: Callable[[str], None],
            ev: Optional[Callable[..., None]] = None) -> tuple:
        """Returns (answer, source). An empty answer means only text recognition is available. `ev` receives live
        activity for the chat: model_activity (states of the vision model and of Llama's GPU engine) and
        vision_delta (the answer while it is written)."""
        mode = str(settings.get("vision", "auto"))
        if mode == "ocr":
            (ev or _noop)("model_activity", model="vision", state="off", label="the vision model",
                          detail="Turned off in Settings (Images: ocr), so only text recognition is used.")
            return "", "off"
        with self.busy:
            self._ev = ev or _noop
            try:
                return self._ask_locked(path, mime, question, mode, emit)
            finally:
                self._ev = _noop

    def _ask_locked(self, path: str, mime: str, question: str, mode: str, emit: Callable[[str], None]) -> tuple:
        if mode in ("auto", "gpu"):
            r = self._ask_gpu(path, mime, question, emit)
            if r:
                return r
        if mode in ("auto", "cpu"):
            r = self._ask_cpu(path, question, emit)
            if r:
                return r
        self._ev("model_activity", model="vision", state="unavailable", label="the vision model",
                 detail="Not available for this look, so Llama gets the recognized text only.")
        return "", "unavailable"

    def analyze(self, img_id: str, settings: dict, emit: Callable[[str], None],
                ev: Optional[Callable[..., None]] = None) -> dict:
        """OCR plus the default description, cached per image."""
        ev = ev or _noop
        found = self.images.find(img_id)
        if not found:
            return {"error": "image not found"}
        path, mime = found
        cached = self.images.cached_analysis(img_id)
        if cached.get("description") and "ocr" in cached:
            ev("model_activity", model="vision", state="cached", label=str(cached.get("source") or "the vision model"),
               detail="This image was already described earlier, so the saved answer is reused.")
            return {**cached, "cached": True}
        out = dict(cached)
        ocr_thread = None
        if "ocr" not in out:
            # Text recognition runs on the VM's CPU while the vision model loads and looks on the Intel GPU; the two
            # do not share anything, so running them at the same time hides the 10 to 15 seconds of text recognition.
            ev("model_activity", model="ocr", state="working", label="Text recognition (tesseract)",
               detail="Reading every word in the image (alongside the vision model).")
            t0 = time.time()
            def _ocr():
                out["ocr"] = run_ocr(path)
                ev("model_activity", model="ocr", state="done", label="Text recognition (tesseract)",
                   detail=(f"Found {len(out['ocr'])} characters of text" if out["ocr"] else "Found no text") + f" in {time.time() - t0:.1f} s.")
            ocr_thread = threading.Thread(target=_ocr, name="forge-ocr", daemon=True)
            ocr_thread.start()
        if not out.get("description"):
            desc, source = self.ask(path, mime, VISION_PROMPT, settings, emit, ev)
            out["description"], out["source"] = desc, source
        if ocr_thread is not None:
            ocr_thread.join(timeout=90)
            if "ocr" not in out:
                out["ocr"] = ""
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
