/* Forge IDE: source control, notepads, problems, terminal AI commands, rules and MCP settings. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, $$, el, esc, get, post, toast } = F;
  const X = window.ForgeExtras = {};

  // ------------------------------------------------------------------ source control
  let gitState = null;
  X.loadGit = async function () {
    const host = $('#git-panel');
    try {
      const g = await get('/api/git');
      gitState = g;
      host.innerHTML = '';
      if (!g.repo) {
        host.append(el('div', { class: 'pad muted', text: 'This folder is not a git repository.' }),
          el('div', { class: 'gactions' }, el('button', { class: 'btn sm', text: 'Initialize repository', onclick: async () => { await post('/api/git/init', {}); X.loadGit(); F.refreshStatus(); } })));
        return;
      }
      host.append(el('div', { class: 'gsum', text: g.summary || ('On ' + g.branch), title: g.summary || '' }));
      const ta = el('textarea', { id: 'commit-msg', placeholder: 'Commit message (Ctrl+Enter to commit)', spellcheck: 'false' });
      ta.value = sessionStorage.getItem('forge.commitMsg') || '';
      ta.addEventListener('input', () => sessionStorage.setItem('forge.commitMsg', ta.value));
      ta.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') commit(); });
      host.append(ta);
      host.append(el('div', { class: 'gactions' },
        el('button', { class: 'btn sm', text: 'Commit', title: 'Commit staged changes (stages everything if nothing is staged)', onclick: commit }),
        el('button', { class: 'btn ghost sm', text: '✨ Generate message', title: 'Let the model write the commit message from the diff', onclick: X.generateCommitMessage }),
        el('button', { class: 'btn ghost sm', text: 'Stage all', onclick: async () => { await post('/api/git/stage', {}); X.loadGit(); } }),
        el('button', { class: 'btn ghost sm', text: 'Unstage all', onclick: async () => { await post('/api/git/unstage', {}); X.loadGit(); } }),
        el('button', { class: 'btn ghost sm', text: 'Pull', onclick: () => gitRun('/api/git/pull') }),
        el('button', { class: 'btn ghost sm', text: 'Push', onclick: () => gitRun('/api/git/push') })));
      const staged = g.files.filter((f) => f.staged), unstaged = g.files.filter((f) => f.unstaged);
      const section = (title, list, stagedList) => {
        host.append(el('div', { class: 'gsect' }, el('span', { text: title + ' (' + list.length + ')' })));
        if (!list.length) host.append(el('div', { class: 'muted', style: 'padding:2px 12px 6px', text: 'none' }));
        for (const f of list) {
          const row = el('div', { class: 'gfile', title: f.path, onclick: () => showGitDiff(f.path) },
            el('span', { class: 'gs ' + f.status[0], text: f.status }), el('span', { class: 'gp', text: f.path }));
          const acts = el('span', { class: 'ga' });
          acts.append(el('button', { class: 'mini', text: '⤢', title: 'Open file', onclick: (e) => { e.stopPropagation(); F.openFile(f.path); } }));
          if (stagedList) acts.append(el('button', { class: 'mini', text: '−', title: 'Unstage', onclick: async (e) => { e.stopPropagation(); await post('/api/git/unstage', { paths: [f.path] }); X.loadGit(); } }));
          else {
            acts.append(el('button', { class: 'mini', text: '+', title: 'Stage', onclick: async (e) => { e.stopPropagation(); await post('/api/git/stage', { paths: [f.path] }); X.loadGit(); } }));
            if (f.status !== '??') acts.append(el('button', { class: 'mini', text: '↶', title: 'Discard changes (a copy is kept in AI Changes)', onclick: async (e) => { e.stopPropagation(); if (!confirm('Discard your changes to ' + f.path + '? A copy stays in the AI Changes view.')) return; await post('/api/git/discard', { path: f.path }); F.refreshFile(f.path); X.loadGit(); } }));
          }
          row.append(acts);
          host.append(row);
        }
      };
      section('Staged changes', staged, true);
      section('Changes', unstaged, false);
      try { const l = await get('/api/git/log'); host.append(el('div', { class: 'gsect', text: 'Recent commits' }), el('pre', { class: 'glog', text: l.log || '(no commits yet)' })); } catch (e) { /* ignore */ }
    } catch (e) { host.innerHTML = '<div class="pad muted">' + esc(e.message) + '</div>'; }
  };
  async function gitRun(path) {
    try { const r = await post(path, {}); toast((r.ok ? 'Done: ' : 'Failed: ') + (r.output || '').trim().slice(-300), r.ok ? 'ok' : 'error', 6000); X.loadGit(); F.refreshStatus(); } catch (e) { toast(e.message, 'error'); }
  }
  async function commit() {
    const ta = $('#commit-msg');
    const msg = ta.value.trim();
    if (!msg) return toast('Write a commit message first, or click Generate message.', 'error');
    const stageAll = gitState && !gitState.files.some((f) => f.staged);
    try {
      const r = await post('/api/git/commit', { message: msg, stage_all: stageAll });
      toast(r.ok ? 'Committed.' : 'Commit failed: ' + (r.output || '').slice(-300), r.ok ? 'ok' : 'error', 5000);
      if (r.ok) { ta.value = ''; sessionStorage.removeItem('forge.commitMsg'); }
      X.loadGit(); F.refreshStatus();
    } catch (e) { toast(e.message, 'error'); }
  }
  X.generateCommitMessage = async function () {
    const ta = $('#commit-msg');
    if (!ta) return;
    ta.placeholder = 'Generating a commit message with the model…'; ta.disabled = true;
    try {
      const r = await post('/api/git/commit_message', {});
      if (r.message) { ta.value = r.message; sessionStorage.setItem('forge.commitMsg', r.message); }
      else toast(r.note || 'No changes to describe.', 'error');
    } catch (e) { toast(e.message, 'error', 7000); }
    ta.disabled = false; ta.placeholder = 'Commit message (Ctrl+Enter to commit)';
  };
  async function showGitDiff(path) {
    try {
      const d = await get('/api/git/diff', { path });
      await F.openDiff({ title: path + ' (HEAD → working tree)', original: d.original, modified: d.modified, language: F.langFor(path), acceptLabel: 'Close', rejectLabel: 'Open file', onReject: () => F.openFile(path) });
    } catch (e) { toast(e.message, 'error'); }
  }
  $('#btn-git-refresh').onclick = X.loadGit;

  // ------------------------------------------------------------------ notepads
  let pads = [], activePad = -1;
  X.notepads = () => pads;
  async function loadPads() { try { pads = (await get('/api/notepads')).notepads || []; } catch (e) { pads = []; } }
  async function savePads() { try { await post('/api/notepads', { notepads: pads }); } catch (e) { toast(e.message, 'error'); } }
  X.renderNotepads = async function () {
    await loadPads();
    const host = $('#notepads');
    host.innerHTML = '';
    if (!pads.length) host.append(el('div', { class: 'pad muted', text: 'No notepads yet. A notepad is reusable context (conventions, API notes, a plan) you attach to a chat with @notepad.' }));
    pads.forEach((p, i) => host.append(el('div', { class: 'np' + (i === activePad ? ' active' : ''), onclick: () => { activePad = i; X.renderNotepads(); } },
      el('span', { class: 'ht', text: p.name || 'Untitled' }),
      el('span', { class: 'nx', text: '✕', title: 'Delete notepad', onclick: async (e) => { e.stopPropagation(); if (!confirm('Delete notepad "' + p.name + '"?')) return; pads.splice(i, 1); activePad = -1; await savePads(); X.renderNotepads(); } }))));
    if (activePad >= 0 && pads[activePad]) {
      const p = pads[activePad];
      const name = el('input', { value: p.name, placeholder: 'Name' });
      const text = el('textarea', { placeholder: 'Notes the model should know when you attach this notepad…', spellcheck: 'false' });
      text.value = p.text || '';
      const save = F.debounce(async () => { p.name = name.value.trim() || 'Untitled'; p.text = text.value; await savePads(); const row = host.querySelectorAll('.np')[activePad]; if (row) row.querySelector('.ht').textContent = p.name; }, 500);
      name.addEventListener('input', save); text.addEventListener('input', save);
      host.append(el('div', { class: 'np-editor' }, name, text,
        el('button', { class: 'btn ghost sm', text: 'Attach to chat', onclick: () => { window.ForgeChat.insertText('@notepad: ' + p.name + ' '); } })));
    }
  };
  $('#btn-notepad-new').onclick = async () => { await loadPads(); pads.push({ name: 'Notepad ' + (pads.length + 1), text: '' }); activePad = pads.length - 1; await savePads(); X.renderNotepads(); };
  loadPads();

  // ------------------------------------------------------------------ problems
  const problems = new Map();   // path -> text
  X.reportCheck = function (path, text) {
    if (!path) return;
    if (/SYNTAX CHECK FAILED/.test(text)) { problems.set(path, text); F.showPanel('problems'); }
    else problems.delete(path);
    renderProblems();
  };
  function renderProblems() {
    const host = $('#problems');
    host.innerHTML = '';
    if (!problems.size) { host.append(el('div', { class: 'pad muted', text: 'No problems. Syntax checks run after every save and agent edit.' })); return; }
    for (const [path, text] of problems) {
      const m = text.match(/line (\d+)/i);
      host.append(el('div', { class: 'problem' }, el('span', { class: 'pi', text: '✖' }),
        el('div', { class: 'pt', text: path + (m ? ':' + m[1] : '') + '\n' + text }),
        el('span', { class: 'pf', text: 'Open', onclick: () => F.openFile(path, { line: m ? Number(m[1]) : undefined }) }),
        el('span', { class: 'pf', text: 'Fix with AI', onclick: () => window.ForgeChat.sendMessage('Fix this syntax error in ' + path + ':\n\n' + text, 'agent', { kind: 'file', path }) })));
    }
  }

  // ------------------------------------------------------------------ terminal AI (Ctrl+K)
  X.openTerminalAI = function () {
    F.showPanel('terminal');
    const w = $('#term-ai');
    w.classList.remove('hidden');
    const i = $('#term-ai-input'); i.value = ''; i.focus();
    $('#term-ai-status').textContent = '';
  };
  function closeTerminalAI() { $('#term-ai').classList.add('hidden'); if (window.ForgeTerm) window.ForgeTerm.fitAll(); }
  $('#term-ai-close').onclick = closeTerminalAI;
  $('#btn-term-ai').onclick = X.openTerminalAI;
  $('#term-ai-input').addEventListener('keydown', async (e) => {
    if (e.key === 'Escape') return closeTerminalAI();
    if (e.key !== 'Enter') return;
    const instruction = e.target.value.trim();
    if (!instruction) return;
    const st = $('#term-ai-status');
    st.textContent = 'Asking the model…'; e.target.disabled = true;
    try {
      const r = await post('/api/terminal_command', { instruction, cwd: F.status.workspace });
      if (!r.command) throw new Error('no command returned');
      st.textContent = (r.dangerous ? '⚠ This command looks destructive. ' : '') + 'Pasted into the terminal; press Enter there to run it.';
      window.ForgeTerm.paste(r.command);
      setTimeout(closeTerminalAI, 1800);
    } catch (err) { st.textContent = 'Error: ' + err.message; }
    e.target.disabled = false;
  });

  // ------------------------------------------------------------------ GPU engine card
  const inApp = () => !!(window.ForgeApp && typeof window.ForgeApp.engineStatus === 'function');
  let engineTimer = null;
  async function renderEngineCard(host) {
    host.append(el('div', { class: 'sgroup', text: 'GPU engine (Intel graphics, via the Forge app)' }));
    const box = el('div', { class: 'srow' });
    host.append(box);
    const statusEl = el('div', { class: 'hint', text: 'Checking…' });
    const bar = el('div', { style: 'height:6px;border-radius:3px;background:var(--bg-4);overflow:hidden;display:none' }, el('div', { style: 'height:100%;width:0;background:var(--accent)' }));
    const actions = el('div', { class: 'gactions', style: 'padding:6px 0 0;border:0' });
    const logBox = el('pre', { class: 'glog', style: 'max-height:160px;overflow:auto;display:none;background:var(--bg);border-radius:6px;padding:6px' });
    const btnStart = el('button', { class: 'btn sm', text: 'Start engine', onclick: () => { if (inApp()) { window.ForgeApp.engineStart(); refresh(); } } });
    const btnStop = el('button', { class: 'btn ghost sm', text: 'Stop engine', onclick: () => { if (inApp()) { window.ForgeApp.engineStop(); refresh(); } } });
    const btnUse = el('button', { class: 'btn ghost sm', text: 'Use GPU engine for chats', onclick: () => F.saveSetting('engine', 'gpu') });
    const btnCpu = el('button', { class: 'btn ghost sm', text: 'Back to Ollama (CPU)', onclick: () => F.saveSetting('engine', 'ollama') });
    const btnLog = el('button', { class: 'btn ghost sm', text: 'Engine log', onclick: () => { logBox.style.display = logBox.style.display === 'none' ? 'block' : 'none'; refresh(); } });
    actions.append(btnStart, btnStop, btnUse, btnCpu, btnLog);
    box.append(el('label', { text: 'Status' }), statusEl, bar, actions, logBox,
      el('div', { class: 'hint', text: 'The engine is llama.cpp with its Vulkan backend, running on the Googlebook itself (outside the Linux VM) so it can use the Intel GPU. The first start downloads the model weights from Ollama\'s store once (4.9 GB). Memory: the engine needs about 6 GB free on the Googlebook; it is the first thing Android reclaims under pressure, never the VM.' }));
    async function refresh() {
      if (!document.body.contains(box)) { clearInterval(engineTimer); engineTimer = null; return; }
      let server = {};
      try { server = await get('/api/engine/status'); } catch (e) { /* ignore */ }
      const h = (server.health && server.health.status) || 'down';
      let app = null;
      if (inApp()) { try { app = JSON.parse(window.ForgeApp.engineStatus()); } catch (e) { app = null; } }
      const parts = [];
      if (app) {
        parts.push('App: ' + app.state + (app.detail ? ' · ' + app.detail : ''));
        if (app.state === 'downloading' && app.downloadTotal) { bar.style.display = 'block'; bar.firstChild.style.width = Math.round(100 * app.downloaded / app.downloadTotal) + '%'; } else bar.style.display = 'none';
        if (app.hostMemAvailableMb >= 0) parts.push('Googlebook free RAM: ' + (app.hostMemAvailableMb / 1024).toFixed(1) + ' GB');
        if (!app.binary) parts.push('This app build has no engine binary.');
        btnStart.disabled = app.state === 'running' || app.state === 'downloading' || app.state === 'starting' || app.state === 'paused' || !app.binary;
        btnStop.disabled = app.state === 'stopped';
      } else {
        parts.push('Open Forge in the Forge app to start or stop the engine; this page only shows its status.');
        btnStart.disabled = btnStop.disabled = true;
      }
      parts.push('Server sees the engine as: ' + h + ' at ' + (server.url || ''));
      try {
        const v = await get('/api/vision/status');
        const vs = (v.app && v.app.vstate) || 'stopped';
        const tiers = (v.tiers_available || []).join(' and ') || 'none installed';
        parts.push('Vision model: ' + (v.app_connected ? vs + (v.app && v.app.vtier ? ' (' + v.app.vtier + ')' : '') + (v.app && v.app.vdetail && vs !== 'running' ? ' · ' + v.app.vdetail : '') : 'needs the Forge app to run on the GPU') + '. Available: ' + tiers + '. Text recognition: ' + (v.ocr ? 'ready' : 'not installed') + '.');
      } catch (e) { /* older server */ }
      if (server.props && server.props.model_path) parts.push('Model: ' + String(server.props.model_path).split('/').pop());
      parts.push('Chats use: ' + (server.engine === 'gpu' ? 'GPU engine' : 'Ollama (CPU in the VM)'));
      statusEl.textContent = parts.join('\n');
      statusEl.style.whiteSpace = 'pre-line';
      btnUse.disabled = h !== 'ok' || server.engine === 'gpu';
      btnCpu.disabled = server.engine !== 'gpu';
      if (logBox.style.display !== 'none' && inApp()) { try { logBox.textContent = window.ForgeApp.engineLog(); logBox.scrollTop = logBox.scrollHeight; } catch (e) { /* ignore */ } }
    }
    refresh();
    clearInterval(engineTimer);
    engineTimer = setInterval(refresh, 2500);
  }

  // ------------------------------------------------------------------ settings: rules and MCP
  X.renderSettingsExtras = async function (host) {
    await renderEngineCard(host);
    host.append(el('div', { class: 'sgroup', text: 'Rules for the AI' }));
    let rules = { global: '', project: '' };
    try { rules = await get('/api/rules'); } catch (e) { /* ignore */ }
    const mk = (key, label, hint) => {
      const ta = el('textarea', { spellcheck: 'false', placeholder: 'Write rules in plain English, one per line…' });
      ta.value = rules[key] || '';
      ta.addEventListener('change', async () => { try { await post('/api/rules', { [key]: ta.value }); toast('Rules saved', 'ok', 1200); } catch (e) { toast(e.message, 'error'); } });
      host.append(el('div', { class: 'srow' }, el('label', { text: label }), ta, el('div', { class: 'hint', text: hint })));
    };
    mk('global', 'Global rules', 'Always included, for every workspace. Stored in ~/.forge-ide/rules.md.');
    mk('project', 'Project rules', 'For this workspace only. Stored in .forge/rules.md (a .cursorrules, AGENTS.md or CLAUDE.md file is read too).');

    host.append(el('div', { class: 'sgroup', text: 'MCP servers' }));
    let mcp = { servers: [], config: '' };
    try { mcp = await get('/api/mcp'); } catch (e) { /* ignore */ }
    const list = el('div');
    const renderServers = (servers) => {
      list.innerHTML = '';
      if (!servers.length) list.append(el('div', { class: 'hint', text: 'No MCP servers configured.' }));
      for (const s of servers) list.append(el('div', { class: 'mcp-server' }, el('span', { text: s.name + ' · ' + (s.tools.length ? s.tools.length + ' tools' : 'no tools yet') }),
        el('span', { class: s.error ? 'bad' : (s.running ? 'ok' : ''), text: s.error ? 'error: ' + s.error : (s.running ? 'running' : 'stopped') })));
    };
    renderServers(mcp.servers || []);
    const ta = el('textarea', { spellcheck: 'false', style: 'min-height:140px' });
    ta.value = mcp.config || '';
    host.append(el('div', { class: 'srow' }, el('label', { text: 'Configuration (' + (mcp.config_path || '~/.forge-ide/mcp.json') + ')' }), ta,
      el('div', { class: 'hint', text: 'Example: {"servers": {"fs": {"command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/home/you/projects"]}}}. Tools appear to the agent as mcp_<server>_<tool> and need approval to run.' }),
      el('div', { class: 'gactions', style: 'padding:6px 0 0;border:0' },
        el('button', { class: 'btn sm', text: 'Save and reload', onclick: async () => { try { const r = await post('/api/mcp/config', { config: ta.value }); renderServers(r.servers); toast('MCP configuration saved', 'ok'); } catch (e) { toast(e.message, 'error', 6000); } } }),
        el('button', { class: 'btn ghost sm', text: 'Connect servers', onclick: async () => { try { const r = await post('/api/mcp/connect', {}); renderServers(r.servers); } catch (e) { toast(e.message, 'error'); } } })),
      list));
  };
})();
