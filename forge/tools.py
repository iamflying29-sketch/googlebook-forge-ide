"""Workspace tools the agent can call. Every tool is rooted in the workspace folder, returns a
bounded string, and has a timeout. File edits are checkpointed so they can be reviewed and reverted.
"""
from __future__ import annotations

import fnmatch
import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

MAX_TOOL_OUTPUT = 24_000          # characters returned to the model per tool call
MAX_FILE_READ = 60_000            # characters per read_file call
IGNORED_DIRS = {".git", "node_modules", "__pycache__", ".venv", "venv", "dist", "build", ".forge-trash",
                ".idea", ".vscode", ".gradle", "target", ".next", ".cache", ".mypy_cache", ".pytest_cache"}
BINARY_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".gz", ".tgz", ".tar", ".jar",
              ".apk", ".dex", ".so", ".o", ".a", ".bin", ".exe", ".dll", ".class", ".woff", ".woff2", ".ttf",
              ".mp3", ".mp4", ".mov", ".ogg", ".wav", ".sqlite", ".db", ".pyc", ".gguf"}

DANGEROUS_COMMAND = re.compile(
    r"(^|[;&|]\s*)(sudo\s+)?(rm\s+-[a-zA-Z]*r|rm\s+-[a-zA-Z]*f|rmdir|mkfs|dd\s+if=|shred|chmod\s+-R\s+777|"
    r":\(\)\s*\{|git\s+(push\s+.*--force|reset\s+--hard|clean\s+-[a-zA-Z]*f)|"
    r"pm\s+(uninstall|clear)|adb\s+.*\b(rm|uninstall|clear)\b|truncate\s|>\s*/dev/sd|killall|pkill\s+-9|"
    r"shutdown|reboot|poweroff|halt|systemctl\s+(stop|disable|mask))", re.IGNORECASE)


def is_dangerous_command(cmd: str) -> bool:
    return bool(DANGEROUS_COMMAND.search(cmd))


def _clip(text: str, limit: int = MAX_TOOL_OUTPUT) -> str:
    if len(text) <= limit:
        return text
    head = text[: limit - 400]
    return head + f"\n\n... [output truncated: {len(text) - len(head):,} more characters] ..."


def _is_binary_path(path: str) -> bool:
    return os.path.splitext(path)[1].lower() in BINARY_EXT


@dataclass
class FileChange:
    path: str                      # relative path
    kind: str                      # created | modified | deleted
    before: Optional[str]          # original content (None when the file did not exist)
    after: Optional[str]           # new content (None when deleted)
    ts: float = field(default_factory=time.time)


class Checkpoints:
    """Remembers the original content of every file an agent run touches so changes can be reviewed
    and reverted. Only the first snapshot per run and file is kept (the state before the run)."""

    def __init__(self) -> None:
        self.runs: Dict[str, Dict[str, FileChange]] = {}

    def record(self, run_id: str, rel: str, before: Optional[str], after: Optional[str]) -> None:
        run = self.runs.setdefault(run_id, {})
        if rel in run:
            run[rel].after = after
            if run[rel].kind == "created" and after is None:
                run[rel].kind = "deleted"
            return
        kind = "created" if before is None else ("deleted" if after is None else "modified")
        run[rel] = FileChange(rel, kind, before, after)

    def changes(self, run_id: str) -> List[FileChange]:
        return list(self.runs.get(run_id, {}).values())

    def get(self, run_id: str, rel: str) -> Optional[FileChange]:
        return self.runs.get(run_id, {}).get(rel)


