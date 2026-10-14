"""Language servers for the editor (code intelligence like in Cursor): a WebSocket <-> stdio bridge.

The browser speaks the Language Server Protocol itself (static/lsp.js); this module only starts the right server
for a language and passes messages both ways. One server per window and language; it stops when the window closes.
Output is drained continuously by a thread, so a server can never block on a full pipe.
"""
from __future__ import annotations

import json
import os
import re
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


DATA = os.path.expanduser("~/.forge-ide")
# Languages whose servers are large Java or Rust programs: (free VM MB needed, free Googlebook MB needed, memory cap).
# The Googlebook must keep 2.3 GB free beyond the cap, because the VM borrows every new page from it.
HEAVY = {
    "kotlin": (2000, 4100, "1800M"),
    "java": (1700, 3800, "1500M"),
    "rust": (1700, 3800, "1500M"),
    "c": (1000, 3300, "1000M"),
    "cpp": (1000, 3300, "1000M"),
    "go": (1000, 3300, "1000M"),
}


def _build_files(root: str) -> List[str]:
    import glob
    found: List[str] = []
    for pattern in ("settings.gradle*", "build.gradle*", "*/build.gradle*", "*/*/build.gradle*", "pom.xml", "*/pom.xml",
                    "gradle/libs.versions.toml"):
        found += glob.glob(os.path.join(root, pattern))
    return found


def needs_for(lang: str, root: str) -> Optional[tuple]:
    """(free VM MB, free Googlebook MB, memory cap) for a language server, or None for small servers. In a Gradle or
    Maven project the Kotlin server runs a Gradle import (with its own Gradle daemon) every time it starts, even when
    it imported the project before (seen in its log on 2026-10-14), so it always gets the bigger cap there."""
    if lang == "kotlin" and _build_files(root):
        return (2600, 4700, "2400M")
    return HEAVY.get(lang)


def server_command(lang: str, root: str = "") -> Optional[List[str]]:
    """The command for a language server, or None when none is installed for that language."""
    from . import toolchains
    tp = toolchains.paths()
    if lang == "kotlin":
        k = tp["kotlin_lsp"]
        exe = os.path.join(k, "bin", "intellij-server") if k else ""
        if not os.access(exe, os.X_OK):
            return None
        # JetBrains' Kotlin language server; --data-sharing=none means it sends nothing to anyone.
        return [exe, "--stdio", "--data-sharing=none", "--system-path=" + os.path.join(DATA, "lsp-cache", "kotlin")]
    if lang == "java":
        launcher = os.path.join(tp["jdtls"], "bin", "jdtls")
        if not os.path.isfile(launcher):
            return None
        import hashlib
        ws_id = hashlib.sha1((root or "default").encode()).hexdigest()[:12]
        return ["python3", launcher, "--jvm-arg=-Xms256m", "--jvm-arg=-Xmx1024m", "--jvm-arg=-XX:+UseSerialGC",
                "-configuration", os.path.join(DATA, "lsp-cache", "jdtls", "config"),
                "-data", os.path.join(DATA, "lsp-cache", "jdtls", "ws-" + ws_id)]
    if lang == "xml":
        exe = tp["lemminx"]
        return [exe] if exe and os.access(exe, os.X_OK) else None
    if lang == "rust":
        exe = os.path.join(tp["cargo"], "rust-analyzer") if tp["cargo"] else ""
        return [exe] if os.access(exe, os.X_OK) else None
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
        "c": (["clangd", "--background-index=false", "--log=error", "-j=2"], None),
        "cpp": (["clangd", "--background-index=false", "--log=error", "-j=2"], None),
        "go": (["gopls"], None),
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


def init_options(lang: str, root: str) -> Optional[dict]:
    """Startup settings Forge adds to the editor's initialize request for some servers."""
    from . import toolchains
    if lang == "rust":
        # Fewer threads and a smaller cache keep rust-analyzer light; clippy runs on save.
        return {"cargo": {"buildScripts": {"enable": True}}, "procMacro": {"enable": True}, "checkOnSave": True,
                "check": {"command": "clippy"}, "numThreads": 2, "cachePriming": {"numThreads": 1}, "lru": {"capacity": 64}}
    if lang == "xml":
        # Android XML files have no DTD or schema; without this every file gets a "No grammar constraints" note.
        return {"settings": {"xml": {"validation": {"noGrammar": "ignore"}}}}
    if lang == "java":
        tp = toolchains.paths()
        libs: List[str] = []
        if tp["sdk"] and toolchains.is_android_project(root):
            import glob
            jars = sorted(glob.glob(os.path.join(tp["sdk"], "platforms", "android-*", "android.jar")))
            if jars:
                libs.append(jars[-1])   # Android classes for Java files in Android projects
        java = {"import": {"gradle": {"enabled": True, "wrapper": {"enabled": True}}},
                "autobuild": {"enabled": True}, "maxConcurrentBuilds": 1,
                "project": {"referencedLibraries": libs + ["lib/**/*.jar", "libs/**/*.jar"]}}
        if tp["java_home"]:
            java["configuration"] = {"runtimes": [{"name": "JavaSE-21", "path": tp["java_home"], "default": True}]}
        return {"settings": {"java": java}}
    return None


def available() -> Dict[str, bool]:
    return {lang: server_command(lang) is not None for lang in
            ("python", "javascript", "typescript", "shell", "json", "css", "html", "xml", "c", "cpp", "go", "rust",
             "java", "kotlin")}


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


