/* Forge IDE: split editor. A second editor pane on the right shows any open file side by side with the main one
 * (Ctrl+\ or "Open to the side"). Both panes share the same files, so edits appear in both and saving works from
 * either; language-server features and breakpoints work in both panes. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, el, toast } = F;
  const S = window.ForgeSplit = {};
  let ed2 = null, wrap = null, tabsEl = null, path2 = null, order = [], disposeSub = null;

  function ensure() {
    if (ed2) return;
    const area = $('#editor').parentElement;
    wrap = el('div', { class: 'split-pane hidden' });
    tabsEl = el('div', { class: 'split-tabs' });
    const host = el('div', { class: 'split-editor' });
    wrap.append(tabsEl, host);
    area.append(wrap);
    ed2 = F.monaco.editor.create(host, {
      theme: 'forge-dark', automaticLayout: true, fontSize: F.settings.font_size || 13, fontFamily: 'JetBrains Mono, Fira Code, Cascadia Code, Menlo, Consolas, monospace',
      fontLigatures: true, minimap: { enabled: false }, smoothScrolling: true, renderWhitespace: 'selection', bracketPairColorization: { enabled: true },
      scrollBeyondLastLine: false, tabSize: 4, padding: { top: 8 }, stickyScroll: { enabled: true }, lineNumbersMinChars: 4, glyphMargin: true, model: null,
    });
    S.editor = ed2;
    for (const h of F.hooks.onEditorCreated || []) h(ed2);
    ed2.onDidChangeCursorPosition((e) => { if (ed2.hasTextFocus()) $('#sb-pos').textContent = 'Ln ' + e.position.lineNumber + ', Col ' + e.position.column + ' (right pane)'; });
  }
  function renderTabs() {
    tabsEl.innerHTML = '';
    for (const p of order) {
      const f = F.files.get(p);
      if (!f) continue;
      tabsEl.append(el('div', { class: 'tab' + (p === path2 ? ' active' : '') + (f.dirty ? ' dirty' : ''), title: p, onclick: () => show(p) },
        F.fileIcon(p), el('span', { text: p.split('/').pop() }),
        el('span', { class: 'tclose', title: 'Close in this pane', onclick: (e) => { e.stopPropagation(); closeTab(p); } })));
    }
    tabsEl.append(el('div', { class: 'split-actions' }, el('button', { class: 'mini', text: '✕', title: 'Close the split (Ctrl+\\)', onclick: () => S.close() })));
  }
  function show(p) {
    const f = F.files.get(p);
    if (!f) return;
    path2 = p;
    if (disposeSub) disposeSub.dispose();
    ed2.setModel(f.model);
    disposeSub = f.model.onWillDispose(() => { order = order.filter((x) => x !== p); if (path2 === p) { const next = order[order.length - 1]; if (next) setTimeout(() => show(next), 0); else S.close(); } else renderTabs(); });
    renderTabs();
  }
  function closeTab(p) {
    order = order.filter((x) => x !== p);
    if (!order.length) return S.close();
    if (path2 === p) show(order[order.length - 1]); else renderTabs();
  }
  /** Open a file in the right pane. */
  S.open = async (p) => {
    p = p || F.activePath;
    if (!p) return toast('Open a file first.', 'error');
    if (/\.(ipynb|png|jpe?g|gif|webp|bmp|ico|avif)$/i.test(p)) return toast('Notebooks and images open in the main editor only.', 'error');
    const f = F.files.get(p) || await F.openFile(p, { background: true });
    if (!f) return;
    ensure();
    if (!order.includes(p)) order.push(p);
    $('#editor').parentElement.classList.add('split');
    $('#editor').parentElement.classList.remove('with-preview');
    wrap.classList.remove('hidden');
    show(p);
    setTimeout(() => ed2.focus(), 0);
  };
  S.close = () => {
    if (!wrap) return;
    wrap.classList.add('hidden');
    $('#editor').parentElement.classList.remove('split');
    if (ed2) ed2.setModel(null);
    if (disposeSub) { disposeSub.dispose(); disposeSub = null; }
    order = []; path2 = null;
    if (F.editor) F.editor.focus();
  };
  S.toggle = () => { if (wrap && !wrap.classList.contains('hidden')) S.close(); else S.open(); };
  S.focused = () => !!(ed2 && ed2.hasTextFocus());
  S.activePath = () => path2;
  // Ctrl+S in the right pane saves that pane's file; Ctrl+\ splits or closes the split.
  window.addEventListener('keydown', (e) => {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key === '\\') { e.preventDefault(); e.stopPropagation(); S.toggle(); return; }
    if (mod && e.key.toLowerCase() === 's' && S.focused() && path2) { e.preventDefault(); e.stopPropagation(); F.saveFile(path2); }
  }, true);
  // Keep the right pane's tab dots in sync with unsaved changes.
  setInterval(() => { if (wrap && !wrap.classList.contains('hidden')) { const dirty = order.map((p) => (F.files.get(p) || {}).dirty).join(); if (dirty !== S._dirty) { S._dirty = dirty; renderTabs(); } } }, 700);
})();
