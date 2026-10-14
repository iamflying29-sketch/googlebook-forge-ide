/* Forge IDE: Jupyter notebooks (.ipynb). A notebook opens as cells instead of raw JSON: code cells with their own
 * editor and outputs (text, tables, images and errors), Markdown cells, and a Python kernel that keeps variables
 * between cells. The notebook is still a normal file tab: edits update the file's JSON, and Ctrl+S saves it. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, el, esc, toast, post } = F;
  const N = window.ForgeNotebook = {};
  const docs = new Map();      // path -> NotebookDoc
  const rawMode = new Set();   // paths shown as JSON instead
  let view = null, shown = null;
  const ANSI = /\x1b\[[0-9;]*[A-Za-z]/g;
  const uid = () => Math.random().toString(36).slice(2, 10);
  const srcText = (s) => (Array.isArray(s) ? s.join('') : (s || ''));
  const toLines = (t) => { const parts = t.split('\n'); return parts.map((p, i) => (i < parts.length - 1 ? p + '\n' : p)).filter((p, i) => p !== '' || i < parts.length - 1); };

  function emptyNotebook() {
    return { cells: [{ cell_type: 'code', id: uid(), metadata: {}, execution_count: null, outputs: [], source: [] }], metadata: { kernelspec: { display_name: 'Python 3', language: 'python', name: 'python3' }, language_info: { name: 'python' } }, nbformat: 4, nbformat_minor: 5 };
  }

  // ------------------------------------------------------------------ kernel connection
  class KernelConn {
    constructor(doc) { this.doc = doc; this.ws = null; this.state = 'not started'; this.waiting = new Map(); this.info = null; }
    open() {
      if (this.ws && this.ws.readyState <= 1) return Promise.resolve();
      return new Promise((resolve) => {
        const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
        this.state = 'starting'; this.doc.renderStatus();
        const ws = this.ws = new WebSocket(proto + location.host + '/ws/kernel?folder=' + encodeURIComponent(F.folder) + '&path=' + encodeURIComponent(this.doc.path));
        ws.onopen = () => resolve();
        ws.onmessage = (m) => { try { this.onMessage(JSON.parse(m.data)); } catch (e) { console.error(e); } };
        ws.onclose = () => { this.state = 'disconnected'; this.ws = null; this.doc.renderStatus(); for (const c of this.waiting.values()) c.setRunning(false); this.waiting.clear(); resolve(); };
      });
    }
    send(obj) { if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(obj)); }
    async execute(cell) {
      await this.open();
      const id = uid();
      this.waiting.set(id, cell);
      cell.setRunning(true, id);
      this.state = 'busy'; this.doc.renderStatus();
      this.send({ op: 'execute', id, code: cell.code() });
    }
    onMessage(msg) {
      const cell = msg.id ? this.waiting.get(msg.id) : null;
      switch (msg.type) {
        case 'ready': case 'attached': this.info = msg.info || msg; this.state = msg.busy ? 'busy' : 'idle'; break;
        case 'restarted': this.state = 'idle'; for (const c of this.waiting.values()) c.setRunning(false); this.waiting.clear(); toast('Kernel restarted. Variables were cleared.', 'ok'); break;
        case 'stream': if (cell) cell.addOutput({ output_type: 'stream', name: msg.name, text: msg.text }); break;
        case 'result': if (cell) cell.addOutput({ output_type: 'execute_result', execution_count: msg.count, data: msg.data, metadata: {} }); break;
        case 'display': if (cell) cell.addOutput({ output_type: 'display_data', data: msg.data, metadata: {} }); break;
        case 'error': if (cell) cell.addOutput({ output_type: 'error', ename: msg.ename, evalue: msg.evalue, traceback: msg.traceback || [] }); break;
        case 'done': if (cell) { cell.finish(msg.count); this.waiting.delete(msg.id); } if (!this.waiting.size) this.state = 'idle'; break;
        case 'exit': this.state = 'stopped'; for (const c of this.waiting.values()) c.setRunning(false); this.waiting.clear(); if (msg.message) toast(msg.message, msg.message.includes('memory') ? 'error' : 'ok', 6000); break;
        default: break;
      }
      this.doc.renderStatus();
    }
  }

  // ------------------------------------------------------------------ cells
  class Cell {
    constructor(doc, data) {
      this.doc = doc; this.data = data; if (!data.id) data.id = uid(); if (!data.metadata) data.metadata = {};
      if (data.cell_type === 'code') { if (!Array.isArray(data.outputs)) data.outputs = []; if (data.execution_count === undefined) data.execution_count = null; }
      this.editor = null; this.model = null; this.runId = null;
      this.node = el('div', { class: 'nb-cell ' + data.cell_type });
      this.build();
    }
    code() { return this.model ? this.model.getValue() : srcText(this.data.source); }
    build() {
      const d = this.data;
      this.node.innerHTML = '';
      const gutter = el('div', { class: 'nb-gutter' });
      const body = el('div', { class: 'nb-body' });
      const tools = el('div', { class: 'nb-ctools' },
        el('button', { class: 'mini', text: '▲', title: 'Move up', onclick: () => this.doc.move(this, -1) }),
        el('button', { class: 'mini', text: '▼', title: 'Move down', onclick: () => this.doc.move(this, 1) }),
        el('button', { class: 'mini', text: d.cell_type === 'code' ? 'M' : '{}', title: d.cell_type === 'code' ? 'Change to Markdown' : 'Change to code', onclick: () => this.doc.convert(this) }),
        el('button', { class: 'mini', text: '✕', title: 'Delete cell', onclick: () => this.doc.remove(this) }));
      if (d.cell_type === 'code') {
        this.countEl = el('span', { class: 'nb-count', text: '[' + (d.execution_count == null ? ' ' : d.execution_count) + ']' });
        this.runBtn = el('button', { class: 'nb-run', title: 'Run cell (Shift+Enter)', text: '▶', onclick: () => this.run() });
        gutter.append(this.runBtn, this.countEl);
        const edHost = el('div', { class: 'nb-editor' });
        body.append(edHost);
        this.outputs = el('div', { class: 'nb-outputs' });
        body.append(this.outputs);
        this.mountEditor(edHost, 'python', srcText(d.source));
        this.renderOutputs();
      } else {
        this.rendered = el('div', { class: 'nb-md md', ondblclick: () => this.editMarkdown() });
        body.append(this.rendered);
        this.edHost = el('div', { class: 'nb-editor hidden' });
        body.append(this.edHost);
        this.renderMarkdown();
      }
      this.node.append(gutter, body, tools);
    }
    mountEditor(host, lang, text) {
      const monaco = F.monaco;
      this.model = monaco.editor.createModel(text, lang);
      this.editor = monaco.editor.create(host, {
        model: this.model, theme: 'forge-dark', minimap: { enabled: false }, lineNumbers: 'off', glyphMargin: false, folding: false,
        scrollBeyondLastLine: false, wordWrap: 'on', fontSize: F.settings.font_size || 13, renderLineHighlight: 'none', overviewRulerLanes: 0,
        scrollbar: { vertical: 'hidden', alwaysConsumeMouseWheel: false, horizontalScrollbarSize: 6 }, automaticLayout: false, padding: { top: 6, bottom: 6 },
        lineDecorationsWidth: 6, fixedOverflowWidgets: true,
      });
      const fit = () => { const h = Math.max(28, Math.min(2000, this.editor.getContentHeight())); host.style.height = h + 'px'; this.editor.layout({ width: host.clientWidth, height: h }); };
      this.editor.onDidContentSizeChange(fit);
      this.fit = fit;
      setTimeout(fit, 0);
      this.model.onDidChangeContent(() => { this.data.source = toLines(this.model.getValue()); this.doc.changed(); });
      this.editor.addCommand(monaco.KeyMod.Shift | monaco.KeyCode.Enter, () => { this.run(); this.doc.focusNext(this, true); });
      this.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => this.run());
      this.editor.addCommand(monaco.KeyMod.Alt | monaco.KeyCode.Enter, () => { this.run(); this.doc.insert(this, 'code', true); });
    }
    dispose() { if (this.editor) this.editor.dispose(); if (this.model) this.model.dispose(); this.editor = null; this.model = null; }
    // ---------------- markdown
    renderMarkdown() {
      const text = srcText(this.data.source);
      this.rendered.innerHTML = text.trim() ? DOMPurify.sanitize(marked.parse(text)) : '<p class="muted">Empty Markdown cell. Double-click to edit.</p>';
    }
    editMarkdown() {
      this.rendered.classList.add('hidden');
      this.edHost.classList.remove('hidden');
      if (!this.editor) {
        this.mountEditor(this.edHost, 'markdown', srcText(this.data.source));
        this.editor.addCommand(F.monaco.KeyMod.Shift | F.monaco.KeyCode.Enter, () => { this.finishMarkdown(); this.doc.focusNext(this, true); });
        this.editor.onDidBlurEditorText(() => this.finishMarkdown());
      }
      setTimeout(() => { this.fit && this.fit(); this.editor.focus(); }, 0);
    }
    finishMarkdown() { if (!this.editor) return; this.data.source = toLines(this.model.getValue()); this.renderMarkdown(); this.rendered.classList.remove('hidden'); this.edHost.classList.add('hidden'); }
    run() {
      if (this.data.cell_type !== 'code') { this.finishMarkdown(); return; }
      this.data.outputs = [];
      this.renderOutputs();
      this.doc.kernel.execute(this);
    }
    setRunning(on, id) { this.runId = on ? id : null; this.node.classList.toggle('running', on); if (on) this.countEl.textContent = '[*]'; else if (this.countEl.textContent === '[*]') this.countEl.textContent = '[ ]'; }
    finish(count) { this.data.execution_count = count; this.countEl.textContent = '[' + count + ']'; this.node.classList.remove('running'); this.runId = null; this.doc.changed(); }
    addOutput(o) {
      const outs = this.data.outputs;
      const last = outs[outs.length - 1];
      if (o.output_type === 'stream' && last && last.output_type === 'stream' && last.name === o.name) last.text = srcText(last.text) + o.text;
      else outs.push(o);
      this.renderOutputsSoon();
      this.doc.changed();
    }
    renderOutputsSoon() { if (this.pending) return; this.pending = true; requestAnimationFrame(() => { this.pending = false; this.renderOutputs(); }); }
    renderOutputs() {
      const host = this.outputs;
      if (!host) return;
      host.innerHTML = '';
      for (const o of this.data.outputs) host.append(renderOutput(o, this));
    }
  }
  function renderOutput(o, cell) {
    if (o.output_type === 'stream') return el('pre', { class: 'nb-out stream ' + (o.name === 'stderr' ? 'err' : ''), text: srcText(o.text).replace(ANSI, '') });
    if (o.output_type === 'error') {
      const box = el('div', { class: 'nb-out error' }, el('pre', { text: (o.traceback || []).map((t) => srcText(t)).join('').replace(ANSI, '') || (o.ename + ': ' + o.evalue) }));
      box.append(el('button', { class: 'btn ghost sm', text: 'Fix with AI', onclick: () => window.ForgeChat && window.ForgeChat.sendMessage('This notebook cell in ' + cell.doc.path + ' fails. Explain why and fix the cell (edit the notebook file).', 'agent',
        { kind: 'text', title: 'Notebook cell and error', path: 'Notebook cell', text: 'Cell code:\n' + cell.code() + '\n\nError:\n' + (o.traceback || []).map(srcText).join('').replace(ANSI, '') }) }));
      return box;
    }
    const data = o.data || {};
    const wrap = el('div', { class: 'nb-out rich' });
    if (data['image/png']) wrap.append(el('img', { src: 'data:image/png;base64,' + srcText(data['image/png']).replace(/\s/g, ''), alt: 'figure' }));
    else if (data['image/jpeg']) wrap.append(el('img', { src: 'data:image/jpeg;base64,' + srcText(data['image/jpeg']).replace(/\s/g, ''), alt: 'figure' }));
    else if (data['text/html']) { const d = el('div', { class: 'nb-html' }); d.innerHTML = DOMPurify.sanitize(srcText(data['text/html'])); wrap.append(d); }
    else if (data['image/svg+xml']) { const d = el('div', { class: 'nb-html' }); d.innerHTML = DOMPurify.sanitize(srcText(data['image/svg+xml']), { USE_PROFILES: { svg: true } }); wrap.append(d); }
    else if (data['text/markdown']) { const d = el('div', { class: 'md' }); d.innerHTML = DOMPurify.sanitize(marked.parse(srcText(data['text/markdown']))); wrap.append(d); }
    else wrap.append(el('pre', { text: srcText(data['text/plain']).replace(ANSI, '') }));
    if (o.output_type === 'execute_result') wrap.classList.add('result');
    return wrap;
  }

  // ------------------------------------------------------------------ notebook document
  class NotebookDoc {
    constructor(path) {
      this.path = path; this.cells = []; this.kernel = new KernelConn(this); this.timer = null; this.selfUpdate = false;
      this.root = el('div', { class: 'nb-root' });
      this.status = el('span', { class: 'nb-status' });
      const bar = el('div', { class: 'nb-toolbar' },
        el('button', { class: 'btn sm', text: '▶ Run all', onclick: () => this.runAll() }),
        el('button', { class: 'btn ghost sm', text: '+ Code', onclick: () => this.insert(null, 'code') }),
        el('button', { class: 'btn ghost sm', text: '+ Markdown', onclick: () => this.insert(null, 'markdown') }),
        el('button', { class: 'btn ghost sm', text: '■ Interrupt', onclick: () => this.kernel.send({ op: 'interrupt' }) }),
        el('button', { class: 'btn ghost sm', text: '↻ Restart', onclick: async () => { await this.kernel.open(); this.kernel.send({ op: 'restart' }); } }),
        el('button', { class: 'btn ghost sm', text: 'Clear outputs', onclick: () => { for (const c of this.cells) if (c.data.cell_type === 'code') { c.data.outputs = []; c.data.execution_count = null; c.countEl.textContent = '[ ]'; c.renderOutputs(); } this.changed(); } }),
        el('button', { class: 'btn ghost sm', text: '{ } JSON', title: 'Show the notebook file as JSON', onclick: () => { rawMode.add(this.path); sync(); } }),
        this.status);
      this.list = el('div', { class: 'nb-cells' });
      this.root.append(bar, this.list);
      this.load();
    }
    fileModel() { const f = F.files.get(this.path); return f && f.model; }
    load() {
      const m = this.fileModel();
      let nb;
      const text = m ? m.getValue() : '';
      try { nb = text.trim() ? JSON.parse(text) : emptyNotebook(); } catch (e) { nb = null; }
      for (const c of this.cells) c.dispose();
      this.cells = [];
      this.list.innerHTML = '';
      if (!nb || !Array.isArray(nb.cells)) { this.nb = null; this.list.append(el('div', { class: 'pad muted', text: 'This file is not a valid notebook. Use { } JSON to see and fix it.' })); return; }
      this.nb = nb;
      if (!nb.cells.length) nb.cells.push(emptyNotebook().cells[0]);
      for (const d of nb.cells) { const c = new Cell(this, d); this.cells.push(c); this.list.append(c.node); }
      this.list.append(el('div', { class: 'nb-add' }, el('button', { class: 'btn ghost sm', text: '+ Code', onclick: () => this.insert(null, 'code') }), el('button', { class: 'btn ghost sm', text: '+ Markdown', onclick: () => this.insert(null, 'markdown') })));
      this.renderStatus();
      if (!text.trim()) this.changed();
    }
    renderStatus() {
      const k = this.kernel;
      const py = k.info && k.info.python ? 'Python ' + k.info.python : 'Python';
      this.status.textContent = py + ' · ' + (k.state === 'not started' ? 'kernel starts when you run a cell' : k.state);
      this.status.className = 'nb-status ' + k.state.replace(/\s/g, '-');
    }
    changed() {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => this.flush(), 400);
    }
    flush() {
      clearTimeout(this.timer);
      const m = this.fileModel();
      if (!m || !this.nb) return;
      this.nb.cells = this.cells.map((c) => c.data);
      const text = JSON.stringify(this.nb, null, 1) + '\n';
      if (text === m.getValue()) return;
      this.selfUpdate = true;
      m.pushEditOperations([], [{ range: m.getFullModelRange(), text }], () => null);
      this.selfUpdate = false;
    }
    insert(after, type, focus) {
      const d = type === 'code' ? { cell_type: 'code', id: uid(), metadata: {}, execution_count: null, outputs: [], source: [] } : { cell_type: 'markdown', id: uid(), metadata: {}, source: [] };
      const c = new Cell(this, d);
      const i = after ? this.cells.indexOf(after) + 1 : this.cells.length;
      this.cells.splice(i, 0, c);
      const ref = this.list.children[i];
      this.list.insertBefore(c.node, ref || null);
      if (!ref) this.list.append(this.list.querySelector('.nb-add'));
      this.changed();
      if (type === 'markdown') c.editMarkdown(); else if (focus !== false) setTimeout(() => c.editor && c.editor.focus(), 0);
      return c;
    }
    remove(c) { const i = this.cells.indexOf(c); if (i < 0) return; c.dispose(); c.node.remove(); this.cells.splice(i, 1); if (!this.cells.length) this.insert(null, 'code', false); this.changed(); }
    move(c, dir) {
      const i = this.cells.indexOf(c), j = i + dir;
      if (j < 0 || j >= this.cells.length) return;
      this.cells.splice(i, 1); this.cells.splice(j, 0, c);
      const ref = dir < 0 ? this.cells[j + 1].node : (this.cells[j + 1] ? this.cells[j + 1].node : this.list.querySelector('.nb-add'));
      this.list.insertBefore(c.node, ref);
      this.changed();
    }
    convert(c) {
      const i = this.cells.indexOf(c);
      const src = c.code();
      const d = c.data.cell_type === 'code' ? { cell_type: 'markdown', id: c.data.id, metadata: c.data.metadata || {}, source: toLines(src) } : { cell_type: 'code', id: c.data.id, metadata: c.data.metadata || {}, execution_count: null, outputs: [], source: toLines(src) };
      c.dispose();
      const n = new Cell(this, d);
      this.cells[i] = n;
      c.node.replaceWith(n.node);
      this.changed();
    }
    focusNext(c, create) {
      const i = this.cells.indexOf(c);
      const next = this.cells[i + 1] || (create ? this.insert(c, 'code', false) : null);
      if (!next) return;
      next.node.scrollIntoView({ block: 'nearest' });
      if (next.data.cell_type === 'code') setTimeout(() => next.editor && next.editor.focus(), 0); else next.editMarkdown();
    }
    async runAll() { for (const c of this.cells) if (c.data.cell_type === 'code') await new Promise((res) => { c.run(); const t = setInterval(() => { if (!c.runId) { clearInterval(t); res(); } }, 100); }); }
    relayout() { for (const c of this.cells) if (c.fit) c.fit(); }
    dispose() { for (const c of this.cells) c.dispose(); if (this.kernel.ws) this.kernel.ws.close(); this.root.remove(); }
  }

  // ------------------------------------------------------------------ showing the notebook view
  function ensureView() {
    if (view) return view;
    view = el('div', { id: 'notebook', class: 'notebook hidden' });
    $('#editor').parentElement.append(view);
    new ResizeObserver(() => { const d = shown && docs.get(shown); if (d) d.relayout(); }).observe(view);
    return view;
  }
  function sync() {
    const path = F.activePath;
    const v = ensureView();
    const isNb = path && /\.ipynb$/i.test(path) && !rawMode.has(path) && F.files.has(path);
    if (!isNb) { v.classList.add('hidden'); shown = null; if (path && /\.ipynb$/i.test(path) && rawMode.has(path)) showJsonBar(path); else hideJsonBar(); return; }
    hideJsonBar();
    let d = docs.get(path);
    if (!d) {
      d = new NotebookDoc(path);
      docs.set(path, d);
      const m = d.fileModel();
      m.onDidChangeContent(() => { if (!d.selfUpdate) { clearTimeout(d.reload); d.reload = setTimeout(() => { if (!rawMode.has(path)) d.load(); }, 300); } });
      m.onWillDispose(() => { d.dispose(); docs.delete(path); if (shown === path) { v.classList.add('hidden'); shown = null; } });
    }
    if (shown !== path) { v.innerHTML = ''; v.append(d.root); shown = path; }
    v.classList.remove('hidden');
    setTimeout(() => d.relayout(), 0);
  }
  let jsonBar = null;
  function showJsonBar(path) {
    if (!jsonBar) { jsonBar = el('div', { class: 'nb-jsonbar' }); $('#editor').parentElement.append(jsonBar); }
    jsonBar.innerHTML = '';
    jsonBar.append(el('span', { text: 'Showing the notebook as JSON. ' }), el('button', { class: 'btn sm', text: 'Open as notebook', onclick: () => { rawMode.delete(path); const d = docs.get(path); if (d) d.load(); sync(); } }));
    jsonBar.classList.remove('hidden');
  }
  function hideJsonBar() { if (jsonBar) jsonBar.classList.add('hidden'); }
  // Ctrl+S: write the cells into the file before the editor saves it.
  window.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's' && shown) { const d = docs.get(shown); if (d) d.flush(); if (F.editor && !F.editor.hasTextFocus()) { /* app.js saves */ } } }, true);
  N.newNotebook = async () => {
    const name = prompt('Name of the new notebook:', 'notebook.ipynb');
    if (!name) return;
    const path = /\.ipynb$/i.test(name) ? name : name + '.ipynb';
    try { await post('/api/file', { path, content: JSON.stringify(emptyNotebook(), null, 1) + '\n' }); F.loadTree(); await F.openFile(path); }
    catch (e) { toast(e.message, 'error'); }
  };
  N.flushAll = () => { for (const d of docs.values()) d.flush(); };
  const wait = setInterval(() => { if (F.editor && F.hooks) { clearInterval(wait); F.hooks.onActiveFileChanged.push(() => setTimeout(sync, 0)); sync(); } }, 300);
})();