# (free VM MB, free Googlebook MB, memory cap), measured on 2026-10-14. The server's indexes live in native memory
# outside its Java heap, so an Android project (android.jar) needs clearly more than a plain Kotlin project.
KOTLIN_LIGHT_NEEDS = (2000, 4100, "1800M")            # server alone, plain Kotlin: peak about 1.0 GB
KOTLIN_LIGHT_ANDROID_NEEDS = (2700, 4700, "2400M")    # server alone, Android project: peak about 1.7 GB
KOTLIN_GRADLE_NEEDS = (2600, 4700, "2400M")           # server plus its Gradle import, plain Kotlin
KOTLIN_GRADLE_ANDROID_NEEDS = (3500, 5500, "3200M")   # server plus the Android Gradle Plugin import, about 3 GB


def kotlin_plan(root: str, preference: str, host_free_mb: Optional[float]) -> tuple:
    """(use_gradle_import, needs). Auto uses the full Gradle import when the Googlebook has room for it and the
    light project model otherwise; loose folders always use the light model, which also gives them error checking."""
    from .toolchains import is_android_project
    android = is_android_project(root)
    gradle_needs = KOTLIN_GRADLE_ANDROID_NEEDS if android else KOTLIN_GRADLE_NEEDS
    gradle_project = bool(_build_files(root))
    if gradle_project and (preference == "gradle" or (preference == "auto" and (host_free_mb or 0) >= gradle_needs[1])):
        return True, gradle_needs
    return False, (KOTLIN_LIGHT_ANDROID_NEEDS if android else KOTLIN_LIGHT_NEEDS)


def serve(ws: WebSocket, lang: str, root: str, memory_ok, log_dir: str, cmd: Optional[List[str]] = None,
          what: str = "language server", cap: Optional[str] = None, needs: Optional[tuple] = None,
          kotlin_pref: str = "auto", host_free_mb: Optional[float] = None) -> None:
    """Bridge one browser WebSocket to a fresh language server (or debug adapter) process until either side
    closes. Both speak Content-Length framed JSON, so one bridge serves both protocols. Large servers run inside
    a systemd scope with a memory cap, so a runaway server is stopped instead of squeezing the system."""
    from . import toolchains
    cmd = cmd or server_command(lang, root)
    if not cmd:
        ws.send_text(json.dumps({"forge": "error", "message": f"No {what} is installed for {lang}."}))
        ws.close()
        return
    heavy = needs_for(lang, root)
    shadow = None
    if lang == "kotlin" and what == "language server":
        use_gradle, heavy = kotlin_plan(root, kotlin_pref, host_free_mb)
        if not use_gradle:
            from .kotlin_workspace import shadow_dir
            shadow = shadow_dir(root)   # Forge's project file in its cache: the server needs no Gradle run
    vm_need, host_need = needs or ((heavy[0], heavy[1]) if heavy else (LSP_MIN_VM_MB, LSP_MIN_HOST_MB))
    cap = cap or (heavy[2] if heavy else None)
    why = memory_ok(vm_need, host_need)
    if why:
        ws.send_text(json.dumps({"forge": "error", "message": why}))
        ws.close()
        return
    os.makedirs(log_dir, exist_ok=True)
    err = open(os.path.join(log_dir, f"lsp-{lang}.log"), "ab")
    env = toolchains.tool_env()
    env["PATH"] = NODE_BIN + os.pathsep + env.get("PATH", "")
    argv = ["nice", "-n", "5"] + cmd
    unit = None
    if cap and shutil.which("systemd-run"):
        prefix, unit = toolchains.scope_argv(f"{'lsp' if what == 'language server' else 'debug'}-{lang}", cap)
        argv = prefix + argv
    opts = init_options(lang, root) if what == "language server" else None
    try:
        proc = subprocess.Popen(argv, cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=err, env=env, start_new_session=True)
    except OSError as e:
        ws.send_text(json.dumps({"forge": "error", "message": f"Could not start the {lang} {what}: {e}"}))
        ws.close()
        err.close()
        return
    ws.send_text(json.dumps({"forge": "ready", "lang": lang, "command": os.path.basename(cmd[0]),
                             "mode": ("light project model" if shadow else ("Gradle import" if lang == "kotlin" else ""))}))
    folder_requests: set = set()    # ids of the server's workspace/workspaceFolders requests (light mode answers them)
    stop = threading.Event()

    def pump_out() -> None:
        try:
            while not stop.is_set():
                msg = _read_message(proc.stdout)
                if msg is None:
                    break
                if msg:
                    if shadow and b"workspace/workspaceFolders" in msg:
                        try:
                            m = json.loads(msg)
                            if m.get("method") == "workspace/workspaceFolders" and "id" in m:
                                folder_requests.add(m["id"])
                        except ValueError:
                            pass
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
            if opts is not None or shadow:
                # Add Forge's startup settings to the editor's initialize request. In Kotlin light mode the server's
                # project folder is Forge's shadow folder (with workspace.json); the files keep their real paths.
                try:
                    msg = json.loads(data)
                    changed = False
                    if msg.get("method") == "initialize":
                        params = msg.setdefault("params", {})
                        if opts is not None:
                            params.setdefault("initializationOptions", opts)
                            opts = None
                        if shadow:
                            uri = "file://" + shadow
                            params["rootUri"], params["rootPath"] = uri, shadow
                            params["workspaceFolders"] = [{"uri": uri, "name": os.path.basename(root) or "project"}]
                        changed = True
                    elif shadow and msg.get("id") in folder_requests and "result" in msg:
                        folder_requests.discard(msg.get("id"))
                        msg["result"] = [{"uri": "file://" + shadow, "name": os.path.basename(root) or "project"}]
                        changed = True
                    if changed:
                        data = json.dumps(msg).encode()
                except ValueError:
                    pass
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
        toolchains.stop_scope(unit)   # also ends helpers it started in their own session (a Gradle daemon)
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
