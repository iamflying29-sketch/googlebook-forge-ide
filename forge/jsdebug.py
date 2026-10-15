"""JavaScript and TypeScript debugging through Microsoft's js-debug (its standalone Debug Adapter Protocol server).

js-debug works in two steps: the first connection only launches, then the server asks the client (with a
startDebugging request) to open a second connection for the program that actually runs. The editor speaks to one
connection, so this bridge does the second step itself: it answers startDebugging, opens the child connection,
repeats the editor's setup there (initialize, breakpoints, configurationDone) and from then on routes the editor's
requests to the child. Every socket is read continuously on its own thread, the server runs inside a memory cap,
and it stops when the editor closes the debugger."""
from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import threading
import time
from typing import Dict, Optional

from .lsp import _read_message, _stop_group
from .wsproto import WebSocket

JSDEBUG = os.path.expanduser("~/.local/lib/forge-jsdebug/v1.140.0/js-debug/src/dapDebugServer.js")
TMP = os.path.expanduser("~/.forge-ide/jsdebug-tmp")   # js-debug leaves a folder per start; they all stay in here
TRACE_FLAG = os.path.expanduser("~/.forge-ide/jsdebug-trace")   # while this file exists, the bridge logs every message


def available() -> bool:
    return os.path.isfile(JSDEBUG) and bool(shutil.which("node"))


def _frame(obj: dict) -> bytes:
    data = json.dumps(obj).encode()
    return b"Content-Length: " + str(len(data)).encode() + b"\r\n\r\n" + data


class Conn:
    def __init__(self, port: int, name: str = "", log=None):
        self.name, self.log = name, log
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.sock.settimeout(None)
        self.stream = self.sock.makefile("rb")
        self.lock = threading.Lock()

    def send(self, obj: dict) -> None:
        if self.log and os.path.exists(TRACE_FLAG):
            self.log.write(f"{time.time():.3f} -> {self.name} {obj.get('type')} {obj.get('command')} {json.dumps(obj.get('arguments'))[:160]}\n".encode())
        with self.lock:
            self.sock.sendall(_frame(obj))

    def close(self) -> None:
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


