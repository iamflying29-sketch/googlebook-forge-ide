"""The Android view's project facts (application id, newest APK) and the live logcat. Devices, install, run and
screenshots are in devices.py; virtual devices are in vdevice.py.

The logcat stream is drained continuously by a thread into a bounded queue, and it stops at a total time limit or
size limit or when the window closes, so nothing on the device ever waits on Forge. Debian's adb is used, never a
second adb version."""
from __future__ import annotations

import glob
import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
from typing import Callable, List, Optional

ADB = shutil.which("adb") or "adb"
LOGCAT_MAX_S = 15 * 60
LOGCAT_MAX_BYTES = 20_000_000


def project_info(root: str) -> dict:
    """Android modules of a project: application id and the newest built APK."""
    from .toolchains import is_android_project
    info = {"android": is_android_project(root), "modules": []}
    if not info["android"]:
        return info
    for gradle in sorted(glob.glob(os.path.join(root, "*", "build.gradle*")) + glob.glob(os.path.join(root, "build.gradle*"))):
        try:
            with open(gradle, "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read(300000)
        except OSError:
            continue
        if not re.search(r"(^|\n)\s*android\s*\{", text):
            continue   # a module has an android { } block; the root file only declares plugin versions
        mod = os.path.dirname(gradle)
        app_id = None
        m = re.search(r"applicationId\s*=?\s*[\"']([\w.]+)[\"']", text)
        if m:
            app_id = m.group(1)
        if not app_id:
            for man in glob.glob(os.path.join(mod, "src", "main", "AndroidManifest.xml")):
                try:
                    mm = re.search(r'package="([\w.]+)"', open(man, encoding="utf-8", errors="replace").read(200000))
                    app_id = mm.group(1) if mm else None
                except OSError:
                    pass
        apks = sorted(glob.glob(os.path.join(mod, "build", "outputs", "apk", "**", "*.apk"), recursive=True), key=os.path.getmtime)
        info["modules"].append({"module": os.path.relpath(mod, root), "application_id": app_id,
                                "apk": os.path.relpath(apks[-1], root) if apks else None,
                                "apk_time": os.path.getmtime(apks[-1]) if apks else None,
                                "application": "com.android.application" in text})
    info["gradlew"] = os.path.isfile(os.path.join(root, "gradlew"))
    return info


def kmp_info(root: str) -> List[dict]:
    """Kotlin Multiplatform modules of a project and their targets (from their source sets)."""
    from .kotlin_workspace import is_kmp_module, _kmp_split
    out = []
    for gradle in sorted(glob.glob(os.path.join(root, "*", "build.gradle*")) + glob.glob(os.path.join(root, "build.gradle*"))):
        mod = os.path.dirname(gradle)
        if mod in [os.path.join(root, m["module"]) for m in out] or not is_kmp_module(mod):
            continue
        sets = sorted(os.path.basename(s) for s in glob.glob(os.path.join(mod, "src", "*")) if os.path.isdir(s))
        targets = sorted({_kmp_split(s)[0] for s in sets} - {"common"})
        try:
            text = open(gradle, encoding="utf-8", errors="replace").read(300000)
        except OSError:
            text = ""
        out.append({"module": os.path.relpath(mod, root), "gradle_path": ":" + os.path.relpath(mod, root).replace(os.sep, ":") if mod != root else "",
                    "sets": sets, "targets": targets, "compose_desktop": "compose.desktop" in text or "org.jetbrains.compose" in text,
                    "android_app": "com.android.application" in text or "androidApplication" in text})
    return out


class Logcat:
    """One live logcat for one window. `send` delivers a JSON text batch to the browser."""

    def __init__(self, send: Callable[[str], None], app_id: str = "", level: str = "V", serial: str = "", port: int = 5037):
        self.send = send
        self.serial, self.port = serial, int(port)
        self.q: "queue.Queue[Optional[str]]" = queue.Queue(maxsize=5000)
        self.dropped = 0
        self.proc: Optional[subprocess.Popen] = None
        self.app_id = app_id if re.match(r"^[\w.]*$", app_id or "") else ""
        self.level = level if level in ("V", "D", "I", "W", "E") else "V"

    def run(self) -> None:
        from .devices import adb
        args = [ADB, "-P", str(self.port)] + (["-s", self.serial] if self.serial else []) + ["logcat", "-v", "threadtime", "-T", "300", "*:" + self.level]
        if self.app_id:
            code, out, _ = adb(["shell", "pidof", self.app_id], serial=self.serial or None, port=self.port, timeout=10)
            pid = (out or "").strip().split()[:1]
            if not pid:
                self.send(json.dumps({"type": "info", "text": f"{self.app_id} is not running, so the whole log is shown."}))
            else:
                args.append("--pid=" + pid[0])
        self.proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                     start_new_session=True)
        threading.Thread(target=self._read, name="logcat-read", daemon=True).start()
        threading.Thread(target=self._forward, name="logcat-send", daemon=True).start()

    def _read(self) -> None:
        """Drain logcat continuously; stop at the time or size limit (the closed pipe ends adb's stream)."""
        start, total = time.time(), 0
        assert self.proc is not None and self.proc.stdout is not None
        try:
            for raw in self.proc.stdout:
                total += len(raw)
                try:
                    self.q.put_nowait(raw.decode("utf-8", "replace").rstrip("\n"))
                except queue.Full:
                    self.dropped += 1
                if time.time() - start > LOGCAT_MAX_S or total > LOGCAT_MAX_BYTES:
                    try:
                        self.q.put_nowait("[Forge stopped the log at its time or size limit. Click Start to continue.]")
                    except queue.Full:
                        pass
                    break
        except (OSError, ValueError):
            pass
        self.stop()
        try:
            self.q.put_nowait(None)
        except queue.Full:
            pass

    def _forward(self) -> None:
        while True:
            batch: List[str] = []
            try:
                item = self.q.get(timeout=1.0)
            except queue.Empty:
                if self.proc is None or self.proc.poll() is not None:
                    return
                continue
            if item is None:
                self._safe_send({"type": "end"})
                return
            batch.append(item)
            while len(batch) < 400:
                try:
                    item = self.q.get_nowait()
                except queue.Empty:
                    break
                if item is None:
                    self._safe_send({"type": "lines", "lines": batch})
                    self._safe_send({"type": "end"})
                    return
                batch.append(item)
            if self.dropped:
                batch.append(f"[{self.dropped} lines were skipped: the log was faster than the window]")
                self.dropped = 0
            if not self._safe_send({"type": "lines", "lines": batch}):
                self.stop()
                return
            time.sleep(0.15)

    def _safe_send(self, obj: dict) -> bool:
        try:
            self.send(json.dumps(obj))
            return True
        except OSError:
            return False

    def stop(self) -> None:
        p = self.proc
        if p is None or p.poll() is not None:
            return
        try:
            p.terminate()
            p.wait(timeout=3)
        except subprocess.TimeoutExpired:
            p.kill()
        except OSError:
            pass
