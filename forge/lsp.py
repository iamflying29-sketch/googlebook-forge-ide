"""Language servers for the editor (code intelligence like in Cursor): a WebSocket <-> stdio bridge.

The browser speaks the Language Server Protocol itself (static/lsp.js); this module only starts the right server
for a language and passes messages both ways. One server per window and language; it stops when the window closes.
Output is drained continuously by a thread, so a server can never block on a full pipe.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import threading
import time
from typing import Dict, List, Optional

from .wsproto import WebSocket

NODE_BIN = os.path.expanduser("~/.local/lib/forge-lsp/node_modules/.bin")
LSP_MIN_VM_MB = 800          # free VM memory needed to start a language server
LSP_MIN_HOST_MB = 2500       # free Googlebook memory needed, when the Forge app reports it


def _bin(name: str) -> Optional[str]:
    p = os.path.join(NODE_BIN, name)
    if os.path.isfile(p):
        return p
    return shutil.which(name)


def server_command(lang: str) -> Optional[List[str]]:
    """The command for a language server, or None when none is installed for that language."""
    table = {
        "python": (["pylsp"], None),
        "javascript": (["typescript-language-server", "--stdio"], None),
        "typescript": (["typescript-language-server", "--stdio"], None),
        "shell": (["bash-language-server", "start"], None),
        "json": (["vscode-json-language-server", "--stdio"], None),
        "css": (["vscode-css-language-server", "--stdio"], None),
        "scss": (["vscode-css-language-server", "--stdio"], None),
        "less": (["vscode-css-language-server", "--stdio"], None),
        "html": (["vscode-html-language-server", "--stdio"], None),
        "c": (["clangd", "--background-index=false", "--log=error"], None),
        "cpp": (["clangd", "--background-index=false", "--log=error"], None),
        "go": (["gopls"], None),
        "rust": (["rust-analyzer"], None),
        "java": (["jdtls"], None),
    }
    entry = table.get(lang)
    if not entry:
        return None
    cmd = list(entry[0])
    exe = _bin(cmd[0])
    if not exe:
        return None
    cmd[0] = exe
    return cmd


def available() -> Dict[str, bool]:
    return {lang: server_command(lang) is not None for lang in
            ("python", "javascript", "typescript", "shell", "json", "css", "html", "c", "cpp", "go", "rust", "java")}


def _read_message(stream) -> Optional[bytes]:
    """Read one Content-Length framed message from a language server."""
    length = None
    while True:
        line = stream.readline()
        if not line:
            return None
        line = line.strip()
        if not line:
            break
        if line.lower().startswith(b"content-length:"):
            length = int(line.split(b":", 1)[1].strip())
    if length is None:
        return b""
    data = b""
    while len(data) < length:
        chunk = stream.read(length - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def serve(ws: WebSocket, lang: str, root: str, memory_ok, log_dir: str, cmd: Optional[List[str]] = None,
          what: str = "language server") -> None:
    """Bridge one browser WebSocket to a fresh language server (or debug adapter) process until either side
    closes. Both speak Content-Length framed JSON, so one bridge serves both protocols."""
    cmd = cmd or server_command(lang)
    if not cmd:
        ws.send_text(json.dumps({"forge": "error", "message": f"No {what} is installed for {lang}."}))
        ws.close()
        return
    why = memory_ok()
    if why:
        ws.send_text(json.dumps({"forge": "error", "message": why}))
        ws.close()
        return
    os.makedirs(log_dir, exist_ok=True)
    err = open(os.path.join(log_dir, f"lsp-{lang}.log"), "ab")
    env = dict(os.environ)
    env["PATH"] = NODE_BIN + os.pathsep + env.get("PATH", "")
    try:
        proc = subprocess.Popen(["nice", "-n", "5"] + cmd, cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=err, env=env, start_new_session=True)
    except OSError as e:
        ws.send_text(json.dumps({"forge": "error", "message": f"Could not start the {lang} {what}: {e}"}))
        ws.close()
        err.close()
        return
    ws.send_text(json.dumps({"forge": "ready", "lang": lang, "command": os.path.basename(cmd[0])}))
    stop = threading.Event()

    def pump_out() -> None:
        try:
            while not stop.is_set():
                msg = _read_message(proc.stdout)
                if msg is None:
                    break
                if msg:
                    ws.send_text(msg.decode("utf-8", errors="replace"))
        except (OSError, ValueError):
            pass
        finally:
            stop.set()
            try:
                ws.send_text(json.dumps({"forge": "exit", "lang": lang}))
            except OSError:
                pass

    threading.Thread(target=pump_out, name=f"lsp-{lang}-out", daemon=True).start()
    try:
        while not stop.is_set():
            op, data = ws.recv()
            if op == 8:
                break
            if op not in (1, 2) or not data:
                continue
            try:
                proc.stdin.write(b"Content-Length: " + str(len(data)).encode() + b"\r\n\r\n" + data)
                proc.stdin.flush()
            except (OSError, ValueError):
                break
    except (ConnectionError, OSError):
        pass
    finally:
        stop.set()
        try:
            proc.stdin.close()
        except OSError:
            pass
        _stop_group(proc)
        err.close()
        ws.close()


def _stop_group(proc: subprocess.Popen) -> None:
    """Stop a server and everything it started (tsserver, a debugged program, ...): it runs in its own session,
    so its process group holds all of them."""
    import signal
    try:
        pgid = os.getpgid(proc.pid)
    except OSError:
        pgid = None
    for sig, wait in ((signal.SIGTERM, 3), (signal.SIGKILL, 3)):
        try:
            if pgid is not None:
                os.killpg(pgid, sig)
            else:
                proc.send_signal(sig)
        except OSError:
            pass
        try:
            proc.wait(timeout=wait)
            if pgid is None:
                return
            # The leader is gone; give the rest of the group a moment, then make sure.
            time.sleep(0.3)
            os.killpg(pgid, 0)
        except subprocess.TimeoutExpired:
            continue
        except OSError:
            return   # no process of the group is left
