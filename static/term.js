/* Forge IDE: integrated terminal (xterm.js over a WebSocket to a bash pty). */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, el, toast } = F;
  const T = window.ForgeTerm = {};
  const terms = new Map();   // id -> { term, fit, ws, host, tab }
  let active = null, counter = 0;

  const THEME = { background: '#12151d', foreground: '#d7dbe7', cursor: '#ff8a3d', selectionBackground: '#2b3550', black: '#1a1e2a', red: '#ff6b6b', green: '#4fd18b', yellow: '#ffd166', blue: '#6ea8fe', magenta: '#b48cff', cyan: '#5ad3d3', white: '#d7dbe7', brightBlack: '#5b6378', brightRed: '#ff8787', brightGreen: '#7ee2a8', brightYellow: '#ffe08a', brightBlue: '#8fbcff', brightMagenta: '#c9aaff', brightCyan: '#86e3e3', brightWhite: '#ffffff' };

  function wsUrl(id, cols, rows) {
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    return proto + location.host + '/ws/term?id=' + encodeURIComponent(id) + '&cols=' + cols + '&rows=' + rows + '&folder=' + encodeURIComponent(F.folder || '');
  }

  T.create = function (id) {
    id = id || ('t' + (++counter) + '-' + Math.random().toString(36).slice(2, 6));
    const host = el('div', { class: 'term-host' });
    $('#terminals').append(host);
    const term = new Terminal({ theme: THEME, fontFamily: 'JetBrains Mono, Fira Code, Cascadia Code, Menlo, Consolas, monospace', fontSize: 12.5, cursorBlink: true, scrollback: 5000, allowProposedApi: true });
    const fit = new FitAddon.FitAddon();
    term.loadAddon(fit);
    try { term.loadAddon(new WebLinksAddon.WebLinksAddon()); } catch (e) { /* optional */ }
    term.open(host);
    const entry = { id, term, fit, ws: null, host, tab: null, lastOut: '' };
    terms.set(id, entry);
    const tab = el('div', { class: 'ttab', text: 'bash ' + terms.size, onclick: () => T.activate(id) });
    tab.addEventListener('contextmenu', (e) => { e.preventDefault(); F.contextMenu(e, [
      { label: 'Add recent output to chat', run: () => window.ForgeChat && window.ForgeChat.addTerminalContext(entry.lastOut) },
      { label: 'Clear', run: () => term.clear() },
      { label: 'Kill terminal', run: () => T.kill(id) }]); });
    entry.tab = tab;
    $('#term-tabs').append(tab);
    T.activate(id);
    connect(entry);
    term.onData((d) => { if (entry.ws && entry.ws.readyState === 1) entry.ws.send(new TextEncoder().encode(d)); });
    term.onResize(({ cols, rows }) => { if (entry.ws && entry.ws.readyState === 1) entry.ws.send(JSON.stringify({ type: 'resize', cols, rows })); });
    term.attachCustomKeyEventHandler((e) => {
      // Let IDE shortcuts pass through; keep copy and paste with Ctrl+Shift+C / V.
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'C' || e.key === 'c')) { const s = term.getSelection(); if (s) navigator.clipboard.writeText(s); return false; }
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'V' || e.key === 'v')) { navigator.clipboard.readText().then((t) => { if (entry.ws && entry.ws.readyState === 1) entry.ws.send(new TextEncoder().encode(t)); }); return false; }
      if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K') && e.type === 'keydown') { if (window.ForgeExtras) window.ForgeExtras.openTerminalAI(); return false; }
      if ((e.ctrlKey || e.metaKey) && ['p', 'P', 'i', 'I', 'b', 'B', '`', ',', 'n', 'N'].includes(e.key) && e.type === 'keydown') return false;
      return true;
    });
    return entry;
  };

  function connect(entry) {
    const { term } = entry;
    const ws = new WebSocket(wsUrl(entry.id, term.cols, term.rows));
    ws.binaryType = 'arraybuffer';
    entry.ws = ws;
    ws.onopen = () => { ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows })); };
    ws.onmessage = (m) => {
      if (typeof m.data === 'string') {
        try { const j = JSON.parse(m.data); if (j.type === 'exit') { term.writeln('\r\n\x1b[90m[process exited]\x1b[0m'); } } catch (e) { term.write(m.data); }
        return;
      }
      const bytes = new Uint8Array(m.data);
      term.write(bytes);
      try { entry.lastOut = (entry.lastOut + new TextDecoder().decode(bytes)).slice(-8000); } catch (e) { /* ignore */ }
    };
    ws.onclose = () => { if (terms.has(entry.id)) term.writeln('\r\n\x1b[90m[disconnected: click to reconnect]\x1b[0m'); entry.ws = null; entry.host.onclick = () => { if (!entry.ws) { entry.host.onclick = null; connect(entry); } }; };
    ws.onerror = () => { /* handled by onclose */ };
  }

  T.activate = function (id) {
    active = id;
    for (const [k, e] of terms) { e.host.classList.toggle('active', k === id); e.tab.classList.toggle('active', k === id); }
    const e = terms.get(id);
    if (e) { setTimeout(() => { try { e.fit.fit(); } catch (x) { /* hidden */ } e.term.focus(); }, 0); }
  };
  T.kill = async function (id) {
    const e = terms.get(id);
    if (!e) return;
    try { await F.post('/api/terminal/kill', { id }); } catch (x) { /* ignore */ }
    if (e.ws) e.ws.close();
    e.term.dispose(); e.host.remove(); e.tab.remove();
    terms.delete(id);
    const next = terms.keys().next().value;
    if (next) T.activate(next); else active = null;
  };
  T.activeOutput = function () { const e = terms.get(active); return e ? e.lastOut : ''; };
  T.paste = function (text) { const e = terms.get(active); if (e && e.ws && e.ws.readyState === 1) { e.ws.send(new TextEncoder().encode(text)); e.term.focus(); } };
  /** Open a new terminal tab with a label and run a command in it (used by the Android view for builds). */
  T.run = function (command, label) {
    const entry = T.create();
    if (label) entry.tab.textContent = label;
    let tries = 0;
    const go = () => {
      if (entry.ws && entry.ws.readyState === 1) { setTimeout(() => entry.ws.send(new TextEncoder().encode(command + '\r')), 600); return; }
      if (++tries < 60) setTimeout(go, 100);
    };
    go();
    return entry;
  };
  T.ensure = function () { if (!terms.size) T.create(); else T.fitAll(); };
  T.fitAll = function () { for (const e of terms.values()) { if (e.host.classList.contains('active')) { try { e.fit.fit(); } catch (x) { /* ignore */ } } } };
  T.resetAll = async function () { for (const id of [...terms.keys()]) await T.kill(id); T.create(); };
  $('#btn-term-new').onclick = () => { F.showPanel('terminal'); T.create(); };
  $('#btn-term-kill').onclick = () => { if (active) T.kill(active); };
  window.addEventListener('resize', F.debounce(() => T.fitAll(), 100));
  new ResizeObserver(F.debounce(() => T.fitAll(), 50)).observe($('#terminals'));
})();
