"""Kernels for Forge notebooks: one Python process per open notebook, kept between page reloads and stopped when
nobody has used it for a while.

Safety: each kernel runs in its own systemd scope with a memory cap (MemoryMax), so code in a cell that eats memory
is stopped inside that cap instead of squeezing the VM and the Googlebook. Kernel output is read continuously by a
thread and handed to each browser through a bounded queue, so neither side can block the other."""
from __future__ import annotations

import json
import os
import queue
import shutil
import signal
import subprocess
import sys
import threading
import time
from typing import Callable, Dict, Optional, Set

RUNNER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "kernel_runner.py")
JUPYTER_RUNNER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "jupyter_runner.py")
JUPYTER_PY = os.path.join(os.path.expanduser("~"), ".local", "lib", "forge-jupyter", "venv", "bin", "python")
PYTHON_NAMES = ("", "python3", "python", "forge-python")
# The Kotlin kernel is a Java program; its heap and code caches stay well inside the kernel's memory cap.
KOTLIN_JAVA_OPTS = "-Xmx640m -XX:+UseSerialGC -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m"


def available_kernels() -> list:
    """Forge's own Python kernel plus every Jupyter kernel installed for Forge (Kotlin, Bash, ...)."""
    kernels = [{"name": "python3", "display_name": "Python 3 (Forge)", "language": "python"}]
    if os.path.isfile(JUPYTER_PY):
        try:
            r = subprocess.run([JUPYTER_PY, "-I", "-c", "import json; from jupyter_client.kernelspec import KernelSpecManager as K; "
                                "print(json.dumps({n: [s['spec']['display_name'], s['spec']['language']] for n, s in K().get_all_specs().items()}))"],
                               capture_output=True, text=True, timeout=30)
            for name, (display, lang) in sorted(json.loads(r.stdout or "{}").items()):
                if name not in PYTHON_NAMES:
                    kernels.append({"name": name, "display_name": display, "language": lang})
        except (OSError, subprocess.TimeoutExpired, ValueError):
            pass
    return kernels


