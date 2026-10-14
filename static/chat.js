/* Forge IDE: AI panel (Agent / Ask / Plan), streaming, thinking, tools, approvals, sessions. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, $$, el, esc, get, post, toast } = F;

  const C = window.ForgeChat = {};
  let session = null;            // { id, title, ... }
  let transcript = [];           // persisted UI transcript
  let run = null;                // { id, es, msgEl, blocks, ... }
  let contexts = [];             // attached context chips
  let verbose = false;
  let mentionItems = [], mentionSel = 0, mentionStart = -1;

  const messagesEl = $('#messages');
  const promptEl = $('#prompt');

  // ------------------------------------------------------------------ markdown rendering
  marked.setOptions({ gfm: true, breaks: false });
  const renderer = new marked.Renderer();
  renderer.code = function (code, infostring) {
    const text = typeof code === 'object' ? code.text : code;
    const lang = (typeof code === 'object' ? code.lang : infostring) || '';
    const id = 'cb' + Math.random().toString(36).slice(2, 9);
    return '<pre data-lang="' + esc(lang) + '" data-id="' + id + '"><div class="pre-bar"><span>' + esc(lang || 'code') + '</span><div class="pre-actions"><button data-act="copy">Copy</button><button data-act="insert">Insert at cursor</button><button data-act="apply">Apply to file</button></div></div><code class="lang-' + esc(lang) + '">' + esc(text) + '</code></pre>';
  };
  function md(text) {
    const html = marked.parse(text || '', { renderer });
    return DOMPurify.sanitize(html, { ADD_ATTR: ['data-act', 'data-lang', 'data-id'] });
  }
  function colorize(root) {
    if (!F.monaco) return;
    for (const code of root.querySelectorAll('pre code')) {
      if (code.dataset.colored) continue;
      code.dataset.colored = '1';
      const lang = code.parentElement.dataset.lang;
      const langId = F.monaco.languages.getLanguages().some((l) => l.id === lang || (l.aliases || []).includes(lang)) ? lang : F.langFor('x.' + lang);
      F.monaco.editor.colorize(code.textContent, langId, { tabSize: 4 }).then((h) => { code.innerHTML = h; }).catch(() => {});
    }
  }
  messagesEl.addEventListener('click', (e) => {
    const b = e.target.closest('button[data-act]');
    if (!b) return;
    const pre = b.closest('pre');
    const code = pre.querySelector('code').textContent;
    const act = b.dataset.act;
    if (act === 'copy') { navigator.clipboard.writeText(code); b.textContent = 'Copied'; setTimeout(() => (b.textContent = 'Copy'), 1200); }
    else if (act === 'insert') { if (!F.editor.getModel()) return toast('Open a file first.'); F.editor.executeEdits('forge-chat', [{ range: F.editor.getSelection(), text: code, forceMoveMarkers: true }]); F.editor.focus(); }
    else if (act === 'apply') applyCodeToFile(code);
  });
  async function applyCodeToFile(code) {
    const f = F.activePath && F.files.get(F.activePath);
    if (!f) return toast('Open the target file first, then click Apply.');
    const accepted = await F.openDiff({ title: 'Apply to ' + f.path, original: f.model.getValue(), modified: code, language: f.language, acceptLabel: 'Apply' });
    if (accepted !== null) { f.model.setValue(accepted); toast('Applied to ' + f.path + '. Save with Ctrl+S.', 'ok'); }
  }

  // ------------------------------------------------------------------ transcript rendering
  function scrollToBottom(force) {
    const nearBottom = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < 120;
    if (force || nearBottom) messagesEl.scrollTop = messagesEl.scrollHeight;
  }
  function showEmpty(show) { $('#chat-empty').classList.toggle('hidden', !show); }

  function renderUser(turn) {
    const m = el('div', { class: 'msg user' });
    if (turn.attachments && turn.attachments.length) {
      const ctx = el('div', { class: 'ctx' });
      for (const a of turn.attachments) ctx.append(el('span', { class: 'cchip', text: a.kind === 'selection' ? a.path + ':' + a.start + '-' + a.end : (a.kind === 'terminal' ? 'terminal output' : a.path || a.title) }));
      m.append(ctx);
    }
    m.append(el('div', { class: 'bubble', text: turn.text }));
    return m;
  }

  function newAssistantEl() {
    const m = el('div', { class: 'msg assistant' });
    return m;
  }
  function thinkingBlock(text, live) {
    const d = el('details', { class: 'thinking' + (live ? ' live' : ''), open: live ? '' : null });
    const sum = el('summary');
    sum.innerHTML = '<svg class="chev" viewBox="0 0 16 16"><path d="M5 3l5 5-5 5"/></svg><span class="tl">' + (live ? 'Thinking…' : 'Thought process') + '</span>';
    const body = el('div', { class: 'tbody', text: text || '' });
    d.append(sum, body);
    d._body = body; d._label = sum.querySelector('.tl');
    return d;
  }
  function textBlock(text) {
    const d = el('div', { class: 'md' });
    d.innerHTML = md(text);
    colorize(d);
    return d;
  }
  const TOOL_ICONS = { read_file: '📄', write_file: '✎', edit_file: '✎', list_dir: '▤', find_files: '🔍', search: '🔍', run_command: '$', web_fetch: '🌐', delete_path: '🗑', ask_user: '?' };
  function argSummary(name, a) {
    a = a || {};
    if (name === 'run_command') return a.command || '';
    if (name === 'search') return (a.pattern || '') + (a.path && a.path !== '.' ? ' in ' + a.path : '');
    if (name === 'find_files') return a.pattern || '';
    if (name === 'web_fetch') return a.url || '';
    if (name === 'read_file') return (a.path || '') + (a.start_line ? ':' + a.start_line + '-' + (a.end_line || '') : '');
    return a.path || JSON.stringify(a);
  }
  function toolCard(t) {
    const card = el('div', { class: 'tool ' + (t.state || 'running') });
    const head = el('div', { class: 'thead', onclick: () => card.classList.toggle('open') },
      el('span', { class: 'ticon', text: TOOL_ICONS[t.name] || '⚙' }),
      el('span', { class: 'tname', text: labelFor(t.name) }),
      el('span', { class: 'targ', text: argSummary(t.name, t.arguments), title: argSummary(t.name, t.arguments) }),
      el('span', { class: 'tstate', text: t.state === 'ok' ? 'done' + (t.seconds != null ? ' · ' + t.seconds + 's' : '') : t.state === 'fail' ? 'failed' : 'running' }));
    const body = el('div', { class: 'tbody' });
    const args = Object.assign({}, t.arguments || {});
    const big = ['content', 'old_text', 'new_text'];
    const small = {};
    for (const k of Object.keys(args)) if (!big.includes(k)) small[k] = args[k];
    body.append(el('div', { class: 'sect', text: 'arguments' }), el('pre', { text: JSON.stringify(small, null, 1) }));
    for (const k of big) if (args[k] !== undefined) body.append(el('div', { class: 'sect', text: k }), el('pre', { text: String(args[k]) }));
    const res = el('pre', { class: 'tres', text: t.result || '' });
    body.append(el('div', { class: 'sect', text: 'result' }), res);
    if (t.name === 'read_file' || t.name === 'write_file' || t.name === 'edit_file') {
      body.append(el('div', { class: 'tactions' }, el('button', { class: 'btn ghost sm', text: 'Open file', onclick: () => F.openFile(args.path) })));
    }
    card.append(head, body);
    card._res = res; card._state = head.querySelector('.tstate');
    return card;
  }
  function labelFor(name) {
    return { read_file: 'Read file', write_file: 'Write file', edit_file: 'Edit file', list_dir: 'List folder', find_files: 'Find files', search: 'Search code', run_command: 'Run command', web_fetch: 'Fetch URL', delete_path: 'Move to trash', ask_user: 'Question' }[name] || name;
  }
  function fileChangeChip(ch) {
    return el('span', { class: 'filechange', title: 'Review diff', onclick: async () => {
      try {
        const r = await get('/api/changes', { run_id: ch.run_id });
        const c = r.changes.find((x) => x.path === ch.path);
        if (!c) return F.openFile(ch.path);
        await F.openDiff({ title: ch.path + ' (' + c.kind + ')', original: c.before || '', modified: c.after || '', language: F.langFor(ch.path), acceptLabel: 'Keep', rejectLabel: 'Revert',
          onReject: async () => { await post('/api/revert', { run_id: ch.run_id, path: ch.path }); F.refreshFile(ch.path); F.loadTree(); toast('Reverted ' + ch.path, 'ok'); } });
      } catch (e) { toast(e.message, 'error'); }
    } }, F.fileIcon(ch.path), el('span', { text: ch.path }), el('span', { class: 'badge ' + ch.kind, text: ch.kind }));
  }
  function statLine(s) {
    const parts = [];
    if (s.gen_tps) parts.push(s.gen_tps + ' tok/s');
    if (s.completion_tokens) parts.push(s.completion_tokens + ' tokens out');
    if (s.prompt_tokens) parts.push(s.prompt_tokens + ' in');
    if (s.first_token_s != null) parts.push('first token ' + s.first_token_s + 's');
    if (s.total_s) parts.push(s.total_s + 's total');
    const d = el('div', { class: 'statline' });
    for (const p of parts) d.append(el('span', { text: p }));
    return d;
  }

  /** Render a stored assistant turn (from the transcript). */
  function renderAssistant(turn) {
    const m = newAssistantEl();
    for (const b of turn.blocks || []) {
      if (b.type === 'thinking') m.append(thinkingBlock(b.text, false));
      else if (b.type === 'text') m.append(textBlock(b.text));
      else if (b.type === 'tool') m.append(toolCard(b));
      else if (b.type === 'approval') m.append(approvalCard(b, true));
      else if (b.type === 'ask') m.append(askCard(b.question));
      else if (b.type === 'status') m.append(el('div', { class: 'status-line' + (b.error ? ' error' : ''), text: b.text }));
      else if (b.type === 'files') { const w = el('div', { style: 'display:flex;flex-wrap:wrap;gap:4px' }); for (const ch of b.files) w.append(fileChangeChip(ch)); m.append(w); }
      else if (b.type === 'verbose' && verbose) m.append(el('div', { class: 'verbose', text: '[' + b.kind + '] ' + b.text }));
    }
    if (turn.stats) m.append(statLine(turn.stats));
    return m;
  }
  function askCard(q) { return el('div', { class: 'ask-card' }, el('div', { class: 'atitle', text: 'Forge asks' }), el('div', { text: q })); }
  function approvalCard(a, resolved) {
    const card = el('div', { class: 'approval' + (resolved ? ' resolved' : '') });
    card.append(el('div', { class: 'atitle', text: (a.tool === 'run_command' ? 'Run this command?' : a.tool === 'delete_path' ? 'Move to trash?' : 'Allow ' + a.tool + '?') + (a.reason ? ' · ' + a.reason : '') }));
    const cmd = a.tool === 'run_command' ? (a.arguments.command || '') : JSON.stringify(a.arguments);
    let ta = null;
    if (!resolved && a.tool === 'run_command') { ta = el('textarea', { spellcheck: 'false' }); ta.value = cmd; card.append(ta); }
    else card.append(el('pre', { text: cmd }));
    if (resolved) card.append(el('div', { class: 'muted small', text: a.decision === 'approved' ? 'Approved' : a.decision === 'denied' ? 'Denied' : '' }));
    else {
      const decide = async (decision) => {
        const args = ta && ta.value.trim() !== cmd ? { command: ta.value.trim() } : null;
        try { await post('/api/approve', { run_id: a.run_id, approval_id: a.approval_id, decision, arguments: args }); } catch (e) { toast(e.message, 'error'); }
        card.classList.add('resolved');
        card.querySelector('.aactions').replaceWith(el('div', { class: 'muted small', text: decision === 'deny' ? 'Denied' : 'Approved' }));
        a.decision = decision === 'deny' ? 'denied' : 'approved';
        if (ta) ta.replaceWith(el('pre', { text: ta.value }));
      };
      card.append(el('div', { class: 'aactions' },
        el('button', { class: 'btn sm', text: 'Run', onclick: () => decide('approve') }),
        el('button', { class: 'btn ghost sm', text: 'Always allow this chat', onclick: () => decide('always') }),
        el('button', { class: 'btn ghost sm', text: 'Skip', onclick: () => decide('deny') })));
      setTimeout(() => { const b = card.querySelector('.btn'); if (b) b.focus(); }, 50);
    }
    return card;
  }

  function renderTranscript() {
    for (const n of Array.from(messagesEl.children)) if (n.id !== 'chat-empty') n.remove();
    showEmpty(transcript.length === 0);
    for (const t of transcript) messagesEl.append(t.role === 'user' ? renderUser(t) : renderAssistant(t));
    scrollToBottom(true);
  }

  // ------------------------------------------------------------------ sessions
  C.sessionId = () => (session ? session.id : '');
  async function loadSession(id) {
    if (run) { toast('A run is still active in this chat.', 'error'); return; }
    try {
      session = await get('/api/session', { id });
      transcript = session.transcript || [];
      localStorage.setItem('forge.session', session.id);
      $('#chat-title-text').textContent = session.title || 'New chat';
      renderTranscript();
    } catch (e) { await newChat(); }
  }
  async function newChat() {
    if (run) stopRun();
    session = await post('/api/session/new', {});
    transcript = [];
    localStorage.setItem('forge.session', session.id);
    $('#chat-title-text').textContent = 'New chat';
    renderTranscript();
    contexts = []; renderContexts();
    promptEl.focus();
    if (F.ui.chat === false) F.toggle('chat');
    loadHistory($('#history'));
  }
  C.newChat = newChat;
  async function saveTranscript() {
    if (!session) return;
    try { await post('/api/session/transcript', { id: session.id, transcript, title: session.title }); } catch (e) { /* ignore */ }
  }
  async function loadHistory(host) {
    if (!host) return;
    try {
      const r = await get('/api/sessions');
      host.innerHTML = '';
      if (!r.sessions.length) host.append(el('div', { class: 'muted pad', text: 'No chats yet.' }));
      for (const s of r.sessions) {
        const row = el('div', { class: 'hist' + (session && s.id === session.id ? ' active' : ''), onclick: () => loadSession(s.id) },
          el('span', { class: 'ht', text: s.title, title: s.title + '\n' + (s.workspace || '') }),
          el('span', { class: 'hd', text: timeAgo(s.updated) }),
          el('span', { class: 'hx', text: '✕', title: 'Delete chat', onclick: async (e) => { e.stopPropagation(); if (!confirm('Delete chat "' + s.title + '"?')) return; await post('/api/session/delete', { id: s.id }); if (session && session.id === s.id) await newChat(); else loadHistory(host); } }));
        host.append(row);
      }
    } catch (e) { host.innerHTML = '<div class="muted pad">' + esc(e.message) + '</div>'; }
  }
  C.loadHistory = loadHistory;
  function timeAgo(ts) {
    const d = (Date.now() / 1000 - ts);
    if (d < 60) return 'now'; if (d < 3600) return Math.floor(d / 60) + 'm'; if (d < 86400) return Math.floor(d / 3600) + 'h'; return Math.floor(d / 86400) + 'd';
  }
  $('#btn-new-chat').onclick = newChat;
  $('#btn-new-chat-side').onclick = newChat;
  $('#btn-chat-history').onclick = () => F.showView('history');
  C.resetContext = async () => { if (!session) return; await post('/api/session/reset', { id: session.id }); toast('Model context reset for this chat; the transcript stays.', 'ok'); };
  C.prewarm = async () => { if (!session) return; await post('/api/prewarm', { session_id: session.id }); toast('Warming up the model in the background.', 'ok'); };

  // ------------------------------------------------------------------ context chips
  function renderContexts() {
    const host = $('#context-chips');
    host.innerHTML = '';
    contexts.forEach((c, i) => {
      const label = c.kind === 'selection' ? c.path + ':' + c.start + '-' + c.end : c.kind === 'terminal' ? 'Terminal output' : (c.path || c.title);
      host.append(el('span', { class: 'cchip', title: label }, c.kind === 'file' ? F.fileIcon(c.path) : el('span', { text: c.kind === 'selection' ? '⌶' : '▣' }), el('span', { class: 'n', text: label }), el('span', { class: 'x', text: '✕', onclick: () => { contexts.splice(i, 1); renderContexts(); } })));
    });
  }
  C.addFileContext = (path) => { if (!contexts.some((c) => c.kind === 'file' && c.path === path)) contexts.push({ kind: 'file', path }); renderContexts(); if (F.ui.chat === false) F.toggle('chat'); promptEl.focus(); };
  C.addSelectionAndFocus = () => {
    const ed = F.editor;
    if (ed && F.activePath) {
      const sel = ed.getSelection();
      const model = ed.getModel();
      if (sel && !sel.isEmpty()) {
        contexts.push({ kind: 'selection', path: F.activePath, start: sel.startLineNumber, end: sel.endLineNumber, text: model.getValueInRange(sel), language: model.getLanguageId() });
      } else C.addFileContext(F.activePath);
      renderContexts();
    }
    if (F.ui.chat === false) F.toggle('chat');
    promptEl.focus();
  };
  C.addTerminalContext = (text) => { contexts = contexts.filter((c) => c.kind !== 'terminal'); contexts.push({ kind: 'terminal', text }); renderContexts(); promptEl.focus(); };
  C.insertText = (t) => { promptEl.value += t; promptEl.focus(); };
  C.sendMessage = (text, mode, attach) => { if (attach) contexts.push(attach); if (mode) $('#mode-select').value = mode; promptEl.value = text; if (F.ui.chat === false) F.toggle('chat'); send(); };
  C.quickAsk = (text, mode) => { C.addSelectionAndFocus(); if (mode) $('#mode-select').value = mode; promptEl.value = text; send(); };
  $('#btn-add-selection').onclick = C.addSelectionAndFocus;
  $('#btn-add-context').onclick = () => { promptEl.focus(); promptEl.value += (promptEl.value && !promptEl.value.endsWith(' ') ? ' ' : '') + '@'; promptEl.dispatchEvent(new Event('input')); };
  for (const b of $$('.tip')) b.onclick = () => { promptEl.value = b.dataset.tip; if (F.activePath && /current file/.test(b.dataset.tip)) C.addFileContext(F.activePath); promptEl.focus(); };

  // @mentions
  promptEl.addEventListener('input', async () => {
    const v = promptEl.value, pos = promptEl.selectionStart;
    const at = v.lastIndexOf('@', pos - 1);
    if (at >= 0 && (at === 0 || /\s/.test(v[at - 1])) && !/\s/.test(v.slice(at + 1, pos))) {
      mentionStart = at;
      const q = v.slice(at + 1, pos);
      try {
        const r = await get('/api/files', { q });
        const special = [
          { label: 'codebase', hint: 'ranked search of the whole workspace for this message', kind: 'codebase' },
          { label: 'web', hint: 'web search results for this message', kind: 'web' },
          { label: 'git', hint: 'uncommitted git changes', kind: 'git' },
          { label: 'terminal', hint: 'recent output of the active terminal', kind: 'terminal' },
        ].filter((x) => !q || x.label.startsWith(q.toLowerCase()));
        const pads = (window.ForgeExtras ? window.ForgeExtras.notepads() : []).filter((n) => !q || n.name.toLowerCase().includes(q.toLowerCase())).map((n) => ({ label: 'notepad: ' + n.name, hint: n.text.slice(0, 60), kind: 'notepad', pad: n }));
        mentionItems = [...special, ...pads, ...r.files.slice(0, 10).map((f) => ({ label: f }))];
        if (F.activePath && !q) mentionItems.splice(special.length, 0, { label: F.activePath, hint: 'current file' });
        mentionSel = 0; renderMentions();
      } catch (e) { hideMentions(); }
    } else hideMentions();
    autoGrow();
  });
  function renderMentions() {
    const m = $('#mention-menu');
    m.innerHTML = '';
    if (!mentionItems.length) return hideMentions();
    m.classList.remove('hidden');
    mentionItems.forEach((it, i) => m.append(el('div', { class: 'mi' + (i === mentionSel ? ' sel' : ''), onmousedown: (e) => { e.preventDefault(); pickMention(i); } }, el('span', { text: it.label.split('/').pop() }), el('span', { class: 'd', text: it.hint || it.label }))));
  }
  function hideMentions() { $('#mention-menu').classList.add('hidden'); mentionItems = []; mentionStart = -1; }
  function pickMention(i) {
    const it = mentionItems[i];
    if (!it) return;
    const v = promptEl.value, pos = promptEl.selectionStart;
    promptEl.value = v.slice(0, mentionStart) + v.slice(pos);
    promptEl.selectionStart = promptEl.selectionEnd = mentionStart;
    if (it.kind === 'terminal') C.addTerminalContext(window.ForgeTerm ? window.ForgeTerm.activeOutput() : '');
    else if (it.kind === 'notepad') { contexts.push({ kind: 'text', title: 'Notepad: ' + it.pad.name, text: it.pad.text, path: 'notepad: ' + it.pad.name }); renderContexts(); }
    else if (it.kind) { if (!contexts.some((c) => c.kind === it.kind)) contexts.push({ kind: it.kind, path: '@' + it.kind }); renderContexts(); }
    else C.addFileContext(it.label);
    hideMentions();
  }
  function autoGrow() { promptEl.style.height = 'auto'; promptEl.style.height = Math.min(240, Math.max(64, promptEl.scrollHeight)) + 'px'; }
  promptEl.addEventListener('keydown', (e) => {
    if (mentionItems.length) {
      if (e.key === 'ArrowDown') { mentionSel = (mentionSel + 1) % mentionItems.length; renderMentions(); e.preventDefault(); return; }
      if (e.key === 'ArrowUp') { mentionSel = (mentionSel - 1 + mentionItems.length) % mentionItems.length; renderMentions(); e.preventDefault(); return; }
      if (e.key === 'Enter' || e.key === 'Tab') { pickMention(mentionSel); e.preventDefault(); return; }
      if (e.key === 'Escape') { hideMentions(); e.preventDefault(); return; }
    }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); }
    else if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && F.settings.enter_sends !== false && !e.isComposing) { e.preventDefault(); send(); }
    else if (e.key === 'Escape') { if (run) stopRun(); }
  });

  // ------------------------------------------------------------------ sending and streaming
  let initDone = null;   // resolves when the first session exists; send() waits for it
  async function send() {
    const text = promptEl.value.trim();
    if (!text || run) return;
    if (initDone) await initDone;
    if (!session) await newChat();
    const mode = $('#mode-select').value;
    const attachments = contexts.slice();
    promptEl.value = ''; autoGrow();
    contexts = []; renderContexts(); hideMentions();
    showEmpty(false);
    const userTurn = { role: 'user', text, attachments, ts: Date.now() };
    transcript.push(userTurn);
    messagesEl.append(renderUser(userTurn));
    if (session.title === 'New chat') { session.title = text.slice(0, 60); $('#chat-title-text').textContent = session.title; }
    const turn = { role: 'assistant', blocks: [], stats: null, mode, ts: Date.now() };
    transcript.push(turn);
    const msgEl = newAssistantEl();
    messagesEl.append(msgEl);
    const spinner = el('div', { class: 'status-line', text: 'Thinking…' });
    msgEl.append(spinner);
    scrollToBottom(true);
    let r;
    try { r = await post('/api/chat', { session_id: session.id, message: text, mode, attachments }); }
    catch (e) { spinner.textContent = 'Error: ' + e.message; spinner.classList.add('error'); turn.blocks.push({ type: 'status', text: 'Error: ' + e.message, error: true }); return; }
    run = { id: r.run_id, msgEl, turn, spinner, cur: null, curEl: null, tools: new Map(), files: [], filesEl: null, verboseEls: [] };
    setBusy(true);
    const es = new EventSource('/api/events?run_id=' + r.run_id);
    run.es = es;
    es.onmessage = (m) => { try { handleEvent(JSON.parse(m.data)); } catch (e) { console.error(e); } };
    es.onerror = () => { if (run && run.es === es) { setTimeout(() => { if (run && run.es === es) finishRun('disconnected'); }, 1500); } };
  }
  $('#btn-send').onclick = send;
  $('#btn-stop').onclick = () => stopRun();
  function setBusy(b) {
    $('#btn-send').classList.toggle('hidden', b);
    $('#btn-stop').classList.toggle('hidden', !b);
    $('#sb-run').textContent = b ? 'Agent working…' : '';
    $('#sb-run').classList.toggle('busy', b);
  }
  async function stopRun() {
    if (!run) return;
    try { await post('/api/cancel', { run_id: run.id }); } catch (e) { /* ignore */ }
  }
  function finishRun(status) {
    if (!run) return;
    if (run.es) run.es.close();
    if (run.spinner) run.spinner.remove();
    if (run.curEl && run.curEl.classList) { run.curEl.classList.remove('live', 'cursor-blink'); if (run.curEl._label) run.curEl._label.textContent = 'Thought process'; if (run.curEl.tagName === 'DETAILS') run.curEl.open = false; }
    run = null;
    setBusy(false);
    saveTranscript();
    loadHistory($('#history'));
    F.loadChanges && F.loadChanges();
    if (status === 'disconnected') toast('Connection to the Forge server was lost.', 'error');
  }

  const flushText = (() => {
    let pending = false;
    return () => {
      if (pending) return;
      pending = true;
      requestAnimationFrame(() => {
        pending = false;
        if (!run || !run.cur) return;
        if (run.cur.type === 'text') { run.curEl.innerHTML = md(run.cur.text); colorize(run.curEl); run.curEl.classList.add('cursor-blink'); }
        else if (run.cur.type === 'thinking') { run.curEl._body.textContent = run.cur.text; }
        scrollToBottom();
      });
    };
  })();

  function closeCurrent() {
    if (!run || !run.cur) return;
    if (run.cur.type === 'text') { run.curEl.innerHTML = md(run.cur.text); colorize(run.curEl); run.curEl.classList.remove('cursor-blink'); if (!run.cur.text.trim()) run.curEl.remove(); }
    if (run.cur.type === 'thinking') { run.curEl.classList.remove('live'); run.curEl._label.textContent = 'Thought process'; run.curEl.open = false; run.curEl._body.textContent = run.cur.text; }
    run.cur = null; run.curEl = null;
  }
  function startBlock(type) {
    closeCurrent();
    run.spinner.remove();
    const block = { type, text: '' };
    run.turn.blocks.push(block);
    run.cur = block;
    run.curEl = type === 'thinking' ? thinkingBlock('', true) : el('div', { class: 'md cursor-blink' });
    run.msgEl.append(run.curEl);
    scrollToBottom();
  }
  function logVerbose(kind, text) {
    const log = $('#agent-log');
    const line = el('div');
    line.innerHTML = '<span class="lt">' + new Date().toLocaleTimeString() + '</span> <span class="lk">' + esc(kind) + '</span> ' + esc(text);
    log.append(line);
    while (log.children.length > 2000) log.firstChild.remove();
    log.scrollTop = log.scrollHeight;
  }

  function handleEvent(ev) {
    if (!run) return;
    switch (ev.type) {
      case 'thinking_delta':
        if (!run.cur || run.cur.type !== 'thinking') startBlock('thinking');
        run.cur.text += ev.text; flushText(); break;
      case 'text_delta':
        if (!run.cur || run.cur.type !== 'text') startBlock('text');
        run.cur.text += ev.text; flushText(); break;
      case 'tool_call_start':
        closeCurrent(); break;
      case 'tool_call': {
        closeCurrent(); run.spinner.remove();
        const t = { type: 'tool', call_id: ev.call_id, name: ev.name, arguments: ev.arguments, state: 'running', result: '' };
        run.turn.blocks.push(t);
        const card = toolCard(t);
        run.tools.set(ev.call_id, { t, card });
        run.msgEl.append(card); scrollToBottom();
        logVerbose('tool', ev.name + ' ' + argSummary(ev.name, ev.arguments));
        break;
      }
      case 'tool_result': {
        const x = run.tools.get(ev.call_id);
        if (!x) break;
        x.t.state = ev.ok ? 'ok' : 'fail'; x.t.result = ev.result; x.t.seconds = ev.seconds;
        x.card.classList.remove('running'); x.card.classList.add(ev.ok ? 'ok' : 'fail');
        x.card._res.textContent = ev.result;
        x.card._state.textContent = ev.ok ? 'done' + (ev.seconds != null ? ' · ' + ev.seconds + 's' : '') : 'failed';
        if (!ev.ok) x.card.classList.add('open');
        if (window.ForgeExtras && (ev.name === 'write_file' || ev.name === 'edit_file')) window.ForgeExtras.reportCheck((x.t.arguments || {}).path || '', ev.result.includes('SYNTAX CHECK FAILED') ? ev.result.slice(ev.result.indexOf('SYNTAX CHECK FAILED')) : 'Syntax check: OK');
        if (ev.name === 'write_file' || ev.name === 'edit_file') { F.refreshFile(ev.arguments ? ev.arguments.path : (x.t.arguments || {}).path); F.loadTree(); }
        break;
      }
      case 'file_changed': {
        const ch = { path: ev.path, kind: ev.kind, run_id: ev.run_id };
        run.files.push(ch);
        let fb = run.turn.blocks.find((b) => b.type === 'files');
        if (!fb) { fb = { type: 'files', files: [] }; run.turn.blocks.push(fb); run.filesEl = el('div', { style: 'display:flex;flex-wrap:wrap;gap:4px' }); run.msgEl.append(run.filesEl); }
        if (!fb.files.some((f) => f.path === ch.path)) { fb.files.push(ch); run.filesEl.append(fileChangeChip(ch)); }
        else { const i = fb.files.findIndex((f) => f.path === ch.path); fb.files[i] = ch; }
        F.refreshFile(ev.path);
        break;
      }
      case 'approval_required': {
        closeCurrent();
        const a = { type: 'approval', run_id: ev.run_id, approval_id: ev.approval_id, tool: ev.tool, arguments: ev.arguments, reason: ev.reason, decision: null };
        run.turn.blocks.push(a);
        run.msgEl.append(approvalCard(a, false)); scrollToBottom(true);
        if (document.hidden && 'Notification' in window && Notification.permission === 'granted') new Notification('Forge needs your approval', { body: argSummary(ev.tool, ev.arguments) });
        break;
      }
      case 'approval_resolved': break;
      case 'ask_user': {
        closeCurrent();
        run.turn.blocks.push({ type: 'ask', question: ev.question });
        run.msgEl.append(askCard(ev.question)); promptEl.focus(); break;
      }
      case 'tool_error':
        run.turn.blocks.push({ type: 'status', text: ev.message, error: true });
        run.msgEl.append(el('div', { class: 'status-line error', text: ev.message })); break;
      case 'status':
        closeCurrent();
        run.turn.blocks.push({ type: 'status', text: ev.text });
        run.msgEl.append(el('div', { class: 'status-line', text: ev.text })); break;
      case 'stats': {
        run.turn.stats = ev;
        const parts = [];
        if (ev.gen_tps) parts.push(ev.gen_tps + ' tok/s');
        if (ev.context_estimate) parts.push('~' + ev.context_estimate + '/' + (F.settings.num_ctx || 8192) + ' ctx');
        $('#stats').textContent = parts.join(' · ');
        if (ev.gen_tps) $('#sb-tps').textContent = ev.gen_tps + ' tok/s';
        if (ev.context_estimate) { const pct = Math.round(100 * ev.context_estimate / (F.settings.num_ctx || 8192)); $('#sb-ctx').textContent = 'ctx ' + pct + '%'; $('#sb-ctx').style.color = pct > 85 ? 'var(--red)' : ''; }
        if (ev.prompt_tps || ev.gen_tps) logVerbose('stats', 'prompt ' + (ev.prompt_tokens || 0) + ' tok @ ' + (ev.prompt_tps || '?') + ' tok/s, gen ' + (ev.completion_tokens || 0) + ' tok @ ' + (ev.gen_tps || '?') + ' tok/s, total ' + ev.total_s + 's');
        break;
      }
      case 'verbose':
        logVerbose(ev.kind, ev.text);
        run.turn.blocks.push({ type: 'verbose', kind: ev.kind, text: ev.text });
        if (verbose) { closeCurrent(); run.msgEl.append(el('div', { class: 'verbose', text: '[' + ev.kind + '] ' + ev.text })); }
        break;
      case 'assistant_start': case 'assistant_end': break;
      case 'error':
        closeCurrent();
        run.turn.blocks.push({ type: 'status', text: 'Error: ' + ev.message, error: true });
        run.msgEl.append(el('div', { class: 'status-line error', text: 'Error: ' + ev.message + (ev.detail ? '\n' + ev.detail : '') }));
        logVerbose('error', ev.message + (ev.detail ? '\n' + ev.detail : ''));
        break;
      case 'turn_done': break;
      case 'end':
        closeCurrent();
        if (run.turn.stats) run.msgEl.append(statLine(run.turn.stats));
        finishRun(ev.status);
        break;
    }
  }

  // ------------------------------------------------------------------ verbose and settings
  C.toggleVerbose = () => { F.saveSetting('verbose', !F.settings.verbose); };
  $('#btn-verbose').onclick = C.toggleVerbose;
  C.onSettings = () => { verbose = !!F.settings.verbose; $('#btn-verbose').classList.toggle('on', verbose); };
  $('#mode-select').addEventListener('change', () => localStorage.setItem('forge.mode', $('#mode-select').value));

  // ------------------------------------------------------------------ init
  C.init = async () => {
    verbose = !!F.settings.verbose;
    $('#mode-select').value = localStorage.getItem('forge.mode') || 'agent';
    const sid = localStorage.getItem('forge.session');
    // If the user already sent a message before startup finished, keep that live session untouched.
    initDone = (async () => { if (session || run) return; if (sid) await loadSession(sid); else await newChat(); })();
    await initDone;
    if ('Notification' in window && Notification.permission === 'default') { try { Notification.requestPermission(); } catch (e) { /* ignore */ } }
    loadHistory($('#history'));
  };
})();
