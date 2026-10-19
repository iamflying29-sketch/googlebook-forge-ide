"""Client for a llama.cpp `llama-server` (the Forge GPU engine running on the Googlebook's Android side).

It presents the same interface as OllamaClient (chat_stream / generate / list_models / running / alive) and
yields Ollama-shaped dictionaries, so the agent code does not care which engine answers.
"""
from __future__ import annotations

import http.client
import json
import threading
import time
import urllib.parse
from typing import Any, Dict, Iterator, List, Optional

from .ollama_client import OllamaError


class LlamaCppClient:
    def __init__(self, base_url: str, api_key: str = "", connect_timeout: float = 5.0):
        u = urllib.parse.urlparse(base_url)
        self.host = u.hostname or "127.0.0.1"
        self.port = u.port or 8081
        self.api_key = api_key
        self.connect_timeout = connect_timeout
        self.base_url = base_url

    def _headers(self) -> Dict[str, str]:
        h = {"Content-Type": "application/json"}
        if self.api_key:
            h["Authorization"] = "Bearer " + self.api_key
        return h

    def _conn(self, timeout: float) -> http.client.HTTPConnection:
        return http.client.HTTPConnection(self.host, self.port, timeout=timeout)

    def _json(self, method: str, path: str, body: Optional[dict] = None, timeout: float = 30.0) -> Any:
        conn = self._conn(timeout)
        try:
            conn.request(method, path, body=json.dumps(body).encode() if body is not None else None, headers=self._headers())
            resp = conn.getresponse()
            raw = resp.read(20 * 1024 * 1024)
            if resp.status >= 400:
                raise OllamaError(f"GPU engine {path} returned HTTP {resp.status}: {raw[:400].decode(errors='replace')}")
            return json.loads(raw) if raw else {}
        except (OSError, http.client.HTTPException) as e:
            raise OllamaError(f"Cannot reach the GPU engine at {self.host}:{self.port} ({e})") from e
        finally:
            conn.close()

    # ---------------------------------------------------------------- status
    def alive(self) -> bool:
        try:
            r = self._json("GET", "/health", timeout=5)
            return r.get("status") in ("ok", "no slot available")
        except OllamaError:
            return False

    def wait_ready(self, timeout: float = 300.0, on_wait=None, cancel: Optional[threading.Event] = None) -> bool:
        """Wait until the engine answers (it pauses while the vision model uses the GPU)."""
        deadline = time.monotonic() + timeout
        told = False
        while time.monotonic() < deadline:
            if self.alive():
                return True
            if cancel is not None and cancel.is_set():
                return False
            if not told and on_wait is not None:
                on_wait()
                told = True
            time.sleep(1.0)
        return False

    def health(self) -> dict:
        try:
            return self._json("GET", "/health", timeout=5)
        except OllamaError as e:
            return {"status": "down", "error": str(e)}

    def props(self) -> dict:
        try:
            return self._json("GET", "/props", timeout=10)
        except OllamaError:
            return {}

    def version(self) -> str:
        p = self.props()
        return str(p.get("build_info") or "llama.cpp")

    def list_models(self) -> List[dict]:
        try:
            r = self._json("GET", "/v1/models", timeout=10)
        except OllamaError:
            return []
        out = []
        for m in r.get("data", []):
            out.append({"name": m.get("id", "gpu-model"), "model": m.get("id"), "details": {"parameter_size": "", "family": "llama.cpp"}})
        return out

    def running(self) -> List[dict]:
        if not self.alive():
            return []
        p = self.props()
        name = (p.get("model_path") or "gpu-model").split("/")[-1]
        return [{"name": name, "model": name, "size": 0, "details": {"family": "llama.cpp (GPU engine)"}}]

    # ---------------------------------------------------------------- generation
    @staticmethod
    def _options_to_body(options: Optional[dict]) -> dict:
        o = options or {}
        body: Dict[str, Any] = {}
        if "temperature" in o:
            body["temperature"] = o["temperature"]
        if "top_p" in o:
            body["top_p"] = o["top_p"]
        if "top_k" in o:
            body["top_k"] = o["top_k"]
        if "repeat_penalty" in o:
            body["repeat_penalty"] = o["repeat_penalty"]
        if "num_predict" in o and int(o["num_predict"]) > 0:
            body["max_tokens"] = int(o["num_predict"])
        if o.get("stop"):
            body["stop"] = [s for s in o["stop"] if not s.startswith("<|")]  # the engine handles its own special tokens
        if o.get("seed") not in (None, "", 0):
            body["seed"] = int(o["seed"])
        return body

    def chat_stream(self, model: str, messages: List[dict], options: Optional[dict] = None,
                    tools: Optional[list] = None, keep_alive: str = "", cancel: Optional[threading.Event] = None) -> Iterator[dict]:
        """Stream a chat completion; yields Ollama-shaped chunks: {"message": {"content": ...}, "done": bool, ...}."""
        body = {"model": model or "gpu-model", "messages": [{"role": m["role"], "content": m.get("content", "")} for m in messages],
                "stream": True, "stream_options": {"include_usage": True}, "cache_prompt": True, "timings_per_token": False,
                # Models with a built-in thinking mode (Qwen3.5, Gemma 4) would otherwise think at length before every
                # reply; Forge keeps its own short "thought process" in the prompt instead. Llama 3.1 ignores this.
                "chat_template_kwargs": {"enable_thinking": False}}
        body.update(self._options_to_body(options))
        conn = self._conn(300.0)
        started = time.monotonic()
        stop_flag = threading.Event()

        def watchdog() -> None:
            while not stop_flag.is_set():
                if (cancel is not None and cancel.is_set()) or time.monotonic() - started > 1800:
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

        threading.Thread(target=watchdog, name="gpu-engine-watchdog", daemon=True).start()
        content_total = 0
        usage: Dict[str, Any] = {}
        timings: Dict[str, Any] = {}
        finish = None
        t_first: Optional[float] = None
        try:
            conn.request("POST", "/v1/chat/completions", body=json.dumps(body).encode(), headers=self._headers())
            resp = conn.getresponse()
            if resp.status >= 400:
                raw = resp.read(100000)
                raise OllamaError(f"GPU engine returned HTTP {resp.status}: {raw[:400].decode(errors='replace')}")
            buf = b""
            while True:
                if cancel is not None and cancel.is_set():
                    return
                chunk = resp.read1(65536) if hasattr(resp, "read1") else resp.read(65536)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    line = line.strip()
                    if not line.startswith(b"data:"):
                        continue
                    payload = line[5:].strip()
                    if payload == b"[DONE]":
                        buf = b""
                        break
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    if "error" in obj:
                        raise OllamaError(str(obj["error"]))
                    if obj.get("usage"):
                        usage = obj["usage"]
                    if obj.get("timings"):
                        timings = obj["timings"]
                    for ch in obj.get("choices", []):
                        delta = ch.get("delta", {}).get("content")
                        if delta:
                            if t_first is None:
                                t_first = time.monotonic()
                            content_total += len(delta)
                            yield {"message": {"role": "assistant", "content": delta}, "done": False}
                        if ch.get("finish_reason"):
                            finish = ch["finish_reason"]
                if finish and usage:
                    break
        except (OSError, http.client.HTTPException) as e:
            if cancel is not None and cancel.is_set():
                return
            raise OllamaError(f"GPU engine stream interrupted: {e}") from e
        finally:
            stop_flag.set()
            try:
                conn.close()
            except OSError:
                pass
        total = time.monotonic() - started
        pe = int(usage.get("prompt_tokens") or timings.get("prompt_n") or 0)
        ec = int(usage.get("completion_tokens") or timings.get("predicted_n") or 0)
        ped = int(timings.get("prompt_ms", 0) * 1e6) if timings.get("prompt_ms") else (int((t_first - started) * 1e9) if t_first else 0)
        ed = int(timings.get("predicted_ms", 0) * 1e6) if timings.get("predicted_ms") else (int((time.monotonic() - t_first) * 1e9) if t_first else 0)
        yield {"message": {"role": "assistant", "content": ""}, "done": True, "done_reason": finish or "stop",
               "prompt_eval_count": pe, "prompt_eval_duration": ped, "eval_count": ec, "eval_duration": ed,
               "total_duration": int(total * 1e9), "load_duration": 0}

    def generate(self, model: str, prompt: str, options: Optional[dict] = None, raw: bool = False,
                 keep_alive: str = "", timeout: float = 120.0, system: Optional[str] = None) -> dict:
        msgs = []
        if system:
            msgs.append({"role": "system", "content": system})
        msgs.append({"role": "user", "content": prompt})
        body = {"model": model or "gpu-model", "messages": msgs, "stream": False, "cache_prompt": True}
        body.update(self._options_to_body(options))
        r = self._json("POST", "/v1/chat/completions", body, timeout=timeout)
        text = ""
        for ch in r.get("choices", []):
            text += ch.get("message", {}).get("content", "") or ""
        u = r.get("usage", {})
        return {"response": text, "prompt_eval_count": u.get("prompt_tokens", 0), "eval_count": u.get("completion_tokens", 0)}
