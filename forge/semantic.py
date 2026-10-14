"""Meaning-based codebase search (like Cursor's codebase index): code chunks are turned into embedding vectors by a
small embedding model, and a question finds the chunks closest in meaning, blended with keyword (BM25) ranking.

Memory and CPU safety, because the VM's memory comes from the Googlebook:
- The embedding model runs in its own small llama.cpp server (about 250 MB), never in Ollama, so it never pushes
  the chat model out of memory or forces a reload.
- It starts only on demand, only when the memory gate allows it, at low CPU priority, and stops after two idle
  minutes. A watchdog stops it if it stops answering.
- Indexing pauses while a chat is running and waits whenever memory is short.
"""
from __future__ import annotations

import array
import hashlib
import json
import math
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request
from typing import Callable, Dict, List, Optional, Tuple

from .codesearch import CHUNK_LINES, CodeIndex

DATA = os.path.expanduser("~/.forge-ide")
SERVER_BIN = os.path.join(os.path.expanduser("~/.local/lib/forge-embed"), "llama-server")
MODEL = os.path.join(DATA, "models", "embed", "nomic-embed-text-v1.5.Q8_0.gguf")
PORT = 8091
DIM = 256                 # nomic-embed-text v1.5 supports shorter (Matryoshka) vectors; 256 keeps search fast
IDLE_STOP_S = 120
BATCH = 16
MAX_CHUNKS = 4000
CHUNK_CHARS = 1800        # embedded text per chunk (about 450 tokens); the whole chunk is still returned by search
SKIP_DIRS = {"vendor", "third_party", "third-party", "external", "deps", "bower_components", "site-packages", ".forge",
             "out", "coverage", "generated", "gen"}
SKIP_SUFFIXES = (".min.js", ".min.css", ".map", ".lock", "-lock.json", ".lock.json", ".snap", ".svg", ".csv")
# Stops the embedding server when the Forge server process is gone, even after a hard kill (no orphans).
WATCHDOG = r"""
parent=$1; shift
"$@" & child=$!
trap 'kill $child 2>/dev/null; wait $child; exit 0' TERM INT
while kill -0 "$parent" 2>/dev/null && kill -0 "$child" 2>/dev/null; do sleep 3 & wait $!; done
kill $child 2>/dev/null; wait $child
"""
EMBED_MIN_VM_MB = 700
EMBED_MIN_HOST_MB = 2600      # about 300 MB for the server plus the 2.3 GB the Googlebook keeps free


def available() -> bool:
    return os.access(SERVER_BIN, os.X_OK) and os.path.isfile(MODEL)


def _post(url: str, body: dict, timeout: float) -> dict:
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read(64 * 1024 * 1024).decode())


def _shorten(vec: List[float]) -> List[float]:
    """Layer norm, keep the first DIM values, then unit length (nomic's documented Matryoshka recipe)."""
    n = len(vec)
    mean = sum(vec) / n
    var = sum((x - mean) ** 2 for x in vec) / n
    sd = math.sqrt(var + 1e-5)
    v = [(x - mean) / sd for x in vec[:DIM]]
    norm = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / norm for x in v]


