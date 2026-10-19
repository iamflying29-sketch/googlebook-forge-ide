"""Cursor-style chat features on the server: file attachments, memories, hooks, slash commands, @Docs,
@past chats and @folders context. Pure standard library."""
from __future__ import annotations

import hashlib
import html as htmlmod
import json
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional

from .codesearch import tokenize

TEXT_LIMIT = 200_000          # characters kept from an attached text or PDF file


def _load(path: str, default: Any) -> Any:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return default


def _save(path: str, data: Any) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


# ====================================================================== attachments (any file)
class Attachments:
    """Files attached to a chat with the attachment button. Images go to the image store; text, code and PDFs are
    kept as text the model can read; the original file is kept so the agent can open it by path."""

    def __init__(self, folder: str):
        self.folder = folder
        os.makedirs(folder, exist_ok=True)

    def save(self, data: bytes, name: str) -> dict:
        if len(data) > 25 * 1024 * 1024:
            raise ValueError("The file is larger than 25 MB.")
        name = os.path.basename(name or "file")[:120] or "file"
        att_id = hashlib.sha256(data).hexdigest()[:20]
        d = os.path.join(self.folder, att_id)
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, name)
        if not os.path.exists(path):
            with open(path, "wb") as fh:
                fh.write(data)
        kind, text = self._extract(path, data)
        meta = {"id": att_id, "name": name, "path": path, "bytes": len(data), "kind": kind,
                "chars": len(text or ""), "preview": (text or "")[:300]}
        if text is not None:
            with open(os.path.join(d, "text.txt"), "w", encoding="utf-8") as fh:
                fh.write(text)
        _save(os.path.join(d, "meta.json"), meta)
        return meta

    @staticmethod
    def _extract(path: str, data: bytes) -> tuple:
        if data[:5] == b"%PDF-":
            if not shutil.which("pdftotext"):
                return "binary", None
            try:
                r = subprocess.run(["pdftotext", "-layout", "-q", path, "-"], capture_output=True, timeout=60)
                return "pdf", r.stdout.decode("utf-8", errors="replace")[:TEXT_LIMIT]
            except (subprocess.TimeoutExpired, OSError):
                return "binary", None
        if b"\x00" in data[:4096]:
            return "binary", None
        try:
            return "text", data.decode("utf-8")[:TEXT_LIMIT]
        except UnicodeDecodeError:
            try:
                return "text", data.decode("latin-1")[:TEXT_LIMIT]
            except UnicodeDecodeError:
                return "binary", None

    def get(self, att_id: str) -> Optional[dict]:
        if not att_id or not all(c in "0123456789abcdef" for c in att_id):
            return None
        meta = _load(os.path.join(self.folder, att_id, "meta.json"), None)
        if not meta:
            return None
        try:
            with open(os.path.join(self.folder, att_id, "text.txt"), "r", encoding="utf-8") as fh:
                meta["text"] = fh.read()
        except OSError:
            meta["text"] = None
        return meta


# ====================================================================== memories
class Memories:
    """Durable facts and preferences the agent saves with approval (like Cursor's Memories). Global ones apply to
    every folder; project ones live in <folder>/.forge/memories.json."""

    def __init__(self, data_dir: str):
        self.global_path = os.path.join(data_dir, "memories.json")

    @staticmethod
    def project_path(root: str) -> str:
        return os.path.join(root, ".forge", "memories.json")

    def list(self, root: str) -> dict:
        return {"global": _load(self.global_path, []), "project": _load(self.project_path(root), [])}

    def add(self, root: str, text: str, scope: str = "project") -> dict:
        text = text.strip()
        if not text:
            raise ValueError("empty memory")
        path = self.global_path if scope == "global" else self.project_path(root)
        items = _load(path, [])
        item = {"id": hashlib.sha1((text + str(time.time())).encode()).hexdigest()[:10], "text": text[:1000],
                "created": time.time()}
        items.append(item)
        _save(path, items[-200:])
        return item

    def remove(self, root: str, mem_id: str) -> None:
        for path in (self.global_path, self.project_path(root)):
            items = _load(path, [])
            kept = [m for m in items if m.get("id") != mem_id]
            if len(kept) != len(items):
                _save(path, kept)

    def update(self, root: str, mem_id: str, text: str) -> None:
        for path in (self.global_path, self.project_path(root)):
            items = _load(path, [])
            for m in items:
                if m.get("id") == mem_id:
                    m["text"] = text.strip()[:1000]
                    _save(path, items)
                    return

    def prompt_text(self, root: str) -> str:
        m = self.list(root)
        lines = [f"- {x['text']}" for x in m["global"]] + [f"- {x['text']} (this project)" for x in m["project"]]
        return "\n".join(lines[-80:])