class Workspace:
    def __init__(self, root: str, checkpoints: Optional[Checkpoints] = None):
        self.root = os.path.realpath(os.path.expanduser(root))
        self.checkpoints = checkpoints or Checkpoints()
        self._file_cache: Tuple[float, List[str]] = (0.0, [])

    # ------------------------------------------------------------------ paths
    def resolve(self, rel: str, must_exist: bool = False) -> str:
        rel = (rel or ".").strip()
        if rel.startswith("~"):
            raise ValueError("Paths must be relative to the workspace (no ~).")
        p = os.path.realpath(os.path.join(self.root, rel))
        if p != self.root and not p.startswith(self.root + os.sep):
            raise ValueError(f"Path '{rel}' is outside the workspace. Only files inside {self.root} may be used.")
        if must_exist and not os.path.exists(p):
            raise FileNotFoundError(f"'{rel}' does not exist in the workspace.")
        return p

    def relpath(self, abs_path: str) -> str:
        return os.path.relpath(abs_path, self.root).replace(os.sep, "/")

    # ------------------------------------------------------------------ listing
    def list_dir(self, rel: str = ".") -> str:
        p = self.resolve(rel, must_exist=True)
        if not os.path.isdir(p):
            raise ValueError(f"'{rel}' is a file, not a directory. Use read_file to read it.")
        entries = []
        with os.scandir(p) as it:
            for e in sorted(it, key=lambda e: (not e.is_dir(follow_symlinks=False), e.name.lower())):
                if e.name in IGNORED_DIRS:
                    entries.append(f"{e.name}/   (ignored)")
                    continue
                if e.is_dir(follow_symlinks=False):
                    try:
                        n = len(os.listdir(e.path))
                    except OSError:
                        n = 0
                    entries.append(f"{e.name}/   ({n} items)")
                else:
                    try:
                        size = e.stat(follow_symlinks=False).st_size
                    except OSError:
                        size = 0
                    entries.append(f"{e.name}   ({size:,} bytes)")
        if not entries:
            return f"{rel or '.'}/ is empty."
        return _clip(f"Contents of {rel or '.'}/ ({len(entries)} entries):\n" + "\n".join(entries))

    def all_files(self, max_files: int = 20000, refresh: bool = False) -> List[str]:
        now = time.time()
        if not refresh and now - self._file_cache[0] < 5 and self._file_cache[1]:
            return self._file_cache[1]
        out: List[str] = []
        for dirpath, dirnames, filenames in os.walk(self.root):
            dirnames[:] = [d for d in sorted(dirnames) if d not in IGNORED_DIRS and not d.startswith(".")]
            for f in sorted(filenames):
                out.append(self.relpath(os.path.join(dirpath, f)))
                if len(out) >= max_files:
                    self._file_cache = (now, out)
                    return out
        self._file_cache = (now, out)
        return out

    def find_files(self, pattern: str, limit: int = 200) -> str:
        pattern = pattern.strip()
        files = self.all_files()
        if "*" not in pattern and "?" not in pattern:
            matches = [f for f in files if pattern.lower() in f.lower()]
        else:
            pat = pattern if "/" in pattern else f"*{pattern}"
            matches = [f for f in files if fnmatch.fnmatch(f, pat) or fnmatch.fnmatch(os.path.basename(f), pattern)]
        if not matches:
            return f"No files match '{pattern}'. The workspace has {len(files)} files; try list_dir or a shorter pattern."
        more = f"\n... and {len(matches) - limit} more" if len(matches) > limit else ""
        return f"{len(matches)} matching files:\n" + "\n".join(matches[:limit]) + more

    def repo_map(self, max_chars: int = 6000) -> str:
        """A compact outline of the workspace: file list plus top-level definitions of code files."""
        files = self.all_files()
        lines: List[str] = [f"Workspace: {self.root} ({len(files)} files)"]
        budget = max_chars
        if len(files) > 150:
            return self._repo_overview(files, max_chars)
        defre = re.compile(r"^(?:export\s+)?(?:async\s+)?(?:def|class|function|fn|func|struct|enum|interface|type|impl|pub fn|public\s+(?:static\s+)?(?:class|interface|void|[A-Za-z<>\[\]]+\s+\w+\s*\())\s*([A-Za-z_][\w]*)?", re.M)
        for f in files[:400]:
            ext = os.path.splitext(f)[1].lower()
            line = f"- {f}"
            if ext in {".py", ".js", ".ts", ".tsx", ".jsx", ".java", ".kt", ".go", ".rs", ".c", ".cc", ".cpp", ".h", ".sh", ".rb", ".php", ".swift", ".cs"}:
                try:
                    with open(os.path.join(self.root, f), "r", encoding="utf-8", errors="replace") as fh:
                        src = fh.read(40000)
                    names = []
                    for m in defre.finditer(src):
                        n = m.group(1)
                        if n and n not in names:
                            names.append(n)
                        if len(names) >= 12:
                            break
                    if names:
                        line += "  [" + ", ".join(names) + "]"
                except OSError:
                    pass
            if budget - len(line) < 0:
                lines.append(f"- ... ({len(files) - len(lines) + 1} more files; use find_files / list_dir)")
                break
            budget -= len(line) + 1
            lines.append(line)
        return "\n".join(lines)

    def _repo_overview(self, files: List[str], max_chars: int) -> str:
        """For big workspaces: top-level folders with file counts and sub-folders, plus top-level files."""
        from collections import Counter, defaultdict
        top_files = [f for f in files if "/" not in f]
        counts: Counter = Counter()
        subdirs: Dict[str, set] = defaultdict(set)
        for f in files:
            if "/" in f:
                top, rest = f.split("/", 1)
                counts[top] += 1
                if "/" in rest:
                    subdirs[top].add(rest.split("/", 1)[0])
        lines = [f"Workspace: {self.root} ({len(files)} files in {len(counts)} top-level folders). Overview only; "
                 f"use list_dir, find_files and search to explore."]
        for top, n in counts.most_common():
            subs = sorted(subdirs.get(top, ()))
            extra = (" | " + ", ".join(subs[:8]) + (", ..." if len(subs) > 8 else "")) if subs else ""
            line = f"- {top}/ ({n} files){extra}"
            if sum(len(l) + 1 for l in lines) + len(line) > max_chars:
                lines.append(f"- ... {len(counts) - len(lines) + 1} more folders")
                break
            lines.append(line)
        if top_files:
            tf = ", ".join(top_files[:25]) + (", ..." if len(top_files) > 25 else "")
            lines.append(f"- top-level files: {tf}")
        return "\n".join(lines)

    # ------------------------------------------------------------------ reading
    def read_text(self, rel: str) -> str:
        p = self.resolve(rel, must_exist=True)
        if os.path.isdir(p):
            raise ValueError(f"'{rel}' is a directory. Use list_dir.")
        if _is_binary_path(p):
            raise ValueError(f"'{rel}' is a binary file ({os.path.getsize(p):,} bytes) and cannot be read as text.")
        with open(p, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()

    def read_file(self, rel: str, start_line: Optional[int] = None, end_line: Optional[int] = None) -> str:
        text = self.read_text(rel)
        lines = text.split("\n")
        total = len(lines)
        s = max(1, int(start_line or 1))
        e = min(total, int(end_line or total))
        if s > total:
            return f"{rel} has only {total} lines."
        chunk = lines[s - 1:e]
        width = len(str(e))
        numbered = "\n".join(f"{i:{width}d}| {l}" for i, l in enumerate(chunk, start=s))
        header = f"File: {rel} (lines {s}-{e} of {total})\n"
        if len(numbered) > MAX_FILE_READ:
            numbered = numbered[:MAX_FILE_READ] + f"\n... [truncated; read a smaller line range with start_line/end_line] ..."
        return header + numbered

    # ------------------------------------------------------------------ writing
    def _snapshot(self, run_id: Optional[str], rel: str) -> Optional[str]:
        p = os.path.join(self.root, rel)
        if os.path.exists(p) and not os.path.isdir(p) and not _is_binary_path(p):
            try:
                with open(p, "r", encoding="utf-8", errors="replace") as fh:
                    return fh.read()
            except OSError:
                return None
        return None

    def write_file(self, rel: str, content: str, run_id: Optional[str] = None) -> str:
        p = self.resolve(rel)
        rel = self.relpath(p)
        before = self._snapshot(run_id, rel) if os.path.exists(p) else None
        os.makedirs(os.path.dirname(p) or self.root, exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="") as fh:
            fh.write(content)
        if run_id:
            self.checkpoints.record(run_id, rel, before, content)
        self._file_cache = (0.0, [])
        n = content.count("\n") + (0 if content.endswith("\n") or not content else 1)
        verb = "Created" if before is None else "Wrote"
        return f"{verb} {rel} ({n} lines, {len(content):,} characters)." + self.verify(rel)

    def edit_file(self, rel: str, old_text: str, new_text: str, run_id: Optional[str] = None,
                  replace_all: bool = False) -> str:
        p = self.resolve(rel, must_exist=True)
        rel = self.relpath(p)
        src = self.read_text(rel)
        if old_text == "":
            raise ValueError("old_text must not be empty. To create or overwrite a whole file use write_file.")
        count = src.count(old_text)
        used = old_text
        if count == 0:
            # Fuzzy fallback: ignore differences in leading/trailing whitespace of each line.
            used = self._fuzzy_locate(src, old_text)
            if used is None:
                hint = self._nearest_hint(src, old_text)
                raise ValueError(f"old_text was not found in {rel}. Nothing changed. Read the file again and copy the "
                                 f"exact text (including indentation).{hint}")
            count = src.count(used)
        if count > 1 and not replace_all:
            raise ValueError(f"old_text occurs {count} times in {rel}. Include more surrounding lines so it is unique, "
                             f"or set replace_all to true.")
        new_src = src.replace(used, new_text) if replace_all else src.replace(used, new_text, 1)
        before = src
        with open(p, "w", encoding="utf-8", newline="") as fh:
            fh.write(new_src)
        if run_id:
            self.checkpoints.record(run_id, rel, before, new_src)
        # Show the model the edited region so it can confirm the result.
        idx = new_src.find(new_text)
        line_no = new_src.count("\n", 0, max(idx, 0)) + 1
        ctx_lines = new_src.split("\n")
        a = max(0, line_no - 4)
        b = min(len(ctx_lines), line_no + new_text.count("\n") + 4)
        width = len(str(b))
        preview = "\n".join(f"{i:{width}d}| {l}" for i, l in enumerate(ctx_lines[a:b], start=a + 1))
        return (f"Edited {rel}: replaced {count if replace_all else 1} occurrence(s). The file now has "
                f"{len(ctx_lines)} lines. Region after the edit:\n{preview}") + self.verify(rel)

    @staticmethod
    def _norm(s: str) -> str:
        return "\n".join(l.strip() for l in s.strip("\n").split("\n"))

    def _fuzzy_locate(self, src: str, old: str) -> Optional[str]:
        want = self._norm(old)
        if not want:
            return None
        want_lines = want.split("\n")
        src_lines = src.split("\n")
        n = len(want_lines)
        for i in range(0, len(src_lines) - n + 1):
            window = src_lines[i:i + n]
            if "\n".join(l.strip() for l in window) == want:
                return "\n".join(window)
        return None

    def _nearest_hint(self, src: str, old: str) -> str:
        first = next((l.strip() for l in old.split("\n") if l.strip()), "")
        if not first:
            return ""
        for i, l in enumerate(src.split("\n"), start=1):
            if first[:40] in l:
                return f" Hint: a line containing '{first[:40]}' exists at line {i}; read around it."
        return ""

    def delete_path(self, rel: str, run_id: Optional[str] = None) -> str:
        """Move a file or folder to the workspace's .forge-trash folder instead of deleting it."""
        p = self.resolve(rel, must_exist=True)
        rel = self.relpath(p)
        if p == self.root:
            raise ValueError("Refusing to remove the workspace root.")
        trash = os.path.join(self.root, ".forge-trash", time.strftime("%Y%m%d-%H%M%S"))
        os.makedirs(trash, exist_ok=True)
        dest = os.path.join(trash, rel.replace("/", "__"))
        before = self._snapshot(run_id, rel)
        shutil.move(p, dest)
        if run_id and before is not None:
            self.checkpoints.record(run_id, rel, before, None)
        self._file_cache = (0.0, [])
        return f"Moved {rel} to {self.relpath(dest)} (the workspace trash folder; nothing is permanently deleted)."

    def revert(self, run_id: str, rel: str) -> str:
        ch = self.checkpoints.get(run_id, rel)
        if ch is None:
            raise ValueError("No checkpoint for that file.")
        p = self.resolve(rel)
        if ch.before is None:
            if os.path.exists(p):
                self.delete_path(rel)
            msg = f"Removed {rel} (it did not exist before the run)."
        else:
            os.makedirs(os.path.dirname(p) or self.root, exist_ok=True)
            with open(p, "w", encoding="utf-8", newline="") as fh:
                fh.write(ch.before)
            msg = f"Restored {rel} to its content before the run."
        ch.after = ch.before
        ch.kind = "reverted"
        self._file_cache = (0.0, [])
        return msg

    # ------------------------------------------------------------------ verification
    def verify(self, rel: str) -> str:
        """Cheap automatic checks after an edit; the result is appended to the tool output so the model
        notices syntax errors right away."""
        p = os.path.join(self.root, rel)
        ext = os.path.splitext(rel)[1].lower()
        try:
            if ext == ".py":
                r = subprocess.run(["python3", "-I", "-m", "py_compile", p], capture_output=True, text=True, timeout=20)
                if r.returncode != 0:
                    return "\nSYNTAX CHECK FAILED (python3 -m py_compile):\n" + _clip(r.stderr.strip(), 2000)
                return "\nSyntax check: OK (py_compile)."
            if ext in {".js", ".mjs", ".cjs"} and shutil.which("node"):
                r = subprocess.run(["node", "--check", p], capture_output=True, text=True, timeout=20)
                if r.returncode != 0:
                    return "\nSYNTAX CHECK FAILED (node --check):\n" + _clip(r.stderr.strip(), 2000)
                return "\nSyntax check: OK (node --check)."
            if ext == ".json":
                with open(p, "r", encoding="utf-8") as fh:
                    json.load(fh)
                return "\nSyntax check: OK (valid JSON)."
            if ext == ".sh" and shutil.which("bash"):
                r = subprocess.run(["bash", "-n", p], capture_output=True, text=True, timeout=20)
                if r.returncode != 0:
                    return "\nSYNTAX CHECK FAILED (bash -n):\n" + _clip(r.stderr.strip(), 2000)
                return "\nSyntax check: OK (bash -n)."
        except json.JSONDecodeError as e:
            return f"\nSYNTAX CHECK FAILED (JSON): {e}"
        except (subprocess.TimeoutExpired, OSError) as e:
            return f"\n(syntax check skipped: {e})"
        return ""

    # ------------------------------------------------------------------ searching
    def search(self, pattern: str, rel: str = ".", glob: Optional[str] = None, regex: bool = False,
               max_results: int = 200, timeout: float = 25.0) -> str:
        p = self.resolve(rel, must_exist=True)
        cmd = ["grep", "-rnI", "--color=never", "-m", "50"]
        for d in IGNORED_DIRS:
            cmd += ["--exclude-dir", d]
        if glob:
            for g in str(glob).split(","):
                g = g.strip()
                if g:
                    cmd += ["--include", g]
        cmd += ["-E" if regex else "-F", "--", pattern, p]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, errors="replace")
        except subprocess.TimeoutExpired:
            return "Search timed out after 25 seconds. Narrow the path or pattern."
        if r.returncode == 2 and r.stderr:
            return "grep error: " + _clip(r.stderr, 1000)
        lines = r.stdout.splitlines()
        out = []
        for l in lines[:max_results]:
            if l.startswith(self.root + os.sep):
                l = l[len(self.root) + 1:]
            if len(l) > 300:
                l = l[:300] + " ..."
            out.append(l)
        if not out:
            return f"No matches for '{pattern}' under {rel or '.'}."
        more = f"\n... {len(lines) - max_results} more matches not shown (narrow the search)." if len(lines) > max_results else ""
        return _clip(f"{len(lines)} matches for '{pattern}':\n" + "\n".join(out) + more)

    # ------------------------------------------------------------------ commands
    def run_command(self, command: str, timeout: float = 90.0, cwd: Optional[str] = None) -> Tuple[str, int]:
        timeout = max(1.0, min(float(timeout or 90.0), 600.0))
        wd = self.resolve(cwd, must_exist=True) if cwd else self.root
        env = dict(os.environ)
        env.setdefault("TERM", "dumb")
        env["FORGE_IDE"] = "1"
        env["NO_AUTO_TMUX"] = "1"
        start = time.monotonic()
        try:
            proc = subprocess.Popen(["bash", "-lc", command], cwd=wd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    stdin=subprocess.DEVNULL, env=env, start_new_session=True)
        except OSError as e:
            return f"Failed to start command: {e}", -1
        chunks: List[bytes] = []
        total = 0
        cap = 400_000
        try:
            assert proc.stdout is not None
            import select
            fd = proc.stdout
            while True:
                if time.monotonic() - start > timeout:
                    self._kill(proc)
                    chunks.append(f"\n[command killed after {timeout:.0f} s timeout]".encode())
                    break
                ready, _, _ = select.select([fd], [], [], 0.25)
                if ready:
                    data = os.read(fd.fileno(), 65536)
                    if not data:
                        break
                    if total < cap:
                        chunks.append(data[: cap - total])
                    total += len(data)
                elif proc.poll() is not None:
                    rest = fd.read()
                    if rest and total < cap:
                        chunks.append(rest[: cap - total])
                    break
            proc.wait(timeout=5)
        except Exception as e:  # noqa: BLE001
            self._kill(proc)
            chunks.append(f"\n[error while running: {e}]".encode())
        code = proc.returncode if proc.returncode is not None else -1
        text = b"".join(chunks).decode("utf-8", errors="replace")
        if total > cap:
            text += f"\n[output capped at {cap:,} bytes; {total - cap:,} bytes dropped]"
        dur = time.monotonic() - start
        head = f"$ {command}\n(exit code {code}, {dur:.1f} s)\n"
        return head + (_clip(text) if text.strip() else "(no output)"), code

    @staticmethod
    def _kill(proc: subprocess.Popen) -> None:
        try:
            os.killpg(proc.pid, 15)
            time.sleep(0.3)
            if proc.poll() is None:
                os.killpg(proc.pid, 9)
        except (ProcessLookupError, PermissionError):
            pass

    # ------------------------------------------------------------------ web
    @staticmethod
    def web_fetch(url: str, timeout: float = 20.0, limit: int = 12000) -> str:
        if not re.match(r"^https?://", url):
            raise ValueError("Only http(s) URLs are allowed.")
        req = urllib.request.Request(url, headers={"User-Agent": "ForgeIDE/1.0 (+local)"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(2_000_000)
            ctype = resp.headers.get("Content-Type", "")
        text = raw.decode("utf-8", errors="replace")
        if "html" in ctype or "<html" in text[:2000].lower():
            text = re.sub(r"(?is)<(script|style|noscript|svg).*?</\1>", " ", text)
            text = re.sub(r"(?s)<[^>]+>", " ", text)
            text = html.unescape(text)
            text = re.sub(r"[ \t\r\f\v]+", " ", text)
            text = re.sub(r"\n\s*\n+", "\n\n", text)
        return _clip(text.strip(), limit)


    @staticmethod
    def web_search(query: str, limit: int = 8, timeout: float = 20.0) -> str:
        """Search the web (DuckDuckGo's HTML endpoint, no API key) and return titles, links and snippets."""
        q = urllib.parse.quote_plus(query.strip())
        req = urllib.request.Request("https://html.duckduckgo.com/html/?q=" + q,
                                     headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) ForgeIDE/1.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            page = resp.read(1_500_000).decode("utf-8", errors="replace")
        results = []
        for m in re.finditer(r'<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>(.*?)(?=<a[^>]+class="result__a"|$)', page, re.S):
            href, title, rest = m.group(1), m.group(2), m.group(3)
            if "uddg=" in href:
                href = urllib.parse.unquote(href.split("uddg=", 1)[1].split("&", 1)[0])
            sm = re.search(r'class="result__snippet"[^>]*>(.*?)</a>', rest, re.S)
            snippet = html.unescape(re.sub(r"<[^>]+>", "", sm.group(1))) if sm else ""
            title = html.unescape(re.sub(r"<[^>]+>", "", title))
            results.append((title.strip(), href.strip(), re.sub(r"\s+", " ", snippet).strip()))
            if len(results) >= limit:
                break
        if not results:
            return f"No web results for '{query}'."
        out = [f"Web results for '{query}':"]
        for i, (t, u, sn) in enumerate(results, 1):
            out.append(f"{i}. {t}\n   {u}\n   {sn}")
        return "\n".join(out)


# ---------------------------------------------------------------------- tool schema
TOOL_SPECS: List[dict] = [
    {"name": "read_file", "description": "Read a file from the workspace with line numbers. Use start_line/end_line for big files.",
     "parameters": {"path": "string, relative path", "start_line": "integer, optional", "end_line": "integer, optional"}},
    {"name": "list_dir", "description": "List the files and folders inside a directory.",
     "parameters": {"path": "string, relative path ('.' for the workspace root)"}},
    {"name": "find_files", "description": "Find files by name or glob pattern (for example 'config', '*.py', 'src/**/*.ts').",
     "parameters": {"pattern": "string"}},
    {"name": "search", "description": "Search file contents for text (grep). Returns file:line:match. Use it to find where something is defined or used.",
     "parameters": {"pattern": "string, the text or regex to find", "path": "string, optional folder to search in", "glob": "string, optional file filter such as '*.py'", "regex": "boolean, optional, treat pattern as a regular expression"}},
    {"name": "write_file", "description": "Create a new file or completely overwrite an existing file with the given content. Parent folders are created.",
     "parameters": {"path": "string", "content": "string, the full file content"}},
    {"name": "edit_file", "description": "Make a precise edit: replace old_text (copied exactly from the file, including indentation) with new_text. old_text must be unique in the file unless replace_all is true.",
     "parameters": {"path": "string", "old_text": "string", "new_text": "string", "replace_all": "boolean, optional"}},
    {"name": "delete_path", "description": "Move a file or folder to the workspace trash folder (.forge-trash). Requires user approval.",
     "parameters": {"path": "string"}},
    {"name": "run_command", "description": "Run a shell command in the workspace (bash) and return its output. Use it to run tests, build, install packages, inspect git, or check versions. Non-interactive only; the user is asked for approval unless auto-run is on.",
     "parameters": {"command": "string", "timeout": "integer seconds, optional (default 90, max 600)", "cwd": "string, optional relative folder"}},
    {"name": "web_fetch", "description": "Fetch a web page or API URL and return its text (documentation, error explanations, package info).",
     "parameters": {"url": "string"}},
    {"name": "web_search", "description": "Search the web and get titles, links and snippets. Follow up with web_fetch on a promising link.",
     "parameters": {"query": "string"}},
    {"name": "codebase_search", "description": "Find the code chunks most related to a question or concept across the whole workspace (ranked keyword search). Good first step when you do not know where something lives.",
     "parameters": {"query": "string, a few descriptive words such as 'websocket terminal resize'"}},
    {"name": "ask_user", "description": "Stop and ask the user a question when a decision is genuinely theirs (requirements, which option to pick). Do not use it for things you can find out with the other tools.",
     "parameters": {"question": "string"}},
]

TOOL_NAMES = {t["name"] for t in TOOL_SPECS}
NEEDS_APPROVAL = {"run_command", "delete_path"}


def tool_specs_text() -> str:
    parts = []
    for t in TOOL_SPECS:
        params = ", ".join(f'"{k}": {v}' for k, v in t["parameters"].items())
        parts.append(f"- {t['name']}: {t['description']}\n  arguments: {{{params}}}")
    return "\n".join(parts)


def ollama_tool_schemas() -> list:
    """Schemas for Ollama's native tool-calling API (used when the user picks native mode)."""
    out = []
    type_map = {"string": "string", "integer": "integer", "boolean": "boolean"}
    for t in TOOL_SPECS:
        props = {}
        required = []
        for k, v in t["parameters"].items():
            typ = type_map.get(v.split(",")[0].split()[0], "string")
            props[k] = {"type": typ, "description": v}
            if "optional" not in v:
                required.append(k)
        out.append({"type": "function", "function": {"name": t["name"], "description": t["description"],
                                                     "parameters": {"type": "object", "properties": props, "required": required}}})
    return out