class EmbedServer:
    """The embedding model's own small server, started on demand and stopped when idle."""

    def __init__(self, gate: Callable[[], Optional[str]], log_dir: str):
        self.gate = gate
        self.log_path = os.path.join(log_dir, "embed-server.log")
        self.proc: Optional[subprocess.Popen] = None
        self.lock = threading.Lock()
        self.last_used = 0.0
        self.error: Optional[str] = None
        threading.Thread(target=self._idle_watch, name="embed-idle", daemon=True).start()

    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def _healthy(self) -> bool:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=3) as r:
                return r.status == 200
        except (urllib.error.URLError, OSError, ValueError):
            return False

    def ensure(self) -> None:
        with self.lock:
            self.last_used = time.time()
            if self.running() and self._healthy():
                return
            if self.running():
                self._stop_locked()
            if not available():
                raise RuntimeError("The embedding model or its server is not installed.")
            why = self.gate()
            if why:
                raise RuntimeError(why)
            os.makedirs(os.path.dirname(self.log_path), exist_ok=True)
            log = open(self.log_path, "ab")
            threads = str(max(2, min(6, (os.cpu_count() or 4) - 2)))
            cmd = ["bash", "-c", WATCHDOG, "forge-embed", str(os.getpid()),
                   "nice", "-n", "10", SERVER_BIN, "-m", MODEL, "--embedding", "--pooling", "mean", "-c", "2048",
                   "-b", "2048", "-ub", "2048", "-np", "1", "-t", threads, "--host", "127.0.0.1", "--port", str(PORT),
                   "--no-webui", "--log-verbosity", "1"]
            # Output goes to a file, so the server can never block on a full pipe.
            self.proc = subprocess.Popen(cmd, stdout=log, stderr=log, stdin=subprocess.DEVNULL, start_new_session=True)
            log.close()
            deadline = time.time() + 40
            while time.time() < deadline:
                if self.proc.poll() is not None:
                    self.error = "The embedding server exited at start (see ~/.forge-ide/logs/embed-server.log)."
                    self.proc = None
                    raise RuntimeError(self.error)
                if self._healthy():
                    self.error = None
                    return
                time.sleep(0.4)
            self._stop_locked()
            raise RuntimeError("The embedding server did not start in time.")

    def embed(self, texts: List[str]) -> List[List[float]]:
        self.ensure()
        self.last_used = time.time()
        try:
            r = _post(f"http://127.0.0.1:{PORT}/v1/embeddings", {"input": texts, "model": "embed"}, timeout=180)
        except (urllib.error.URLError, OSError, ValueError) as e:
            # A server that stops answering is stopped, and the next call starts a fresh one.
            with self.lock:
                self._stop_locked()
            raise RuntimeError(f"embedding request failed: {e}") from None
        self.last_used = time.time()
        data = sorted(r.get("data", []), key=lambda d: d.get("index", 0))
        return [_shorten(d["embedding"]) for d in data]

    def _stop_locked(self) -> None:
        p, self.proc = self.proc, None
        if p is None or p.poll() is not None:
            return
        p.terminate()
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            p.kill()
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass

    def stop(self) -> None:
        with self.lock:
            self._stop_locked()

    def _idle_watch(self) -> None:
        while True:
            time.sleep(10)
            if self.running() and time.time() - self.last_used > IDLE_STOP_S:
                if self.lock.acquire(timeout=1):
                    try:
                        if time.time() - self.last_used > IDLE_STOP_S:
                            self._stop_locked()
                    finally:
                        self.lock.release()


