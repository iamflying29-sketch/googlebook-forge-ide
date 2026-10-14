/* Forge IDE: voice input for the chat. In the Forge app it uses Android's speech recognizer (live text while you
 * speak). In a browser it records the microphone, and whisper.cpp in the Linux VM turns the recording into text. */
(function () {
  'use strict';
  const F = window.Forge;
  const { toast, post, get } = F;
  const V = window.ForgeVoice = {};
  const app = () => !!(window.ForgeApp && typeof window.ForgeApp.voiceStart === 'function');
  let target = null, button = null, base = '', finals = '', active = false;

  function setButton(state) {
    if (!button) return;
    button.classList.toggle('recording', state === 'listening');
    button.classList.toggle('busy', state === 'processing');
    button.title = state === 'listening' ? 'Listening. Click to stop.' : state === 'processing' ? 'Turning your speech into text…' : 'Voice input: speak your message';
  }
  function show(partial) {
    if (!target) return;
    const sep = base && !/\s$/.test(base) ? ' ' : '';
    target.value = base + sep + (finals + (partial ? (finals ? ' ' : '') + partial : '')).trim();
    target.dispatchEvent(new Event('input'));
  }

  // ------------------------------------------------------------------ Forge app: Android's recognizer
  V._native = (raw) => {
    let ev;
    try { ev = JSON.parse(raw); } catch (e) { return; }
    if (ev.type === 'partial') show(ev.text);
    else if (ev.type === 'final') { finals = (finals + ' ' + ev.text).trim(); show(''); }
    else if (ev.type === 'error') { toast(ev.text, 'error', 6000); }
    else if (ev.type === 'state') {
      if (ev.text === 'stopped') { active = false; setButton('idle'); if (target) target.focus(); }
      else if (ev.text === 'listening' || ev.text === 'ready' || ev.text === 'speaking') setButton('listening');
    }
  };

  // ------------------------------------------------------------------ browser: record, then whisper.cpp
  let rec = null;
  async function startRecording() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('This browser cannot record audio.');
    const st = await get('/api/voice/status').catch(() => ({ available: false }));
    if (!st.available) throw new Error('Voice input in the browser needs whisper.cpp in the Linux VM, and it is not installed.');
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const src = ctx.createMediaStreamSource(stream);
    const node = ctx.createScriptProcessor(4096, 1, 1);
    const chunks = [];
    let samples = 0;
    node.onaudioprocess = (e) => {
      const d = e.inputBuffer.getChannelData(0);
      chunks.push(new Float32Array(d));
      samples += d.length;
      if (samples > ctx.sampleRate * 170) V.stop();   // whisper takes at most three minutes
    };
    src.connect(node); node.connect(ctx.destination);
    rec = { stream, ctx, node, src, chunks };
  }
  function wav16k(chunks, rate) {
    const total = chunks.reduce((n, c) => n + c.length, 0);
    const ratio = rate / 16000;
    const outLen = Math.floor(total / ratio);
    const all = new Float32Array(total);
    let o = 0;
    for (const c of chunks) { all.set(c, o); o += c.length; }
    const pcm = new Int16Array(outLen);
    for (let i = 0; i < outLen; i++) {
      // Average the source samples that fall into this output sample (a simple low-pass before downsampling).
      const a = Math.floor(i * ratio), b = Math.min(total, Math.floor((i + 1) * ratio));
      let sum = 0;
      for (let k = a; k < b; k++) sum += all[k];
      const v = b > a ? sum / (b - a) : all[a] || 0;
      pcm[i] = Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
    }
    const buf = new ArrayBuffer(44 + pcm.length * 2);
    const dv = new DataView(buf);
    const str = (off, s) => { for (let i = 0; i < s.length; i++) dv.setUint8(off + i, s.charCodeAt(i)); };
    str(0, 'RIFF'); dv.setUint32(4, 36 + pcm.length * 2, true); str(8, 'WAVE'); str(12, 'fmt ');
    dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 1, true); dv.setUint32(24, 16000, true);
    dv.setUint32(28, 32000, true); dv.setUint16(32, 2, true); dv.setUint16(34, 16, true); str(36, 'data'); dv.setUint32(40, pcm.length * 2, true);
    new Int16Array(buf, 44).set(pcm);
    return new Blob([buf], { type: 'audio/wav' });
  }
  async function finishRecording() {
    const r = rec;
    rec = null;
    if (!r) return;
    try { r.node.disconnect(); r.src.disconnect(); } catch (e) { /* ignore */ }
    r.stream.getTracks().forEach((t) => t.stop());
    const rate = r.ctx.sampleRate;
    try { await r.ctx.close(); } catch (e) { /* ignore */ }
    const blob = wav16k(r.chunks, rate);
    if (blob.size < 16000) { setButton('idle'); return; }   // under half a second: nothing to recognize
    setButton('processing');
    try {
      const data = await new Promise((res, rej) => { const fr = new FileReader(); fr.onload = () => res(fr.result); fr.onerror = () => rej(fr.error); fr.readAsDataURL(blob); });
      const out = await post('/api/voice/transcribe', { data });
      finals = (finals + ' ' + (out.text || '')).trim();
      show('');
      if (!out.text) toast('No speech was recognized.', 'error');
    } catch (e) { toast(e.message, 'error', 6000); }
    setButton('idle');
    if (target) target.focus();
  }

  // ------------------------------------------------------------------ public
  V.toggle = async (input, btn) => {
    if (active) return V.stop();
    target = input; button = btn; base = input.value; finals = ''; active = true;
    if (app() && window.ForgeApp.voiceAvailable()) { setButton('listening'); window.ForgeApp.voiceStart(navigator.language || 'en-US'); return; }
    try { await startRecording(); setButton('listening'); toast('Listening. Click the microphone again when you are done.', 'ok', 2500); }
    catch (e) { active = false; setButton('idle'); toast(e.message || String(e), 'error', 6000); }
  };
  V.stop = () => {
    if (!active) return;
    active = false;
    if (app() && window.ForgeApp.voiceAvailable()) { window.ForgeApp.voiceStop(); return; }
    finishRecording();
  };
  V.active = () => active;
})();