# ====================================================================== hooks
class Hooks:
    """Scripts that run on agent events (like Cursor's hooks): beforeSubmitPrompt, beforeShellExecution,
    afterFileEdit and stop. Configured in ~/.forge-ide/hooks.json and <folder>/.forge/hooks.json as
    {"beforeShellExecution": [{"command": "..."}], ...}. Each hook gets the event as JSON on stdin, has 10 seconds,
    and may answer {"permission": "allow" | "deny" | "ask", "message": "..."} on stdout. The environment also has
    FORGE_EVENT, FORGE_ROOT, FORGE_FILE (the edited file, as an absolute path) and FORGE_COMMAND."""

    EVENTS = ("beforeSubmitPrompt", "beforeShellExecution", "afterFileEdit", "stop")

    def __init__(self, data_dir: str):
        self.global_path = os.path.join(data_dir, "hooks.json")

    def config(self, root: str) -> Dict[str, list]:
        out: Dict[str, list] = {e: [] for e in self.EVENTS}
        for path in (self.global_path, os.path.join(root, ".forge", "hooks.json")):
            cfg = _load(path, {})
            if isinstance(cfg, dict):
                for e in self.EVENTS:
                    for h in cfg.get(e, []) or []:
                        if isinstance(h, dict) and h.get("command"):
                            out[e].append(h)
        return out

    def run(self, root: str, event: str, payload: dict) -> dict:
        """Run every hook for an event. The most restrictive answer wins: deny, then ask, then allow."""
        result = {"permission": "allow", "messages": []}
        rank = {"allow": 0, "ask": 1, "deny": 2}
        for h in self.config(root).get(event, []):
            try:
                env = dict(os.environ, FORGE_EVENT=event, FORGE_ROOT=root, FORGE_FILE=str(payload.get("path", "")),
                           FORGE_COMMAND=str(payload.get("command", "")))
                r = subprocess.run(["bash", "-c", str(h["command"])], input=json.dumps({"event": event, **payload}),
                                   capture_output=True, text=True, timeout=float(h.get("timeout", 10)), cwd=root, env=env)
            except (subprocess.TimeoutExpired, OSError) as e:
                result["messages"].append(f"hook '{h['command'][:60]}' failed: {e}")
                continue
            out = (r.stdout or "").strip()
            if not out:
                if r.returncode == 2:   # exit code 2 means deny, like Cursor and Claude Code hooks
                    result["permission"] = "deny"
                    result["messages"].append((r.stderr or "denied by a hook").strip()[:500])
                continue
            try:
                ans = json.loads(out.splitlines()[-1])
            except json.JSONDecodeError:
                continue
            p = str(ans.get("permission", "allow"))
            if p in rank and rank[p] > rank[result["permission"]]:
                result["permission"] = p
            if ans.get("message"):
                result["messages"].append(str(ans["message"])[:500])
        return result


# ====================================================================== slash commands
BUILTIN_COMMANDS = [
    {"name": "new", "description": "Start a new chat", "builtin": True},
    {"name": "reset", "description": "Clear the model's context for this chat (the transcript stays)", "builtin": True},
    {"name": "export", "description": "Save this chat as a Markdown file in Documents", "builtin": True},
    {"name": "plan", "description": "Switch to Plan mode", "builtin": True},
    {"name": "ask", "description": "Switch to Ask mode", "builtin": True},
    {"name": "agent", "description": "Switch to Agent mode", "builtin": True},
    {"name": "review", "description": "Review my uncommitted changes for bugs and risks",
     "prompt": "Review my uncommitted changes (attached as @git) like a careful senior engineer: list real bugs, risky "
               "changes and missing tests, most important first, with file and line. Do not edit anything."},
    {"name": "explain", "description": "Explain the current file or selection",
     "prompt": "Explain this code clearly: what it does, how it works, and anything surprising."},
    {"name": "tests", "description": "Write tests for the current file and run them",
     "prompt": "Write focused unit tests for the attached code, put them in the project's usual test location, run "
               "them, and fix any failures."},
    {"name": "fix-lints", "description": "Fix the linter errors of the current file",
     "prompt": "Fix every problem in the attached linter errors. Read the file first, make minimal edits, then check "
               "again."},
    {"name": "summarize", "description": "Summarize this chat so far",
     "prompt": "Summarize this conversation so far in a short list: what was asked, what was done, and what is left."},
]


