"""A small Python kernel for Forge notebooks. Forge starts one per open notebook and talks to it with JSON lines:
it reads {"op": "execute", "id": ..., "code": ...} on stdin and writes stream, result, display, error and done
messages on stdout. Variables live on between cells, like in Jupyter. matplotlib figures become PNG images.

It exits by itself when the Forge server that started it is gone."""
import ast
import base64
import builtins
import io
import json
import os
import sys
import threading
import time
import traceback

_out = sys.__stdout__
_lock = threading.Lock()
_current = [None]
MAX_CELL_OUTPUT = 2_000_000


def send(obj):
    line = json.dumps(obj) + "\n"
    with _lock:
        _out.write(line)
        _out.flush()


class Stream(io.TextIOBase):
    """Batches printed text and sends it at most 20 times a second, with a cap per cell."""

    def __init__(self, name):
        self.name = name
        self.buf = []
        self.size = 0
        self.sent = 0
        self.last = 0.0
        self.capped = False

    def writable(self):
        return True

    def write(self, s):
        if not s or self.capped:
            return len(s or "")
        self.buf.append(s)
        self.size += len(s)
        if self.size > 8192 or time.time() - self.last > 0.05:
            self.flush()
        return len(s)

    def flush(self):
        if not self.buf:
            return
        text = "".join(self.buf)
        self.buf, self.size, self.last = [], 0, time.time()
        if self.sent + len(text) > MAX_CELL_OUTPUT:
            text = text[: max(0, MAX_CELL_OUTPUT - self.sent)] + "\n[output truncated: more than 2 MB]\n"
            self.capped = True
        self.sent += len(text)
        send({"id": _current[0], "type": "stream", "name": self.name, "text": text})

    def reset(self):
        self.buf, self.size, self.sent, self.capped = [], 0, 0, False


stdout, stderr = Stream("stdout"), Stream("stderr")


def _rich(value):
    data = {"text/plain": repr(value)}
    for attr, mime in (("_repr_html_", "text/html"), ("_repr_markdown_", "text/markdown"), ("_repr_svg_", "image/svg+xml"),
                       ("_repr_png_", "image/png"), ("_repr_latex_", "text/latex")):
        fn = getattr(value, attr, None)
        if callable(fn):
            try:
                out = fn()
                if out is None:
                    continue
                if isinstance(out, bytes):
                    out = base64.b64encode(out).decode()
                data[mime] = out
            except Exception:  # noqa: BLE001
                pass
    return data


def display(*objs):
    for obj in objs:
        send({"id": _current[0], "type": "display", "data": _rich(obj)})


def _no_input(*_a, **_k):
    raise RuntimeError("input() is not available in Forge notebooks. Set the value in a cell instead.")


def _figures():
    plt = sys.modules.get("matplotlib.pyplot")
    if plt is None:
        return
    try:
        for num in plt.get_fignums():
            fig = plt.figure(num)
            buf = io.BytesIO()
            fig.savefig(buf, format="png", bbox_inches="tight", dpi=100)
            send({"id": _current[0], "type": "display", "data": {"image/png": base64.b64encode(buf.getvalue()).decode(),
                                                                  "text/plain": repr(fig)}})
        plt.close("all")
    except Exception as e:  # noqa: BLE001
        stderr.write(f"[could not render the figure: {e}]\n")


namespace = {"__name__": "__main__", "display": display}
count = [0]


def execute(msg_id, code):
    _current[0] = msg_id
    stdout.reset()
    stderr.reset()
    count[0] += 1
    sys.stdout, sys.stderr = stdout, stderr
    builtins.input = _no_input
    status = "ok"
    try:
        tree = ast.parse(code, "<cell>", "exec")
        last = None
        if tree.body and isinstance(tree.body[-1], ast.Expr):
            last = ast.Expression(tree.body.pop().value)
        exec(compile(tree, f"<cell {count[0]}>", "exec"), namespace)
        if last is not None:
            value = eval(compile(last, f"<cell {count[0]}>", "eval"), namespace)
            if value is not None:
                namespace["_"] = value
                stdout.flush()
                send({"id": msg_id, "type": "result", "count": count[0], "data": _rich(value)})
    except KeyboardInterrupt:
        status = "error"
        stdout.flush()
        send({"id": msg_id, "type": "error", "ename": "KeyboardInterrupt", "evalue": "interrupted",
              "traceback": ["KeyboardInterrupt: the cell was interrupted"]})
    except BaseException as e:  # noqa: BLE001  (SystemExit in a cell must not end the kernel)
        status = "error"
        stdout.flush()
        tb = traceback.format_exception(type(e), e, e.__traceback__)
        # Hide this runner's own frames.
        tb = [t for t in tb if "kernel_runner.py" not in t]
        send({"id": msg_id, "type": "error", "ename": type(e).__name__, "evalue": str(e), "traceback": tb})
    finally:
        try:
            _figures()
        except KeyboardInterrupt:
            pass
        stdout.flush()
        stderr.flush()
        sys.stdout, sys.stderr = sys.__stdout__, sys.__stderr__
        send({"id": msg_id, "type": "done", "count": count[0], "status": status})


def _watch_parent(parent):
    while True:
        time.sleep(3)
        if os.getppid() != parent:
            os._exit(0)


def main():
    parent = int(os.environ.get("FORGE_PARENT_PID", "0") or 0) or os.getppid()
    threading.Thread(target=_watch_parent, args=(os.getppid(),), daemon=True).start()
    if parent != os.getppid():
        def _watch_forge():
            while True:
                time.sleep(3)
                try:
                    os.kill(parent, 0)
                except OSError:
                    os._exit(0)
        threading.Thread(target=_watch_forge, daemon=True).start()
    if os.environ.get("FORGE_NOTEBOOK_DIR"):
        try:
            os.chdir(os.environ["FORGE_NOTEBOOK_DIR"])
            sys.path.insert(0, os.environ["FORGE_NOTEBOOK_DIR"])
        except OSError:
            pass
    send({"type": "ready", "python": sys.version.split()[0], "pid": os.getpid()})
    while True:
        try:
            line = sys.stdin.readline()
        except KeyboardInterrupt:
            continue
        if not line:
            break
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        if msg.get("op") == "execute":
            try:
                execute(msg.get("id"), str(msg.get("code", "")))
            except KeyboardInterrupt:
                send({"id": msg.get("id"), "type": "done", "count": count[0], "status": "error"})
        elif msg.get("op") == "complete":
            prefix = str(msg.get("prefix", ""))
            names = sorted(n for n in list(namespace) + dir(builtins) if n.startswith(prefix))[:50]
            send({"id": msg.get("id"), "type": "completions", "items": names})


if __name__ == "__main__":
    main()
