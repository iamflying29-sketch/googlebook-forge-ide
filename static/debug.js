/* Forge IDE: debugger for Python (Run and Debug, like Cursor and VS Code). The page speaks the Debug Adapter
 * Protocol to debugpy through the server's /ws/dap bridge: breakpoints in the gutter, step controls, variables,
 * watch expressions, the call stack and a Debug console that evaluates expressions in the paused frame. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, el, toast } = F;
  const D = window.ForgeDebug = {};
  const bps = new Map();          // relative path -> Set of line numbers
  const bpDecos = new Map();      // model uri -> decoration ids
  let session = null;             // { dap, state, threadId, frames, frameId, reason }
  let curDeco = [];
  const watches = [];
  const storeKey = () => 'forge.breakpoints.' + F.folder;

  try { const saved = JSON.parse(localStorage.getItem(storeKey()) || '{}'); for (const [p, lines] of Object.entries(saved)) bps.set(p, new Set(lines)); } catch (e) { /* ignore */ }
  try { watches.push(...JSON.parse(localStorage.getItem('forge.watches') || '[]')); } catch (e) { /* ignore */ }
  const persist = () => { const o = {}; for (const [p, s] of bps) if (s.size) o[p] = [...s].sort((a, b) => a - b); try { localStorage.setItem(storeKey(), JSON.stringify(o)); localStorage.setItem('forge.watches', JSON.stringify(watches)); } catch (e) { /* ignore */ } };

  // ------------------------------------------------------------------ DAP connection
  class Dap {
    constructor(onEvent) { this.seq = 1; this.pending = new Map(); this.onEvent = onEvent; this.ws = null; this.closed = false; }
    connect() {
      return new Promise((resolve, reject) => {
        const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
        const ws = this.ws = new WebSocket(proto + location.host + '/ws/dap?folder=' + encodeURIComponent(F.folder));
        const timer = setTimeout(() => reject(new Error('The debugger did not start in time.')), 20000);
        ws.onmessage = (m) => {
          let msg;
          try { msg = JSON.parse(m.data); } catch (e) { return; }
          if (msg.forge === 'ready') { clearTimeout(timer); resolve(); return; }
          if (msg.forge === 'error') { clearTimeout(timer); reject(new Error(msg.message)); return; }
          if (msg.forge === 'exit') { this.onEvent({ event: 'adapterExit' }); return; }
          if (msg.type === 'response') { const p = this.pending.get(msg.request_seq); if (p) { this.pending.delete(msg.request_seq); msg.success ? p.resolve(msg.body || {}) : p.reject(new Error(msg.message || (msg.body && msg.body.error && msg.body.error.format) || 'request failed')); } }
          else if (msg.type === 'event') this.onEvent(msg);
          else if (msg.type === 'request') this.send({ type: 'response', request_seq: msg.seq, command: msg.command, success: false, message: 'not supported by Forge' });
        };
        ws.onclose = () => { this.closed = true; clearTimeout(timer); for (const p of this.pending.values()) p.reject(new Error('debugger disconnected')); this.pending.clear(); this.onEvent({ event: 'adapterExit' }); };
        ws.onerror = () => { clearTimeout(timer); reject(new Error('Cannot connect to the debugger.')); };
      });
    }
    send(obj) { if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(obj)); }
    request(command, args, timeoutMs) {
      const seq = this.seq++;
      return new Promise((resolve, reject) => {
        this.pending.set(seq, { resolve, reject });
        this.send({ seq, type: 'request', command, arguments: args || {} });
        setTimeout(() => { if (this.pending.has(seq)) { this.pending.delete(seq); reject(new Error(command + ' timed out')); } }, timeoutMs || 30000);
      });
    }
    close() { try { this.ws.close(); } catch (e) { /* ignore */ } }
  }

  // ------------------------------------------------------------------ breakpoints in the editor
  function relOf(model) { return F.relPath(model.uri.path); }
  function renderBreakpoints(model) {
    if (!model || !F.monaco) return;
    const lines = bps.get(relOf(model)) || new Set();
    const decs = [...lines].filter((l) => l <= model.getLineCount()).map((l) => ({ range: new F.monaco.Range(l, 1, l, 1), options: { glyphMarginClassName: 'bp-glyph', glyphMarginHoverMessage: { value: 'Breakpoint (click to remove)' }, stickiness: 1 } }));
    bpDecos.set(model.uri.toString(), model.deltaDecorations(bpDecos.get(model.uri.toString()) || [], decs));
  }
  function syncFromDecorations(model) {
    const ids = bpDecos.get(model.uri.toString());
    if (!ids || !ids.length) return;
    const lines = new Set(ids.map((id) => model.getDecorationRange(id)).filter(Boolean).map((r) => r.startLineNumber));
    bps.set(relOf(model), lines);
    persist();
  }
  async function toggleBreakpoint(model, line) {
    const rel = relOf(model);
    const set = bps.get(rel) || new Set();
    if (set.has(line)) set.delete(line); else set.add(line);
    bps.set(rel, set);
    persist();
    renderBreakpoints(model);
    renderSidebar();
    if (session && session.dap) await sendBreakpoints(rel).catch(() => {});
  }
  D.toggleAtCursor = () => { const ed = (window.ForgeSplit && window.ForgeSplit.focused()) ? window.ForgeSplit.editor : F.editor, m = ed && ed.getModel(); if (m) toggleBreakpoint(m, ed.getPosition().lineNumber); };
  async function sendBreakpoints(rel) {
    const lines = [...(bps.get(rel) || [])].sort((a, b) => a - b);
    await session.dap.request('setBreakpoints', { source: { path: F.absPath(rel), name: rel.split('/').pop() }, breakpoints: lines.map((line) => ({ line })), lines });
  }
  function hookGutter(ed) {
    ed.onMouseDown((e) => {
      const T = F.monaco.editor.MouseTargetType;
      if (e.target.type === T.GUTTER_GLYPH_MARGIN && e.target.position) toggleBreakpoint(ed.getModel(), e.target.position.lineNumber);
    });
  }
  function hookEditor() {
    const ed = F.editor;
    ed.updateOptions({ glyphMargin: true });
    hookGutter(ed);
    F.hooks.onEditorCreated.push(hookGutter);
    ed.onDidChangeModel(() => { const m = ed.getModel(); if (m) { renderBreakpoints(m); showCurrentLine(); } });
    F.monaco.editor.onDidCreateModel((m) => { renderBreakpoints(m); m.onDidChangeContent(() => syncFromDecorations(m)); });
    for (const m of F.monaco.editor.getModels()) { renderBreakpoints(m); m.onDidChangeContent(() => syncFromDecorations(m)); }
  }

  // ------------------------------------------------------------------ console
  let openLine = null;   // program output continues on the same line until it prints a newline
  function out(text, cls, raw) {
    const host = $('#debug-output');
    if (!host) return;
    if (raw) {
      const parts = text.split('\n');
      parts.forEach((part, i) => {
        if (!openLine || openLine.dataset.cls !== (cls || '')) { openLine = el('div', { class: 'dline ' + (cls || ''), 'data-cls': cls || '' }); host.append(openLine); }
        openLine.textContent += part;
        if (i < parts.length - 1) openLine = null;
      });
    } else { openLine = null; host.append(el('div', { class: 'dline ' + (cls || ''), text })); }
    while (host.children.length > 3000) host.firstChild.remove();
    host.scrollTop = host.scrollHeight;
  }
  $('#debug-input').addEventListener('keydown', async (e) => {
    if (e.key !== 'Enter') return;
    const expr = e.target.value.trim();
    if (!expr) return;
    e.target.value = '';
    out('› ' + expr, 'in');
    if (!session || session.state !== 'stopped') { out('The program is not paused. Expressions are evaluated in the paused frame.', 'err'); return; }
    try { const r = await session.dap.request('evaluate', { expression: expr, frameId: session.frameId, context: 'repl' }); out(r.result, 'res'); }
    catch (err) { out(err.message, 'err'); }
  });

  // ------------------------------------------------------------------ session control
  async function start(noDebug) {
    if (session) { if (session.state === 'stopped') return cont(); return toast('A debug session is already running.', 'error'); }
    const progInput = $('#dbg-program');
    let program = (progInput && progInput.value.trim()) || F.activePath || '';
    if (!program) return toast('Open the Python file you want to run, or type its path in Run and Debug.', 'error');
    if (!/\.py$/i.test(program)) return toast('The debugger supports Python files (.py).', 'error');
    if (F.saveAll) await F.saveAll();
    const args = ($('#dbg-args') && $('#dbg-args').value.trim()) ? $('#dbg-args').value.trim().match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g).map((a) => a.replace(/^["']|["']$/g, '')) : [];
    F.showPanel('debug');
    $('#debug-output').innerHTML = '';
    out((noDebug ? 'Running ' : 'Debugging ') + program + (args.length ? ' ' + args.join(' ') : ''), 'info');
    const s = session = { dap: null, state: 'starting', threadId: null, frames: [], frameId: null, reason: '', noDebug };
    renderSidebar();
    s.dap = new Dap((ev) => onEvent(s, ev));
    try {
      await s.dap.connect();
      await s.dap.request('initialize', { clientID: 'forge', clientName: 'Forge IDE', adapterID: 'debugpy', pathFormat: 'path', linesStartAt1: true, columnsStartAt1: true, supportsVariableType: true, supportsRunInTerminalRequest: false, locale: 'en' });
      s.initialized = new Promise((res) => { s.onInitialized = res; });
      const launched = s.dap.request('launch', { type: 'python', request: 'launch', name: 'Forge', program: F.absPath(program), args, cwd: F.folder, console: 'internalConsole', redirectOutput: true, justMyCode: true, showReturnValue: true, noDebug: !!noDebug, stopOnEntry: false }, 60000);
      await Promise.race([s.initialized, launched]);
      for (const rel of bps.keys()) await sendBreakpoints(rel).catch(() => {});
      await s.dap.request('setExceptionBreakpoints', { filters: ['uncaught'] }).catch(() => {});
      await s.dap.request('configurationDone', {}).catch(() => {});
      await launched;
      if (s.state === 'starting') s.state = 'running';
      renderSidebar();
    } catch (e) {
      out('Could not start: ' + e.message, 'err');
      toast(e.message, 'error', 6000);
      end(s);
    }
  }
  function end(s) {
    if (session !== s) return;
    session = null;
    try { s.dap && s.dap.close(); } catch (e) { /* ignore */ }
    clearCurrentLine();
    renderSidebar();
  }
  async function stop() {
    const s = session;
    if (!s) return;
    try { await s.dap.request('disconnect', { terminateDebuggee: true }, 5000); } catch (e) { /* ignore */ }
    out('Stopped.', 'info');
    end(s);
  }
  async function restart() { const nd = session && session.noDebug; await stop(); setTimeout(() => start(nd), 400); }
  const step = (cmd) => async () => {
    if (!session || session.state !== 'stopped') return;
    session.state = 'running'; clearCurrentLine(); renderSidebar();
    try { await session.dap.request(cmd, { threadId: session.threadId }); } catch (e) { out(e.message, 'err'); }
  };
  const cont = step('continue'), next = step('next'), stepIn = step('stepIn'), stepOut = step('stepOut');
  async function pause() { if (session && session.state === 'running') { try { await session.dap.request('pause', { threadId: session.threadId || 1 }); } catch (e) { out(e.message, 'err'); } } }

  async function onEvent(s, ev) {
    if (session !== s) return;
    const b = ev.body || {};
    switch (ev.event) {
      case 'initialized': if (s.onInitialized) s.onInitialized(); break;
      case 'output': if (b.category !== 'telemetry' && b.output) out(b.output, b.category === 'stderr' ? 'err' : b.category === 'console' ? 'info' : '', true); break;
      case 'stopped': {
        s.state = 'stopped'; s.threadId = b.threadId || s.threadId; s.reason = b.reason + (b.text ? ': ' + b.text : '') + (b.description && !b.text ? ': ' + b.description : '');
        try {
          const st = await s.dap.request('stackTrace', { threadId: s.threadId, startFrame: 0, levels: 60 });
          s.frames = st.stackFrames || [];
          await selectFrame(s.frames.find((f) => f.source && f.source.path) || s.frames[0]);
        } catch (e) { out(e.message, 'err'); }
        if (b.reason === 'exception') { try { const ex = await s.dap.request('exceptionInfo', { threadId: s.threadId }); out('Exception: ' + (ex.exceptionId || '') + ' ' + (ex.description || ''), 'err'); } catch (e) { /* optional */ } }
        renderSidebar();
        break;
      }
      case 'continued': s.state = 'running'; clearCurrentLine(); renderSidebar(); break;
      case 'thread': if (!s.threadId && b.reason === 'started') s.threadId = b.threadId; break;
      case 'exited': out('The program exited with code ' + b.exitCode + '.', b.exitCode ? 'err' : 'info'); break;
      case 'terminated': case 'adapterExit': end(s); break;
      default: break;
    }
  }
  async function selectFrame(frame) {
    const s = session;
    if (!s || !frame) return;
    s.frameId = frame.id;
    s.frame = frame;
    if (frame.source && frame.source.path) {
      await F.openFile(frame.source.path, { line: frame.line, column: frame.column || 1 });
      showCurrentLine();
    }
    try {
      const sc = await s.dap.request('scopes', { frameId: frame.id });
      s.scopes = sc.scopes || [];
    } catch (e) { s.scopes = []; }
    renderSidebar();
  }
  function clearCurrentLine() { if (F.editor) curDeco = F.editor.deltaDecorations(curDeco, []); }
  function showCurrentLine() {
    clearCurrentLine();
    const s = session, ed = F.editor;
    if (!s || s.state !== 'stopped' || !s.frame || !s.frame.source || !ed || !ed.getModel()) return;
    if (F.relPath(s.frame.source.path) !== relOf(ed.getModel())) return;
    const l = s.frame.line;
    curDeco = ed.deltaDecorations([], [{ range: new F.monaco.Range(l, 1, l, 1), options: { isWholeLine: true, className: 'dbg-line', glyphMarginClassName: 'dbg-arrow' } }]);
  }

  // ------------------------------------------------------------------ sidebar
  function varNode(v) {
    const row = el('div', { class: 'dvar' });
    const head = el('div', { class: 'dvhead' + (v.variablesReference ? ' expandable' : '') },
      el('span', { class: 'dvtw', text: v.variablesReference ? '▸' : '' }), el('span', { class: 'dvn', text: v.name }), el('span', { class: 'dvsep', text: ' = ' }),
      el('span', { class: 'dvv', text: v.value, title: (v.type ? v.type + ': ' : '') + v.value }));
    row.append(head);
    if (v.variablesReference) {
      let open = false, kids = null;
      head.onclick = async () => {
        open = !open; head.querySelector('.dvtw').textContent = open ? '▾' : '▸';
        if (!open) { if (kids) kids.remove(); kids = null; return; }
        kids = el('div', { class: 'dkids' });
        row.append(kids);
        try { const r = await session.dap.request('variables', { variablesReference: v.variablesReference }); for (const c of (r.variables || []).slice(0, 300)) kids.append(varNode(c)); }
        catch (e) { kids.append(el('div', { class: 'muted small', text: e.message })); }
      };
    }
    return row;
  }
  function section(title, ...children) { return el('div', { class: 'dsect' }, el('div', { class: 'dsect-head', text: title }), ...children); }
  let renderSeq = 0;
  async function renderSidebar() {
    const real = $('#debug-panel');
    if (!real) return;
    // Build off-screen and swap in at the end, so overlapping redraws never mix their rows.
    const mine = ++renderSeq;
    const host = el('div');
    const s = session;
    const prog = el('input', { id: 'dbg-program', type: 'text', placeholder: 'Python file (default: the open file)', value: (D.lastProgram || '') });
    prog.addEventListener('change', () => { D.lastProgram = prog.value; });
    const args = el('input', { id: 'dbg-args', type: 'text', placeholder: 'Arguments (optional)', value: D.lastArgs || '' });
    args.addEventListener('change', () => { D.lastArgs = args.value; });
    host.append(el('div', { class: 'dcfg' }, prog, args,
      el('div', { class: 'gactions', style: 'padding:6px 0 0;border:0' },
        el('button', { class: 'btn sm', text: s ? (s.state === 'stopped' ? 'Continue (F5)' : 'Running…') : 'Start debugging (F5)', disabled: !!(s && s.state !== 'stopped'), onclick: () => start(false) }),
        el('button', { class: 'btn ghost sm', text: 'Run without debugging', disabled: !!s, onclick: () => start(true) }))));
    if (s) {
      const bar = el('div', { class: 'dtools' });
      const ICONS = {
        cont: '<path d="M4.5 3v10l8-5z" fill="currentColor" stroke="none"/>', pause: '<path d="M5 3v10M11 3v10" stroke-width="2.2"/>',
        over: '<path d="M2.5 9a5.5 5.5 0 0 1 10.5-2M13.5 3.5v3.5H10"/><circle cx="8" cy="13" r="1.3" fill="currentColor"/>',
        into: '<path d="M8 2v7M5 6.5L8 9.5l3-3"/><circle cx="8" cy="13" r="1.3" fill="currentColor"/>',
        out: '<path d="M8 10V3M5 5.5L8 2.5l3 3"/><circle cx="8" cy="13" r="1.3" fill="currentColor"/>',
        restart: '<path d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9M13.5 2.5v3h-3"/>', stop: '<rect x="4" y="4" width="8" height="8" rx="1" fill="currentColor" stroke="none"/>',
      };
      const tool = (icon, title, fn, enabled, cls) => { const b = el('button', { class: 'mini dtool ' + (cls || ''), title, disabled: !enabled, onclick: fn }); b.innerHTML = '<svg viewBox="0 0 16 16">' + ICONS[icon] + '</svg>'; bar.append(b); };
      tool('cont', 'Continue (F5)', cont, s.state === 'stopped', 'go');
      tool('pause', 'Pause (F6)', pause, s.state === 'running');
      tool('over', 'Step over (F10)', next, s.state === 'stopped');
      tool('into', 'Step into (F11)', stepIn, s.state === 'stopped');
      tool('out', 'Step out (Shift+F11)', stepOut, s.state === 'stopped');
      tool('restart', 'Restart (Ctrl+Shift+F5)', restart, true);
      tool('stop', 'Stop (Shift+F5)', stop, true, 'halt');
      host.append(bar, el('div', { class: 'dstate', text: s.state === 'stopped' ? 'Paused' + (s.reason ? ' (' + s.reason + ')' : '') : s.state === 'starting' ? 'Starting…' : 'Running' }));
    }
    if (s && s.state === 'stopped') {
      const vars = el('div');
      host.append(section('Variables', vars));
      for (const sc of s.scopes || []) {
        if (sc.expensive && sc.name !== 'Locals') { vars.append(varNode({ name: sc.name, value: '', variablesReference: sc.variablesReference })); continue; }
        vars.append(el('div', { class: 'dscope', text: sc.name }));
        try { const r = await s.dap.request('variables', { variablesReference: sc.variablesReference }); for (const v of (r.variables || []).slice(0, 300)) vars.append(varNode(v)); }
        catch (e) { vars.append(el('div', { class: 'muted small', text: e.message })); }
      }
    }
    const wl = el('div');
    const winput = el('input', { type: 'text', placeholder: 'Add an expression to watch', class: 'dwatch-in' });
    winput.addEventListener('keydown', (e) => { if (e.key === 'Enter' && winput.value.trim()) { watches.push(winput.value.trim()); persist(); renderSidebar(); } });
    for (const [i, w] of watches.entries()) {
      const val = el('span', { class: 'dvv', text: s && s.state === 'stopped' ? '…' : 'not paused' });
      wl.append(el('div', { class: 'dvar dvhead' }, el('span', { class: 'dvn', text: w }), el('span', { class: 'dvsep', text: ' = ' }), val, el('span', { class: 'dx', text: '✕', title: 'Remove', onclick: () => { watches.splice(i, 1); persist(); renderSidebar(); } })));
      if (s && s.state === 'stopped') s.dap.request('evaluate', { expression: w, frameId: s.frameId, context: 'watch' }).then((r) => { val.textContent = r.result; }).catch((e) => { val.textContent = e.message; val.classList.add('err'); });
    }
    wl.append(winput);
    host.append(section('Watch', wl));
    if (s && s.state === 'stopped') {
      const cs = el('div');
      for (const f of s.frames) cs.append(el('div', { class: 'dframe' + (f.id === s.frameId ? ' active' : ''), title: (f.source && f.source.path) || '', onclick: () => selectFrame(f) },
        el('span', { class: 'dfn', text: f.name }), el('span', { class: 'dfl', text: (f.source ? (f.source.name || '') : '') + ':' + f.line })));
      host.append(section('Call stack', cs));
    }
    const bl = el('div');
    let count = 0;
    for (const [rel, set] of bps) for (const line of [...set].sort((a, b) => a - b)) {
      count++;
      bl.append(el('div', { class: 'dbp', onclick: () => F.openFile(rel, { line }) }, el('span', { class: 'bp-dot' }), el('span', { text: rel.split('/').pop() + ':' + line, title: rel }),
        el('span', { class: 'dx', text: '✕', title: 'Remove breakpoint', onclick: async (e) => { e.stopPropagation(); set.delete(line); persist(); for (const m of F.monaco.editor.getModels()) renderBreakpoints(m); if (session) await sendBreakpoints(rel).catch(() => {}); renderSidebar(); } })));
    }
    if (!count) bl.append(el('div', { class: 'muted small pad', text: 'Click left of a line number in the editor, or press F9, to add a breakpoint.' }));
    else bl.append(el('button', { class: 'btn ghost sm', text: 'Remove all breakpoints', onclick: async () => { const rels = [...bps.keys()]; bps.clear(); persist(); for (const m of F.monaco.editor.getModels()) renderBreakpoints(m); if (session) for (const r of rels) await sendBreakpoints(r).catch(() => {}); renderSidebar(); } }));
    host.append(section('Breakpoints', bl));
    if (mine !== renderSeq) return;
    const focused = document.activeElement && real.contains(document.activeElement) ? document.activeElement.id : null;
    real.replaceChildren(...host.childNodes);
    if (focused && $('#' + focused)) $('#' + focused).focus();
    $('#sb-debug').textContent = s ? (s.state === 'stopped' ? 'Debugger paused' : 'Debugging') : '';
  }
  D.render = renderSidebar;
  D.start = start; D.stop = stop; D.active = () => !!session;

  window.addEventListener('keydown', (e) => {
    if (e.key === 'F5' && !e.shiftKey && !e.ctrlKey) { e.preventDefault(); session && session.state === 'stopped' ? cont() : start(false); }
    else if (e.key === 'F5' && e.ctrlKey && e.shiftKey) { e.preventDefault(); restart(); }
    else if (e.key === 'F5' && e.ctrlKey) { e.preventDefault(); start(true); }
    else if (e.key === 'F5' && e.shiftKey) { e.preventDefault(); stop(); }
    else if (e.key === 'F6') { e.preventDefault(); pause(); }
    else if (e.key === 'F9') { e.preventDefault(); D.toggleAtCursor(); }
    else if (e.key === 'F10') { e.preventDefault(); next(); }
    else if (e.key === 'F11' && e.shiftKey) { e.preventDefault(); stepOut(); }
    else if (e.key === 'F11') { e.preventDefault(); stepIn(); }
  }, true);
  const wait = setInterval(() => { if (F.editor && F.monaco) { clearInterval(wait); hookEditor(); renderSidebar(); } }, 400);
})();
