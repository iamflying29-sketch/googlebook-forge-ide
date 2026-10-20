"""Agent Skills, the way Cursor has them: a skill is a folder with a SKILL.md file (YAML front matter, then
instructions) and any supporting files (scripts, references, templates) next to it.

Folders searched, in this order: the project's .cursor/skills, .agents/skills and .forge/skills (also in nested project
folders, which scopes those skills to that folder), then the global ~/.cursor/skills, ~/.agents/skills and
~/.forge-ide/skills, and, like Cursor, Claude's and Codex's folders (.claude/skills, .codex/skills, ~/.claude/skills,
~/.codex/skills) unless the setting skills_claude_codex is off. Category folders in between are allowed: the skill's
name is the name of the folder that holds SKILL.md (or the front matter's name).

Front matter fields: name, description (what the agent reads to decide when the skill fits), paths (globs: the agent's
list shows them, so it uses the skill for matching files), disable-model-invocation (true: only the user runs it, with
/name). A skill from a nested project folder is listed with that folder, for files in it.

The agent sees a short list of the skills it may use and reads a skill's SKILL.md when a task fits; the user runs one
with /name in the chat, which sends the whole SKILL.md with the message.
"""
import fnmatch
import os
import time
from typing import Dict, List, Optional

HOME = os.path.expanduser("~")
PROJECT_DIRS = [".cursor/skills", ".agents/skills", ".forge/skills"]
GLOBAL_DIRS = ["~/.cursor/skills", "~/.agents/skills", "~/.forge-ide/skills"]
COMPAT_PROJECT_DIRS = [".claude/skills", ".codex/skills"]
COMPAT_GLOBAL_DIRS = ["~/.claude/skills", "~/.codex/skills"]
SKIP = {".git", "node_modules", "build", "dist", "out", ".gradle", ".venv", "venv", "__pycache__", "target", ".idea"}

_cache: Dict[str, tuple] = {}


def _front_matter(text: str) -> Dict[str, object]:
    """A small YAML front matter reader: key: value lines, quoted strings, inline [a, b] lists and "- item" lists."""
    out: Dict[str, object] = {}
    if not text.startswith("---"):
        return out
    end = text.find("\n---", 3)
    if end < 0:
        return out
    key = None
    for raw in text[3:end].splitlines():
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.lstrip().startswith("- ") and key:
            out.setdefault(key, [])
            if isinstance(out[key], list):
                out[key].append(line.lstrip()[2:].strip().strip("\"'"))
            continue
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        key = k.strip()
        v = v.strip()
        if v.startswith("[") and v.endswith("]"):
            out[key] = [x.strip().strip("\"'") for x in v[1:-1].split(",") if x.strip()]
        elif v == "":
            out[key] = []
        else:
            if (v.startswith('"') and v.endswith('"')) or (v.startswith("'") and v.endswith("'")):
                v = v[1:-1]
            low = v.lower()
            out[key] = True if low == "true" else False if low == "false" else v
    return out


def _body(text: str) -> str:
    if text.startswith("---"):
        end = text.find("\n---", 3)
        if end >= 0:
            return text[end + 4:].lstrip("\n")
    return text


def _scan(base: str, scope: str, owner: str, out: List[dict], seen: set) -> None:
    """Every SKILL.md under base (category folders allowed, four levels deep at most)."""
    if not os.path.isdir(base):
        return
    for dirpath, dirnames, filenames in os.walk(base):
        depth = dirpath[len(base):].count(os.sep)
        if depth >= 4:
            dirnames[:] = []
        dirnames[:] = [d for d in dirnames if d not in SKIP and not d.startswith(".")]
        if "SKILL.md" not in filenames:
            continue
        path = os.path.join(dirpath, "SKILL.md")
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read(200000)
        except OSError:
            continue
        fm = _front_matter(text)
        name = str(fm.get("name") or os.path.basename(dirpath)).strip()
        if not name or name in seen:
            continue      # the first one found wins: project skills before global ones
        seen.add(name)
        paths = fm.get("paths") or []
        if isinstance(paths, str):
            paths = [p.strip() for p in paths.split(",") if p.strip()]
        manual = fm.get("disable-model-invocation") is True or str(fm.get("disable-model-invocation")).lower() == "true"
        files = []
        for f in sorted(os.listdir(dirpath))[:40]:
            if f != "SKILL.md":
                files.append(f + ("/" if os.path.isdir(os.path.join(dirpath, f)) else ""))
        out.append({"name": name, "description": str(fm.get("description") or "").strip(), "path": path, "dir": dirpath,
                    "scope": scope, "owner": owner, "source": base, "paths": list(paths), "manual_only": manual, "files": files})


