"""Forge IDE HTTP server (standard library only).

Serves the web UI and a JSON API: files, search, agent runs streamed over Server-Sent Events,
approvals, sessions, checkpoints, inline edit, completions, and a WebSocket terminal.
"""
from __future__ import annotations

import json
import mimetypes
import os
import posixpath
import socket
import sys
import threading
import time
import traceback
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, Optional

from . import __version__
from .agent import Agent, Run, complete, inline_edit_stream
from .llamacpp_client import LlamaCppClient
from .vision import VisionManager
from .ollama_client import OllamaClient, OllamaError
from .terminal import TerminalManager
from .tools import Checkpoints, Workspace
from .wsproto import handshake

HERE = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.join(os.path.dirname(HERE), "static")
DATA_DIR = os.path.expanduser(os.environ.get("FORGE_HOME", "~/.forge-ide"))
SESSIONS_DIR = os.path.join(DATA_DIR, "sessions")

DEFAULT_SETTINGS: Dict[str, Any] = {
    "model": "forge-coder",
    "fallback_model": "llama3.1",
    "ollama_url": "http://127.0.0.1:11434",
    "num_ctx": 8192,
    "num_predict": 3072,
    "temperature": 0.2,
    "top_p": 0.9,
    "top_k": 40,
    "repeat_penalty": 1.05,
    "num_thread": 0,
    "keep_alive": "20m",
    "thinking": True,
    "verbose": False,
    "tool_mode": "prompt",
    "max_iterations": 30,
    "auto_approve_commands": False,
    "external_file_protection": True,
    "vision": "auto",                    # auto (Intel GPU, then CPU, then text recognition) | gpu | cpu | ocr
    "vision_cpu_model": "qwen3-vl:2b",   # changes outside the project folder ask first (Cursor's External-File Protection)
    "tab_completions": False,
    "completion_delay_ms": 1200,
    "repo_map_chars": 3000,
    "prewarm": True,
    "font_size": 13,
    "theme": "forge-dark",
    "workspace": os.path.expanduser("~/projects"),
    "recent_workspaces": [],
    # GPU engine: llama.cpp (Vulkan) running on the Android side inside the Forge app
    "engine": "ollama",                 # ollama | gpu
    "gpu_engine_port": 8081,
    "gpu_engine_key": "",               # generated on first use, shared with the app over localhost
    "gpu_model": "forge-coder",         # Ollama model whose weights the app downloads from this server
    "gpu_ctx": 8192,
    "gpu_layers": 99,
}


# Settings a project folder may override in <folder>/.forge/settings.json (like .vscode/settings.json in Cursor).
# Machine-wide things (the engine, Ollama, the GPU engine, how long the model stays loaded) stay user settings.
FOLDER_SETTING_KEYS = {"vision", "model", "temperature", "top_p", "top_k", "repeat_penalty", "num_ctx", "num_predict", "thinking",
                       "verbose", "tool_mode", "max_iterations", "auto_approve_commands", "external_file_protection",
                       "repo_map_chars", "tab_completions", "completion_delay_ms", "font_size", "prewarm"}

MODEL_LOAD_NEED_MB = 5800   # loading llama3.1 8B Q4_K_M needs about 5.2 GB plus headroom