def kernel_name_of(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return str(((json.load(fh).get("metadata") or {}).get("kernelspec") or {}).get("name") or "")
    except (OSError, ValueError, AttributeError):
        return ""
KERNEL_MEMORY_MAX = "1536M"
IDLE_STOP_S = 30 * 60          # a kernel nobody has used for 30 minutes is stopped
DETACHED_STOP_S = 10 * 60      # a kernel with no open notebook window is stopped after 10 minutes
KERNEL_MIN_VM_MB = 1800       # the kernel may grow to its cap, so the cap must fit in free memory
KERNEL_MIN_HOST_MB = 3800     # the cap plus the 2.3 GB the Googlebook keeps free


class Kernel:
    def __init__(self, path: str, log_dir: str):
        self.path = path
        self.listeners: Set["Listener"] = set()
        self.last_used = time.time()
        self.detached_since: Optional[float] = time.time()
        self.proc: Optional[subprocess.Popen] = None
        self.info: dict = {}
        self.lock = threading.Lock()
        self.log_dir = log_dir
        self.busy = False

    def start(self) -> None:
        from .toolchains import tool_env
        env = dict(tool_env(), PYTHONUNBUFFERED="1", MPLBACKEND="Agg", FORGE_PARENT_PID=str(os.getpid()),
                   FORGE_NOTEBOOK_DIR=os.path.dirname(self.path), KOTLIN_JUPYTER_JAVA_OPTS=KOTLIN_JAVA_OPTS)
        name = kernel_name_of(self.path)
        if name in PYTHON_NAMES or not os.path.isfile(JUPYTER_PY):
            cmd = [sys.executable, "-u", RUNNER]
            self.jupyter = False
        else:
            cmd = [JUPYTER_PY, "-I", "-u", JUPYTER_RUNNER, name]
            self.jupyter = True
        self.unit = None
        if shutil.which("systemd-run"):
            from .toolchains import scope_argv
            prefix, self.unit = scope_argv("kernel", KERNEL_MEMORY_MAX)
            cmd = prefix + cmd
        os.makedirs(self.log_dir, exist_ok=True)
        err = open(os.path.join(self.log_dir, "kernel.log"), "ab")
        self.proc = subprocess.Popen(["nice", "-n", "3"] + cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=err, cwd=os.path.dirname(self.path), env=env, start_new_session=True)
        err.close()
        threading.Thread(target=self._pump, name="kernel-out", daemon=True).start()

    def alive(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def _pump(self) -> None:
        proc = self.proc
        assert proc is not None and proc.stdout is not None
        for raw in proc.stdout:
            try:
                msg = json.loads(raw.decode("utf-8", "replace"))
            except ValueError:
                continue
            if msg.get("type") == "ready":
                self.info = msg
            if msg.get("type") == "done":
                self.busy = False
            self.broadcast(msg)
        self.busy = False
        code = proc.poll()
        self.broadcast({"type": "exit", "code": code,
                        "message": "The kernel stopped" + (" (it ran out of its 1.5 GB memory limit)" if code in (-9, 137) else "") + "."})

    def broadcast(self, msg: dict) -> None:
        for l in list(self.listeners):
            l.put(msg)

    def send(self, obj: dict) -> None:
        if not self.alive():
            raise RuntimeError("The kernel is not running.")
        self.last_used = time.time()
        with self.lock:
            self.proc.stdin.write((json.dumps(obj) + "\n").encode())
            self.proc.stdin.flush()

    def execute(self, msg_id: str, code: str) -> None:
        self.busy = True
        self.send({"op": "execute", "id": msg_id, "code": code})

    def interrupt(self) -> None:
        if not self.alive():
            return
        if getattr(self, "jupyter", False):
            # Kotlin, Bash and the other Jupyter kernels run in their own session under the bridge; a signal to the
            # bridge would end it. The bridge asks the kernel to interrupt the cell the Jupyter way instead.
            try:
                self.send({"op": "interrupt"})
            except (OSError, RuntimeError):
                pass
            return
        # Forge's own Python kernel: SIGINT raises KeyboardInterrupt in the cell. The scope wrapper is replaced by the
        # kernel itself (systemd-run --scope execs it), so the process group holds just the kernel and what it started.
        try:
            os.killpg(os.getpgid(self.proc.pid), signal.SIGINT)
        except OSError:
            pass

    def stop(self) -> None:
        p, self.proc = self.proc, None
        if p is None:
            return
        from .toolchains import stop_scope
        threading.Thread(target=stop_scope, args=(getattr(self, "unit", None),), name="kernel-scope-stop", daemon=True).start()
        try:
            pgid = os.getpgid(p.pid)
        except OSError:
            pgid = None
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                if pgid is not None:
                    os.killpg(pgid, sig)
                else:
                    p.send_signal(sig)
            except OSError:
                break
            try:
                p.wait(timeout=4)
                break
            except subprocess.TimeoutExpired:
                continue


class Listener:
    """One browser window attached to a kernel: a bounded queue drained by the window's own sender thread."""

    def __init__(self, send: Callable[[str], None]):
        self.q: "queue.Queue[Optional[dict]]" = queue.Queue(maxsize=4000)
        self.dropped = 0
        self._send = send
        threading.Thread(target=self._run, name="kernel-ws", daemon=True).start()

    def put(self, msg: dict) -> None:
        try:
            self.q.put_nowait(msg)
        except queue.Full:
            self.dropped += 1

    def close(self) -> None:
        try:
            self.q.put_nowait(None)
        except queue.Full:
            pass

    def _run(self) -> None:
        while True:
            msg = self.q.get()
            if msg is None:
                return
            try:
                if self.dropped:
                    self._send(json.dumps({"type": "stream", "id": msg.get("id"), "name": "stderr",
                                           "text": f"\n[{self.dropped} output messages were dropped: too much output]\n"}))
                    self.dropped = 0
                self._send(json.dumps(msg))
            except OSError:
                return


class KernelManager:
    def __init__(self, gate: Callable[[], Optional[str]], log_dir: str):
        self.gate = gate
        self.log_dir = log_dir
        self.kernels: Dict[str, Kernel] = {}
        self.lock = threading.Lock()
        threading.Thread(target=self._reaper, name="kernel-reaper", daemon=True).start()

    def get(self, path: str, start: bool = True) -> Kernel:
        with self.lock:
            k = self.kernels.get(path)
            if k and k.alive():
                return k
            if not start:
                raise RuntimeError("No kernel is running for this notebook.")
            why = self.gate()
            if why:
                raise RuntimeError(why)
            k = Kernel(path, self.log_dir)
            k.start()
            self.kernels[path] = k
            return k

    def restart(self, path: str) -> Kernel:
        with self.lock:
            old = self.kernels.pop(path, None)
        listeners = set(old.listeners) if old else set()
        if old:
            old.stop()
        k = self.get(path)
        k.listeners |= listeners
        return k

    def stop(self, path: str) -> None:
        with self.lock:
            k = self.kernels.pop(path, None)
        if k:
            k.stop()

    def status(self) -> list:
        return [{"path": p, "alive": k.alive(), "busy": k.busy, "windows": len(k.listeners), "idle_s": int(time.time() - k.last_used)}
                for p, k in self.kernels.items()]

    def _reaper(self) -> None:
        while True:
            time.sleep(30)
            now = time.time()
            for path, k in list(self.kernels.items()):
                if not k.alive():
                    self.kernels.pop(path, None)
                    continue
                if k.listeners:
                    k.detached_since = None
                elif k.detached_since is None:
                    k.detached_since = now
                idle = now - k.last_used > IDLE_STOP_S and not k.busy
                detached = k.detached_since is not None and now - k.detached_since > DETACHED_STOP_S
                if idle or detached:
                    self.stop(path)
