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
        # Empty texts and 0, never null: the app reads these as text and numbers.
        info["modules"].append({"module": os.path.relpath(mod, root), "application_id": app_id or "",
                                "apk": os.path.relpath(apks[-1], root) if apks else "",
                                "apk_time": os.path.getmtime(apks[-1]) if apks else 0,
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


PID_POLL_S = 2   # how often the device looks up the app's process while an app's log is followed


class Logcat:
    """One live logcat for one window. `send` delivers a JSON text batch to the window.

    With an app id the log follows that app's process, as Android Studio does: a small loop on the device prints the
    app's process id every PID_POLL_S seconds over one adb connection, and whenever it changes (the app started,
    crashed and started again, or was restarted by an install) the logcat is started again for the new process. While
    the app does not run, nothing is streamed and the window says so. Every stream is drained as it arrives, and the
    whole session stops at its time or size limit, when the window closes, or when the device goes away."""

    def __init__(self, send: Callable[[str], None], app_id: str = "", level: str = "V", serial: str = "", port: int = 5037,
                 name: str = ""):
        self.send = send
        self.serial, self.port = serial, int(port)
        self.name = name or serial
        self.q: "queue.Queue[Optional[str]]" = queue.Queue(maxsize=5000)
        self.dropped = 0
        self.proc: Optional[subprocess.Popen] = None     # the logcat now streaming
        self.watch: Optional[subprocess.Popen] = None    # the device's process id loop (app logs only)
        self.app_id = app_id if re.match(r"^[\w.]*$", app_id or "") else ""
        self.level = level if level in ("V", "D", "I", "W", "E") else "V"
        self.lock = threading.Lock()
        self.gen = 0
        self.pid = ""
        self.started = time.time()
        self.total = 0
        self.stopped = threading.Event()

    def _adb(self) -> List[str]:
        return [ADB, "-P", str(self.port)] + (["-s", self.serial] if self.serial else [])

    def run(self) -> None:
        self._safe_send({"type": "device", "name": self.name, "serial": self.serial, "port": self.port})
        threading.Thread(target=self._forward, name="logcat-send", daemon=True).start()
        if self.app_id:
            # A bounded loop on the device: it ends by itself after the session's time limit at the latest.
            loop = (f"i=0; while [ $i -lt {LOGCAT_MAX_S // PID_POLL_S + 5} ]; do echo pid=$(pidof {self.app_id}); "
                    f"i=$((i+1)); sleep {PID_POLL_S}; done")
            self.watch = subprocess.Popen(self._adb() + ["shell", loop], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                          stdin=subprocess.DEVNULL, start_new_session=True)
            threading.Thread(target=self._follow, name="logcat-pid", daemon=True).start()
        else:
            self._start_stream("")

    def _start_stream(self, pid: str) -> None:
        """Starts (or starts again) the logcat, for one process when pid is given; the earlier stream ends quietly."""
        with self.lock:
            if self.stopped.is_set():
                return
            self.gen += 1
            gen, old = self.gen, self.proc
            args = self._adb() + ["logcat", "-v", "threadtime", "-T", "300", "*:" + self.level] + (["--pid=" + pid] if pid else [])
            self.proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                         start_new_session=True)
            proc = self.proc
        if old is not None:
            _end(old)
        threading.Thread(target=self._read, args=(proc, gen), name="logcat-read", daemon=True).start()

    def _follow(self) -> None:
        """Reads the device's process id loop as it writes and follows the app's process."""
        w = self.watch
        assert w is not None and w.stdout is not None
        first, followed = True, False
        try:
            for raw in w.stdout:
                if self.stopped.is_set():
                    break
                line = raw.decode("utf-8", "replace").strip()
                if not line.startswith("pid="):
                    continue
                pid = (line[4:].split() or [""])[0]
                if not pid.isdigit():
                    pid = ""
                if first:
                    first = False
                    if not pid:
                        self._note(f"{self.app_id} is not running on {self.name}. Its log appears here as soon as it starts.")
                if pid and pid != self.pid:
                    self._note((f"{self.app_id} started again, as process {pid}; following it." if followed else
                                f"Following process {pid} of {self.app_id}."))
                    self.pid, followed = pid, True
                    self._start_stream(pid)
                elif not pid and self.pid:
                    self._note(f"The process of {self.app_id} ended. Its log continues here when it starts again.")
                    self.pid = ""
        except (OSError, ValueError):
            pass
        if not self.stopped.is_set():
            self._note(f"Forge lost the connection to {self.name}.")
            self._finish()

    def _note(self, text: str) -> None:
        try:
            self.q.put_nowait("[" + text + "]")
        except queue.Full:
            self.dropped += 1

    def _read(self, proc: subprocess.Popen, gen: int) -> None:
        """Drains one logcat continuously; the session stops at the time or size limit (the closed pipe ends adb's
        stream), and when the stream that is current ends by itself (the device went away)."""
        assert proc.stdout is not None
        try:
            for raw in proc.stdout:
                if gen != self.gen or self.stopped.is_set():
                    break
                self.total += len(raw)
                try:
                    self.q.put_nowait(raw.decode("utf-8", "replace").rstrip("\n"))
                except queue.Full:
                    self.dropped += 1
                if time.time() - self.started > LOGCAT_MAX_S or self.total > LOGCAT_MAX_BYTES:
                    self._note("Forge stopped the log at its time or size limit. Press the start button to continue.")
                    self._finish()
                    break
        except (OSError, ValueError):
            pass
        if gen == self.gen and not self.stopped.is_set():
            self._note(f"The log stream of {self.name} ended (the device was disconnected or stopped).")
            self._finish()

    def _finish(self) -> None:
        """Ends the session and tells the window, once."""
        if self.stopped.is_set():
            return
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
                if self.stopped.is_set():
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
        """Ends the streams (the window closed, or the session finished)."""
        self.stopped.set()
        with self.lock:
            procs = [p for p in (self.proc, self.watch) if p is not None]
        for p in procs:
            _end(p)


def _end(p: subprocess.Popen) -> None:
    """Ends one adb stream: politely, then for good after three seconds."""
    if p.poll() is not None:
        return
    try:
        p.terminate()
        p.wait(timeout=3)
    except subprocess.TimeoutExpired:
        p.kill()
        try:
            p.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass
    except OSError:
        pass