def custom_commands(data_dir: str, root: str) -> List[dict]:
    """Markdown files in ~/.forge-ide/commands, <folder>/.forge/commands and <folder>/.cursor/commands."""
    out = []
    for d in (os.path.join(data_dir, "commands"), os.path.join(root, ".forge", "commands"),
              os.path.join(root, ".cursor", "commands")):
        try:
            names = sorted(n for n in os.listdir(d) if n.endswith(".md"))
        except OSError:
            continue
        for n in names:
            try:
                with open(os.path.join(d, n), "r", encoding="utf-8") as fh:
                    text = fh.read(20000)
            except OSError:
                continue
            first = next((l.strip("# ").strip() for l in text.splitlines() if l.strip()), "")
            out.append({"name": n[:-3], "description": first[:120], "prompt": text, "source": d})
    return out


# ====================================================================== @Docs
class Docs:
    """Documentation sites indexed for @Docs and the docs_search tool: a polite crawl of one site section, split
    into chunks and searched with BM25."""

    def __init__(self, data_dir: str):
        self.folder = os.path.join(data_dir, "docs")
        os.makedirs(self.folder, exist_ok=True)
        self.jobs: Dict[str, dict] = {}
        self._cache: Dict[str, dict] = {}

    @staticmethod
    def slug(name: str) -> str:
        return re.sub(r"[^a-z0-9_-]+", "-", name.lower()).strip("-")[:60] or "docs"

    def list(self) -> List[dict]:
        out = []
        for n in sorted(os.listdir(self.folder)):
            if n.endswith(".json"):
                d = _load(os.path.join(self.folder, n), {})
                out.append({"name": d.get("name", n[:-5]), "url": d.get("url"), "pages": len(d.get("pages", [])),
                            "chunks": len(d.get("chunks", [])), "updated": d.get("updated"), "status": "ready"})
        for name, job in self.jobs.items():
            if job.get("status") != "ready":
                out = [o for o in out if o["name"] != name] + [{"name": name, "url": job["url"], "pages": job.get("pages", 0),
                                                               "chunks": 0, "status": job["status"], "error": job.get("error")}]
        return out

    def remove(self, name: str) -> None:
        """Moves an index into ~/.forge-ide/trash/docs, so a removal can be undone by moving it back."""
        name = self.slug(name)
        self.jobs.pop(name, None)
        self._cache.pop(name, None)
        src = os.path.join(self.folder, name + ".json")
        if os.path.exists(src):
            trash = os.path.join(os.path.dirname(self.folder), "trash", "docs")
            os.makedirs(trash, exist_ok=True)
            os.replace(src, os.path.join(trash, f"{name}-{int(time.time())}.json"))

    def add(self, name: str, url: str, max_pages: int = 40) -> None:
        if not re.match(r"^https?://", url):
            raise ValueError("The documentation address must start with http:// or https://")
        name = self.slug(name or urllib.parse.urlparse(url).netloc)
        self.jobs[name] = {"url": url, "status": "crawling", "pages": 0}
        threading.Thread(target=self._crawl, args=(name, url, max_pages), name=f"docs-{name}", daemon=True).start()

    def _crawl(self, name: str, url: str, max_pages: int) -> None:
        job = self.jobs[name]
        base = urllib.parse.urlparse(url)
        prefix = base.path.rsplit("/", 1)[0] + "/" if not base.path.endswith("/") else base.path
        seen, queue, pages = set(), [(url, 0)], []
        deadline = time.time() + 240
        try:
            while queue and len(pages) < max_pages and time.time() < deadline:
                u, depth = queue.pop(0)
                u = u.split("#", 1)[0]
                if u in seen:
                    continue
                seen.add(u)
                try:
                    req = urllib.request.Request(u, headers={"User-Agent": "ForgeIDE-docs/1.0 (+local)"})
                    with urllib.request.urlopen(req, timeout=15) as resp:
                        if "html" not in resp.headers.get("Content-Type", "html"):
                            continue
                        raw = resp.read(3_000_000).decode("utf-8", errors="replace")
                except Exception:  # noqa: BLE001
                    continue
                title = htmlmod.unescape((re.search(r"(?is)<title>(.*?)</title>", raw) or [None, u])[1]).strip()[:160]
                for href in re.findall(r'(?i)href="([^"#]+)', raw):
                    nu = urllib.parse.urljoin(u, href)
                    p = urllib.parse.urlparse(nu)
                    if p.netloc == base.netloc and p.path.startswith(prefix) and depth < 2 and nu not in seen \
                            and not re.search(r"\.(png|jpe?g|gif|svg|zip|pdf|css|js|ico)$", p.path, re.I):
                        queue.append((p._replace(query="", fragment="").geturl(), depth + 1))
                text = re.sub(r"(?is)<(script|style|noscript|svg|nav|footer|header).*?</\1>", " ", raw)
                text = htmlmod.unescape(re.sub(r"(?s)<[^>]+>", " ", text))
                text = re.sub(r"[ \t\r\f\v]+", " ", text)
                text = re.sub(r"\n\s*\n+", "\n\n", text).strip()
                pages.append({"url": u, "title": title, "text": text[:60000]})
                job["pages"] = len(pages)
                time.sleep(0.3)   # be polite to the documentation site
            chunks = []
            for i, pg in enumerate(pages):
                t = pg["text"]
                for k in range(0, len(t), 1500):
                    chunks.append({"page": i, "text": t[k:k + 1800]})
            _save(os.path.join(self.folder, name + ".json"),
                  {"name": name, "url": url, "pages": [{"url": p["url"], "title": p["title"]} for p in pages],
                   "chunks": chunks, "updated": time.time()})
            self._cache.pop(name, None)
            job["status"] = "ready"
        except Exception as e:  # noqa: BLE001
            job["status"] = "error"
            job["error"] = str(e)

    def search(self, name: str, query: str, limit: int = 6, max_chars: int = 7000) -> str:
        import math
        name = self.slug(name)
        d = self._cache.get(name) or _load(os.path.join(self.folder, name + ".json"), None)
        if not d:
            return f"No documentation named '{name}' is indexed. Indexed: " + ", ".join(x["name"] for x in self.list())
        self._cache[name] = d
        q = tokenize(query)
        chunks = d.get("chunks", [])
        if "_tf" not in d:
            from collections import Counter
            d["_tf"] = [Counter(tokenize(c["text"])) for c in chunks]
            d["_df"] = Counter(t for tf in d["_tf"] for t in tf)
            d["_avg"] = (sum(sum(tf.values()) for tf in d["_tf"]) / max(1, len(chunks))) or 1.0
        n = len(chunks)
        scores = []
        for i, tf in enumerate(d["_tf"]):
            s = 0.0
            ln = sum(tf.values())
            for t in set(q):
                f = tf.get(t)
                if f:
                    idf = math.log(1 + (n - d["_df"][t] + 0.5) / (d["_df"][t] + 0.5))
                    s += idf * f * 2.5 / (f + 1.5 * (0.25 + 0.75 * ln / d["_avg"]))
            if s > 0:
                scores.append((s, i))
        scores.sort(reverse=True)
        if not scores:
            return f"Nothing in the '{name}' documentation matched '{query}'."
        out, used = [f"Top results from the '{name}' documentation for '{query}':"], 0
        for s, i in scores[:limit]:
            c = chunks[i]
            pg = d["pages"][c["page"]]
            block = f"\n--- {pg['title']} ({pg['url']}) ---\n{c['text']}"
            out.append(block[: max(0, max_chars - used)])
            used += len(block)
            if used >= max_chars:
                break
        return "\n".join(out)


