"""Ranked codebase search without an embedding model: BM25 over code chunks, identifiers split into words.
Cheap to build (a few seconds for thousands of files) and uses a few tens of MB at most."""
from __future__ import annotations

import math
import os
import re
import threading
import time
from collections import Counter, defaultdict
from typing import Dict, List, Optional, Tuple

from .tools import IGNORED_DIRS, BINARY_EXT

TEXT_EXT = {".py", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".json", ".html", ".htm", ".css", ".scss", ".md", ".txt",
            ".sh", ".bash", ".yml", ".yaml", ".toml", ".ini", ".cfg", ".xml", ".java", ".kt", ".kts", ".go", ".rs", ".c",
            ".h", ".cc", ".cpp", ".hpp", ".cs", ".rb", ".php", ".sql", ".swift", ".dart", ".lua", ".gradle", ".properties",
            ".env.example", ".service", ".conf", ".csv"}
MAX_FILE_BYTES = 300_000
CHUNK_LINES = 40
TOKEN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+")
STOP = {"the", "a", "an", "and", "or", "of", "to", "in", "is", "it", "for", "on", "with", "as", "by", "at", "be", "this",
        "that", "from", "if", "else", "return", "self", "def", "class", "import", "const", "let", "var", "function", "new",
        "null", "none", "true", "false", "int", "str", "string", "public", "private", "static", "void", "end", "then"}


def tokenize(text: str) -> List[str]:
    out: List[str] = []
    for tok in TOKEN_RE.findall(text):
        if tok.isdigit():
            continue
        # split snake_case and camelCase into words
        parts = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", tok).replace("_", " ").lower().split()
        if len(parts) > 1:
            out.append(tok.lower())
        for p in parts:
            if len(p) > 1 and p not in STOP:
                out.append(p)
    return out


class CodeIndex:
    def __init__(self, root: str):
        self.root = root
        self.lock = threading.Lock()
        self.built = 0.0
        self.signature: Tuple[int, float] = (0, 0.0)
        self.chunks: List[Tuple[str, int, int, str]] = []   # (path, start_line, end_line, text)
        self.df: Counter = Counter()
        self.tf: List[Counter] = []
        self.lengths: List[int] = []
        self.avg_len = 1.0

    def _files(self) -> List[Tuple[str, float, int]]:
        out = []
        for dirpath, dirnames, filenames in os.walk(self.root):
            dirnames[:] = [d for d in dirnames if d not in IGNORED_DIRS and not d.startswith(".")]
            for f in filenames:
                ext = os.path.splitext(f)[1].lower()
                if ext in BINARY_EXT or (ext not in TEXT_EXT and "." in f):
                    continue
                p = os.path.join(dirpath, f)
                try:
                    st = os.stat(p)
                except OSError:
                    continue
                if st.st_size > MAX_FILE_BYTES or st.st_size == 0:
                    continue
                out.append((p, st.st_mtime, st.st_size))
                if len(out) >= 6000:
                    return out
        return out

    def ensure(self, max_age: float = 60.0) -> None:
        with self.lock:
            if time.time() - self.built < max_age and self.chunks:
                return
            files = self._files()
            sig = (len(files), max((m for _, m, _ in files), default=0.0))
            if sig == self.signature and self.chunks:
                self.built = time.time()
                return
            self._build(files)
            self.signature = sig
            self.built = time.time()

    def _build(self, files: List[Tuple[str, float, int]]) -> None:
        chunks, tfs, lengths, df = [], [], [], Counter()
        t0 = time.time()
        for p, _, _ in files:
            try:
                with open(p, "r", encoding="utf-8", errors="replace") as fh:
                    lines = fh.read().split("\n")
            except OSError:
                continue
            rel = os.path.relpath(p, self.root).replace(os.sep, "/")
            path_tokens = tokenize(rel)
            for i in range(0, len(lines), CHUNK_LINES):
                part = lines[i:i + CHUNK_LINES]
                text = "\n".join(part)
                if not text.strip():
                    continue
                toks = tokenize(text) + path_tokens
                if not toks:
                    continue
                c = Counter(toks)
                chunks.append((rel, i + 1, i + len(part), text))
                tfs.append(c)
                lengths.append(len(toks))
                df.update(c.keys())
            if time.time() - t0 > 20:   # never spend more than 20 seconds indexing
                break
        self.chunks, self.tf, self.lengths, self.df = chunks, tfs, lengths, df
        self.avg_len = (sum(lengths) / len(lengths)) if lengths else 1.0

    def search(self, query: str, limit: int = 8) -> List[Tuple[float, str, int, int, str]]:
        self.ensure()
        q = tokenize(query)
        if not q or not self.chunks:
            return []
        n = len(self.chunks)
        k1, b = 1.5, 0.75
        scores: Dict[int, float] = defaultdict(float)
        for term in set(q):
            dfreq = self.df.get(term, 0)
            if not dfreq:
                continue
            idf = math.log(1 + (n - dfreq + 0.5) / (dfreq + 0.5))
            for idx, tf in enumerate(self.tf):
                f = tf.get(term)
                if not f:
                    continue
                denom = f + k1 * (1 - b + b * self.lengths[idx] / self.avg_len)
                scores[idx] += idf * f * (k1 + 1) / denom
        best = sorted(scores.items(), key=lambda kv: -kv[1])[:limit]
        return [(s, *self.chunks[i]) for i, s in best]

    def search_text(self, query: str, limit: int = 8, max_chars: int = 9000) -> str:
        hits = self.search(query, limit)
        if not hits:
            return f"No code related to '{query}' found (index covers {len(self.chunks)} chunks)."
        out = [f"Top {len(hits)} code chunks related to '{query}' (ranked by keyword relevance):"]
        used = 0
        for score, rel, a, bb, text in hits:
            block = f"\n--- {rel} (lines {a}-{bb}) ---\n{text}"
            if used + len(block) > max_chars:
                block = block[: max_chars - used] + "\n... [chunk truncated]"
            out.append(block)
            used += len(block)
            if used >= max_chars:
                break
        return "\n".join(out)
