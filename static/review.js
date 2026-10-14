/* Forge IDE: review the agent's edits inline in the editor, like Cursor. Each change is highlighted (added lines in
 * green, removed lines shown in red above them) with Keep and Undo buttons, and a bar at the top offers Keep all,
 * Undo all and stepping between changes. Undo edits the file back and saves it; Keep just accepts the change. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, el, get, toast } = F;
  const R = window.ForgeReview = {};
  const reviews = new Map();   // path -> { run_id, base }
  let deco = [], zones = [], overlay = null, bar = null, hunks = [], current = -1, renderTimer = null, listening = false;
  const storeKey = () => 'forge.review.' + F.folder;

  function persist() {
    const o = {};
    for (const [p, r] of reviews) o[p] = r.run_id;
    try { localStorage.setItem(storeKey(), JSON.stringify(o)); } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------------ line diff (Myers, with a size guard)
  function diffLines(a, b) {
    let pre = 0;
    while (pre < a.length && pre < b.length && a[pre] === b[pre]) pre++;
    let suf = 0;
    while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf] === b[b.length - 1 - suf]) suf++;
    const A = a.slice(pre, a.length - suf), B = b.slice(pre, b.length - suf);
    const N = A.length, M = B.length;
    if (!N && !M) return [];
    if (!N || !M || N * M < 4 || N + M > 20000) return [{ a0: pre, a1: pre + N, b0: pre, b1: pre + M }];
    const max = N + M, off = max + 1;
    const v = new Int32Array(2 * max + 3);
    const trace = [];
    let found = -1;
    for (let d = 0; d <= max && found < 0; d++) {
      if (d > 1500) return [{ a0: pre, a1: pre + N, b0: pre, b1: pre + M }];   // very different: one block
      trace.push(v.slice());
      for (let k = -d; k <= d; k += 2) {
        let x = (k === -d || (k !== d && v[off + k - 1] < v[off + k + 1])) ? v[off + k + 1] : v[off + k - 1] + 1;
        let y = x - k;
        while (x < N && y < M && A[x] === B[y]) { x++; y++; }
        v[off + k] = x;
        if (x >= N && y >= M) { found = d; break; }
      }
    }
    // Walk back through the trace to list the equal line pairs.
    const equal = [];
    let x = N, y = M;
    for (let d = found; d > 0; d--) {
      const vv = trace[d];
      const k = x - y;
      const prevK = (k === -d || (k !== d && vv[off + k - 1] < vv[off + k + 1])) ? k + 1 : k - 1;
      const px = vv[off + prevK], py = px - prevK;
      while (x > px && y > py) { equal.push([x - 1, y - 1]); x--; y--; }
      x = px; y = py;
    }
    while (x > 0 && y > 0) { equal.push([x - 1, y - 1]); x--; y--; }
    equal.reverse();
    const out = [];
    let ia = 0, ib = 0;
    for (const [ea, eb] of equal.concat([[N, M]])) {
      if (ea > ia || eb > ib) out.push({ a0: pre + ia, a1: pre + ea, b0: pre + ib, b1: pre + eb });
      ia = ea + 1; ib = eb + 1;
    }
    return out;
  }

  // ------------------------------------------------------------------ rendering
  function clear() {
    const ed = F.editor;
    if (!ed) return;
    deco = ed.deltaDecorations(deco, []);
    if (zones.length) ed.changeViewZones((acc) => { for (const z of zones) acc.removeZone(z); });
    zones = [];
    if (overlay) overlay.dom.innerHTML = '';
    if (bar) bar.dom.classList.add('hidden');
    hunks = [];
  }
  function ensureWidgets() {
    const ed = F.editor;
    if (overlay) return;
    const odom = el('div', { class: 'rv-overlay' });
    overlay = { dom: odom, getId: () => 'forge.review.buttons', getDomNode: () => odom, getPosition: () => null };
    ed.addOverlayWidget(overlay);
    const bdom = el('div', { class: 'rv-bar hidden' });
    bar = { dom: bdom, getId: () => 'forge.review.bar', getDomNode: () => bdom, getPosition: () => ({ preference: F.monaco.editor.OverlayWidgetPositionPreference.BOTTOM_RIGHT_CORNER }) };
    ed.addOverlayWidget(bar);
    ed.onDidScrollChange(placeButtons);
    ed.onDidLayoutChange(placeButtons);
    ed.onDidChangeModel(() => schedule(0));
  }
  function schedule(ms) { clearTimeout(renderTimer); renderTimer = setTimeout(render, ms == null ? 250 : ms); }
  function placeButtons() {
    if (!overlay) return;
    const ed = F.editor;
    const top0 = ed.getScrollTop();
    const width = ed.getLayoutInfo().contentWidth;
    for (const node of overlay.dom.children) {
      const line = Number(node.dataset.line);
      const top = ed.getTopForLineNumber(line) - top0;
      node.style.top = top + 'px';
      node.style.left = Math.max(0, ed.getLayoutInfo().contentLeft + width - 190) + 'px';
      node.style.display = top < -20 || top > ed.getLayoutInfo().height ? 'none' : '';
    }
  }
  function render() {
    const ed = F.editor;
    if (!ed) return;
    clear();
    const path = F.activePath;
    const r = path && reviews.get(path);
    const f = path && F.files.get(path);
    if (!r || !f || ed.getModel() !== f.model) return;
    ensureWidgets();
    const model = f.model;
    const now = model.getLinesContent();
    const base = r.base.split('\n');
    hunks = diffLines(base, now);
    if (!hunks.length) { finish(path, 'kept'); return; }
    const monaco = F.monaco;
    const decs = [];
    const fontInfo = ed.getOption(monaco.editor.EditorOption.fontInfo);
    const lineH = ed.getOption(monaco.editor.EditorOption.lineHeight);
    ed.changeViewZones((acc) => {
      hunks.forEach((h) => {
        for (let l = h.b0 + 1; l <= h.b1; l++) decs.push({ range: new monaco.Range(l, 1, l, 1), options: { isWholeLine: true, className: 'rv-added', linesDecorationsClassName: 'rv-added-gutter' } });
        if (h.a1 > h.a0) {
          const dom = el('div', { class: 'rv-removed' });
          dom.style.fontFamily = fontInfo.fontFamily; dom.style.fontSize = fontInfo.fontSize + 'px'; dom.style.lineHeight = lineH + 'px';
          for (const t of base.slice(h.a0, h.a1)) dom.append(el('div', { class: 'rv-rline', text: t || ' ' }));
          zones.push(acc.addZone({ afterLineNumber: h.b0, heightInLines: h.a1 - h.a0, domNode: dom }));
        }
      });
    });
    deco = ed.deltaDecorations([], decs);
    hunks.forEach((h, i) => {
      const line = Math.max(1, Math.min(model.getLineCount(), h.b1 > h.b0 ? h.b0 + 1 : h.b0 + 1));
      const box = el('div', { class: 'rv-btns', 'data-line': String(line) },
        el('button', { class: 'rv-keep', text: 'Keep', title: 'Keep this change', onclick: () => keep(i) }),
        el('button', { class: 'rv-undo', text: 'Undo', title: 'Undo this change and save the file', onclick: () => undo(i) }));
      overlay.dom.append(box);
    });
    placeButtons();
    bar.dom.classList.remove('hidden');
    bar.dom.innerHTML = '';
    const added = hunks.reduce((n, h) => n + (h.b1 - h.b0), 0), removed = hunks.reduce((n, h) => n + (h.a1 - h.a0), 0);
    bar.dom.append(el('span', { class: 'rv-title', text: hunks.length + (hunks.length === 1 ? ' change' : ' changes') + ' from Forge' }),
      el('span', { class: 'rv-stat' }, el('span', { class: 'plus', text: '+' + added }), el('span', { class: 'minus', text: ' −' + removed })),
      el('button', { text: '‹', title: 'Previous change', onclick: () => go(-1) }), el('button', { text: '›', title: 'Next change', onclick: () => go(1) }),
      el('button', { class: 'rv-keep', text: 'Keep all', onclick: keepAll }), el('button', { class: 'rv-undo', text: 'Undo all', onclick: undoAll }));
    if (!listening) {
      listening = true;
      ed.onDidChangeModelContent(() => { if (reviews.has(F.activePath)) schedule(); });
    }
  }
  function go(dir) {
    if (!hunks.length) return;
    current = (current + dir + hunks.length) % hunks.length;
    const h = hunks[current];
    F.editor.revealLineInCenter(Math.max(1, h.b0 + 1));
    F.editor.setPosition({ lineNumber: Math.max(1, h.b0 + 1), column: 1 });
  }

  // ------------------------------------------------------------------ actions
  function keep(i) {
    const path = F.activePath, r = reviews.get(path), h = hunks[i];
    if (!r || !h) return;
    const base = r.base.split('\n');
    const now = F.files.get(path).model.getLinesContent();
    base.splice(h.a0, h.a1 - h.a0, ...now.slice(h.b0, h.b1));
    r.base = base.join('\n');
    render();
  }
  async function undo(i) {
    const path = F.activePath, r = reviews.get(path), h = hunks[i], f = F.files.get(path);
    if (!r || !h || !f) return;
    const base = r.base.split('\n');
    const model = f.model, monaco = F.monaco;
    const old = base.slice(h.a0, h.a1);
    let range, text;
    if (h.b1 > h.b0) {
      // Replace the new lines with the old ones.
      const endLine = h.b1, endCol = model.getLineMaxColumn(endLine);
      range = new monaco.Range(h.b0 + 1, 1, endLine, endCol);
      text = old.join('\n');
      if (!old.length) {
        // Pure insertion: remove the lines including their line break.
        range = h.b1 < model.getLineCount() ? new monaco.Range(h.b0 + 1, 1, h.b1 + 1, 1)
          : new monaco.Range(Math.max(1, h.b0), h.b0 ? model.getLineMaxColumn(h.b0) : 1, endLine, endCol);
        text = '';
      }
    } else {
      // Pure deletion: put the old lines back before line b0 + 1.
      if (h.b0 < model.getLineCount()) { range = new monaco.Range(h.b0 + 1, 1, h.b0 + 1, 1); text = old.join('\n') + '\n'; }
      else { const last = model.getLineCount(); range = new monaco.Range(last, model.getLineMaxColumn(last), last, model.getLineMaxColumn(last)); text = '\n' + old.join('\n'); }
    }
    model.pushEditOperations([], [{ range, text, forceMoveMarkers: true }], () => null);
    await F.saveFile(path, true);
    render();
  }
  async function keepAll() {
    const path = F.activePath;
    finish(path, 'kept');
    toast('Kept all changes in ' + path, 'ok', 1500);
  }
  async function undoAll() {
    const path = F.activePath, r = reviews.get(path), f = F.files.get(path);
    if (!r || !f) return;
    const model = f.model;
    model.pushEditOperations([], [{ range: model.getFullModelRange(), text: r.base }], () => null);
    await F.saveFile(path, true);
    finish(path, 'undone');
    toast('Undid all changes in ' + path + ' (Ctrl+Z brings them back).', 'ok', 3000);
  }
  function finish(path) {
    reviews.delete(path);
    persist();
    if (path === F.activePath) clear();
    updateBadge();
  }
  function updateBadge() {
    const n = reviews.size;
    const sb = $('#sb-review');
    if (sb) { sb.textContent = n ? n + (n === 1 ? ' file to review' : ' files to review') : ''; sb.title = n ? [...reviews.keys()].join('\n') + '\nClick to open the next one.' : ''; }
  }

  // ------------------------------------------------------------------ public
  async function baseFor(runId, path) {
    const r = await get('/api/changes', { run_id: runId });
    const c = (r.changes || []).find((x) => x.path === path);
    return c ? { base: c.before || '', kind: c.kind } : null;
  }
  /** A file changed by the agent: start (or extend) its review. The earliest unreviewed version stays the base. */
  R.onAgentChange = async (runId, path) => {
    if (reviews.has(path)) { schedule(); return; }
    try {
      const b = await baseFor(runId, path);
      if (!b || b.kind === 'deleted') return;
      reviews.set(path, { run_id: runId, base: b.base });
      persist(); updateBadge();
      if (F.files.has(path)) schedule(400);
    } catch (e) { /* the change list is optional */ }
  };
  R.onOpen = () => schedule(300);
  R.reviewRun = async (runId) => {
    try {
      const r = await get('/api/changes', { run_id: runId });
      let first = null;
      for (const c of r.changes || []) {
        if (c.kind === 'deleted') continue;
        if (!reviews.has(c.path)) reviews.set(c.path, { run_id: runId, base: c.before || '' });
        first = first || c.path;
      }
      persist(); updateBadge();
      if (first) { await F.openFile(first); schedule(100); } else toast('This reply changed no files that can be reviewed.', 'ok');
    } catch (e) { toast(e.message, 'error'); }
  };
  R.next = async () => { const p = [...reviews.keys()].find((x) => x !== F.activePath) || [...reviews.keys()][0]; if (p) { await F.openFile(p); schedule(100); } };
  R.keepAll = () => { for (const p of [...reviews.keys()]) finish(p); toast('Kept every pending change.', 'ok'); };
  R.pending = () => [...reviews.keys()];
  R.init = async () => {
    let saved = {};
    try { saved = JSON.parse(localStorage.getItem(storeKey()) || '{}'); } catch (e) { /* ignore */ }
    for (const [path, runId] of Object.entries(saved)) {
      try { const b = await baseFor(runId, path); if (b) reviews.set(path, { run_id: runId, base: b.base }); } catch (e) { /* run gone */ }
    }
    persist(); updateBadge();
    F.hooks.onActiveFileChanged.push(() => schedule(50));
    schedule(200);
  };
  const sb = $('#sb-review');
  if (sb) sb.onclick = R.next;
  setTimeout(() => { if (F.editor) R.init(); else { const t = setInterval(() => { if (F.editor) { clearInterval(t); R.init(); } }, 500); } }, 1500);
})();