# ====================================================================== @past chats and @folders
def chat_context(session: dict, max_chars: int = 8000) -> str:
    """The text of another chat: what was asked and answered, without tool output."""
    parts = [f"Chat '{session.get('title', '')}' (folder {session.get('workspace', '')}):"]
    for m in session.get("messages", []):
        role = m.get("role")
        if role not in ("user", "assistant"):
            continue
        text = re.sub(r"(?s)<thinking>.*?</thinking>", "", m.get("content", ""))
        text = re.sub(r"(?s)<(?:tool_call|forge_tool)>.*?(</(?:tool_call|forge_tool)>|$)", "[tool call]", text).strip()
        text = text.split("\n\n## Attached context")[0]
        if text:
            parts.append(f"{role.upper()}: {text[:2000]}")
    s = "\n\n".join(parts)
    return s[-max_chars:]


def folder_context(ws: Any, rel: str, query: str, max_chars: int = 9000) -> str:
    """A folder for @folders: its file list and the code chunks in it most related to the message."""
    root = ws.resolve(rel, must_exist=True)
    if not os.path.isdir(root):
        raise ValueError(f"{rel} is not a folder")
    files = ws._walk_files(root, max_files=400, seconds=3)
    out = [f"Folder {ws.relpath(root)} ({len(files)} files):"] + [f"- {f}" for f in files[:150]]
    if len(files) > 150:
        out.append(f"- ... {len(files) - 150} more")
    try:
        from .codesearch import CodeIndex
        idx = getattr(ws, "code_index", None) or CodeIndex(ws.root)
        ws.code_index = idx
        prefix = ws.relpath(root).rstrip("/") + "/"
        hits = [h for h in idx.search(query, limit=40) if root == ws.root or h[1].startswith(prefix)][:5]
        for score, path, a, b, text in hits:
            out.append(f"\n--- {path} (lines {a}-{b}) ---\n{text}")
    except Exception:  # noqa: BLE001
        pass
    return "\n".join(out)[:max_chars]
