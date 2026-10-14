"""Model Context Protocol client: runs MCP servers over stdio (JSON-RPC) and exposes their tools to the agent.

Configuration: ~/.forge-ide/mcp.json
{
  "servers": {
    "filesystem": {"command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/home/you/projects"]}
  }
}
Every call has a timeout; a server that does not answer is dropped. Servers are started lazily on first use.
"""
from __future__ import annotations

import json
import os
import subprocess
import threading
import time
from typing import Any, Dict, List, Optional

CONFIG = os.path.expanduser(os.path.join(os.environ.get("FORGE_HOME", "~/.forge-ide"), "mcp.json"))


class MCPServer:
    def __init__(self, name: str, command: List[str], env: Optional[dict] = None, cwd: Optional[str] = None):
        self.name = name
        self.command = command
        self.env = env or {}
        self.cwd = cwd
        self.proc: Optional[subprocess.Popen] = None
        self.lock = threading.Lock()
        self.next_id = 1
        self.tools: List[dict] = []
        self.error: Optional[str] = None

    def start(self) -> bool:
        if self.proc and self.proc.poll() is None:
            return True
        try:
            env = dict(os.environ)
            env.update({k: str(v) for k, v in self.env.items()})
            self.proc = subprocess.Popen(self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                         cwd=self.cwd or None, env=env, text=True, bufsize=1)
        except OSError as e:
            self.error = f"cannot start: {e}"
            return False
        try:
            self._request("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                         "clientInfo": {"name": "forge-ide", "version": "1.0"}}, timeout=30)
            self._notify("notifications/initialized", {})
            res = self._request("tools/list", {}, timeout=30)
            self.tools = list(res.get("tools", []))
            self.error = None
            return True
        except Exception as e:  # noqa: BLE001
            self.error = f"handshake failed: {e}"
            self.stop()
            return False

    def stop(self) -> None:
        p = self.proc
        self.proc = None
        if p and p.poll() is None:
            try:
                p.terminate()
                p.wait(timeout=3)
            except Exception:  # noqa: BLE001
                p.kill()

    def _notify(self, method: str, params: dict) -> None:
        assert self.proc and self.proc.stdin
        self.proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": method, "params": params}) + "\n")
        self.proc.stdin.flush()

    def _request(self, method: str, params: dict, timeout: float = 60.0) -> dict:
        with self.lock:
            assert self.proc and self.proc.stdin and self.proc.stdout
            rid = self.next_id
            self.next_id += 1
            self.proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}) + "\n")
            self.proc.stdin.flush()
            deadline = time.monotonic() + timeout
            result: Optional[dict] = None
            box: Dict[str, Any] = {}

            def reader() -> None:
                while True:
                    line = self.proc.stdout.readline()  # type: ignore[union-attr]
                    if not line:
                        box["eof"] = True
                        return
                    try:
                        msg = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if msg.get("id") == rid:
                        box["msg"] = msg
                        return

            t = threading.Thread(target=reader, daemon=True)
            t.start()
            t.join(max(0.0, deadline - time.monotonic()))
            if t.is_alive():
                self.stop()
                raise TimeoutError(f"MCP server {self.name} did not answer {method} within {timeout:.0f} s; stopped it")
            if "eof" in box:
                self.stop()
                raise RuntimeError(f"MCP server {self.name} exited")
            msg = box.get("msg") or {}
            if "error" in msg:
                raise RuntimeError(str(msg["error"]))
            result = msg.get("result") or {}
            return result

    def call(self, tool: str, args: dict) -> str:
        if not self.start():
            return f"ERROR: MCP server {self.name} unavailable ({self.error})"
        res = self._request("tools/call", {"name": tool, "arguments": args}, timeout=120)
        parts = []
        for c in res.get("content", []):
            if c.get("type") == "text":
                parts.append(c.get("text", ""))
            else:
                parts.append(f"[{c.get('type')} content]")
        text = "\n".join(parts) or json.dumps(res)[:4000]
        if res.get("isError"):
            text = "ERROR from tool: " + text
        return text[:24000]


class MCP:
    _inst: Optional["MCP"] = None

    def __init__(self) -> None:
        self.servers: Dict[str, MCPServer] = {}
        self.loaded_at = 0.0
        self.lock = threading.Lock()

    @classmethod
    def get(cls) -> "MCP":
        if cls._inst is None:
            cls._inst = MCP()
        return cls._inst

    def reload(self) -> None:
        with self.lock:
            for s in self.servers.values():
                s.stop()
            self.servers = {}
            try:
                with open(CONFIG, "r", encoding="utf-8") as fh:
                    cfg = json.load(fh)
            except (OSError, json.JSONDecodeError):
                cfg = {}
            for name, spec in (cfg.get("servers") or {}).items():
                cmd = spec.get("command")
                if isinstance(cmd, str):
                    cmd = cmd.split()
                if not cmd or spec.get("disabled"):
                    continue
                self.servers[name] = MCPServer(name, list(cmd) + list(spec.get("args") or []), spec.get("env"), spec.get("cwd"))
            self.loaded_at = time.time()

    def _ensure(self) -> None:
        if not self.loaded_at or (os.path.exists(CONFIG) and os.path.getmtime(CONFIG) > self.loaded_at):
            self.reload()

    def status(self) -> List[dict]:
        self._ensure()
        out = []
        for name, s in self.servers.items():
            out.append({"name": name, "running": bool(s.proc and s.proc.poll() is None), "tools": [t.get("name") for t in s.tools],
                        "error": s.error, "command": s.command})
        return out

    def connect_all(self) -> List[dict]:
        self._ensure()
        for s in self.servers.values():
            s.start()
        return self.status()

    def tool_names(self) -> List[str]:
        return [f"mcp_{n}_{t.get('name')}" for n, s in self.servers.items() for t in s.tools]

    def specs_text(self) -> str:
        self._ensure()
        lines = []
        for n, s in self.servers.items():
            if not s.tools and not s.proc:
                s.start()
            for t in s.tools:
                props = (t.get("inputSchema") or {}).get("properties") or {}
                params = ", ".join(f'"{k}": {v.get("type", "any")}' for k, v in props.items())
                lines.append(f"- mcp_{n}_{t.get('name')}: {(t.get('description') or '').strip()[:300]}\n  arguments: {{{params}}}")
        return "\n".join(lines)

    def call_tool(self, full_name: str, args: dict) -> str:
        self._ensure()
        for n, s in self.servers.items():
            prefix = f"mcp_{n}_"
            if full_name.startswith(prefix):
                return s.call(full_name[len(prefix):], args)
        return f"ERROR: unknown MCP tool {full_name}"
