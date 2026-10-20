"""Forge IDE HTTP server (standard library only).

Serves the native Forge app (and the forge-ide command) a JSON API: files, search, agent runs streamed over
Server-Sent Events, approvals, sessions, checkpoints, inline edit, completions, and WebSockets for the terminal,
the language servers, the debugger and the device screens. It answers only this VM's own loopback connections.
"""
from __future__ import annotations

import json
import re
import os
import subprocess
import posixpath
import socket
import sys
import threading
import time
import traceback
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import List, Any, Dict, Optional

from . import __version__
from .agent import Agent, Run, complete, inline_edit_stream
from .llamacpp_client import LlamaCppClient
from .vision import VisionManager
from .features import Attachments, Docs, Hooks, Memories, BUILTIN_COMMANDS, custom_commands
from .ollama_client import OllamaClient, OllamaError
from .terminal import TerminalManager
from .tools import Checkpoints, Workspace
from .wsproto import handshake

HERE = os.path.dirname(os.path.abspath(__file__))
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
    "keep_alive": "5m",
    "thinking": True,
    "verbose": False,
    "tool_mode": "prompt",
    "max_iterations": 30,
    "render_whitespace": False,
    "auto_approve_commands": False,
    "mcp_tool_protection": True,
    "external_file_protection": True,
    "semantic_index": True,
    "custom_modes": [],
    "kotlin_project_model": "auto",
    "command_allowlist": [],             # commands starting with these run without asking (unless destructive)
    "command_denylist": [],              # commands starting with these always ask
    "lsp": True,                         # language servers for code intelligence in the editor
    "vision": "auto",                    # auto (Intel GPU, then CPU, then text recognition) | gpu | cpu | ocr
    "vision_cpu_model": "qwen3-vl:2b",   # changes outside the project folder ask first (Cursor's External-File Protection)
    "tab_completions": False,
    "completion_delay_ms": 1200,
    "repo_map_chars": 3000,
    "prewarm": True,
    "vd_updates": "check",   # virtual device images: "check" daily, "install" also installs, "install-move" also moves devices, "off"
    "gpu_autostart": True,   # the Forge app starts the GPU engine by itself when the engine is chosen and not running (memory rules apply)
    # Extra llama-server flags for the GPU engine (the app drops them and starts again plainly if the engine refuses them):
    # a 1 GB cap on the server's own prompt cache in RAM, one unified KV cache, and draft-free speculative decoding
    # (ngram-mod guesses the next tokens from text the model has already produced; code and file contents repeat).
    "gpu_extra_args": "--jinja --fit off -ub 256 --cache-ram 1024 --kv-unified --spec-type ngram-mod --spec-ngram-mod-n-min 16 --spec-ngram-mod-n-max 32",
    # Percent of the graphics driver's held memory (freed by Forge's own earlier GPU processes, invisible to Android's
    # free-memory figure) that the app's free-memory check may count. 0 keeps the check on visible free memory only.
    "gpu_pool_credit": 0,
    # Minutes without a chat after which the Forge app unloads the GPU engine (0 keeps it loaded). The next message
    # starts it again by itself and waits for it, so an idle laptop does not keep 3 to 4 GB of graphics memory busy.
    "gpu_idle_unload_min": 10,
    # Extra llama-server flags for the vision model's server. Flash attention inside the image encoder is the slow
    # path on Vulkan (llama.cpp issue 17012: minutes instead of seconds on integrated GPUs), so it is off by default.
    "vision_extra_args": "-fa off",   # "--fit off" made the vision server fail to load its model (2026-10-19)
    "font_size": 13,
    "minimap": True,                     # the editor's minimap on the right of the code
    "preview_tabs": True,                # a single click in the Explorer opens a preview tab, as in Cursor
    "skills_claude_codex": True,         # like Cursor, also load skills from Claude's and Codex's skill folders
    "workspace": os.path.expanduser("~/projects"),
    "recent_workspaces": [],
    # GPU engine: llama.cpp (Vulkan) running on the Android side inside the Forge app
    "engine": "ollama",                 # ollama | gpu
    "gpu_engine_port": 8081,
    "gpu_engine_key": "",               # generated on first use, shared with the app over localhost
    "gpu_model": "forge-coder",         # Ollama model whose weights the app downloads from this server
    "gpu_ctx": 8192,
    "gpu_layers": 99,
    # The GPU engine also sees images when its model has a vision projector (Qwen3.5): screenshots then go straight to
    # the engine instead of a second vision model, so the GPU never unloads one model to load another for a picture.
    "gpu_vision": True,
    "gpu_image_tokens": 640,             # the most image tokens one picture becomes (time and memory grow with it)
}

# Vision projectors for GPU engine models that can see, in ~/.forge-ide/models (F16: the Intel GPU has no bf16).
GPU_MMPROJ = {
    "qwen3.5:4b": ("qwen35-vision", "mmproj-F16.gguf", "Qwen3.5 4B"),
}


# Settings a project folder may override in <folder>/.forge/settings.json (like .vscode/settings.json in Cursor).
# Machine-wide things (the engine, Ollama, the GPU engine, how long the model stays loaded) stay user settings.
FOLDER_SETTING_KEYS = {"command_allowlist", "command_denylist", "vision", "model", "temperature", "top_p", "top_k", "repeat_penalty", "num_ctx", "num_predict", "thinking",
                       "verbose", "tool_mode", "max_iterations", "auto_approve_commands", "external_file_protection",
                       "repo_map_chars", "tab_completions", "completion_delay_ms", "font_size", "minimap", "render_whitespace", "prewarm", "lsp",
                       "semantic_index", "custom_modes", "kotlin_project_model", "mcp_tool_protection", "preview_tabs"}