def mem_info() -> Dict[str, int]:
    """MemAvailable, MemTotal and swap use in MB, from /proc/meminfo."""
    out = {"available_mb": 0, "total_mb": 0, "swap_used_mb": 0}
    try:
        vals: Dict[str, int] = {}
        with open("/proc/meminfo", "r", encoding="utf-8") as fh:
            for line in fh:
                k, _, rest = line.partition(":")
                vals[k] = int(rest.split()[0]) // 1024
        out["available_mb"] = vals.get("MemAvailable", 0)
        out["total_mb"] = vals.get("MemTotal", 0)
        out["swap_used_mb"] = vals.get("SwapTotal", 0) - vals.get("SwapFree", 0)
        # Memory the host has taken back from the VM through the virtio balloon (pages of 4 KB).
        inflate = deflate = 0
        with open("/proc/vmstat", "r", encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("balloon_inflate "):
                    inflate = int(line.split()[1])
                elif line.startswith("balloon_deflate "):
                    deflate = int(line.split()[1])
        out["balloon_mb"] = max(0, (inflate - deflate) * 4 // 1024)
    except (OSError, ValueError):
        pass
    return out


MEM_PANIC_MB = 320           # below this much available memory the VM is about to be killed by the host
MEM_WARN_MB = 600


def _load_json(path: str, default: Any) -> Any:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return default


def _save_json(path: str, data: Any) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=1, ensure_ascii=False)
    os.replace(tmp, path)


class State:
    """Everything the server shares between requests."""

    def __init__(self) -> None:
        os.makedirs(SESSIONS_DIR, exist_ok=True)
        self.lock = threading.RLock()
        self.settings: Dict[str, Any] = dict(DEFAULT_SETTINGS)
        self.settings.update(_load_json(os.path.join(DATA_DIR, "config.json"), {}))
        self.client = OllamaClient(self.settings.get("ollama_url", "http://127.0.0.1:11434"))
        self.ollama = self.client
        if not self.settings.get("gpu_engine_key"):
            self.settings["gpu_engine_key"] = uuid.uuid4().hex
            self.save_settings()
        self.gpu_client = LlamaCppClient(f"http://{self.host_ip()}:{int(self.settings.get('gpu_engine_port', 8081))}",
                                         self.settings["gpu_engine_key"])
        self.engine_stats: Dict[str, Any] = {}
        self.checkpoints = Checkpoints(os.path.join(DATA_DIR, "checkpoints"))
        self.vision = VisionManager(self, DATA_DIR)
        self.workspaces: Dict[str, Workspace] = {}
        os.makedirs(os.path.realpath(os.path.expanduser(self.settings["workspace"])), exist_ok=True)
        self.workspace = self.workspace_for(self.settings["workspace"])   # default folder for windows without one
        self.runs: Dict[str, Run] = {}
        self.sessions: Dict[str, dict] = {}
        self.terminals = TerminalManager()
        self.model_checked = False
        self._load_sessions()
        self.last_mem_event: Optional[dict] = None
        self.last_shrink = 0.0
        threading.Thread(target=self._housekeeping, name="housekeeping", daemon=True).start()
        threading.Thread(target=self._memory_watchdog, name="memory-watchdog", daemon=True).start()

    def shrink_vm_memory(self) -> dict:
        """Give memory back to the Googlebook: unload Ollama's model, drop the page cache and compact free memory so
        free page reporting can hand whole blocks back to the host (measured: host went from 3.5 to 6.2 GB free)."""
        import subprocess
        self.unload_model()
        out = {"before": mem_info()}
        try:
            subprocess.run(["sync"], timeout=30)
            subprocess.run(["sudo", "-n", "sysctl", "-q", "-w", "vm.drop_caches=3"], timeout=30, capture_output=True)
            subprocess.run(["sudo", "-n", "sysctl", "-q", "-w", "vm.compact_memory=1"], timeout=60, capture_output=True)
        except (subprocess.TimeoutExpired, OSError) as e:
            out["error"] = str(e)
        out["after"] = mem_info()
        self.last_shrink = time.time()
        return out

    def unload_model(self) -> None:
        """Ask Ollama to drop every loaded model from memory right away (the main model frees about 5 GB)."""
        names = []
        try:
            names = [m.get("name") for m in self.ollama.running() if m.get("name")]
        except OllamaError:
            pass
        for n in names or [self.resolve_model()]:
            try:
                self.ollama._json("POST", "/api/generate", {"model": n, "keep_alive": 0}, timeout=30)
            except OllamaError:
                pass

    def _memory_watchdog(self) -> None:
        """If the VM runs out of memory the host kills it (seen 2026-10-14). Before that happens, stop runs and
        unload the model. Checks every 3 seconds; cheap (two small /proc reads)."""
        low_since = 0.0
        while True:
            time.sleep(3)
            m = mem_info()
            if not m.get("total_mb"):
                continue
            if m["available_mb"] < MEM_PANIC_MB:
                low_since = low_since or time.time()
                if time.time() - low_since < 6:
                    continue        # a short dip is fine; act when it persists
                with self.lock:
                    active = [r for r in self.runs.values() if not r.done]
                for r in active:
                    r.emit("status", text=f"Memory emergency: only {m['available_mb']} MB free in the VM. Stopping and unloading the model so the VM is not killed.")
                    r.cancel.set()
                self.unload_model()
                self.last_mem_event = {"ts": time.time(), "available_mb": m["available_mb"], "balloon_mb": m.get("balloon_mb", 0)}
                print(f"[memory watchdog] unloaded the model: {m['available_mb']} MB available, balloon {m.get('balloon_mb', 0)} MB", flush=True)
                low_since = 0.0
                time.sleep(20)
            else:
                low_since = 0.0
            if self.active_engine() == "gpu" and time.time() - self.last_shrink > 180:
                cached = 0
                try:
                    with open("/proc/meminfo", "r", encoding="utf-8") as fh:
                        for line in fh:
                            if line.startswith("Cached:"):
                                cached = int(line.split()[1]) // 1024
                except OSError:
                    pass
                if cached > 1500:
                    self.shrink_vm_memory()

    # ------------------------------------------------------------ engines
    @staticmethod
    def host_ip() -> str:
        """The Android host as seen from the VM: the default gateway of the VM's network."""
        try:
            import subprocess
            out = subprocess.run(["ip", "-4", "route", "show", "default"], capture_output=True, text=True, timeout=5).stdout
            for tok in out.split():
                if tok.count(".") == 3 and tok != "0.0.0.0":
                    return tok
        except (OSError, subprocess.TimeoutExpired):
            pass
        return "127.0.0.1"   # no route found: the engine can still be reached if it runs in the VM itself

    def active_engine(self) -> str:
        return "gpu" if self.settings.get("engine") == "gpu" else "ollama"

    def client_for(self, engine: Optional[str] = None):
        engine = engine or self.active_engine()
        if engine == "gpu":
            self.gpu_client = LlamaCppClient(f"http://{self.host_ip()}:{int(self.settings.get('gpu_engine_port', 8081))}",
                                             str(self.settings.get("gpu_engine_key", "")))
            return self.gpu_client
        return self.ollama

    def gpu_model_file(self) -> Optional[dict]:
        """Locate the GGUF blob of the chosen Ollama model so the app can download it (no second copy in the VM).
        Ollama's `show` output names the blob in its FROM line."""
        name = str(self.settings.get("gpu_model") or "forge-coder")
        try:
            info = self.ollama.show(name)
        except OllamaError:
            return None
        path = None
        for line in str(info.get("modelfile", "")).splitlines():
            if line.startswith("FROM ") and "/blobs/sha256-" in line:
                path = line[5:].strip()
                break
        if not path or not os.path.isfile(path) or not os.access(path, os.R_OK):
            return None
        det = info.get("details", {})
        digest = "sha256:" + os.path.basename(path).split("sha256-", 1)[-1]
        fname = f"{name.split(':')[0]}-{det.get('parameter_size', '')}-{det.get('quantization_level', '')}.gguf".replace(" ", "")
        return {"path": path, "size": os.path.getsize(path), "digest": digest, "filename": fname,
                "model": name, "quant": det.get("quantization_level"), "params": det.get("parameter_size")}

    # ------------------------------------------------------------ persistence
    def save_settings(self) -> None:
        _save_json(os.path.join(DATA_DIR, "config.json"), self.settings)

    def _load_sessions(self) -> None:
        for name in os.listdir(SESSIONS_DIR):
            if name.endswith(".json"):
                s = _load_json(os.path.join(SESSIONS_DIR, name), None)
                if isinstance(s, dict) and s.get("id"):
                    self.sessions[s["id"]] = s

    def save_session(self, s: dict) -> None:
        s["updated"] = time.time()
        _save_json(os.path.join(SESSIONS_DIR, s["id"] + ".json"), s)

    # ------------------------------------------------------------ folders (one per window, like Cursor)
    def workspace_for(self, path: Optional[str]) -> Workspace:
        p = os.path.realpath(os.path.expanduser(path or self.settings.get("workspace") or "~"))
        if not os.path.isdir(p):
            raise ValueError(f"{p} is not a folder")
        with self.lock:
            w = self.workspaces.get(p)
            if w is None:
                w = Workspace(p, self.checkpoints)
                self.workspaces[p] = w
            return w

    @staticmethod
    def folder_settings_path(root: str) -> str:
        return os.path.join(root, ".forge", "settings.json")

    def folder_overrides(self, root: Optional[str]) -> Dict[str, Any]:
        if not root:
            return {}
        d = _load_json(self.folder_settings_path(root), {})
        return {k: v for k, v in d.items() if k in FOLDER_SETTING_KEYS} if isinstance(d, dict) else {}

    def effective_settings(self, root: Optional[str] = None) -> Dict[str, Any]:
        s = dict(self.settings)
        s.update(self.folder_overrides(root))
        return s

    def settings_payload(self, root: str) -> Dict[str, Any]:
        return {"effective": self.effective_settings(root), "user": self.settings, "folder": self.folder_overrides(root),
                "folder_keys": sorted(FOLDER_SETTING_KEYS), "folder_path": root}

    def new_session(self, title: str = "New chat", folder: Optional[str] = None) -> dict:
        s = {"id": uuid.uuid4().hex[:10], "title": title, "workspace": folder or self.workspace.root, "created": time.time(),
             "updated": time.time(), "messages": [], "transcript": [], "auto_approve": False}
        self.sessions[s["id"]] = s
        self.save_session(s)
        return s

    def set_workspace(self, path: str) -> Workspace:
        """Remember a folder as the most recent one (the default for new windows). Open windows keep their own."""
        w = self.workspace_for(path)
        path = w.root
        self.workspace = w
        self.settings["workspace"] = path
        recent = [p for p in self.settings.get("recent_workspaces", []) if p != path]
        self.settings["recent_workspaces"] = [path] + recent[:11]
        self.save_settings()
        return w

    def resolve_model(self, want: Optional[str] = None) -> str:
        """Use the configured model when it exists in Ollama, otherwise the fallback."""
        want = want or self.settings.get("model", "llama3.1")
        try:
            names = {m.get("name") for m in self.client.list_models()}
            names |= {n.split(":")[0] for n in list(names) if n}
            if want in names or (want + ":latest") in names:
                return want
            fb = self.settings.get("fallback_model", "llama3.1")
            if fb in names or (fb + ":latest") in names:
                return fb
            if names:
                return sorted(n for n in names if n)[0]
        except OllamaError:
            pass
        return want

    def _housekeeping(self) -> None:
        while True:
            time.sleep(60)
            with self.lock:
                old = [rid for rid, r in self.runs.items() if r.done and time.time() - r.started > 3600 * 6]
                for rid in old:
                    self.runs.pop(rid, None)

    # ------------------------------------------------------------ warm-up
    def prewarm(self, session: dict) -> None:
        """Push the stable system prompt through the model so Ollama's prompt cache is hot."""
        root = session.get("workspace")
        try:
            ws = self.workspace_for(root)
        except ValueError:
            return
        settings = self.settings_for_run(ws.root)
        if not settings.get("prewarm", True) or self.memory_block_reason(settings["model"]):
            return

        def go() -> None:
            try:
                client = self.client_for()
                agent = Agent(client, ws, settings, session)
                system = agent._system_prompt("agent")
                msgs = [{"role": "system", "content": system}, {"role": "user", "content": "Reply with the single word ready."}]
                for _ in client.chat_stream(settings["model"], msgs,
                                                 options={**agent._options(), "num_predict": 1},
                                                 keep_alive=str(self.settings.get("keep_alive", "60m"))):
                    pass
            except Exception:  # noqa: BLE001
                pass

        threading.Thread(target=go, name="prewarm", daemon=True).start()

    def model_loaded(self, model: Optional[str] = None) -> bool:
        want = (model or self.resolve_model()).split(":")[0]
        try:
            return any(m.get("name", "").split(":")[0] == want for m in self.ollama.running())
        except OllamaError:
            return False

    def memory_block_reason(self, model: Optional[str] = None) -> Optional[str]:
        """Refuse to load the model when the VM does not have room for it. Loading a 5 GB model into a VM
        that is already nearly full made the host kill the whole VM once (2026-10-14); never again."""
        if self.active_engine() == "gpu":
            paused = self.engine_stats.get("state") == "paused" or self.vision.want()
            if not self.gpu_client.alive() and not paused:
                return ("The GPU engine is not running. Start it from the Forge app (Engine card in Settings) or switch "
                        "the engine back to Ollama in Settings.")
            return None
        return self.memory_block_reason_ollama(model)

    def memory_block_reason_ollama(self, model: Optional[str] = None) -> Optional[str]:
        if self.model_loaded(model):
            return None
        m = mem_info()
        # The VM's memory comes from the Googlebook: when the Forge app reports the host's free memory, check it too.
        host = self.vision.app.get("host_mem_mb") if time.time() - self.vision.app_seen < 60 else None
        if isinstance(host, (int, float)) and 0 <= host < MODEL_LOAD_NEED_MB:
            return (f"Not enough free memory on the Googlebook to load the model in the VM: {int(host)} MB free, about "
                    f"{MODEL_LOAD_NEED_MB} MB needed. Close other apps or stop the GPU engine, then try again.")
        try:
            other = [x.get("name") for x in self.ollama.running()]
        except OllamaError:
            other = []
        if other and m["available_mb"] and m["available_mb"] < MODEL_LOAD_NEED_MB:
            return (f"Another model is loaded ({', '.join(other)}), and there is not enough memory for a second one. "
                    f"Unload it with the command palette (Unload the model from memory), or use the same model in "
                    f"this folder's settings, then try again.")
        if m["available_mb"] and m["available_mb"] < MODEL_LOAD_NEED_MB:
            return (f"Not enough free memory to load the model safely: {m['available_mb']} MB available, about "
                    f"{MODEL_LOAD_NEED_MB} MB needed. Close other programs in the Linux VM (Waydroid, big builds, other "
                    f"models) and try again. This guard exists because an overfull VM gets killed by GooglebookOS.")
        return None

    def model_can_see(self, model: Optional[str]) -> bool:
        """True when an Ollama chat model has its own vision (then images are also passed to it directly)."""
        if not model:
            return False
        cache = getattr(self, "_vision_caps", {})
        if model not in cache:
            try:
                cache[model] = "vision" in (self.ollama.show(model).get("capabilities") or [])
            except OllamaError:
                cache[model] = False
            self._vision_caps = cache
        return bool(cache[model])

    def settings_for_run(self, root: Optional[str] = None) -> dict:
        s = self.effective_settings(root)
        if self.active_engine() == "gpu":
            s["model"] = "gpu-model"
            s["num_ctx"] = int(self.settings.get("gpu_ctx", 8192))
        else:
            s["model"] = self.resolve_model(s.get("model"))
        return s


STATE: Optional[State] = None


class Handler(BaseHTTPRequestHandler):
    server_version = f"ForgeIDE/{__version__}"
    protocol_version = "HTTP/1.1"

    # ------------------------------------------------------------ plumbing
    def log_message(self, fmt: str, *args: Any) -> None:
        if os.environ.get("FORGE_DEBUG"):
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, code: int, data: Any) -> None:
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _error(self, code: int, message: str) -> None:
        self._json(code, {"error": message})

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n > 64 * 1024 * 1024:
            raise ValueError("request too large")
        raw = self.rfile.read(n) if n else b""
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def _ws(self, q: Optional[Dict[str, list]] = None, b: Optional[dict] = None) -> Workspace:
        """The project folder of the window that sent this request (X-Forge-Folder header, or ?folder=)."""
        st = STATE
        assert st is not None
        raw = self.headers.get("X-Forge-Folder")
        val = urllib.parse.unquote(raw) if raw else ""
        if not val and q:
            val = q.get("folder", [""])[0]
        if not val and b:
            val = str(b.get("folder") or "")
        if not val:
            return st.workspace
        # A window whose folder disappeared must not silently work in another folder.
        return st.workspace_for(val)

    def _check_host(self) -> bool:
        """Reject requests whose Host is not one of ours (DNS rebinding protection)."""
        host = (self.headers.get("Host") or "").split(":")[0].lower()
        allowed = {"localhost", "127.0.0.1", "::1", "[::1]", ""}
        allowed |= set(getattr(self.server, "allowed_hosts", set()))
        if host in allowed or host.endswith(".localhost") or host.startswith(("10.", "192.168.", "172.", "100.")):
            return True
        self._error(403, "Forbidden host header")
        return False

    # ------------------------------------------------------------ routing
    def do_GET(self) -> None:  # noqa: N802
        if not self._check_host():
            return
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        try:
            if u.path == "/ws/term":
                return self._ws_terminal(q)
            if u.path.startswith("/api/"):
                return self._api_get(u.path, q)
            return self._static(u.path)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            try:
                self._error(500, str(e))
            except Exception:  # noqa: BLE001
                pass

    def do_POST(self) -> None:  # noqa: N802
        if not self._check_host():
            return
        origin = self.headers.get("Origin")
        if origin:
            oh = urllib.parse.urlparse(origin).hostname or ""
            hh = (self.headers.get("Host") or "").split(":")[0]
            if oh != hh:
                return self._error(403, "Cross-origin request blocked")
        u = urllib.parse.urlparse(self.path)
        try:
            body = self._body()
        except (ValueError, json.JSONDecodeError) as e:
            return self._error(400, f"bad JSON body: {e}")
        try:
            self._api_post(u.path, body)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except (ValueError, FileNotFoundError, PermissionError, IsADirectoryError, NotADirectoryError) as e:
            self._error(400, str(e))
        except OllamaError as e:
            self._error(502, str(e))
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            self._error(500, str(e))

    # ------------------------------------------------------------ static files
    def _static(self, path: str) -> None:
        if path in ("", "/"):
            path = "/index.html"
        rel = posixpath.normpath(urllib.parse.unquote(path)).lstrip("/")
        full = os.path.realpath(os.path.join(STATIC, rel))
        if not full.startswith(os.path.realpath(STATIC) + os.sep) or not os.path.isfile(full):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        ctype, _ = mimetypes.guess_type(full)
        if full.endswith(".js"):
            ctype = "application/javascript"
        elif full.endswith(".css"):
            ctype = "text/css"
        elif full.endswith(".html"):
            ctype = "text/html; charset=utf-8"
        size = os.path.getsize(full)
        self.send_response(200)
        self.send_header("Content-Type", ctype or "application/octet-stream")
        self.send_header("Content-Length", str(size))
        if "/vendor/" in full:
            self.send_header("Cache-Control", "public, max-age=604800")
        else:
            self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        with open(full, "rb") as fh:
            while True:
                chunk = fh.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    # ------------------------------------------------------------ GET api
    def _api_get(self, path: str, q: Dict[str, list]) -> None:
        st = STATE
        assert st is not None
        folder_missing = None
        try:
            ws = self._ws(q)
        except ValueError as e:
            if path not in ("/api/status", "/api/dirs", "/api/events", "/api/run", "/api/engine/config", "/api/engine/model",
                            "/api/engine/status", "/api/settings", "/api/image", "/api/image/analysis", "/api/engine/control",
                            "/api/engine/vision_file", "/api/vision/status"):
                return self._error(400, f"This window's folder is not available ({e}). Open another folder.")
            folder_missing = str(e)
            ws = st.workspace

        def arg(name: str, default: str = "") -> str:
            return q.get(name, [default])[0]

        if path == "/api/status":
            alive = st.client.alive()
            running = []
            try:
                running = st.client.running() if alive else []
            except OllamaError:
                pass
            gpu = st.gpu_client.health() if st.active_engine() == "gpu" or arg("engine") else {}
            return self._json(200, {"version": __version__, "ollama": alive, "workspace": ws.root, "engine": st.active_engine(),
                                    "gpu_engine": {"url": st.gpu_client.base_url, "health": gpu},
                                    "workspace_name": os.path.basename(ws.root) or ws.root,
                                    "model": st.resolve_model(st.effective_settings(ws.root).get("model")),
                                    "running": running, "pid": os.getpid(), "default_folder": st.workspace.root,
                                    "folder_missing": folder_missing,
                                    "host_mem_mb": (st.vision.app.get("host_mem_mb") if time.time() - st.vision.app_seen < 60 else None),
                                    "home": os.path.expanduser("~"), "mem": mem_info(), "mem_event": st.last_mem_event})
        if path == "/api/settings":
            return self._json(200, st.settings_payload(ws.root) if not folder_missing else
                              {**st.settings_payload(ws.root), "folder": {}, "effective": dict(st.settings)})
        if path == "/api/models":
            models = st.ollama.list_models()
            return self._json(200, {"models": models, "active": st.resolve_model(st.effective_settings(ws.root).get("model")),
                                    "running": st.ollama.running()})
        if path == "/api/tree":
            rel = arg("path", ".")
            p = ws.resolve(rel, must_exist=True)
            items = []
            with os.scandir(p) as it:
                for e in sorted(it, key=lambda e: (not e.is_dir(follow_symlinks=False), e.name.lower())):
                    if e.name == ".forge-trash":
                        continue
                    is_dir = e.is_dir(follow_symlinks=False)
                    try:
                        size = 0 if is_dir else e.stat(follow_symlinks=False).st_size
                    except OSError:
                        size = 0
                    items.append({"name": e.name, "path": ws.relpath(e.path), "dir": is_dir, "size": size})
            return self._json(200, {"path": rel, "items": items})
        if path == "/api/files":
            files = ws.all_files(refresh=arg("refresh") == "1")
            needle = arg("q").lower()
            if needle:
                files = [f for f in files if _fuzzy(needle, f.lower())]
                files.sort(key=lambda f: (needle not in os.path.basename(f).lower(), len(f)))
            return self._json(200, {"files": files[:300], "total": len(files)})
        if path == "/api/file":
            rel = arg("path")
            text = ws.read_text(rel)
            p = ws.resolve(rel)
            return self._json(200, {"path": rel, "content": text, "mtime": os.path.getmtime(p), "size": len(text)})
        if path == "/api/search":
            return self._json(200, {"result": ws.search(arg("q"), arg("path", ".") or ".", arg("glob") or None,
                                                        arg("regex") == "1", max_results=400)})
        if path == "/api/sessions":
            items = sorted(st.sessions.values(), key=lambda s: -s.get("updated", 0))
            if arg("all") != "1":
                items = [s for s in items if s.get("workspace") == ws.root]
            return self._json(200, {"folder": ws.root, "sessions": [{"id": s["id"], "title": s["title"], "updated": s["updated"],
                                    "workspace": s.get("workspace"), "messages": len(s.get("messages", []))}
                                    for s in items[:300]]})
        if path == "/api/session":
            s = st.sessions.get(arg("id"))
            if not s:
                return self._error(404, "no such session")
            return self._json(200, s)
        if path == "/api/events":
            return self._sse(arg("run_id"), int(arg("since", "0") or 0))
        if path == "/api/run":
            r = st.runs.get(arg("run_id"))
            if not r:
                return self._error(404, "no such run")
            return self._json(200, {"id": r.id, "status": r.status, "done": r.done, "events": len(r.events),
                                    "approval": r.approval})
        if path == "/api/changes":
            # One run's changes with file contents (for diffs), or a light list of this folder's runs (or all folders).
            rid = arg("run_id")
            runs = st.checkpoints.list_runs(None if (rid or arg("all") == "1") else ws.root)
            if rid:
                runs = [r for r in runs if r["run_id"] == rid]
            out = []
            for r in runs[:80]:
                for ch in r["files"]:
                    item = {"run_id": r["run_id"], "path": ch.path, "kind": ch.kind, "ts": ch.ts, "revertable": ch.revertable,
                            "workspace": r["workspace"], "title": r["title"], "run_ts": r["ts"], "session_id": r["session_id"]}
                    if rid:
                        item["before"] = ch.before
                        item["after"] = ch.after
                    out.append(item)
            return self._json(200, {"changes": out, "folder": ws.root})
        if path == "/api/git":
            return self._json(200, _git_status(ws.root))
        if path == "/api/git/diff":
            rel = arg("path")
            cur = ""
            try:
                cur = ws.read_text(rel)
            except Exception:  # noqa: BLE001
                cur = ""
            head = _git_show_head(ws.root, rel)
            return self._json(200, {"path": rel, "original": head, "modified": cur})
        if path == "/api/git/log":
            return self._json(200, {"log": _git(ws.root, ["log", "--oneline", "-n", "30"])[1]})
        if path == "/api/rules":
            return self._json(200, {"global": _read_text(os.path.join(DATA_DIR, "rules.md")),
                                    "project": _read_text(os.path.join(ws.root, ".forge", "rules.md")),
                                    "project_path": ".forge/rules.md"})
        if path == "/api/notepads":
            return self._json(200, {"notepads": _load_json(os.path.join(DATA_DIR, "notepads.json"), [])})
        if path == "/api/engine/status":
            h = st.gpu_client.health()
            props = st.gpu_client.props() if h.get("status") in ("ok", "no slot available") else {}
            mf = st.gpu_model_file()
            return self._json(200, {"engine": st.active_engine(), "url": st.gpu_client.base_url, "health": h,
                                    "props": {k: props.get(k) for k in ("model_path", "build_info", "total_slots", "default_generation_settings") if k in props},
                                    "model_file": {k: v for k, v in (mf or {}).items() if k != "path"}})
        if path == "/api/image":
            found = st.vision.images.find(arg("id"))
            if not found:
                return self._error(404, "no such image")
            fp, mime = found
            size = os.path.getsize(fp)
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "private, max-age=31536000, immutable")
            self.end_headers()
            with open(fp, "rb") as fh:
                self.wfile.write(fh.read())
            return None
        if path == "/api/image/analysis":
            return self._json(200, st.vision.images.cached_analysis(arg("id")))
        if path == "/api/engine/control":
            report = {k: arg(k) for k in ("vstate", "vtier", "vdetail") if arg(k)}
            try:
                report["host_mem_mb"] = int(arg("host_mem_mb") or -1)
            except ValueError:
                pass
            return self._json(200, st.vision.control(report, float(arg("wait", "20") or 20)))
        if path == "/api/engine/vision_file":
            fp = st.vision.file_path(arg("name"))
            if not fp:
                return self._error(404, "no such vision model file")
            size = os.path.getsize(fp)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(fp, "rb") as fh:
                while True:
                    chunk = fh.read(1024 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return None
        if path == "/api/vision/status":
            return self._json(200, st.vision.status())
        if path == "/api/engine/config":
            # Read by the Forge app (host side) to launch llama-server with the right arguments.
            mf = st.gpu_model_file()
            return self._json(200, {"host_ip": st.host_ip(), "port": int(st.settings.get("gpu_engine_port", 8081)),
                                    "api_key": st.settings.get("gpu_engine_key", ""), "ctx": int(st.settings.get("gpu_ctx", 8192)),
                                    "gpu_layers": int(st.settings.get("gpu_layers", 99)),
                                    "model": None if not mf else {"filename": mf["filename"], "size": mf["size"], "digest": mf["digest"],
                                                                  "download": "/api/engine/model"}})
        if path == "/api/engine/model":
            mf = st.gpu_model_file()
            if not mf:
                return self._error(404, "model file not found in Ollama's store")
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(mf["size"]))
            self.send_header("Content-Disposition", f'attachment; filename="{mf["filename"]}"')
            self.send_header("X-Digest", mf["digest"])
            self.end_headers()
            with open(mf["path"], "rb") as fh:
                while True:
                    chunk = fh.read(1024 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return None
        if path == "/api/mcp":
            from .mcp import MCP, CONFIG as MCP_CONFIG
            return self._json(200, {"servers": MCP.get().status(), "config_path": MCP_CONFIG,
                                    "config": _read_text(MCP_CONFIG) or '{\n  "servers": {}\n}\n'})
        if path == "/api/dirs":
            # Folder browser for "Open workspace".
            p = os.path.realpath(os.path.expanduser(arg("path") or "~"))
            if not os.path.isdir(p):
                return self._error(400, "not a folder")
            dirs = []
            try:
                with os.scandir(p) as it:
                    for e in sorted(it, key=lambda e: e.name.lower()):
                        if e.is_dir(follow_symlinks=False) and not e.name.startswith("."):
                            dirs.append(e.name)
            except PermissionError:
                pass
            return self._json(200, {"path": p, "parent": os.path.dirname(p), "dirs": dirs,
                                    "recent": st.settings.get("recent_workspaces", [])})
        return self._error(404, "unknown API endpoint")

    # ------------------------------------------------------------ POST api
    def _api_post(self, path: str, b: dict) -> None:
        st = STATE
        assert st is not None
        try:
            ws = self._ws(None, b)
        except ValueError:
            # A window whose folder disappeared may still open another folder and use machine-wide actions.
            if not (path in ("/api/workspace", "/api/approve", "/api/cancel", "/api/unload", "/api/notepads",
                             "/api/terminal/kill", "/api/image") or path.startswith("/api/engine/")
                    or (path == "/api/settings" and b.get("_scope", "user") == "user")):
                raise
            ws = st.workspace
        if path == "/api/settings":
            scope = str(b.pop("_scope", "user"))
            unset = [str(k) for k in (b.pop("_unset", []) or [])]
            if scope == "folder":
                fp = st.folder_settings_path(ws.root)
                cur = _load_json(fp, {})
                cur = cur if isinstance(cur, dict) else {}
                for k, v in b.items():
                    if k in FOLDER_SETTING_KEYS:
                        cur[k] = v
                for k in unset:
                    cur.pop(k, None)
                _save_json(fp, cur)
            else:
                allowed = set(DEFAULT_SETTINGS) - {"recent_workspaces", "workspace"}
                for k, v in b.items():
                    if k in allowed:
                        st.settings[k] = v
                st.save_settings()
                st.client = OllamaClient(st.settings.get("ollama_url", "http://127.0.0.1:11434"))
                st.ollama = st.client
            return self._json(200, st.settings_payload(ws.root))
        if path == "/api/workspace":
            p = str(b.get("path", "")).strip()
            if b.get("create") and p and not os.path.exists(os.path.expanduser(p)):
                os.makedirs(os.path.expanduser(p), exist_ok=True)
            w = st.set_workspace(p)
            return self._json(200, {"workspace": w.root})
        if path == "/api/file":
            rel = str(b["path"])
            content = str(b.get("content", ""))
            msg = ws.write_file(rel, content, run_id=None)
            check = msg.split("\n", 1)[1] if "\n" in msg else ""
            return self._json(200, {"ok": True, "path": rel, "mtime": os.path.getmtime(ws.resolve(rel)), "check": check.strip()})
        if path == "/api/file/new":
            rel = str(b["path"])
            p = ws.resolve(rel)
            if os.path.exists(p):
                raise ValueError("already exists")
            if b.get("dir"):
                os.makedirs(p, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(p) or ws.root, exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write("")
            ws._file_cache = (0.0, [])
            return self._json(200, {"ok": True, "path": ws.relpath(p)})
        if path == "/api/file/rename":
            src = ws.resolve(str(b["path"]), must_exist=True)
            dst = ws.resolve(str(b["new_path"]))
            if os.path.exists(dst):
                raise ValueError("target already exists")
            os.makedirs(os.path.dirname(dst) or ws.root, exist_ok=True)
            os.rename(src, dst)
            ws._file_cache = (0.0, [])
            return self._json(200, {"ok": True, "path": ws.relpath(dst)})
        if path == "/api/file/trash":
            msg = ws.delete_path(str(b["path"]))
            return self._json(200, {"ok": True, "message": msg})
        if path == "/api/session/new":
            s = st.new_session(str(b.get("title") or "New chat"), ws.root)
            st.prewarm(s)
            return self._json(200, s)
        if path == "/api/session/delete":
            sid = str(b.get("id"))
            s = st.sessions.pop(sid, None)
            if s:
                p = os.path.join(SESSIONS_DIR, sid + ".json")
                trash = os.path.join(DATA_DIR, "trash")
                os.makedirs(trash, exist_ok=True)
                if os.path.exists(p):
                    os.replace(p, os.path.join(trash, sid + ".json"))
            return self._json(200, {"ok": True})
        if path == "/api/session/rename":
            s = st.sessions.get(str(b.get("id")))
            if not s:
                return self._error(404, "no such session")
            s["title"] = str(b.get("title") or s["title"])[:120]
            st.save_session(s)
            return self._json(200, {"ok": True})
        if path == "/api/session/transcript":
            s = st.sessions.get(str(b.get("id")))
            if not s:
                return self._error(404, "no such session")
            s["transcript"] = b.get("transcript", [])
            if b.get("title"):
                s["title"] = str(b["title"])[:120]
            st.save_session(s)
            return self._json(200, {"ok": True})
        if path == "/api/session/reset":
            s = st.sessions.get(str(b.get("id")))
            if not s:
                return self._error(404, "no such session")
            s["messages"] = []
            s["repo_map"] = None
            st.save_session(s)
            return self._json(200, {"ok": True})
        if path == "/api/chat":
            return self._start_chat(b)
        if path == "/api/approve":
            r = st.runs.get(str(b.get("run_id")))
            if not r:
                return self._error(404, "no such run")
            ok = r.resolve_approval(str(b.get("approval_id")), str(b.get("decision", "deny")), b.get("arguments"))
            return self._json(200, {"ok": ok})
        if path == "/api/cancel":
            r = st.runs.get(str(b.get("run_id")))
            if r:
                r.cancel.set()
            return self._json(200, {"ok": bool(r)})
        if path == "/api/inline_edit":
            run = Run("inline:" + str(b.get("session_id") or ""), "edit")
            st.runs[run.id] = run
            settings = st.settings_for_run(ws.root)
            t = threading.Thread(target=inline_edit_stream, name="inline-edit", daemon=True,
                                 args=(st.client_for(), settings, run, str(b.get("path", "")), str(b.get("language", "")),
                                       str(b.get("selection", "")), str(b.get("instruction", "")),
                                       str(b.get("before", "")), str(b.get("after", ""))))
            run.thread = t
            t.start()
            return self._json(200, {"run_id": run.id})
        if path == "/api/complete":
            text = complete(st.client_for(), st.settings_for_run(ws.root), str(b.get("path", "")), str(b.get("language", "")),
                            str(b.get("prefix", "")), str(b.get("suffix", "")))
            return self._json(200, {"text": text})
        if path == "/api/revert":
            rid = str(b["run_id"])
            msg = self._run_ws(rid, ws).revert(rid, str(b["path"]))
            return self._json(200, {"ok": True, "message": msg})
        if path == "/api/revert_all":
            rid = str(b["run_id"])
            rws = self._run_ws(rid, ws)
            msgs = []
            for ch in list(st.checkpoints.changes(rid)):
                if ch.kind != "reverted":
                    try:
                        msgs.append(rws.revert(rid, ch.path))
                    except Exception as e:  # noqa: BLE001
                        msgs.append(f"{ch.path}: {e}")
            return self._json(200, {"ok": True, "messages": msgs})
        if path == "/api/git/stage":
            paths = b.get("paths") or []
            code, out = _git(ws.root, ["add", "-A"] if not paths else ["add", "--"] + [str(x) for x in paths])
            return self._json(200, {"ok": code == 0, "output": out})
        if path == "/api/git/unstage":
            paths = b.get("paths") or []
            code, out = _git(ws.root, ["reset", "-q"] if not paths else ["reset", "-q", "--"] + [str(x) for x in paths])
            return self._json(200, {"ok": code == 0, "output": out})
        if path == "/api/git/discard":
            rel = str(b.get("path", ""))
            if not rel:
                raise ValueError("path required")
            # Keep a checkpoint copy so the discard can be undone from the AI Changes view.
            try:
                before = ws.read_text(rel)
            except Exception:  # noqa: BLE001
                before = None
            code, out = _git(ws.root, ["checkout", "--", rel])
            if code == 0 and before is not None:
                after = ws.read_text(rel) if os.path.exists(ws.resolve(rel)) else None
                did = "git-discard-" + uuid.uuid4().hex[:8]
                st.checkpoints.begin(did, ws.root, "", f"Discarded with git: {rel}")
                st.checkpoints.record(did, rel, before, after)
            return self._json(200, {"ok": code == 0, "output": out})
        if path == "/api/git/commit":
            msg = str(b.get("message", "")).strip()
            if not msg:
                raise ValueError("commit message required")
            if b.get("stage_all"):
                _git(ws.root, ["add", "-A"])
            code, out = _git(ws.root, ["-c", "user.name=" + str(b.get("name") or _git(ws.root, ["config", "user.name"])[1].strip() or "Forge"),
                                       "-c", "user.email=" + str(b.get("email") or _git(ws.root, ["config", "user.email"])[1].strip() or "forge@localhost"),
                                       "commit", "-m", msg])
            return self._json(200, {"ok": code == 0, "output": out})
        if path == "/api/git/push":
            code, out = _git(ws.root, ["push"], timeout=120)
            return self._json(200, {"ok": code == 0, "output": out})
        if path == "/api/git/pull":
            code, out = _git(ws.root, ["pull", "--ff-only"], timeout=120)
            return self._json(200, {"ok": code == 0, "output": out})
        if path == "/api/git/init":
            code, out = _git(ws.root, ["init"])
            return self._json(200, {"ok": code == 0, "output": out})
        if path == "/api/git/commit_message":
            settings = st.settings_for_run(ws.root)
            blocked = st.memory_block_reason(settings["model"])
            if blocked:
                return self._error(503, blocked)
            code, diff = _git(ws.root, ["diff", "--cached", "--no-color", "--stat", "-p"])
            if not diff.strip():
                code, diff = _git(ws.root, ["diff", "--no-color", "--stat", "-p"])
            if not diff.strip():
                return self._json(200, {"message": "", "note": "no changes to describe"})
            prompt = ("Write a git commit message for the following diff. First line: a short imperative summary under 70 "
                      "characters. Then a blank line and two to five short bullet points if the change has several parts. "
                      "Plain text only, no markdown fences, no quotes around it.\n\n```diff\n" + diff[:14000] + "\n```")
            r = st.client_for().generate(settings["model"], prompt, options={"num_ctx": 8192, "temperature": 0.2, "num_predict": 200},
                                   keep_alive=str(settings.get("keep_alive", "20m")), timeout=600,
                                   system="You are a senior engineer writing precise, conventional git commit messages.")
            text = str(r.get("response", "")).strip().strip("`").strip()
            return self._json(200, {"message": text})
        if path == "/api/rules":
            if "global" in b:
                _write_text(os.path.join(DATA_DIR, "rules.md"), str(b["global"]))
            if "project" in b:
                _write_text(os.path.join(ws.root, ".forge", "rules.md"), str(b["project"]))
            for sess in st.sessions.values():
                sess["repo_map_ts"] = 0   # rules changed: rebuild the system prompt on the next message
            return self._json(200, {"ok": True})
        if path == "/api/notepads":
            pads = b.get("notepads")
            if not isinstance(pads, list):
                raise ValueError("notepads must be a list")
            _save_json(os.path.join(DATA_DIR, "notepads.json"), pads[:200])
            return self._json(200, {"ok": True})
        if path == "/api/mcp/config":
            from .mcp import MCP, CONFIG as MCP_CONFIG
            text = str(b.get("config", ""))
            json.loads(text)   # must be valid JSON
            _write_text(MCP_CONFIG, text)
            MCP.get().reload()
            return self._json(200, {"ok": True, "servers": MCP.get().status()})
        if path == "/api/mcp/connect":
            from .mcp import MCP
            return self._json(200, {"servers": MCP.get().connect_all()})
        if path == "/api/terminal_command":
            settings = st.settings_for_run(ws.root)
            blocked = st.memory_block_reason(settings["model"])
            if blocked:
                return self._error(503, blocked)
            instruction = str(b.get("instruction", "")).strip()
            if not instruction:
                raise ValueError("instruction required")
            prompt = (f"Operating system: Debian Linux in a VM, shell: bash, current folder: {b.get('cwd') or ws.root}.\n"
                      f"Task: {instruction}\n\nReply with exactly one shell command line that does this, and nothing else: "
                      f"no explanation, no markdown, no backticks.")
            r = st.client_for().generate(settings["model"], prompt, options={"num_ctx": 4096, "temperature": 0.1, "num_predict": 120,
                                                                      "stop": ["\n\n", "```"]},
                                   keep_alive=str(settings.get("keep_alive", "20m")), timeout=300,
                                   system="You are an expert Linux shell user. You answer with one command line only.")
            cmd = str(r.get("response", "")).strip().strip("`").strip()
            cmd = cmd.split("\n")[0].strip()
            if cmd.startswith("$ "):
                cmd = cmd[2:]
            from .tools import is_dangerous_command
            return self._json(200, {"command": cmd, "dangerous": is_dangerous_command(cmd)})
        if path == "/api/ollama/pull":
            return self._pull_model(str(b.get("model", "")))
        if path == "/api/image":
            import base64 as _b64
            data = str(b.get("data", ""))
            if data.startswith("data:"):
                data = data.split(",", 1)[-1]
            try:
                raw = _b64.b64decode(data, validate=False)
            except ValueError:
                raise ValueError("the image data is not valid base64")
            meta = st.vision.images.save(raw, str(b.get("name") or ""))
            meta["url"] = "/api/image?id=" + meta["id"]
            return self._json(200, meta)
        if path == "/api/image/analyze":
            # What the vision helper sees in an image (also used to test the vision path without the chat model).
            settings = st.effective_settings(ws.root)
            img_id = str(b.get("id", ""))
            question = str(b.get("question") or "").strip()
            notes: list = []
            t0 = time.time()
            if question:
                found = st.vision.images.find(img_id)
                if not found:
                    return self._error(404, "no such image")
                answer, source = st.vision.ask(found[0], found[1], question, settings, notes.append)
                return self._json(200, {"answer": answer, "source": source, "notes": notes, "seconds": round(time.time() - t0, 1)})
            res = st.vision.analyze(img_id, settings, notes.append)
            return self._json(200, {**res, "notes": notes, "seconds": round(time.time() - t0, 1)})
        if path == "/api/vision/stop":
            st.vision.holds = 0
            st.vision.release()
            return self._json(200, {"ok": True})
        if path == "/api/engine/vision_status":
            st.vision.report({k: b.get(k) for k in ("vstate", "vtier", "vdetail", "host_mem_mb") if k in b})
            return self._json(200, {"ok": True})
        if path == "/api/engine/prepare":
            return self._json(200, st.shrink_vm_memory())
        if path == "/api/engine/select":
            eng = "gpu" if str(b.get("engine")) == "gpu" else "ollama"
            if eng == "gpu" and not st.gpu_client.alive():
                return self._error(503, "The GPU engine is not reachable. Start it in the Forge app first.")
            st.settings["engine"] = eng
            st.save_settings()
            st.vision.wanted_until = 0.0
            if eng == "gpu":
                st.unload_model()      # the VM must not hold a second copy of the model
            for sess in st.sessions.values():
                sess["messages"] = []  # a different engine means a different prompt cache; start the context fresh
            return self._json(200, {"engine": eng})
        if path == "/api/engine/stats":
            st.engine_stats = dict(b)
            return self._json(200, {"ok": True})
        if path == "/api/terminal/kill":
            st.terminals.kill(str(b.get("id", "")))
            return self._json(200, {"ok": True})
        if path == "/api/unload":
            st.unload_model()
            return self._json(200, {"ok": True})
        if path == "/api/prewarm":
            s = st.sessions.get(str(b.get("session_id")))
            if s:
                st.prewarm(s)
            return self._json(200, {"ok": bool(s)})
        return self._error(404, "unknown API endpoint")

    # ------------------------------------------------------------ chat
    def _run_ws(self, run_id: str, fallback: Workspace) -> Workspace:
        """The project folder a checkpointed run belongs to (falls back to the window's folder)."""
        st = STATE
        assert st is not None
        root = st.checkpoints.run_meta(run_id).get("workspace")
        try:
            return st.workspace_for(root) if root else fallback
        except ValueError:
            return fallback

    def _start_chat(self, b: dict) -> None:
        st = STATE
        assert st is not None
        win_ws = self._ws(None, b)
        sid = str(b.get("session_id") or "")
        s = st.sessions.get(sid)
        if not s:
            s = st.new_session("New chat", win_ws.root)
        if not s.get("workspace"):
            s["workspace"] = win_ws.root
        try:
            ws = st.workspace_for(s["workspace"])     # a chat always works in its own project folder
        except ValueError:
            return self._error(400, f"This chat's folder no longer exists: {s.get('workspace')}")
        text = str(b.get("message", "")).strip()
        if not text:
            return self._error(400, "empty message")
        mode = str(b.get("mode", "agent"))
        if mode not in ("agent", "ask", "plan"):
            mode = "agent"
        active = [r for r in st.runs.values() if r.session_id == s["id"] and not r.done]
        if active:
            return self._error(409, "a run is already active for this session")
        fallback_note = None
        if st.active_engine() == "gpu" and not st.gpu_client.alive():
            # Never load the 5 GB CPU model automatically here: the engine usually stopped because memory was short.
            paused = st.engine_stats.get("state") == "paused" or st.vision.want()
            if not paused:
                return self._error(503, "The GPU engine is not running. Start it from the Forge app (Settings, GPU engine "
                                        "card), or switch the engine to Ollama in Settings.")
        if fallback_note:
            settings = st.effective_settings(ws.root)
            settings["model"] = st.resolve_model(settings.get("model"))
            blocked = st.memory_block_reason_ollama(settings["model"])
        else:
            settings = st.settings_for_run(ws.root)
            blocked = st.memory_block_reason(settings["model"])
        if blocked:
            return self._error(503, blocked)
        if s["title"] in ("New chat", "") and text:
            s["title"] = text[:60]
        run = Run(s["id"], mode)
        st.runs[run.id] = run
        st.checkpoints.begin(run.id, ws.root, s["id"], text)
        engine = "ollama" if fallback_note else st.active_engine()
        if s.get("engine_used") not in (None, engine):
            s["messages"] = []          # different engine, different prompt cache: fresh context
        s["engine_used"] = engine
        agent = Agent(st.ollama if fallback_note else st.client_for(), ws, settings, s)
        agent.vision = st.vision
        if engine == "ollama":
            settings["native_images"] = st.model_can_see(settings.get("model"))

        def go() -> None:
            if fallback_note:
                run.emit("status", text=fallback_note)
            agent.run_turn(run, text, list(b.get("attachments") or []), mode)
            st.save_session(s)

        run.thread = threading.Thread(target=go, name=f"run-{run.id}", daemon=True)
        run.thread.start()
        return self._json(200, {"run_id": run.id, "session_id": s["id"], "model": settings["model"], "folder": ws.root})

    def _sse(self, run_id: str, since: int) -> None:
        st = STATE
        assert st is not None
        r = st.runs.get(run_id)
        if not r:
            return self._error(404, "no such run")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Connection", "close")
        self.end_headers()
        idx = since
        last_ping = time.monotonic()
        while True:
            evs = r.wait_events(idx, timeout=10)
            for ev in evs:
                payload = json.dumps(ev, ensure_ascii=False)
                self.wfile.write(f"id: {idx}\ndata: {payload}\n\n".encode())
                idx += 1
            self.wfile.flush()
            if r.done and idx >= len(r.events):
                self.wfile.write(f"data: {json.dumps({'type': 'end', 'status': r.status, 'run_id': r.id})}\n\n".encode())
                self.wfile.flush()
                return
            if time.monotonic() - last_ping > 10:
                self.wfile.write(b": ping\n\n")
                self.wfile.flush()
                last_ping = time.monotonic()

    def _pull_model(self, model: str) -> None:
        st = STATE
        assert st is not None
        if not model:
            return self._error(400, "model required")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            for obj in st.client.stream("/api/pull", {"name": model}, total_timeout=7200, idle_timeout=600):
                self.wfile.write(f"data: {json.dumps(obj)}\n\n".encode())
                self.wfile.flush()
        except OllamaError as e:
            self.wfile.write(f"data: {json.dumps({'error': str(e)})}\n\n".encode())
        self.wfile.write(b"data: {\"done\": true}\n\n")
        self.wfile.flush()

    # ------------------------------------------------------------ terminal
    def _ws_terminal(self, q: Dict[str, list]) -> None:
        st = STATE
        assert st is not None
        # Browsers send Origin on WebSocket upgrades and WebSockets are not covered by CORS, so without this check
        # any web page open in Chrome could connect to ws://localhost:8765 and get a shell.
        origin = self.headers.get("Origin")
        if origin and urllib.parse.urlparse(origin).netloc.lower() != (self.headers.get("Host") or "").lower():
            self.send_response(403)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        try:
            folder = self._ws(q).root
        except ValueError:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        sock = handshake(self)
        if sock is None:
            return
        term_id = q.get("id", ["main"])[0][:40]
        cols = int(q.get("cols", ["120"])[0] or 120)
        rows = int(q.get("rows", ["30"])[0] or 30)
        st.terminals.serve(sock, term_id, folder, cols, rows)


def _fuzzy(needle: str, hay: str) -> bool:
    if needle in hay:
        return True
    i = 0
    for ch in hay:
        if i < len(needle) and ch == needle[i]:
            i += 1
    return i == len(needle)


def _read_text(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return ""


def _write_text(path: str, text: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def _git(root: str, args: list, timeout: float = 30.0) -> tuple:
    import subprocess
    try:
        r = subprocess.run(["git", "-C", root] + args, capture_output=True, text=True, timeout=timeout,
                           env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    except (subprocess.TimeoutExpired, OSError) as e:
        return 1, f"git failed: {e}"
    return r.returncode, (r.stdout + r.stderr)[:200000]


def _git_show_head(root: str, rel: str) -> str:
    code, out = _git(root, ["show", "HEAD:" + rel])
    return out if code == 0 else ""


def _git_status(root: str) -> dict:
    import subprocess
    if not os.path.isdir(os.path.join(root, ".git")):
        return {"repo": False}
    try:
        branch = subprocess.run(["git", "-C", root, "rev-parse", "--abbrev-ref", "HEAD"], capture_output=True, text=True, timeout=10).stdout.strip()
        status = subprocess.run(["git", "-C", root, "status", "--porcelain=v1"], capture_output=True, text=True, timeout=15).stdout
    except (subprocess.TimeoutExpired, OSError) as e:
        return {"repo": True, "error": str(e)}
    files = []
    for line in status.splitlines():
        if len(line) > 3:
            x, y = line[0], line[1]
            files.append({"status": (line[:2].strip() or "M"), "path": line[3:], "staged": x not in (" ", "?"),
                          "unstaged": y != " " or x == "?"})
    ahead = ""
    try:
        ahead = subprocess.run(["git", "-C", root, "status", "-sb"], capture_output=True, text=True, timeout=10).stdout.splitlines()[0]
    except (subprocess.TimeoutExpired, OSError, IndexError):
        pass
    return {"repo": True, "branch": branch, "files": files, "summary": ahead}


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 64
    allowed_hosts: set = set()


def main(argv: Optional[list] = None) -> int:
    import argparse
    ap = argparse.ArgumentParser(prog="forge-ide", description="Forge IDE: a local Cursor-style AI coding IDE for Ollama.")
    ap.add_argument("--host", default=os.environ.get("FORGE_HOST", "0.0.0.0"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("FORGE_PORT", "8765")))
    ap.add_argument("--workspace", "-w", default=None, help="folder to open")
    args = ap.parse_args(argv)
    global STATE
    STATE = State()
    if args.workspace:
        STATE.set_workspace(args.workspace)
    if not os.path.isdir(STATE.workspace.root):
        os.makedirs(STATE.workspace.root, exist_ok=True)
    srv = Server((args.host, args.port), Handler)
    try:
        srv.allowed_hosts = {socket.gethostname().lower()} | set(_local_ips())
    except OSError:
        srv.allowed_hosts = set()
    print(f"Forge IDE {__version__} listening on http://{args.host}:{args.port}  workspace={STATE.workspace.root}", flush=True)
    print("Open http://localhost:%d in Chrome (allow the port in the Terminal app's port forwarding if asked)." % args.port, flush=True)
    try:
        srv.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
    return 0


def _local_ips() -> list:
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None):
            ips.append(info[4][0])
    except OSError:
        pass
    return ips


if __name__ == "__main__":
    sys.exit(main())
