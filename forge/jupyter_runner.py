"""A bridge between Forge notebooks and a real Jupyter kernel (Kotlin, Bash, or any other installed kernel).

Forge talks to it with the same JSON lines as to its own Python kernel: it reads {"op": "execute" | "interrupt" |
"restart", ...} on stdin and writes ready, stream, result, display, error and done messages on stdout. Inside, it
starts the kernel with jupyter_client and reads the kernel's messages continuously on a thread, so the kernel never
waits on Forge. It runs inside the notebook's memory cap together with the kernel, and it stops the kernel and exits
when the Forge server that started it is gone.

Usage: python -I jupyter_runner.py <kernel name>   (run with the Python that has the kernels and jupyter_client)"""
import json
import os
import queue
import sys
import threading
import time

from jupyter_client.manager import KernelManager

_lock = threading.Lock()
MAX_CELL_OUTPUT = 2_000_000


def send(obj):
    line = json.dumps(obj) + "\n"
    with _lock:
        sys.stdout.write(line)
        sys.stdout.flush()


class Bridge:
    def __init__(self, name: str):
        self.name = name
        self.km = KernelManager(kernel_name=name)
        self.kc = None
        self.ids = {}            # Jupyter msg_id of an execute request -> Forge cell id
        self.sizes = {}          # Forge cell id -> bytes of output sent
        self.pending = queue.Queue()

    def start(self):
        env = dict(os.environ)
        self.km.start_kernel(env=env)
        self.kc = self.km.client()
        self.kc.start_channels()
        self.kc.wait_for_ready(timeout=180)
        spec = self.km.kernel_spec
        if spec and spec.language == "bash":
            # The Bash kernel reads your ~/.bashrc; a DEBUG trap there (one that sets the window title, for example)
            # would print escape codes into every cell and break the kernel's exit status check, so this session
            # clears it. Your files are not changed.
            mid = self.kc.execute("trap - DEBUG", silent=True, store_history=False)
            end = time.time() + 30
            while time.time() < end:
                try:
                    if self.kc.get_shell_msg(timeout=2)["parent_header"].get("msg_id") == mid:
                        break
                except queue.Empty:
                    continue
        info = {}
        try:
            reply = self.kc.kernel_info(reply=True, timeout=60)
            info = reply["content"].get("language_info", {})
        except Exception:  # noqa: BLE001
            pass
        send({"type": "ready", "kernel": self.name, "display_name": spec.display_name if spec else self.name,
              "language": (info.get("name") or (spec.language if spec else "")), "version": info.get("version", "")})
        threading.Thread(target=self.iopub, name="iopub", daemon=True).start()
        threading.Thread(target=self.shell, name="shell", daemon=True).start()

    def iopub(self):
        while True:
            try:
                msg = self.kc.get_iopub_msg(timeout=1)
            except queue.Empty:
                continue
            except Exception:  # noqa: BLE001
                return
            cid = self.ids.get(msg.get("parent_header", {}).get("msg_id"))
            if cid is None:
                continue
            t, c = msg["msg_type"], msg["content"]
            if t == "stream":
                self.out(cid, {"type": "stream", "id": cid, "name": c.get("name", "stdout"), "text": c.get("text", "")})
            elif t == "execute_result":
                self.out(cid, {"type": "result", "id": cid, "count": c.get("execution_count"), "data": c.get("data", {})})
            elif t in ("display_data", "update_display_data"):
                self.out(cid, {"type": "display", "id": cid, "data": c.get("data", {})})
            elif t == "error":
                send({"type": "error", "id": cid, "ename": c.get("ename", ""), "evalue": c.get("evalue", ""), "traceback": c.get("traceback", [])})

    def out(self, cid, obj):
        n = len(json.dumps(obj))
        self.sizes[cid] = self.sizes.get(cid, 0) + n
        if self.sizes[cid] > MAX_CELL_OUTPUT:
            if self.sizes[cid] - n <= MAX_CELL_OUTPUT:
                send({"type": "stream", "id": cid, "name": "stderr", "text": "\n[Output stopped: this cell printed more than 2 MB.]\n"})
            return
        send(obj)

    def shell(self):
        while True:
            try:
                msg = self.kc.get_shell_msg(timeout=1)
            except queue.Empty:
                continue
            except Exception:  # noqa: BLE001
                return
            if msg["msg_type"] != "execute_reply":
                continue
            mid = msg.get("parent_header", {}).get("msg_id")
            cid = self.ids.get(mid)
            if cid is None:
                continue
            # Let the kernel's last output messages arrive before the cell is marked done.
            time.sleep(0.15)
            send({"type": "done", "id": cid, "count": msg["content"].get("execution_count")})
            self.ids.pop(mid, None)
            self.sizes.pop(cid, None)

    def execute(self, cid, code):
        mid = self.kc.execute(code, store_history=True, allow_stdin=False)
        self.ids[mid] = cid

    def interrupt(self):
        try:
            self.km.interrupt_kernel()
        except Exception as e:  # noqa: BLE001
            send({"type": "stream", "id": None, "name": "stderr", "text": f"Interrupt failed: {e}\n"})

    def restart(self):
        self.km.restart_kernel(now=True)
        self.kc.wait_for_ready(timeout=180)
        self.ids.clear()
        send({"type": "restarted"})

    def shutdown(self):
        try:
            self.kc.stop_channels()
            self.km.shutdown_kernel(now=True)
        except Exception:  # noqa: BLE001
            pass


def watch_parent(bridge):
    parent = int(os.environ.get("FORGE_PARENT_PID", "0") or 0)
    while parent:
        time.sleep(5)
        try:
            os.kill(parent, 0)
        except OSError:
            bridge.shutdown()
            os._exit(0)


def main():
    bridge = Bridge(sys.argv[1])
    try:
        bridge.start()
    except Exception as e:  # noqa: BLE001
        send({"type": "exit", "message": f"The {sys.argv[1]} kernel did not start: {e}"})
        bridge.shutdown()
        return
    threading.Thread(target=watch_parent, args=(bridge,), daemon=True).start()
    for line in sys.stdin:
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        op = msg.get("op")
        if op == "execute":
            bridge.execute(msg.get("id"), msg.get("code", ""))
        elif op == "interrupt":
            bridge.interrupt()
        elif op == "restart":
            bridge.restart()
    bridge.shutdown()


if __name__ == "__main__":
    main()
