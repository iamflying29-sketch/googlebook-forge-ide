/* Forge IDE: the image viewer and the live Markdown preview (side by side, like Cursor's Ctrl+Shift+V). */
(function () {
  'use strict';
  const F = window.Forge;
  const { $, el } = F;
  const P = window.ForgePreview = {};
  const mdOn = new Set();      // Markdown files with the preview open
  let imgView = null, mdView = null, mdModelSub = null, mdTimer = null;

  function ensure() {
    const area = $('#editor').parentElement;
    if (!imgView) { imgView = el('div', { class: 'img-view hidden' }); area.append(imgView); }
    if (!mdView) { mdView = el('div', { class: 'md-preview hidden' }); area.append(mdView); }
  }
  function renderMd(f) {
    if (!mdView || !f) return;
    const top = mdView.scrollTop;
    mdView.innerHTML = '';
    const body = el('div', { class: 'md md-body' });
    body.innerHTML = DOMPurify.sanitize(marked.parse(f.model.getValue()));
    // Relative image links point into the project folder.
    for (const img of body.querySelectorAll('img[src]')) {
      const src = img.getAttribute('src');
      if (src && !/^(https?:|data:|\/api\/)/.test(src)) {
        const dir = f.path.includes('/') ? f.path.slice(0, f.path.lastIndexOf('/') + 1) : '';
        img.src = '/api/raw?folder=' + encodeURIComponent(F.folder) + '&path=' + encodeURIComponent(dir + src);
      }
    }
    for (const a of body.querySelectorAll('a[href]')) a.target = '_blank';
    mdView.append(el('div', { class: 'md-head' }, el('span', { text: 'Preview of ' + f.path.split('/').pop() }), el('button', { class: 'mini', text: '✕', title: 'Close preview (Ctrl+Shift+V)', onclick: () => P.toggleMarkdown() })), body);
    mdView.scrollTop = top;
  }
  function sync() {
    ensure();
    const f = F.activePath && F.files.get(F.activePath);
    const area = $('#editor').parentElement;
    // Image viewer
    if (f && f.image) {
      imgView.innerHTML = '';
      const url = '/api/raw?folder=' + encodeURIComponent(F.folder) + '&path=' + encodeURIComponent(f.path);
      const img = el('img', { src: url, alt: f.path });
      const info = el('div', { class: 'img-info', text: f.path });
      img.onload = () => { info.textContent = f.path + '  ·  ' + img.naturalWidth + ' × ' + img.naturalHeight + ' pixels'; };
      img.onerror = () => { info.textContent = 'Cannot show ' + f.path; };
      img.onclick = () => img.classList.toggle('actual');
      imgView.append(img, info);
      imgView.classList.remove('hidden');
    } else imgView.classList.add('hidden');
    // Markdown preview
    if (mdModelSub) { mdModelSub.dispose(); mdModelSub = null; }
    const showMd = f && !f.image && /\.(md|markdown|mdx)$/i.test(f.path) && mdOn.has(f.path);
    area.classList.toggle('with-preview', !!showMd);
    if (showMd) {
      mdView.classList.remove('hidden');
      renderMd(f);
      mdModelSub = f.model.onDidChangeContent(() => { clearTimeout(mdTimer); mdTimer = setTimeout(() => renderMd(f), 200); });
    } else mdView.classList.add('hidden');
  }
  P.toggleMarkdown = () => {
    const p = F.activePath;
    if (!p || !/\.(md|markdown|mdx)$/i.test(p)) { F.toast('Open a Markdown file first.', 'error'); return; }
    if (mdOn.has(p)) mdOn.delete(p); else mdOn.add(p);
    sync();
  };
  window.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === 'v' && F.activePath && /\.(md|markdown|mdx)$/i.test(F.activePath)) { e.preventDefault(); e.stopPropagation(); P.toggleMarkdown(); } }, true);
  const wait = setInterval(() => { if (F.editor && F.hooks) { clearInterval(wait); F.hooks.onActiveFileChanged.push(() => setTimeout(sync, 0)); F.editor.onDidChangeModel(() => setTimeout(sync, 0)); sync(); } }, 300);
})();
