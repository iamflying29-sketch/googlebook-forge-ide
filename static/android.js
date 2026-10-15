/* Forge IDE: the Android view. Devices (the Googlebook, virtual devices and devices paired over Wi-Fi) with one
 * target device that builds, installs, logcat and adb use; virtual devices (Android 11 to 17 in containers in the
 * Linux VM) with screen profiles; a floating device window with the live screen, taps, swipes, typing and Back, Home
 * and Recents; build, install and run for Android projects; a live logcat; the toolchains; Cargo for Rust.
 * Builds run in a terminal tab through forge-gradle and forge-cargo, which check free memory first. */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, el, get, post, toast } = F;
  const A = window.ForgeAndroid = {};
  let info = null, dev = null, watching = null, poller = null;

  const KIND = { googlebook: 'Googlebook', virtual: 'Virtual device', waydroid: 'Waydroid', phone: 'Phone', tablet: 'Tablet',
    foldable: 'Foldable', watch: 'Watch', tv: 'TV' };
  function row(label, value, ok) {
    return el('div', { class: 'tc-row' }, el('span', { class: 'tc-dot ' + (ok ? 'ok' : 'no') }), el('span', { class: 'tc-l', text: label }), el('span', { class: 'tc-v', text: value }));
  }
  function section(title, ...kids) { return el('div', { class: 'dsect' }, el('div', { class: 'dsect-head', text: title }), ...kids); }
  const actions = (...b) => el('div', { class: 'gactions', style: 'padding:4px 0 8px;border:0' }, ...b);
  const runInTerminal = (cmd, label) => { F.showPanel('terminal'); if (window.ForgeTerm && window.ForgeTerm.run) window.ForgeTerm.run(cmd, label); };
  const same = (a, b) => a && b && a.serial === b.serial && a.port === b.port;
  const targetName = () => (dev && dev.target ? dev.target.name || dev.target.serial : 'no device');

  async function render() {
    const host = $('#android-panel');
    if (!host) return;
    if (!host.childElementCount) host.innerHTML = '<div class="muted pad">Checking the devices and toolchains…</div>';
    try { [dev, info] = await Promise.all([get('/api/devices'), get('/api/android/status')]); }
    catch (e) { host.innerHTML = ''; host.append(el('div', { class: 'muted pad', text: e.message })); return; }
    host.innerHTML = '';
    host.append(devicesSection(), virtualSection(), pairSection());
    projectSection(host);
    toolchainSection(host);
    await rustSection(host);
    keepPolling();
  }

  // ---------------------------------------------------------------- devices
  function devicesSection() {
    const box = el('div');
    const online = dev.devices.filter((d) => d.state === 'device');
    for (const d of dev.devices) {
      const isTarget = same(d, dev.target);
      const facts = [d.android && 'Android ' + d.android, d.one_ui, d.width && d.width + '×' + d.height, KIND[d.kind] || d.kind].filter(Boolean).join(', ');
      box.append(el('div', { class: 'dev-row' + (isTarget ? ' target' : '') },
        el('div', { class: 'dev-name' }, el('span', { class: 'tc-dot ' + (d.state === 'device' ? 'ok' : 'no') }),
          el('b', { text: d.name || d.serial }), isTarget ? el('span', { class: 'dev-badge', text: 'target' }) : null),
        el('div', { class: 'dev-facts', text: d.state === 'device' ? facts : d.state + ' (' + d.serial + ')' }),
        actions(
          d.state === 'device' && !isTarget ? el('button', { class: 'btn sm', text: 'Use', title: 'Builds, installs, logcat and adb use this device', onclick: () => useDevice(d) }) : null,
          d.state === 'device' ? el('button', { class: 'btn ghost sm', text: 'Screen', onclick: () => openScreen(d) }) : null,
          d.state === 'device' ? el('button', { class: 'btn ghost sm', text: 'Screenshot to chat', onclick: () => screenshotToChat(d) }) : null,
          d.kind !== 'googlebook' && d.kind !== 'virtual' ? el('button', { class: 'btn ghost sm', text: 'Disconnect', onclick: async () => { const r = await post('/api/devices/disconnect', { serial: d.serial, port: d.port }); toast(r.message, r.ok ? 'ok' : 'error'); render(); } }) : null)));
    }
    if (!dev.devices.some((d) => d.kind === 'googlebook')) {
      box.append(actions(el('button', { class: 'btn ghost sm', text: 'Connect this Googlebook', onclick: async () => { const r = await post('/api/android/connect', {}); toast(r.message, r.ok ? 'ok' : 'error', 5000); render(); } })));
    }
    if (!online.length) box.append(el('div', { class: 'muted small', text: 'No device is connected. Connect the Googlebook, start a virtual device or pair a device below.' }));
    return section('Devices', box, el('div', { class: 'hint', text: 'The target device is where Build, install and run, Logcat and adb in the terminal and in the agent\'s commands go. The Googlebook\'s own screen is view only.' }));
  }
  async function useDevice(d) {
    const r = await post('/api/devices/target', { serial: d.serial, port: d.port });
    toast(r.message, r.ok ? 'ok' : 'error');
    render();
  }

  // ---------------------------------------------------------------- virtual devices
  function virtualSection() {
    const v = dev.virtual, box = el('div');
    if (!v.available) return section('Virtual devices', el('div', { class: 'hint', text: 'Virtual devices need Podman and sudo without a password in the Linux VM. See the manual, section "Virtual devices".' }));
    if (!v.guard) box.append(el('div', { class: 'warn-box', text: 'The Forge Android guard service is not running. It protects the Linux VM while Android runs, so virtual devices will not start. Install it with guard/install.sh in the Forge folder.' }));
    for (const n of v.notices || []) box.append(el('div', { class: 'warn-box', text: n.message }));
    for (const d of v.devices) {
      const busy = d.phase === 'starting' || d.phase === 'booting' || d.phase === 'stopping';
      const live = dev.devices.find((x) => x.serial === d.serial && x.port === 5041 && x.state === 'device');
      box.append(el('div', { class: 'dev-row' + (live && same(live, dev.target) ? ' target' : '') },
        el('div', { class: 'dev-name' }, el('span', { class: 'tc-dot ' + (d.running ? 'ok' : 'no') }), el('b', { text: d.name }),
          el('span', { class: 'dev-badge dim', text: d.phase })),
        el('div', { class: 'dev-facts', text: [d.version_label, (dev.profiles[(d.look || {}).profile] || {}).label, d.memory_mb ? d.memory_mb + ' MB in use' : '', d.message].filter(Boolean).join(', ') }),
        actions(
          d.running ? el('button', { class: 'btn ghost sm', text: 'Stop', disabled: busy, onclick: () => vdAction('/api/vd/stop', d) })
            : el('button', { class: 'btn sm', text: 'Start', disabled: busy, title: 'Starts the device in the Linux VM; it becomes the target device when it is ready', onclick: () => vdAction('/api/vd/start', d) }),
          live ? el('button', { class: 'btn ghost sm', text: 'Screen', onclick: () => openScreen(live) }) : null,
          live && !same(live, dev.target) ? el('button', { class: 'btn ghost sm', text: 'Use', onclick: () => useDevice(live) }) : null)));
    }
    // create
    const ver = el('select', { title: 'Android version' }, ...v.versions.map((x) => el('option', { value: x.version, text: x.label + (x.installed ? '' : ' (download first)') })));
    const typ = el('select', { title: 'Device type and screen' }, ...Object.keys(v.types).map((k) => el('option', { value: k, text: (dev.profiles[v.types[k]] || {}).label || k })));
    const dl = el('div');
    const showDl = () => {
      dl.innerHTML = '';
      const x = v.versions.find((y) => y.version === ver.value);
      if (!x) return;
      if (x.job && x.job.running) dl.append(el('div', { class: 'hint', text: 'Downloading ' + x.label + ': ' + ((x.job.lines || []).slice(-1)[0] || 'starting') }));
      else if (!x.installed) {
        dl.append(el('div', { class: 'hint', text: x.label + ' (' + x.detail + ') is not downloaded yet: ' + x.size + '.' + (x.version === '17' ? ' Android 17 is LineageOS 24.0 from the WayDroid-ATV project; Forge checks the published checksums.' : ' Android 11 to 16 come from the open-source Redroid project.') }),
          actions(el('button', { class: 'btn ghost sm', text: 'Download ' + x.label, onclick: async () => { const r = await post('/api/vd/download', { version: x.version }); toast(r.message, r.ok ? 'ok' : 'error', 6000); render(); } })));
      } else if (x.job && x.job.ok === false) dl.append(el('div', { class: 'warn-box', text: 'The last download failed: ' + (x.job.lines || []).slice(-2).join(' ') }));
    };
    ver.onchange = showDl;
    showDl();
    box.append(el('div', { class: 'dev-create' }, ver, typ,
      el('button', { class: 'btn ghost sm', text: 'Create', onclick: async () => {
        const x = v.versions.find((y) => y.version === ver.value);
        if (!x.installed) return toast('Download ' + x.label + ' first.', 'error');
        const r = await post('/api/vd/create', { version: ver.value, type: typ.value });
        toast(r.message, r.ok ? 'ok' : 'error'); render();
      } })), dl);
    return section('Virtual devices', box, el('div', { class: 'hint', text: 'Real Android running in the Linux VM, without hardware virtualization. One device needs its memory limit (2.5 to 3 GB) plus 2.3 GB free on the Googlebook, so stop it, or unload the Llama model, when memory is short. Screen profiles, dark theme and text size change live in the device window. Watches are watch-sized, round screens of phone Android, because Wear OS itself cannot run here.' }));
  }
  async function vdAction(path, d) {
    const r = await post(path, { id: d.id });
    toast(r.message, r.ok ? 'ok' : 'error', 7000);
    render();
  }
  function keepPolling() {
    clearTimeout(poller);
    const v = dev && dev.virtual;
    const active = v && (v.devices.some((d) => ['starting', 'booting', 'stopping'].includes(d.phase)) || v.versions.some((x) => x.job && x.job.running));
    if (active && $('.sidebar .view.active[data-view="android"]')) poller = setTimeout(render, 2500);
  }

  // ---------------------------------------------------------------- pairing
  function pairSection() {
    const addr = el('input', { placeholder: 'Pairing address and port (shown under Pair device with pairing code)', spellcheck: 'false' });
    const code = el('input', { placeholder: 'Six-digit pairing code', spellcheck: 'false', inputmode: 'numeric', maxlength: '6' });
    const conn = el('input', { placeholder: 'Device address and port (shown under Wireless debugging)', spellcheck: 'false' });
    const found = el('div');
    return section('Pair a phone, tablet, watch or TV',
      el('div', { class: 'hint', text: 'On the device: Developer options, Wireless debugging, Pair device with pairing code. Galaxy Watch and Wear OS watches have the same option under Developer options (on Galaxy Watch, tap Software version five times first). The device and this Googlebook must be on the same Wi-Fi.' }),
      el('div', { class: 'dev-form' }, addr, code, el('button', { class: 'btn sm', text: 'Pair', onclick: async () => {
        const r = await post('/api/devices/pair', { address: addr.value, code: code.value });
        toast(r.message, r.ok ? 'ok' : 'error', 7000);
        if (r.ok) { code.value = ''; toast('Paired. Now enter the address shown under Wireless debugging (not the pairing one) and click Connect.', 'ok', 8000); }
      } })),
      el('div', { class: 'dev-form' }, conn, el('button', { class: 'btn ghost sm', text: 'Connect', onclick: async () => {
        const r = await post('/api/devices/connect', { address: conn.value });
        toast(r.message, r.ok ? 'ok' : 'error', 6000); if (r.ok) render();
      } }), el('button', { class: 'btn ghost sm', text: 'Look for devices', title: 'Devices that announce Wireless debugging on the network (the Linux VM often cannot see these announcements)', onclick: async () => {
        found.innerHTML = '';
        const r = await get('/api/devices/scan');
        if (!r.found.length) found.append(el('div', { class: 'muted small', text: 'No announcements reached the Linux VM. Type the address shown on the device instead.' }));
        for (const x of r.found) found.append(el('div', { class: 'tc-row' }, el('span', { class: 'tc-dot ok' }), el('span', { class: 'tc-l', text: x.service }), el('span', { class: 'tc-v', text: x.name + ' ' + x.address })));
      } })), found);
  }

  // ---------------------------------------------------------------- project, toolchains, Rust
  function projectSection(host) {
    const p = info.project;
    if (!(p && p.android)) return;
    const t = dev.target;
    const box = el('div');
    for (const m of p.modules.filter((x) => x.application)) {
      box.append(row(m.module + ' (' + (m.application_id || 'unknown id') + ')', m.apk ? 'APK built ' + new Date(m.apk_time * 1000).toLocaleString() : 'not built yet', !!m.apk));
      box.append(actions(
        el('button', { class: 'btn sm', text: 'Build, install and run', title: 'Builds the debug APK in a terminal, then installs it on ' + targetName() + ' and starts it', onclick: () => buildInstallRun(m, true) }),
        el('button', { class: 'btn ghost sm', text: 'Build debug APK', onclick: () => buildInstallRun(m, false) }),
        el('button', { class: 'btn ghost sm', text: 'Install', disabled: !m.apk || !t, onclick: () => installApk(m) }),
        el('button', { class: 'btn ghost sm', text: 'Run', disabled: !m.application_id || !t, onclick: () => launchApp(m) }),
        el('button', { class: 'btn ghost sm', text: 'App logcat', disabled: !m.application_id, onclick: () => openLogcat(m.application_id) })));
    }
    box.append(actions(
      el('button', { class: 'btn ghost sm', text: 'Lint', onclick: () => runInTerminal('forge-gradle lint', 'Android lint') }),
      el('button', { class: 'btn ghost sm', text: 'Unit tests', onclick: () => runInTerminal('forge-gradle test', 'Gradle tests') }),
      el('button', { class: 'btn ghost sm', text: 'Release bundle (AAB)', onclick: () => runInTerminal('forge-gradle bundleRelease', 'Gradle bundle') })));
    if (!p.gradlew) box.append(el('div', { class: 'hint', text: 'This project has no gradlew; forge-gradle uses Gradle ' + (info.toolchains.gradle || '') + ' instead.' }));
    box.append(el('div', { class: 'hint', text: 'Target device: ' + targetName() + '.' }));
    host.append(section('This Android project', box));
  }
  function toolchainSection(host) {
    const t = info.toolchains, tc = el('div');
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
    tc.append(el('div', { class: 'hint', text: 'Everything is on the PATH in Forge\'s terminal and for the agent, together with forge-device. Gradle and Cargo builds started with forge-gradle and forge-cargo (and by the agent) check free memory first and run inside a memory cap.' }));
    host.append(section('Toolchains', tc));
  }
  async function rustSection(host) {
    let cargo = false;
    try { cargo = (await get('/api/files', { q: 'Cargo.toml' })).files.some((f) => /(^|\/)Cargo\.toml$/.test(f)); } catch (e) { /* ignore */ }
    if (cargo && info.toolchains.cargo) {
      host.append(section('This Rust project', actions(...['check', 'build', 'run', 'test', 'clippy'].map((c) => el('button', { class: 'btn ghost sm', text: 'cargo ' + c, onclick: () => runInTerminal('forge-cargo ' + c, 'cargo ' + c) })))));
    }
  }

  async function screenshotToChat(d) {
    try {
      const t = d || (dev && dev.target);
      const meta = await post('/api/android/screenshot', t ? { serial: t.serial, port: t.port } : {});
      if (window.ForgeChat) window.ForgeChat.addContext({ kind: 'image', id: meta.id, url: meta.url, name: (t && t.name ? t.name : 'Device') + ' screen' });
      toast('Screenshot attached to the chat.', 'ok');
    } catch (e) { toast(e.message, 'error'); }
  }
  async function installApk(m) {
    toast('Installing ' + m.apk + ' on ' + targetName() + '…', 'ok', 2500);
    const r = await post('/api/android/install', { apk: m.apk });
    toast(r.ok ? 'Installed on ' + targetName() + '.' : 'Install failed: ' + r.message, r.ok ? 'ok' : 'error', 6000);
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

  // ---------------------------------------------------------------- device window
  let win = null;
  function openScreen(d) {
    if (!win) win = buildWindow();
    win.show(d);
  }
  function buildWindow() {
    const img = el('img', { class: 'devwin-screen', alt: 'Device screen', draggable: 'false', tabindex: '0' });
    const frame = el('div', { class: 'devwin-frame' }, img);
    const title = el('span', { class: 'devwin-title' });
    const prof = el('select', { title: 'Screen profile' });
    const dark = el('button', { class: 'btn ghost sm', text: 'Dark' });
    const font = el('select', { title: 'Text size' }, ...[['1', 'Text 100%'], ['1.15', 'Text 115%'], ['1.3', 'Text 130%'], ['1.5', 'Text 150%'], ['2', 'Text 200%']].map(([v, l]) => el('option', { value: v, text: l })));
    const rot = el('button', { class: 'btn ghost sm', text: 'Rotate' });
    const shot = el('button', { class: 'btn ghost sm', text: 'To chat', title: 'Attach this screen to the chat so the vision model can look at it for Llama' });
    const close = el('button', { class: 'mini', text: '✕', title: 'Close (the device keeps running)' });
    const text = el('input', { class: 'devwin-text', placeholder: 'Type text for the device, then Enter', spellcheck: 'false' });
    const key = (k, label) => el('button', { class: 'btn ghost sm', text: label, onclick: () => send('key', { key: k }) });
    const nav = el('div', { class: 'devwin-nav' }, key('back', 'Back'), key('home', 'Home'), key('recents', 'Recents'), text);
    const note = el('div', { class: 'devwin-note' });
    const look = el('div', { class: 'devwin-look' }, prof, dark, font, rot);
    const box = el('div', { class: 'devwin hidden' },
      el('div', { class: 'devwin-head' }, title, shot, close), look, el('div', { class: 'devwin-body' }, frame), nav, note);
    document.body.append(box);
    let d = null, timer = null, gen = 0, lookState = {};
    for (const [k, p] of Object.entries((dev && dev.profiles) || {})) prof.append(el('option', { value: k, text: p.label + ' ' + p.width + '×' + p.height }));

    async function send(kind, extra) {
      try { const r = await post('/api/devices/input', Object.assign({ serial: d.serial, port: d.port, kind }, extra)); if (!r.ok && r.message) toast(r.message, 'error'); }
      catch (e) { toast(e.message, 'error'); }
      refreshSoon();
    }
    async function setLook(change) {
      const r = await post('/api/devices/look', Object.assign({ serial: d.serial, port: d.port }, change));
      if (!r.ok) return toast(r.message, 'error');
      lookState = r.look || lookState;
      applyLook();
      refreshSoon(900);
    }
    function applyLook() {
      prof.value = lookState.profile || prof.value;
      dark.classList.toggle('on', !!lookState.dark);
      font.value = String(lookState.font_scale || 1);
      frame.classList.toggle('round', (lookState.profile || '') === 'watch-round');
    }
    prof.onchange = () => setLook({ profile: prof.value });
    dark.onclick = () => setLook({ dark: !lookState.dark });
    font.onchange = () => setLook({ font_scale: parseFloat(font.value) });
    rot.onclick = () => setLook({ rotation: ((lookState.rotation || 0) + 1) % 4 });
    shot.onclick = () => screenshotToChat(d);
    close.onclick = () => { box.classList.add('hidden'); clearTimeout(timer); gen++; };
    text.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); if (text.value) send('text', { text: text.value }); text.value = ''; setTimeout(() => send('key', { key: 'enter' }), 150); }
    });

    // Screen: one picture at a time, the next one asked for when the last arrived (about two per second).
    function refreshSoon(ms) { clearTimeout(timer); timer = setTimeout(refresh, ms || 250); }
    function refresh() {
      if (!d || box.classList.contains('hidden') || document.hidden) return;
      const my = ++gen, t0 = Date.now();
      const next = new Image();
      next.onload = () => { if (my !== gen) return; img.src = next.src; timer = setTimeout(refresh, Math.max(350, Date.now() - t0)); };
      next.onerror = () => { if (my !== gen) return; timer = setTimeout(refresh, 2500); };
      next.src = '/api/devices/screen?' + new URLSearchParams({ serial: d.serial, port: d.port, t: t0 });
    }
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshSoon(); });

    // Pointer: a short press is a tap, a long one a long press, a drag a swipe; the wheel scrolls.
    let down = null;
    const toDevice = (e) => {
      const r = img.getBoundingClientRect();
      return { x: Math.round((e.clientX - r.left) * img.naturalWidth / r.width), y: Math.round((e.clientY - r.top) * img.naturalHeight / r.height) };
    };
    img.addEventListener('pointerdown', (e) => { if (!d.input) return; e.preventDefault(); img.focus(); down = Object.assign(toDevice(e), { t: Date.now(), cx: e.clientX, cy: e.clientY }); });
    img.addEventListener('pointerup', (e) => {
      if (!down || !d.input) return;
      const up = toDevice(e), ms = Date.now() - down.t, moved = Math.hypot(e.clientX - down.cx, e.clientY - down.cy) > 8;
      if (!moved) send(ms > 550 ? 'long' : 'tap', { x: down.x, y: down.y });
      else send('swipe', { x: down.x, y: down.y, x2: up.x, y2: up.y, ms: Math.min(1500, Math.max(80, ms)) });
      down = null;
    });
    let wheelBusy = false;
    img.addEventListener('wheel', (e) => {
      if (!d.input) return;
      e.preventDefault();
      if (wheelBusy) return;
      wheelBusy = true; setTimeout(() => { wheelBusy = false; }, 250);
      const p = toDevice(e), dy = Math.max(-0.35, Math.min(0.35, -e.deltaY / 600)) * img.naturalHeight;
      send('swipe', { x: p.x, y: p.y, x2: p.x, y2: Math.round(p.y + dy), ms: 180 });
    }, { passive: false });
    img.addEventListener('keydown', (e) => {
      if (!d.input) return;
      const map = { Escape: 'back', Backspace: 'delete', Enter: 'enter', Tab: 'tab', ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
      if (map[e.key]) { e.preventDefault(); send('key', { key: map[e.key] }); }
      else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey) { e.preventDefault(); send('text', { text: e.key }); }
    });

    // Drag the window by its title bar.
    const head = box.querySelector('.devwin-head');
    head.addEventListener('pointerdown', (e) => {
      if (e.target.closest('button,select,input')) return;
      const r = box.getBoundingClientRect(), ox = e.clientX - r.left, oy = e.clientY - r.top;
      const move = (ev) => { box.style.left = Math.max(0, ev.clientX - ox) + 'px'; box.style.top = Math.max(0, ev.clientY - oy) + 'px'; box.style.right = 'auto'; };
      const stop = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop); };
      window.addEventListener('pointermove', move); window.addEventListener('pointerup', stop);
    });

    return {
      show(device) {
        d = Object.assign({}, device);
        d.input = device.kind !== 'googlebook';
        const vd = device.virtual_id ? (dev.virtual.devices.find((x) => x.id === device.virtual_id) || {}) : null;
        lookState = vd ? Object.assign({}, vd.look || {}) : {};
        title.textContent = device.name || device.serial;
        look.classList.toggle('hidden', !vd);
        nav.classList.toggle('hidden', !d.input);
        note.textContent = d.input ? 'Click to tap, hold to long press, drag to swipe, scroll to scroll. Click the screen, then type to send keys.' : 'This is your Googlebook\'s own screen, so Forge only shows it.';
        applyLook();
        box.classList.remove('hidden');
        img.removeAttribute('src');
        refreshSoon(0);
      },
    };
  }

  // ---------------------------------------------------------------- logcat (bottom panel)
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
    out.append(el('div', { class: 'lc-info', text: 'Logcat started on ' + targetName() + (appId ? ' for ' + appId : '') + '.' }));
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
  A.openScreen = openScreen;
})();
