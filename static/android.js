/* Forge IDE: the Android view. The installed toolchains, the Googlebook as the device over adb, build, install and
 * launch for Android projects, a screenshot of the running app for the chat (the vision model looks at it for
 * Llama), a live logcat, and Cargo commands for Rust projects. Builds run in a terminal tab through forge-gradle
 * and forge-cargo, which check free memory first and run inside a 2 GB memory cap. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, el, get, post, toast } = F;
  const A = window.ForgeAndroid = {};
  let info = null, watching = null;

  function row(label, value, ok) {
    return el('div', { class: 'tc-row' }, el('span', { class: 'tc-dot ' + (ok ? 'ok' : 'no') }), el('span', { class: 'tc-l', text: label }), el('span', { class: 'tc-v', text: value }));
  }
  function section(title, ...kids) { return el('div', { class: 'dsect' }, el('div', { class: 'dsect-head', text: title }), ...kids); }
  const runInTerminal = (cmd, label) => { F.showPanel('terminal'); if (window.ForgeTerm && window.ForgeTerm.run) window.ForgeTerm.run(cmd, label); };

  async function render() {
    const host = $('#android-panel');
    if (!host) return;
    host.innerHTML = '<div class="muted pad">Checking the toolchains and the device…</div>';
    try { info = await get('/api/android/status'); } catch (e) { host.innerHTML = ''; host.append(el('div', { class: 'muted pad', text: e.message })); return; }
    const t = info.toolchains, p = info.project, devs = info.devices || [];
    host.innerHTML = '';
    // ---------------- device
    const online = devs.filter((d) => d.state === 'device');
    const devBox = el('div');
    if (online.length) for (const d of online) devBox.append(row(d.model ? d.model.replace(/_/g, ' ') : 'Device', d.serial, true));
    else devBox.append(el('div', { class: 'muted small', text: 'No device is connected over adb.' }));
    devBox.append(el('div', { class: 'gactions', style: 'padding:6px 0 0;border:0' },
      el('button', { class: 'btn ghost sm', text: online.length ? 'Reconnect' : 'Connect to this Googlebook', onclick: async () => { const r = await post('/api/android/connect', {}); toast(r.message, r.ok ? 'ok' : 'error', 5000); render(); } }),
      el('button', { class: 'btn ghost sm', text: 'Screenshot to chat', disabled: !online.length, title: 'Takes a screenshot of the Googlebook and attaches it to the chat, so the vision model can look at your app for Llama', onclick: screenshotToChat }),
      el('button', { class: 'btn ghost sm', text: 'Logcat', onclick: () => openLogcat('') })));
    host.append(section('Device', devBox, el('div', { class: 'hint', text: 'Your Googlebook is the test device, over Wireless debugging. The Linux VM has no virtualization support, so there is no emulator.' })));
    // ---------------- this project
    if (p && p.android) {
      const box = el('div');
      for (const m of p.modules.filter((x) => x.application)) {
        box.append(row(m.module + ' (' + (m.application_id || 'unknown id') + ')', m.apk ? 'APK built ' + new Date(m.apk_time * 1000).toLocaleString() : 'not built yet', !!m.apk));
        box.append(el('div', { class: 'gactions', style: 'padding:4px 0 8px;border:0' },
          el('button', { class: 'btn sm', text: 'Build, install and run', title: 'Builds the debug APK in a terminal, then installs it on the Googlebook and starts it', onclick: () => buildInstallRun(m, true) }),
          el('button', { class: 'btn ghost sm', text: 'Build debug APK', onclick: () => buildInstallRun(m, false) }),
          el('button', { class: 'btn ghost sm', text: 'Install', disabled: !m.apk || !online.length, onclick: () => installApk(m) }),
          el('button', { class: 'btn ghost sm', text: 'Run', disabled: !m.application_id || !online.length, onclick: () => launchApp(m) }),
          el('button', { class: 'btn ghost sm', text: 'App logcat', disabled: !m.application_id, onclick: () => openLogcat(m.application_id) })));
      }
      box.append(el('div', { class: 'gactions', style: 'padding:0;border:0' },
        el('button', { class: 'btn ghost sm', text: 'Lint', onclick: () => runInTerminal('forge-gradle lint', 'Android lint') }),
        el('button', { class: 'btn ghost sm', text: 'Unit tests', onclick: () => runInTerminal('forge-gradle test', 'Gradle tests') }),
        el('button', { class: 'btn ghost sm', text: 'Release bundle (AAB)', onclick: () => runInTerminal('forge-gradle bundleRelease', 'Gradle bundle') })));
      if (!p.gradlew) box.append(el('div', { class: 'hint', text: 'This project has no gradlew; forge-gradle uses Gradle ' + (t.gradle || '') + ' instead.' }));
      host.append(section('This Android project', box));
    }
    // ---------------- toolchains
    const tc = el('div');
    tc.append(row('Android SDK', t.android_sdk ? 'platforms ' + t.platforms.join(', ') : 'not found', !!t.android_sdk));
    tc.append(row('Build tools', t.build_tools.join(', ') || 'none', t.build_tools.length > 0));
    tc.append(row('NDK and CMake', [t.ndk, t.cmake && 'cmake ' + t.cmake].filter(Boolean).join(', ') || 'not installed', !!t.ndk));
    tc.append(row('SDK tools', ['sdkmanager', 'lint', 'apkanalyzer', 'aapt2', 'd8', 'apksigner', 'bundletool'].filter((k) => t[k]).join(', '), t.sdkmanager));
    tc.append(row('adb', t.adb || 'not found', !!t.adb));
    tc.append(row('Java', t.java || 'not found', !!t.java));
    tc.append(row('Kotlin', t.kotlinc || 'not installed', !!t.kotlinc));
    tc.append(row('Gradle', t.gradle || 'not installed', !!t.gradle));
    tc.append(row('Rust', t.rust || 'not installed', !!t.rust));
    tc.append(row('Code intelligence', ['Kotlin ' + (t.kotlin_lsp ? '✓' : '✗'), 'Java ' + (t.jdtls ? '✓' : '✗'), 'XML ' + (t.lemminx ? '✓' : '✗'), 'Rust ' + (t.rust_analyzer ? '✓' : '✗')].join('  '), t.kotlin_lsp && t.jdtls));
    tc.append(el('div', { class: 'hint', text: 'Everything is on the PATH in Forge\'s terminal and for the agent. Gradle and Cargo builds started with forge-gradle and forge-cargo (and by the agent) check free memory first and run inside a 2 GB memory cap.' }));
    host.append(section('Toolchains', tc));
    // ---------------- Rust
    let cargo = false;
    try { cargo = (await get('/api/files', { q: 'Cargo.toml' })).files.some((f) => /(^|\/)Cargo\.toml$/.test(f)); } catch (e) { /* ignore */ }
    if (cargo && t.cargo) {
      host.append(section('This Rust project', el('div', { class: 'gactions', style: 'padding:0;border:0' },
        ...['check', 'build', 'run', 'test', 'clippy'].map((c) => el('button', { class: 'btn ghost sm', text: 'cargo ' + c, onclick: () => runInTerminal('forge-cargo ' + c, 'cargo ' + c) })))));
    }
  }

  async function screenshotToChat() {
    try {
      const meta = await post('/api/android/screenshot', {});
      if (window.ForgeChat) window.ForgeChat.addContext({ kind: 'image', id: meta.id, url: meta.url, name: 'Googlebook screen' });
      toast('Screenshot attached to the chat.', 'ok');
    } catch (e) { toast(e.message, 'error'); }
  }
  async function installApk(m) {
    toast('Installing ' + m.apk + '…', 'ok', 2500);
    const r = await post('/api/android/install', { apk: m.apk });
    toast(r.ok ? 'Installed on the Googlebook.' : 'Install failed: ' + r.message, r.ok ? 'ok' : 'error', 6000);
    return r.ok;
  }
  async function launchApp(m) {
    const r = await post('/api/android/launch', { app_id: m.application_id });
    toast(r.ok ? 'Started ' + m.application_id + '.' : 'Could not start it: ' + r.message, r.ok ? 'ok' : 'error', 6000);
  }
  /** Build in a terminal (visible, stoppable with Ctrl C); when a newer APK appears, optionally install and run it. */
  function buildInstallRun(m, thenRun) {
    const started = Date.now() / 1000;
    const cdir = m.module && m.module !== '.' ? ':' + m.module.replace(/\//g, ':') + ':' : '';
    runInTerminal('forge-gradle ' + cdir + 'assembleDebug', 'Gradle build');
    if (!thenRun) return;
    clearInterval(watching);
    let waited = 0;
    watching = setInterval(async () => {
      waited += 5;
      if (waited > 1800) { clearInterval(watching); return; }
      let p;
      try { p = (await get('/api/android/status')).project; } catch (e) { return; }
      const mm = (p.modules || []).find((x) => x.module === m.module);
      if (mm && mm.apk && mm.apk_time > started) {
        clearInterval(watching);
        if (await installApk(mm)) await launchApp(mm);
        render();
      }
    }, 5000);
  }

  // ------------------------------------------------------------------ logcat (bottom panel)
  let lc = null;
  const LEVEL = /^\S+\s+\S+\s+\d+\s+\d+\s+([VDIWEF])\s/;
  function openLogcat(appId) {
    F.showPanel('logcat');
    $('#logcat-app').value = appId || '';
    startLogcat();
  }
  function startLogcat() {
    stopLogcat();
    const out = $('#logcat-out');
    const appId = $('#logcat-app').value.trim();
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    const ws = lc = new WebSocket(proto + location.host + '/ws/logcat?app_id=' + encodeURIComponent(appId) + '&level=' + encodeURIComponent($('#logcat-level').value));
    const filter = () => $('#logcat-filter').value.toLowerCase();
    out.append(el('div', { class: 'lc-info', text: 'Logcat started' + (appId ? ' for ' + appId : '') + '.' }));
    ws.onmessage = (m) => {
      let msg;
      try { msg = JSON.parse(m.data); } catch (e) { return; }
      if (msg.type === 'info') out.append(el('div', { class: 'lc-info', text: msg.text }));
      if (msg.type === 'end') { out.append(el('div', { class: 'lc-info', text: 'The log stream ended.' })); $('#logcat-toggle').textContent = 'Start'; }
      if (msg.type !== 'lines') return;
      const near = out.scrollHeight - out.scrollTop - out.clientHeight < 60;
      const f = filter();
      for (const line of msg.lines) {
        const lv = (line.match(LEVEL) || [])[1] || '';
        const d = el('div', { class: 'lc-line lv-' + lv, text: line });
        if (f && !line.toLowerCase().includes(f)) d.classList.add('hidden');
        out.append(d);
      }
      while (out.childElementCount > 5000) out.firstChild.remove();
      if (near) out.scrollTop = out.scrollHeight;
    };
    ws.onclose = () => { if (lc === ws) { lc = null; $('#logcat-toggle').textContent = 'Start'; } };
    $('#logcat-toggle').textContent = 'Stop';
  }
  function stopLogcat() { if (lc) { const w = lc; lc = null; try { w.close(); } catch (e) { /* ignore */ } } $('#logcat-toggle').textContent = 'Start'; }
  $('#logcat-toggle').onclick = () => (lc ? stopLogcat() : startLogcat());
  $('#logcat-clear').onclick = () => { $('#logcat-out').innerHTML = ''; };
  $('#logcat-filter').addEventListener('input', () => {
    const f = $('#logcat-filter').value.toLowerCase();
    for (const d of $('#logcat-out').children) if (d.classList.contains('lc-line')) d.classList.toggle('hidden', !!f && !d.textContent.toLowerCase().includes(f));
  });
  $('#logcat-chat').onclick = () => {
    const lines = [...$('#logcat-out').querySelectorAll('.lc-line:not(.hidden)')].slice(-200).map((d) => d.textContent).join('\n');
    if (!lines) return toast('The log is empty.', 'error');
    window.ForgeChat.addContext({ kind: 'text', title: 'Logcat', path: 'Logcat (' + Math.min(200, lines.split('\n').length) + ' lines)', text: lines });
  };
  $('#btn-android-refresh').onclick = render;
  A.render = render;
  A.openLogcat = openLogcat;
  A.screenshotToChat = screenshotToChat;
})();
