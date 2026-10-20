"""Streaming client for the Ollama HTTP API using only the standard library.

Every request has a deadline. Streams are read incrementally and can be cancelled through a
threading.Event, which closes the connection so Ollama stops generating.
"""
from __future__ import annotations

import http.client
import json
import re
import threading
import time
import urllib.parse
from typing import Any, Callable, Dict, Iterator, List, Optional


def keep_alive_value(v: Any) -> Any:
    """Ollama reads a number as seconds (a negative one keeps the model loaded until it is unloaded) and text as a
    duration with a unit ("5m", "24h"). A bare number written as text ("-1", "300") has no unit, which Ollama refuses,
    so it is sent as a number."""
    if isinstance(v, str) and re.fullmatch(r"\s*-?\d+\s*", v):
        return int(v)
    return v


class OllamaError(Exception):
    pass


class OllamaClient:
    def __init__(self, base_url: str = "http://127.0.0.1:11434", connect_timeout: float = 10.0):
        u = urllib.parse.urlparse(base_url)
        self.host = u.hostname or "127.0.0.1"
        self.port = u.port or 11434
        self.connect_timeout = connect_timeout

    # ------------------------------------------------------------------ helpers
    def _conn(self, timeout: float) -> http.client.HTTPConnection:
        return http.client.HTTPConnection(self.host, self.port, timeout=timeout)

    def _json(self, method: str, path: str, body: Optional[dict] = None, timeout: float = 30.0) -> Any:
        conn = self._conn(timeout)
        try:
            data = json.dumps(body).encode() if body is not None else None
            conn.request(method, path, body=data, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            raw = resp.read(50 * 1024 * 1024)
            if resp.status >= 400:
                raise OllamaError(f"Ollama {path} returned HTTP {resp.status}: {raw[:500].decode(errors='replace')}")
            if not raw:
                return {}
            return json.loads(raw)
        except (OSError, http.client.HTTPException) as e:
            raise OllamaError(f"Cannot reach Ollama at {self.host}:{self.port} ({e})") from e
        finally:
            conn.close()

    # ------------------------------------------------------------------ simple calls
    def version(self) -> str:
        return str(self._json("GET", "/api/version", timeout=10).get("version", "?"))

    def list_models(self) -> List[dict]:
        return list(self._json("GET", "/api/tags", timeout=20).get("models", []))

    def running(self) -> List[dict]:
        return list(self._json("GET", "/api/ps", timeout=20).get("models", []))

    def show(self, model: str) -> dict:
        return self._json("POST", "/api/show", {"name": model}, timeout=30)

    def alive(self) -> bool:
        try:
            self.version()
            return True
        except OllamaError:
            return False

    # ------------------------------------------------------------------ streaming
    def stream(self, path: str, body: dict, cancel: Optional[threading.Event] = None,
               total_timeout: float = 1800.0, idle_timeout: float = 300.0) -> Iterator[dict]:
        """Yield decoded JSON objects from a streaming Ollama endpoint.

        The connection is closed when `cancel` is set, when nothing arrives for `idle_timeout`
        seconds, or when `total_timeout` has passed. Ollama notices the closed socket and stops.
        """
        body = dict(body)
        body["stream"] = True
        conn = self._conn(idle_timeout)
        started = time.monotonic()
        stop_flag = threading.Event()

        def watchdog() -> None:
            while not stop_flag.is_set():
                if (cancel is not None and cancel.is_set()) or time.monotonic() - started > total_timeout:
                    try:
                        conn.sock and conn.sock.shutdown(2)  # type: ignore[union-attr]
                    except OSError:
                        pass
                    try:
                        conn.close()
                    except OSError:
                        pass
                    return
                stop_flag.wait(0.2)

        t = threading.Thread(target=watchdog, name="ollama-watchdog", daemon=True)
        t.start()
        try:
            conn.request("POST", path, body=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            if resp.status >= 400:
                raw = resp.read(100000)
                raise OllamaError(f"Ollama {path} returned HTTP {resp.status}: {raw[:500].decode(errors='replace')}")
            buf = b""
            while True:
                if cancel is not None and cancel.is_set():
                    return
                try:
                    chunk = resp.read1(65536) if hasattr(resp, "read1") else resp.read(65536)
                except (OSError, http.client.HTTPException) as e:
                    if cancel is not None and cancel.is_set():
                        return
                    raise OllamaError(f"Stream interrupted: {e}") from e
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if "error" in obj and not obj.get("done"):
                        raise OllamaError(str(obj["error"]))
                    yield obj
                    if obj.get("done"):
                        return
            if buf.strip():
                try:
                    yield json.loads(buf)
                except json.JSONDecodeError:
                    pass
        except (OSError, http.client.HTTPException) as e:
            if cancel is not None and cancel.is_set():
                return
            raise OllamaError(f"Cannot reach Ollama at {self.host}:{self.port} ({e})") from e
        finally:
            stop_flag.set()
            try:
                conn.close()
            except OSError:
                pass

    def chat_stream(self, model: str, messages: List[dict], options: Optional[dict] = None,
                    tools: Optional[list] = None, keep_alive: str = "30m",
                    cancel: Optional[threading.Event] = None, think: Optional[bool] = None) -> Iterator[dict]:
        body: Dict[str, Any] = {"model": model, "messages": messages, "keep_alive": keep_alive_value(keep_alive)}
        if think is not None:
            body["think"] = think
        if options:
            body["options"] = options
        if tools:
            body["tools"] = tools
        return self.stream("/api/chat", body, cancel=cancel)

    def generate(self, model: str, prompt: str, options: Optional[dict] = None, raw: bool = False,
                 keep_alive: str = "30m", timeout: float = 120.0, system: Optional[str] = None) -> dict:
        body: Dict[str, Any] = {"model": model, "prompt": prompt, "stream": False, "keep_alive": keep_alive_value(keep_alive)}
        if options:
            body["options"] = options
        if raw:
            body["raw"] = True
        if system:
            body["system"] = system
        return self._json("POST", "/api/generate", body, timeout=timeout)
