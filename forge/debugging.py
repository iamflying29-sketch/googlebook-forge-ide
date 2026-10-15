"""Run and Debug for more than Python: which debug adapter serves a file, and the build step before a native debug.

- Python: debugpy.
- C and C++: GDB's own Debug Adapter Protocol mode (gdb --interpreter=dap, GDB 14 or newer). Forge compiles the
  open file with debug information first (gcc or g++ -g -O0), unless you name an existing program.
- Rust: rust-gdb (rustup's GDB wrapper with Rust's pretty printers) in the same mode, after `forge-cargo build`.
- Kotlin and Java: the debugger built into JetBrains' Kotlin language server, reached through the Kotlin server the
  editor already runs for the folder (so no second large Java process starts). Forge asks it for a debug port and
  bridges that port to the window."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from typing import Optional

LANGS = {".py": "python", ".c": "c", ".h": "c", ".cpp": "cpp", ".cc": "cpp", ".cxx": "cpp", ".hpp": "cpp",
         ".rs": "rust", ".kt": "kotlin", ".kts": "kotlin", ".java": "java"}


def lang_of(path: str) -> Optional[str]:
    return LANGS.get(os.path.splitext(path)[1].lower())


def adapter_command(lang: str) -> Optional[list]:
    if lang in ("c", "cpp"):
        gdb = shutil.which("gdb")
        return [gdb, "--interpreter=dap"] if gdb and _gdb_has_dap(gdb) else None
    if lang == "rust":
        from .toolchains import tool_env
        rg = shutil.which("rust-gdb", path=tool_env()["PATH"])
        gdb = shutil.which("gdb")
        return [rg, "--interpreter=dap"] if rg and gdb and _gdb_has_dap(gdb) else None
    return None


def _gdb_has_dap(gdb: str) -> bool:
    try:
        out = subprocess.run([gdb, "--version"], capture_output=True, text=True, timeout=10).stdout
        m = re.search(r"(\d+)\.(\d+)", out)
        return bool(m) and int(m.group(1)) >= 14
    except (OSError, subprocess.TimeoutExpired):
        return False


def _run(cmd: list, cwd: str, timeout: int) -> tuple:
    from .toolchains import tool_env
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout, env=tool_env(), stdin=subprocess.DEVNULL)
        return r.returncode, (r.stdout + r.stderr)[-6000:]
    except subprocess.TimeoutExpired:
        return 124, f"The build took longer than {timeout} seconds."
    except OSError as e:
        return 127, str(e)


def prepare(path: str, root: str) -> dict:
    """Build what a debug session needs and say how to launch it."""
    lang = lang_of(path)
    if lang is None:
        return {"ok": False, "message": "Forge can debug Python, C, C++, Rust, Kotlin and Java files."}
    if lang == "python":
        return {"ok": True, "lang": lang, "program": path}
    if lang in ("c", "cpp"):
        if path.endswith((".h", ".hpp")):
            return {"ok": False, "message": "Open the .c or .cpp file that has main, not a header."}
        comp = shutil.which("gcc" if lang == "c" else "g++")
        if not comp:
            return {"ok": False, "message": "The compiler is not installed (sudo apt install build-essential)."}
        program = os.path.splitext(path)[0]
        cmd = [comp, "-g", "-O0", "-Wall", os.path.basename(path), "-o", os.path.basename(program)] + (["-lm"] if lang == "c" else [])
        code, out = _run(cmd, os.path.dirname(path), 300)
        return {"ok": code == 0, "lang": lang, "program": program, "cwd": os.path.dirname(path),
                "build": " ".join(cmd[:1] + [os.path.basename(c) if os.path.isabs(c) else c for c in cmd[1:]]), "output": out,
                "message": "" if code == 0 else "The program did not compile. The build output is in the Debug console."}
    if lang == "rust":
        crate = os.path.dirname(path)
        while crate.startswith(root) and not os.path.isfile(os.path.join(crate, "Cargo.toml")):
            parent = os.path.dirname(crate)
            if parent == crate:
                break
            crate = parent
        if not os.path.isfile(os.path.join(crate, "Cargo.toml")):
            return {"ok": False, "message": "This Rust file is not inside a Cargo project (no Cargo.toml above it)."}
        code, out = _run(["forge-cargo", "build", "--message-format=json-render-diagnostics"], crate, 900)
        program = None
        text = []
        for line in out.splitlines():
            if line.startswith("{"):
                try:
                    m = json.loads(line)
                except ValueError:
                    continue
                if m.get("reason") == "compiler-artifact" and m.get("executable"):
                    program = m["executable"]
                elif m.get("reason") == "compiler-message":
                    text.append((m.get("message") or {}).get("rendered") or "")
            else:
                text.append(line)
        ok = code == 0 and bool(program)
        return {"ok": ok, "lang": lang, "program": program, "cwd": crate, "build": "forge-cargo build", "output": "\n".join(text)[-6000:],
                "message": "" if ok else ("The crate did not build. The build output is in the Debug console." if code else "The crate has no program to run (it is a library).")}
    # Kotlin and Java: the main class, from the package line and the file name, then a build with the Kotlin
    # compiler (and javac for Java files) into Forge's own build folder, because the Kotlin server compiles nothing
    # in Forge's light project model.
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            src = fh.read(400000)
    except OSError as e:
        return {"ok": False, "message": str(e)}
    pkg = re.search(r"^\s*package\s+([\w.]+)", src, flags=re.M)
    base = os.path.splitext(os.path.basename(path))[0]
    if lang == "kotlin":
        if not re.search(r"^\s*fun\s+main\s*\(", src, flags=re.M):
            return {"ok": False, "message": "This Kotlin file has no top-level fun main()."}
        cls = base[:1].upper() + base[1:] + "Kt"
    else:
        if "static void main" not in src:
            return {"ok": False, "message": "This Java file has no public static void main."}
        cls = base
    main = (pkg.group(1) + "." if pkg else "") + cls
    return _build_jvm(path, root, lang, main)


def _source_root(path: str, root: str) -> str:
    """The source folder the file belongs to: src/<set>/kotlin or java in a module, or else the file's folder."""
    parts = path.split(os.sep)
    for i in range(len(parts) - 3, 0, -1):
        if parts[i] == "src" and parts[i + 2] in ("kotlin", "java"):
            return os.sep.join(parts[:i + 3])
    return os.path.dirname(path)