def discover(root: str, include_compat: bool = True) -> List[dict]:
    """The skills of a project folder and the global ones (cached for 20 seconds)."""
    key = f"{root}|{include_compat}"
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < 20:
        return hit[1]
    out: List[dict] = []
    seen: set = set()
    proj_dirs = PROJECT_DIRS + (COMPAT_PROJECT_DIRS if include_compat else [])
    glob_dirs = GLOBAL_DIRS + (COMPAT_GLOBAL_DIRS if include_compat else [])
    for d in proj_dirs:
        _scan(os.path.join(root, d), "project", root, out, seen)
    # Nested project folders (a monorepo's apps/web/.cursor/skills): those skills apply to files in that folder.
    for dirpath, dirnames, _ in os.walk(root):
        depth = dirpath[len(root):].count(os.sep)
        if depth >= 4:
            dirnames[:] = []
        dirnames[:] = [d for d in dirnames if d not in SKIP and (not d.startswith(".") or d in (".cursor", ".agents", ".claude", ".codex", ".forge"))]
        if dirpath == root:
            continue
        for d in proj_dirs:
            base = os.path.join(dirpath, d)
            if os.path.isdir(base):
                _scan(base, "folder", dirpath, out, seen)
    for d in glob_dirs:
        _scan(os.path.expanduser(d), "global", HOME, out, seen)
    _cache[key] = (time.time(), out)
    return out


def find(root: str, name: str, include_compat: bool = True) -> Optional[dict]:
    for s in discover(root, include_compat):
        if s["name"] == name:
            return s
    return None


def applies(skill: dict, rel_paths: List[str]) -> bool:
    """A skill with paths, or one from a nested folder, applies only when the files at hand match."""
    globs = skill.get("paths") or []
    if skill.get("scope") == "folder":
        owner = skill["owner"]
        if not any(os.path.abspath(p).startswith(owner + os.sep) for p in rel_paths):
            return not rel_paths
    if not globs:
        return True
    return any(fnmatch.fnmatch(p, g) or fnmatch.fnmatch(os.path.basename(p), g) for p in rel_paths for g in globs) if rel_paths else True


def prompt_list(root: str, include_compat: bool = True, max_items: int = 40) -> str:
    """The short list the agent sees: name, a shortened description and the SKILL.md path, model-usable skills only."""
    lines = []
    for s in discover(root, include_compat):
        if s["manual_only"]:
            continue
        desc = " ".join(s["description"].split())
        if len(desc) > 110:
            desc = desc[:107] + "..."
        # Where a skill applies goes with it: the agent uses a scoped skill only for files that match.
        scope = []
        if s.get("paths"):
            scope.append("for files matching " + ", ".join(s["paths"][:6]))
        if s.get("scope") == "folder":
            scope.append("for files in " + os.path.relpath(s["owner"], root))
        lines.append(f"- {s['name']}: {desc}" + (f" ({'; '.join(scope)})" if scope else ""))
        if len(lines) >= max_items:
            break
    return "\n".join(lines)


def full_text(skill: dict, limit: int = 20000) -> str:
    """A skill as the model receives it when the user runs it with /name: its instructions and where its files are."""
    try:
        with open(skill["path"], "r", encoding="utf-8", errors="replace") as fh:
            body = _body(fh.read(200000))
    except OSError as e:
        return f"[the skill could not be read: {e}]"
    cut = len(body) > limit
    files = ", ".join(skill.get("files") or []) or "none"
    return (body[:limit] + ("\n[truncated; read the rest with read_file " + skill["path"] + "]" if cut else "")
            + f"\n\nThe skill's folder is {skill['dir']} (its other files: {files}); read or run them from there when the instructions say so.")