class SemanticIndex:
    """Embedding vectors for one project folder, stored in ~/.forge-ide/index/<id>/ and updated incrementally."""

    def __init__(self, root: str, server: EmbedServer, busy: Callable[[], bool]):
        self.root = root
        self.server = server
        self.busy = busy
        self.dir = os.path.join(DATA, "index", hashlib.sha1(root.encode()).hexdigest()[:16])
        self.lock = threading.Lock()
        self.files: Dict[str, List[float]] = {}               # rel -> [mtime, size]
        self.chunks: List[Tuple[str, int, int]] = []           # (rel, start, end)
        self.vectors = array.array("f")
        self.status = {"state": "idle", "done": 0, "total": 0, "chunks": 0, "error": None, "updated": None}
        self.worker: Optional[threading.Thread] = None
        self.stop_flag = False
        self._load()

    # ------------------------------------------------------------------ storage
    def _load(self) -> None:
        try:
            with open(os.path.join(self.dir, "meta.json"), "r", encoding="utf-8") as fh:
                meta = json.load(fh)
            if meta.get("dim") != DIM or meta.get("model") != os.path.basename(MODEL):
                return
            vec = array.array("f")
            with open(os.path.join(self.dir, "vectors.f32"), "rb") as fh:
                vec.frombytes(fh.read())
            chunks = [tuple(c) for c in meta.get("chunks", [])]
            if len(vec) != len(chunks) * DIM:
                return
            self.files, self.chunks, self.vectors = meta.get("files", {}), chunks, vec
            self.status.update(state="ready" if chunks else "idle", chunks=len(chunks), updated=meta.get("updated"))
        except (OSError, ValueError, KeyError):
            pass

    def _save(self) -> None:
        os.makedirs(self.dir, exist_ok=True)
        meta = {"root": self.root, "model": os.path.basename(MODEL), "dim": DIM, "updated": time.time(),
                "files": self.files, "chunks": [list(c) for c in self.chunks]}
        tmp_meta, tmp_vec = os.path.join(self.dir, "meta.json.new"), os.path.join(self.dir, "vectors.f32.new")
        with open(tmp_vec, "wb") as fh:
            fh.write(self.vectors.tobytes())
        with open(tmp_meta, "w", encoding="utf-8") as fh:
            json.dump(meta, fh)
        os.replace(tmp_vec, os.path.join(self.dir, "vectors.f32"))
        os.replace(tmp_meta, os.path.join(self.dir, "meta.json"))
        self.status["updated"] = meta["updated"]

    # ------------------------------------------------------------------ indexing
    def start(self) -> None:
        if self.worker and self.worker.is_alive():
            return
        self.stop_flag = False
        self.worker = threading.Thread(target=self._run, name="semantic-index", daemon=True)
        self.worker.start()

    def pause(self) -> None:
        self.stop_flag = True

    def _run(self) -> None:
        try:
            self._index()
        except Exception as e:  # noqa: BLE001
            self.status.update(state="error", error=str(e)[:300])

    def _candidates(self) -> List[Tuple[str, float, int]]:
        """Project files worth indexing: git's list when the folder is a repository (it respects .gitignore),
        otherwise the keyword index's list. Vendored, minified, generated and lock files are skipped."""
        files = None
        try:
            r = subprocess.run(["git", "-C", self.root, "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                               capture_output=True, timeout=20)
            if r.returncode == 0 and r.stdout:
                files = []
                from .codesearch import MAX_FILE_BYTES, TEXT_EXT
                from .tools import BINARY_EXT, IGNORED_DIRS
                for rel in r.stdout.decode("utf-8", "replace").split("\0")[:20000]:
                    if not rel or any(part in IGNORED_DIRS for part in rel.split("/")):
                        continue
                    ext = os.path.splitext(rel)[1].lower()
                    if ext in BINARY_EXT or (ext not in TEXT_EXT and "." in os.path.basename(rel)):
                        continue
                    p = os.path.join(self.root, rel)
                    try:
                        st = os.stat(p)
                    except OSError:
                        continue
                    if 0 < st.st_size <= MAX_FILE_BYTES:
                        files.append((p, st.st_mtime, st.st_size))
        except (OSError, subprocess.TimeoutExpired):
            files = None
        if files is None:
            files = CodeIndex(self.root)._files()
        out = []
        for p, m, sz in files:
            rel = os.path.relpath(p, self.root).replace(os.sep, "/")
            low = rel.lower()
            if any(part in SKIP_DIRS for part in low.split("/")[:-1]) or low.endswith(SKIP_SUFFIXES):
                continue
            out.append((p, m, sz))
        return out

    @staticmethod
    def _looks_generated(text: str) -> bool:
        lines = text.split("\n")
        return bool(lines) and (len(text) / max(1, len(lines)) > 250 or max(len(l) for l in lines) > 3000)

    def _index(self) -> None:
        files = self._candidates()
        files.sort(key=lambda f: -f[1])            # newest files first, so a cap keeps what you work on
        current = {os.path.relpath(p, self.root).replace(os.sep, "/"): (p, m, s) for p, m, s in files}
        with self.lock:
            # Drop vectors of files that changed or no longer exist.
            stale = {rel for rel, (mt, sz) in self.files.items()
                     if rel not in current or abs(current[rel][1] - mt) > 1e-3 or current[rel][2] != sz}
            # Chunks of a file whose indexing was interrupted are dropped too, so the file is indexed again whole.
            stale |= {c[0] for c in self.chunks} - set(self.files)
            if stale:
                keep = [i for i, c in enumerate(self.chunks) if c[0] not in stale]
                vec = array.array("f")
                for i in keep:
                    vec.extend(self.vectors[i * DIM:(i + 1) * DIM])
                self.chunks = [self.chunks[i] for i in keep]
                self.vectors = vec
                for rel in stale:
                    self.files.pop(rel, None)
        todo: List[Tuple[str, int, int, str]] = []
        todo_files: Dict[str, Tuple[float, int]] = {}
        budget = MAX_CHUNKS - len(self.chunks)
        for rel, (p, m, s) in current.items():
            if rel in self.files or budget <= 0:
                continue
            try:
                with open(p, "r", encoding="utf-8", errors="replace") as fh:
                    whole = fh.read()
            except OSError:
                continue
            if self._looks_generated(whole):
                continue
            lines = whole.split("\n")
            for i in range(0, len(lines), CHUNK_LINES):
                part = lines[i:i + CHUNK_LINES]
                text = "\n".join(part)
                if text.strip() and budget > 0:
                    todo.append((rel, i + 1, i + len(part), text[:CHUNK_CHARS]))
                    budget -= 1
            todo_files[rel] = (m, s)
        self.status.update(state="indexing" if todo else "ready", done=0, total=len(todo), error=None,
                           chunks=len(self.chunks))
        if not todo:
            if stale:
                self._save()
            return
        done_files: Dict[str, int] = {}
        need = {rel: sum(1 for t in todo if t[0] == rel) for rel in todo_files}
        last_save = time.time()
        i = 0
        while i < len(todo):
            if self.stop_flag:
                self.status["state"] = "paused"
                break
            if self.busy():
                self.status["state"] = "waiting for the chat to finish"
                time.sleep(3)
                continue
            batch = todo[i:i + BATCH]
            try:
                vecs = self.server.embed([f"search_document: {rel}\n{text}" for rel, _a, _b, text in batch])
            except RuntimeError as e:
                self.status.update(state="waiting", error=str(e)[:300])
                time.sleep(30)
                if self.stop_flag:
                    break
                continue
            self.status["state"] = "indexing"
            self.status["error"] = None
            with self.lock:
                for (rel, a, b, _t), v in zip(batch, vecs):
                    self.chunks.append((rel, a, b))
                    self.vectors.extend(v)
                    done_files[rel] = done_files.get(rel, 0) + 1
                    if done_files[rel] == need[rel]:
                        self.files[rel] = list(todo_files[rel])
            i += len(batch)
            self.status.update(done=i, chunks=len(self.chunks))
            if time.time() - last_save > 20:
                with self.lock:
                    self._save()
                last_save = time.time()
        with self.lock:
            self._save()
        if self.status["state"] == "indexing":
            self.status["state"] = "ready"

    # ------------------------------------------------------------------ search
    def search(self, query: str, limit: int = 30) -> List[Tuple[float, str, int, int]]:
        if not self.chunks:
            return []
        q = self.server.embed([f"search_query: {query}"])[0]
        with self.lock:
            vec, chunks = self.vectors, list(self.chunks)
        scores = []
        for i in range(len(chunks)):
            row = vec[i * DIM:(i + 1) * DIM]
            scores.append((sum(a * b for a, b in zip(q, row)), i))
        scores.sort(reverse=True)
        return [(s, *chunks[i]) for s, i in scores[:limit]]


def hybrid_search_text(code_index: CodeIndex, sem: Optional[SemanticIndex], query: str, limit: int = 8,
                       max_chars: int = 9000) -> str:
    """Blend keyword and meaning-based results with reciprocal rank fusion."""
    kw = code_index.search(query, 30)
    meaning: List[Tuple[float, str, int, int]] = []
    note = ""
    if sem is not None and sem.chunks:
        try:
            meaning = sem.search(query, 30)
        except RuntimeError as e:
            note = f" (meaning-based search unavailable right now: {e})"
    elif sem is not None:
        note = " (the meaning-based index is not built yet, so this is keyword ranking only)"
    texts: Dict[Tuple[str, int], Tuple[int, str]] = {}
    fused: Dict[Tuple[str, int], float] = {}
    for rank, (_s, rel, a, b, text) in enumerate(kw):
        fused[(rel, a)] = fused.get((rel, a), 0.0) + 1.0 / (60 + rank)
        texts[(rel, a)] = (b, text)
    for rank, (_s, rel, a, b) in enumerate(meaning):
        fused[(rel, a)] = fused.get((rel, a), 0.0) + 1.0 / (60 + rank)
        if (rel, a) not in texts:
            try:
                with open(os.path.join(code_index.root, rel), "r", encoding="utf-8", errors="replace") as fh:
                    lines = fh.read().split("\n")
                texts[(rel, a)] = (b, "\n".join(lines[a - 1:b]))
            except OSError:
                continue
    best = sorted((k for k in fused if k in texts), key=lambda k: -fused[k])[:limit]
    if not best:
        return f"No code related to '{query}' found{note}."
    how = "meaning and keywords" if meaning else "keyword relevance"
    out = [f"Top {len(best)} code chunks related to '{query}' (ranked by {how}){note}:"]
    used = 0
    for rel, a in best:
        b, text = texts[(rel, a)]
        block = f"\n--- {rel} (lines {a}-{b}) ---\n{text}"
        if used + len(block) > max_chars:
            block = block[: max_chars - used] + "\n... [chunk truncated]"
        out.append(block)
        used += len(block)
        if used >= max_chars:
            break
    return "\n".join(out)