# Settings whose values must stay in a range (the memory checks count the context, but a huge one never fits).
SETTING_RANGES = {"num_ctx": (2048, 32768), "gpu_ctx": (2048, 32768)}

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
        self.engine_start_sent = 0.0     # when the app was last told to start the GPU engine by itself
        self._need_mb, self._need_mb_ts, self._need_mb_for = 0, 0.0, ""   # cached engine_need_mb() and its model
        self.engine_last_use = time.time()   # when a chat last used the GPU engine (the idle unload counts from here)
        self.engine_idle_stopped = False     # the app unloaded the engine because it was idle; no autostart until asked
        self.engine_stop_sent = 0.0
        self.engine_asked_ts = 0.0           # when a chat asked for the engine (a reply then waits for it)
        self.engine_alive_since = 0.0        # when the engine was first seen answering (the idle unload waits three minutes)
        self.checkpoints = Checkpoints(os.path.join(DATA_DIR, "checkpoints"))
        self.vision = VisionManager(self, DATA_DIR)
        self.attachments = Attachments(os.path.join(DATA_DIR, "attachments"))
        self.memories = Memories(DATA_DIR)
        self.hooks = Hooks(DATA_DIR)
        self.hooks.trusted = self.folder_trusted
        self.docs = Docs(DATA_DIR)
        from .semantic import EmbedServer, EMBED_MIN_VM_MB, EMBED_MIN_HOST_MB
        self.embed_server = EmbedServer(lambda: self.small_memory_block("the code index", EMBED_MIN_VM_MB, EMBED_MIN_HOST_MB),
                                        os.path.join(DATA_DIR, "logs"))
        self.semantic: Dict[str, Any] = {}
        from .notebook import KernelManager, KERNEL_MIN_VM_MB, KERNEL_MIN_HOST_MB
        self.kernels = KernelManager(lambda: self.small_memory_block("a notebook kernel", KERNEL_MIN_VM_MB, KERNEL_MIN_HOST_MB),
                                     os.path.join(DATA_DIR, "logs"))
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
        try:
            from . import updates
            updates.start_auto(lambda: self.settings, lambda: self.small_memory_block("an image update", 1200, 3300))
        except Exception as e:  # noqa: BLE001
            print(f"[updates] not started: {e}", flush=True)
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

    def unload_model(self) -> List[str]:
        """Ask Ollama to drop every loaded model from memory right away (the main model frees about 5 GB). Returns the
        names it dropped."""
        names = []
        try:
            names = [m.get("name") for m in self.ollama.running() if m.get("name")]
        except OllamaError:
            pass
        done = []
        for n in names or [self.resolve_model()]:
            try:
                self.ollama._json("POST", "/api/generate", {"model": n, "keep_alive": 0}, timeout=30)
                if n in names:
                    done.append(n)
            except OllamaError:
                pass
        return done

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
            # When Ollama has just dropped its last model (its keep-alive ran out), the VM compacts its free memory once,
            # so whole blocks go back to the Googlebook through free page reporting instead of staying with the VM.
            now = time.time()
            if now - getattr(self, "_ollama_seen", 0.0) > 30:
                self._ollama_seen = now
                try:
                    loaded = bool(self.ollama.running())
                except Exception:  # noqa: BLE001
                    loaded = False
                if getattr(self, "_ollama_had", False) and not loaded:
                    try:
                        import subprocess
                        subprocess.run(["sudo", "-n", "sysctl", "-q", "-w", "vm.compact_memory=1"], timeout=60, capture_output=True)
                        print("[memory] Ollama unloaded its model: compacted the VM's free memory so it goes back to the Googlebook", flush=True)
                    except (OSError, subprocess.TimeoutExpired):
                        pass
                self._ollama_had = loaded
            if self.active_engine() == "gpu" and time.time() - self.last_shrink > 180:
                cached = 0
                try:
                    with open("/proc/meminfo", "r", encoding="utf-8") as fh:
                        for line in fh:
                            if line.startswith("Cached:"):
                                cached = int(line.split()[1]) // 1024
                except OSError:
                    pass
                # The VM's file cache is memory the Googlebook cannot use until the VM gives it back. Hand it back only
                # when the Googlebook is short (under 3 GB free by the app's report). Shrinking on a schedule while the
                # Googlebook has room made the VM free and re-fault gigabytes every few minutes, and every round trip
                # makes the host compact and split its memory again: the fragmentation that slows the GPU's reads
                # (measured 2026-10-20: 98 percent of the host's compaction attempts failed after 9 days of uptime).
                host = self.vision.app.get("host_mem_mb") if time.time() - self.vision.app_seen < 120 else None
                tight = isinstance(host, (int, float)) and 0 <= host < 3000
                if tight and cached > 700:
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

    @staticmethod
    def pretty_model_name(name: str) -> str:
        """A model's name as people say it: "qwen3.5:4b" is "Qwen3.5 4B", forge-coder is the tuned Llama 3.1 8B."""
        n = str(name or "").replace(":latest", "").strip()
        known = {"forge-coder": "Llama 3.1 8B (forge-coder)", "llama3.1": "Llama 3.1 8B", "llama3.1:8b": "Llama 3.1 8B"}
        if n in known:
            return known[n]
        base, _, size = n.partition(":")
        base = base[:1].upper() + base[1:]
        if re.fullmatch(r"\d+(\.\d+)?b", size):
            size = size.upper()
        return (base + " " + size).strip() or "the model"

    def chat_model_label(self, engine: Optional[str] = None, model: Optional[str] = None) -> str:
        """How the chat names the model that answers, with where it runs."""
        if (engine or self.active_engine()) == "gpu":
            return self.pretty_model_name(str(self.settings.get("gpu_model") or "forge-coder")) + " (Intel GPU)"
        return self.pretty_model_name(model or self.resolve_model(self.settings.get("model"))) + " (CPU)"

    def client_for(self, engine: Optional[str] = None):
        engine = engine or self.active_engine()
        if engine == "gpu":
            self.gpu_client = LlamaCppClient(f"http://{self.host_ip()}:{int(self.settings.get('gpu_engine_port', 8081))}",
                                             str(self.settings.get("gpu_engine_key", "")))
            return self.gpu_client
        return self.ollama

    def gpu_speed(self) -> dict:
        """The GPU engine's writing speed: the median of its last replies, and the best ever measured for this model
        (kept in gpu_speed.json). The status bar warns when the recent speed is far below the best."""
        from .llamacpp_client import SPEEDS
        model = str(self.settings.get("gpu_model") or "")
        recent = [x for x in SPEEDS[-8:] if time.time() - x["ts"] < 7200]
        med = sorted(x["gen_tps"] for x in recent)[len(recent) // 2] if recent else None
        path = os.path.join(DATA_DIR, "gpu_speed.json")
        best = _load_json(path, {})
        best = best if isinstance(best, dict) else {}
        entry = best.get(model) or {}
        top = max((x["gen_tps"] for x in recent), default=0.0)
        if top > float(entry.get("gen_tps", 0) or 0):
            entry = {"gen_tps": top, "ts": time.time()}
            best[model] = entry
            _save_json(path, best)
        return {"model": model, "recent_tps": med, "samples": len(recent), "best_tps": entry.get("gen_tps"), "best_ts": entry.get("ts"),
                "last": SPEEDS[-1] if SPEEDS else None}

    def gpu_mmproj_file(self) -> Optional[dict]:
        """The vision projector for the GPU engine's model, when one is installed and gpu_vision is on."""
        if not self.settings.get("gpu_vision", True):
            return None
        name = str(self.settings.get("gpu_model") or "")
        entry = GPU_MMPROJ.get(name) or GPU_MMPROJ.get(name.split(":")[0] + ":" + (name.split(":")[1] if ":" in name else "latest"))
        if not entry:
            return None
        path = os.path.join(DATA_DIR, "models", entry[0], entry[1])
        if not os.path.isfile(path):
            return None
        return {"path": path, "size": os.path.getsize(path), "filename": name.replace(":", "-") + "-" + entry[1], "label": entry[2]}

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

    @staticmethod
    def session_is_empty(s: dict) -> bool:
        """A chat nobody has written in yet (a fresh "New chat" tab). It is kept in memory only, so opening and
        closing Forge does not leave saved empty chats behind; a renamed chat counts as used."""
        if s.get("renamed") or s.get("transcript"):
            return False
        return not any(m.get("role") == "user" for m in s.get("messages", []))

    def save_session(self, s: dict) -> None:
        s["updated"] = time.time()
        if self.session_is_empty(s) and not os.path.exists(os.path.join(SESSIONS_DIR, s["id"] + ".json")):
            return
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

    # ---------------------------------------------------------------- folder trust (Cursor and VS Code's workspace trust)
    # A folder's own .forge/settings.json may make these safer, but it may loosen them only once you trust the folder:
    # a cloned repository must not turn on Auto-run, allow commands, turn off a protection or run its own hooks.
    TIGHTEN_ONLY = {"auto_approve_commands": False, "external_file_protection": True, "mcp_tool_protection": True}

    def _trust_path(self) -> str:
        return os.path.join(DATA_DIR, "trusted-folders.json")

    def folder_trusted(self, root: Optional[str]) -> bool:
        if not root:
            return False
        d = _load_json(self._trust_path(), {})
        return isinstance(d, dict) and bool(d.get(os.path.realpath(root)))

    def set_folder_trust(self, root: str, trusted: bool) -> None:
        d = _load_json(self._trust_path(), {})
        d = d if isinstance(d, dict) else {}
        key = os.path.realpath(root)
        if trusted:
            d[key] = time.time()
        else:
            d.pop(key, None)
        _save_json(self._trust_path(), d)

    def untrusted_wants(self, root: Optional[str]) -> List[str]:
        """What this folder's own settings and hooks ask for that is held back until you trust the folder, in words."""
        if not root or self.folder_trusted(root):
            return []
        fo = self.folder_overrides(root)
        out = []
        if fo.get("auto_approve_commands") is True:
            out.append("run the agent's commands without asking (Auto-run)")
        if fo.get("command_allowlist"):
            out.append("allow these commands without asking: " + ", ".join(str(x) for x in fo["command_allowlist"])[:300])
        if fo.get("external_file_protection") is False:
            out.append("turn off external-file protection")
        if fo.get("mcp_tool_protection") is False:
            out.append("turn off MCP tools protection")
        if any(isinstance(m, dict) and m.get("auto_run") for m in (fo.get("custom_modes") or [])):
            out.append("custom modes that run commands without asking")
        hooks = self.hooks.project_hooks(root)
        for ev, hs in hooks.items():
            for h in hs:
                out.append(f"run a hook on {ev}: {str(h.get('command'))[:120]}")
        return out

    def folder_overrides(self, root: Optional[str]) -> Dict[str, Any]:
        if not root:
            return {}
        d = _load_json(self.folder_settings_path(root), {})
        return {k: v for k, v in d.items() if k in FOLDER_SETTING_KEYS} if isinstance(d, dict) else {}

    def effective_settings(self, root: Optional[str] = None) -> Dict[str, Any]:
        s = dict(self.settings)
        trusted = self.folder_trusted(root)
        for k, v in self.folder_overrides(root).items():
            if k == "command_denylist":
                # A folder adds to your denylist; it never shortens it.
                s[k] = list(dict.fromkeys([str(x) for x in (self.settings.get(k) or [])] + [str(x) for x in (v or [])]))
                continue
            if not trusted:
                if k in self.TIGHTEN_ONLY and bool(v) != self.TIGHTEN_ONLY[k]:
                    continue
                if k == "command_allowlist":
                    continue
                if k == "custom_modes" and isinstance(v, list):
                    v = [{**m, "auto_run": False} for m in v if isinstance(m, dict)]
            s[k] = v
        return s

    def settings_payload(self, root: str) -> Dict[str, Any]:
        user = {k: v for k, v in self.settings.items() if k != "gpu_engine_key"}   # the engine's key never leaves the VM
        return {"effective": {k: v for k, v in self.effective_settings(root).items() if k != "gpu_engine_key"}, "user": user,
                "folder": self.folder_overrides(root), "folder_keys": sorted(FOLDER_SETTING_KEYS), "folder_path": root,
                "trust": {"trusted": self.folder_trusted(root), "held_back": self.untrusted_wants(root)}}

    def new_session(self, title: str = "New chat", folder: Optional[str] = None, sid: str = "") -> dict:
        s = {"id": sid if re.fullmatch(r"[0-9a-f]{10}", sid or "") else uuid.uuid4().hex[:10], "title": title, "workspace": folder or self.workspace.root, "created": time.time(),
             "updated": time.time(), "messages": [], "transcript": [], "allow": []}
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
            # Neither is installed: the first model that can chat (vision and embedding models cannot).
            chat = sorted(n for n in names if n and not re.search(r"vl|embed", n, re.I))
            if chat:
                return chat[0]
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
        if not settings.get("prewarm", True) or self.memory_block_reason(settings["model"], ctx=settings.get("num_ctx")):
            return

        def go() -> None:
            try:
                client = self.client_for()
                agent = Agent(client, ws, settings, session)
                # With the server state, so the warmed prompt is the one a message really sends (memories, skills,
                # docs, toolchains and the window's other folders included); otherwise the cache would not match.
                agent.state = self
                agent.vision = self.vision
                system = agent._system_prompt("agent")
                msgs = [{"role": "system", "content": system}, {"role": "user", "content": "Reply with the single word ready."}]
                for _ in client.chat_stream(settings["model"], msgs,
                                                 options={**agent._options(), "num_predict": 1},
                                                 keep_alive=str(self.settings.get("keep_alive", "5m"))):
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

    ENGINE_START_NEED_MB = 5600   # the 4.9 GB model plus headroom; the app's own GpuBudget check decides for real

    def engine_start_due(self, report: Dict[str, Any]) -> bool:
        """Whether the Forge app should start the GPU engine now, by itself: the GPU engine is the chosen engine, it is
        down (not paused for the vision model), autostart is on, no other AI model is loaded (the vision model in the
        app, or a model in Ollama; one at a time), the Googlebook has room by the app's own report, and the app was
        not asked in the last three minutes (a start that fails is not repeated in a loop). The app runs its own
        free-memory check before loading anything; this never bypasses it."""
        if not self.settings.get("gpu_autostart", True) or self.active_engine() != "gpu":
            return False
        if self.engine_idle_stopped:
            return False          # unloaded on purpose; the next chat asks for it (ask_engine_start)
        if time.time() - self.engine_start_sent < 180:
            return False
        if self.engine_stats.get("state") == "paused" or self.vision.want():
            return False
        if report.get("vstate") in ("running", "starting", "downloading"):
            return False
        try:
            host = float(report.get("host_mem_mb", -1))
            # What the app's own check counts as free (the memory credit setting included); it never counts less.
            host = max(host, float(report.get("effective_mb", -1)))
        except (TypeError, ValueError):
            host = -1
        # The engine starts without its vision projector when only that fits (EngineService), so the smaller need
        # decides here; the app's own free-memory check still runs first.
        if host < self.engine_need_min_mb():
            return False
        if self.gpu_client.alive():
            return False
        try:
            if self.ollama.loaded_models():
                return False
        except Exception:  # noqa: BLE001  (Ollama down counts as nothing loaded)
            pass
        self.engine_start_sent = time.time()
        print(f"[engine] asking the Forge app to start the GPU engine ({int(host)} MB free on the Googlebook)", flush=True)
        return True

    def engine_need_mb(self) -> int:
        """What the app's own free-memory check asks for before launching the engine: the weights plus 1200 MB for the
        KV cache, compute buffers and margin (the same sum EngineService uses). Cached, locating the blob asks Ollama."""
        now = time.time()
        name = str(self.settings.get("gpu_model") or "forge-coder")
        if (now - self._need_mb_ts > 600 or name != self._need_mb_for) and now - getattr(self, "_need_try_ts", 0.0) > 15:
            self._need_try_ts = now   # a failed look-up is tried again after 15 seconds, not on every status poll
            try:
                mf = self.gpu_model_file()
                if mf and mf.get("size"):
                    # Kept only when it worked: right after Quit Forge, Ollama is still starting, and a failed look-up
                    # must not leave the 5.6 GB fallback in place for ten minutes (seen 2026-10-20).
                    self._need_mb_ts, self._need_mb_for = now, name
                    # The KV cache grows with the engine's context (1200 MB was measured at 8192 tokens).
                    self._need_mb = int(mf["size"]) // (1024 * 1024) + self.context_extra_mb(1200, self.settings.get("gpu_ctx", 8192))
                    # The vision projector and its image encoder, when the engine loads one (EngineService adds the same).
                    pf = self.gpu_mmproj_file()
                    if pf:
                        self._need_mb += int(pf["size"]) // (1024 * 1024) + 300
            except Exception:  # noqa: BLE001
                pass
        return self._need_mb or self.ENGINE_START_NEED_MB

    def engine_need_min_mb(self) -> int:
        """The engine's need without the vision projector, which it leaves out when only that fits (EngineService)."""
        full = self.engine_need_mb()
        try:
            pf = self.gpu_mmproj_file()
            if pf:
                return full - (int(pf["size"]) // (1024 * 1024) + 300)
        except Exception:  # noqa: BLE001
            pass
        return full

    def engine_memory_note(self) -> str:
        """One sentence about the Googlebook's free memory against what the GPU engine needs, from the app's latest
        report. Empty when the app has not reported lately or when there is room (then memory is not the reason)."""
        try:
            host = int(self.vision.app.get("host_mem_mb", -1))
        except (TypeError, ValueError):
            host = -1
        if host < 0 or time.time() - self.vision.app_seen > 120:
            return ""
        need = self.engine_need_min_mb()
        if host >= need:
            return ""
        return (f"The Googlebook has {host} MB free and the GPU engine needs about {need} MB (more to see images), so the Forge "
                f"app is waiting for memory. Closing other apps on the Googlebook (not the Terminal) makes room, and the engine "
                f"then starts by itself.")

    def ask_engine_start(self) -> None:
        """Wake the app's control poll so it hears about the engine right away (a chat needs it)."""
        self.engine_idle_stopped = False
        self.engine_asked_ts = self.engine_last_use = time.time()
        self.engine_start_sent = 0.0
        with self.vision.cond:
            self.vision.engine_poke = True
            self.vision.cond.notify_all()

    def engine_idle_stop_due(self, report: Dict[str, Any]) -> bool:
        """Whether the app should unload the GPU engine now: it runs, no chat has used it for the idle time in the
        settings, nothing is running or waiting for the vision model, and it was not asked in the last ten minutes."""
        try:
            idle_min = int(self.settings.get("gpu_idle_unload_min", 10) or 0)
        except (TypeError, ValueError):
            idle_min = 30
        # Without autostart nothing would start the engine again for the next message, so it is not unloaded either.
        if idle_min <= 0 or self.active_engine() != "gpu" or self.engine_idle_stopped or not self.settings.get("gpu_autostart", True):
            return False
        now = time.time()
        if now - self.engine_last_use < idle_min * 60 or now - self.engine_stop_sent < 600:
            return False
        if self.vision.want() or any(not r.done for r in self.runs.values()):
            return False
        if not self.gpu_client.alive():
            self.engine_alive_since = 0.0
            return False
        # A freshly started engine must not be unloaded by a poll that races its own "running" report (seen 2026-10-19).
        if self.engine_alive_since == 0.0:
            self.engine_alive_since = now
            self.engine_last_use = now
            return False
        if now - self.engine_alive_since < 180:
            return False
        self.engine_idle_stopped = True
        self.engine_stop_sent = now
        print(f"[engine] idle for {idle_min} minutes: asking the Forge app to unload the GPU engine", flush=True)
        return True

    def memory_block_reason(self, model: Optional[str] = None, engine: Optional[str] = None, ctx: Optional[int] = None) -> Optional[str]:
        """Refuse to load the model when the VM does not have room for it. Loading a 5 GB model into a VM
        that is already nearly full made the host kill the whole VM once (2026-10-14); never again."""
        if (engine or self.active_engine()) == "gpu":
            paused = self.engine_stats.get("state") == "paused" or self.vision.want()
            if not self.gpu_client.alive() and not paused:
                if time.time() - self.engine_asked_ts < 600:
                    return None   # a chat asked for the engine; the reply waits for it and says what it waits for
                if self.settings.get("gpu_autostart", True) and time.time() - self.vision.app_seen < 60:
                    self.ask_engine_start()
                    return None
                return ("The GPU engine is not running. Start it from the Forge app (Engine card in Settings) or switch "
                        "the engine back to Ollama in Settings.")
            return None
        return self.memory_block_reason_ollama(model, ctx)

    @staticmethod
    def context_extra_mb(base_mb: int, ctx: Optional[int]) -> int:
        """The KV cache and buffers grow with the context window: [base_mb] was measured at 8192 tokens, so a bigger
        context reserves proportionally more (a smaller one keeps the measured amount as a margin)."""
        try:
            c = int(ctx or 8192)
        except (TypeError, ValueError):
            c = 8192
        return base_mb * max(8192, c) // 8192

    def model_need_mb(self, model: Optional[str] = None, ctx: Optional[int] = None) -> int:
        """Free memory a CPU model needs to load: its file size plus 900 MB for the KV cache and buffers at 8192 tokens
        of context, more for a bigger context (the 4.9 GB Llama 3.1 build comes out at the 5800 MB that was measured; a
        3.3 GB Qwen3.5 4B needs about 4200 MB)."""
        if ctx is None:
            ctx = self.settings.get("num_ctx", 8192)
        name = self.resolve_model(model) if model else self.resolve_model(self.settings.get("model"))
        try:
            for mm in self.ollama.list_models():
                if mm.get("name") in (name, f"{name}:latest") and mm.get("size"):
                    return max(2500, int(mm["size"]) // (1024 * 1024) + self.context_extra_mb(900, ctx))
        except Exception:  # noqa: BLE001  (Ollama not answering: assume the big model)
            pass
        return MODEL_LOAD_NEED_MB

    def memory_block_reason_ollama(self, model: Optional[str] = None, ctx: Optional[int] = None) -> Optional[str]:
        if self.model_loaded(model):
            return None
        need = self.model_need_mb(model, ctx)
        m = mem_info()
        # The VM's memory comes from the Googlebook: when the Forge app reports the host's free memory, check it too.
        host = self.vision.app.get("host_mem_mb") if time.time() - self.vision.app_seen < 60 else None
        if isinstance(host, (int, float)) and 0 <= host < need:
            return (f"Not enough free memory on the Googlebook to load the model in the VM: {int(host)} MB free, about "
                    f"{need} MB needed. Close other apps or stop the GPU engine, then try again.")
        try:
            other = [x.get("name") for x in self.ollama.running()]
        except OllamaError:
            other = []
        if other and m["available_mb"] and m["available_mb"] < need:
            return (f"Another model is loaded ({', '.join(other)}), and there is not enough memory for a second one. "
                    f"Unload it with the command palette (Unload the model from memory), or use the same model in "
                    f"this folder's settings, then try again.")
        if m["available_mb"] and m["available_mb"] < need:
            return (f"Not enough free memory to load the model safely: {m['available_mb']} MB available, about "
                    f"{need} MB needed. Close other programs in the Linux VM (Waydroid, big builds, other "
                    f"models) and try again. This guard exists because an overfull VM gets killed by GooglebookOS.")
        return None

    def extra_roots(self, root: str) -> List[str]:
        """More folders shown in this folder's windows (multi-root, like Cursor's Add Folder to Workspace)."""
        d = _load_json(self.folder_settings_path(root), {})
        out: List[str] = []
        for p in (d.get("workspace_folders") or []) if isinstance(d, dict) else []:
            p = os.path.realpath(os.path.expanduser(str(p)))
            if os.path.isdir(p) and p != root and p not in out:
                out.append(p)
        return out

    def set_extra_roots(self, root: str, roots: List[str]) -> None:
        fp = self.folder_settings_path(root)
        cur = _load_json(fp, {})
        cur = cur if isinstance(cur, dict) else {}
        cur["workspace_folders"] = roots
        _save_json(fp, cur)

    def semantic_for(self, root: str):
        """The meaning-based index of a folder, or None when it is turned off or not installed."""
        from .semantic import SemanticIndex, available
        if not available() or not self.effective_settings(root).get("semantic_index", True):
            return None
        if root not in self.semantic:
            self.semantic[root] = SemanticIndex(root, self.embed_server, lambda: any(not r.done for r in self.runs.values()))
        return self.semantic[root]

    def small_memory_block(self, what: str, vm_need_mb: int, host_need_mb: int) -> Optional[str]:
        """The memory gate for helper processes (speech, debugger, notebook kernels, embeddings): refuse when the VM
        or the Googlebook is short, so a helper never pushes the system toward a crash."""
        m = mem_info()
        if m.get("available_mb", 99999) < vm_need_mb:
            return f"Not enough free memory in the VM for {what} ({m.get('available_mb')} MB free, {vm_need_mb} MB needed)."
        host = self.host_free_mb()
        if isinstance(host, (int, float)) and 0 <= host < host_need_mb:
            return f"Not enough free memory on the Googlebook for {what} ({int(host)} MB free, {host_need_mb} MB needed)."
        return None

    def host_free_mb(self) -> Optional[float]:
        """The Googlebook's free memory: from the Forge app's report, or else read over adb (MemAvailable from
        /proc/meminfo, a few hundred bytes, with a time limit), cached for 10 seconds. None when neither works."""
        if time.time() - self.vision.app_seen < 60 and isinstance(self.vision.app.get("host_mem_mb"), (int, float)):
            return float(self.vision.app["host_mem_mb"])
        cached = getattr(self, "_host_mem_cache", None)
        if cached and time.time() - cached[0] < 10:
            return cached[1]
        # Ask the Googlebook by its own serial: with virtual or paired devices connected, a plain "adb shell" would fail.
        from .devices import googlebook_free_mb
        try:
            value = googlebook_free_mb()
        except (OSError, subprocess.TimeoutExpired):
            value = None
        self._host_mem_cache = (time.time(), value)
        return value

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

    def settings_for_run(self, root: Optional[str] = None, engine: Optional[str] = None, model: Optional[str] = None) -> dict:
        """Settings for one reply. A chat may choose its own engine and model (the composer's model picker)."""
        s = self.effective_settings(root)
        if (engine or self.active_engine()) == "gpu":
            s["model"] = "gpu-model"
            s["num_ctx"] = int(self.settings.get("gpu_ctx", 8192))
        else:
            s["model"] = self.resolve_model(model or s.get("model"))
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
        if host in allowed or host.endswith(".localhost"):
            return True
        self._error(403, "Forbidden host header")
        return False

    # ------------------------------------------------------------ routing
    def _authorized(self, path: str) -> bool:
        """Every request but the open ones carries the Forge app's key or the VM key (see key_ok)."""
        if path in OPEN_PATHS or key_ok(self.headers.get("X-Forge-Key") or ""):
            return True
        self._json(401, {"error": "This request needs the Forge app's key. Open the Forge app (it pairs by itself); if the app "
                                  "was reinstalled, run forge-ide pair-reset in the Linux terminal first.", "unpaired": True})
        return False

    def do_GET(self) -> None:  # noqa: N802
        if not self._check_host():
            return
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        if not self._authorized(u.path):
            return
        try:
            if u.path == "/ws/term":
                return self._ws_terminal(q)
            if u.path == "/ws/lsp":
                return self._ws_lsp(q)
            if u.path == "/ws/dap":
                return self._ws_dap(q)
            if u.path == "/ws/kernel":
                return self._ws_kernel(q)
            if u.path == "/ws/logcat":
                return self._ws_logcat(q)
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
        if not self._authorized(u.path):
            return
        try:
            body = self._body()
        except (ValueError, json.JSONDecodeError) as e:
            return self._error(400, f"bad JSON body: {e}")
        if u.path == "/api/pair":
            return self._pair(body)
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

    def _peer_alive(self) -> bool:
        """False when the client closed its connection (its socket reads as ended without waiting)."""
        import select as _select
        try:
            r, _, _ = _select.select([self.connection], [], [], 0)
            if r:
                return self.connection.recv(1, socket.MSG_PEEK) != b""
            return True
        except OSError:
            return False

    def _pair(self, b: dict) -> None:
        """The Forge app tells the server its key. The first app to pair after a reset is the one the server trusts (the
        Forge app pairs as soon as it starts); after that only the same key pairs again."""
        import hashlib
        given = str(b.get("key") or "")
        if len(given) < 32:
            return self._error(400, "a key of at least 32 characters is needed")
        h = hashlib.sha256(given.encode()).hexdigest()
        cur = app_key_hash()
        if cur and cur != h:
            return self._error(403, "This Forge server is paired with another install of the Forge app. If you reinstalled "
                                    "the app, run forge-ide pair-reset in the Linux terminal, then reopen Forge.")
        if not cur:
            _save_json(APP_KEY_FILE, {"sha256": h, "paired": time.time()})
            print("[keys] the Forge app paired with this server", flush=True)
        return self._json(200, {"paired": True})

    # ------------------------------------------------------------ no web page
    def _static(self, path: str) -> None:
        """Forge's window is the native Forge app on the Googlebook; this server has no browser page any more. The
        root answers with one plain sentence so a browser pointed here learns where the IDE is; nothing else is served."""
        if path in ("", "/"):
            body = ("Forge IDE runs as the native Forge app on the Googlebook. This server answers only the app (its API is "
                    "under /api), so there is no page to show here.\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ------------------------------------------------------------ GET api
    def _api_get(self, path: str, q: Dict[str, list]) -> None:
        st = STATE
        assert st is not None
        folder_missing = None
        try:
            ws = self._ws(q)
        except ValueError as e:
            if path not in ("/api/status", "/api/dirs", "/api/events", "/api/run", "/api/engine/config", "/api/engine/model", "/api/engine/mmproj",
                            "/api/engine/status", "/api/settings", "/api/image", "/api/image/analysis", "/api/engine/control",
                            "/api/engine/vision_file", "/api/vision/status", "/api/memcheck", "/api/toolchains",
                            "/api/devices", "/api/devices/screen", "/api/devices/scan", "/api/notebook/kernels",
                            "/api/cloud/google", "/api/cloud/google/models", "/api/cloud/google/projects",
                            "/api/cloud/google/list", "/api/cloud/samsung"):
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
                                    "gpu_engine": {"url": st.gpu_client.base_url, "health": gpu, "host_mem_mb": st.vision.app.get("host_mem_mb"),
                                                   "need_mb": st.engine_need_mb() if st.active_engine() == "gpu" else None,
                                                   "need_min_mb": st.engine_need_min_mb() if st.active_engine() == "gpu" else None,
                                                   "note": st.engine_memory_note() if st.active_engine() == "gpu" and gpu.get("status") != "ok" else ""},
                                    "workspace_name": os.path.basename(ws.root) or ws.root,
                                    "model": st.resolve_model(st.effective_settings(ws.root).get("model")),
                                    "chat_model_label": st.chat_model_label(),
                                    "running": running, "pid": os.getpid(), "default_folder": st.workspace.root,
                                    "folder_missing": folder_missing,
                                    "host_mem_mb": (st.vision.app.get("host_mem_mb") if time.time() - st.vision.app_seen < 60 else None),
                                    "home": os.path.expanduser("~"), "mem": mem_info(), "mem_event": st.last_mem_event,
                                    "gpu_speed": st.gpu_speed() if st.active_engine() == "gpu" else None,
                                    "gpu_vision": st.vision.engine_sees() if st.active_engine() == "gpu" and gpu.get("status") == "ok" else False})
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
        if path == "/api/memcheck":
            # Used by forge-gradle and forge-cargo before a build starts in the terminal.
            try:
                vm, host = int(arg("vm", "1000")), int(arg("host", "3000"))
            except ValueError:
                return self._error(400, "bad numbers")
            why = st.small_memory_block(arg("what", "this job")[:80], vm, host)
            hf = st.host_free_mb()
            return self._json(200, {"ok": why is None, "reason": why, "host_mb": int(hf) if hf is not None else None,
                                    "vm_mb": mem_info().get("available_mb")})
        if path == "/api/toolchains":
            from . import toolchains
            return self._json(200, toolchains.status())
        if path == "/api/notebook/kernels":
            from .notebook import available_kernels
            return self._json(200, {"kernels": available_kernels()})
        if path == "/api/devices":
            from . import devices, vdevice
            devs = devices.list_devices()
            t = devices.target(devs)
            from . import personas
            return self._json(200, {"devices": devs, "target": t, "virtual": vdevice.status(), "personas": personas.listing(),
                                    "behaviors": {k: {"label": v["label"], "about": v["about"]} for k, v in personas.BEHAVIORS.items()},
                                    "profiles": {k: {"label": v[0], "width": v[1], "height": v[2], "density": v[3]} for k, v in devices.PROFILES.items()}})
        if path == "/api/devices/scan":
            from . import devices
            return self._json(200, {"found": devices.scan()})
        if path.startswith("/api/cloud/"):
            from . import cloudlab
            refresh = q.get("refresh", ["0"])[0] == "1"
            if path == "/api/cloud/google":
                return self._json(200, cloudlab.google_status())
            if path == "/api/cloud/google/models":
                return self._json(200, cloudlab.google_models(refresh))
            if path == "/api/cloud/google/projects":
                return self._json(200, cloudlab.google_projects())
            if path == "/api/cloud/google/list":
                return self._json(200, cloudlab.google_list(q.get("project", [""])[0]))
            if path == "/api/cloud/samsung":
                return self._json(200, cloudlab.samsung_summary(refresh))
        if path == "/api/devices/screen":
            from . import devices, vdevice
            serial, port = devices.resolve(arg("serial") or None, int(arg("port") or 0) or None)
            png = devices.screen_png(serial, port)
            vd = vdevice.by_serial(serial) if port == devices.FORGE_PORT else None
            why = ""
            if png is None and vd and vdevice.VERSIONS[vd["version"]]["backend"] == "arm":
                # An emulated ARM device shows its screen through QEMU until Android's adb is up (and if it never is).
                from . import armvm
                png, why = armvm.screen_png(vd)
            if png is None:
                # The device window shows this text in place of the picture, so it says why there is none.
                if not why and vd:
                    ph = vdevice.phase(vd["id"])
                    if ph.get("phase") in ("starting", "booting"):
                        why = f"{vd['name']} is still starting ({ph.get('message') or 'Android is starting'}). Its screen appears once Android answers adb."
                    elif not vdevice.running(vd):
                        why = f"{vd['name']} is not running."
                return self._error(503, why or "The device did not send a screenshot (adb's screencap gave no picture).")
            if vd:
                vdevice.touch_active(vd)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(png)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(png)
            return None
        if path == "/api/android/status":
            from . import android, toolchains
            from . import devices
            devs = devices.list_devices()
            return self._json(200, {"toolchains": toolchains.status(), "devices": devs, "target": devices.target(devs),
                                    "project": android.project_info(ws.root), "multiplatform": android.kmp_info(ws.root)})
        if path == "/api/roots":
            return self._json(200, {"primary": ws.root, "extra": st.extra_roots(ws.root)})
        if path == "/api/raw":
            # Image files for the editor's image viewer (images only, so this never serves arbitrary files as pages).
            p = ws.resolve(arg("path"), must_exist=True)
            kinds = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
                     ".bmp": "image/bmp", ".ico": "image/x-icon", ".avif": "image/avif"}
            ctype = kinds.get(os.path.splitext(p)[1].lower())
            if not ctype or not os.path.isfile(p) or os.path.getsize(p) > 40_000_000:
                return self._error(400, "not an image file Forge can show")
            with open(p, "rb") as fh:
                data = fh.read()
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(data)
            return None
        if path == "/api/files":
            files = ws.all_files(refresh=arg("refresh") == "1")
            for extra in st.extra_roots(ws.root):
                files = files + [extra + "/" + f for f in st.workspace_for(extra).all_files(refresh=arg("refresh") == "1")]
            needle = arg("q").lower()
            if needle:
                files = [f for f in files if _fuzzy(needle, f.lower())]
                files.sort(key=lambda f: (needle not in os.path.basename(f).lower(), len(f)))
            return self._json(200, {"files": files[:300], "total": len(files)})
        if path == "/api/file":
            # The editor's read: the file's own encoding and line ending are detected (or the encoding asked for is
            # used, for Reopen with Encoding) and reported, so a save writes the file back the same way.
            from . import textcodec, tools as _tools
            rel = arg("path")
            if arg("run_id"):
                ws = self._run_ws(arg("run_id"), ws)   # a run of another folder: its files, not this window's
            p = ws.resolve(rel, must_exist=True)
            if os.path.isdir(p):
                raise ValueError(f"'{rel}' is a directory.")
            if _tools._is_binary_path(p):
                raise ValueError(f"'{rel}' is a binary file ({os.path.getsize(p):,} bytes) and cannot be read as text.")
            with open(p, "rb") as fh:
                data = fh.read()
            text, enc, eol, lossless = textcodec.decode(data, arg("encoding") or None)
            return self._json(200, {"path": rel, "content": text, "mtime": os.path.getmtime(p), "size": len(text),
                                    "encoding": enc, "encoding_name": textcodec.name_of(enc), "eol": eol, "lossless": lossless})
        if path == "/api/encodings":
            from . import textcodec
            return self._json(200, {"encodings": textcodec.choices()})
        if path == "/api/search":
            # The Search view: the same engine and globs as Replace, over this folder and the folders added to the
            # window. With a replacement it also returns each line as Replace would write it.
            from .tools import search_regex, search_files, regex_replacement
            try:
                rx = search_regex(arg("q"), arg("regex") == "1", arg("case") != "0", arg("word") == "1")
            except re.error as e:
                return self._error(400, f"This is not a valid regular expression: {e}")
            rep = None
            if arg("replacement") != "":
                rep = regex_replacement(arg("replacement")) if arg("regex") == "1" else arg("replacement").replace("\\", "\\\\")
            files = list(ws.all_files())
            for extra in st.extra_roots(ws.root):
                files += [extra + "/" + f for f in st.workspace_for(extra).all_files()]
            limit = int(arg("max") or 400)
            hits, total, cut = search_files(ws, files, rx, arg("glob") or None, arg("exclude") or None, limit, time.monotonic() + 25, rep)
            return self._json(200, {"hits": hits, "total": total, "stopped_early": cut})
        if path == "/api/sessions":
            items = sorted(st.sessions.values(), key=lambda s: -s.get("updated", 0))
            if arg("all") != "1":
                items = [s for s in items if s.get("workspace") == ws.root]
            # Empty chats are open tabs, not history.
            items = [s for s in items if not st.session_is_empty(s)]
            # A saved chat whose file was deleted outside Forge is forgotten here, so no restart is needed.
            gone = [s for s in items if not os.path.exists(os.path.join(SESSIONS_DIR, s["id"] + ".json"))]
            for s in gone:
                st.sessions.pop(s["id"], None)
            items = [s for s in items if s not in gone]
            return self._json(200, {"folder": ws.root, "sessions": [{"id": s["id"], "title": s["title"], "updated": s["updated"],
                                    "workspace": s.get("workspace"),
                                    # The messages you see (your questions and the replies), not the model's working list.
                                    "messages": sum(1 for t in (s.get("transcript") or []) if isinstance(t, dict) and t.get("role") in ("user", "assistant"))}
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
            # As in Cursor's Source Control: a staged file compares HEAD with what is staged, an unstaged one compares
            # what is staged (or HEAD) with the file as it is now.
            rel = arg("path")
            head = _git_show_head(ws.root, rel)
            index = _git_show_index(ws.root, rel)
            if arg("staged") == "1":
                return self._json(200, {"path": rel, "original": head, "modified": index if index is not None else ""})
            cur = ""
            try:
                cur = ws.read_text(rel)
            except Exception:  # noqa: BLE001
                cur = ""
            return self._json(200, {"path": rel, "original": index if index is not None else head, "modified": cur})
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
                                    "props": {k: props.get(k) for k in ("model_path", "build_info", "total_slots", "default_generation_settings", "modalities") if k in props},
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
            for k in ("host_mem_mb", "effective_mb"):
                try:
                    report[k] = int(arg(k) or -1)
                except ValueError:
                    pass
            answer = st.vision.control(report, float(arg("wait", "20") or 20))
            if (answer.get("engine_start") or answer.get("engine_stop")) and not self._peer_alive():
                # The app that asked is gone (its process ended while this answer waited, for example during an
                # install): a request it never receives must not count, or the next app would wait 3 minutes.
                if answer.get("engine_start"):
                    st.engine_start_sent = 0.0
                if answer.get("engine_stop"):
                    st.engine_idle_stopped = False
                    st.engine_stop_sent = 0.0
                return None
            try:
                return self._json(200, answer)
            except (BrokenPipeError, ConnectionResetError):
                # The app that asked is gone (its process ended while this answer waited, for example during an
                # install): a start request it never received must not count, or the next app would wait 3 minutes.
                if answer.get("engine_start"):
                    st.engine_start_sent = 0.0
                if answer.get("engine_stop"):
                    st.engine_idle_stopped = False
                    st.engine_stop_sent = 0.0
                raise
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
        if path == "/api/memories":
            return self._json(200, st.memories.list(ws.root))
        if path == "/api/commands":
            from . import skills as _skills
            sk = _skills.discover(ws.root, bool(st.settings.get("skills_claude_codex", True)))
            return self._json(200, {"commands": BUILTIN_COMMANDS + custom_commands(DATA_DIR, ws.root),
                                    "skills": [{k: s[k] for k in ("name", "description", "path", "scope", "manual_only", "files")} for s in sk]})
        if path == "/api/skills":
            from . import skills as _skills
            return self._json(200, {"skills": _skills.discover(ws.root, bool(st.settings.get("skills_claude_codex", True)))})
        if path == "/api/docs":
            return self._json(200, {"docs": st.docs.list()})
        if path == "/api/hooks":
            return self._json(200, {"global": _read_text(st.hooks.global_path) or '{\n}\n',
                                    "project": _read_text(os.path.join(ws.root, ".forge", "hooks.json")) or '{\n}\n',
                                    "events": list(st.hooks.EVENTS)})
        if path == "/api/index/status":
            from .semantic import available
            enabled = bool(st.effective_settings(ws.root).get("semantic_index", True))
            sem = st.semantic_for(ws.root)
            if not enabled:
                # Turned off: an indexer still running for this folder pauses now.
                old = st.semantic.get(ws.root)
                if old is not None and old.worker and old.worker.is_alive():
                    old.pause()
            elif sem is not None and sem.status.get("state") in ("idle", "ready") and not (sem.worker and sem.worker.is_alive()) \
                    and time.time() - max(float(sem.status.get("updated") or 0), getattr(sem, "checked_at", 0.0)) > 300 \
                    and not any(not r.done for r in st.runs.values()):
                # As in Cursor, the index keeps itself up to date: the window asks for the status every so often, and
                # an index older than five minutes is brought up to date (only the changed files are read again; the
                # indexer waits while a chat runs and checks memory before it loads its small model).
                sem.checked_at = time.time()
                sem.start()
            return self._json(200, {"available": available(), "enabled": enabled,
                                    "status": dict(sem.status) if sem else None, "server_running": st.embed_server.running()})
        if path == "/api/lsp/status":
            from . import lsp
            return self._json(200, {"available": lsp.available()})
        if path == "/api/vision/status":
            return self._json(200, st.vision.status())
        if path == "/api/engine/config":
            # Read by the Forge app (host side) to launch llama-server with the right arguments.
            mf = st.gpu_model_file()
            pf = st.gpu_mmproj_file()
            return self._json(200, {"host_ip": st.host_ip(), "port": int(st.settings.get("gpu_engine_port", 8081)),
                                    "api_key": st.settings.get("gpu_engine_key", ""), "ctx": int(st.settings.get("gpu_ctx", 8192)),
                                    "gpu_layers": int(st.settings.get("gpu_layers", 99)),
                                    "extra_args": str(st.settings.get("gpu_extra_args", "") or ""),
                                    "pool_credit_percent": int(st.settings.get("gpu_pool_credit", 0) or 0),
                                    "model": None if not mf else {"filename": mf["filename"], "size": mf["size"], "digest": mf["digest"],
                                                                  "download": "/api/engine/model"},
                                    "mmproj": None if not pf else {"filename": pf["filename"], "size": pf["size"], "download": "/api/engine/mmproj"},
                                    "image_max_tokens": int(st.settings.get("gpu_image_tokens", 640) or 640)})
        if path == "/api/engine/mmproj":
            pf = st.gpu_mmproj_file()
            if not pf:
                return self._error(404, "no vision projector for the GPU engine's model")
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(pf["size"]))
            self.end_headers()
            with open(pf["path"], "rb") as fh:
                while True:
                    chunk = fh.read(1024 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return None
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
                             "/api/terminal/kill", "/api/image") or path.startswith(("/api/engine/", "/api/devices/", "/api/vd/", "/api/cloud/"))
                    or (path == "/api/settings" and b.get("_scope", "user") == "user")):
                raise
            ws = st.workspace
        if path == "/api/settings":
            scope = str(b.pop("_scope", "user"))
            for k, (lo, hi) in SETTING_RANGES.items():
                if k in b:
                    try:
                        v = int(b[k])
                    except (TypeError, ValueError):
                        return self._error(400, f"{k} must be a whole number from {lo} to {hi}.")
                    if not lo <= v <= hi:
                        return self._error(400, f"{k} must be from {lo} to {hi} (a bigger context needs more memory than this VM has).")
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
            # The editor's save, in the file's encoding and line ending (given by the editor, or else those the file
            # already has on disk, so a file Forge did not open itself keeps them too).
            from . import textcodec
            rel = str(b["path"])
            content = str(b.get("content", ""))
            if b.get("run_id"):
                ws = self._run_ws(str(b["run_id"]), ws)   # a run of another folder: its files, not this window's
            p = ws.resolve(rel)
            if b.get("if_absent") and os.path.exists(p):
                # Save As asks before it replaces a file; the app sends the save again without if_absent after a yes.
                return self._error(409, f"{rel} already exists.")
            enc, eol = b.get("encoding"), b.get("eol")
            if not enc or not eol:
                old_enc, old_eol = "utf8", "LF"
                if os.path.isfile(p):
                    with open(p, "rb") as fh:
                        _, old_enc, old_eol, _ = textcodec.decode(fh.read())
                enc, eol = enc or old_enc, eol or old_eol
            data = textcodec.encode(content, str(enc), str(eol))
            os.makedirs(os.path.dirname(p) or ws.root, exist_ok=True)
            with open(p, "wb") as fh:
                fh.write(data)
            ws._file_cache = (0.0, [])
            check = ws.verify(ws.relpath(p))
            return self._json(200, {"ok": True, "path": rel, "mtime": os.path.getmtime(p), "check": check.strip(),
                                    "encoding": enc, "eol": eol})
        if path == "/api/file/new":
            rel = str(b["path"])
            p = ws.resolve(rel)
            if os.path.exists(p):
                return self._error(409, f"{rel} already exists.")
            if b.get("dir"):
                os.makedirs(p, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(p) or ws.root, exist_ok=True)
                try:
                    # "x": created only if it does not exist yet, so a file that appeared meanwhile is never emptied.
                    with open(p, "x", encoding="utf-8") as fh:
                        fh.write("")
                except FileExistsError:
                    return self._error(409, f"{rel} already exists.")
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
            s["renamed"] = True
            st.save_session(s)
            return self._json(200, {"ok": True})
        if path == "/api/session/transcript":
            s = st.sessions.get(str(b.get("id")))
            if not s and b.get("transcript"):
                s = st.new_session("New chat", ws.root, str(b.get("id")))
            if not s:
                return self._error(404, "no such session")
            s["transcript"] = b.get("transcript", [])
            if b.get("title"):
                s["title"] = str(b["title"])[:120]
            st.save_session(s)
            return self._json(200, {"ok": True})
        if path == "/api/session/allow":
            # Takes back what "Always allow" granted in a chat: one grant ("run_command", "mcp:<tool>",
            # "outside_changes") or all of them.
            s = st.sessions.get(str(b.get("id")))
            if not s:
                return self._error(404, "no such session")
            key = str(b.get("revoke", "all"))
            s.pop("auto_approve", None)
            s["allow"] = [] if key == "all" else [k for k in (s.get("allow") or []) if k != key]
            st.save_session(s)
            return self._json(200, {"allow": s["allow"]})
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
        if path == "/api/engine/bench":
            # One measured reply on the GPU engine (no loop): a fixed prompt and 128 tokens, as a speed check.
            if st.active_engine() != "gpu" or not st.gpu_client.alive():
                return self._error(503, "The GPU engine is not running.")
            from .llamacpp_client import record_speed
            body = {"model": "bench", "messages": [{"role": "user", "content": "Write a Python function that parses an ISO 8601 date string without any library, with comments."}],
                    "stream": False, "cache_prompt": False, "max_tokens": 128, "temperature": 0, "ignore_eos": True,
                    "chat_template_kwargs": {"enable_thinking": False}}
            t0 = time.time()
            r = st.gpu_client._json("POST", "/v1/chat/completions", body, timeout=300)
            tm = r.get("timings") or {}
            gen = float(tm.get("predicted_per_second") or 0)
            if gen > 0:
                record_speed(gen, float(tm.get("prompt_per_second") or 0), int(tm.get("predicted_n") or 0), "bench")
            return self._json(200, {"gen_tps": round(gen, 2), "prompt_tps": round(float(tm.get("prompt_per_second") or 0), 1),
                                    "tokens": tm.get("predicted_n"), "seconds": round(time.time() - t0, 1), "speed": st.gpu_speed()})
        if path == "/api/inline_edit":
            settings = st.settings_for_run(ws.root)
            # The same free-memory check as a chat message: Ctrl K must never load a model into a VM without room.
            blocked = st.memory_block_reason(settings["model"], ctx=settings.get("num_ctx"))
            if blocked:
                return self._error(503, blocked)
            run = Run("inline:" + str(b.get("session_id") or ""), "edit")
            st.runs[run.id] = run
            t = threading.Thread(target=inline_edit_stream, name="inline-edit", daemon=True,
                                 args=(st.client_for(), settings, run, str(b.get("path", "")), str(b.get("language", "")),
                                       str(b.get("selection", "")), str(b.get("instruction", "")),
                                       str(b.get("before", "")), str(b.get("after", ""))))
            run.thread = t
            t.start()
            return self._json(200, {"run_id": run.id})
        if path == "/api/complete":
            settings = st.settings_for_run(ws.root)
            # Tab completions load the model too, so they pass the free-memory check first.
            blocked = st.memory_block_reason(settings["model"], ctx=settings.get("num_ctx"))
            if blocked:
                return self._error(503, blocked)
            text = complete(st.client_for(), settings, str(b.get("path", "")), str(b.get("language", "")),
                            str(b.get("prefix", "")), str(b.get("suffix", "")))
            return self._json(200, {"text": text})
        if path == "/api/trust":
            # Trust this folder (or take the trust back): its own settings and hooks may then loosen the safety keys.
            st.set_folder_trust(ws.root, bool(b.get("trusted", True)))
            return self._json(200, st.settings_payload(ws.root))
        if path == "/api/changes/keep":
            ok = st.checkpoints.keep_part(str(b.get("run_id", "")), str(b.get("path", "")), str(b.get("before", "")))
            if not ok:
                return self._error(404, "This change is no longer known.")
            return self._json(200, {"ok": True})
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
                snap = ws._snapshot(None, ws.resolve(rel))      # text, encoding, line ending and the exact bytes
            except Exception:  # noqa: BLE001
                snap = None
            code, out = _git(ws.root, ["checkout", "--", rel])
            if code == 0 and snap is not None:
                after = ws.read_text(rel) if os.path.exists(ws.resolve(rel)) else None
                did = "git-discard-" + uuid.uuid4().hex[:8]
                st.checkpoints.begin(did, ws.root, "", f"Discarded with git: {rel}")
                st.checkpoints.record(did, rel, snap[0], after, snap[3], snap[1], snap[2])
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
            blocked = st.memory_block_reason(settings["model"], ctx=settings.get("num_ctx"))
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
            r = st.client_for().generate(settings["model"], prompt, options={"num_ctx": int(settings.get("num_ctx", 8192)), "temperature": 0.2, "num_predict": 200},
                                   keep_alive=str(settings.get("keep_alive", "5m")), timeout=600,
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
            blocked = st.memory_block_reason(settings["model"], ctx=settings.get("num_ctx"))
            if blocked:
                return self._error(503, blocked)
            instruction = str(b.get("instruction", "")).strip()
            if not instruction:
                raise ValueError("instruction required")
            # The shell's own folder (where cd took it), read from the shell process; the window's folder otherwise.
            cwd = ""
            term = str(b.get("term") or "")[:40]
            sess = st.terminals.sessions.get(ws.root + "\0" + term) if term else None
            if sess is not None and sess.alive:
                try:
                    cwd = os.readlink(f"/proc/{sess.pid}/cwd")
                except OSError:
                    cwd = ""
            prompt = (f"Operating system: Debian Linux in a VM, shell: bash, current folder: {cwd or b.get('cwd') or ws.root}.\n"
                      f"Task: {instruction}\n\nReply with exactly one shell command line that does this, and nothing else: "
                      f"no explanation, no markdown, no backticks.")
            r = st.client_for().generate(settings["model"], prompt, options={"num_ctx": int(settings.get("num_ctx", 8192)), "temperature": 0.1, "num_predict": 120,
                                                                      "stop": ["\n\n", "```"]},
                                   keep_alive=str(settings.get("keep_alive", "5m")), timeout=300,
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
        if path == "/api/android/connect":
            from . import devices
            return self._json(200, devices.connect_googlebook())
        if path == "/api/android/install":
            from . import devices, vdevice
            apk = ws.resolve(str(b.get("apk", "")), must_exist=True)
            if not apk.endswith(".apk"):
                return self._error(400, "That is not an APK file.")
            serial, port = devices.resolve(b.get("serial"), b.get("port"))
            vd = vdevice.by_serial(serial) if port == devices.FORGE_PORT else None
            if vd:
                vdevice.touch_active(vd)
            return self._json(200, devices.install(apk, serial, port))
        if path == "/api/android/launch":
            from . import devices
            serial, port = devices.resolve(b.get("serial"), b.get("port"))
            return self._json(200, devices.launch(str(b.get("app_id", "")), serial, port))
        if path == "/api/android/screenshot":
            from . import devices, vdevice
            serial, port = devices.resolve(b.get("serial"), b.get("port"))
            png = devices.screen_png(serial, port)
            vd = vdevice.by_serial(serial) if port == devices.FORGE_PORT else None
            why = ""
            if png is None and vd and vdevice.VERSIONS[vd["version"]]["backend"] == "arm":
                # As in the device window: an emulated ARM device's screen comes through QEMU while adb is not up.
                from . import armvm
                png, why = armvm.screen_png(vd)
            if png is None:
                return self._error(503, why or "The screenshot failed. Check that the device is connected (Android view).")
            meta = st.vision.images.save(png, "device-screen.png")
            meta["url"] = "/api/image?id=" + meta["id"]
            return self._json(200, meta)
        if path == "/api/debug/android":
            from . import debugging, devices
            serial, port = devices.resolve(b.get("serial"), b.get("port"))
            if b.get("release"):
                debugging.android_release(int(b.get("local_port") or 0), serial, port)
                return self._json(200, {"ok": True})
            r = debugging.android_prepare(str(b.get("app_id", "")), serial, port)
            r.update({"serial": serial, "port": port})
            return self._json(200, r)
        if path == "/api/debug/prepare":
            from . import debugging
            p = ws.resolve(str(b.get("path", "")), must_exist=True)
            return self._json(200, debugging.prepare(p, ws.root))
        if path.startswith("/api/devices/") or path.startswith("/api/vd/"):
            return self._json(200, self._devices_post(path, b))
        if path.startswith("/api/cloud/google/"):
            # Reserving costs money: these only run with confirmed=true, which the Android view sends after you agree to
            # Google's price. The agent has no tool for them.
            from . import cloudlab
            if path == "/api/cloud/google/reserve":
                return self._json(200, cloudlab.google_reserve(str(b.get("target", "")), str(b.get("project", "")), b.get("confirmed") is True))
            if path == "/api/cloud/google/extend":
                return self._json(200, cloudlab.google_extend(str(b.get("reservation", "")), int(b.get("minutes", 15)),
                                                              str(b.get("project", "")), b.get("confirmed") is True))
            if path == "/api/cloud/google/release":
                return self._json(200, cloudlab.google_release(str(b.get("reservation", "")), str(b.get("project", ""))))
        if path == "/api/replace":
            import fnmatch
            q = str(b.get("q", ""))
            if not q:
                return self._error(400, "nothing to search for")
            from .tools import search_regex, regex_replacement, path_matches, split_globs
            rep = str(b.get("replacement", ""))
            try:
                pat = search_regex(q, bool(b.get("regex")), bool(b.get("case", True)), bool(b.get("word")))
            except re.error as e:
                return self._error(400, f"invalid regular expression: {e}")
            if b.get("regex"):
                rep = regex_replacement(rep)     # Cursor's $1 and ${name} work, as in the Search view's preview
            glob = str(b.get("glob") or "").strip()
            exclude = split_globs(b.get("exclude"))
            dry = bool(b.get("dry_run"))
            files = list(ws.all_files(refresh=True))
            for extra in st.extra_roots(ws.root):
                files += [extra + "/" + f for f in st.workspace_for(extra).all_files(refresh=True)]
            if glob:
                files = [f for f in files if path_matches(f, split_globs(glob))]
            if exclude:
                files = [f for f in files if not path_matches(f, exclude)]
            only = b.get("paths")
            if isinstance(only, list) and only:
                wanted = {str(x) for x in only}
                files = [f for f in files if f in wanted]
            run_id = "replace-" + uuid.uuid4().hex[:10]
            found, total = [], 0
            for rel in files[:20000]:
                try:
                    p = ws.resolve(rel, must_exist=True)
                    if os.path.getsize(p) > 2_000_000:
                        continue
                    text = ws.read_text(rel)
                except (ValueError, OSError):
                    continue
                new, n = pat.subn(rep if b.get("regex") else (lambda _m: rep), text)
                if not n:
                    continue
                if not dry:
                    if not found:
                        st.checkpoints.begin(run_id, ws.root, "", f"Replace '{q[:40]}' with '{rep[:40]}'")
                    ws.write_file(rel, new, run_id)
                found.append({"path": rel, "count": n})
                total += n
            return self._json(200, {"files": found, "total": total, "run_id": None if dry or not found else run_id})
        if path == "/api/roots":
            roots = st.extra_roots(ws.root)
            target = os.path.realpath(os.path.expanduser(str(b.get("path", ""))))
            if b.get("action") == "remove":
                roots = [r for r in roots if r != target]
            else:
                if not os.path.isdir(target):
                    return self._error(400, f"{target} is not a folder")
                if target == ws.root or target in roots:
                    return self._error(400, "That folder is already in this window.")
                roots.append(target)
            st.set_extra_roots(ws.root, roots)
            return self._json(200, {"primary": ws.root, "extra": st.extra_roots(ws.root)})
        if path == "/api/index":
            if b.get("action") == "pause":
                # Pause always works, also after the index was turned off while it ran.
                old = st.semantic.get(ws.root)
                if old is None:
                    return self._json(200, {"status": None})
                old.pause()
                return self._json(200, {"status": dict(old.status)})
            sem = st.semantic_for(ws.root)
            if sem is None:
                return self._error(400, "The code index is turned off in Settings or not installed.")
            sem.start()
            return self._json(200, {"status": dict(sem.status)})
        if path == "/api/snapshot":
            # A picture of the native window that the Forge app rendered by itself (its snapshot mode), so its look
            # can be checked while the Googlebook's screen is off. Kept in ~/.forge-ide/snapshots, one file per name.
            import base64 as _b64
            name = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(b.get("name") or "snapshot"))[:80] or "snapshot"
            raw = _b64.b64decode(str(b.get("data", "")), validate=False)
            if not raw.startswith(b"\x89PNG") or len(raw) > 20 * 1024 * 1024:
                raise ValueError("a PNG of at most 20 MB is expected")
            folder = os.path.join(os.path.expanduser("~"), ".forge-ide", "snapshots")
            os.makedirs(folder, exist_ok=True)
            with open(os.path.join(folder, name + ".png"), "wb") as fh:
                fh.write(raw)
            return self._json(200, {"ok": True, "name": name})
        if path == "/api/attachment":
            import base64 as _b64
            from .images import sniff
            data = str(b.get("data", ""))
            if data.startswith("data:"):
                data = data.split(",", 1)[-1]
            raw = _b64.b64decode(data, validate=False)
            name = str(b.get("name") or "file")
            if sniff(raw):
                meta = st.vision.images.save(raw, name)
                return self._json(200, {**meta, "kind": "image", "url": "/api/image?id=" + meta["id"]})
            meta = st.attachments.save(raw, name)
            # The chat sends the record back with the message; kind "upload" makes the agent read the stored file (its
            # text, or a note that the type cannot be read), and file_kind says what the file is.
            return self._json(200, {**meta, "kind": "upload", "file_kind": meta.get("kind", "")})
        if path == "/api/memories":
            action = str(b.get("action", "add"))
            if action == "add":
                st.memories.add(ws.root, str(b.get("text", "")), str(b.get("scope", "project")))
            elif action == "remove":
                st.memories.remove(ws.root, str(b.get("id", "")))
            elif action == "update":
                st.memories.update(ws.root, str(b.get("id", "")), str(b.get("text", "")))
            return self._json(200, st.memories.list(ws.root))
        if path == "/api/docs":
            if str(b.get("action", "add")) == "add":
                st.docs.add(str(b.get("name", "")), str(b.get("url", "")), int(b.get("max_pages", 40) or 40))
            elif b.get("action") == "remove":
                st.docs.remove(str(b.get("name", "")))
            return self._json(200, {"docs": st.docs.list()})
        if path == "/api/hooks":
            text = str(b.get("config", "{}"))
            json.loads(text)
            target = st.hooks.global_path if b.get("scope") == "global" else os.path.join(ws.root, ".forge", "hooks.json")
            _write_text(target, text)
            return self._json(200, {"ok": True})
        if path == "/api/session/rewind":
            # Restore checkpoint: undo the file changes of this reply and every later one, and cut the model's context.
            s = st.sessions.get(str(b.get("id")))
            if not s:
                return self._error(404, "no such session")
            rid = str(b.get("run_id", ""))
            runs = s.get("runs", [])
            idx = next((i for i, r in enumerate(runs) if r.get("run_id") == rid), None)
            if idx is None:
                # A reply that died before Forge recorded it (a crash, a memory wait that never ended) has no
                # checkpoint of its own. Any file it changed under its own id is put back, and nothing else is touched.
                done = list(st.checkpoints.changes(rid)) if rid else []
                msgs = []
                for ch in done:
                    if ch.kind != "reverted":
                        try:
                            msgs.append(self._run_ws(rid, ws).revert(rid, ch.path))
                        except Exception as e:  # noqa: BLE001
                            msgs.append(f"{ch.path}: {e}")
                # The model's context is cut where this message was sent: every message of a turn carries its run id,
                # so the first one of this run marks the cut, even after older messages were trimmed away. Chats from
                # before the run ids fall back to the text of the user turn in the transcript. A second restore finds
                # nothing and cuts nothing.
                text = next((str(t.get("text", "")) for t in s.get("transcript", []) if t.get("role") == "user" and t.get("run_id") == rid), "")
                hist = s.get("messages", [])
                cut = next((k for k, m in enumerate(hist) if m.get("run_id") == rid), None)
                if cut is None and text.strip() and not any(m.get("run_id") for m in hist):
                    for k in range(len(hist) - 1, -1, -1):
                        m = hist[k]
                        c = m.get("content")
                        c = c if isinstance(c, str) else json.dumps(c)
                        if m.get("role") == "user" and c.startswith(text.strip()[:200]):
                            cut = k
                            break
                if cut is not None:
                    later = {m.get("run_id") for m in hist[cut:] if m.get("run_id")}
                    s["messages"] = hist[:cut]
                    s["runs"] = [r for r in runs if r.get("run_id") not in later and (later or int(r.get("msg_index", 0)) < cut)]
                    st.save_session(s)
                return self._json(200, {"ok": True, "messages": msgs})
            msgs = []
            for r in reversed(runs[idx:]):
                rws = self._run_ws(r["run_id"], ws)
                for ch in list(st.checkpoints.changes(r["run_id"])):
                    if ch.kind != "reverted":
                        try:
                            msgs.append(rws.revert(r["run_id"], ch.path))
                        except Exception as e:  # noqa: BLE001
                            msgs.append(f"{ch.path}: {e}")
            # The context is cut at the first message of the oldest restored turn (the run ids say which messages
            # belong to which turn); chats from before the run ids use the index noted when the turn began.
            gone = {str(r.get("run_id")) for r in runs[idx:]}
            hist = s.get("messages", [])
            if any(m.get("run_id") for m in hist):
                cut = next((k for k, m in enumerate(hist) if m.get("run_id") in gone), len(hist))
            else:
                cut = min(int(runs[idx].get("msg_index", len(hist))), len(hist))
            s["messages"] = hist[:cut]
            s["runs"] = runs[:idx]
            st.save_session(s)
            return self._json(200, {"ok": True, "messages": msgs})
        if path == "/api/session/export":
            s = st.sessions.get(str(b.get("id")))
            if not s:
                return self._error(404, "no such session")
            md = str(b.get("markdown") or "")
            base = "/mnt/shared/Documents/Forge/Chats" if os.path.isdir("/mnt/shared/Documents") else os.path.join(DATA_DIR, "exports")
            os.makedirs(base, exist_ok=True)
            safe = re.sub(r"[^A-Za-z0-9 _.-]+", "", s.get("title") or "chat").strip()[:60] or "chat"
            target = os.path.join(base, f"{safe} {time.strftime('%Y-%m-%d %H%M')}.md")
            _write_text(target, md)
            return self._json(200, {"ok": True, "path": target})
        if path == "/api/image/analyze":
            # What the vision helper sees in an image (also used to test the vision path without the chat model).
            settings = st.effective_settings(ws.root)
            img_id = str(b.get("id", ""))
            question = str(b.get("question") or "").strip()
            notes: list = []
            activity: list = []
            t0 = time.time()

            def ev(kind: str, **d: Any) -> None:
                # The same live events a chat receives; deltas are summarized to keep the reply small.
                if kind == "vision_delta":
                    if activity and activity[-1].get("kind") == "vision_delta":
                        activity[-1]["chunks"] += 1
                    else:
                        activity.append({"kind": "vision_delta", "chunks": 1, "t": round(time.time() - t0, 1)})
                    return
                activity.append({"kind": kind, "t": round(time.time() - t0, 1), **d})
            if question:
                found = st.vision.images.find(img_id)
                if not found:
                    return self._error(404, "no such image")
                answer, source = st.vision.ask(found[0], found[1], question, settings, notes.append, ev)
                return self._json(200, {"answer": answer, "source": source, "notes": notes, "activity": activity,
                                        "seconds": round(time.time() - t0, 1)})
            res = st.vision.analyze(img_id, settings, notes.append, ev)
            return self._json(200, {**res, "notes": notes, "activity": activity, "seconds": round(time.time() - t0, 1)})
        if path == "/api/vision/stop":
            st.vision.holds = 0
            st.vision.release()
            return self._json(200, {"ok": True})
        if path == "/api/engine/vision_status":
            if b.get("log"):
                st.engine_stats = {**st.engine_stats, "log": str(b.get("log"))}
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
            return self._json(200, {"engine": eng})
        if path == "/api/engine/stats":
            st.engine_stats = dict(b)
            if b.get("event") == "running":
                st.engine_last_use = time.time()   # a fresh engine counts as used: the idle unload must not undo a start
                st.engine_idle_stopped = False
            elif b.get("event") == "stopped" and b.get("by_user"):
                # You stopped the engine (Stop engine): autostart leaves it stopped until you start it or a chat needs
                # it (ask_engine_start), exactly as after an idle unload.
                st.engine_idle_stopped = True
                print("[engine] stopped by the user: no autostart until it is started again or a chat needs it", flush=True)
            return self._json(200, {"ok": True})
        if path == "/api/engine/applog":
            # The Forge app's own engine and vision log, as last posted with an engine event (for diagnosis).
            return self._json(200, {"stats": {k: v for k, v in st.engine_stats.items() if k != "log"},
                                    "log": str(st.engine_stats.get("log", ""))})
        if path == "/api/terminal/kill":
            st.terminals.kill(ws.root + "\0" + str(b.get("id", "")))
            return self._json(200, {"ok": True})
        if path == "/api/unload":
            return self._json(200, {"ok": True, "unloaded": st.unload_model()})
        if path == "/api/quit":
            # Quit Forge: everything Forge runs in the VM stops, and then the server itself.
            from . import quit as forge_quit

            def cancel_runs() -> int:
                live = [r for r in list(st.runs.values()) if not r.done]
                for r in live:
                    r.cancel.set()
                return len(live)
            steps = forge_quit.stop_everything(cancel_runs, st.unload_model)
            forge_quit.stop_server_soon()
            return self._json(200, {"ok": True, "steps": steps})
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
            # An empty chat tab lives only in memory; after a server restart it comes back under the same id, so the
            # app's tab and the saved chat stay one chat.
            s = st.new_session("New chat", win_ws.root, sid)
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
        custom_mode = None
        if mode not in ("agent", "ask", "plan"):
            # A custom mode (like Cursor's): your own instructions and tool access on top of Agent mode.
            modes = st.effective_settings(ws.root).get("custom_modes") or []
            custom_mode = next((m for m in modes if isinstance(m, dict) and str(m.get("name", "")).strip() == mode), None)
            if custom_mode is None:
                return self._error(400, f"The mode \"{mode}\" no longer exists in this folder (it was renamed or deleted in "
                                        "Settings, Custom modes). Pick another mode in the chat's mode menu.")
            mode = "agent"
        active = [r for r in st.runs.values() if r.session_id == s["id"] and not r.done]
        if active:
            return self._error(409, "a run is already active for this session")
        fallback_note = None
        # The chat's own model choice (composer picker): "__gpu" for the GPU engine, an Ollama model name, or empty
        # for the window's setting. It is remembered with the chat.
        if b.get("model") == "__default":
            s.pop("model_choice", None)
            choice = ""
        else:
            choice = str(b.get("model") or s.get("model_choice") or "")[:120]
            if b.get("model"):
                s["model_choice"] = choice
        run_engine = "gpu" if choice == "__gpu" else ("ollama" if choice else st.active_engine())
        run_model = choice if choice and choice != "__gpu" else None
        if run_engine == "gpu" and not st.gpu_client.alive():
            # Never load the 5 GB CPU model automatically here: the engine usually stopped because memory was short.
            paused = st.engine_stats.get("state") == "paused" or st.vision.want()
            if not paused:
                if st.settings.get("gpu_autostart", True) and time.time() - st.vision.app_seen < 60:
                    # The app starts the engine; the reply waits for it (up to ten minutes) and says what it waits for.
                    st.ask_engine_start()
                else:
                    return self._error(503, "The GPU engine is not running. Start it from the Forge app (Settings, GPU engine "
                                            "card), or switch the engine to Ollama in Settings.")
        if fallback_note:
            settings = st.effective_settings(ws.root)
            settings["model"] = st.resolve_model(settings.get("model"))
            blocked = st.memory_block_reason_ollama(settings["model"])
        else:
            settings = st.settings_for_run(ws.root, run_engine, run_model)
            blocked = st.memory_block_reason(settings["model"], run_engine, ctx=settings.get("num_ctx"))
        if blocked:
            return self._error(503, blocked)
        hook = st.hooks.run(ws.root, "beforeSubmitPrompt", {"prompt": text, "session": s["id"]})
        if hook["permission"] == "deny":
            return self._error(400, "A hook blocked this message: " + ("; ".join(hook["messages"]) or "no reason given"))
        if hook["permission"] == "ask" and not b.get("hook_confirmed"):
            # A hook wants you to confirm this message first; the app asks and sends it again with hook_confirmed.
            return self._json(409, {"error": "A hook asks you to confirm this message: " + ("; ".join(hook["messages"]) or "no reason given"),
                                    "hook_ask": True, "messages": hook["messages"]})
        if s["title"] in ("New chat", "") and text:
            s["title"] = text[:60]
        run = Run(s["id"], mode)
        st.runs[run.id] = run
        st.checkpoints.begin(run.id, ws.root, s["id"], text)
        engine = "ollama" if fallback_note else run_engine
        used = f"{engine}:{settings.get('model')}"
        if s.get("engine_used") not in (None, engine, used):
            # Another engine or model continues with the whole conversation, as in Cursor. Only what belongs to the
            # old engine goes: native tool call records and image data.
            for m in s.get("messages") or []:
                m.pop("tool_calls", None)
                m.pop("images", None)
        s["engine_used"] = used
        agent = Agent(st.ollama if fallback_note else st.client_for(engine), ws, settings, s)
        agent.vision = st.vision
        agent.state = st
        agent.custom_mode = custom_mode
        if custom_mode and custom_mode.get("auto_run"):
            settings["auto_approve_commands"] = True
        if engine == "ollama":
            settings["native_images"] = st.model_can_see(settings.get("model"))

        def go() -> None:
            if fallback_note:
                run.emit("status", text=fallback_note)
            if engine == "gpu":
                st.engine_last_use = time.time()
            agent.run_turn(run, text, list(b.get("attachments") or []), mode)
            st.save_session(s)
            threading.Thread(target=lambda: st.hooks.run(ws.root, "stop", {"session": s["id"], "status": run.status}), daemon=True).start()

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
    def _ws_lsp(self, q: Dict[str, list]) -> None:
        """A language server for this window (code intelligence in the editor)."""
        st = STATE
        assert st is not None
        from . import lsp
        if not _origin_ok(self):
            self.send_response(403); self.send_header("Content-Length", "0"); self.end_headers()
            return
        try:
            folder = self._ws(q).root
        except ValueError:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()
            return
        lang = q.get("lang", [""])[0][:20]

        def memory_ok(vm_need: int = lsp.LSP_MIN_VM_MB, host_need: int = lsp.LSP_MIN_HOST_MB) -> Optional[str]:
            return st.small_memory_block(f"the {lang} language server", vm_need, host_need)

        sock = handshake(self)
        if sock is None:
            return
        lsp.serve(sock, lang, folder, memory_ok, os.path.join(DATA_DIR, "logs"),
                  kotlin_pref=str(st.effective_settings(folder).get("kotlin_project_model", "auto")),
                  host_free_mb=st.host_free_mb() if lang == "kotlin" else None)

    def _ws_dap(self, q: Dict[str, list]) -> None:
        """The debugger for this window: the app speaks the Debug Adapter Protocol to the adapter for the
        language (debugpy, GDB, rust-gdb, or the Kotlin server's debugger for Kotlin and Java)."""
        st = STATE
        assert st is not None
        from . import debugging, lsp
        if not _origin_ok(self):
            self.send_response(403); self.send_header("Content-Length", "0"); self.end_headers()
            return
        try:
            folder = self._ws(q).root
        except ValueError:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()
            return
        lang = q.get("lang", ["python"])[0]
        sock = handshake(self)
        if sock is None:
            return
        gate = lambda vm, host: st.small_memory_block("the debugger", vm, host)
        if lang in ("kotlin", "java"):
            # The Kotlin server the editor has open for this folder, or else one of Forge's own for this session.
            live, stop_own = lsp.LIVE.get(("kotlin", folder)), None
            try:
                why = gate(400, 2800)   # the debugged program runs inside the server's memory cap with a 256 MB heap
                if why:
                    raise RuntimeError(why)
                if live is None:
                    live, stop_own = lsp.headless_kotlin(folder, gate, os.path.join(DATA_DIR, "logs"))
                port = int(live.request("workspace/executeCommand", {"command": "start_debug_server", "arguments": ["file://" + folder]}, timeout=120))
            except (RuntimeError, TimeoutError, OSError, TypeError, ValueError) as e:
                if stop_own:
                    stop_own()
                sock.send_text(json.dumps({"forge": "error", "message": f"The Kotlin debugger did not start: {e}"}))
                sock.close()
                return
            try:
                lsp.serve_tcp(sock, "127.0.0.1", port, what="Kotlin debugger")
            finally:
                if stop_own:
                    stop_own()
            return
        if lang in ("javascript", "typescript"):
            from . import jsdebug
            jsdebug.serve(sock, folder, gate, os.path.join(DATA_DIR, "logs"))
            return
        if lang == "python":
            import importlib.util
            cmd = [sys.executable, "-m", "debugpy.adapter"] if importlib.util.find_spec("debugpy") else None
            what = "debugger (debugpy)"
        elif lang in ("c", "cpp", "rust"):
            cmd = debugging.adapter_command(lang)
            what = "debugger (GDB)" if lang in ("c", "cpp") else "debugger (rust-gdb)"
        else:
            cmd, what = None, "debugger"
        if cmd is None:
            # Said plainly at once, instead of starting something else that would never answer the debugger's requests.
            sock.send_text(json.dumps({"forge": "error", "message": debugging.adapter_missing(lang)}))
            sock.close()
            return
        # GDB replaces the program's whole environment with a launch's env; merged, it adds to it as in VS Code.
        lsp.serve(sock, lang, folder, gate, os.path.join(DATA_DIR, "logs"), cmd=cmd, what=what, cap="1G", needs=(700, 3300),
                  merge_launch_env=lang in ("c", "cpp", "rust"))

    def _ws_kernel(self, q: Dict[str, list]) -> None:
        """A notebook window's connection to its Python kernel."""
        st = STATE
        assert st is not None
        from .notebook import Listener
        if not _origin_ok(self):
            self.send_response(403); self.send_header("Content-Length", "0"); self.end_headers()
            return
        try:
            ws = self._ws(q)
            path = ws.resolve(q.get("path", [""])[0], must_exist=True)
        except (ValueError, OSError):
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()
            return
        sock = handshake(self)
        if sock is None:
            return
        listener = Listener(sock.send_text)
        kernel = None
        try:
            try:
                kernel = st.kernels.get(path)
                kernel.listeners.add(listener)
                listener.put({"type": "attached", "busy": kernel.busy, "info": kernel.info})
            except RuntimeError as e:
                sock.send_text(json.dumps({"type": "exit", "message": str(e)}))
            while True:
                op, data = sock.recv()
                if op == 8:
                    break
                if op != 1 or not data:
                    continue
                try:
                    msg = json.loads(data.decode("utf-8", "replace"))
                except ValueError:
                    continue
                cmd = msg.get("op")
                try:
                    if cmd == "restart" or (cmd == "execute" and (kernel is None or not kernel.alive())):
                        if kernel is not None:
                            kernel.listeners.discard(listener)
                        kernel = st.kernels.restart(path) if cmd == "restart" else st.kernels.get(path)
                        kernel.listeners.add(listener)
                        listener.put({"type": "restarted"})
                    if cmd == "execute":
                        kernel.execute(str(msg.get("id", "")), str(msg.get("code", "")))
                    elif cmd == "interrupt" and kernel is not None:
                        kernel.interrupt()
                    elif cmd == "complete" and kernel is not None:
                        kernel.send({"op": "complete", "id": str(msg.get("id", "")), "prefix": str(msg.get("prefix", ""))})
                    elif cmd == "shutdown":
                        st.kernels.stop(path)
                        listener.put({"type": "exit", "message": "The kernel was shut down."})
                except RuntimeError as e:
                    listener.put({"type": "exit", "message": str(e), "id": msg.get("id")})
        except (ConnectionError, OSError):
            pass
        finally:
            if kernel is not None:
                kernel.listeners.discard(listener)
            listener.close()
            sock.close()

    def _devices_post(self, path: str, b: Dict[str, Any]) -> dict:
        """Device actions from the Android view, the forge-device command and the agent."""
        from . import devices, vdevice
        st = STATE
        assert st is not None
        if path == "/api/devices/target":
            serial, port = devices.resolve(str(b.get("serial") or ""), b.get("port"))
            devices.set_target(serial, port)
            return {"ok": True, "message": f"Builds, logcat and adb now use {serial}."}
        if path == "/api/devices/pair":
            return devices.pair(str(b.get("address", "")), str(b.get("code", "")))
        if path == "/api/devices/connect":
            return devices.connect(str(b.get("address", "")))
        if path == "/api/devices/disconnect":
            serial, port = devices.resolve(str(b.get("serial") or ""), b.get("port"))
            return devices.disconnect(serial, port)
        if path == "/api/devices/input":
            serial, port = devices.resolve(b.get("serial"), b.get("port"))
            vd = vdevice.by_serial(serial) if port == devices.FORGE_PORT else None
            if vd:
                vdevice.touch_active(vd)
            r = devices.send_input(serial, port, str(b.get("kind", "")), b)
            if not r.get("ok") and vd and vdevice.VERSIONS[vd["version"]]["backend"] == "arm":
                from . import armvm   # an emulated ARM device takes input through QEMU until Android's adb is up
                r = armvm.send_input(vd, str(b.get("kind", "")), b)
            return r
        if path == "/api/devices/look":
            serial, port = devices.resolve(b.get("serial"), b.get("port"))
            return devices.set_look(serial, port, {k: b[k] for k in ("profile", "dark", "font_scale", "rotation") if k in b})
        if path == "/api/devices/behave":
            serial, port = devices.resolve(b.get("serial"), b.get("port"))
            return devices.behave(serial, port, str(b.get("preset", "")), str(b.get("package", "")).strip())
        if path == "/api/vd/create":
            d = vdevice.create(str(b.get("version", "")), str(b.get("type", "phone")), str(b.get("name", "")), str(b.get("persona", "")))
            return {"ok": True, "message": f"Created {d['name']}.", "device": d}
        if path == "/api/vd/rename":
            vdevice.rename(str(b.get("id", "")), str(b.get("name", "")))
            return {"ok": True, "message": "Renamed."}
        if path == "/api/vd/start":
            def ready(d: dict) -> None:
                if b.get("make_target", True):
                    devices.set_target(f"127.0.0.1:{d['port']}", devices.FORGE_PORT)
            return vdevice.start(str(b.get("id", "")), ready)
        if path == "/api/vd/stop":
            return vdevice.stop(str(b.get("id", "")))
        if path == "/api/vd/download":
            return vdevice.download(str(b.get("version", "")), lambda: st.small_memory_block("a download", 1200, 3300))
        if path.startswith("/api/vd/updates/") or path == "/api/vd/move":
            from . import updates
            gate = lambda: st.small_memory_block("an image update", 1200, 3300)
            if path == "/api/vd/updates/check":
                r = {"ok": True, "message": "Checking every image source; the results appear here in a minute or two."}
                threading.Thread(target=updates.check_all, args=(gate,), name="vd-updates-check", daemon=True).start()
                return r
            if path == "/api/vd/updates/apply":
                return updates.apply(str(b.get("version", "")), str(b.get("kind", "update")), gate)
            if path == "/api/vd/updates/apply_all":
                return updates.apply_all(gate)
            if path == "/api/vd/move":
                return updates.move(str(b.get("id", "")), str(b.get("version", "")))
        raise ValueError("unknown device action")

    def _ws_logcat(self, q: Dict[str, list]) -> None:
        """A live logcat for the Android view; it ends when the window closes or at its limits."""
        from .android import Logcat
        if not _origin_ok(self):
            self.send_response(403); self.send_header("Content-Length", "0"); self.end_headers()
            return
        sock = handshake(self)
        if sock is None:
            return
        from . import devices
        try:
            serial, port = devices.resolve(q.get("serial", [""])[0] or None, int(q.get("port", ["0"])[0] or 0) or None)
        except ValueError as e:
            sock.send_text(json.dumps({"type": "info", "text": str(e)}))
            sock.send_text(json.dumps({"type": "end"}))
            sock.close()
            return
        lc = Logcat(sock.send_text, q.get("app_id", [""])[0][:200], q.get("level", ["V"])[0][:1], serial, port,
                    devices.display_name(serial, port))
        try:
            lc.run()
            while True:
                op, _data = sock.recv()
                if op == 8:
                    break
        except (ConnectionError, OSError):
            pass
        finally:
            lc.stop()
            sock.close()

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
        # Every folder has its own shells (a window's "main" shell is not another window's), as in Cursor.
        term_id = folder + "\0" + q.get("id", ["main"])[0][:40]
        cols = int(q.get("cols", ["120"])[0] or 120)
        rows = int(q.get("rows", ["30"])[0] or 30)
        st.terminals.serve(sock, term_id, folder, cols, rows)


def _origin_ok(handler) -> bool:
    origin = handler.headers.get("Origin")
    return not origin or urllib.parse.urlparse(origin).netloc.lower() == (handler.headers.get("Host") or "").lower()


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
    # "./" makes the path relative to the folder, which may be a subfolder of the repository.
    code, out = _git(root, ["show", "HEAD:./" + rel])
    return out if code == 0 else ""


def _git_show_index(root: str, rel: str) -> Optional[str]:
    """A file as it is staged (the index); None when it is not in the index."""
    code, out = _git(root, ["show", ":./" + rel])
    return out if code == 0 else None


def _git_status(root: str) -> dict:
    """The folder's git state. The folder may be the repository, a subfolder of one, a worktree or a submodule; paths are
    relative to the folder (git reports them relative to the repository, with -z so spaces, quotes and other characters
    come through as they are, and renames as their new name)."""
    import subprocess
    try:
        inside = subprocess.run(["git", "-C", root, "rev-parse", "--is-inside-work-tree"], capture_output=True, text=True, timeout=10)
        if inside.returncode != 0 or inside.stdout.strip() != "true":
            return {"repo": False}
        prefix = subprocess.run(["git", "-C", root, "rev-parse", "--show-prefix"], capture_output=True, text=True, timeout=10).stdout.strip()
        branch = subprocess.run(["git", "-C", root, "rev-parse", "--abbrev-ref", "HEAD"], capture_output=True, text=True, timeout=10).stdout.strip()
        status = subprocess.run(["git", "-C", root, "status", "--porcelain=v1", "-z", "-uall"], capture_output=True, timeout=15).stdout
    except (subprocess.TimeoutExpired, OSError) as e:
        return {"repo": True, "error": str(e)}
    files = []
    parts = status.decode("utf-8", errors="replace").split("\0")
    i = 0
    while i < len(parts):
        entry = parts[i]
        i += 1
        if len(entry) < 4:
            continue
        x, y, path = entry[0], entry[1], entry[3:]
        if x in "RC":
            i += 1        # the next field is the old name of a rename or copy
        if prefix:
            if not path.startswith(prefix):
                continue  # a change elsewhere in the repository, outside this folder
            path = path[len(prefix):]
        files.append({"status": (entry[:2].strip() or "M"), "path": path, "staged": x not in (" ", "?"),
                      "unstaged": y != " " or x == "?"})
    ahead = ""
    try:
        ahead = subprocess.run(["git", "-C", root, "status", "-sb"], capture_output=True, text=True, timeout=10).stdout.splitlines()[0]
    except (subprocess.TimeoutExpired, OSError, IndexError):
        pass
    return {"repo": True, "branch": branch, "files": files, "summary": ahead}


# ---------------------------------------------------------------- keys (only the Forge app and Forge's own tools)
# The Terminal app forwards the server's port to the Googlebook's localhost, where every Android app can reach it.
# So every request carries a key: the Forge app's own (made by the app, kept in its private storage; the server keeps
# only its SHA-256, learned when the app pairs), or the VM key that Forge's tools in the Linux VM read from a file.
APP_KEY_FILE = os.path.join(DATA_DIR, "app-key.json")
VM_KEY_FILE = os.path.join(DATA_DIR, "vm-key")
OPEN_PATHS = {"", "/", "/api/pair", "/api/memcheck"}     # the plain sentence, pairing, and the build tools' memory check


def vm_key() -> str:
    """The key for Forge's own tools in the VM (forge-ide, scripts); made once, readable only by this user."""
    try:
        with open(VM_KEY_FILE, "r", encoding="utf-8") as fh:
            k = fh.read().strip()
        if len(k) >= 32:
            return k
    except OSError:
        pass
    import secrets
    k = secrets.token_hex(24)
    os.makedirs(DATA_DIR, exist_ok=True)
    fd = os.open(VM_KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        fh.write(k)
    return k


def app_key_hash() -> str:
    d = _load_json(APP_KEY_FILE, {})
    return str(d.get("sha256") or "") if isinstance(d, dict) else ""


def key_ok(given: str) -> bool:
    import hashlib
    import hmac
    if not given:
        return False
    if hmac.compare_digest(given, vm_key()):
        return True
    h = app_key_hash()
    return bool(h) and hmac.compare_digest(hashlib.sha256(given.encode()).hexdigest(), h)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 64
    allowed_hosts: set = set()

    def verify_request(self, request, client_address) -> bool:  # noqa: ANN001
        """Only connections from the VM's own loopback are served. The Forge app reaches the server through the Terminal
        app's port forwarding, which connects from 127.0.0.1 inside the VM; everything else (the VM's network address,
        the containers of virtual devices and WayDroid, other machines) is closed at once, because the server offers a
        shell and access to files."""
        host = str(client_address[0] if client_address else "")
        return host.startswith("127.") or host == "::1" or host.startswith("::ffff:127.")


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
    # Started by forge-ide.socket, systemd hands over the listening socket (fd 3), so the server can stop completely
    # (Quit Forge) and the next connection starts it again.
    if os.environ.get("LISTEN_PID") == str(os.getpid()) and os.environ.get("LISTEN_FDS") == "1":
        srv = Server((args.host, args.port), Handler, bind_and_activate=False)
        srv.socket.close()
        srv.socket = socket.fromfd(3, socket.AF_INET, socket.SOCK_STREAM)
        os.close(3)
        srv.server_address = srv.socket.getsockname()
    else:
        srv = Server((args.host, args.port), Handler)
    vm_key()   # made now, so Forge's tools in the VM can read it before their first request
    # Ollama and the Forge Android guard are stopped by Quit Forge and come back with the server.
    from . import quit as forge_quit
    threading.Thread(target=lambda: [print("Forge:", m, flush=True) for m in forge_quit.start_companions()],
                     name="companions", daemon=True).start()
    try:
        srv.allowed_hosts = {socket.gethostname().lower()} | set(_local_ips())
    except OSError:
        srv.allowed_hosts = set()
    print(f"Forge IDE {__version__} listening on http://{args.host}:{args.port}  workspace={STATE.workspace.root}", flush=True)
    print("Open the Forge app on the Googlebook; it connects through the Terminal app's port forwarding (allow port %d if asked)." % args.port, flush=True)
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