def serve(ws: WebSocket, root: str, memory_ok, log_dir: str) -> None:
    from . import toolchains
    if not available():
        ws.send_text(json.dumps({"forge": "error", "message": "js-debug is not installed (install-extras.sh debug)."}))
        ws.close()
        return
    why = memory_ok(700, 3300)
    if why:
        ws.send_text(json.dumps({"forge": "error", "message": why}))
        ws.close()
        return
    os.makedirs(TMP, exist_ok=True)
    os.makedirs(log_dir, exist_ok=True)
    log = open(os.path.join(log_dir, "jsdebug.log"), "ab")
    prefix, unit = toolchains.scope_argv("debug-js", "1G")
    env = dict(toolchains.tool_env(), TMPDIR=TMP)
    proc = subprocess.Popen(prefix + ["nice", "-n", "5", shutil.which("node"), JSDEBUG, "0", "127.0.0.1"], cwd=root,
                            stdout=subprocess.PIPE, stderr=log, stdin=subprocess.DEVNULL, env=env, start_new_session=True)
    port_box: Dict[str, int] = {}
    ready = threading.Event()

    def drain() -> None:   # read the server's output for as long as it runs, so it never waits on a full pipe
        for line in proc.stdout:
            m = re.search(rb"listening at [^\s]*:(\d+)", line)
            if m and not ready.is_set():
                port_box["port"] = int(m.group(1))
                ready.set()
            log.write(line)
            log.flush()

    threading.Thread(target=drain, name="jsdebug-out", daemon=True).start()
    stop = threading.Event()
    conns: Dict[str, Optional[Conn]] = {"parent": None, "child": None}
    stored: Dict[str, object] = {"initialize": {}, "breakpoints": {}, "exceptions": None, "configured": False}
    own: Dict[int, threading.Event] = {}
    seq = [1_000_000]   # the bridge's own requests; the editor's use small numbers

    def cleanup() -> None:
        stop.set()
        for c in conns.values():
            if c:
                c.close()
        _stop_group(proc)
        toolchains.stop_scope(unit)
        log.close()

    if not ready.wait(20):
        ws.send_text(json.dumps({"forge": "error", "message": "The JavaScript debugger did not start."}))
        ws.close()
        cleanup()
        return

    def own_request(conn: Conn, command: str, arguments: dict, wait: float = 0) -> None:
        seq[0] += 1
        s = seq[0]
        own[s] = threading.Event()
        conn.send({"seq": s, "type": "request", "command": command, "arguments": arguments})
        if wait:
            own[s].wait(wait)

    def start_child(req: dict, parent: Conn) -> None:
        parent.send({"seq": 0, "type": "response", "request_seq": req["seq"], "command": "startDebugging", "success": True})
        if conns["child"] is not None:
            return   # one program per session; worker threads and child processes of it are not followed
        args = req.get("arguments") or {}
        child = Conn(port_box["port"], "child", log)
        conns["child"] = child
        threading.Thread(target=pump, args=(child, "child"), name="jsdebug-child", daemon=True).start()
        own_request(child, "initialize", dict(stored["initialize"]) or {"adapterID": "pwa-node"}, wait=20)
        own_request(child, args.get("request", "launch"), args.get("configuration") or {})   # js-debug's own keys only

    def configure_child(child: Conn) -> None:
        """Send the editor's breakpoints to the child. js-debug announces "initialized" on the child twice (once at
        once and again when it has attached to the running program), and only breakpoints sent after the second one
        bind in TypeScript through source maps, so this runs after each one. The program starts at configurationDone,
        which the child gets only once the editor has sent its own (it is passed on below when it comes later)."""
        for bp in list(stored["breakpoints"].values()):
            own_request(child, "setBreakpoints", bp)
        if stored["exceptions"] is not None:
            own_request(child, "setExceptionBreakpoints", stored["exceptions"])
        if stored["configured"]:
            own_request(child, "configurationDone", {})

    def pump(conn: Conn, name: str) -> None:
        try:
            while not stop.is_set():
                msg = _read_message(conn.stream)
                if msg is None:
                    break
                if not msg:
                    continue
                m = json.loads(msg)
                if os.path.exists(TRACE_FLAG) and m.get("event") not in ("loadedSource", "output"):
                    log.write(f"{time.time():.3f} <- {name} {m.get('type')} {m.get('command') or m.get('event')} {json.dumps(m.get('body'))[:160]}\n".encode())
                if m.get("type") == "response" and m.get("request_seq") in own:
                    own.pop(m["request_seq"]).set()
                    if m.get("command") == "setBreakpoints" or not m.get("success", True):
                        log.write(("bridge: " + json.dumps({k: m.get(k) for k in ("command", "success", "message", "body")})[:600] + "\n").encode())
                        log.flush()
                    continue
                if m.get("type") == "request":
                    if m.get("command") == "startDebugging" and name == "parent":
                        threading.Thread(target=start_child, args=(m, conn), daemon=True).start()
                    else:   # requests the editor does not support (a terminal, a nested program)
                        conn.send({"seq": 0, "type": "response", "request_seq": m["seq"], "command": m.get("command"),
                                   "success": m.get("command") == "startDebugging"})
                    continue
                if name == "child" and m.get("type") == "event" and m.get("event") == "initialized":
                    threading.Thread(target=configure_child, args=(conn,), daemon=True).start()
                if name == "parent" and conns["child"] is not None and m.get("type") == "event" and m.get("event") in ("terminated", "exited"):
                    continue   # the launcher ends; the program's own session reports its end
                ws.send_text(msg.decode("utf-8", errors="replace"))
        except (OSError, ValueError):
            pass
        finally:
            if name == "child" or conns["child"] is None:
                stop.set()
                try:
                    ws.send_text(json.dumps({"forge": "exit", "lang": "javascript"}))
                except OSError:
                    pass

    try:
        parent = Conn(port_box["port"], "parent", log)
    except OSError as e:
        ws.send_text(json.dumps({"forge": "error", "message": f"Cannot reach the JavaScript debugger: {e}"}))
        ws.close()
        cleanup()
        return
    conns["parent"] = parent
    threading.Thread(target=pump, args=(parent, "parent"), name="jsdebug-parent", daemon=True).start()
    ws.send_text(json.dumps({"forge": "ready", "lang": "javascript", "command": "js-debug"}))
    try:
        while not stop.is_set():
            op, data = ws.recv()
            if op == 8:
                break
            if op not in (1, 2) or not data:
                continue
            m = json.loads(data)
            cmd, args = m.get("command"), m.get("arguments") or {}
            if cmd == "initialize":
                args["supportsStartDebuggingRequest"] = True   # the bridge answers startDebugging for the editor
                m["arguments"] = args
                stored["initialize"] = args
            elif cmd == "setBreakpoints":
                stored["breakpoints"][json.dumps((args.get("source") or {}).get("path"))] = args
            elif cmd == "setExceptionBreakpoints":
                stored["exceptions"] = args
            child = conns["child"]
            if cmd == "configurationDone":
                stored["configured"] = True
                parent.send(m)
                if child is not None:
                    seq[0] += 1
                    own[seq[0]] = threading.Event()
                    child.send(dict(m, seq=seq[0]))
                continue
            if cmd in ("disconnect", "terminate"):
                for c in (child, parent):
                    if c:
                        try:
                            c.send(m)
                        except OSError:
                            pass
                time.sleep(0.3)
                break
            (child if child is not None and cmd not in ("initialize", "launch", "attach") else parent).send(m)
    except (ConnectionError, OSError, ValueError):
        pass
    finally:
        cleanup()
        ws.close()
