"""Integrated terminal: a bash shell on a pseudo terminal, bridged to xterm.js over a WebSocket."""
from __future__ import annotations

import fcntl
import json
import os
import pty
import select
import signal
import struct
import termios
import threading
import time
from typing import Dict, Optional

from .wsproto import WebSocket


class PtySession:
    def __init__(self, cwd: str, cols: int = 120, rows: int = 30, shell: Optional[str] = None):
        self.cwd = cwd
        env = dict(os.environ)
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["FORGE_IDE"] = "1"
        env["NO_AUTO_TMUX"] = "1"   # the user's ~/.profile auto-attaches tmux in login shells; not wanted here
        shell = shell or os.environ.get("SHELL") or "/bin/bash"
        pid, fd = pty.fork()
        if pid == 0:  # child
            try:
                os.chdir(cwd)
            except OSError:
                pass
            os.execvpe(shell, [shell, "-l"] if shell.endswith("bash") else [shell], env)
        self.pid = pid
        self.fd = fd
        self.alive = True
        self.history = bytearray()
        self.lock = threading.Lock()
        self.clients: list = []
        self.last_used = time.time()
        self.resize(cols, rows)
        self.reader = threading.Thread(target=self._read_loop, name="pty-reader", daemon=True)
        self.reader.start()

    def resize(self, cols: int, rows: int) -> None:
        cols = max(10, min(int(cols), 500))
        rows = max(3, min(int(rows), 300))
        try:
            fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        except OSError:
            pass

    def write(self, data: bytes) -> None:
        self.last_used = time.time()
        try:
            os.write(self.fd, data)
        except OSError:
            self.alive = False

    def _read_loop(self) -> None:
        while self.alive:
            try:
                r, _, _ = select.select([self.fd], [], [], 0.5)
            except (OSError, ValueError):
                break
            if not r:
                if self.pid and os.waitpid(self.pid, os.WNOHANG)[0] != 0:
                    break
                continue
            try:
                data = os.read(self.fd, 65536)
            except OSError:
                break
            if not data:
                break
            with self.lock:
                self.history += data
                if len(self.history) > 200_000:
                    del self.history[:len(self.history) - 200_000]
                clients = list(self.clients)
            for ws in clients:
                try:
                    ws.send_bytes(data)
                except OSError:
                    self.detach(ws)
        self.alive = False
        for ws in list(self.clients):
            try:
                ws.send_text(json.dumps({"type": "exit"}))
            except OSError:
                pass

    def attach(self, ws: WebSocket) -> None:
        with self.lock:
            self.clients.append(ws)
            hist = bytes(self.history)
        if hist:
            try:
                ws.send_bytes(hist)
            except OSError:
                pass

    def detach(self, ws: WebSocket) -> None:
        with self.lock:
            if ws in self.clients:
                self.clients.remove(ws)

    def kill(self) -> None:
        self.alive = False
        try:
            os.kill(self.pid, signal.SIGHUP)
            time.sleep(0.2)
            os.kill(self.pid, signal.SIGKILL)
        except (ProcessLookupError, OSError):
            pass
        try:
            os.close(self.fd)
        except OSError:
            pass
        try:
            os.waitpid(self.pid, os.WNOHANG)
        except ChildProcessError:
            pass


class TerminalManager:
    def __init__(self) -> None:
        self.sessions: Dict[str, PtySession] = {}
        self.lock = threading.Lock()

    def get_or_create(self, term_id: str, cwd: str, cols: int, rows: int) -> PtySession:
        with self.lock:
            s = self.sessions.get(term_id)
            if s is None or not s.alive:
                s = PtySession(cwd, cols, rows)
                self.sessions[term_id] = s
            return s

    def kill(self, term_id: str) -> None:
        with self.lock:
            s = self.sessions.pop(term_id, None)
        if s:
            s.kill()

    def serve(self, ws: WebSocket, term_id: str, cwd: str, cols: int, rows: int) -> None:
        sess = self.get_or_create(term_id, cwd, cols, rows)
        sess.attach(ws)
        try:
            while True:
                op, data = ws.recv()
                if op == 8:
                    break
                if op == 2:
                    sess.write(data)
                    continue
                text = data.decode("utf-8", errors="replace")
                if text.startswith("{"):
                    try:
                        msg = json.loads(text)
                    except json.JSONDecodeError:
                        sess.write(data)
                        continue
                    t = msg.get("type")
                    if t == "resize":
                        sess.resize(msg.get("cols", 80), msg.get("rows", 24))
                    elif t == "input":
                        sess.write(str(msg.get("data", "")).encode())
                    elif t == "ping":
                        ws.send_text(json.dumps({"type": "pong"}))
                    continue
                sess.write(data)
        except (ConnectionError, OSError):
            pass
        finally:
            sess.detach(ws)
            ws.close()
