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
    host.append(devicesSection(), virtualSection(), labSection(), pairSection());
    projectSection(host);
    multiplatformSection(host);
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
    const sh = v.shield || {};
    if (v.guard && sh.warning) box.append(el('div', { class: 'warn-box', text: sh.warning }));
    else if (v.guard && sh.fresh === false) box.append(el('div', { class: 'warn-box', text: 'The guard\'s VM shield has not reported for over a minute. Check it with: systemctl status forge-android-guard' }));
    else if (v.guard && sh.fresh) box.append(el('div', { class: 'hint', text: 'VM shield on: Android cannot suspend, hibernate, restart or crash the Linux VM, and Android\'s changes to the VM\'s kernel settings are put back.' + (sh.fixed ? ' In the last 15 minutes it put back ' + sh.fixed + (sh.fixed === 1 ? ' change.' : ' changes.') : '') }));
    for (const d of v.devices) {
      const busy = d.phase === 'starting' || d.phase === 'booting' || d.phase === 'stopping';
      const live = dev.devices.find((x) => x.serial === d.serial && x.port === 5041 && x.state === 'device');
      box.append(el('div', { class: 'dev-row' + (live && same(live, dev.target) ? ' target' : '') },
        el('div', { class: 'dev-name' }, el('span', { class: 'tc-dot ' + (d.running ? 'ok' : 'no') }), el('b', { text: d.name }),
          d.persona ? el('span', { class: 'dev-badge', text: 'persona', title: 'Android with this device\'s screen, identity, features and battery behavior. It is not the maker\'s own interface; for that, use a real device or a device lab.' }) : null,
          el('span', { class: 'dev-badge dim', text: d.phase })),
        el('div', { class: 'dev-facts', text: [d.version_label, (dev.profiles[(d.look || {}).profile] || {}).label, d.memory_mb ? d.memory_mb + ' MB in use' : '', d.message].filter(Boolean).join(', ') }),
        actions(
          d.running ? el('button', { class: 'btn ghost sm', text: 'Stop', disabled: busy, onclick: () => vdAction('/api/vd/stop', d) })
            : el('button', { class: 'btn sm', text: 'Start', disabled: busy, title: 'Starts the device in the Linux VM; it becomes the target device when it is ready', onclick: () => vdAction('/api/vd/start', d) }),
          live ? el('button', { class: 'btn ghost sm', text: 'Screen', onclick: () => openScreen(live) }) : null,
          live && !same(live, dev.target) ? el('button', { class: 'btn ghost sm', text: 'Use', onclick: () => useDevice(live) }) : null,
          !d.running && d.upgrades && d.upgrades.length ? el('button', { class: 'btn ghost sm', text: 'Upgrade to ' + d.upgrades[d.upgrades.length - 1].label,
            title: 'Moves this device to the newer installed version of its family and keeps its apps and data; Android migrates them on the next start, as a phone does after a system upgrade',
            onclick: async () => { const r = await post('/api/vd/move', { id: d.id, version: d.upgrades[d.upgrades.length - 1].version }); toast(r.message, r.ok ? 'ok' : 'error', 8000); render(); } }) : null)));
    }
    box.append(updatesBlock(v));
    // create
    const ver = el('select', { title: 'Android version' }, ...v.versions.map((x) => el('option', { value: x.version, text: x.label + (x.installed ? '' : ' (download first)') })));
    const typ = el('select', { title: 'Device type and screen' }, ...Object.keys(v.types).map((k) => el('option', { value: k, text: (dev.profiles[v.types[k]] || {}).label || k })));
    const pers = el('select', { title: 'Persona: Android dressed as a specific device (its screen, cutout, identity, features and the maker\'s battery behavior)' },
      el('option', { value: '', text: 'No persona' }));
    const makers = {};
    for (const x of dev.personas || []) (makers[x.maker + ' (' + x.skin + ')'] = makers[x.maker + ' (' + x.skin + ')'] || []).push(x);
    for (const [m, list] of Object.entries(makers)) {
      const g = el('optgroup', { label: m });
      for (const x of list) g.append(el('option', { value: x.id, text: x.name + ' persona' + (x.certainty === 'exact' ? '' : ' (approximate)') }));
      pers.append(g);
    }
    const persHint = el('div', { class: 'hint' });
    pers.onchange = () => {
      const x = (dev.personas || []).find((y) => y.id === pers.value);
      typ.disabled = !!x;
      persHint.textContent = x ? x.name + ': ' + x.width + '×' + x.height + ' at ' + x.density + ' dpi' + (x.cutout ? ', camera hole' : '') + ', ' + x.maker + ' identity and features' + (x.behavior && x.behavior !== 'stock' && x.behavior !== 'pixel' ? ', ' + x.maker + ' battery behavior available' : '') + '. ' + (x.certainty === 'exact' ? 'Screen values from the maker\'s published data or the device\'s own source tree. ' : 'Some screen values are approximate. ') + (x.note || '') : '';
    };
    const dl = el('div');
    const showDl = () => {
      dl.innerHTML = '';
      const x = v.versions.find((y) => y.version === ver.value);
      if (!x) return;
      if (x.job && x.job.running) dl.append(el('div', { class: 'hint', text: 'Downloading ' + x.label + ': ' + ((x.job.lines || []).slice(-1)[0] || 'starting') }));
      else if (!x.installed) {
        dl.append(el('div', { class: 'hint', text: x.label + ' (' + x.detail + ') is not downloaded yet: ' + x.size + '.' + (x.backend === 'waydroid' ? ' It is LineageOS from the WayDroid-ATV project, a community build made for containers; Forge checks the published checksums.' + (x.version.includes('google') ? ' Its Google apps are the ones that project includes. Signing in to Google Play may first need the device registered at google.com/android/uncertified.' : '') : ' Android 11 to 16 come from the open-source Redroid project.') }),
          actions(el('button', { class: 'btn ghost sm', text: 'Download ' + x.label, onclick: async () => { const r = await post('/api/vd/download', { version: x.version }); toast(r.message, r.ok ? 'ok' : 'error', 6000); render(); } })));
      } else if (x.job && x.job.ok === false) dl.append(el('div', { class: 'warn-box', text: 'The last download failed: ' + (x.job.lines || []).slice(-2).join(' ') }));
    };
    ver.onchange = () => {   // some images are only for TVs, others only for phones and tablets
      const x = v.versions.find((y) => y.version === ver.value) || {};
      for (const o of typ.options) o.disabled = !!(x.types && !x.types.includes(o.value));
      if (typ.selectedOptions[0] && typ.selectedOptions[0].disabled) typ.value = [...typ.options].find((o) => !o.disabled).value;
      showDl();
    };
    ver.onchange();
    box.append(el('div', { class: 'dev-create' }, ver, typ, pers,
      el('button', { class: 'btn ghost sm', text: 'Create', onclick: async () => {
        const x = v.versions.find((y) => y.version === ver.value);
        if (!x.installed) return toast('Download ' + x.label + ' first.', 'error');
        const r = await post('/api/vd/create', { version: ver.value, type: typ.value, persona: pers.value });
        toast(r.message, r.ok ? 'ok' : 'error'); render();
      } })), persHint, dl);
    return section('Virtual devices', box, el('div', { class: 'hint', text: 'Real Android running in the Linux VM, without hardware virtualization. One device needs its memory limit (2.5 to 3 GB) plus 2.3 GB free on the Googlebook, so stop it, or unload the Llama model, when memory is short. Screen profiles, dark theme and text size change live in the device window. Watches are watch-sized, round screens of phone Android, because Wear OS itself cannot run here. Versions marked (ARM, emulated) are the makers\' own Android under ARM emulation: real but slow, 15 to 30 minutes to the first screen.' }));
  }
  // Updates and upgrades of the images (forge/updates.py): what the daily check found, buttons to install, the mode.
  function updatesBlock(v) {
    const u = v.updates || {};
    const box = el('div', { class: 'dev-row' });
    const when = u.checked ? new Date(u.checked * 1000).toLocaleString() : 'never';
    const head = el('div', { class: 'dev-name' }, el('b', { text: 'Updates and upgrades' }),
      el('span', { class: 'dev-badge dim', text: u.checking ? 'checking' : (u.applying ? 'installing ' + u.applying : (u.available ? u.available + ' available' : 'current')) }));
    box.append(head, el('div', { class: 'dev-facts', text: 'Last check: ' + when + (u.error ? '. ' + u.error : '') + '. Every image comes from its project\'s own release feed: a newer build of the same version is an update, and the same family for a newer Android is an upgrade.' }));
    for (const e of u.errors || []) box.append(el('div', { class: 'warn-box', text: 'The check could not reach ' + e }));
    for (const it of u.items || []) {
      if (!it.update && !it.upgrade && !it.error && !it.upgrade_blocked) continue;
      const row = el('div', { class: 'dev-facts' }, el('b', { text: it.label + ': ' }));
      if (it.error) row.append(el('span', { text: 'check failed: ' + it.error }));
      if (it.update) row.append(el('span', { text: 'update available, ' + it.update.label + ' (' + it.update.size + '). ' }),
        el('button', { class: 'btn sm', text: 'Update', disabled: !!u.applying, onclick: async () => { const r = await post('/api/vd/updates/apply', { version: it.version, kind: 'update' }); toast(r.message, r.ok ? 'ok' : 'error', 8000); render(); } }));
      if (it.upgrade) row.append(el('span', { text: (it.update ? ' ' : '') + 'upgrade available: ' + it.upgrade.label + ' (' + it.upgrade.size + ')' + (it.upgrade.installed ? ', installed; devices can move to it with their Upgrade button. ' : '. ') }),
        it.upgrade.installed ? null : el('button', { class: 'btn sm', text: 'Upgrade', disabled: !!u.applying, onclick: async () => { const r = await post('/api/vd/updates/apply', { version: it.version, kind: 'upgrade' }); toast(r.message, r.ok ? 'ok' : 'error', 8000); render(); } }));
      if (it.upgrade_blocked) row.append(el('span', { class: 'muted', text: ' ' + it.upgrade_blocked }));
      box.append(row);
    }
    const mode = el('select', { title: 'What the daily run does by itself' },
      el('option', { value: 'check', text: 'Daily: check and show what is available' }),
      el('option', { value: 'install', text: 'Daily: check, download and install updates and upgrades' }),
      el('option', { value: 'install-move', text: 'Daily: install, and move stopped devices to the newest version' }),
      el('option', { value: 'off', text: 'Never check by itself' }));
    mode.value = (F.settings && F.settings.vd_updates) || 'check';
    mode.onchange = async () => { await post('/api/settings', { vd_updates: mode.value }); if (F.settings) F.settings.vd_updates = mode.value; toast('Saved.', 'ok'); };
    box.append(actions(
      el('button', { class: 'btn ghost sm', text: 'Check now', disabled: !!u.checking, onclick: async () => { const r = await post('/api/vd/updates/check', {}); toast(r.message, r.ok ? 'ok' : 'error', 6000); setTimeout(render, 4000); } }),
      el('button', { class: 'btn sm', text: 'Update and upgrade everything', disabled: !!u.applying || !u.available, title: 'Installs every available update and upgrade, one after another; devices then move with their Upgrade button, or by themselves in the third daily mode',
        onclick: async () => { const r = await post('/api/vd/updates/apply_all', {}); toast(r.message, r.ok ? 'ok' : 'error', 8000); render(); } }),
      mode));
    for (const l of u.log || []) box.append(el('div', { class: 'muted small', text: new Date(l.time * 1000).toLocaleString() + ' ' + l.message }));
    return box;
  }
  async function vdAction(path, d) {
    const r = await post(path, { id: d.id });
    toast(r.message, r.ok ? 'ok' : 'error', 7000);
    render();
  }
  function keepPolling() {
    clearTimeout(poller);
    const v = dev && dev.virtual;
    const active = v && ((v.updates && (v.updates.checking || v.updates.applying)) || v.devices.some((d) => ['starting', 'booting', 'stopping'].includes(d.phase)) || v.versions.some((x) => x.job && x.job.running));
    if (active && $('.sidebar .view.active[data-view="android"]')) poller = setTimeout(render, 2500);
  }

  // ---------------------------------------------------------------- device labs (the makers' real software)
  const link = (url, text) => el('a', { href: url, target: '_blank', rel: 'noopener', text: text || url });
  function labSection() {
    const box = el('div');
    box.append(el('div', { class: 'hint', text: 'Samsung\'s One UI, Motorola\'s My UX, OnePlus\'s OxygenOS and Google\'s Pixel UI are built for the makers\' ARM phones, so besides the emulated ARM devices Forge connects real devices: yours over Wi-Fi (below), or a device in Google\'s or Samsung\'s lab. A lab device appears under Devices and works in the device window, with installs, logcat and the debugger.' }));
    // Google's device lab
    const g = el('div', { class: 'lab-card' }, el('b', { text: 'Google\'s device lab' }), el('div', { class: 'hint', text: 'Real Pixel, Samsung Galaxy, Motorola, OnePlus, OPPO, realme, Xiaomi, Sony and Nothing phones, a Pixel Tablet and a Pixel Watch, reserved through Google\'s Android command line tool.' }));
    const gBody = el('div', { class: 'hint', text: 'Checking…' });
    g.append(gBody);
    box.append(g);
    (async () => {
      let st;
      try { st = await get('/api/cloud/google'); } catch (e) { gBody.textContent = e.message; return; }
      gBody.innerHTML = '';
      if (!st.installed) { gBody.textContent = 'Google\'s Android command line tool is not installed in the Linux VM.'; return; }
      if (!st.signed_in) {
        gBody.append(el('div', { text: 'Sign in once with your Google account: run "android auth login" in a terminal and follow the link it shows. Forge never sees your password.' }),
          actions(el('button', { class: 'btn ghost sm', text: 'Open a terminal for sign-in', onclick: () => runInTerminal('android auth login', 'Google sign-in') })));
      }
      gBody.append(el('div', { class: 'hint', text: st.price }));
      const project = el('input', { placeholder: 'Google Cloud project ID', spellcheck: 'false', value: localStorage.getItem('forge.labProject') || '' });
      const pick = el('select', { title: 'Device and Android version' }, el('option', { value: '', text: 'Loading the lab\'s devices…' }));
      const out = el('pre', { class: 'lab-out hidden' });
      const reserve = el('button', { class: 'btn sm', text: 'Reserve', disabled: !st.signed_in, onclick: async () => {
        const target = pick.value, pj = project.value.trim();
        if (!target || !pj) return toast('Choose a device and enter your project ID.', 'error');
        localStorage.setItem('forge.labProject', pj);
        if (!confirm('Reserve ' + pick.selectedOptions[0].text + ' in Google\'s device lab?\n\n' + st.price + '\n\nReturn the device when you are done, so it stops costing money.')) return;
        const r = await post('/api/cloud/google/reserve', { target, project: pj, confirmed: true });
        toast(r.message, r.ok ? 'ok' : 'error', 8000);
      } });
      const list = el('button', { class: 'btn ghost sm', text: 'My reservations', disabled: !st.signed_in, onclick: async () => {
        const pj = project.value.trim();
        const r = await get('/api/cloud/google/list?' + new URLSearchParams({ project: pj }));
        out.classList.remove('hidden'); out.textContent = r.text || 'No reservations.';
      } });
      const resId = el('input', { placeholder: 'Reservation ID (from My reservations)', spellcheck: 'false' });
      const giveBack = el('button', { class: 'btn ghost sm', text: 'Return device', disabled: !st.signed_in, onclick: async () => {
        const r = await post('/api/cloud/google/release', { reservation: resId.value.trim(), project: project.value.trim() });
        toast(r.message, r.ok ? 'ok' : 'error', 8000); render();
      } });
      const extend = el('button', { class: 'btn ghost sm', text: 'Extend 15 minutes', disabled: !st.signed_in, onclick: async () => {
        if (!confirm('Extend this reservation by 15 minutes?\n\n' + st.price)) return;
        const r = await post('/api/cloud/google/extend', { reservation: resId.value.trim(), project: project.value.trim(), minutes: 15, confirmed: true });
        toast(r.message, r.ok ? 'ok' : 'error', 8000);
      } });
      gBody.append(el('div', { class: 'dev-create' }, pick, project, reserve), el('div', { class: 'dev-create' }, list, resId, giveBack, extend), out);
      for (const j of Object.values(st.jobs || {})) gBody.append(el('div', { class: j.ok === false ? 'warn-box' : 'hint', text: j.what + (j.running ? ': running' : j.ok ? ': done' : ': failed') + (j.lines && j.lines.length ? '. ' + j.lines.slice(-3).join(' ') : '') }));
      try {
        const m = await get('/api/cloud/google/models');
        pick.innerHTML = '';
        pick.append(el('option', { value: '', text: m.ok ? 'Choose a device' : 'The device list could not be read' }));
        for (const mk of m.makers || []) {
          const og = el('optgroup', { label: mk.maker + ' (' + mk.models.length + ')' });
          for (const md of mk.models) for (const t of md.targets) og.append(el('option', { value: t, text: md.name + ', Android API ' + t.split('/')[1] }));
          pick.append(og);
        }
      } catch (e) { pick.innerHTML = ''; pick.append(el('option', { value: '', text: e.message })); }
    })();
    // Samsung's Remote Test Lab
    const sBody = el('div');
    const conn = el('input', { placeholder: 'localhost:PORT shown by Remote Debug Bridge', spellcheck: 'false' });
    box.append(el('div', { class: 'lab-card' }, el('b', { text: 'Samsung Remote Test Lab (free)' }),
      el('div', { class: 'hint' }, document.createTextNode('Real Galaxy phones, foldables, tablets and Galaxy Watches with One UI, including One UI 9 on Android 17. Sign in on Samsung\'s site for 20 free credits a day (one credit is 15 minutes) and reserve a device there: '), link('https://developer.samsung.com/remotetestlab/')),
      el('div', { class: 'hint', text: 'To use it in Forge: in the lab\'s device screen, open Remote Debug Bridge and download Samsung\'s rdb program for Linux, save it in Documents, unzip it and run it in Forge\'s terminal (cd /mnt/shared/Documents && unzip rdb.zip && ./rdb), click Connect on Samsung\'s page, then enter the localhost port it shows here. Accept the "Allow USB debugging" prompt in the lab\'s screen the first time.' }),
      el('div', { class: 'dev-create' }, conn, el('button', { class: 'btn ghost sm', text: 'Connect', onclick: async () => {
        const r = await post('/api/devices/connect', { address: conn.value.trim() });
        toast(r.message, r.ok ? 'ok' : 'error', 8000); render();
      } }), el('button', { class: 'btn ghost sm', text: 'What is in the lab', onclick: async () => {
        sBody.textContent = 'Reading Samsung\'s device list…';
        const r = await get('/api/cloud/samsung');
        sBody.innerHTML = '';
        if (!r.ok) { sBody.textContent = r.message; return; }
        sBody.append(el('div', { class: 'hint', text: r.units + ' Android devices in Samsung\'s labs (and ' + r.tizen_units + ' Samsung TVs, which run Tizen, not Android). The newest:' }));
        for (const p of r.products.slice(0, 25)) sBody.append(el('div', { class: 'tc-row' }, el('span', { class: 'tc-l', text: p.name }), el('span', { class: 'tc-v', text: p.versions.slice(0, 2).join(', ') + ' (' + p.units + ')' })));
      } })), sBody));
    // Amazon
    box.append(el('div', { class: 'lab-card' }, el('b', { text: 'Amazon Fire TV' }),
      el('div', { class: 'hint' }, document.createTextNode('Amazon has no emulator for Fire OS. Its free Appstore Quality Central gives every developer cloud Fire TV devices in the browser: '), link('https://developer.amazon.com/docs/app-testing/live-device-interaction-virtual.html'), document.createTextNode('. In Forge, the Fire TV Stick 4K Max persona gives Android TV Fire TV\'s screen, identity and features.'))));
    return section('Device labs: the makers\' real software', box);
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
    A.appId = (p.modules.find((x) => x.application && x.application_id) || {}).application_id || '';
    for (const m of p.modules.filter((x) => x.application)) {
      box.append(row(m.module + ' (' + (m.application_id || 'unknown id') + ')', m.apk ? 'APK built ' + new Date(m.apk_time * 1000).toLocaleString() : 'not built yet', !!m.apk));
      box.append(actions(
        el('button', { class: 'btn sm', text: 'Build, install and run', title: 'Builds the debug APK in a terminal, then installs it on ' + targetName() + ' and starts it', onclick: () => buildInstallRun(m, true) }),
        el('button', { class: 'btn ghost sm', text: 'Build debug APK', onclick: () => buildInstallRun(m, false) }),
        el('button', { class: 'btn ghost sm', text: 'Install', disabled: !m.apk || !t, onclick: () => installApk(m) }),
        el('button', { class: 'btn ghost sm', text: 'Run', disabled: !m.application_id || !t, onclick: () => launchApp(m) }),
        el('button', { class: 'btn ghost sm', text: 'Debug on device', disabled: !m.application_id || !t, title: 'Starts the installed debug build on ' + targetName() + ' and attaches the debugger (breakpoints in the gutter, F9)', onclick: () => window.ForgeDebug && window.ForgeDebug.attachAndroid({ app_id: m.application_id }) }),
        el('button', { class: 'btn ghost sm', text: 'App logcat', disabled: !m.application_id, onclick: () => openLogcat(m.application_id) }),
        el('button', { class: 'btn ghost sm', text: 'Compose previews', title: 'Renders every @Preview of this module as pictures on phone, foldable, tablet, round and square watch and TV screens, without a device (forge-preview)',
          onclick: () => runInTerminal('forge-preview ' + (m.module === '.' ? ':' : ':' + m.module.split('/').join(':')), 'Compose previews') })));
    }
    box.append(actions(
      el('button', { class: 'btn ghost sm', text: 'Lint', onclick: () => runInTerminal('forge-gradle lint', 'Android lint') }),
      el('button', { class: 'btn ghost sm', text: 'Unit tests', onclick: () => runInTerminal('forge-gradle test', 'Gradle tests') }),
      el('button', { class: 'btn ghost sm', text: 'Release bundle (AAB)', onclick: () => runInTerminal('forge-gradle bundleRelease', 'Gradle bundle') })));
    if (!p.gradlew) box.append(el('div', { class: 'hint', text: 'This project has no gradlew; forge-gradle uses Gradle ' + (info.toolchains.gradle || '') + ' instead.' }));
    box.append(el('div', { class: 'hint', text: 'Target device: ' + targetName() + '.' }));
    host.append(section('This Android project', box));
  }
  /** Kotlin Multiplatform modules: build, test and run each target with forge-gradle. iOS needs macOS and Xcode. */
  function multiplatformSection(host) {
    const mods = info.multiplatform || [];
    if (!mods.length) return;
    const box = el('div');
    const T = { jvm: 'JVM', desktop: 'Desktop', android: 'Android', js: 'JavaScript', wasmJs: 'Wasm', ios: 'iOS', iosArm64: 'iOS', iosX64: 'iOS', iosSimulatorArm64: 'iOS', native: 'Native', apple: 'Apple', web: 'Web', linuxX64: 'Linux', macos: 'macOS' };
    for (const m of mods) {
      const p = m.gradle_path;
      const targets = [...new Set(m.targets.map((x) => T[x] || x))];
      box.append(row(m.module, 'targets: ' + (targets.join(', ') || 'none found'), true));
      const btn = (text, task, title) => el('button', { class: 'btn ghost sm', text, title: title || task, onclick: () => runInTerminal('forge-gradle ' + p + ':' + task, text) });
      const has = (x) => m.targets.includes(x);
      box.append(actions(
        btn('Check common code', 'compileCommonMainKotlinMetadata'),
        has('jvm') ? btn('Build JVM', 'compileKotlinJvm') : null,
        has('jvm') ? btn('JVM tests', 'jvmTest') : null,
        has('desktop') ? btn('Build desktop', 'compileKotlinDesktop') : null,
        m.compose_desktop ? btn('Run desktop app', 'run', 'Compose Multiplatform desktop app (it opens in the Linux VM\'s display)') : null,
        has('android') && m.android_app ? btn('Build Android', 'assembleDebug') : null,
        has('js') ? btn('Run in browser (JS)', 'jsBrowserDevelopmentRun') : null,
        has('wasmJs') ? btn('Run in browser (Wasm)', 'wasmJsBrowserDevelopmentRun') : null,
        btn('All tests', 'allTests')));
      if (m.targets.some((x) => x.startsWith('ios') || x === 'apple' || x.startsWith('macos'))) box.append(el('div', { class: 'hint', text: 'iOS and macOS targets can only be compiled on a Mac with Xcode. Forge still checks their shared Kotlin code, but not their Apple APIs.' }));
    }
    box.append(el('div', { class: 'hint', text: 'Code intelligence treats each source set as its own module (commonMain, jvmMain, androidMain and so on), so expect and actual declarations link up and Android APIs only resolve in Android code. JavaScript, Wasm and iOS sets are checked as shared Kotlin; their platform APIs are not known to the Kotlin server.' }));
    host.append(section('Kotlin Multiplatform', box));
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
    const hole = el('div', { class: 'devwin-hole hidden' });   // Android leaves its own cutout drawing out of screenshots
    const frame = el('div', { class: 'devwin-frame' }, img, hole);
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
    const bsel = el('select', { title: 'A maker\'s battery behavior, made with Android\'s own controls, for the app below' });
    const bpkg = el('input', { class: 'devwin-pkg', placeholder: 'App package, for example com.example.app', spellcheck: 'false' });
    const bgo = el('button', { class: 'btn ghost sm', text: 'Apply' });
    const behave = el('div', { class: 'devwin-look' }, bsel, bpkg, bgo);
    const box = el('div', { class: 'devwin hidden' },
      el('div', { class: 'devwin-head' }, title, shot, close), look, behave, el('div', { class: 'devwin-body' }, frame), nav, note);
    document.body.append(box);
    let d = null, timer = null, gen = 0, lookState = {}, persona = null;
    function fillProfiles(own) {   // the general profiles and this device's own persona screen
      prof.innerHTML = '';
      for (const [k, p] of Object.entries((dev && dev.profiles) || {})) {
        if (k.startsWith('persona:') && k !== own) continue;
        prof.append(el('option', { value: k, text: (k === own ? 'Persona: ' : '') + p.label + ' ' + p.width + '×' + p.height }));
      }
    }
    bgo.onclick = async () => {
      const r = await post('/api/devices/behave', { serial: d.serial, port: d.port, preset: bsel.value, package: bpkg.value.trim() });
      toast(r.message, r.ok ? 'ok' : 'error', 8000);
    };
    bsel.onchange = () => { const b = (dev.behaviors || {})[bsel.value]; bsel.title = b ? b.about : 'Undo every behavior for this app'; };

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
      const own = persona && lookState.profile === 'persona:' + persona.id && !(lookState.rotation % 4);
      frame.classList.toggle('round', (lookState.profile || '') === 'watch-round' || (own && persona.kind === 'watch-round'));
      // The persona's rounded corners and camera hole, drawn over the picture in proportion.
      frame.style.borderRadius = own && persona.corner ? (persona.corner / persona.width * 100) + '% / ' + (persona.corner / persona.height * 100) + '%' : '';
      const c = own && persona.cutout;
      hole.classList.toggle('hidden', !c);
      if (c) Object.assign(hole.style, { left: ((c.x - c.r) / persona.width * 100) + '%', top: ((c.y - c.r) / persona.height * 100) + '%',
        width: (2 * c.r / persona.width * 100) + '%', height: (2 * c.r / persona.height * 100) + '%' });
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
        persona = vd && vd.persona ? (dev.personas || []).find((x) => x.id === vd.persona) || null : null;
        fillProfiles(persona ? 'persona:' + persona.id : '');
        title.textContent = device.name || device.serial;
        look.classList.toggle('hidden', !vd);
        behave.classList.toggle('hidden', !vd);
        bsel.innerHTML = '';
        const order = Object.keys(dev.behaviors || {}).sort((a, b) => (persona && a.startsWith(persona.behavior) ? -1 : 0) - (persona && b.startsWith(persona.behavior) ? -1 : 0));
        for (const k of order) bsel.append(el('option', { value: k, text: dev.behaviors[k].label }));
        bsel.append(el('option', { value: 'reset', text: 'Reset the app to normal' }));
        bsel.onchange();
        if (!bpkg.value) bpkg.value = A.appId || '';
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
