/* Forge IDE: language-server client for the Monaco editor (code intelligence like in Cursor).
 * Suggestions, hover, go to definition, peek and find references, rename, formatting, signature help,
 * outline, quick fixes and live diagnostics, for every language with an installed server. */
(function () {
  'use strict';
  const F = window.Forge;
  const L = window.ForgeLSP = {};
  // Monaco language id -> [server key, LSP languageId]
  const LANGS = {
    python: ['python', 'python'], javascript: ['javascript', 'javascript'], typescript: ['typescript', 'typescript'],
    shell: ['shell', 'shellscript'], json: ['json', 'json'], css: ['css', 'css'], scss: ['scss', 'scss'], less: ['less', 'less'],
    html: ['html', 'html'], c: ['c', 'c'], cpp: ['cpp', 'cpp'], go: ['go', 'go'], rust: ['rust', 'rust'], java: ['java', 'java'],
    kotlin: ['kotlin', 'kotlin'], xml: ['xml', 'xml'],
  };
  const clients = new Map();       // server key -> Client
  const registered = new Set();    // monaco language ids with providers
  const unavailable = new Set();   // server keys that failed to start this session
  const retryAfter = new Map();    // server key -> time after which a memory-refused server may be tried again
  const told = new Set();
  const attached = new Map();      // model uri -> { model, client, version, timer, disposer }
  L.status = () => [...clients.values()].map((c) => ({ lang: c.key, ready: c.isReady, command: c.command, mode: c.mode || '' }));

  // ------------------------------------------------------------------ helpers
  const M = () => F.monaco;
  const toRange = (r) => new (M().Range)(r.start.line + 1, r.start.character + 1, r.end.line + 1, r.end.character + 1);
  const toPos = (p) => ({ line: p.lineNumber - 1, character: p.column - 1 });
  const fromUri = (uri) => decodeURIComponent(String(uri).replace(/^file:\/\//, ''));
  const folderUri = () => M().Uri.file(F.folder).toString();
  function md(contents) {
    if (!contents) return [];
    const one = (c) => typeof c === 'string' ? { value: c } : c.kind ? { value: c.value } : { value: '```' + (c.language || '') + '\n' + c.value + '\n```' };
    return Array.isArray(contents) ? contents.map(one) : [one(contents)];
  }
  const KIND = () => {
    const K = M().languages.CompletionItemKind;
    return [null, K.Text, K.Method, K.Function, K.Constructor, K.Field, K.Variable, K.Class, K.Interface, K.Module, K.Property, K.Unit, K.Value,
      K.Enum, K.Keyword, K.Snippet, K.Color, K.File, K.Reference, K.Folder, K.EnumMember, K.Constant, K.Struct, K.Event, K.Operator, K.TypeParameter];
  };
  const SYMKIND = () => {
    const S = M().languages.SymbolKind;
    return [null, S.File, S.Module, S.Namespace, S.Package, S.Class, S.Method, S.Property, S.Field, S.Constructor, S.Enum, S.Interface,
      S.Function, S.Variable, S.Constant, S.String, S.Number, S.Boolean, S.Array, S.Object, S.Key, S.Null, S.EnumMember, S.Struct, S.Event, S.Operator, S.TypeParameter];
  };
  const SEV = () => { const S = M().MarkerSeverity; return [null, S.Error, S.Warning, S.Info, S.Hint]; };

  /** A model for any file URI, so peek, references and rename can show and edit files that are not open. */
  async function ensureModel(uri) {
    const u = M().Uri.parse(uri);
    let model = M().editor.getModel(u);
    if (model) return model;
    try {
      const path = fromUri(uri);
      const r = await F.get('/api/file', { path });
      model = M().editor.getModel(u) || M().editor.createModel(r.content, F.langFor(path), u);
      return model;
    } catch (e) { return null; }
  }
  async function toLocations(res) {
    if (!res) return [];
    const list = Array.isArray(res) ? res : [res];
    const out = [];
    for (const l of list) {
      const uri = l.targetUri || l.uri;
      const range = l.targetSelectionRange || l.targetRange || l.range;
      if (!uri || !range) continue;
      await ensureModel(uri);
      out.push({ uri: M().Uri.parse(uri), range: toRange(range) });
    }
    return out;
  }
  async function workspaceEdit(we) {
    const edits = [];
    const add = async (uri, textEdits) => {
      const model = await ensureModel(uri);
      if (!model) return;
      for (const t of textEdits) edits.push({ resource: model.uri, textEdit: { range: toRange(t.range), text: t.newText }, versionId: undefined });
    };
    if (we && we.changes) for (const [uri, tes] of Object.entries(we.changes)) await add(uri, tes);
    if (we && we.documentChanges) for (const dc of we.documentChanges) if (dc.textDocument && dc.edits) await add(dc.textDocument.uri, dc.edits);
    return { edits };
  }
  /** Apply a server-initiated edit and open the changed files as tabs so they can be saved. */
  async function applyEdit(we) {
    const { edits } = await workspaceEdit(we);
    const byModel = new Map();
    for (const e of edits) { const m = M().editor.getModel(e.resource); if (m) { if (!byModel.has(m)) byModel.set(m, []); byModel.get(m).push({ range: e.textEdit.range, text: e.textEdit.text }); } }
    for (const [m, list] of byModel) { m.pushEditOperations([], list, () => null); await F.openFile(F.relPath(fromUri(m.uri.toString())), { background: true }); }
    return true;
  }

  // ------------------------------------------------------------------ one client per language server
  class Client {
    constructor(key) {
      this.key = key; this.seq = 0; this.pending = new Map(); this.caps = {}; this.isReady = false; this.command = '';
      this.ready = new Promise((res, rej) => { this._res = res; this._rej = rej; });
      this.ready.catch(() => {});
      const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
      this.ws = new WebSocket(proto + location.host + '/ws/lsp?lang=' + encodeURIComponent(key) + '&folder=' + encodeURIComponent(F.folder));
      this.ws.onmessage = (m) => { let msg; try { msg = JSON.parse(m.data); } catch (e) { return; } this.onMessage(msg); };
      this.ws.onclose = () => {
        this.dead = true; this.isReady = false;
        for (const p of this.pending.values()) p.reject(new Error('language server closed'));
        this.pending.clear(); clients.delete(this.key);
        this._rej(new Error('closed'));
      };
    }
    send(o) { if (this.ws.readyState === 1) this.ws.send(JSON.stringify(Object.assign({ jsonrpc: '2.0' }, o))); }
    notify(method, params) { this.send({ method, params }); }
    request(method, params, ms) {
      const id = ++this.seq;
      return new Promise((resolve, reject) => {
        const t = setTimeout(() => { this.pending.delete(id); reject(new Error(method + ' timed out')); }, ms || 15000);
        this.pending.set(id, { resolve: (v) => { clearTimeout(t); resolve(v); }, reject: (e) => { clearTimeout(t); reject(e); } });
        this.send({ id, method, params });
      });
    }
    async onMessage(msg) {
      if (msg.forge === 'ready') { this.command = msg.command; this.mode = msg.mode || ''; if (msg.mode) log(this.key + ' language server: ' + msg.mode); this.initialize(); return; }
      if (msg.forge === 'error') {
        log('language server: ' + msg.message);
        if (/memory/i.test(msg.message)) {
          // Refused to protect memory: say so once, and try again when a file of this language is opened later.
          retryAfter.set(this.key, Date.now() + 60000);
          if (!told.has(this.key)) { told.add(this.key); F.toast(msg.message + ' Code intelligence for this language starts when you reopen the file with more memory free.', 'error', 9000); }
        } else unavailable.add(this.key);
        this._rej(new Error(msg.message)); return;
      }
      if (msg.forge) return;
      if (msg.id !== undefined && !msg.method) {
        const p = this.pending.get(msg.id);
        if (p) { this.pending.delete(msg.id); msg.error ? p.reject(new Error(msg.error.message)) : p.resolve(msg.result); }
        return;
      }
      if (msg.method && msg.id !== undefined) {   // a request from the server
        let result = null;
        try {
          if (msg.method === 'workspace/configuration') result = (msg.params.items || []).map(() => null);
          else if (msg.method === 'workspace/diagnostic/refresh') { setTimeout(() => { for (const st of attached.values()) if (st.client === this) pull(st); }, 300); }
          else if (msg.method === 'workspace/applyEdit') result = { applied: await applyEdit(msg.params.edit) };
          else if (msg.method === 'workspace/workspaceFolders') result = [{ uri: folderUri(), name: F.folderName(F.folder) }];
        } catch (e) { result = null; }
        this.send({ id: msg.id, result });
        return;
      }
      if (msg.method === 'textDocument/publishDiagnostics') return diagnostics(this, msg.params);
      if (msg.method === 'window/logMessage' && msg.params && msg.params.type === 1) log(this.key + ': ' + msg.params.message);
    }
    async initialize() {
      try {
        const r = await this.request('initialize', {
          processId: null, rootUri: folderUri(), rootPath: F.folder,
          workspaceFolders: [{ uri: folderUri(), name: F.folderName(F.folder) }],
          clientInfo: { name: 'Forge IDE' },
          capabilities: {
            textDocument: {
              synchronization: { didSave: true, dynamicRegistration: false },
              completion: { completionItem: { snippetSupport: true, documentationFormat: ['markdown', 'plaintext'], insertReplaceSupport: false }, contextSupport: true },
              hover: { contentFormat: ['markdown', 'plaintext'] },
              signatureHelp: { signatureInformation: { documentationFormat: ['markdown', 'plaintext'], parameterInformation: { labelOffsetSupport: true } } },
              definition: { linkSupport: true }, declaration: { linkSupport: true }, typeDefinition: { linkSupport: true }, implementation: { linkSupport: true },
              references: {}, documentSymbol: { hierarchicalDocumentSymbolSupport: true }, formatting: {}, rangeFormatting: {},
              rename: { prepareSupport: false }, codeAction: { codeActionLiteralSupport: { codeActionKind: { valueSet: ['quickfix', 'refactor', 'source'] } } },
              publishDiagnostics: { relatedInformation: false },
              diagnostic: { dynamicRegistration: false, relatedDocumentSupport: false },
            },
            workspace: { applyEdit: true, workspaceFolders: true, configuration: true },
          },
        }, 60000);
        this.caps = (r && r.capabilities) || {};
        this.notify('initialized', {});
        this.isReady = true;
        this._res();
      } catch (e) { this._rej(e); }
    }
  }
  function log(text) { const el = document.querySelector('#agent-log'); if (el) { const d = document.createElement('div'); d.textContent = new Date().toLocaleTimeString() + ' ' + text; el.append(d); } }

  function clientFor(monacoLang) {
    const entry = LANGS[monacoLang];
    if (!entry || unavailable.has(entry[0]) || F.settings.lsp === false) return null;
    let c = clients.get(entry[0]);
    if ((!c || c.dead) && (retryAfter.get(entry[0]) || 0) > Date.now()) return null;
    if (!c || c.dead) { c = new Client(entry[0]); clients.set(entry[0], c); }
    registerProviders(monacoLang);
    return c;
  }

  // ------------------------------------------------------------------ documents
  L.attach = async function (model) {
    const lang = model.getLanguageId();
    const c = clientFor(lang);
    if (!c) return;
    try { await c.ready; } catch (e) { return; }
    const key = model.uri.toString();
    if (attached.has(key) || model.isDisposed()) return;
    const st = { model, client: c, version: 1, timer: null };
    attached.set(key, st);
    c.notify('textDocument/didOpen', { textDocument: { uri: key, languageId: LANGS[lang][1], version: 1, text: model.getValue() } });
    st.disposer = model.onDidChangeContent(() => { clearTimeout(st.timer); st.timer = setTimeout(() => flush(st), 250); });
    pullSoon(st, 400);
  };
  function flush(st) {
    if (!st.timer && st.flushed) return;
    clearTimeout(st.timer); st.timer = null;
    if (st.model.isDisposed()) return;
    st.version++;
    st.client.notify('textDocument/didChange', { textDocument: { uri: st.model.uri.toString(), version: st.version }, contentChanges: [{ text: st.model.getValue() }] });
    pullSoon(st, 700);
  }
  /** Servers like the Kotlin server send errors only when asked (pull diagnostics, LSP 3.17). */
  function pullSoon(st, ms) {
    if (!st.client.caps.diagnosticProvider) return;
    clearTimeout(st.pullTimer);
    st.pullTimer = setTimeout(() => pull(st), ms);
  }
  async function pull(st) {
    if (!st.client.caps.diagnosticProvider || st.model.isDisposed() || st.pulling) { if (st.pulling) st.pullAgain = true; return; }
    st.pulling = true;
    const uri = st.model.uri.toString();
    try {
      const r = await st.client.request('textDocument/diagnostic', { textDocument: { uri }, previousResultId: st.resultId || undefined }, 120000);
      if (r && r.resultId) st.resultId = r.resultId;
      if (r && r.kind !== 'unchanged' && Array.isArray(r.items)) diagnostics(st.client, { uri, diagnostics: r.items });
    } catch (e) { /* the server may still be indexing; the next edit asks again */ }
    st.pulling = false;
    if (st.pullAgain) { st.pullAgain = false; pullSoon(st, 300); }
  }
  function sync(model) { const st = attached.get(model.uri.toString()); if (st && st.timer) flush(st); return st; }
  L.saved = (model) => { const st = sync(model); if (st) { st.client.notify('textDocument/didSave', { textDocument: { uri: model.uri.toString() } }); pullSoon(st, 300); } };
  L.detach = (model) => {
    const key = model.uri.toString();
    const st = attached.get(key);
    if (!st) return;
    clearTimeout(st.timer); clearTimeout(st.pullTimer);
    if (st.disposer) st.disposer.dispose();
    st.client.notify('textDocument/didClose', { textDocument: { uri: key } });
    attached.delete(key);
    M().editor.setModelMarkers(model, 'lsp', []);
  };

  const lastDiagnostics = new Map();   // path -> diagnostics, for Problems and @linter errors
  L.diagnosticsFor = (path) => lastDiagnostics.get(path) || [];
  L.allDiagnostics = () => lastDiagnostics;
  function diagnostics(c, params) {
    const model = M().editor.getModel(M().Uri.parse(params.uri));
    const path = F.relPath(fromUri(params.uri));
    lastDiagnostics.set(path, params.diagnostics || []);
    if (window.ForgeExtras && window.ForgeExtras.reportLsp) window.ForgeExtras.reportLsp(path, params.diagnostics || []);
    if (!model) return;
    const sev = SEV();
    M().editor.setModelMarkers(model, 'lsp', (params.diagnostics || []).map((d) => {
      const r = toRange(d.range);
      return { startLineNumber: r.startLineNumber, startColumn: r.startColumn, endLineNumber: r.endLineNumber, endColumn: Math.max(r.endColumn, r.startColumn + 1),
        message: d.message, severity: sev[d.severity || 1] || sev[1], source: d.source || c.key, code: d.code !== undefined ? String(d.code) : undefined };
    }));
  }

  // ------------------------------------------------------------------ providers (registered once per language)
  function registerProviders(lang) {
    if (registered.has(lang)) return;
    registered.add(lang);
    const ml = M().languages;
    const get = (model) => { const st = sync(model); return st ? st.client : null; };
    const has = (c, cap) => c && c.isReady && c.caps[cap];
    disableBuiltin(lang);
    ml.registerCompletionItemProvider(lang, {
      triggerCharacters: ['.', ':', '(', '"', "'", '/', '@', '<', '$', '-', ' '],
      async provideCompletionItems(model, position, ctx) {
        const c = get(model);
        if (!has(c, 'completionProvider')) return { suggestions: [] };
        const word = model.getWordUntilPosition(position);
        const def = new (M().Range)(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
        let res;
        try { res = await c.request('textDocument/completion', { textDocument: { uri: model.uri.toString() }, position: toPos(position), context: { triggerKind: ctx.triggerKind === 1 ? 2 : 1, triggerCharacter: ctx.triggerCharacter } }); } catch (e) { return { suggestions: [] }; }
        const items = Array.isArray(res) ? res : (res && res.items) || [];
        const kinds = KIND();
        return { incomplete: !!(res && res.isIncomplete), suggestions: items.slice(0, 400).map((it) => {
          const te = it.textEdit;
          const range = te ? toRange(te.range || te.replace || te.insert) : def;
          return { label: it.label, kind: kinds[it.kind] !== undefined && kinds[it.kind] !== null ? kinds[it.kind] : M().languages.CompletionItemKind.Text,
            detail: it.detail, documentation: it.documentation ? (typeof it.documentation === 'string' ? it.documentation : { value: it.documentation.value }) : undefined,
            insertText: te ? te.newText : (it.insertText || it.label), range, sortText: it.sortText, filterText: it.filterText,
            insertTextRules: it.insertTextFormat === 2 ? M().languages.CompletionItemInsertTextRule.InsertAsSnippet : undefined,
            additionalTextEdits: (it.additionalTextEdits || []).map((a) => ({ range: toRange(a.range), text: a.newText })), _lsp: it };
        }) };
      },
      async resolveCompletionItem(item) {
        const st = [...attached.values()][0];
        const c = st && st.client;
        if (!item._lsp || !c || !(c.caps.completionProvider || {}).resolveProvider || item.documentation) return item;
        try { const r = await c.request('completionItem/resolve', item._lsp, 4000); if (r && r.documentation) item.documentation = typeof r.documentation === 'string' ? r.documentation : { value: r.documentation.value }; if (r && r.detail) item.detail = r.detail; } catch (e) { /* keep */ }
        return item;
      },
    });
    ml.registerHoverProvider(lang, { async provideHover(model, position) {
      const c = get(model); if (!has(c, 'hoverProvider')) return null;
      try { const r = await c.request('textDocument/hover', { textDocument: { uri: model.uri.toString() }, position: toPos(position) }); if (!r || !r.contents) return null; return { contents: md(r.contents), range: r.range ? toRange(r.range) : undefined }; } catch (e) { return null; }
    } });
    const loc = (method, cap) => async (model, position) => {
      const c = get(model); if (!has(c, cap)) return [];
      try { return await toLocations(await c.request(method, { textDocument: { uri: model.uri.toString() }, position: toPos(position) })); } catch (e) { return []; }
    };
    ml.registerDefinitionProvider(lang, { provideDefinition: loc('textDocument/definition', 'definitionProvider') });
    ml.registerDeclarationProvider(lang, { provideDeclaration: loc('textDocument/declaration', 'declarationProvider') });
    ml.registerTypeDefinitionProvider(lang, { provideTypeDefinition: loc('textDocument/typeDefinition', 'typeDefinitionProvider') });
    ml.registerImplementationProvider(lang, { provideImplementation: loc('textDocument/implementation', 'implementationProvider') });
    ml.registerReferenceProvider(lang, { async provideReferences(model, position) {
      const c = get(model); if (!has(c, 'referencesProvider')) return [];
      try { return await toLocations(await c.request('textDocument/references', { textDocument: { uri: model.uri.toString() }, position: toPos(position), context: { includeDeclaration: true } })); } catch (e) { return []; }
    } });
    ml.registerSignatureHelpProvider(lang, { signatureHelpTriggerCharacters: ['(', ','], signatureHelpRetriggerCharacters: [','],
      async provideSignatureHelp(model, position) {
        const c = get(model); if (!has(c, 'signatureHelpProvider')) return null;
        try {
          const r = await c.request('textDocument/signatureHelp', { textDocument: { uri: model.uri.toString() }, position: toPos(position) });
          if (!r || !r.signatures || !r.signatures.length) return null;
          return { value: { activeSignature: r.activeSignature || 0, activeParameter: r.activeParameter || 0, signatures: r.signatures.map((s) => ({
            label: s.label, documentation: s.documentation ? (typeof s.documentation === 'string' ? s.documentation : { value: s.documentation.value }) : undefined,
            parameters: (s.parameters || []).map((p) => ({ label: p.label, documentation: p.documentation ? (typeof p.documentation === 'string' ? p.documentation : { value: p.documentation.value }) : undefined })) })) }, dispose() {} };
        } catch (e) { return null; }
      } });
    ml.registerDocumentSymbolProvider(lang, { async provideDocumentSymbols(model) {
      const c = get(model); if (!has(c, 'documentSymbolProvider')) return [];
      try {
        const r = await c.request('textDocument/documentSymbol', { textDocument: { uri: model.uri.toString() } });
        const sk = SYMKIND();
        const conv = (s) => s.location ? { name: s.name, detail: '', kind: sk[s.kind] || 0, range: toRange(s.location.range), selectionRange: toRange(s.location.range), tags: [] }
          : { name: s.name, detail: s.detail || '', kind: sk[s.kind] || 0, range: toRange(s.range), selectionRange: toRange(s.selectionRange || s.range), tags: [], children: (s.children || []).map(conv) };
        return (r || []).map(conv);
      } catch (e) { return []; }
    } });
    ml.registerRenameProvider(lang, { async provideRenameEdits(model, position, newName) {
      const c = get(model); if (!has(c, 'renameProvider')) return { edits: [] };
      const r = await c.request('textDocument/rename', { textDocument: { uri: model.uri.toString() }, position: toPos(position), newName });
      const we = await workspaceEdit(r);
      setTimeout(async () => { for (const e of we.edits) { const p = F.relPath(fromUri(e.resource.toString())); if (!F.files.has(p)) await F.openFile(p, { background: true }); } }, 300);
      return we;
    } });
    ml.registerDocumentFormattingEditProvider(lang, { async provideDocumentFormattingEdits(model, options) {
      const c = get(model); if (!has(c, 'documentFormattingProvider')) return [];
      try { const r = await c.request('textDocument/formatting', { textDocument: { uri: model.uri.toString() }, options: { tabSize: options.tabSize, insertSpaces: options.insertSpaces } }); return (r || []).map((t) => ({ range: toRange(t.range), text: t.newText })); } catch (e) { return []; }
    } });
    ml.registerCodeActionProvider(lang, { async provideCodeActions(model, range, context) {
      const c = get(model); if (!has(c, 'codeActionProvider')) return { actions: [], dispose() {} };
      const diags = (context.markers || []).filter((m) => m.owner === 'lsp').map((m) => ({ range: { start: { line: m.startLineNumber - 1, character: m.startColumn - 1 }, end: { line: m.endLineNumber - 1, character: m.endColumn - 1 } }, message: m.message, severity: 1, code: m.code, source: m.source }));
      try {
        const r = await c.request('textDocument/codeAction', { textDocument: { uri: model.uri.toString() }, range: { start: { line: range.startLineNumber - 1, character: range.startColumn - 1 }, end: { line: range.endLineNumber - 1, character: range.endColumn - 1 } }, context: { diagnostics: diags } }, 8000);
        const actions = [];
        for (const a of r || []) if (a.edit) actions.push({ title: a.title, kind: a.kind || 'quickfix', edit: await workspaceEdit(a.edit), isPreferred: a.isPreferred });
        return { actions, dispose() {} };
      } catch (e) { return { actions: [], dispose() {} }; }
    } });
  }

  /** Monaco ships its own single-file services for JS, TS, JSON, CSS and HTML; the language server replaces them. */
  function disableBuiltin(lang) {
    try {
      const ml = M().languages;
      const off = { completionItems: false, hovers: false, documentSymbols: false, definitions: false, references: false, documentHighlights: false, rename: false, diagnostics: false, documentRangeFormattingEdits: false, signatureHelp: false, onTypeFormattingEdits: false, codeActions: false, inlayHints: false };
      if ((lang === 'javascript' || lang === 'typescript') && ml.typescript) {
        const d = lang === 'javascript' ? ml.typescript.javascriptDefaults : ml.typescript.typescriptDefaults;
        d.setModeConfiguration(off); d.setDiagnosticsOptions({ noSemanticValidation: true, noSyntaxValidation: true });
      }
      if (lang === 'json' && ml.json) ml.json.jsonDefaults.setModeConfiguration({ ...off, tokens: true, colors: true, foldingRanges: true, selectionRanges: true });
      if (['css', 'scss', 'less'].includes(lang) && ml.css) ml.css[lang + 'Defaults'].setModeConfiguration({ ...off, tokens: true, colors: true, foldingRanges: true, selectionRanges: true });
      if (lang === 'html' && ml.html) ml.html.htmlDefaults.setModeConfiguration({ ...off, tokens: true, colors: true, foldingRanges: true, selectionRanges: true, links: true });
    } catch (e) { /* the built-in service stays on */ }
  }

  /** Opening another file from go to definition, peek or a link goes through Forge's tabs. */
  L.installOpener = function () {
    try {
      M().editor.registerEditorOpener({ openCodeEditor(source, resource, selectionOrPosition) {
        const path = F.relPath(fromUri(resource.toString()));
        const line = selectionOrPosition ? (selectionOrPosition.startLineNumber || selectionOrPosition.lineNumber) : undefined;
        const column = selectionOrPosition ? (selectionOrPosition.startColumn || selectionOrPosition.column) : undefined;
        F.openFile(path, { line, column });
        return true;
      } });
    } catch (e) { /* older Monaco */ }
  };
})();