def _build_jvm(path: str, root: str, lang: str, main: str) -> dict:
    import hashlib
    from .kotlin_workspace import build_model
    from .toolchains import paths
    tp = paths()
    java_home = tp["java_home"]
    kotlinc = os.path.join(tp["kotlinc"] or "", "bin", "kotlinc")
    if not java_home or not os.path.isfile(os.path.join(java_home, "bin", "java")):
        return {"ok": False, "message": "Java 21 is not installed."}
    src_root = _source_root(path, root)
    sources = []
    for dirpath, dirnames, filenames in os.walk(src_root):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", ".git", "out", ".forge")]
        sources += [os.path.join(dirpath, f) for f in filenames if f.endswith((".kt", ".java"))]
        if len(sources) > 2000:
            return {"ok": False, "message": "This source folder is too large for Forge's quick build; build it with forge-gradle."}
    model, _summary = build_model(root)
    jars = []
    for lib in model.get("libraries", []):
        if lib["name"] == "android-sdk" or lib["name"].startswith("R:"):
            continue
        jars += [r["path"] for r in lib.get("roots", []) if r.get("type") == "CLASSES" and r["path"].endswith(".jar")]
    out = os.path.join(os.path.expanduser("~"), ".forge-ide", "debug-build", hashlib.sha1(src_root.encode()).hexdigest()[:12])
    os.makedirs(out, exist_ok=True)
    cp = os.pathsep.join(jars)
    kt = [s for s in sources if s.endswith(".kt")]
    jv = [s for s in sources if s.endswith(".java")]
    from .toolchains import scope_argv, stop_scope
    log = []
    if kt:
        if not os.path.isfile(kotlinc):
            return {"ok": False, "message": "The Kotlin compiler is not installed (install-extras.sh android)."}
        prefix, unit = scope_argv("debug-build", "1400M")
        code, text = _run(prefix + [kotlinc, "-J-Xmx700m", "-J-XX:+UseSerialGC", "-nowarn", "-d", out] + (["-cp", cp] if cp else []) + kt + jv, src_root, 600)
        stop_scope(unit)
        log.append(text)
        if code:
            return {"ok": False, "lang": lang, "build": "kotlinc", "output": "\n".join(log), "message": "The Kotlin code did not compile. The output is in the Debug console."}
    if jv:
        prefix, unit = scope_argv("debug-build", "1000M")
        code, text = _run(prefix + [os.path.join(java_home, "bin", "javac"), "-J-Xmx400m", "-g", "-d", out, "-cp", os.pathsep.join([out] + jars)] + jv, src_root, 600)
        stop_scope(unit)
        log.append(text)
        if code:
            return {"ok": False, "lang": lang, "build": "javac", "output": "\n".join(log), "message": "The Java code did not compile. The output is in the Debug console."}
    return {"ok": True, "lang": lang, "main_class": main, "file": path, "class_paths": [out] + jars,
            "java_exec": os.path.join(java_home, "bin", "java"), "build": "kotlinc" if kt else "javac",
            "output": "\n".join(x for x in log if x.strip())}


