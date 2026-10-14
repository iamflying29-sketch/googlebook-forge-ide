/* Forge IDE: core application (editor, explorer, palette, settings, inline edit). */
(function () {
  'use strict';
  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
  const el = (tag, attrs, ...children) => {
    const n = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (k === 'class') n.className = v;
      else if (k === 'text') n.textContent = v;
      else if (k === 'html') n.innerHTML = v;
      else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
      else if (v !== null && v !== undefined) n.setAttribute(k, v);
    }
    for (const c of children) if (c !== null && c !== undefined) n.append(c);
    return n;
  };
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

  // ------------------------------------------------------------------ API
  async function api(path, opts) {
    const r = await fetch(path, opts);
    let data = null;
    try { data = await r.json(); } catch (e) { data = { error: 'bad response' }; }
    if (!r.ok) throw new Error(data && data.error ? data.error : ('HTTP ' + r.status));
    return data;
  }
  const get = (path, params) => api(path + (params ? '?' + new URLSearchParams(params) : ''));
  const post = (path, body) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });

  function toast(msg, kind, ms) {
    const t = el('div', { class: 'toast ' + (kind || '') , text: msg });
    $('#toast-host').append(t);
    setTimeout(() => { t.style.opacity = '0'; setTimeout(() => t.remove(), 300); }, ms || 3500);
  }

  // ------------------------------------------------------------------ state
  const Forge = window.Forge = {
    $, $$, el, esc, api, get, post, toast, debounce,
    settings: {},
    status: {},
    editor: null,
    monaco: null,
    files: new Map(),          // path -> { model, viewState, dirty, mtime, language }
    activePath: null,
    tabOrder: [],
    ui: JSON.parse(localStorage.getItem('forge.ui') || '{}'),
    hooks: { onSelectionChanged: [], onActiveFileChanged: [] },
  };
  const saveUi = () => localStorage.setItem('forge.ui', JSON.stringify(Forge.ui));

  // ------------------------------------------------------------------ layout
  function applyLayout() {
    const main = $('#main');
    main.classList.toggle('no-sidebar', Forge.ui.sidebar === false);
    main.classList.toggle('no-chat', Forge.ui.chat === false);
    $('#editor-col').classList.toggle('no-panel', Forge.ui.panel === false);
    document.documentElement.style.setProperty('--sidebar-w', (Forge.ui.sidebarW || 260) + 'px');
    document.documentElement.style.setProperty('--chat-w', (Forge.ui.chatW || 440) + 'px');
    document.documentElement.style.setProperty('--panel-h', (Forge.ui.panelH || 240) + 'px');
    $('#btn-toggle-sidebar').classList.toggle('active', Forge.ui.sidebar !== false);
    $('#btn-toggle-chat').classList.toggle('active', Forge.ui.chat !== false);
    $('#btn-toggle-terminal').classList.toggle('active', Forge.ui.panel !== false);
    if (Forge.editor) Forge.editor.layout();
    if (window.ForgeTerm) window.ForgeTerm.fitAll();
  }
  Forge.toggle = (what) => { Forge.ui[what] = Forge.ui[what] === false; saveUi(); applyLayout(); };
  $('#btn-toggle-sidebar').onclick = () => Forge.toggle('sidebar');
  $('#btn-toggle-chat').onclick = () => Forge.toggle('chat');
  $('#btn-toggle-terminal').onclick = () => Forge.toggle('panel');
  $('#btn-panel-close').onclick = () => { Forge.ui.panel = false; saveUi(); applyLayout(); };

  // Drag gutters
  for (const g of $$('.gutter')) {
    g.addEventListener('mousedown', (e) => {
      e.preventDefault();
      const target = g.dataset.target;
      const horizontal = g.classList.contains('horizontal');
      const start = horizontal ? e.clientY : e.clientX;
      const startVal = target === 'sidebar' ? (Forge.ui.sidebarW || 260) : target === 'chat' ? (Forge.ui.chatW || 440) : (Forge.ui.panelH || 240);
      g.classList.add('dragging');
      const move = (ev) => {
        const d = (horizontal ? ev.clientY : ev.clientX) - start;
        let v;
        if (target === 'sidebar') v = Math.max(160, Math.min(600, startVal + d)), Forge.ui.sidebarW = v;
        else if (target === 'chat') v = Math.max(300, Math.min(900, startVal - d)), Forge.ui.chatW = v;
        else v = Math.max(80, Math.min(window.innerHeight - 200, startVal - d)), Forge.ui.panelH = v;
        applyLayout();
      };
      const up = () => { g.classList.remove('dragging'); window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up); saveUi(); };
      window.addEventListener('mousemove', move); window.addEventListener('mouseup', up);
    });
  }

  // Activity bar views
  function showView(name) {
    if (Forge.ui.sidebar === false) { Forge.ui.sidebar = true; saveUi(); applyLayout(); }
    for (const b of $$('.act')) b.classList.toggle('active', b.dataset.view === name);
    for (const v of $$('.sidebar .view')) v.classList.toggle('active', v.dataset.view === name);
    Forge.ui.view = name; saveUi();
    if (name === 'changes') loadChanges();
    if (name === 'history' && window.ForgeChat) window.ForgeChat.loadHistory($('#history'));
    if (name === 'settings') renderSettings();
    if (name === 'search') setTimeout(() => $('#search-input').focus(), 0);
    if (name === 'git' && window.ForgeExtras) window.ForgeExtras.loadGit();
    if (name === 'notepads' && window.ForgeExtras) window.ForgeExtras.renderNotepads();
  }
  Forge.showView = showView;
  for (const b of $$('.act')) b.onclick = () => showView(b.dataset.view);

  // Bottom panel tabs
  for (const b of $$('.ptab')) b.onclick = () => showPanel(b.dataset.panel);
  function showPanel(name) {
    if (Forge.ui.panel === false) { Forge.ui.panel = true; saveUi(); applyLayout(); }
    for (const b of $$('.ptab')) b.classList.toggle('active', b.dataset.panel === name);
    for (const v of $$('.pview')) v.classList.toggle('active', v.dataset.panel === name);
    if (name === 'terminal' && window.ForgeTerm) window.ForgeTerm.ensure();
  }
  Forge.showPanel = showPanel;

  // ------------------------------------------------------------------ file icons / languages
  const EXT_LANG = { py: 'python', js: 'javascript', mjs: 'javascript', cjs: 'javascript', ts: 'typescript', tsx: 'typescript', jsx: 'javascript', json: 'json', html: 'html', htm: 'html', css: 'css', scss: 'scss', less: 'less', md: 'markdown', sh: 'shell', bash: 'shell', zsh: 'shell', yml: 'yaml', yaml: 'yaml', toml: 'ini', ini: 'ini', cfg: 'ini', xml: 'xml', svg: 'xml', java: 'java', kt: 'kotlin', kts: 'kotlin', go: 'go', rs: 'rust', c: 'c', h: 'c', cc: 'cpp', cpp: 'cpp', hpp: 'cpp', cs: 'csharp', rb: 'ruby', php: 'php', sql: 'sql', swift: 'swift', dart: 'dart', lua: 'lua', r: 'r', pl: 'perl', ps1: 'powershell', dockerfile: 'dockerfile', gradle: 'groovy', txt: 'plaintext', log: 'plaintext', bat: 'bat', makefile: 'makefile', vue: 'html' };
  const ICON = { python: ['PY', '#3b82f6'], javascript: ['JS', '#eab308'], typescript: ['TS', '#3b82f6'], json: ['{}', '#eab308'], html: ['<>', '#f97316'], css: ['#', '#8b5cf6'], markdown: ['M', '#60a5fa'], shell: ['$', '#22c55e'], yaml: ['Y', '#ef4444'], java: ['J', '#f97316'], kotlin: ['K', '#a855f7'], go: ['GO', '#06b6d4'], rust: ['RS', '#f97316'], c: ['C', '#60a5fa'], cpp: ['C+', '#60a5fa'], plaintext: ['≡', '#94a3b8'] };
  function langFor(path) {
    const base = path.split('/').pop().toLowerCase();
    if (base === 'dockerfile') return 'dockerfile';
    if (base === 'makefile') return 'makefile';
    const ext = base.includes('.') ? base.split('.').pop() : '';
    return EXT_LANG[ext] || 'plaintext';
  }
  Forge.langFor = langFor;
  function fileIcon(path, isDir) {
    if (isDir) return el('span', { class: 'ficon', text: '▸', style: 'color:var(--fg-3)' });
    const [t, c] = ICON[langFor(path)] || ['·', '#94a3b8'];
    return el('span', { class: 'ficon', text: t, style: 'color:' + c });
  }
  Forge.fileIcon = fileIcon;

  // ------------------------------------------------------------------ Monaco
  function initMonaco() {
    return new Promise((resolve) => {
      window.require(['vs/editor/editor.main'], () => {
        const monaco = window.monaco;
        Forge.monaco = monaco;
        monaco.editor.defineTheme('forge-dark', {
          base: 'vs-dark', inherit: true,
          rules: [
            { token: 'comment', foreground: '6b7489', fontStyle: 'italic' },
            { token: 'keyword', foreground: 'c792ea' },
            { token: 'string', foreground: 'c3e88d' },
            { token: 'number', foreground: 'f78c6c' },
            { token: 'type', foreground: 'ffcb6b' },
            { token: 'function', foreground: '82aaff' },
            { token: 'variable', foreground: 'd7dbe7' },
            { token: 'tag', foreground: 'ff8a65' },
            { token: 'attribute.name', foreground: 'ffcb6b' },
          ],
          colors: {
            'editor.background': '#0f1117', 'editor.foreground': '#d7dbe7', 'editorLineNumber.foreground': '#3b4257',
            'editorLineNumber.activeForeground': '#9aa3bd', 'editor.lineHighlightBackground': '#161a25', 'editorCursor.foreground': '#ff8a3d',
            'editor.selectionBackground': '#2b3550', 'editor.inactiveSelectionBackground': '#20273a', 'editorIndentGuide.background1': '#1f2432',
            'editorWidget.background': '#141720', 'editorSuggestWidget.background': '#141720', 'editorSuggestWidget.selectedBackground': '#2a2f45',
            'minimap.background': '#0f1117', 'scrollbarSlider.background': '#2c324355', 'editorGutter.background': '#0f1117',
            'diffEditor.insertedTextBackground': '#1f6f4a55', 'diffEditor.removedTextBackground': '#8a2b2b55',
          },
        });
        const editor = monaco.editor.create($('#editor'), {
          theme: 'forge-dark', automaticLayout: true, fontSize: Forge.settings.font_size || 13, fontFamily: 'JetBrains Mono, Fira Code, Cascadia Code, Menlo, Consolas, monospace',
          fontLigatures: true, minimap: { enabled: true, scale: 1 }, smoothScrolling: true, cursorBlinking: 'smooth', cursorSmoothCaretAnimation: 'on',
          renderWhitespace: 'selection', bracketPairColorization: { enabled: true }, scrollBeyondLastLine: false, wordWrap: 'off', tabSize: 4,
          padding: { top: 8 }, inlineSuggest: { enabled: true, showToolbar: 'onHover' }, quickSuggestions: true, suggest: { preview: true },
          stickyScroll: { enabled: true }, lineNumbersMinChars: 4, model: null,
        });
        Forge.editor = editor;
        editor.onDidChangeCursorPosition((e) => {
          $('#sb-pos').textContent = 'Ln ' + e.position.lineNumber + ', Col ' + e.position.column;
        });
        editor.onDidChangeCursorSelection(() => { for (const h of Forge.hooks.onSelectionChanged) h(); });
        // Keybindings inside the editor
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveActive());
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyK, () => openInlineEdit());
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyL, () => window.ForgeChat && window.ForgeChat.addSelectionAndFocus());
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyI, () => Forge.toggle('chat'));
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyP, () => openPalette(''));
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyP, () => openPalette('>'));
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyW, () => Forge.activePath && closeFile(Forge.activePath));
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Backquote, () => Forge.toggle('panel'));
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyB, () => Forge.toggle('sidebar'));
        editor.addCommand(monaco.KeyMod.Alt | monaco.KeyCode.Backslash, () => triggerCompletion(true));
        editor.addAction({ id: 'forge.addToChat', label: 'Forge: Add selection to chat', contextMenuGroupId: 'navigation', contextMenuOrder: 0, keybindings: [], run: () => window.ForgeChat && window.ForgeChat.addSelectionAndFocus() });
        editor.addAction({ id: 'forge.inlineEdit', label: 'Forge: Edit with AI (Ctrl+K)', contextMenuGroupId: 'navigation', contextMenuOrder: 1, run: () => openInlineEdit() });
        editor.addAction({ id: 'forge.explain', label: 'Forge: Explain selection', contextMenuGroupId: 'navigation', contextMenuOrder: 2, run: () => window.ForgeChat && window.ForgeChat.quickAsk('Explain this code clearly.') });
        editor.addAction({ id: 'forge.fix', label: 'Forge: Fix problems in selection', contextMenuGroupId: 'navigation', contextMenuOrder: 3, run: () => window.ForgeChat && window.ForgeChat.quickAsk('Find and fix any bugs or problems in this code. Edit the file directly.', 'agent') });
        registerCompletions();
        resolve(editor);
      });
    });
  }

  // ------------------------------------------------------------------ files and tabs
  async function openFile(path, opts) {
    opts = opts || {};
    const monaco = Forge.monaco;
    let f = Forge.files.get(path);
    if (!f) {
      let data;
      try { data = await get('/api/file', { path }); } catch (e) { toast('Cannot open ' + path + ': ' + e.message, 'error'); return null; }
      const lang = langFor(path);
      const model = monaco.editor.createModel(data.content, lang, monaco.Uri.file('/' + path));
      f = { path, model, viewState: null, dirty: false, mtime: data.mtime, language: lang, savedVersion: model.getAlternativeVersionId() };
      model.onDidChangeContent(() => {
        const d = model.getAlternativeVersionId() !== f.savedVersion;
        if (d !== f.dirty) { f.dirty = d; renderTabs(); }
      });
      Forge.files.set(path, f);
      Forge.tabOrder.push(path);
    }
    if (!opts.background) activate(path);
    if (opts.line) {
      Forge.editor.revealLineInCenter(opts.line);
      Forge.editor.setPosition({ lineNumber: opts.line, column: opts.column || 1 });
      Forge.editor.focus();
    }
    return f;
  }
  function activate(path) {
    const cur = Forge.activePath && Forge.files.get(Forge.activePath);
    if (cur) cur.viewState = Forge.editor.saveViewState();
    const f = Forge.files.get(path);
    if (!f) return;
    Forge.activePath = path;
    Forge.editor.setModel(f.model);
    if (f.viewState) Forge.editor.restoreViewState(f.viewState);
    $('#welcome').classList.add('hidden');
    $('#sb-lang').textContent = f.language;
    renderTabs();
    renderBreadcrumbs(path);
    highlightTree(path);
    Forge.editor.focus();
    for (const h of Forge.hooks.onActiveFileChanged) h(path);
  }
  function renderTabs() {
    const host = $('#tabs');
    host.innerHTML = '';
    for (const p of Forge.tabOrder) {
      const f = Forge.files.get(p);
      if (!f) continue;
      const t = el('div', { class: 'tab' + (p === Forge.activePath ? ' active' : '') + (f.dirty ? ' dirty' : ''), title: p, onclick: () => activate(p), onauxclick: (e) => { if (e.button === 1) closeFile(p); } },
        fileIcon(p), el('span', { text: p.split('/').pop() }),
        el('span', { class: 'tclose', onclick: (e) => { e.stopPropagation(); closeFile(p); } }));
      t.addEventListener('contextmenu', (e) => { e.preventDefault(); contextMenu(e, [
        { label: 'Close', run: () => closeFile(p) }, { label: 'Close others', run: () => { for (const q of [...Forge.tabOrder]) if (q !== p) closeFile(q); } },
        { label: 'Close all', run: () => { for (const q of [...Forge.tabOrder]) closeFile(q); } }, { label: 'Copy path', run: () => navigator.clipboard.writeText(p) },
        { label: 'Add to chat', run: () => window.ForgeChat && window.ForgeChat.addFileContext(p) }, { label: 'Reveal in explorer', run: () => revealInTree(p) }]); });
      host.append(t);
    }
  }
  function renderBreadcrumbs(path) {
    const b = $('#breadcrumbs');
    b.innerHTML = '';
    const parts = path.split('/');
    parts.forEach((part, i) => {
      if (i) b.append(el('span', { class: 'muted', text: '›' }));
      b.append(el('span', { text: part }));
    });
  }
  async function closeFile(path) {
    const f = Forge.files.get(path);
    if (!f) return;
    if (f.dirty && !confirm('Close ' + path + ' without saving?')) return;
    f.model.dispose();
    Forge.files.delete(path);
    Forge.tabOrder = Forge.tabOrder.filter((p) => p !== path);
    if (Forge.activePath === path) {
      Forge.activePath = null;
      const next = Forge.tabOrder[Forge.tabOrder.length - 1];
      if (next) activate(next);
      else { Forge.editor.setModel(null); $('#welcome').classList.remove('hidden'); $('#breadcrumbs').innerHTML = ''; $('#sb-lang').textContent = ''; }
    }
    renderTabs();
  }
  async function saveActive() {
    const f = Forge.activePath && Forge.files.get(Forge.activePath);
    if (!f) return;
    try {
      const r = await post('/api/file', { path: f.path, content: f.model.getValue() });
      f.savedVersion = f.model.getAlternativeVersionId(); f.dirty = false; f.mtime = r.mtime;
      renderTabs();
      if (window.ForgeExtras) window.ForgeExtras.reportCheck(f.path, r.check || '');
      toast('Saved ' + f.path, 'ok', 1200);
    } catch (e) { toast('Save failed: ' + e.message, 'error'); }
  }
  async function saveAll() { for (const f of Forge.files.values()) if (f.dirty) { await post('/api/file', { path: f.path, content: f.model.getValue() }); f.savedVersion = f.model.getAlternativeVersionId(); f.dirty = false; } renderTabs(); }
  /** Reload a file from disk when the agent changed it (keeps unsaved local edits if dirty). */
  async function refreshFile(path) {
    const f = Forge.files.get(path);
    if (!f) return;
    try {
      const data = await get('/api/file', { path });
      if (data.content !== f.model.getValue()) {
        if (f.dirty) { toast(path + ' changed on disk and has unsaved edits; keeping yours.', 'error'); return; }
        const vs = Forge.editor.saveViewState();
        f.model.setValue(data.content);
        f.savedVersion = f.model.getAlternativeVersionId(); f.dirty = false; f.mtime = data.mtime;
        if (Forge.activePath === path && vs) Forge.editor.restoreViewState(vs);
        renderTabs();
      }
    } catch (e) { /* file may have been removed */ }
  }
  Object.assign(Forge, { openFile, closeFile, saveActive, saveAll, refreshFile, activate });

  // ------------------------------------------------------------------ explorer tree
  const treeState = { open: new Set(JSON.parse(localStorage.getItem('forge.treeOpen') || '[]')) };
  function saveTree() { localStorage.setItem('forge.treeOpen', JSON.stringify([...treeState.open])); }
  async function loadTree() {
    const host = $('#tree');
    host.innerHTML = '';
    await renderDir(host, '.', 0);
  }
  async function renderDir(host, path, depth) {
    let data;
    try { data = await get('/api/tree', { path }); } catch (e) { host.append(el('div', { class: 'muted pad', text: e.message })); return; }
    for (const it of data.items) {
      const row = el('div', { class: 'node' + (it.dir ? ' dir' : ''), style: 'padding-left:' + (8 + depth * 14) + 'px', 'data-path': it.path, title: it.path });
      if (it.dir) {
        const chev = el('svg', { class: 'chev' });
        chev.innerHTML = '<path d="M5 3l5 5-5 5" />';
        chev.setAttribute('viewBox', '0 0 16 16');
        row.append(chev, fileIcon(it.path, true), el('span', { class: 'fname', text: it.name }));
        const kids = el('div', { class: 'children' });
        const toggle = async () => {
          const open = !row.classList.contains('open');
          row.classList.toggle('open', open); kids.classList.toggle('open', open);
          if (open) { treeState.open.add(it.path); if (!kids.dataset.loaded) { kids.dataset.loaded = '1'; await renderDir(kids, it.path, depth + 1); } }
          else treeState.open.delete(it.path);
          saveTree();
        };
        row.onclick = toggle;
        row.addEventListener('contextmenu', (e) => { e.preventDefault(); dirMenu(e, it.path); });
        host.append(row, kids);
        if (treeState.open.has(it.path)) toggle();
      } else {
        row.append(el('span', { class: 'chev' }), fileIcon(it.path), el('span', { class: 'fname', text: it.name }));
        row.onclick = () => openFile(it.path);
        row.addEventListener('contextmenu', (e) => { e.preventDefault(); fileMenu(e, it.path); });
        host.append(row);
      }
    }
  }
  function highlightTree(path) { for (const n of $$('#tree .node')) n.classList.toggle('selected', n.dataset.path === path); }
  function revealInTree(path) { showView('explorer'); const n = $$('#tree .node').find((x) => x.dataset.path === path); if (n) n.scrollIntoView({ block: 'center' }); highlightTree(path); }
  $('#btn-refresh-tree').onclick = loadTree;
  $('#btn-collapse-tree').onclick = () => { treeState.open.clear(); saveTree(); loadTree(); };
  $('#btn-new-file').onclick = () => newFilePrompt('');
  $('#btn-new-folder').onclick = () => newFolderPrompt('');
  $('#welcome-newfile').onclick = () => newFilePrompt('');
  async function newFilePrompt(dir) {
    const name = prompt('New file path' + (dir ? ' inside ' + dir : '') + ':');
    if (!name) return;
    const path = dir ? dir + '/' + name : name;
    try { const r = await post('/api/file/new', { path }); await loadTree(); openFile(r.path); } catch (e) { toast(e.message, 'error'); }
  }
  async function newFolderPrompt(dir) {
    const name = prompt('New folder name' + (dir ? ' inside ' + dir : '') + ':');
    if (!name) return;
    try { await post('/api/file/new', { path: dir ? dir + '/' + name : name, dir: true }); await loadTree(); } catch (e) { toast(e.message, 'error'); }
  }
  function fileMenu(e, path) {
    contextMenu(e, [
      { label: 'Open', run: () => openFile(path) },
      { label: 'Add to chat', run: () => window.ForgeChat && window.ForgeChat.addFileContext(path) },
      { label: 'Rename', run: () => renamePrompt(path) },
      { label: 'Copy path', run: () => navigator.clipboard.writeText(path) },
      { label: 'Move to trash', run: () => trashPath(path) },
    ]);
  }
  function dirMenu(e, path) {
    contextMenu(e, [
      { label: 'New file', run: () => newFilePrompt(path) },
      { label: 'New folder', run: () => newFolderPrompt(path) },
      { label: 'Add folder listing to chat', run: () => window.ForgeChat && window.ForgeChat.insertText('@' + path + '/ ') },
      { label: 'Rename', run: () => renamePrompt(path) },
      { label: 'Copy path', run: () => navigator.clipboard.writeText(path) },
      { label: 'Move to trash', run: () => trashPath(path) },
    ]);
  }
  async function renamePrompt(path) {
    const np = prompt('Rename / move to:', path);
    if (!np || np === path) return;
    try {
      await post('/api/file/rename', { path, new_path: np });
      const f = Forge.files.get(path);
      if (f) { await closeFile(path); openFile(np); }
      loadTree();
    } catch (e) { toast(e.message, 'error'); }
  }
  async function trashPath(path) {
    if (!confirm('Move ' + path + ' to the workspace trash folder (.forge-trash)?')) return;
    try { const r = await post('/api/file/trash', { path }); toast(r.message, 'ok'); if (Forge.files.has(path)) { const f = Forge.files.get(path); f.dirty = false; closeFile(path); } loadTree(); } catch (e) { toast(e.message, 'error'); }
  }
  let menuEl = null;
  function contextMenu(e, items) {
    closeMenu();
    menuEl = el('div', { class: 'mention-menu', style: 'position:fixed;left:' + e.clientX + 'px;top:' + e.clientY + 'px;right:auto;bottom:auto;min-width:180px;max-height:none' });
    for (const it of items) menuEl.append(el('div', { class: 'mi', text: it.label, onclick: () => { closeMenu(); it.run(); } }));
    document.body.append(menuEl);
    const r = menuEl.getBoundingClientRect();
    if (r.bottom > window.innerHeight) menuEl.style.top = (window.innerHeight - r.height - 8) + 'px';
    if (r.right > window.innerWidth) menuEl.style.left = (window.innerWidth - r.width - 8) + 'px';
    setTimeout(() => window.addEventListener('mousedown', closeMenuOnce), 0);
  }
  function closeMenuOnce(ev) { if (menuEl && !menuEl.contains(ev.target)) closeMenu(); }
  function closeMenu() { if (menuEl) { menuEl.remove(); menuEl = null; window.removeEventListener('mousedown', closeMenuOnce); } }
  Forge.contextMenu = contextMenu;
  Forge.loadTree = loadTree;

  // ------------------------------------------------------------------ search view
  $('#search-input').addEventListener('keydown', (e) => { if (e.key === 'Enter') runSearch(); });
  $('#search-glob').addEventListener('keydown', (e) => { if (e.key === 'Enter') runSearch(); });
  async function runSearch() {
    const q = $('#search-input').value.trim();
    const host = $('#search-results');
    if (!q) return;
    host.innerHTML = '<div class="muted pad">Searching…</div>';
    try {
      const r = await get('/api/search', { q, glob: $('#search-glob').value.trim(), regex: $('#search-regex').checked ? '1' : '0' });
      host.innerHTML = '';
      const lines = r.result.split('\n');
      let count = 0, lastFile = null;
      for (const l of lines) {
        const m = l.match(/^(.+?):(\d+):(.*)$/);
        if (!m) { if (count === 0 && l) host.append(el('div', { class: 'muted pad', text: l })); continue; }
        count++;
        if (m[1] !== lastFile) { lastFile = m[1]; host.append(el('div', { class: 'sr-file', text: m[1] })); }
        const line = Number(m[2]);
        host.append(el('div', { class: 'sr-line', title: m[3].trim(), onclick: () => openFile(m[1], { line }) }, el('span', { class: 'ln', text: m[2] }), el('span', { text: m[3].trim() })));
      }
      if (count) host.prepend(el('div', { class: 'muted pad small', text: count + ' results' }));
    } catch (e) { host.innerHTML = '<div class="muted pad">' + esc(e.message) + '</div>'; }
  }

  // ------------------------------------------------------------------ changes view (checkpoints)
  async function loadChanges() {
    const host = $('#changes');
    try {
      const r = await get('/api/changes');
      host.innerHTML = '';
      if (!r.changes.length) { host.innerHTML = '<div class="muted pad">No AI changes yet. When the agent edits files you can review each diff here, keep it or revert it.</div>'; return; }
      const byRun = new Map();
      for (const c of r.changes) { if (!byRun.has(c.run_id)) byRun.set(c.run_id, []); byRun.get(c.run_id).push(c); }
      for (const [rid, list] of byRun) {
        const head = el('div', { class: 'view-head' }, el('span', { text: 'Run ' + rid + ' · ' + new Date(list[0].ts * 1000).toLocaleTimeString() }),
          el('button', { class: 'btn ghost sm', text: 'Revert all', onclick: async () => { if (!confirm('Revert every file this run changed?')) return; await post('/api/revert_all', { run_id: rid }); for (const c of list) refreshFile(c.path); loadChanges(); loadTree(); } }));
        host.append(head);
        for (const c of list) {
          const card = el('div', { class: 'change' },
            el('div', { class: 'cpath', onclick: () => openFile(c.path) }, fileIcon(c.path), el('span', { text: c.path }), el('span', { class: 'badge ' + c.kind, text: c.kind })),
            el('div', { class: 'cactions' },
              el('button', { class: 'btn ghost sm', text: 'Diff', onclick: () => openDiff({ title: c.path, original: c.before || '', modified: c.after || '', language: langFor(c.path), acceptLabel: 'Keep', rejectLabel: 'Revert', onReject: () => revertChange(c) }) }),
              c.kind !== 'reverted' ? el('button', { class: 'btn ghost sm', text: 'Revert', onclick: () => revertChange(c) }) : null));
          host.append(card);
        }
      }
    } catch (e) { host.innerHTML = '<div class="muted pad">' + esc(e.message) + '</div>'; }
  }
  async function revertChange(c) {
    try { const r = await post('/api/revert', { run_id: c.run_id, path: c.path }); toast(r.message, 'ok'); refreshFile(c.path); loadChanges(); loadTree(); } catch (e) { toast(e.message, 'error'); }
  }
  $('#btn-refresh-changes').onclick = loadChanges;
  Forge.loadChanges = loadChanges;

  // ------------------------------------------------------------------ diff modal
  let diffEditor = null, diffResolve = null;
  function openDiff(o) {
    const monaco = Forge.monaco;
    $('#diff-modal').classList.remove('hidden');
    $('#diff-title').textContent = o.title || 'Review changes';
    $('#diff-accept').innerHTML = esc(o.acceptLabel || 'Accept') + ' <kbd>Ctrl ↵</kbd>';
    $('#diff-reject').textContent = o.rejectLabel || 'Reject';
    if (!diffEditor) diffEditor = monaco.editor.createDiffEditor($('#diff-editor'), { theme: 'forge-dark', automaticLayout: true, renderSideBySide: window.innerWidth > 1100, readOnly: false, originalEditable: false, fontSize: Forge.settings.font_size || 13, minimap: { enabled: false } });
    const orig = monaco.editor.createModel(o.original, o.language || 'plaintext');
    const mod = monaco.editor.createModel(o.modified, o.language || 'plaintext');
    diffEditor.setModel({ original: orig, modified: mod });
    const done = (accepted) => {
      const text = mod.getValue();
      $('#diff-modal').classList.add('hidden');
      orig.dispose(); mod.dispose(); diffEditor.setModel(null);
      window.removeEventListener('keydown', key, true);
      if (accepted && o.onAccept) o.onAccept(text);
      if (!accepted && o.onReject) o.onReject();
      if (diffResolve) { diffResolve(accepted ? text : null); diffResolve = null; }
    };
    const key = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); done(false); }
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); done(true); }
    };
    window.addEventListener('keydown', key, true);
    $('#diff-accept').onclick = () => done(true);
    $('#diff-reject').onclick = () => done(false);
    return new Promise((res) => { diffResolve = res; });
  }
  Forge.openDiff = openDiff;

  // ------------------------------------------------------------------ Ctrl+K inline edit
  let inlineRun = null;
  function openInlineEdit() {
    const ed = Forge.editor;
    if (!Forge.activePath) { toast('Open a file first, then select code and press Ctrl+K.'); return; }
    const w = $('#inline-edit');
    w.classList.remove('hidden');
    $('#inline-edit-status').textContent = ed.getSelection().isEmpty() ? 'No selection: the whole file will be edited.' : 'Selected lines ' + ed.getSelection().startLineNumber + ' to ' + ed.getSelection().endLineNumber + '.';
    const inp = $('#inline-edit-input');
    inp.value = ''; inp.focus();
  }
  $('#inline-edit-close').onclick = closeInlineEdit;
  function closeInlineEdit() { $('#inline-edit').classList.add('hidden'); if (inlineRun) { post('/api/cancel', { run_id: inlineRun }); inlineRun = null; } Forge.editor && Forge.editor.focus(); }
  $('#inline-edit-input').addEventListener('keydown', async (e) => {
    if (e.key === 'Escape') { closeInlineEdit(); return; }
    if (e.key !== 'Enter') return;
    const instruction = e.target.value.trim();
    if (!instruction) return;
    const ed = Forge.editor, model = ed.getModel(), f = Forge.files.get(Forge.activePath);
    let sel = ed.getSelection();
    if (sel.isEmpty()) sel = model.getFullModelRange();
    else sel = new Forge.monaco.Selection(sel.startLineNumber, 1, sel.endLineNumber, model.getLineMaxColumn(sel.endLineNumber));
    const selection = model.getValueInRange(sel);
    const before = model.getValueInRange(new Forge.monaco.Range(1, 1, sel.startLineNumber, 1));
    const after = model.getValueInRange(new Forge.monaco.Range(sel.endLineNumber, model.getLineMaxColumn(sel.endLineNumber), model.getLineCount(), model.getLineMaxColumn(model.getLineCount())));
    const status = $('#inline-edit-status');
    status.textContent = 'Generating…';
    e.target.disabled = true;
    try {
      const r = await post('/api/inline_edit', { session_id: window.ForgeChat ? window.ForgeChat.sessionId() : '', path: f.path, language: f.language, selection, instruction, before, after });
      inlineRun = r.run_id;
      let chars = 0;
      const code = await new Promise((resolve, reject) => {
        const es = new EventSource('/api/events?run_id=' + r.run_id);
        let final = null;
        es.onmessage = (m) => {
          const ev = JSON.parse(m.data);
          if (ev.type === 'code_delta') { chars += ev.text.length; status.textContent = 'Generating… ' + chars + ' characters'; }
          else if (ev.type === 'code') final = ev.text;
          else if (ev.type === 'stats' && ev.gen_tps) status.textContent = 'Done · ' + ev.gen_tps + ' tok/s';
          else if (ev.type === 'error') { es.close(); reject(new Error(ev.message)); }
          else if (ev.type === 'end') { es.close(); resolve(final); }
        };
        es.onerror = () => { es.close(); reject(new Error('stream closed')); };
      });
      inlineRun = null;
      e.target.disabled = false;
      if (code === null) { status.textContent = 'Cancelled.'; return; }
      $('#inline-edit').classList.add('hidden');
      const accepted = await openDiff({ title: 'Ctrl+K: ' + instruction, original: selection, modified: code, language: f.language });
      if (accepted !== null) {
        ed.executeEdits('forge-inline', [{ range: sel, text: accepted, forceMoveMarkers: true }]);
        toast('Applied. Save with Ctrl+S.', 'ok', 1500);
      }
      ed.focus();
    } catch (err) {
      e.target.disabled = false; inlineRun = null;
      status.textContent = 'Error: ' + err.message;
    }
  });

  // ------------------------------------------------------------------ Tab completions (opt-in)
  let completionSeq = 0;
  function registerCompletions() {
    const monaco = Forge.monaco;
    monaco.languages.registerInlineCompletionsProvider({ pattern: '**' }, {
      provideInlineCompletions: async (model, position, context, token) => {
        if (!Forge.settings.tab_completions && !manualTrigger) return { items: [] };
        manualTrigger = false;
        const seq = ++completionSeq;
        const delay = Number(Forge.settings.completion_delay_ms || 1200);
        if (Forge.settings.tab_completions) await new Promise((r) => setTimeout(r, delay));
        if (token.isCancellationRequested || seq !== completionSeq) return { items: [] };
        const prefix = model.getValueInRange(new monaco.Range(1, 1, position.lineNumber, position.column));
        const suffix = model.getValueInRange(new monaco.Range(position.lineNumber, position.column, model.getLineCount(), model.getLineMaxColumn(model.getLineCount())));
        if (prefix.trim().length < 3) return { items: [] };
        try {
          $('#sb-tab').textContent = 'Tab: …';
          const r = await post('/api/complete', { path: Forge.activePath || '', language: model.getLanguageId(), prefix, suffix });
          $('#sb-tab').textContent = 'Tab: ' + (Forge.settings.tab_completions ? 'on' : 'off');
          if (token.isCancellationRequested || seq !== completionSeq || !r.text) return { items: [] };
          return { items: [{ insertText: r.text, range: new monaco.Range(position.lineNumber, position.column, position.lineNumber, position.column) }] };
        } catch (e) { $('#sb-tab').textContent = 'Tab: ' + (Forge.settings.tab_completions ? 'on' : 'off'); return { items: [] }; }
      },
      freeInlineCompletions: () => {},
    });
  }
  let manualTrigger = false;
  function triggerCompletion(manual) { manualTrigger = !!manual; Forge.editor.trigger('forge', 'editor.action.inlineSuggest.trigger', {}); }
  $('#sb-tab').onclick = () => { Forge.settings.tab_completions = !Forge.settings.tab_completions; post('/api/settings', { tab_completions: Forge.settings.tab_completions }); updateStatusBar(); };

  // ------------------------------------------------------------------ palette / quick open
  const COMMANDS = [
    { label: 'New chat', hint: 'Ctrl N', run: () => window.ForgeChat && window.ForgeChat.newChat() },
    { label: 'Toggle AI panel', hint: 'Ctrl I', run: () => Forge.toggle('chat') },
    { label: 'Toggle sidebar', hint: 'Ctrl B', run: () => Forge.toggle('sidebar') },
    { label: 'Toggle terminal', hint: 'Ctrl `', run: () => Forge.toggle('panel') },
    { label: 'New terminal', run: () => { showPanel('terminal'); window.ForgeTerm && window.ForgeTerm.create(); } },
    { label: 'Open folder', hint: 'Ctrl O', run: () => openFolderModal() },
    { label: 'New file', run: () => newFilePrompt('') },
    { label: 'Save file', hint: 'Ctrl S', run: () => saveActive() },
    { label: 'Save all files', run: () => saveAll() },
    { label: 'Close file', hint: 'Ctrl W', run: () => Forge.activePath && closeFile(Forge.activePath) },
    { label: 'Edit selection with AI', hint: 'Ctrl K', run: () => openInlineEdit() },
    { label: 'Add selection to chat', hint: 'Ctrl L', run: () => window.ForgeChat && window.ForgeChat.addSelectionAndFocus() },
    { label: 'Search in files', hint: 'Ctrl Shift F', run: () => showView('search') },
    { label: 'Show AI changes', hint: 'Ctrl Shift G', run: () => showView('changes') },
    { label: 'Show chat history', hint: 'Ctrl Shift H', run: () => showView('history') },
    { label: 'Settings', hint: 'Ctrl ,', run: () => showView('settings') },
    { label: 'Toggle verbose mode', run: () => window.ForgeChat && window.ForgeChat.toggleVerbose() },
    { label: 'Toggle thinking display', run: () => { const c = $('#thinking-toggle'); c.checked = !c.checked; c.dispatchEvent(new Event('change')); } },
    { label: 'Toggle Tab completions', run: () => $('#sb-tab').click() },
    { label: 'Trigger completion now', hint: 'Alt \\', run: () => triggerCompletion(true) },
    { label: 'Format document', hint: 'Shift Alt F', run: () => Forge.editor.getAction('editor.action.formatDocument').run() },
    { label: 'Go to line', hint: 'Ctrl G', run: () => Forge.editor.getAction('editor.action.gotoLine').run() },
    { label: 'Refresh explorer', run: () => loadTree() },
    { label: 'Reset conversation context (keep transcript)', run: () => window.ForgeChat && window.ForgeChat.resetContext() },
    { label: 'Warm up the model now', run: () => window.ForgeChat && window.ForgeChat.prewarm() },
    { label: 'Unload the model from memory (frees about 5 GB)', run: async () => { await post('/api/unload', {}); toast('Model unloaded. It loads again on the next message.', 'ok'); refreshStatus(); } },
    { label: 'Open Agent log', run: () => showPanel('output') },
    { label: 'Source control', run: () => showView('git') },
    { label: 'Generate commit message with AI', run: () => { showView('git'); window.ForgeExtras && window.ForgeExtras.generateCommitMessage(); } },
    { label: 'Notepads', run: () => showView('notepads') },
    { label: 'Edit rules for the AI', run: () => showView('settings') },
    { label: 'Problems', run: () => showPanel('problems') },
    { label: 'Terminal: ask AI for a command', hint: 'Ctrl K in terminal', run: () => { showPanel('terminal'); window.ForgeExtras && window.ForgeExtras.openTerminalAI(); } },
    { label: 'Chat with @codebase context', run: () => { window.ForgeChat && window.ForgeChat.insertText('@codebase '); } },
    { label: 'Chat with @web context', run: () => { window.ForgeChat && window.ForgeChat.insertText('@web '); } },
  ];
  let palItems = [], palSel = 0;
  function openPalette(prefix) {
    $('#palette').classList.remove('hidden');
    const inp = $('#palette-input');
    inp.value = prefix || '';
    inp.focus();
    updatePalette();
  }
  function closePalette() { $('#palette').classList.add('hidden'); }
  $('#btn-palette').onclick = () => openPalette('');
  $('#palette').addEventListener('mousedown', (e) => { if (e.target === e.currentTarget) closePalette(); });
  const updatePalette = debounce(async () => {
    const q = $('#palette-input').value;
    const list = $('#palette-list');
    if (q.startsWith('>')) {
      const needle = q.slice(1).trim().toLowerCase();
      palItems = COMMANDS.filter((c) => !needle || c.label.toLowerCase().includes(needle)).map((c) => ({ label: c.label, hint: c.hint || '', run: c.run }));
    } else if (q.startsWith(':')) {
      const n = parseInt(q.slice(1), 10);
      palItems = isNaN(n) ? [] : [{ label: 'Go to line ' + n, hint: '', run: () => { Forge.editor.revealLineInCenter(n); Forge.editor.setPosition({ lineNumber: n, column: 1 }); Forge.editor.focus(); } }];
    } else {
      try {
        const r = await get('/api/files', { q });
        palItems = r.files.map((f) => ({ label: f, hint: '', file: true, run: () => openFile(f) }));
        if (!q) palItems = [...Forge.tabOrder.slice().reverse().map((f) => ({ label: f, hint: 'open', file: true, run: () => openFile(f) })), ...palItems.filter((p) => !Forge.files.has(p.label))];
      } catch (e) { palItems = []; }
    }
    palSel = 0;
    renderPalette();
  }, 60);
  function renderPalette() {
    const list = $('#palette-list');
    list.innerHTML = '';
    palItems.slice(0, 60).forEach((it, i) => {
      const left = it.file ? el('span', { class: 'pl' }, fileIcon(it.label), ' ', el('span', { text: it.label.split('/').pop() }), ' ', el('span', { class: 'dim', text: it.label.includes('/') ? it.label.slice(0, it.label.lastIndexOf('/')) : '' })) : el('span', { class: 'pl', text: it.label });
      list.append(el('div', { class: 'pitem' + (i === palSel ? ' sel' : ''), onclick: () => { closePalette(); it.run(); }, onmousemove: () => { palSel = i; } }, left, el('span', { class: 'pr', text: it.hint })));
    });
    if (!palItems.length) list.append(el('div', { class: 'muted pad', text: 'No matches' }));
  }
  $('#palette-input').addEventListener('input', updatePalette);
  $('#palette-input').addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closePalette();
    else if (e.key === 'ArrowDown') { palSel = Math.min(palItems.length - 1, palSel + 1); renderPalette(); e.preventDefault(); }
    else if (e.key === 'ArrowUp') { palSel = Math.max(0, palSel - 1); renderPalette(); e.preventDefault(); }
    else if (e.key === 'Enter') { const it = palItems[palSel]; if (it) { closePalette(); it.run(); } }
  });

  // ------------------------------------------------------------------ open folder modal
  async function openFolderModal(path) {
    $('#folder-modal').classList.remove('hidden');
    try {
      const r = await get('/api/dirs', { path: path || Forge.status.workspace || '~' });
      $('#folder-path').value = r.path;
      const list = $('#folder-list');
      list.innerHTML = '';
      list.append(el('div', { class: 'fitem', text: '..  (up)', onclick: () => openFolderModal(r.parent) }));
      for (const d of r.dirs) list.append(el('div', { class: 'fitem', onclick: () => openFolderModal(r.path.replace(/\/$/, '') + '/' + d) }, fileIcon(d, true), el('span', { text: d })));
      const rec = $('#folder-recent');
      rec.innerHTML = '';
      for (const p of r.recent) rec.append(el('div', { class: 'fitem rec', text: p, title: p, onclick: () => setWorkspace(p) }));
    } catch (e) { toast(e.message, 'error'); }
  }
  $('#btn-open-workspace').onclick = () => openFolderModal();
  $('#welcome-open').onclick = () => openFolderModal();
  $('#folder-close').onclick = () => $('#folder-modal').classList.add('hidden');
  $('#folder-modal').addEventListener('mousedown', (e) => { if (e.target === e.currentTarget) $('#folder-modal').classList.add('hidden'); });
  $('#folder-open').onclick = () => setWorkspace($('#folder-path').value.trim());
  $('#folder-path').addEventListener('keydown', (e) => { if (e.key === 'Enter') setWorkspace(e.target.value.trim()); });
  async function setWorkspace(p) {
    try {
      await post('/api/workspace', { path: p, create: true });
      $('#folder-modal').classList.add('hidden');
      for (const q of [...Forge.tabOrder]) { const f = Forge.files.get(q); if (f) f.dirty = false; await closeFile(q); }
      treeState.open.clear(); saveTree();
      await refreshStatus();
      await loadTree();
      if (window.ForgeChat) window.ForgeChat.newChat();
      if (window.ForgeTerm) window.ForgeTerm.resetAll();
      toast('Opened ' + p, 'ok');
    } catch (e) { toast(e.message, 'error'); }
  }

  // ------------------------------------------------------------------ settings panel
  const SETTINGS_SCHEMA = [
    { group: 'Engine' },
    { key: 'engine', label: 'Inference engine', type: 'select', options: ['ollama', 'gpu'], hint: 'ollama: CPU inside the Linux VM. gpu: the Forge GPU engine (llama.cpp with Vulkan on the Intel graphics), started from the Forge app. Switching clears the chat context.' },
    { key: 'gpu_ctx', label: 'GPU engine context (tokens)', type: 'number', hint: 'Context window the GPU engine is started with.' },
    { group: 'Model' },
    { key: 'model', label: 'Model', type: 'model', hint: 'Served by Ollama. forge-coder is the tuned Llama 3.1 build.' },
    { key: 'num_ctx', label: 'Context window (tokens)', type: 'number', hint: '8192 is the sweet spot on this machine. Bigger means slower first replies and more RAM.' },
    { key: 'num_predict', label: 'Max tokens per reply', type: 'number' },
    { key: 'temperature', label: 'Temperature', type: 'number', step: 0.05, hint: '0.1 to 0.3 for code.' },
    { key: 'num_thread', label: 'CPU threads (0 = auto)', type: 'number' },
    { key: 'keep_alive', label: 'Keep model loaded', type: 'text', hint: 'e.g. 60m, 24h, -1 for always.' },
    { group: 'Agent' },
    { key: 'thinking', label: 'Show thinking process', type: 'bool', hint: 'The model reasons in a visible block before acting.' },
    { key: 'tool_mode', label: 'Tool calling', type: 'select', options: ['prompt', 'native'], hint: 'prompt: Forge\'s robust tagged format (recommended for 8B models). native: Ollama\'s JSON tool API.' },
    { key: 'max_iterations', label: 'Max tool steps per message', type: 'number' },
    { key: 'auto_approve_commands', label: 'Auto-run commands', type: 'bool', hint: 'Destructive commands and deletions still ask.' },
    { key: 'repo_map_chars', label: 'Repo map size (characters)', type: 'number', hint: 'Outline of your workspace given to the model. Smaller is faster.' },
    { key: 'prewarm', label: 'Pre-warm model on new chat', type: 'bool', hint: 'Processes the system prompt in the background so the first reply is fast.' },
    { group: 'Editor' },
    { key: 'tab_completions', label: 'Tab completions while typing', type: 'bool', hint: 'Slow on CPU; Alt+\\ triggers one manually any time.' },
    { key: 'completion_delay_ms', label: 'Completion delay (ms)', type: 'number' },
    { key: 'font_size', label: 'Editor font size', type: 'number' },
    { key: 'verbose', label: 'Verbose mode', type: 'bool', hint: 'Show raw model traffic, trimming and timing in the chat and Agent log.' },
    { group: 'Connection' },
    { key: 'ollama_url', label: 'Ollama URL', type: 'text' },
  ];
  async function renderSettings() {
    const host = $('#settings-panel');
    host.innerHTML = '';
    let models = [];
    try { models = (await get('/api/models')).models.map((m) => m.name); } catch (e) { /* offline */ }
    for (const s of SETTINGS_SCHEMA) {
      if (s.group) { host.append(el('div', { class: 'sgroup', text: s.group })); continue; }
      const val = Forge.settings[s.key];
      const row = el('div', { class: 'srow' + (s.type === 'bool' ? ' inline' : '') });
      row.append(el('label', { text: s.label }));
      let input;
      if (s.type === 'bool') {
        input = el('div', { class: 'switch' + (val ? ' on' : ''), onclick: async () => { input.classList.toggle('on'); await saveSetting(s.key, input.classList.contains('on')); } });
      } else if (s.type === 'select' || s.type === 'model') {
        input = el('select');
        const opts = s.type === 'model' ? (models.length ? models : [val]) : s.options;
        if (s.type === 'model' && val && !opts.includes(val) && !opts.includes(val + ':latest')) opts.unshift(val);
        for (const o of opts) input.append(el('option', { value: o, text: o }));
        input.value = opts.includes(val) ? val : (opts.includes(val + ':latest') ? val + ':latest' : opts[0]);
        input.onchange = () => saveSetting(s.key, input.value);
      } else {
        input = el('input', { type: s.type, value: val === undefined ? '' : val, step: s.step || (s.type === 'number' ? 1 : null) });
        input.onchange = () => saveSetting(s.key, s.type === 'number' ? Number(input.value) : input.value);
      }
      row.append(input);
      if (s.hint) row.append(el('div', { class: 'hint', text: s.hint }));
      host.append(row);
    }
    if (window.ForgeExtras) await window.ForgeExtras.renderSettingsExtras(host);
    host.append(el('div', { class: 'sgroup', text: 'Shortcuts' }));
    const sc = [['Ctrl P', 'Quick open'], ['Ctrl Shift P', 'Command palette'], ['Ctrl L', 'Add selection to chat'], ['Ctrl K', 'Edit selection with AI'], ['Ctrl I', 'Toggle AI panel'], ['Ctrl Enter', 'Send message'], ['Esc', 'Stop the agent'], ['Ctrl `', 'Terminal'], ['Ctrl B', 'Sidebar'], ['Ctrl S', 'Save'], ['Ctrl N', 'New chat'], ['Alt \\', 'Trigger completion']];
    for (const [k, v] of sc) host.append(el('div', { class: 'srow inline' }, el('span', { class: 'muted', text: v }), el('kbd', { text: k })));
  }
  async function saveSetting(key, value) {
    try {
      if (key === 'engine') { await post('/api/engine/select', { engine: value }); }
      Forge.settings = await post('/api/settings', { [key]: value });
      applySettings();
      toast('Saved ' + key, 'ok', 1000);
    } catch (e) { toast(e.message, 'error'); }
  }
  function applySettings() {
    if (Forge.editor) Forge.editor.updateOptions({ fontSize: Number(Forge.settings.font_size) || 13 });
    $('#thinking-toggle').checked = !!Forge.settings.thinking;
    $('#auto-toggle').checked = !!Forge.settings.auto_approve_commands;
    $('#btn-verbose').classList.toggle('on', !!Forge.settings.verbose);
    updateStatusBar();
    if (window.ForgeChat) window.ForgeChat.onSettings();
  }
  Forge.saveSetting = saveSetting;
  $('#thinking-toggle').addEventListener('change', (e) => saveSetting('thinking', e.target.checked));
  $('#auto-toggle').addEventListener('change', (e) => saveSetting('auto_approve_commands', e.target.checked));
  $('#btn-settings').onclick = () => showView('settings');

  // ------------------------------------------------------------------ status
  async function refreshStatus() {
    try {
      const s = await get('/api/status');
      Forge.status = s;
      $('#workspace-name').textContent = s.workspace_name;
      document.title = s.workspace_name + ' · Forge IDE';
      $('#ollama-dot').className = 'dot ' + (s.ollama ? 'ok' : 'bad');
      $('#sb-ollama').textContent = s.ollama ? 'Ollama connected' : 'Ollama offline';
      $('#sb-ollama').className = 'sb-item ' + (s.ollama ? 'ok' : 'bad');
      const loaded = (s.running || []).map((m) => m.name).join(', ');
      if (s.engine === 'gpu') {
        const h = (s.gpu_engine && s.gpu_engine.health && s.gpu_engine.health.status) || 'down';
        $('#sb-model').textContent = 'GPU engine: ' + (h === 'ok' ? 'ready' : h);
      } else $('#sb-model').textContent = s.model + (loaded ? ' (loaded)' : ' (will load on first message)');
      $('#sb-model').title = loaded ? 'Loaded in memory: ' + loaded : 'Not loaded yet';
      if (s.mem && s.mem.total_mb) {
        const avail = s.mem.available_mb, el2 = $('#sb-mem');
        el2.textContent = 'RAM ' + (avail / 1024).toFixed(1) + ' GB free' + (s.mem.swap_used_mb > 200 ? ' · swap ' + s.mem.swap_used_mb + ' MB' : '') + (s.mem.balloon_mb > 100 ? ' · host took ' + (s.mem.balloon_mb / 1024).toFixed(1) + ' GB' : '');
        el2.style.color = avail < 700 ? 'var(--red)' : avail < 1500 ? 'var(--yellow)' : '';
        el2.title = 'Free memory in the Linux VM (' + (s.mem.total_mb / 1024).toFixed(1) + ' GB total). The model needs about 5.2 GB while loaded. "Host took" is memory GooglebookOS reclaimed from the VM; it comes back when the host has room.';
        if (s.mem_event && s.mem_event.ts !== Forge.lastMemEvent) { Forge.lastMemEvent = s.mem_event.ts; toast('Memory watchdog unloaded the model (' + s.mem_event.available_mb + ' MB were free). Close other programs before the next message.', 'error', 9000); }
      }
      try {
        const g = await get('/api/git');
        $('#sb-git').textContent = g.repo ? ('⎇ ' + g.branch + (g.files && g.files.length ? ' · ' + g.files.length + ' changed' : '')) : '';
      } catch (e) { /* ignore */ }
      await loadModels(s.model);
    } catch (e) { $('#sb-ollama').textContent = 'Server unreachable'; $('#sb-ollama').className = 'sb-item bad'; }
  }
  async function loadModels(active) {
    try {
      const r = await get('/api/models');
      const sel = $('#model-select');
      sel.innerHTML = '';
      for (const m of r.models) sel.append(el('option', { value: m.name, text: m.name + (m.details && m.details.parameter_size ? ' · ' + m.details.parameter_size : '') }));
      const want = r.models.find((m) => m.name === active || m.name === active + ':latest');
      if (want) sel.value = want.name;
      sel.onchange = () => saveSetting('model', sel.value);
    } catch (e) { /* ignore */ }
  }
  function updateStatusBar() {
    $('#sb-tab').textContent = 'Tab: ' + (Forge.settings.tab_completions ? 'on' : 'off');
    $('#sb-tab').classList.toggle('on', !!Forge.settings.tab_completions);
  }
  Forge.refreshStatus = refreshStatus;

  // ------------------------------------------------------------------ global shortcuts
  window.addEventListener('keydown', (e) => {
    const mod = e.ctrlKey || e.metaKey;
    const inEditor = Forge.editor && Forge.editor.hasTextFocus();
    if (mod && e.shiftKey && e.key.toLowerCase() === 'p') { e.preventDefault(); openPalette('>'); return; }
    if (mod && !e.shiftKey && e.key.toLowerCase() === 'p') { e.preventDefault(); openPalette(''); return; }
    if (mod && e.key.toLowerCase() === 'o') { e.preventDefault(); openFolderModal(); return; }
    if (mod && e.key.toLowerCase() === 'i' && !inEditor) { e.preventDefault(); Forge.toggle('chat'); return; }
    if (mod && e.key.toLowerCase() === 'b' && !inEditor) { e.preventDefault(); Forge.toggle('sidebar'); return; }
    if (mod && e.key === '`') { e.preventDefault(); Forge.toggle('panel'); return; }
    if (mod && e.key === ',') { e.preventDefault(); showView('settings'); return; }
    if (mod && e.key.toLowerCase() === 's' && !inEditor) { e.preventDefault(); saveActive(); return; }
    if (mod && e.key.toLowerCase() === 'n') { e.preventDefault(); window.ForgeChat && window.ForgeChat.newChat(); return; }
    if (mod && e.key.toLowerCase() === 'l' && !inEditor) { e.preventDefault(); window.ForgeChat && window.ForgeChat.addSelectionAndFocus(); return; }
    if (mod && e.key.toLowerCase() === 'k' && !inEditor && !e.target.closest('.chat')) { e.preventDefault(); openInlineEdit(); return; }
    if (mod && e.shiftKey && e.key.toLowerCase() === 'f') { e.preventDefault(); showView('search'); return; }
    if (mod && e.shiftKey && e.key.toLowerCase() === 'e') { e.preventDefault(); showView('explorer'); return; }
    if (mod && e.shiftKey && e.key.toLowerCase() === 'g') { e.preventDefault(); showView('changes'); return; }
    if (mod && e.shiftKey && e.key.toLowerCase() === 'h') { e.preventDefault(); showView('history'); return; }
    if (e.key === 'Escape' && !$('#palette').classList.contains('hidden')) closePalette();
  });
  window.addEventListener('beforeunload', (e) => { for (const f of Forge.files.values()) if (f.dirty) { e.preventDefault(); e.returnValue = ''; return; } });

  // ------------------------------------------------------------------ boot
  async function boot() {
    try { Forge.settings = await get('/api/settings'); } catch (e) { toast('Cannot reach the Forge server: ' + e.message, 'error', 8000); }
    applyLayout();
    await initMonaco();
    applySettings();
    await refreshStatus();
    await loadTree();
    showView(Forge.ui.view || 'explorer');
    if (Forge.ui.panel !== false) showPanel('terminal');
    if (window.ForgeChat) window.ForgeChat.init();
    if (window.ForgeTerm) window.ForgeTerm.ensure();
    setInterval(refreshStatus, 20000);
    const open = JSON.parse(localStorage.getItem('forge.openFiles.' + (Forge.status.workspace || '')) || '[]');
    for (const p of open) await openFile(p, { background: true });
    if (open.length) activate(open[open.length - 1]);
    setInterval(() => localStorage.setItem('forge.openFiles.' + (Forge.status.workspace || ''), JSON.stringify(Forge.tabOrder)), 3000);
  }
  boot();
})();
