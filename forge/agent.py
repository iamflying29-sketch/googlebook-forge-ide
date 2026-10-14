"""The agent loop: streams from Ollama, parses thinking / text / tool calls as they arrive, executes
tools (with user approval where needed), feeds results back, and emits UI events.
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import uuid
from typing import Any, Callable, Dict, List, Optional, Tuple

from . import prompts
from .ollama_client import OllamaClient, OllamaError
from .tools import NEEDS_APPROVAL, TOOL_NAMES, Workspace, is_dangerous_command, ollama_tool_schemas

READ_ONLY_TOOLS = {"read_file", "list_dir", "find_files", "search", "web_fetch", "web_search", "codebase_search", "ask_user"}
TAGS = ("<thinking>", "</thinking>", "<tool_call>", "</tool_call>")


# ---------------------------------------------------------------------- stream segmenter
def segment(text: str) -> Tuple[List[Dict[str, str]], bool]:
    """Split streamed model output into ordered segments of type text / thinking / tool_call.

    A trailing fragment that could still become a tag is held back (not emitted yet) so the UI never
    shows half a tag. Returns (segments, tool_call_closed).
    """
    segs: List[Dict[str, str]] = []
    mode = "text"
    i = 0
    n = len(text)
    buf = ""
    closed = False

    def flush(kind: str, s: str) -> None:
        if not s and kind == "text":
            return
        if segs and segs[-1]["type"] == kind and kind != "tool_call":
            segs[-1]["text"] += s
        else:
            segs.append({"type": kind, "text": s})

    while i < n:
        if text[i] == "<":
            rest = text[i:]
            matched = None
            for tag in TAGS:
                if rest.startswith(tag):
                    matched = tag
                    break
            if matched is None:
                # Possible partial tag at the very end: hold it back.
                if any(tag.startswith(rest) for tag in TAGS):
                    break
                buf += "<"
                i += 1
                continue
            flush(mode, buf)
            buf = ""
            if matched == "<thinking>":
                mode = "thinking"
            elif matched == "</thinking>":
                mode = "text"
            elif matched == "<tool_call>":
                if mode == "tool_call":
                    # A second opening tag before the first closed: keep the first call only.
                    i += len(matched)
                    continue
                mode = "tool_call"
                segs.append({"type": "tool_call", "text": ""})
            elif matched == "</tool_call>":
                closed = True
                mode = "text"
            i += len(matched)
            continue
        buf += text[i]
        i += 1
    if buf:
        if mode == "tool_call" and segs and segs[-1]["type"] == "tool_call":
            segs[-1]["text"] += buf
        else:
            flush(mode, buf)
    return segs, closed


# ---------------------------------------------------------------------- tool call parsing
def _extract_json_object(s: str) -> Tuple[Optional[str], int]:
    """Return the first balanced {...} object in s (string aware) and the index after it."""
    start = s.find("{")
    if start < 0:
        return None, -1
    depth = 0
    in_str = False
    esc = False
    for i in range(start, len(s)):
        c = s[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return s[start:i + 1], i + 1
    return s[start:], len(s)


def _repair_json(s: str) -> str:
    """Make common small-model JSON mistakes parseable: raw newlines/tabs inside strings, trailing
    commas, Python literals, single-quoted strings."""
    out = []
    in_str = False
    esc = False
    quote = '"'
    for c in s:
        if in_str:
            if esc:
                out.append(c)
                esc = False
            elif c == "\\":
                out.append(c)
                esc = True
            elif c == quote:
                in_str = False
                out.append('"')
            elif c == "\n":
                out.append("\\n")
            elif c == "\t":
                out.append("\\t")
            elif c == '"' and quote == "'":
                out.append('\\"')
            else:
                out.append(c)
        else:
            if c in ('"', "'"):
                in_str = True
                quote = c
                out.append('"')
            else:
                out.append(c)
    s2 = "".join(out)
    s2 = re.sub(r",\s*([}\]])", r"\1", s2)
    s2 = re.sub(r"\bTrue\b", "true", s2)
    s2 = re.sub(r"\bFalse\b", "false", s2)
    s2 = re.sub(r"\bNone\b", "null", s2)
    return s2


def _unwrap_block(s: str) -> str:
    s = s.strip("\n")
    # Strip a surrounding markdown fence if the model added one inside the block.
    m = re.match(r"^```[a-zA-Z0-9_+-]*\n(.*)\n```$", s, re.S)
    if m:
        s = m.group(1)
    return s


def parse_tool_call(raw: str) -> Tuple[Optional[dict], Optional[str]]:
    """Parse the text inside <tool_call>...</tool_call>. Returns (call, error)."""
    body = raw.strip()
    if not body:
        return None, "empty tool call"
    obj_text, end = _extract_json_object(body)
    call: Optional[dict] = None
    if obj_text:
        for candidate in (obj_text, _repair_json(obj_text)):
            try:
                v = json.loads(candidate)
                if isinstance(v, dict):
                    call = v
                    break
            except json.JSONDecodeError:
                continue
    if call is None:
        # Maybe the model wrote: read_file(path="x") or name: read_file
        m = re.match(r"^\s*([a-z_]+)\s*\((.*)\)\s*$", body, re.S)
        if m and m.group(1) in TOOL_NAMES:
            args = {}
            for k, v in re.findall(r"(\w+)\s*=\s*(\"(?:[^\"\\]|\\.)*\"|'(?:[^'\\]|\\.)*'|[^,]+)", m.group(2)):
                v = v.strip()
                if v[:1] in "\"'":
                    v = v[1:-1]
                args[k] = v
            call = {"name": m.group(1), "arguments": args}
        else:
            return None, "could not parse the tool call JSON"
    name = call.get("name") or call.get("tool") or call.get("function")
    if isinstance(name, dict):
        name = name.get("name")
    args = call.get("arguments")
    if args is None:
        args = call.get("parameters", call.get("args", call.get("input", {})))
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except json.JSONDecodeError:
            try:
                args = json.loads(_repair_json(args))
            except json.JSONDecodeError:
                args = {"value": args}
    if not isinstance(args, dict):
        args = {}
    # Tagged blocks after the JSON override / supply long string arguments.
    tail = body[end:] if end > 0 else ""
    for key in ("content", "old_text", "new_text", "command", "question", "pattern"):
        m = re.search(rf"<{key}>\n?(.*?)\n?</{key}>", tail, re.S)
        if m:
            args[key] = _unwrap_block(m.group(1))
        else:
            m2 = re.search(rf"<{key}>\n?(.*)$", tail, re.S)
            if m2 and key not in args:
                args[key] = _unwrap_block(m2.group(1))
    if not name or (name not in TOOL_NAMES and not str(name).startswith("mcp_")):
        return None, f"unknown tool '{name}'. Available tools: {', '.join(sorted(TOOL_NAMES))}"
    return {"name": name, "arguments": args}, None


def estimate_tokens(text: str) -> int:
    return int(len(text) / 3.3) + 1


# ---------------------------------------------------------------------- run state
class Run:
    """One user turn: everything the agent does until it gives a final answer or stops."""

    def __init__(self, session_id: str, mode: str):
        self.id = uuid.uuid4().hex[:12]
        self.session_id = session_id
        self.mode = mode
        self.events: List[dict] = []
        self.cond = threading.Condition()
        self.cancel = threading.Event()
        self.done = False
        self.status = "running"
        self.approval: Optional[dict] = None       # pending approval request
        self.approval_result: Optional[dict] = None
        self.approval_event = threading.Event()
        self.started = time.time()
        self.thread: Optional[threading.Thread] = None

    def emit(self, type_: str, **data: Any) -> None:
        ev = {"type": type_, "run_id": self.id, "t": round(time.time() - self.started, 3)}
        ev.update(data)
        with self.cond:
            self.events.append(ev)
            self.cond.notify_all()

    def finish(self, status: str = "done") -> None:
        with self.cond:
            self.done = True
            self.status = status
            self.cond.notify_all()

    def wait_events(self, since: int, timeout: float = 15.0) -> List[dict]:
        with self.cond:
            if len(self.events) <= since and not self.done:
                self.cond.wait(timeout)
            return self.events[since:]

    def request_approval(self, tool: str, args: dict, reason: str) -> dict:
        approval_id = uuid.uuid4().hex[:8]
        self.approval = {"id": approval_id, "tool": tool, "arguments": args, "reason": reason}
        self.approval_result = None
        self.approval_event.clear()
        self.emit("approval_required", approval_id=approval_id, tool=tool, arguments=args, reason=reason)
        deadline = time.monotonic() + 3600
        while time.monotonic() < deadline:
            if self.cancel.is_set():
                return {"decision": "deny", "note": "cancelled"}
            if self.approval_event.wait(0.25):
                break
        self.approval = None
        return self.approval_result or {"decision": "deny", "note": "timed out waiting for approval"}

    def resolve_approval(self, approval_id: str, decision: str, arguments: Optional[dict] = None) -> bool:
        if not self.approval or self.approval["id"] != approval_id:
            return False
        self.approval_result = {"decision": decision, "arguments": arguments}
        self.approval_event.set()
        return True


# ---------------------------------------------------------------------- the agent
class Agent:
    def __init__(self, client: OllamaClient, workspace: Workspace, settings: dict, session: dict):
        self.client = client
        self.ws = workspace
        self.settings = settings
        self.session = session          # persisted dict: messages, auto_approve, repo_map ...

    # ----------------------------------------------------------- helpers
    def _options(self) -> dict:
        s = self.settings
        opts = {
            "num_ctx": int(s.get("num_ctx", 8192)),
            "temperature": float(s.get("temperature", 0.2)),
            "top_p": float(s.get("top_p", 0.9)),
            "top_k": int(s.get("top_k", 40)),
            "repeat_penalty": float(s.get("repeat_penalty", 1.05)),
            "num_predict": int(s.get("num_predict", 3072)),
            "stop": ["</tool_call>", "<|eot_id|>", "<|start_header_id|>"],
        }
        if s.get("num_thread"):
            opts["num_thread"] = int(s["num_thread"])
        if s.get("seed") not in (None, "", 0):
            opts["seed"] = int(s["seed"])
        return opts

    def _repo_map(self) -> str:
        rm = self.session.get("repo_map")
        if not rm or time.time() - self.session.get("repo_map_ts", 0) > 900:
            rm = self.ws.repo_map(int(self.settings.get("repo_map_chars", 6000)))
            self.session["repo_map"] = rm
            self.session["repo_map_ts"] = time.time()
        return rm

    def _rules(self) -> str:
        """User rules: global (~/.forge-ide/rules.md) and per workspace (.forge/rules.md or .cursorrules)."""
        texts = []
        candidates = [os.path.expanduser("~/.forge-ide/rules.md"),
                      os.path.join(self.ws.root, ".forge", "rules.md"), os.path.join(self.ws.root, ".cursorrules"),
                      os.path.join(self.ws.root, "AGENTS.md"), os.path.join(self.ws.root, "CLAUDE.md")]
        for p in candidates:
            try:
                with open(p, "r", encoding="utf-8") as fh:
                    t = fh.read().strip()
            except OSError:
                continue
            if t:
                texts.append(f"### {os.path.basename(p)}\n{t[:6000]}")
        return "\n\n".join(texts)

    def _system_prompt(self, mode: str) -> str:
        thinking = bool(self.settings.get("thinking", True))
        if mode == "ask":
            base = prompts.build_ask_system(self._repo_map(), thinking)
        else:
            base = prompts.build_agent_system(self._repo_map(), thinking, plan_mode=(mode == "plan"))
        mcp_text = ""
        try:
            from .mcp import MCP
            mcp_text = MCP.get().specs_text()
        except Exception:  # noqa: BLE001
            mcp_text = ""
        if mcp_text and mode != "ask":
            base += "\n\n## Extra tools from MCP servers\n" + mcp_text
        rules = self._rules()
        if rules:
            base += "\n\n## User rules (always follow these)\n" + rules
        return base

    def _trim(self, messages: List[dict], run: Run) -> List[dict]:
        """Keep the conversation inside the context window. Old tool outputs go first, then old turns."""
        budget = int(self.settings.get("num_ctx", 8192)) - int(self.settings.get("num_predict", 3072)) // 2 - 512
        budget = max(budget, 2048)

        def total() -> int:
            return sum(estimate_tokens(m.get("content", "")) for m in messages)

        if total() <= budget:
            return messages
        trimmed = 0
        tool_idx = [i for i, m in enumerate(messages) if m.get("role") == "tool"]
        for i in tool_idx[:-4]:
            if total() <= budget:
                break
            if len(messages[i]["content"]) > 200:
                messages[i]["content"] = "[earlier tool output removed to save context]"
                trimmed += 1
        while total() > budget and len(messages) > 4:
            # messages[0] is the system prompt, [1] the first user message; drop the oldest after that.
            del messages[2]
            trimmed += 1
        if trimmed:
            run.emit("verbose", kind="trim", text=f"Context trimmed: {trimmed} old messages or tool outputs shortened "
                                                 f"(budget about {budget} tokens).")
        return messages

    # ----------------------------------------------------------- main entry
    def run_turn(self, run: Run, user_text: str, attachments: List[dict], mode: str) -> None:
        """Execute one user turn. Runs in its own thread; emits events on `run`."""
        try:
            self._run_turn(run, user_text, attachments, mode)
        except OllamaError as e:
            run.emit("error", message=str(e))
            run.finish("error")
        except Exception as e:  # noqa: BLE001
            import traceback
            run.emit("error", message=f"Internal error: {e}", detail=traceback.format_exc()[-3000:])
            run.finish("error")

    def _run_turn(self, run: Run, user_text: str, attachments: List[dict], mode: str) -> None:
        msgs: List[dict] = self.session.setdefault("messages", [])
        system = self._system_prompt(mode)
        if not msgs or msgs[0].get("role") != "system":
            msgs.insert(0, {"role": "system", "content": system})
        elif msgs[0]["content"] != system:
            msgs[0]["content"] = system
        content = self._compose_user_message(user_text, attachments)
        msgs.append({"role": "user", "content": content})
        max_iters = int(self.settings.get("max_iterations", 30))
        native = self.settings.get("tool_mode", "prompt") == "native" and mode != "ask"
        model = self.settings.get("model", "llama3.1")
        run.emit("verbose", kind="request", text=f"model={model} mode={mode} tool_mode={'native' if native else 'prompt'} "
                                                f"messages={len(msgs)} system_chars={len(system)} options={json.dumps(self._options())}")
        iterations = 0
        while True:
            if run.cancel.is_set():
                run.emit("status", text="Stopped by user.")
                run.finish("cancelled")
                return
            iterations += 1
            if iterations > max_iters:
                run.emit("status", text=f"Reached the limit of {max_iters} tool steps for one message. Send 'continue' to keep going.")
                run.finish("paused")
                return
            self._trim(msgs, run)
            assistant_text, tool_call, stats = self._stream_assistant(run, msgs, model, native)
            if run.cancel.is_set():
                msgs.append({"role": "assistant", "content": assistant_text})
                run.emit("status", text="Stopped by user.")
                run.finish("cancelled")
                return
            if stats:
                run.emit("stats", **stats)
            if tool_call is None:
                msgs.append({"role": "assistant", "content": assistant_text})
                run.emit("turn_done", iterations=iterations)
                run.finish("done")
                return
            if mode == "ask":
                msgs.append({"role": "assistant", "content": assistant_text})
                run.emit("turn_done", iterations=iterations)
                run.finish("done")
                return
            # Record the assistant message with the tool call so the model sees its own call.
            if native:
                msgs.append({"role": "assistant", "content": assistant_text or "", "tool_calls": [
                    {"function": {"name": tool_call["name"], "arguments": tool_call["arguments"]}}]})
            else:
                msgs.append({"role": "assistant", "content": assistant_text})
            result = self._execute_tool(run, tool_call, mode)
            if result is None:  # ask_user ends the turn
                run.emit("turn_done", iterations=iterations)
                run.finish("done")
                return
            msgs.append({"role": "tool", "content": result})

    def _compose_user_message(self, text: str, attachments: List[dict]) -> str:
        if not attachments:
            return text
        parts = [text, "\n\n## Attached context"]
        for a in attachments:
            kind = a.get("kind", "file")
            if kind == "selection":
                parts.append(f"\n### Selection from {a.get('path')} (lines {a.get('start')}-{a.get('end')})\n```{a.get('language','')}\n{a.get('text','')}\n```")
            elif kind == "file":
                try:
                    body = self.ws.read_text(a["path"])
                except Exception as e:  # noqa: BLE001
                    body = f"[could not read: {e}]"
                if len(body) > 40000:
                    body = body[:40000] + "\n... [file truncated in context; use read_file for the rest]"
                parts.append(f"\n### File {a['path']}\n```\n{body}\n```")
            elif kind == "text":
                parts.append(f"\n### {a.get('title','Note')}\n{a.get('text','')}")
            elif kind == "terminal":
                parts.append(f"\n### Terminal output\n```\n{a.get('text','')[-6000:]}\n```")
            elif kind == "codebase":
                from .codesearch import CodeIndex
                idx = getattr(self.ws, "code_index", None) or CodeIndex(self.ws.root)
                self.ws.code_index = idx
                parts.append("\n### Relevant code from the workspace (@codebase)\n" + idx.search_text(text, limit=6, max_chars=7000))
            elif kind == "web":
                try:
                    parts.append("\n### Web search results (@web)\n" + self.ws.web_search(text, limit=6))
                except Exception as e:  # noqa: BLE001
                    parts.append(f"\n### Web search results (@web)\n[search failed: {e}]")
            elif kind == "git":
                import subprocess
                try:
                    diff = subprocess.run(["git", "-C", self.ws.root, "diff", "--stat", "-p", "--no-color"], capture_output=True,
                                          text=True, timeout=20).stdout
                except (subprocess.TimeoutExpired, OSError) as e:
                    diff = f"[git diff failed: {e}]"
                parts.append("\n### Uncommitted git changes (@git)\n```diff\n" + (diff[:12000] or "(no changes)") + "\n```")
        return "\n".join(parts)

    # ----------------------------------------------------------- streaming one assistant message
    def _stream_assistant(self, run: Run, msgs: List[dict], model: str, native: bool) -> Tuple[str, Optional[dict], Optional[dict]]:
        msg_id = uuid.uuid4().hex[:8]
        run.emit("assistant_start", msg_id=msg_id)
        full = ""
        emitted: List[Tuple[str, int]] = []      # (type, chars emitted) per segment index
        native_calls: List[dict] = []
        stats: Optional[dict] = None
        last_obj: Optional[dict] = None
        tools = ollama_tool_schemas() if native else None
        options = self._options()
        if native:
            options = {k: v for k, v in options.items() if k != "stop"}
        t0 = time.monotonic()
        first_token: Optional[float] = None
        for obj in self.client.chat_stream(model, msgs, options=options, tools=tools, cancel=run.cancel,
                                           keep_alive=str(self.settings.get("keep_alive", "30m"))):
            last_obj = obj
            m = obj.get("message", {})
            if m.get("tool_calls"):
                for tc in m["tool_calls"]:
                    fn = tc.get("function", {})
                    native_calls.append({"name": fn.get("name"), "arguments": fn.get("arguments") or {}})
            delta = m.get("content", "")
            if delta:
                if first_token is None:
                    first_token = time.monotonic() - t0
                full += delta
                segs, closed = segment(full)
                for idx, seg in enumerate(segs):
                    if idx < len(emitted):
                        typ, n = emitted[idx]
                        if typ == seg["type"] and len(seg["text"]) > n:
                            if typ != "tool_call":
                                run.emit(f"{typ}_delta", msg_id=msg_id, text=seg["text"][n:])
                            emitted[idx] = (typ, len(seg["text"]))
                    else:
                        if seg["type"] != "tool_call" and seg["text"]:
                            run.emit(f"{seg['type']}_delta", msg_id=msg_id, text=seg["text"])
                        elif seg["type"] == "tool_call":
                            run.emit("tool_call_start", msg_id=msg_id)
                        emitted.append((seg["type"], len(seg["text"])))
                if closed:
                    break
            if obj.get("done"):
                break
        if last_obj and last_obj.get("done") or stats is None:
            o = last_obj or {}
            pe, ped = o.get("prompt_eval_count"), o.get("prompt_eval_duration")
            ec, ed = o.get("eval_count"), o.get("eval_duration")
            stats = {
                "prompt_tokens": pe, "completion_tokens": ec,
                "prompt_tps": round(pe / ped * 1e9, 1) if pe and ped else None,
                "gen_tps": round(ec / ed * 1e9, 2) if ec and ed else None,
                "load_s": round((o.get("load_duration") or 0) / 1e9, 2),
                "total_s": round((o.get("total_duration") or 0) / 1e9, 2),
                "first_token_s": round(first_token, 2) if first_token is not None else None,
                "done_reason": o.get("done_reason"),
                "context_estimate": sum(estimate_tokens(mm.get("content", "")) for mm in msgs) + estimate_tokens(full),
            }
        # Flush any held-back tail (a lone '<' that never became a tag).
        segs, closed = segment(full + "\u0000")
        full_final = full
        tool_call: Optional[dict] = None
        raw_call = None
        for idx, seg in enumerate(segs):
            seg_text = seg["text"].replace("\u0000", "")
            if seg["type"] == "tool_call":
                raw_call = seg_text
                continue
            if idx < len(emitted):
                typ, n = emitted[idx]
                if typ == seg["type"] and len(seg_text) > n and typ != "tool_call":
                    run.emit(f"{typ}_delta", msg_id=msg_id, text=seg_text[n:])
            elif seg_text and seg["type"] != "tool_call":
                run.emit(f"{seg['type']}_delta", msg_id=msg_id, text=seg_text)
        if native_calls:
            tool_call = native_calls[0]
            run.emit("verbose", kind="raw_tool_call", text=json.dumps(tool_call)[:4000])
        elif raw_call is not None:
            full_final = full + "</tool_call>" if not full.rstrip().endswith("</tool_call>") else full
            call, err = parse_tool_call(raw_call)
            run.emit("verbose", kind="raw_tool_call", text=raw_call[:4000])
            if call is None:
                # Let the model see its mistake and try again.
                run.emit("tool_error", message=f"Could not parse tool call: {err}")
                tool_call = {"name": "__parse_error__", "arguments": {"error": err or "unparseable"}}
            else:
                tool_call = call
        else:
            # Some models output the bare JSON {"name": ..., "parameters": ...} without tags.
            bare = self._detect_bare_json_call(full)
            if bare:
                tool_call = bare
                run.emit("verbose", kind="raw_tool_call", text="(bare JSON) " + json.dumps(bare)[:4000])
        run.emit("assistant_end", msg_id=msg_id)
        return full_final, tool_call, stats

    @staticmethod
    def _detect_bare_json_call(text: str) -> Optional[dict]:
        s = text.strip()
        m = re.search(r"\{\s*\"name\"\s*:\s*\"([a-z_]+)\"", s)
        if not m or m.group(1) not in TOOL_NAMES:
            return None
        obj_text, _ = _extract_json_object(s[m.start():])
        if not obj_text:
            return None
        call, err = parse_tool_call(obj_text)
        return call

    # ----------------------------------------------------------- executing tools
    def _execute_tool(self, run: Run, call: dict, mode: str) -> Optional[str]:
        name = call["name"]
        args = dict(call.get("arguments") or {})
        if name == "__parse_error__":
            return ("ERROR: your tool call could not be parsed (" + str(args.get("error")) + "). Write exactly one "
                    "<tool_call> block containing a JSON object with \"name\" and \"arguments\", and put long code in "
                    "<content>, <old_text> or <new_text> blocks.")
        call_id = uuid.uuid4().hex[:8]
        run.emit("tool_call", call_id=call_id, name=name, arguments=self._display_args(args))
        if mode == "plan" and name not in READ_ONLY_TOOLS:
            res = f"ERROR: '{name}' is not allowed in Plan mode. Only read-only tools may be used; finish by writing the plan."
            run.emit("tool_result", call_id=call_id, name=name, result=res, ok=False)
            return res
        if name == "ask_user":
            q = str(args.get("question") or args.get("value") or "").strip() or "The model asked a question but left it empty."
            run.emit("ask_user", call_id=call_id, question=q)
            run.emit("tool_result", call_id=call_id, name=name, result="(waiting for the user's answer)", ok=True)
            self.session["messages"].append({"role": "assistant", "content": f"Question for the user: {q}"})
            return None
        # Approval
        needs = name in NEEDS_APPROVAL or name.startswith("mcp_")
        if needs:
            auto = bool(self.settings.get("auto_approve_commands")) or bool(self.session.get("auto_approve"))
            dangerous = name == "delete_path" or (name == "run_command" and is_dangerous_command(str(args.get("command", ""))))
            if not auto or dangerous:
                reason = "This command looks destructive." if dangerous and name == "run_command" else (
                    "Moving a file to the trash." if name == "delete_path" else
                    "Tool from an MCP server." if name.startswith("mcp_") else "Commands run on your computer.")
                decision = run.request_approval(name, self._display_args(args), reason)
                d = decision.get("decision")
                if d == "always":
                    self.session["auto_approve"] = True
                if d in ("approve", "always"):
                    if decision.get("arguments"):
                        args.update(decision["arguments"])
                    run.emit("approval_resolved", call_id=call_id, decision="approved")
                else:
                    run.emit("approval_resolved", call_id=call_id, decision="denied")
                    res = "The user declined to run this tool call. Ask them how to proceed or choose a different approach."
                    run.emit("tool_result", call_id=call_id, name=name, result=res, ok=False)
                    return res
        t0 = time.monotonic()
        ok = True
        try:
            res = self._dispatch(run, name, args)
        except Exception as e:  # noqa: BLE001
            ok = False
            res = f"ERROR in {name}: {e}"
        dur = round(time.monotonic() - t0, 2)
        run.emit("tool_result", call_id=call_id, name=name, result=res, ok=ok, seconds=dur)
        if name in ("write_file", "edit_file", "delete_path") and ok:
            ch = self.ws.checkpoints.get(run.id, self.ws.relpath(self.ws.resolve(str(args.get("path", "")))))
            if ch:
                run.emit("file_changed", path=ch.path, kind=ch.kind, run_id=run.id)
        return res

    @staticmethod
    def _display_args(args: dict) -> dict:
        out = {}
        for k, v in args.items():
            if isinstance(v, str) and len(v) > 6000:
                out[k] = v[:6000] + f"\n... [{len(v) - 6000} more characters]"
            else:
                out[k] = v
        return out

    def _dispatch(self, run: Run, name: str, a: dict) -> str:
        ws = self.ws

        def s(key: str, default: Any = None) -> Any:
            v = a.get(key, default)
            return v if v is not None else default

        def need(key: str) -> str:
            v = a.get(key)
            if v is None or (isinstance(v, str) and v == "" and key != "new_text"):
                raise ValueError(f"missing required argument '{key}'")
            return v

        if name == "read_file":
            return ws.read_file(str(need("path")), _int(s("start_line")), _int(s("end_line")))
        if name == "list_dir":
            return ws.list_dir(str(s("path", ".")) or ".")
        if name == "find_files":
            return ws.find_files(str(need("pattern")))
        if name == "search":
            return ws.search(str(need("pattern")), str(s("path", ".")) or ".", s("glob"), bool(s("regex", False)))
        if name == "write_file":
            content = a.get("content")
            if content is None:
                raise ValueError("missing 'content'. Put the file text inside a <content>...</content> block after the JSON.")
            return ws.write_file(str(need("path")), str(content), run_id=run.id)
        if name == "edit_file":
            if "old_text" not in a:
                raise ValueError("missing 'old_text' (use an <old_text> block).")
            if "new_text" not in a:
                raise ValueError("missing 'new_text' (use a <new_text> block; it may be empty to delete the text).")
            return ws.edit_file(str(need("path")), str(a["old_text"]), str(a["new_text"]), run_id=run.id,
                                replace_all=bool(s("replace_all", False)))
        if name == "delete_path":
            return ws.delete_path(str(need("path")), run_id=run.id)
        if name == "run_command":
            out, _code = ws.run_command(str(need("command")), float(s("timeout", 90) or 90), s("cwd"))
            return out
        if name == "web_fetch":
            return ws.web_fetch(str(need("url")))
        if name == "web_search":
            return ws.web_search(str(need("query")))
        if name == "codebase_search":
            from .codesearch import CodeIndex
            idx = getattr(ws, "code_index", None)
            if idx is None:
                idx = ws.code_index = CodeIndex(ws.root)
            return idx.search_text(str(need("query")))
        if name.startswith("mcp_"):
            from .mcp import MCP
            return MCP.get().call_tool(name, a)
        raise ValueError(f"unknown tool {name}")


def _int(v: Any) -> Optional[int]:
    if v in (None, ""):
        return None
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


# ---------------------------------------------------------------------- inline edit and completion
def inline_edit_stream(client: OllamaClient, settings: dict, run: Run, path: str, language: str, selection: str,
                       instruction: str, before: str, after: str) -> None:
    """Ctrl+K: stream a rewritten version of the selection. Emits code_delta events and a final `code`."""
    try:
        ctx_before = before[-3000:]
        ctx_after = after[:1500]
        user = (f"File: {path}\nLanguage: {language}\n\nCode before the selection (for context only):\n```{language}\n{ctx_before}\n```\n\n"
                f"SELECTED CODE to rewrite:\n```{language}\n{selection}\n```\n\nCode after the selection (for context only):\n```{language}\n{ctx_after}\n```\n\n"
                f"Instruction: {instruction}\n\nReply with one fenced code block containing the replacement for the selected code only.")
        msgs = [{"role": "system", "content": prompts.INLINE_EDIT_SYSTEM}, {"role": "user", "content": user}]
        opts = {"num_ctx": int(settings.get("num_ctx", 8192)), "temperature": 0.1, "top_p": 0.9,
                "num_predict": int(settings.get("num_predict", 3072)), "stop": ["<|eot_id|>"]}
        if settings.get("num_thread"):
            opts["num_thread"] = int(settings["num_thread"])
        full = ""
        last = None
        for obj in client.chat_stream(settings.get("model", "llama3.1"), msgs, options=opts, cancel=run.cancel,
                                      keep_alive=str(settings.get("keep_alive", "30m"))):
            last = obj
            d = obj.get("message", {}).get("content", "")
            if d:
                full += d
                run.emit("code_delta", text=d)
            if obj.get("done"):
                break
        code = extract_code_block(full, selection)
        run.emit("code", text=code, raw=full)
        if last:
            ec, ed = last.get("eval_count"), last.get("eval_duration")
            run.emit("stats", gen_tps=round(ec / ed * 1e9, 2) if ec and ed else None, completion_tokens=ec,
                     prompt_tokens=last.get("prompt_eval_count"))
        run.finish("done")
    except Exception as e:  # noqa: BLE001
        run.emit("error", message=str(e))
        run.finish("error")


def extract_code_block(text: str, fallback: str = "") -> str:
    blocks = re.findall(r"```[a-zA-Z0-9_+#.-]*\n(.*?)```", text, re.S)
    if blocks:
        return max(blocks, key=len).rstrip("\n") if len(blocks) > 1 else blocks[0].rstrip("\n")
    m = re.search(r"```[a-zA-Z0-9_+#.-]*\n(.*)$", text, re.S)  # unterminated block
    if m:
        return m.group(1).rstrip("\n")
    t = text.strip()
    return t if t else fallback


def complete(client: OllamaClient, settings: dict, path: str, language: str, prefix: str, suffix: str) -> str:
    """Tab completion: a short insertion at the cursor."""
    prefix = prefix[-2500:]
    suffix = suffix[:600]
    user = (f"File: {path} ({language})\n\n<prefix>\n{prefix}\n</prefix>\n<suffix>\n{suffix}\n</suffix>\n\n"
            "Output only the text to insert at the cursor (between prefix and suffix).")
    opts = {"num_ctx": min(int(settings.get("num_ctx", 8192)), 4096), "temperature": 0.0, "num_predict": 64,
            "stop": ["<|eot_id|>", "\n\n\n", "</prefix>", "<suffix>"]}
    if settings.get("num_thread"):
        opts["num_thread"] = int(settings["num_thread"])
    r = client.generate(settings.get("model", "llama3.1"), user, options=opts, system=prompts.COMPLETION_SYSTEM,
                        timeout=60, keep_alive=str(settings.get("keep_alive", "30m")))
    out = r.get("response", "")
    out = re.sub(r"^```[a-zA-Z0-9_+#.-]*\n", "", out.strip("\n"))
    out = out.replace("```", "")
    # Drop a repeated prefix tail if the model echoed it.
    tail = prefix[-80:].strip()
    if tail and out.strip().startswith(tail):
        out = out.strip()[len(tail):]
    return out.rstrip()
