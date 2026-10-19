/* Forge IDE: the AI panel, like Cursor's chat: tabs with several chats at once, Agent, Ask and Plan modes,
 * streaming thinking and tools, approvals, to-do lists, queued messages, Restore checkpoint, slash commands,
 * @ context (files, folders, code, docs, past chats, web, git, terminal, linter errors, notepads),
 * attachments of any file, images, a model picker and export. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, $$, el, esc, get, post, toast } = F;
  const C = window.ForgeChat = {};
  const promptEl = $('#prompt');
  const host = $('#messages');
  const tabs = [];
  let active = null;
  let verbose = false;
  let menu = { items: [], sel: 0, start: -1, kind: null };

  // ================================================================== markdown and code blocks
  marked.setOptions({ gfm: true, breaks: false });
  const renderer = new marked.Renderer();
  renderer.code = function (code, infostring) {
    const text = typeof code === 'object' ? code.text : code;
    const lang = (typeof code === 'object' ? code.lang : infostring) || '';
    return '<pre data-lang="' + esc(lang) + '"><div class="pre-bar"><span>' + esc(lang || 'code') + '</span><div class="pre-actions"><button data-act="copy">Copy</button><button data-act="insert">Insert at cursor</button><button data-act="apply">Apply to file</button></div></div><code class="lang-' + esc(lang) + '">' + esc(text) + '</code></pre>';
  };
  const md = (text) => DOMPurify.sanitize(marked.parse(text || '', { renderer }), { ADD_ATTR: ['data-act', 'data-lang'] });
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
  host.addEventListener('click', (e) => {
    const b = e.target.closest('button[data-act]');
    if (!b) return;
    const code = b.closest('pre').querySelector('code').textContent;
    if (b.dataset.act === 'copy') { navigator.clipboard.writeText(code); b.textContent = 'Copied'; setTimeout(() => (b.textContent = 'Copy'), 1200); }
    else if (b.dataset.act === 'insert') { if (!F.editor.getModel()) return toast('Open a file first.'); F.editor.executeEdits('forge-chat', [{ range: F.editor.getSelection(), text: code, forceMoveMarkers: true }]); F.editor.focus(); }
    else if (b.dataset.act === 'apply') applyCodeToFile(code);
  });
  async function applyCodeToFile(code) {
    const f = F.activePath && F.files.get(F.activePath);
    if (!f) return toast('Open the target file first, then click Apply.');
    const accepted = await F.openDiff({ title: 'Apply to ' + f.path, original: f.model.getValue(), modified: code, language: f.language, acceptLabel: 'Apply' });
    if (accepted !== null) { f.model.setValue(accepted); toast('Applied to ' + f.path + '. Save with Ctrl+S.', 'ok'); }
  }

  // ================================================================== building blocks of the transcript
  function thinkingBlock(text, live) {
    const d = el('details', { class: 'thinking' + (live ? ' live' : ''), open: live ? '' : null });
    const sum = el('summary');
    sum.innerHTML = '<svg class="chev" viewBox="0 0 16 16"><path d="M5 3l5 5-5 5"/></svg><span class="tl">' + (live ? 'Thinking…' : 'Thought process') + '</span>';
    const body = el('div', { class: 'tbody', text: text || '' });
    d.append(sum, body);
    d._body = body; d._label = sum.querySelector('.tl');
    return d;
  }
  function textBlock(text) { const d = el('div', { class: 'md' }); d.innerHTML = md(text); colorize(d); return d; }
  const TOOL_ICONS = { read_file: '📄', write_file: '✎', edit_file: '✎', list_dir: '▤', find_files: '🔍', search: '🔍', run_command: '$', web_fetch: '🌐', web_search: '🌐', codebase_search: '🔍', delete_path: '🗑', ask_user: '?', look_at_image: '👁', todo_write: '☑', remember: '🧠', docs_search: '📚' };
  const TOOL_LABELS = { read_file: 'Read file', write_file: 'Write file', edit_file: 'Edit file', list_dir: 'List folder', find_files: 'Find files', search: 'Search code', run_command: 'Run command', web_fetch: 'Fetch URL', web_search: 'Web search', codebase_search: 'Search codebase', delete_path: 'Move to trash', ask_user: 'Question', look_at_image: 'Look at image', todo_write: 'Update to-do list', remember: 'Save memory', docs_search: 'Search docs' };
  function argSummary(name, a) {
    a = a || {};
    if (name === 'run_command') return a.command || '';
    if (name === 'search') return (a.pattern || '') + (a.path && a.path !== '.' ? ' in ' + a.path : '');
    if (name === 'find_files') return a.pattern || '';
    if (name === 'web_fetch') return a.url || '';
    if (name === 'web_search' || name === 'codebase_search') return a.query || '';
    if (name === 'docs_search') return (a.name || '') + ': ' + (a.query || '');
    if (name === 'read_file') return (a.path || '') + (a.start_line ? ':' + a.start_line + '-' + (a.end_line || '') : '');
    if (name === 'look_at_image') return (a.question || '') + (a.image ? '  [' + a.image + ']' : '');
    if (name === 'remember') return a.memory || '';
    if (name === 'todo_write') return Array.isArray(a.todos) ? a.todos.length + ' items' : '';
    return a.path || JSON.stringify(a);
  }
  function toolCard(t) {
    const card = el('div', { class: 'tool ' + (t.state || 'running') });
    const head = el('div', { class: 'thead', onclick: () => card.classList.toggle('open') },
      el('span', { class: 'ticon', text: TOOL_ICONS[t.name] || '⚙' }), el('span', { class: 'tname', text: TOOL_LABELS[t.name] || t.name }),
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
    if (['read_file', 'write_file', 'edit_file'].includes(t.name)) body.append(el('div', { class: 'tactions' }, el('button', { class: 'btn ghost sm', text: 'Open file', onclick: () => F.openFile(args.path) })));
    card.append(head, body);
    card._res = res; card._state = head.querySelector('.tstate');
    return card;
  }
  function fileChangeChip(ch) {
    return el('span', { class: 'filechange', title: 'Review diff', 'data-path': ch.path, onclick: async () => {
      try {
        const r = await get('/api/changes', { run_id: ch.run_id });
        const c = r.changes.find((x) => x.path === ch.path);
        if (!c) return F.openFile(ch.path);
        await F.openDiff({ title: ch.path + ' (' + c.kind + ')', original: c.before || '', modified: c.after || '', language: F.langFor(ch.path), acceptLabel: 'Keep', rejectLabel: 'Revert',
          onReject: async () => { await post('/api/revert', { run_id: ch.run_id, path: ch.path }); F.refreshFile(ch.path); F.loadTree(); toast('Reverted ' + ch.path, 'ok'); } });
      } catch (e) { toast(e.message, 'error'); }
    } }, F.fileIcon(ch.path), el('span', { text: ch.path }), el('span', { class: 'badge ' + ch.kind, text: ch.kind }));
  }
  function filesRow(runId) {
    const chips = el('span', { class: 'files-row' });
    const btn = el('button', { class: 'btn ghost sm', text: 'Revert all', title: 'Undo every file change of this reply', onclick: async () => {
      if (!runId || !confirm('Undo every file change of this reply?')) return;
      try { const r = await post('/api/revert_all', { run_id: runId }); toast((r.messages || []).length + ' file(s) restored.', 'ok'); } catch (e) { toast(e.message, 'error'); }
      F.loadTree(); F.loadChanges && F.loadChanges();
      chips.querySelectorAll('.filechange').forEach((c) => { if (c.dataset.path) F.refreshFile(c.dataset.path); });
    } });
    const review = el('button', { class: 'btn ghost sm', text: 'Review in editor', title: 'Show these changes inline in the editor with Keep and Undo for each change', onclick: () => { if (window.ForgeReview) window.ForgeReview.reviewRun(runId); } });
    const row = el('div', { class: 'files-row' }, chips, review, btn);
    row._chips = chips;
    return row;
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
  function visionBlock(b) {
    const d = el('details', { class: 'vision' });
    d.append(el('summary', { text: '👁 What the vision model sees in image ' + b.number + (b.source && b.source !== 'unavailable' && b.source !== 'off' ? ' (' + b.source + ')' : '') }));
    const body = el('div', { class: 'vbody' });
    if (b.description) body.append(el('div', { class: 'vsect', text: 'Description' }), el('div', { text: b.description }));
    else body.append(el('div', { class: 'muted', text: 'The vision model was not available, so only the recognized text was used.' }));
    if (b.ocr) body.append(el('div', { class: 'vsect', text: 'Recognized text' }), el('pre', { text: b.ocr }));
    d.append(body);
    return d;
  }
  function todosBlock(b) {
    const d = el('div', { class: 'todos' });
    const done = b.todos.filter((t) => t.status === 'completed').length;
    d.append(el('div', { class: 'todos-head', text: 'To-do list · ' + done + ' of ' + b.todos.length + ' done' }));
    for (const t of b.todos) d.append(el('div', { class: 'todo ' + t.status }, el('span', { class: 'tick', text: t.status === 'completed' ? '✓' : t.status === 'in_progress' ? '▸' : '○' }), el('span', { text: t.content })));
    return d;
  }
  // ================================================================== models at work: Llama 3.1 and the vision model
  const MODEL_STATES = {
    llama: { loading: 'Loading into memory', waiting: 'Waiting for its GPU engine', resumed: 'GPU engine resumed', reading: 'Reading',
      thinking: 'Thinking', writing: 'Writing', tool: 'Calling a tool', done: 'Step finished', pausing: 'Lending the GPU',
      paused: 'Paused (GPU lent to the vision model)', resuming: 'Resuming on the GPU', failed: 'Stopped', idle: 'Idle' },
    vision: { starting: 'Starting', preparing: 'Copying its files', loading: 'Loading', looking: 'Looking at the image', answering: 'Answering',
      done: 'Answered', released: 'Finished', cached: 'Answer reused', failed: 'Failed', skipped: 'Skipped', unavailable: 'Not available',
      off: 'Turned off', idle: 'Idle' },
    ocr: { working: 'Reading the text', done: 'Finished', idle: 'Idle' },
  };
  const BUSY = new Set(['loading', 'reading', 'thinking', 'writing', 'tool', 'starting', 'preparing', 'looking', 'answering', 'working']);
  const WAITING = new Set(['waiting', 'pausing', 'paused', 'resuming']);
  const modelName = (m, label) => m === 'llama' ? 'Llama 3.1' : m === 'ocr' ? 'Text recognition' : ((label || '').replace(/\s*\(.*\)$/, '') || 'Vision model');
  const stateText = (m, st) => (MODEL_STATES[m] || {})[st] || st;
  function stateClass(st) { return BUSY.has(st) ? 'busy' : WAITING.has(st) ? 'wait' : (st === 'failed' || st === 'skipped' || st === 'unavailable') ? 'bad' : 'idle'; }

  /** Busy seconds per model, from the timeline. */
  function busyTimes(items, endT) {
    const out = {};
    const last = {};
    for (const it of items) {
      const prev = last[it.model];
      if (prev && (BUSY.has(prev.state) || WAITING.has(prev.state))) out[it.model] = (out[it.model] || 0) + (it.t - prev.t);
      last[it.model] = it;
    }
    for (const [m, it] of Object.entries(last)) if ((BUSY.has(it.state) || WAITING.has(it.state)) && endT != null) out[m] = (out[m] || 0) + Math.max(0, endT - it.t);
    return out;
  }
  function timelineSummary(b, endT) {
    const bt = busyTimes(b.items, endT);
    const names = {};
    for (const it of b.items) names[it.model] = modelName(it.model, it.label);
    const parts = Object.entries(bt).filter(([, v]) => v > 0.05).map(([m, v]) => names[m] + ' ' + (v < 10 ? v.toFixed(1) : Math.round(v)) + ' s');
    return 'How the models worked' + (parts.length ? ': ' + parts.join(' · ') : '') + ' (' + b.items.length + ' steps)';
  }
  function timelineItem(it) {
    return el('div', { class: 'tl-item ' + stateClass(it.state) }, el('span', { class: 'tl-t', text: '+' + it.t.toFixed(1) + ' s' }),
      el('span', { class: 'tl-head' }, el('span', { class: 'mbadge ' + it.model, text: modelName(it.model, it.label), title: it.label || '' }),
        el('span', { class: 'tl-state', text: stateText(it.model, it.state) })),
      el('span', { class: 'tl-t' }), el('span', { class: 'tl-text', text: it.detail || '' }));
  }
  function timelineBlock(b, live) {
    const d = el('details', { class: 'mtimeline' + (live ? ' live' : ''), open: (live || b.items.some((i) => i.model !== 'llama')) ? '' : null });
    const sum = el('summary', { text: timelineSummary(b, live ? null : b.end) });
    const list = el('div', { class: 'tl-list' });
    for (const it of b.items) list.append(timelineItem(it));
    d.append(sum, list);
    d._sum = sum; d._list = list;
    return d;
  }
  function exchangeCard(b) {
    const card = el('div', { class: 'xchg ' + (b.state || 'asking') });
    const asker = b.asker === 'llama' ? el('span', { class: 'mbadge llama', text: 'Llama 3.1' }) : el('span', { class: 'mbadge forge', text: 'Forge' });
    const vis = el('span', { class: 'mbadge vision', text: b.vision_label ? modelName('vision', b.vision_label) : 'the vision model', title: b.vision_label || '' });
    const head = el('div', { class: 'xhead' }, asker, el('span', { text: ' asks ' }), vis,
      el('span', { class: 'muted', text: b.asker === 'llama' ? ' (Llama cannot see images, so it asks for help)' : (' to describe image ' + (b.number || '') + ' for Llama 3.1, which cannot see images').replace('  ', ' ') }));
    const q = b.asker === 'llama' ? el('div', { class: 'xq', text: b.question })
      : el('details', { class: 'xq' }, el('summary', { text: 'Describe this image precisely for a programmer who cannot see it' }), el('div', { class: 'muted small', text: b.question }));
    const top = el('div', { class: 'xrow' }, q);
    if (b.image_url) top.prepend(el('img', { class: 'xthumb', src: b.image_url, alt: 'image', title: 'Click to enlarge', onclick: () => openLightbox(b.image_url) }));
    const status = el('div', { class: 'xstatus', text: b.status || (b.state === 'answered' ? '' : 'Waiting for the vision model…') });
    const ans = el('div', { class: 'xans', text: b.answer || '' });
    const ansHead = el('div', { class: 'xhead' }, el('span', { class: 'mbadge vision', text: b.vision_label ? modelName('vision', b.vision_label) : 'Vision model', title: b.vision_label || b.source || '' }),
      el('span', { text: b.state === 'answered' ? (b.answer ? ' answers' + (b.cached ? ' (saved answer from earlier)' : '') : ' could not answer') : ' is working' }),
      el('span', { class: 'muted', text: b.seconds != null ? '  ·  ' + b.seconds + ' s' : '' }));
    card.append(head, top, ansHead, status, ans);
    if (b.ocr) card.append(el('details', { class: 'xocr' }, el('summary', { text: 'Text recognition (tesseract) also read ' + b.ocr.length + ' characters' }), el('pre', { text: b.ocr })));
    if (b.error) card.append(el('div', { class: 'status-line error', text: b.error }));
    if (b.state === 'answered') card.append(el('div', { class: 'xfoot' }, el('span', { class: 'mbadge llama', text: 'Llama 3.1' }),
      el('span', { text: b.asker === 'llama' ? ' gets this answer back as the result of its look_at_image call and continues.'
        : (b.answer ? ' reads this description' : ' reads') + (b.ocr ? ' and the recognized text' : '') + ' together with your message.' })));
    card._status = status; card._ans = ans; card._ansHead = ansHead;
    return card;
  }
  function renderStrip() {
    const strip = $('#model-strip');
    if (!strip) return;
    const t = active;
    const show = t && t.models && (t.run || (t.modelsUntil && Date.now() < t.modelsUntil));
    strip.classList.toggle('hidden', !show);
    if (!show) return;
    strip.innerHTML = '';
    for (const m of ['llama', 'vision', 'ocr']) {
      const st = t.models[m];
      if (!st || (m === 'ocr' && st.state === 'idle')) continue;
      const secs = Math.max(0, Math.round((Date.now() - st.since) / 1000));
      const extra = m === 'llama' && st.state === 'writing' && st.tps ? ' · ' + st.tps + ' tok/s' : '';
      strip.append(el('div', { class: 'mpill ' + m + ' ' + stateClass(st.state), title: (st.label || '') + (st.detail ? '\n' + st.detail : '') },
        el('span', { class: 'mdot' }), el('b', { text: modelName(m, st.label) }),
        el('span', { class: 'mstate', text: stateText(m, st.state) + extra + (BUSY.has(st.state) || WAITING.has(st.state) ? ' · ' + secs + ' s' : '') })));
    }
    if (!t.models.vision) strip.append(el('div', { class: 'mpill vision idle', title: 'The vision model starts only when an image needs looking at' },
      el('span', { class: 'mdot' }), el('b', { text: 'Vision model' }), el('span', { class: 'mstate', text: 'Idle' })));
  }
  setInterval(() => { if (active && (active.run || (active.modelsUntil && Date.now() < active.modelsUntil + 1500))) renderStrip(); }, 1000);

  function openLightbox(url) { $('#lightbox-img').src = url; $('#lightbox').classList.remove('hidden'); }
  $('#lightbox').addEventListener('click', () => $('#lightbox').classList.add('hidden'));
  window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#lightbox').classList.contains('hidden')) $('#lightbox').classList.add('hidden'); });
  const askCard = (q) => el('div', { class: 'ask-card' }, el('div', { class: 'atitle', text: 'Forge asks' }), el('div', { text: q }));

  function approvalCard(a, resolved) {
    const card = el('div', { class: 'approval' + (resolved ? ' resolved' : '') });
    const isWrite = a.tool === 'write_file' || a.tool === 'edit_file';
    const title = a.tool === 'run_command' ? 'Run this command?' : a.tool === 'delete_path' ? 'Move to trash?' : a.tool === 'remember' ? 'Save this memory?' : isWrite ? 'Change a file outside this project?' : 'Allow ' + a.tool + '?';
    card.append(el('div', { class: 'atitle', text: title }));
    if (a.reason) card.append(el('div', { class: 'muted small', text: a.reason }));
    const args = a.arguments || {};
    const editable = a.tool === 'run_command' ? 'command' : a.tool === 'remember' ? 'memory' : null;
    const text = editable ? String(args[editable] || '') : isWrite ? (args.path + '\n\n' + (a.tool === 'write_file' ? 'New content:\n' + String(args.content || '').slice(0, 1500)
      : 'Replace:\n' + String(args.old_text || '').slice(0, 700) + '\n\nWith:\n' + String(args.new_text || '').slice(0, 700))) : JSON.stringify(args);
    let ta = null;
    if (!resolved && editable) { ta = el('textarea', { spellcheck: 'false' }); ta.value = text; card.append(ta); if (a.tool === 'remember') card.append(el('div', { class: 'muted small', text: 'Scope: ' + (args.scope === 'global' ? 'every folder' : 'this project') + '. You can edit it before saving.' })); }
    else card.append(el('pre', { text }));
    if (resolved) card.append(el('div', { class: 'muted small', text: a.decision === 'approved' ? 'Approved' : a.decision === 'denied' ? 'Denied' : '' }));
    else {
      const decide = async (decision) => {
        const changed = ta && ta.value.trim() !== text ? { [editable]: ta.value.trim() } : null;
        try { await post('/api/approve', { run_id: a.run_id, approval_id: a.approval_id, decision, arguments: changed }); } catch (e) { toast(e.message, 'error'); }
        card.classList.add('resolved');
        card.querySelector('.aactions').replaceWith(el('div', { class: 'muted small', text: decision === 'deny' ? 'Denied' : 'Approved' }));
        a.decision = decision === 'deny' ? 'denied' : 'approved';
        if (ta) ta.replaceWith(el('pre', { text: ta.value }));
      };
      const actions = el('div', { class: 'aactions' }, el('button', { class: 'btn sm', text: a.tool === 'run_command' ? 'Run' : a.tool === 'remember' ? 'Save' : 'Allow', onclick: () => decide('approve') }));
      if (a.tool !== 'remember') actions.append(el('button', { class: 'btn ghost sm', text: 'Always allow this chat', onclick: () => decide('always') }));
      actions.append(el('button', { class: 'btn ghost sm', text: a.tool === 'remember' ? 'Do not save' : 'Skip', onclick: () => decide('deny') }));
      card.append(actions);
      setTimeout(() => { const b = card.querySelector('.btn'); if (b) b.focus(); }, 50);
    }
    return card;
  }

  function contextLabel(a) {
    if (a.kind === 'selection') return a.path + ':' + a.start + '-' + a.end;
    if (a.kind === 'terminal') return 'Terminal output';
    if (a.kind === 'upload') return a.name;
    if (a.kind === 'docs') return 'Docs: ' + a.name;
    if (a.kind === 'chat') return 'Chat: ' + a.title;
    if (a.kind === 'folder') return 'Folder: ' + a.path;
    return a.path || a.title || a.kind;
  }
  function renderUser(tab, turn) {
    const m = el('div', { class: 'msg user' });
    const atts = turn.attachments || [];
    const imgs = atts.filter((a) => a.kind === 'image');
    const others = atts.filter((a) => a.kind !== 'image' && a.kind !== 'editor');
    if (imgs.length) {
      const wrap = el('div', { class: 'msg-images' });
      for (const a of imgs) wrap.append(el('img', { src: a.url, alt: a.name || 'image', title: a.name || 'Image', onclick: () => openLightbox(a.url) }));
      m.append(wrap);
    }
    if (others.length) { const ctx = el('div', { class: 'ctx' }); for (const a of others) ctx.append(el('span', { class: 'cchip', text: contextLabel(a) })); m.append(ctx); }
    m.append(el('div', { class: 'bubble', text: turn.text }));
    if (turn.run_id) m.append(el('button', { class: 'restore', text: '↺ Restore checkpoint', title: 'Undo the file changes of this message and everything after it, and continue the chat from here', onclick: () => restoreCheckpoint(tab, turn) }));
    return m;
  }
  function renderAssistant(turn) {
    const m = el('div', { class: 'msg assistant' });
    const todos = (turn.blocks || []).find((b) => b.type === 'todos');
    if (todos) m.append(todosBlock(todos));
    const tl = (turn.blocks || []).find((b) => b.type === 'timeline');
    if (tl) m.append(timelineBlock(tl, false));
    const hasExchange = (turn.blocks || []).some((b) => b.type === 'exchange');
    for (const b of turn.blocks || []) {
      if (b.type === 'thinking') m.append(thinkingBlock(b.text, false));
      else if (b.type === 'text') m.append(textBlock(b.text));
      else if (b.type === 'exchange') m.append(exchangeCard(b));
      else if (b.type === 'tool') { const c = toolCard(b); if (hasExchange && b.name === 'look_at_image') c.classList.add('as-exchange'); m.append(c); }
      else if (b.type === 'approval') m.append(approvalCard(b, true));
      else if (b.type === 'ask') m.append(askCard(b.question));
      else if (b.type === 'vision') m.append(visionBlock(b));
      else if (b.type === 'status') m.append(el('div', { class: 'status-line' + (b.error ? ' error' : ''), text: b.text }));
      else if (b.type === 'files') { const w = filesRow(b.files[0] && b.files[0].run_id); for (const ch of b.files) w._chips.append(fileChangeChip(ch)); m.append(w); }
      else if (b.type === 'verbose' && verbose) m.append(el('div', { class: 'verbose', text: '[' + b.kind + '] ' + b.text }));
    }
    if (turn.stats) m.append(statLine(turn.stats));
    return m;
  }

  // ================================================================== tabs (several chats at once)
  class Tab {
    constructor(session) {
      this.session = session; this.transcript = session.transcript || []; this.run = null;
      this.contexts = []; this.queue = []; this.scroll = 0; this.attention = false; this.draft = '';
      this.models = null; this.modelsUntil = 0;
      this.pane = el('div', { class: 'tab-pane hidden' });
      host.append(this.pane);
      this.render();
    }
    render() {
      this.pane.innerHTML = '';
      for (const t of this.transcript) this.pane.append(t.role === 'user' ? renderUser(this, t) : renderAssistant(t));
    }
  }
  const tabsKey = () => 'forge.tabs.' + F.folder;
  function saveTabs() { try { localStorage.setItem(tabsKey(), JSON.stringify({ ids: tabs.map((t) => t.session.id), active: active ? active.session.id : null })); } catch (e) { /* ignore */ } }
  function renderTabBar() {
    const bar = $('#chat-tabs');
    bar.innerHTML = '';
    for (const t of tabs) {
      const b = el('div', { class: 'ctab' + (t === active ? ' active' : '') + (t.run ? ' busy' : '') + (t.attention ? ' attention' : ''), title: t.session.title, onclick: () => switchTo(t) },
        el('span', { class: 'ct-dot' }), el('span', { class: 'ct-title', text: t.session.title || 'New chat' }),
        el('span', { class: 'ct-x', text: '✕', title: 'Close tab', onclick: (e) => { e.stopPropagation(); closeTab(t); } }));
      b.addEventListener('auxclick', (e) => { if (e.button === 1) closeTab(t); });
      bar.append(b);
    }
    const act = bar.querySelector('.ctab.active');
    if (act) act.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
  function switchTo(tab) {
    if (active === tab) return;
    if (active) { active.scroll = host.scrollTop; active.draft = promptEl.value; active.pane.classList.add('hidden'); }
    active = tab;
    tab.attention = false;
    tab.pane.classList.remove('hidden');
    promptEl.value = tab.draft || '';
    autoGrow();
    $('#chat-empty').classList.toggle('hidden', tab.transcript.length > 0);
    renderTabBar(); renderContexts(); renderQueue(); refreshComposer(); loadModelPicker(); renderStrip();
    host.scrollTop = tab.scroll || host.scrollHeight;
    saveTabs();
    loadHistory($('#history'));
  }
  function addTab(session, activate) {
    const existing = tabs.find((t) => t.session.id === session.id);
    if (existing) { if (activate) switchTo(existing); return existing; }
    const t = new Tab(session);
    tabs.push(t);
    if (activate || !active) switchTo(t); else renderTabBar();
    saveTabs();
    return t;
  }
  async function closeTab(tab) {
    if (tab.run && !confirm('This chat is still working. Stop it and close the tab?')) return;
    if (tab.run) await stopRun(tab);
    const i = tabs.indexOf(tab);
    tabs.splice(i, 1);
    tab.pane.remove();
    if (active === tab) { active = null; if (tabs.length) switchTo(tabs[Math.max(0, i - 1)]); else await newChat(); }
    renderTabBar(); saveTabs();
  }
  async function openChat(id) {
    const open = tabs.find((t) => t.session.id === id);
    if (open) return switchTo(open);
    const s = await get('/api/session', { id });
    if (s.workspace && s.workspace !== F.folder) { F.goToFolder(s.workspace, s.id); return; }
    addTab(s, true);
  }
  async function newChat() {
    const s = await post('/api/session/new', {});
    addTab(s, true);
    promptEl.focus();
    if (F.ui.chat === false) F.toggle('chat');
    loadHistory($('#history'));
  }
  C.newChat = newChat;
  C.nextTab = (dir) => { if (tabs.length < 2) return; const i = tabs.indexOf(active); switchTo(tabs[(i + dir + tabs.length) % tabs.length]); };
  C.closeActiveTab = () => { if (active) closeTab(active); };
  C.sessionId = () => (active ? active.session.id : '');
  async function saveTranscript(tab) {
    try { await post('/api/session/transcript', { id: tab.session.id, transcript: tab.transcript, title: tab.session.title }); } catch (e) { /* ignore */ }
  }

  // ================================================================== chat history (sidebar)
  async function loadHistory(hostEl) {
    if (!hostEl) return;
    const all = !!F.ui.chatsAll;
    const scopeBtn = $('#btn-chats-scope');
    if (scopeBtn) scopeBtn.textContent = all ? 'All folders' : 'This folder';
    try {
      const r = await get('/api/sessions', all ? { all: '1' } : null);
      hostEl.innerHTML = '';
      if (!r.sessions.length) { hostEl.append(el('div', { class: 'muted pad', text: all ? 'No chats yet.' : 'No chats in this folder yet.' })); return; }
      const groups = new Map();
      for (const s of r.sessions) { const k = s.workspace || ''; if (!groups.has(k)) groups.set(k, []); groups.get(k).push(s); }
      const keys = [...groups.keys()].sort((a, b) => (a === F.folder ? -1 : b === F.folder ? 1 : 0));
      for (const folder of keys) {
        if (all) hostEl.append(el('div', { class: 'hist-folder', title: folder }, el('span', { text: F.folderName(folder) + (folder === F.folder ? ' (this window)' : '') }), el('span', { class: 'hf-path', text: folder })));
        for (const s of groups.get(folder)) {
          const other = s.workspace && s.workspace !== F.folder;
          const isOpen = tabs.some((t) => t.session.id === s.id);
          hostEl.append(el('div', { class: 'hist' + (active && s.id === active.session.id ? ' active' : '') + (isOpen ? ' open' : ''), title: s.title + '\n' + (s.workspace || ''), onclick: () => (other ? F.goToFolder(s.workspace, s.id) : openChat(s.id)) },
            el('span', { class: 'ht', text: s.title }), el('span', { class: 'hd', text: timeAgo(s.updated) }),
            el('span', { class: 'hw', text: '↗', title: 'Open in a new window', onclick: (e) => { e.stopPropagation(); F.newWindow(s.workspace || F.folder, s.id); } }),
            el('span', { class: 'hx', text: '✕', title: 'Delete chat', onclick: async (e) => {
              e.stopPropagation(); if (!confirm('Delete chat "' + s.title + '"?')) return;
              const t = tabs.find((x) => x.session.id === s.id); if (t) await closeTab(t);
              await post('/api/session/delete', { id: s.id }); loadHistory(hostEl);
            } })));
        }
      }
    } catch (e) { hostEl.innerHTML = '<div class="muted pad">' + esc(e.message) + '</div>'; }
  }
  C.loadHistory = loadHistory;
  { const b = $('#btn-chats-scope'); if (b) b.onclick = () => { F.ui.chatsAll = !F.ui.chatsAll; try { localStorage.setItem('forge.ui', JSON.stringify(F.ui)); } catch (e) { /* ignore */ } loadHistory($('#history')); }; }
  function timeAgo(ts) { const d = Date.now() / 1000 - ts; if (d < 60) return 'now'; if (d < 3600) return Math.floor(d / 60) + 'm'; if (d < 86400) return Math.floor(d / 3600) + 'h'; return Math.floor(d / 86400) + 'd'; }
  $('#btn-new-chat').onclick = newChat;
  $('#btn-new-chat-side').onclick = newChat;
  $('#btn-chat-history').onclick = () => F.showView('history');
  C.resetContext = async () => { if (!active) return; await post('/api/session/reset', { id: active.session.id }); toast('Model context reset for this chat; the transcript stays.', 'ok'); };
  C.prewarm = async () => { if (!active) return; await post('/api/prewarm', { session_id: active.session.id }); toast('Warming up the model in the background.', 'ok'); };

  // ================================================================== export and Restore checkpoint
  function transcriptMarkdown(tab) {
    const out = ['# ' + (tab.session.title || 'Chat'), '', 'Folder: ' + F.folder, ''];
    for (const t of tab.transcript) {
      if (t.role === 'user') {
        out.push('## You', '', t.text);
        const shown = (t.attachments || []).filter((a) => a.kind !== 'editor'); if (shown.length) out.push('', 'Attached: ' + shown.map(contextLabel).join(', '));
        out.push('');
      } else {
        out.push('## Forge', '');
        for (const b of t.blocks || []) {
          if (b.type === 'text') out.push(b.text, '');
          else if (b.type === 'thinking') out.push('> Thought process: ' + b.text.replace(/\n/g, ' '), '');
          else if (b.type === 'tool') out.push('Tool: ' + (TOOL_LABELS[b.name] || b.name) + ' ' + argSummary(b.name, b.arguments) + (b.result ? '\n```\n' + String(b.result).slice(0, 1500) + '\n```' : ''), '');
          else if (b.type === 'todos') out.push('To-do list:', ...b.todos.map((x) => '- [' + (x.status === 'completed' ? 'x' : ' ') + '] ' + x.content), '');
          else if (b.type === 'status') out.push('_' + b.text + '_', '');
        }
      }
    }
    return out.join('\n');
  }
  C.exportChat = async () => {
    if (!active) return;
    try { const r = await post('/api/session/export', { id: active.session.id, markdown: transcriptMarkdown(active) }); toast('Saved to ' + r.path, 'ok', 6000); } catch (e) { toast(e.message, 'error'); }
  };
  async function restoreCheckpoint(tab, turn) {
    if (tab.run) return toast('Wait until this chat has finished, or stop it first.', 'error');
    if (!confirm('Restore to before this message? File changes from this message onward are undone, and the chat continues from here with your message back in the input box.')) return;
    try {
      const r = await post('/api/session/rewind', { id: tab.session.id, run_id: turn.run_id });
      const i = tab.transcript.indexOf(turn);
      tab.transcript = tab.transcript.slice(0, i);
      tab.render();
      await saveTranscript(tab);
      for (const m of r.messages || []) { const p = (m.match(/^(?:Restored|Removed) (\S+)/) || [])[1]; if (p) F.refreshFile(p); }
      F.loadTree(); F.loadChanges && F.loadChanges();
      if (tab === active) {
        promptEl.value = turn.text; autoGrow();
        tab.contexts = (turn.attachments || []).filter((a) => a.kind !== 'editor'); renderContexts();
        $('#chat-empty').classList.toggle('hidden', tab.transcript.length > 0);
      }
      toast('Restored. ' + (r.messages || []).length + ' file change(s) undone.', 'ok');
    } catch (e) { toast(e.message, 'error'); }
  }

  // ================================================================== context chips and attachments
  function renderContexts() {
    const hostEl = $('#context-chips');
    hostEl.innerHTML = '';
    if (!active) return;
    active.contexts.forEach((c, i) => {
      const remove = el('span', { class: 'x', text: '✕', onclick: () => { active.contexts.splice(i, 1); renderContexts(); } });
      if (c.kind === 'image') {
        hostEl.append(el('span', { class: 'cchip img' + (c.busy ? ' busy' : ''), title: (c.name || 'image') + (c.busy ? ' (uploading)' : '') },
          c.url ? el('img', { src: c.url, alt: '', onclick: () => openLightbox(c.url) }) : el('span', { text: '…' }), el('span', { class: 'n', text: c.name || 'image' }), remove));
        return;
      }
      const icon = c.kind === 'file' ? F.fileIcon(c.path) : c.kind === 'upload' ? F.fileIcon(c.name) : el('span', { text: { selection: '⌶', upload: '📎', docs: '📚', chat: '💬', folder: '📁', codebase: '🔍', web: '🌐', git: '⎇', terminal: '▣' }[c.kind] || '▣' });
      hostEl.append(el('span', { class: 'cchip' + (c.busy ? ' busy' : ''), title: contextLabel(c) + (c.bytes ? ' (' + Math.round(c.bytes / 1024) + ' KB)' : '') }, icon, el('span', { class: 'n', text: contextLabel(c) }), remove));
    });
  }
  const ctxs = () => (active ? active.contexts : []);
  C.addFileContext = (path) => { if (!ctxs().some((c) => c.kind === 'file' && c.path === path)) ctxs().push({ kind: 'file', path }); renderContexts(); if (F.ui.chat === false) F.toggle('chat'); promptEl.focus(); };
  C.addSelectionAndFocus = () => {
    const ed = F.editor;
    if (ed && F.activePath) {
      const sel = ed.getSelection(), model = ed.getModel();
      if (sel && !sel.isEmpty()) ctxs().push({ kind: 'selection', path: F.activePath, start: sel.startLineNumber, end: sel.endLineNumber, text: model.getValueInRange(sel), language: model.getLanguageId() });
      else C.addFileContext(F.activePath);
      renderContexts();
    }
    if (F.ui.chat === false) F.toggle('chat');
    promptEl.focus();
  };
  C.addTerminalContext = (text) => { if (!active) return; active.contexts = active.contexts.filter((c) => c.kind !== 'terminal'); active.contexts.push({ kind: 'terminal', text }); renderContexts(); promptEl.focus(); };
  C.addContext = (c) => { ctxs().push(c); renderContexts(); if (F.ui.chat === false) F.toggle('chat'); promptEl.focus(); };
  C.insertText = (t) => { promptEl.value += t; promptEl.focus(); promptEl.dispatchEvent(new Event('input')); };
  C.sendMessage = (text, mode, attach) => { if (attach) ctxs().push(attach); if (mode) $('#mode-select').value = mode; promptEl.value = text; if (F.ui.chat === false) F.toggle('chat'); send(); };
  C.quickAsk = (text, mode) => { C.addSelectionAndFocus(); if (mode) $('#mode-select').value = mode; promptEl.value = text; send(); };
  $('#btn-add-selection').onclick = C.addSelectionAndFocus;

  const readDataUrl = (file) => new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(r.result); r.onerror = () => rej(r.error); r.readAsDataURL(file); });
  async function addImageFile(file) {
    if (!file || !/^image\/(png|jpeg|gif|webp)$/.test(file.type)) return addAnyFile(file);
    if (file.size > 15 * 1024 * 1024) { toast('That image is larger than 15 MB.', 'error'); return; }
    const chip = { kind: 'image', name: file.name && file.name !== 'image.png' ? file.name : 'Screenshot', busy: true };
    ctxs().push(chip); renderContexts();
    try {
      const meta = await post('/api/image', { data: await readDataUrl(file), name: chip.name });
      Object.assign(chip, { id: meta.id, url: meta.url, width: meta.width, height: meta.height, busy: false });
    } catch (e) { ctxs().splice(ctxs().indexOf(chip), 1); toast('Could not attach the image: ' + e.message, 'error'); }
    renderContexts(); if (F.ui.chat === false) F.toggle('chat'); promptEl.focus();
  }
  /** The attachment button: any file. Images go to the vision helper; text, code and PDFs are read for the model. */
  async function addAnyFile(file) {
    if (!file) return;
    if (/^image\/(png|jpeg|gif|webp)$/.test(file.type)) return addImageFile(file);
    if (file.size > 25 * 1024 * 1024) { toast(file.name + ' is larger than 25 MB.', 'error'); return; }
    const chip = { kind: 'upload', name: file.name || 'file', busy: true, bytes: file.size };
    ctxs().push(chip); renderContexts();
    try {
      const meta = await post('/api/attachment', { data: await readDataUrl(file), name: chip.name });
      if (meta.kind === 'image') Object.assign(chip, { kind: 'image', id: meta.id, url: meta.url, busy: false });
      else Object.assign(chip, { id: meta.id, fileKind: meta.kind, busy: false });
      if (meta.kind === 'binary') toast(chip.name + ' cannot be read as text; the agent can still find it by path.', 'error', 6000);
    } catch (e) { ctxs().splice(ctxs().indexOf(chip), 1); toast('Could not attach ' + chip.name + ': ' + e.message, 'error'); }
    renderContexts(); if (F.ui.chat === false) F.toggle('chat'); promptEl.focus();
  }
  C.addImageFile = addImageFile; C.addAnyFile = addAnyFile;
  $('#btn-add-image').onclick = () => $('#image-input').click();
  $('#image-input').addEventListener('change', (e) => { for (const f of e.target.files) addImageFile(f); e.target.value = ''; });
  $('#btn-attach').onclick = () => $('#attach-input').click();
  $('#attach-input').addEventListener('change', (e) => { for (const f of e.target.files) addAnyFile(f); e.target.value = ''; });
  function filesFrom(dt) {
    const out = [];
    if (!dt) return out;
    for (const it of dt.items || []) if (it.kind === 'file') { const f = it.getAsFile(); if (f) out.push(f); }
    if (!out.length) for (const f of dt.files || []) out.push(f);
    return out;
  }
  promptEl.addEventListener('paste', (e) => { const files = filesFrom(e.clipboardData); if (files.length) { e.preventDefault(); for (const f of files) addAnyFile(f); } });
  let dragDepth = 0;
  const chatEl = $('#chat');
  chatEl.addEventListener('dragenter', (e) => { if ([...(e.dataTransfer.types || [])].includes('Files')) { dragDepth++; $('#drop-hint').classList.remove('hidden'); } });
  chatEl.addEventListener('dragleave', () => { dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) $('#drop-hint').classList.add('hidden'); });
  chatEl.addEventListener('dragover', (e) => { if ([...(e.dataTransfer.types || [])].includes('Files')) e.preventDefault(); });
  chatEl.addEventListener('drop', (e) => { dragDepth = 0; $('#drop-hint').classList.add('hidden'); const files = filesFrom(e.dataTransfer); if (files.length) { e.preventDefault(); for (const f of files) addAnyFile(f); } });
  $('#btn-add-context').onclick = () => { promptEl.focus(); promptEl.value += (promptEl.value && !promptEl.value.endsWith(' ') ? ' ' : '') + '@'; promptEl.dispatchEvent(new Event('input')); };
  for (const b of $$('.tip')) b.onclick = () => { promptEl.value = b.dataset.tip; if (F.activePath && /current file/.test(b.dataset.tip)) C.addFileContext(F.activePath); promptEl.focus(); };

  function lintText() {
    const lines = [];
    const all = window.ForgeLSP ? window.ForgeLSP.allDiagnostics() : new Map();
    const sev = ['', 'error', 'warning', 'info', 'hint'];
    for (const [path, diags] of all) for (const d of diags) lines.push(path + ':' + (d.range.start.line + 1) + ': ' + (sev[d.severity || 1] || 'error') + ': ' + d.message + (d.source ? ' (' + d.source + ')' : ''));
    const syn = window.ForgeExtras && window.ForgeExtras.problemTexts ? window.ForgeExtras.problemTexts() : [];
    return lines.concat(syn).join('\n');
  }
  function addLints() {
    const t = lintText();
    if (!t) { toast('There are no linter errors right now.', 'ok'); return false; }
    ctxs().push({ kind: 'text', title: 'Linter errors', text: t, path: 'Linter errors' }); renderContexts();
    return true;
  }

  // ================================================================== @ menu and / commands
  let cache = { at: 0, docs: [], chats: [], commands: [] };
  async function refreshCache() {
    if (Date.now() - cache.at < 10000) return;
    try { const [d, s, c] = await Promise.all([get('/api/docs'), get('/api/sessions'), get('/api/commands')]); cache = { at: Date.now(), docs: d.docs || [], chats: s.sessions || [], commands: c.commands || [] }; } catch (e) { /* keep */ }
  }
  const draftKey = () => 'forge.draft.' + (F.folder || '');
  promptEl.addEventListener('input', () => { try { if (promptEl.value.trim()) localStorage.setItem(draftKey(), promptEl.value); else localStorage.removeItem(draftKey()); } catch (e) { /* ignore */ } });
  promptEl.addEventListener('input', async () => {
    const v = promptEl.value, pos = promptEl.selectionStart;
    autoGrow();
    if (/^\/[\w-]*$/.test(v.slice(0, pos)) && !v.slice(0, pos).includes(' ')) {
      await refreshCache();
      const q = v.slice(1, pos).toLowerCase();
      menu = { kind: 'slash', start: 0, sel: 0, items: cache.commands.filter((c) => c.name.startsWith(q) || !q).map((c) => ({ label: '/' + c.name, hint: c.description, cmd: c })) };
      return renderMenu();
    }
    const at = v.lastIndexOf('@', pos - 1);
    if (at >= 0 && (at === 0 || /\s/.test(v[at - 1])) && !/\s/.test(v.slice(at + 1, pos))) {
      const q = v.slice(at + 1, pos).toLowerCase();
      await refreshCache();
      let files = [];
      try { files = (await get('/api/files', { q })).files || []; } catch (e) { /* offline */ }
      const folders = [...new Set(files.map((f) => f.includes('/') ? f.slice(0, f.lastIndexOf('/')) : '').filter(Boolean))].slice(0, 4);
      const items = [
        { label: 'codebase', hint: 'the most relevant code in this project', kind: 'codebase' },
        { label: 'web', hint: 'web search results for your message', kind: 'web' },
        { label: 'git', hint: 'your uncommitted changes', kind: 'git' },
        { label: 'terminal', hint: 'recent output of the active terminal', kind: 'terminal' },
        { label: 'linter errors', hint: 'errors and warnings from the language servers', kind: 'lints' },
        ...cache.docs.filter((d) => d.status === 'ready').map((d) => ({ label: 'docs: ' + d.name, hint: d.url, kind: 'docs', name: d.name })),
        { label: 'docs: add new documentation…', hint: 'index a documentation site for @Docs', kind: 'docs-add' },
        ...cache.chats.filter((s) => (!active || s.id !== active.session.id) && s.title !== 'New chat').slice(0, 8).map((s) => ({ label: 'chat: ' + s.title, hint: 'an earlier chat in this folder', kind: 'chat', id: s.id, title: s.title })),
        ...(window.ForgeExtras ? window.ForgeExtras.notepads() : []).map((n) => ({ label: 'notepad: ' + n.name, hint: n.text.slice(0, 60), kind: 'notepad', pad: n })),
        ...folders.map((f) => ({ label: 'folder: ' + f, hint: 'files and relevant code in this folder', kind: 'folder', path: f })),
      ].filter((x) => !q || x.label.toLowerCase().includes(q));
      if (F.activePath && !q) items.unshift({ label: F.activePath, hint: 'current file' });
      menu = { kind: 'at', start: at, sel: 0, items: [...items, ...files.slice(0, 10).map((f) => ({ label: f }))].slice(0, 40) };
      return renderMenu();
    }
    hideMenu();
  });
  function renderMenu() {
    const m = $('#mention-menu');
    m.innerHTML = '';
    if (!menu.items.length) return hideMenu();
    m.classList.remove('hidden');
    menu.items.forEach((it, i) => m.append(el('div', { class: 'mi' + (i === menu.sel ? ' sel' : ''), onmousedown: (e) => { e.preventDefault(); pickMenu(i); } },
      el('span', { text: it.kind || it.cmd ? it.label : it.label.split('/').pop() }), el('span', { class: 'd', text: it.hint || it.label }))));
    const sel = m.querySelector('.mi.sel'); if (sel) sel.scrollIntoView({ block: 'nearest' });
  }
  function hideMenu() { $('#mention-menu').classList.add('hidden'); menu = { items: [], sel: 0, start: -1, kind: null }; }
  async function pickMenu(i) {
    const it = menu.items[i];
    if (!it) return;
    if (menu.kind === 'slash') { hideMenu(); promptEl.value = ''; return runCommand(it.cmd, ''); }
    const v = promptEl.value, pos = promptEl.selectionStart;
    promptEl.value = v.slice(0, menu.start) + v.slice(pos);
    promptEl.selectionStart = promptEl.selectionEnd = menu.start;
    hideMenu();
    if (it.kind === 'terminal') return C.addTerminalContext(window.ForgeTerm ? window.ForgeTerm.activeOutput() : '');
    if (it.kind === 'notepad') { ctxs().push({ kind: 'text', title: 'Notepad: ' + it.pad.name, text: it.pad.text, path: 'notepad: ' + it.pad.name }); return renderContexts(); }
    if (it.kind === 'lints') return addLints();
    if (it.kind === 'docs') { ctxs().push({ kind: 'docs', name: it.name }); return renderContexts(); }
    if (it.kind === 'docs-add') return addDocsDialog();
    if (it.kind === 'chat') { ctxs().push({ kind: 'chat', id: it.id, title: it.title }); return renderContexts(); }
    if (it.kind === 'folder') { ctxs().push({ kind: 'folder', path: it.path }); return renderContexts(); }
    if (it.kind) { if (!ctxs().some((c) => c.kind === it.kind)) ctxs().push({ kind: it.kind, path: '@' + it.kind }); return renderContexts(); }
    C.addFileContext(it.label);
  }
  async function addDocsDialog() {
    const url = prompt('Address of the documentation to index for @Docs (for example https://docs.python.org/3/library/):');
    if (!url) return;
    const name = prompt('A short name for it:', new URL(url).hostname.split('.').slice(-2, -1)[0] || 'docs');
    if (!name) return;
    try { await post('/api/docs', { action: 'add', name, url }); cache.at = 0; toast('Indexing ' + name + ' in the background. It appears under @docs when ready.', 'ok', 6000); } catch (e) { toast(e.message, 'error'); }
  }
  C.addDocsDialog = addDocsDialog;
  /** Slash commands: built-in actions run at once; prompt commands fill the input so you can add details. */
  async function runCommand(cmd, rest) {
    const n = cmd.name;
    if (n === 'new') return newChat();
    if (n === 'reset') return C.resetContext();
    if (n === 'export') return C.exportChat();
    if (n === 'plan' || n === 'ask' || n === 'agent') { $('#mode-select').value = n; localStorage.setItem('forge.mode', n); toast(n[0].toUpperCase() + n.slice(1) + ' mode', 'ok', 1200); return; }
    if (n === 'review' && !ctxs().some((c) => c.kind === 'git')) ctxs().push({ kind: 'git', path: '@git' });
    if ((n === 'explain' || n === 'tests') && F.activePath) C.addSelectionAndFocus();
    if (n === 'fix-lints' && !addLints()) return;
    renderContexts();
    promptEl.value = (cmd.prompt || '').trim() + (rest ? '\n\n' + rest : '');
    autoGrow(); promptEl.focus();
  }
  function autoGrow() { promptEl.style.height = 'auto'; promptEl.style.height = Math.min(240, Math.max(64, promptEl.scrollHeight)) + 'px'; }
  promptEl.addEventListener('keydown', (e) => {
    if (menu.items.length) {
      if (e.key === 'ArrowDown') { menu.sel = (menu.sel + 1) % menu.items.length; renderMenu(); e.preventDefault(); return; }
      if (e.key === 'ArrowUp') { menu.sel = (menu.sel - 1 + menu.items.length) % menu.items.length; renderMenu(); e.preventDefault(); return; }
      if (e.key === 'Enter' || e.key === 'Tab') { pickMenu(menu.sel); e.preventDefault(); return; }
      if (e.key === 'Escape') { hideMenu(); e.preventDefault(); return; }
    }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); }
    else if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.isComposing) { e.preventDefault(); send(); }
    else if (e.key === 'Escape' && active && active.run) stopRun(active);
  });

  // ================================================================== sending, queueing and streaming
  let initDone = null;
  async function send() {
    if (initDone) await initDone;
    if (!active) await newChat();
    const tab = active;
    let text = promptEl.value.trim();
    if (tab.contexts.some((c) => c.busy)) { toast('Wait a moment: an attachment is still uploading.', 'error'); return; }
    const slash = text.match(/^\/([\w-]+)\s*([\s\S]*)$/);
    if (slash) {
      await refreshCache();
      const cmd = cache.commands.find((c) => c.name === slash[1]);
      if (cmd && (cmd.builtin || !slash[2])) { promptEl.value = ''; return runCommand(cmd, slash[2]); }
      if (cmd) text = (cmd.prompt || '').trim() + '\n\n' + slash[2];
    }
    // Mentions typed by hand (for example "@codebase" or "@web") work like ones picked from the menu.
    for (const m of text.matchAll(/(?:^|\s)@(codebase|web|git)\b/g)) if (!tab.contexts.some((c) => c.kind === m[1])) tab.contexts.push({ kind: m[1], path: '@' + m[1] });
    if (/(?:^|\s)@terminal\b/.test(text) && !tab.contexts.some((c) => c.kind === 'terminal') && window.ForgeTerm) tab.contexts.push({ kind: 'terminal', text: window.ForgeTerm.activeOutput() });
    if (!text && tab.contexts.some((c) => c.kind === 'image')) text = 'Look at the attached image.';
    if (!text && tab.contexts.length) text = 'Look at the attached context.';
    if (!text) return;
    const item = { text, attachments: tab.contexts.slice(), mode: $('#mode-select').value };
    // Like Cursor, tell the model which file you are looking at (name and cursor line only, so prompts stay small).
    const viewing = (window.ForgeSplit && window.ForgeSplit.focused()) ? window.ForgeSplit.activePath() : F.activePath;
    if (viewing && F.editor && !item.attachments.some((c) => c.path === viewing)) {
      const pos = (window.ForgeSplit && window.ForgeSplit.focused() ? window.ForgeSplit.editor : F.editor).getPosition();
      item.attachments.push({ kind: 'editor', path: viewing, line: pos ? pos.lineNumber : null });
    }
    promptEl.value = ''; tab.draft = ''; autoGrow();
    try { localStorage.removeItem(draftKey()); } catch (e) { /* ignore */ }
    tab.contexts = []; renderContexts(); hideMenu();
    if (tab.run) { tab.queue.push(item); renderQueue(); toast('Queued. It is sent when the current reply finishes.', 'ok', 1500); return; }
    startRun(tab, item);
  }
  function renderQueue() {
    const q = $('#queue');
    q.innerHTML = '';
    if (!active || !active.queue.length) { q.classList.add('hidden'); return; }
    q.classList.remove('hidden');
    active.queue.forEach((it, i) => q.append(el('div', { class: 'qitem' }, el('span', { class: 'qlabel', text: 'Queued' }), el('span', { class: 'qtext', text: it.text, title: it.text }),
      el('button', { class: 'mini', text: '⏩', title: 'Send now (stops the current reply)', onclick: () => { const [x] = active.queue.splice(i, 1); active.queue.unshift(x); renderQueue(); stopRun(active); } }),
      el('button', { class: 'mini', text: '✎', title: 'Edit (moves it back to the input box)', onclick: () => { const [x] = active.queue.splice(i, 1); promptEl.value = x.text; active.contexts = x.attachments; renderContexts(); renderQueue(); autoGrow(); } }),
      el('button', { class: 'mini', text: '✕', title: 'Remove from the queue', onclick: () => { active.queue.splice(i, 1); renderQueue(); } }))));
  }
  /** A Retry button under a failed message: sends the same message again. `before` runs first (it may remove the failed attempt). */
  function retryButton(tab, item, before) {
    const wrap = el('div', { class: 'retry-row' });
    wrap.append(el('button', { class: 'btn sm', text: 'Retry', title: 'Send this message again', onclick: () => {
      if (tab.run) { toast('Wait until this chat has finished, or stop it first.', 'error'); return; }
      wrap.remove();
      if (before) before();
      if (tab === active) { promptEl.value = ''; tab.draft = ''; autoGrow(); }
      startRun(tab, { text: item.text, attachments: (item.attachments || []).slice(), mode: item.mode });
    } }));
    return wrap;
  }
  async function startRun(tab, item) {
    $('#chat-empty').classList.toggle('hidden', true);
    const userTurn = { role: 'user', text: item.text, attachments: item.attachments, ts: Date.now() };
    tab.transcript.push(userTurn);
    const userEl = renderUser(tab, userTurn);
    tab.pane.append(userEl);
    if (tab.session.title === 'New chat') { tab.session.title = item.text.slice(0, 60); renderTabBar(); }
    const turn = { role: 'assistant', blocks: [], stats: null, mode: item.mode, ts: Date.now() };
    tab.transcript.push(turn);
    const msgEl = el('div', { class: 'msg assistant' });
    tab.pane.append(msgEl);
    const spinner = el('div', { class: 'status-line', text: 'Thinking…' });
    msgEl.append(spinner);
    if (tab === active) host.scrollTop = host.scrollHeight;
    let r;
    try { r = await post('/api/chat', { session_id: tab.session.id, message: item.text, mode: item.mode, attachments: item.attachments, model: tab.session.model_choice || '' }); }
    catch (e) {
      spinner.textContent = 'Error: ' + e.message; spinner.classList.add('error');
      turn.blocks.push({ type: 'status', text: 'Error: ' + e.message, error: true });
      // Retry sends the same message again (with its attachments and mode) and replaces this failed attempt.
      msgEl.append(retryButton(tab, item, () => {
        for (const t of [userTurn, turn]) { const k = tab.transcript.indexOf(t); if (k >= 0) tab.transcript.splice(k, 1); }
        userEl.remove(); msgEl.remove();
      }));
      saveTranscript(tab); return;
    }
    userTurn.run_id = r.run_id;
    userEl.replaceWith(renderUser(tab, userTurn));
    const R = tab.run = { id: r.run_id, tab, msgEl, turn, spinner, cur: null, curEl: null, tools: new Map(), filesEl: null, todosEl: null, pending: false,
      t0: Date.now(), exchanges: new Map(), timeline: null, timelineEl: null, item };
    tab.models = { llama: { state: 'reading', label: '', detail: 'Starting', since: Date.now() } };
    renderStrip();
    renderTabBar(); refreshComposer();
    const es = new EventSource('/api/events?run_id=' + r.run_id);
    R.es = es;
    es.onmessage = (m) => { try { handleEvent(R, JSON.parse(m.data)); } catch (e) { console.error(e); } };
    es.onerror = () => { setTimeout(() => { if (tab.run === R && es.readyState === 2) finishRun(R, 'disconnected'); }, 1500); };
  }
  $('#btn-send').onclick = send;
  $('#btn-stop').onclick = () => { if (active) stopRun(active); };
  function refreshComposer() {
    const busy = !!(active && active.run);
    $('#btn-stop').classList.toggle('hidden', !busy);
    $('#btn-send').title = busy ? 'Queue this message (Enter)' : 'Send (Enter)';
    $('#btn-send').classList.toggle('queueing', busy);
    const any = tabs.filter((t) => t.run).length;
    $('#sb-run').textContent = any ? (any === 1 ? 'Agent working…' : any + ' chats working…') : '';
    $('#sb-run').classList.toggle('busy', !!any);
  }
  async function stopRun(tab) { if (tab.run) { try { await post('/api/cancel', { run_id: tab.run.id }); } catch (e) { /* ignore */ } } }
  function finishRun(R, status) {
    const tab = R.tab;
    if (tab.run !== R) return;
    if (R.es) R.es.close();
    R.spinner.remove();
    closeCurrent(R);
    tab.run = null;
    if (tab.models) {
      for (const st of Object.values(tab.models)) if (BUSY.has(st.state) || WAITING.has(st.state) || st.state === 'done') { st.state = 'idle'; st.since = Date.now(); }
      tab.modelsUntil = Date.now() + 8000;
    }
    if (R.timeline) {
      R.timeline.end = (Date.now() - R.t0) / 1000;
      if (R.timelineEl) { R.timelineEl.classList.remove('live'); R.timelineEl._sum.textContent = timelineSummary(R.timeline, R.timeline.end); if (!R.timeline.items.some((i) => i.model !== 'llama')) R.timelineEl.open = false; }
    }
    renderStrip();
    if (tab !== active) tab.attention = true;
    renderTabBar(); refreshComposer();
    saveTranscript(tab);
    loadHistory($('#history'));
    F.loadChanges && F.loadChanges();
    if (status === 'disconnected') toast('Connection to the Forge server was lost.', 'error');
    if (tab.queue.length) { const next = tab.queue.shift(); if (tab === active) renderQueue(); setTimeout(() => startRun(tab, next), 300); }
  }
  const visible = (R) => R.tab === active;
  function scrollIfActive(R, force) { if (!visible(R)) return; const near = host.scrollHeight - host.scrollTop - host.clientHeight < 120; if (force || near) host.scrollTop = host.scrollHeight; }
  function flushText(R) {
    if (R.pending) return;
    R.pending = true;
    requestAnimationFrame(() => {
      R.pending = false;
      if (!R.cur) return;
      if (R.cur.type === 'text') { R.curEl.innerHTML = md(R.cur.text); colorize(R.curEl); R.curEl.classList.add('cursor-blink'); }
      else if (R.cur.type === 'thinking') R.curEl._body.textContent = R.cur.text;
      scrollIfActive(R);
    });
  }
  function closeCurrent(R) {
    if (!R.cur) return;
    if (R.cur.type === 'text') { R.curEl.innerHTML = md(R.cur.text); colorize(R.curEl); R.curEl.classList.remove('cursor-blink'); if (!R.cur.text.trim()) R.curEl.remove(); }
    if (R.cur.type === 'thinking') { R.curEl.classList.remove('live'); R.curEl._label.textContent = 'Thought process'; R.curEl.open = false; R.curEl._body.textContent = R.cur.text; }
    R.cur = null; R.curEl = null;
  }
  function startBlock(R, type) {
    closeCurrent(R);
    R.spinner.remove();
    const block = { type, text: '' };
    R.turn.blocks.push(block);
    R.cur = block;
    R.curEl = type === 'thinking' ? thinkingBlock('', true) : el('div', { class: 'md cursor-blink' });
    R.msgEl.append(R.curEl);
    scrollIfActive(R);
  }
  function logVerbose(kind, text) {
    const log = $('#agent-log');
    const line = el('div');
    line.innerHTML = '<span class="lt">' + new Date().toLocaleTimeString() + '</span> <span class="lk">' + esc(kind) + '</span> ' + esc(text);
    log.append(line);
    while (log.children.length > 2000) log.firstChild.remove();
    log.scrollTop = log.scrollHeight;
  }
  function handleEvent(R, ev) {
    if (R.tab.run !== R) return;
    switch (ev.type) {
      case 'thinking_delta': if (!R.cur || R.cur.type !== 'thinking') startBlock(R, 'thinking'); R.cur.text += ev.text; flushText(R); break;
      case 'text_delta': if (!R.cur || R.cur.type !== 'text') startBlock(R, 'text'); R.cur.text += ev.text; flushText(R); break;
      case 'tool_call_start': closeCurrent(R); break;
      case 'model_activity': {
        const now = (Date.now() - R.t0) / 1000;
        const tab = R.tab;
        if (!tab.models) tab.models = {};
        const prev = tab.models[ev.model] || {};
        tab.models[ev.model] = { state: ev.state, label: ev.label || prev.label || '', detail: ev.detail || '', since: Date.now(), tps: prev.tps };
        if (!R.timeline) {
          R.timeline = { type: 'timeline', items: [] };
          R.turn.blocks.unshift(R.timeline);
          R.timelineEl = timelineBlock(R.timeline, true);
          const todo = R.msgEl.querySelector(':scope > .todos');
          if (todo) todo.after(R.timelineEl); else R.msgEl.prepend(R.timelineEl);
        }
        const it = { t: Math.round(now * 10) / 10, model: ev.model, state: ev.state, label: ev.label || '', detail: ev.detail || '' };
        R.timeline.items.push(it);
        R.timelineEl._list.append(timelineItem(it));
        R.timelineEl._sum.textContent = timelineSummary(R.timeline, now);
        if (ev.exchange && R.exchanges.has(ev.exchange)) {
          const x = R.exchanges.get(ev.exchange);
          if (ev.model === 'vision' && ev.label) x.b.vision_label = ev.label;
          x.b.status = (ev.model === 'llama' ? 'Llama 3.1: ' : ev.model === 'ocr' ? 'Text recognition: ' : '') + (ev.detail || stateText(ev.model, ev.state));
          if (x.b.state !== 'answered') { const fresh = exchangeCard(x.b); x.el.replaceWith(fresh); x.el = fresh; }
        }
        if (R.tab === active) renderStrip();
        logVerbose(ev.model, stateText(ev.model, ev.state) + ': ' + (ev.detail || ''));
        break;
      }
      case 'model_progress': {
        const st = R.tab.models && R.tab.models[ev.model];
        if (st) { st.tps = ev.tps; st.tokens = ev.tokens; }
        if (R.tab === active) renderStrip();
        break;
      }
      case 'vision_exchange': {
        closeCurrent(R); R.spinner.remove();
        if (ev.phase === 'ask') {
          const b = { type: 'exchange', id: ev.id, asker: ev.asker, question: ev.question, number: ev.number, image_url: ev.image_url, answer: '', state: 'asking' };
          R.turn.blocks.push(b);
          const card = exchangeCard(b);
          R.exchanges.set(ev.id, { b, el: card });
          R.msgEl.append(card); scrollIfActive(R, true);
          // The exchange card shows Llama's question and the answer, so its look_at_image tool card is not repeated.
          if (ev.asker === 'llama') for (const [, tx] of R.tools) if (tx.t.name === 'look_at_image') tx.card.classList.add('as-exchange');
        } else if (ev.phase === 'answer' && R.exchanges.has(ev.id)) {
          const x = R.exchanges.get(ev.id);
          Object.assign(x.b, { state: 'answered', answer: ev.text || x.b.answer || '', ocr: ev.ocr || '', source: ev.source, cached: ev.cached, seconds: ev.seconds, error: ev.error, status: '' });
          if (!x.b.vision_label && ev.source && ev.source !== 'unavailable' && ev.source !== 'off') x.b.vision_label = ev.source;
          const fresh = exchangeCard(x.b); x.el.replaceWith(fresh); x.el = fresh;
          scrollIfActive(R);
        }
        break;
      }
      case 'vision_delta': {
        const x = ev.exchange && R.exchanges.get(ev.exchange);
        if (!x) break;
        x.b.answer = (x.b.answer || '') + ev.text;
        x.el._ans.textContent = x.b.answer;
        scrollIfActive(R);
        break;
      }
      case 'tool_call': {
        closeCurrent(R); R.spinner.remove();
        const t = { type: 'tool', call_id: ev.call_id, name: ev.name, arguments: ev.arguments, state: 'running', result: '' };
        R.turn.blocks.push(t);
        const card = toolCard(t);
        R.tools.set(ev.call_id, { t, card });
        R.msgEl.append(card); scrollIfActive(R);
        logVerbose('tool', ev.name + ' ' + argSummary(ev.name, ev.arguments));
        break;
      }
      case 'tool_result': {
        const x = R.tools.get(ev.call_id);
        if (!x) break;
        x.t.state = ev.ok ? 'ok' : 'fail'; x.t.result = ev.result; x.t.seconds = ev.seconds;
        x.card.classList.remove('running'); x.card.classList.add(ev.ok ? 'ok' : 'fail');
        x.card._res.textContent = ev.result;
        x.card._state.textContent = ev.ok ? 'done' + (ev.seconds != null ? ' · ' + ev.seconds + 's' : '') : 'failed';
        if (!ev.ok) x.card.classList.add('open');
        if (window.ForgeExtras && (ev.name === 'write_file' || ev.name === 'edit_file')) window.ForgeExtras.reportCheck((x.t.arguments || {}).path || '', ev.result.includes('SYNTAX CHECK FAILED') ? ev.result.slice(ev.result.indexOf('SYNTAX CHECK FAILED')) : 'Syntax check: OK');
        if (ev.name === 'write_file' || ev.name === 'edit_file') { F.refreshFile((x.t.arguments || {}).path); F.loadTree(); }
        break;
      }
      case 'file_changed': {
        const ch = { path: ev.path, kind: ev.kind, run_id: ev.run_id };
        let fb = R.turn.blocks.find((b) => b.type === 'files');
        if (!fb) { fb = { type: 'files', files: [] }; R.turn.blocks.push(fb); R.filesEl = filesRow(ch.run_id); R.msgEl.append(R.filesEl); }
        if (!fb.files.some((f) => f.path === ch.path)) { fb.files.push(ch); R.filesEl._chips.append(fileChangeChip(ch)); }
        else fb.files[fb.files.findIndex((f) => f.path === ch.path)] = ch;
        F.refreshFile(ev.path);
        if (window.ForgeReview) window.ForgeReview.onAgentChange(ev.run_id, ev.path);
        break;
      }
      case 'todos': {
        let b = R.turn.blocks.find((x) => x.type === 'todos');
        if (!b) { b = { type: 'todos', todos: [] }; R.turn.blocks.push(b); }
        b.todos = ev.todos;
        const fresh = todosBlock(b);
        if (R.todosEl) R.todosEl.replaceWith(fresh); else R.msgEl.prepend(fresh);
        R.todosEl = fresh;
        break;
      }
      case 'approval_required': {
        closeCurrent(R);
        const a = { type: 'approval', run_id: ev.run_id, approval_id: ev.approval_id, tool: ev.tool, arguments: ev.arguments, reason: ev.reason, decision: null };
        R.turn.blocks.push(a);
        R.msgEl.append(approvalCard(a, false)); scrollIfActive(R, true);
        if (R.tab !== active) { R.tab.attention = true; renderTabBar(); }
        if ((document.hidden || R.tab !== active) && 'Notification' in window && Notification.permission === 'granted') new Notification('Forge needs your approval', { body: argSummary(ev.tool, ev.arguments) });
        break;
      }
      case 'approval_resolved': break;
      case 'ask_user': closeCurrent(R); R.turn.blocks.push({ type: 'ask', question: ev.question }); R.msgEl.append(askCard(ev.question)); if (visible(R)) promptEl.focus(); break;
      case 'image_analysis': {
        closeCurrent(R); R.spinner.remove();
        const b = { type: 'vision', number: ev.number, source: ev.source, description: ev.description, ocr: ev.ocr };
        R.turn.blocks.push(b); R.msgEl.append(visionBlock(b)); scrollIfActive(R);
        break;
      }
      case 'tool_error': R.turn.blocks.push({ type: 'status', text: ev.message, error: true }); R.msgEl.append(el('div', { class: 'status-line error', text: ev.message })); break;
      case 'status': closeCurrent(R); R.turn.blocks.push({ type: 'status', text: ev.text }); R.msgEl.append(el('div', { class: 'status-line', text: ev.text })); break;
      case 'stats': {
        R.turn.stats = ev;
        if (!visible(R)) break;
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
        R.turn.blocks.push({ type: 'verbose', kind: ev.kind, text: ev.text });
        if (verbose) { closeCurrent(R); R.msgEl.append(el('div', { class: 'verbose', text: '[' + ev.kind + '] ' + ev.text })); }
        break;
      case 'error':
        closeCurrent(R);
        R.turn.blocks.push({ type: 'status', text: 'Error: ' + ev.message, error: true });
        R.msgEl.append(el('div', { class: 'status-line error', text: 'Error: ' + ev.message + (ev.detail ? '\n' + ev.detail : '') }));
        if (R.item) R.msgEl.append(retryButton(R.tab, R.item, null));
        logVerbose('error', ev.message + (ev.detail ? '\n' + ev.detail : ''));
        break;
      case 'end':
        closeCurrent(R);
        if (R.turn.stats) R.msgEl.append(statLine(R.turn.stats));
        finishRun(R, ev.status);
        break;
      default: break;
    }
  }

  // ================================================================== model picker, verbose, voice
  /** The composer's model picker belongs to the chat, like Cursor's: each tab keeps its own model. */
  let pickerModels = null;
  async function loadModelPicker(refresh) {
    const sel = $('#model-pick');
    if (!sel) return;
    try {
      if (!pickerModels || refresh) pickerModels = (await get('/api/models')).models.map((m) => m.name).filter((n) => !/vl|embed/i.test(n));
    } catch (e) { pickerModels = pickerModels || []; }
    const def = F.settings.engine === 'gpu' ? 'GPU engine' : String(F.settings.model || 'model').replace(':latest', '');
    sel.innerHTML = '';
    sel.append(el('option', { value: '', text: 'Default (' + def + ')' }));
    sel.append(el('option', { value: '__gpu', text: '⚡ GPU engine (Intel)' }));
    for (const n of pickerModels) sel.append(el('option', { value: n, text: n.replace(':latest', '') + ' (CPU)' }));
    const want = active ? (active.session.model_choice || '') : '';
    sel.value = [...sel.options].some((o) => o.value === want) ? want : '';
    sel.title = 'Model for this chat. Default follows the window setting (' + def + ').';
  }
  $('#model-pick').addEventListener('change', (e) => {
    if (!active) return;
    active.session.model_choice = e.target.value;
    toast('This chat now uses ' + (e.target.selectedOptions[0] || {}).textContent + '.', 'ok', 1500);
  });
  C.toggleVerbose = () => { F.saveSetting('verbose', !F.settings.verbose); };
  $('#btn-verbose').onclick = C.toggleVerbose;
  $('#btn-export').onclick = () => C.exportChat();
  $('#btn-voice').onclick = () => { if (window.ForgeVoice) window.ForgeVoice.toggle(promptEl, $('#btn-voice')); else toast('Voice input is not available.', 'error'); };
  /** Built-in modes plus your custom modes (Settings, Custom modes). */
  function renderModes() {
    const sel = $('#mode-select');
    const cur = sel.value || localStorage.getItem('forge.mode') || 'agent';
    sel.innerHTML = '';
    for (const [v, t] of [['agent', 'Agent'], ['ask', 'Ask'], ['plan', 'Plan']]) sel.append(el('option', { value: v, text: t }));
    const custom = (F.settings.custom_modes || []).filter((m) => m && m.name);
    for (const m of custom) sel.append(el('option', { value: m.name, text: m.name, title: m.instructions || '' }));
    sel.append(el('option', { value: '__edit', text: 'Edit modes…' }));
    sel.value = [...sel.options].some((o) => o.value === cur && o.value !== '__edit') ? cur : 'agent';
  }
  C.renderModes = renderModes;
  C.onSettings = () => {
    renderModes(); verbose = !!F.settings.verbose; $('#btn-verbose').classList.toggle('on', verbose); loadModelPicker(true); };
  $('#mode-select').addEventListener('change', () => {
    const sel = $('#mode-select');
    if (sel.value === '__edit') { sel.value = localStorage.getItem('forge.mode') || 'agent'; F.showView('settings'); setTimeout(() => { const g = [...document.querySelectorAll('#settings-panel .sgroup')].find((x) => x.textContent === 'Custom modes'); if (g) g.scrollIntoView({ block: 'start' }); }, 600); return; }
    localStorage.setItem('forge.mode', sel.value);
  });

  // ================================================================== start
  C.init = async () => {
    verbose = !!F.settings.verbose;
    renderModes();
    $('#mode-select').value = localStorage.getItem('forge.mode') || 'agent';
    if (!$('#mode-select').value) $('#mode-select').value = 'agent';
    const params = new URLSearchParams(location.search);
    const urlChat = params.get('chat');
    if (urlChat) { params.delete('chat'); history.replaceState(null, '', '/?' + params.toString()); }
    initDone = (async () => {
      let saved = { ids: [], active: null };
      try { saved = JSON.parse(localStorage.getItem(tabsKey()) || '{"ids":[]}'); } catch (e) { /* ignore */ }
      if (!saved.ids || !saved.ids.length) { const old = localStorage.getItem('forge.session.' + F.folder); if (old) saved = { ids: [old], active: old }; }
      for (const id of (saved.ids || []).slice(0, 12)) {
        try { const s = await get('/api/session', { id }); if (!s.workspace || s.workspace === F.folder) addTab(s, false); } catch (e) { /* deleted chat */ }
      }
      if (urlChat) await openChat(urlChat).catch(() => {});
      const want = tabs.find((t) => t.session.id === (urlChat || saved.active));
      if (want) switchTo(want); else if (tabs.length) switchTo(tabs[tabs.length - 1]); else await newChat();
      // An unsent message comes back: carried over from the folder this window just left, or kept from before a
      // reload of this folder's window.
      let carried = '';
      try { carried = sessionStorage.getItem('forge.carryDraft') || ''; sessionStorage.removeItem('forge.carryDraft'); } catch (e) { /* ignore */ }
      if (!carried) { try { carried = localStorage.getItem(draftKey()) || ''; } catch (e) { /* ignore */ } }
      if (carried && !promptEl.value.trim() && active) { promptEl.value = carried; active.draft = carried; autoGrow(); try { localStorage.setItem(draftKey(), carried); } catch (e) { /* ignore */ } }
    })();
    await initDone;
    loadModelPicker();
    if ('Notification' in window && Notification.permission === 'default') { try { Notification.requestPermission(); } catch (e) { /* ignore */ } }
    loadHistory($('#history'));
  };
})();