def android_prepare(app_id: str, serial: str, port: int) -> dict:
    """Start an app on a device so that it waits for the debugger, and forward its Java debug connection (JDWP) to a
    local port the Kotlin debugger can attach to. The app must be a debug build."""
    import socket
    import time
    from .devices import adb
    if not re.match(r"^[\w.]+$", app_id or ""):
        return {"ok": False, "message": "That is not a valid application id."}
    rc, out, _ = adb(["shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", app_id], serial=serial, port=port, timeout=15)
    comp = (out or "").strip().splitlines()[-1:] if rc == 0 else []
    if not comp or "/" not in comp[0]:
        rc, out, _ = adb(["shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LEANBACK_LAUNCHER", app_id], serial=serial, port=port, timeout=15)
        comp = (out or "").strip().splitlines()[-1:] if rc == 0 else []
    if not comp or "/" not in comp[0]:
        return {"ok": False, "message": f"{app_id} is not installed on the device, or it has no launcher activity. Build and install it first."}
    adb(["shell", "am", "force-stop", app_id], serial=serial, port=port, timeout=15)
    rc, out, err = adb(["shell", "am", "start", "-D", "-n", comp[0]], serial=serial, port=port, timeout=20)
    if rc != 0 or "Error" in (out or ""):
        return {"ok": False, "message": "The app did not start: " + ((out or "") + (err or "")).strip()[-300:]}
    pid = ""
    for _ in range(40):
        rc, out, _ = adb(["shell", "pidof", "-s", app_id], serial=serial, port=port, timeout=8)
        pid = (out or "").strip()
        if pid.isdigit():
            break
        time.sleep(0.25)
    if not pid.isdigit():
        return {"ok": False, "message": "The app did not start a process."}
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        local = s.getsockname()[1]
    rc, out, err = adb(["forward", f"tcp:{local}", f"jdwp:{pid}"], serial=serial, port=port, timeout=15)
    if rc != 0:
        return {"ok": False, "message": "adb could not forward the debug connection: " + ((out or "") + (err or "")).strip()[-300:]}
    return {"ok": True, "local_port": local, "pid": int(pid), "component": comp[0],
            "message": f"{app_id} is waiting for the debugger (process {pid})."}


def android_release(local_port: int, serial: str, port: int) -> None:
    from .devices import adb
    adb(["forward", "--remove", f"tcp:{int(local_port)}"], serial=serial, port=port, timeout=10)
