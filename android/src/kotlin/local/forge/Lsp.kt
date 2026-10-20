package local.forge

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The language server client of the native editor, the port of the old page's lsp.js. The Forge server starts one
 * language server per language and window behind /ws/lsp and passes JSON-RPC messages both ways, one WebSocket
 * text frame per message (the server adds the Content-Length framing for the server process itself). This object
 * keeps one client per language, started lazily when the first file of that language is opened, and the documents
 * each client knows about.
 *
 * Everything here runs on the main thread: the WebSocket client delivers its callbacks there, and the panels call
 * in from there. Every request has a timeout, so a silent server can never leave the editor waiting.
 */
object Lsp {
    private const val TAG = "ForgeLsp"

    /** A diagnostic with 0-based positions, as the protocol sends them. Severity: 1 error, 2 warning, 3 info, 4 hint. */
    class Diagnostic(val line: Int, val col: Int, val endLine: Int, val endCol: Int, val severity: Int, val message: String, val source: String, val code: String) {
        val isError: Boolean get() = severity <= 1
        fun describe(): String = message + (if (source.isNotEmpty()) " (" + source + (if (code.isNotEmpty()) " $code" else "") + ")" else "")
    }

    /** A place in a file, 0-based. The path is relative to the window's folder when the file lies inside it. */
    class Location(val path: String, val line: Int, val col: Int, val endLine: Int, val endCol: Int)

    /** One replacement of a 0-based range with new text. */
    class TextEdit(val line: Int, val col: Int, val endLine: Int, val endCol: Int, val newText: String)

    class CompletionItem(val label: String, val kind: Int, val detail: String, val documentation: String, val insertText: String,
                         val edit: TextEdit?, val additional: List<TextEdit>, val sortText: String, val filterText: String,
                         val snippet: Boolean, val raw: JSONObject)

    class Signature(val label: String, val documentation: String, val parameters: List<Pair<String, String>>)
    class SignatureHelp(val signatures: List<Signature>, val activeSignature: Int, val activeParameter: Int)
    class Hover(val markdown: String, val range: Location?)

    /** A symbol of a document (a class, a function, a field), with its children for the outline. Positions are 0-based. */
    class Symbol(val name: String, val detail: String, val kind: Int, val line: Int, val col: Int, val endLine: Int, val endCol: Int,
                 val children: List<Symbol>, val path: String = "")

    /** A quick fix or refactoring offered by the server: either edits to apply, a command to run, or both. */
    class CodeAction(val title: String, val kind: String, val preferred: Boolean, val edits: Map<String, List<TextEdit>>, val command: JSONObject?)

    /** The names of symbol kinds, by the protocol's numbers (index 0 is unused). */
    val symbolKindNames = arrayOf("", "file", "module", "namespace", "package", "class", "method", "property", "field", "constructor",
        "enum", "interface", "function", "variable", "constant", "string", "number", "boolean", "array", "object", "key", "null",
        "enum member", "struct", "event", "operator", "type parameter")

    /** The names of completion item kinds, by the protocol's numbers (index 0 is unused). */
    val kindNames = arrayOf("", "text", "method", "function", "constructor", "field", "variable", "class", "interface", "module", "property",
        "unit", "value", "enum", "keyword", "snippet", "color", "file", "reference", "folder", "enum member", "constant", "struct", "event",
        "operator", "type parameter")

    /** False when the "lsp" setting is off; then no server is ever started. */
    @Volatile var enabled = true

    /** The absolute path of the window's folder, resolved from GET /api/roots before the first server starts. */
    @Volatile var root: String = ""

    /** The last diagnostics of every file (path to list), for the Problems panel and the editor decorations. */
    val diagnostics = LinkedHashMap<String, List<Diagnostic>>()

    /** Called on the main thread with every diagnostics update of one file. */
    val listeners = ArrayList<(String, List<Diagnostic>) -> Unit>()

    /** Applies a workspace edit sent by a server (a rename, a code action): path to its edits. Set by the editor panel. */
    var applyEdit: ((Map<String, List<TextEdit>>) -> Boolean)? = null

    /** A short note for the user, for example that a server refused to start for lack of memory. */
    var notify: ((String) -> Unit)? = null

    private val clients = HashMap<String, LspClient>()
    private val unavailable = HashSet<String>()
    private val retryAfter = HashMap<String, Long>()

    // ---------------------------------------------------------------- languages
    /** File extension (or well known base name) to (server key, protocol language id), the table of lsp.js. */
    private val byExtension = mapOf(
        "py" to ("python" to "python"), "pyi" to ("python" to "python"),
        "js" to ("javascript" to "javascript"), "mjs" to ("javascript" to "javascript"), "cjs" to ("javascript" to "javascript"), "jsx" to ("javascript" to "javascriptreact"),
        "ts" to ("typescript" to "typescript"), "mts" to ("typescript" to "typescript"), "tsx" to ("typescript" to "typescriptreact"),
        "sh" to ("shell" to "shellscript"), "bash" to ("shell" to "shellscript"), "zsh" to ("shell" to "shellscript"),
        "json" to ("json" to "json"), "jsonc" to ("json" to "jsonc"),
        "css" to ("css" to "css"), "scss" to ("scss" to "scss"), "less" to ("less" to "less"),
        "html" to ("html" to "html"), "htm" to ("html" to "html"),
        "c" to ("c" to "c"), "h" to ("c" to "c"), "cc" to ("cpp" to "cpp"), "cpp" to ("cpp" to "cpp"), "cxx" to ("cpp" to "cpp"), "hpp" to ("cpp" to "cpp"), "hh" to ("cpp" to "cpp"),
        "go" to ("go" to "go"), "rs" to ("rust" to "rust"), "java" to ("java" to "java"),
        "kt" to ("kotlin" to "kotlin"), "kts" to ("kotlin" to "kotlin"), "xml" to ("xml" to "xml"))

    /** The server key and the protocol language id for a file, or null when no language server exists for it. */
    fun serverFor(path: String): Pair<String, String>? {
        val base = path.substringAfterLast('/').lowercase()
        val ext = if (base.contains('.')) base.substringAfterLast('.') else ""
        return byExtension[ext]
    }

    /** The client for a file's language, started when needed; null when there is none or it is not to be started now. */
    fun clientFor(path: String): LspClient? {
        if (!enabled) return null
        val (key, _) = serverFor(path) ?: return null
        if (key in unavailable) return null
        val existing = clients[key]
        if (existing != null && !existing.dead) return existing
        if ((retryAfter[key] ?: 0L) > System.currentTimeMillis()) return null
        val c = LspClient(key)
        clients[key] = c
        c.start()
        return c
    }

    /** A line for the status display per running server. */
    fun status(): List<String> = clients.values.filter { !it.dead }.map { it.key + ": " + (if (it.ready) "ready" else "starting") + (if (it.mode.isNotEmpty()) " (" + it.mode + ")" else "") }

    /** Stops the server of a file's language; the next open of such a file starts a fresh one. */
    fun restart(path: String) {
        val (key, _) = serverFor(path) ?: return
        unavailable.remove(key); retryAfter.remove(key)
        clients.remove(key)?.stop()
    }

    fun shutdownAll() { for (c in clients.values) c.stop(); clients.clear() }

    // ---------------------------------------------------------------- documents
    fun open(path: String, text: String) {
        val c = clientFor(path) ?: return
        val languageId = serverFor(path)?.second ?: return
        c.whenReady { c.didOpen(path, languageId, text) }
    }

    /** The whole text after a change; sent 250 ms after the last call, like the old page. */
    fun change(path: String, text: String) { clients[serverFor(path)?.first ?: return]?.let { if (!it.dead) it.changed(path, text) } }
    fun save(path: String) { clients[serverFor(path)?.first ?: return]?.let { if (!it.dead) it.didSave(path) } }
    fun close(path: String) {
        clients[serverFor(path)?.first ?: return]?.let { if (!it.dead) it.didClose(path) }
        publish(path, emptyList())
    }

    /** A file that was renamed: the old document closes, the new one opens. */
    fun moved(oldPath: String, newPath: String, text: String) { close(oldPath); open(newPath, text) }

    internal fun publish(path: String, list: List<Diagnostic>) {
        if (list.isEmpty()) { if (diagnostics.remove(path) == null) return } else diagnostics[path] = list
        for (l in listeners.toList()) try { l(path, list) } catch (e: Exception) { Log.w(TAG, "diagnostics listener", e) }
    }

    internal fun markUnavailable(key: String, memory: Boolean) {
        if (memory) retryAfter[key] = System.currentTimeMillis() + 60000 else unavailable.add(key)
    }

    // ---------------------------------------------------------------- paths and URIs
    private fun encodePath(p: String): String = p.split('/').joinToString("/") { seg ->
        if (seg.isEmpty()) seg else URLEncoder.encode(seg, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("%2A", "*").replace("%40", "@").replace("%2B", "+")
    }

    fun uriFor(path: String): String = "file://" + encodePath(if (path.startsWith("/")) path else root.trimEnd('/') + "/" + path)

    fun pathFor(uri: String): String {
        var p = uri
        if (p.startsWith("file://")) p = p.substring(7)
        p = try { URLDecoder.decode(p.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { p }
        val r = root.trimEnd('/')
        if (r.isNotEmpty() && p.startsWith("$r/")) return p.substring(r.length + 1)
        return p
    }

    // ---------------------------------------------------------------- JSON helpers shared with the panels
    fun range(line: Int, col: Int, endLine: Int, endCol: Int): JSONObject =
        JSONObject().put("start", JSONObject().put("line", line).put("character", col)).put("end", JSONObject().put("line", endLine).put("character", endCol))

    fun parseLocation(o: JSONObject?): Location? {
        o ?: return null
        val uri = if (o.has("targetUri")) o.optString("targetUri") else o.optString("uri")
        val r = o.optJSONObject("targetSelectionRange") ?: o.optJSONObject("targetRange") ?: o.optJSONObject("range")
        if (uri.isEmpty() || r == null) return null
        val s = r.optJSONObject("start") ?: return null
        val e = r.optJSONObject("end") ?: s
        return Location(pathFor(uri), s.optInt("line"), s.optInt("character"), e.optInt("line"), e.optInt("character"))
    }

    fun parseLocations(res: Any?): List<Location> {
        val out = ArrayList<Location>()
        when (res) {
            is JSONObject -> parseLocation(res)?.let { out.add(it) }
            is JSONArray -> for (i in 0 until res.length()) parseLocation(res.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun parseEdit(o: JSONObject?): TextEdit? {
        o ?: return null
        val r = o.optJSONObject("range") ?: o.optJSONObject("replace") ?: o.optJSONObject("insert") ?: return null
        val s = r.optJSONObject("start") ?: return null
        val e = r.optJSONObject("end") ?: s
        return TextEdit(s.optInt("line"), s.optInt("character"), e.optInt("line"), e.optInt("character"), o.optString("newText"))
    }

    /** A WorkspaceEdit as path to edits; file creations, renames and deletions inside it are not applied. */
    fun parseWorkspaceEdit(we: JSONObject?): Map<String, List<TextEdit>> {
        val out = LinkedHashMap<String, ArrayList<TextEdit>>()
        we ?: return out
        fun add(uri: String, arr: JSONArray?) {
            arr ?: return
            val list = out.getOrPut(pathFor(uri)) { ArrayList() }
            for (i in 0 until arr.length()) parseEdit(arr.optJSONObject(i))?.let { list.add(it) }
        }
        we.optJSONObject("changes")?.let { ch -> for (k in ch.keys()) add(k, ch.optJSONArray(k)) }
        we.optJSONArray("documentChanges")?.let { dc ->
            for (i in 0 until dc.length()) {
                val d = dc.optJSONObject(i) ?: continue
                val td = d.optJSONObject("textDocument") ?: continue
                add(td.optString("uri"), d.optJSONArray("edits"))
            }
        }
        return out
    }

    /** A string field, or an empty string when it is missing or JSON null (optString would give the text "null"). */
    fun str(o: JSONObject?, key: String): String = if (o == null || !o.has(key) || o.isNull(key)) "" else o.optString(key)

    /** Markdown out of a MarkedString, a MarkupContent, or a list of them. */
    fun markdownOf(contents: Any?): String {
        fun one(c: Any?): String = when (c) {
            is String -> c
            is JSONObject -> if (c.has("kind")) c.optString("value") else "```" + c.optString("language") + "\n" + c.optString("value") + "\n```"
            else -> ""
        }
        return when (contents) {
            is JSONArray -> (0 until contents.length()).joinToString("\n\n") { one(contents.opt(it)) }.trim()
            else -> one(contents).trim()
        }
    }

    fun docOf(d: Any?): String = when (d) { is String -> d; is JSONObject -> d.optString("value"); else -> "" }

    /** Snippet syntax to plain text: `${1:name}` becomes name, `$1` and `${2}` vanish, `\$` becomes `$`. */
    fun plainSnippet(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { out.append(s[i + 1]); i += 2; continue }
            if (c == '$' && i + 1 < s.length) {
                if (s[i + 1] == '{') {
                    var depth = 1; var j = i + 2
                    while (j < s.length && depth > 0) { if (s[j] == '{') depth++ else if (s[j] == '}') depth--; j++ }
                    val inner = s.substring(i + 2, (j - 1).coerceAtLeast(i + 2))
                    val colon = inner.indexOf(':')
                    val pipe = inner.indexOf('|')
                    when {
                        colon > 0 && inner.substring(0, colon).all { it.isDigit() || it.isLetter() || it == '_' } -> out.append(plainSnippet(inner.substring(colon + 1)))
                        pipe > 0 -> out.append(inner.substring(pipe + 1).trimEnd('|').substringBefore(','))
                        inner.all { it.isDigit() } -> { }
                        else -> out.append(inner)
                    }
                    i = j; continue
                }
                if (s[i + 1].isDigit()) { var j = i + 1; while (j < s.length && s[j].isDigit()) j++; i = j; continue }
            }
            out.append(c); i++
        }
        return out.toString()
    }
}

/**
 * One connection to one language server. The JSON-RPC traffic, the initialize handshake, the documents the server
 * was told about, pull diagnostics for servers that only answer when asked (the Kotlin server), and the requests of
 * the editor (completion, hover, signature help, definition, references, rename).
 */
class LspClient(val key: String) {
    private val TAG = "ForgeLsp"
    private class Pending(val cb: (Any?, String?) -> Unit, val timeout: Runnable)
    private class Doc(var version: Int) { var text = ""; var flush: Runnable? = null; var pull: Runnable? = null; var resultId = ""; var pulling = false; var pullAgain = false }

    private var ws: Api.WebSocketClient? = null
    private var seq = 0
    private val pending = HashMap<Int, Pending>()
    private val docs = HashMap<String, Doc>()
    private val waiting = ArrayList<() -> Unit>()
    var caps = JSONObject(); private set
    var ready = false; private set
    var dead = false; private set
    var command = ""; private set
    var mode = ""; private set

    fun has(cap: String): Boolean { val v = caps.opt(cap); return v != null && v != JSONObject.NULL && v != false }
    fun capability(cap: String): JSONObject? = caps.optJSONObject(cap)

    /** The characters after which the server wants a completion or signature help request. */
    fun triggerCharacters(cap: String): Set<Char> {
        val arr = capability(cap)?.optJSONArray("triggerCharacters") ?: return emptySet()
        val out = HashSet<Char>()
        for (i in 0 until arr.length()) arr.optString(i).firstOrNull()?.let { out.add(it) }
        return out
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun start() {
        if (Lsp.root.isNotEmpty()) { connect(); return }
        Api.async("roots", { Api.get("/api/roots") }) { r, e ->
            val primary = r?.optString("primary", "") ?: ""
            if (primary.isNotEmpty()) Lsp.root = primary
            else if (Api.folder.isNotEmpty()) Lsp.root = Api.folder
            if (Lsp.root.isEmpty()) { fail("The window's folder is unknown (" + (e?.message ?: "no answer") + "), so no language server was started.", false); return@async }
            connect()
        }
    }

    private fun connect() {
        val path = "/ws/lsp?lang=" + enc(key) + "&folder=" + enc(Lsp.root)
        val client = Api.WebSocketClient(path, { onText(it) }, { why -> onClosed(why) })
        ws = client
        client.connect()
    }

    private fun fail(message: String, memory: Boolean) {
        Log.w(TAG, "$key: $message")
        Lsp.markUnavailable(key, memory)
        Lsp.notify?.invoke(if (memory) "$message Code intelligence for this language starts when you reopen the file with more memory free." else message)
        dead = true
        ready = false
        finishAll("language server unavailable")
        ws?.close(); ws = null
    }

    private fun onClosed(why: String) {
        if (dead) return
        Log.w(TAG, "$key language server closed: $why")
        dead = true; ready = false
        finishAll("language server closed")
        for (p in docs.keys.toList()) Lsp.publish(p, emptyList())
        docs.clear()
    }

    private fun finishAll(reason: String) {
        for (p in pending.values) { Api.main.removeCallbacks(p.timeout); try { p.cb(null, reason) } catch (e: Exception) { } }
        pending.clear()
        waiting.clear()
        for (d in docs.values) { d.flush?.let { Api.main.removeCallbacks(it) }; d.pull?.let { Api.main.removeCallbacks(it) } }
    }

    fun stop() {
        if (dead) return
        dead = true; ready = false
        finishAll("language server stopped")
        try { send(JSONObject().put("id", ++seq).put("method", "shutdown")) } catch (e: Exception) { }
        ws?.close(); ws = null
        for (p in docs.keys.toList()) Lsp.publish(p, emptyList())
        docs.clear()
    }

    /** Runs the block once the server has answered initialize; dropped if the server dies first. */
    fun whenReady(block: () -> Unit) { if (ready) block() else if (!dead) waiting.add(block) }

    // ---------------------------------------------------------------- messages
    private fun send(o: JSONObject) {
        o.put("jsonrpc", "2.0")
        ws?.sendText(o.toString())
    }

    fun notify(method: String, params: JSONObject) { if (!dead) send(JSONObject().put("method", method).put("params", params)) }

    fun request(method: String, params: JSONObject, timeoutMs: Long = 15000, cb: (Any?, String?) -> Unit) {
        if (dead) { cb(null, "language server unavailable"); return }
        val id = ++seq
        val timeout = Runnable { pending.remove(id)?.cb?.invoke(null, "$method timed out") }
        pending[id] = Pending(cb, timeout)
        Api.main.postDelayed(timeout, timeoutMs)
        send(JSONObject().put("id", id).put("method", method).put("params", params))
    }

    private fun onText(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (msg.has("forge")) {
            when (msg.optString("forge")) {
                "ready" -> { command = msg.optString("command"); mode = msg.optString("mode"); initialize() }
                "error" -> { val m = msg.optString("message"); fail(m, m.contains("memory", ignoreCase = true)) }
                "exit" -> onClosed("the server process ended")
            }
            return
        }
        val hasId = msg.has("id") && !msg.isNull("id")
        val method = msg.optString("method", "")
        if (hasId && method.isEmpty()) {
            val p = pending.remove(msg.optInt("id")) ?: return
            Api.main.removeCallbacks(p.timeout)
            val err = msg.optJSONObject("error")
            if (err != null) p.cb(null, err.optString("message", "request failed")) else p.cb(msg.opt("result"), null)
            return
        }
        if (hasId) { onServerRequest(msg.get("id"), method, msg.optJSONObject("params")); return }
        when (method) {
            "textDocument/publishDiagnostics" -> msg.optJSONObject("params")?.let { onDiagnostics(it.optString("uri"), it.optJSONArray("diagnostics")) }
            "window/logMessage" -> msg.optJSONObject("params")?.let { if (it.optInt("type", 4) == 1) Log.w(TAG, key + ": " + it.optString("message")) }
            "window/showMessage" -> msg.optJSONObject("params")?.let { if (it.optInt("type", 4) <= 2) Lsp.notify?.invoke(key + ": " + it.optString("message")) }
        }
    }

    private fun onServerRequest(id: Any, method: String, params: JSONObject?) {
        var result: Any? = JSONObject.NULL
        try {
            when (method) {
                "workspace/configuration" -> { val items = params?.optJSONArray("items"); val a = JSONArray(); for (i in 0 until (items?.length() ?: 0)) a.put(JSONObject.NULL); result = a }
                "workspace/workspaceFolders" -> result = JSONArray().put(folderObject())
                "workspace/applyEdit" -> {
                    val edits = Lsp.parseWorkspaceEdit(params?.optJSONObject("edit"))
                    val applied = Lsp.applyEdit?.invoke(edits) ?: false
                    result = JSONObject().put("applied", applied)
                }
                "workspace/diagnostic/refresh" -> Api.main.postDelayed({ for ((p, _) in docs) pullSoon(p, 0) }, 300)
                "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create" -> result = JSONObject.NULL
            }
        } catch (e: Exception) { Log.w(TAG, "server request $method", e) }
        send(JSONObject().put("id", id).put("result", result))
    }

    private fun folderObject(): JSONObject = JSONObject().put("uri", Lsp.uriFor(Lsp.root)).put("name", Lsp.root.trimEnd('/').substringAfterLast('/'))

    private fun initialize() {
        val formats = JSONArray().put("markdown").put("plaintext")
        val td = JSONObject()
            .put("synchronization", JSONObject().put("didSave", true).put("dynamicRegistration", false))
            .put("completion", JSONObject().put("completionItem", JSONObject().put("snippetSupport", true).put("documentationFormat", formats).put("insertReplaceSupport", false)).put("contextSupport", true))
            .put("hover", JSONObject().put("contentFormat", formats))
            .put("signatureHelp", JSONObject().put("signatureInformation", JSONObject().put("documentationFormat", formats).put("parameterInformation", JSONObject().put("labelOffsetSupport", true))))
            .put("definition", JSONObject().put("linkSupport", true)).put("declaration", JSONObject().put("linkSupport", true))
            .put("typeDefinition", JSONObject().put("linkSupport", true)).put("implementation", JSONObject().put("linkSupport", true))
            .put("references", JSONObject()).put("documentSymbol", JSONObject().put("hierarchicalDocumentSymbolSupport", true))
            .put("formatting", JSONObject()).put("rangeFormatting", JSONObject())
            .put("rename", JSONObject().put("prepareSupport", false))
            .put("codeAction", JSONObject().put("codeActionLiteralSupport", JSONObject().put("codeActionKind", JSONObject().put("valueSet", JSONArray().put("quickfix").put("refactor").put("source")))))
            .put("publishDiagnostics", JSONObject().put("relatedInformation", false))
            .put("diagnostic", JSONObject().put("dynamicRegistration", false).put("relatedDocumentSupport", false))
        val params = JSONObject()
            .put("processId", JSONObject.NULL).put("rootUri", Lsp.uriFor(Lsp.root)).put("rootPath", Lsp.root)
            .put("workspaceFolders", JSONArray().put(folderObject()))
            .put("clientInfo", JSONObject().put("name", "Forge IDE"))
            .put("capabilities", JSONObject().put("textDocument", td).put("workspace", JSONObject().put("applyEdit", true).put("workspaceFolders", true).put("configuration", true)
                .put("symbol", JSONObject()).put("executeCommand", JSONObject())))
        request("initialize", params, 60000) { r, err ->
            if (err != null || dead) { if (!dead) fail("The $key language server did not initialize: $err", false); return@request }
            caps = (r as? JSONObject)?.optJSONObject("capabilities") ?: JSONObject()
            notify("initialized", JSONObject())
            if (key == "python") notify("workspace/didChangeConfiguration", JSONObject().put("settings", JSONObject().put("pylsp",
                JSONObject().put("plugins", JSONObject().put("jedi_symbols", JSONObject().put("include_import_symbols", false).put("all_scopes", true))))))
            ready = true
            val blocks = waiting.toList(); waiting.clear()
            for (b in blocks) try { b() } catch (e: Exception) { Log.w(TAG, "after ready", e) }
        }
    }

    // ---------------------------------------------------------------- documents
    private fun textDocument(path: String): JSONObject = JSONObject().put("uri", Lsp.uriFor(path))

    fun didOpen(path: String, languageId: String, text: String) {
        if (docs.containsKey(path)) { changed(path, text); return }
        val d = Doc(1); d.text = text
        docs[path] = d
        notify("textDocument/didOpen", JSONObject().put("textDocument", textDocument(path).put("languageId", languageId).put("version", 1).put("text", text)))
        pullSoon(path, 400)
    }

    fun changed(path: String, text: String) {
        val d = docs[path] ?: return
        d.text = text
        d.flush?.let { Api.main.removeCallbacks(it) }
        val task = Runnable { flush(path) }
        d.flush = task
        Api.main.postDelayed(task, 250)
    }

    private fun flush(path: String) {
        val d = docs[path] ?: return
        d.flush?.let { Api.main.removeCallbacks(it) }
        d.flush = null
        d.version++
        notify("textDocument/didChange", JSONObject().put("textDocument", textDocument(path).put("version", d.version))
            .put("contentChanges", JSONArray().put(JSONObject().put("text", d.text))))
        pullSoon(path, 700)
    }

    /** Sends a pending change at once, so a request that follows sees the current text. */
    fun sync(path: String) { val d = docs[path] ?: return; if (d.flush != null) flush(path) }

    fun didSave(path: String) {
        if (!docs.containsKey(path)) return
        sync(path)
        notify("textDocument/didSave", JSONObject().put("textDocument", textDocument(path)))
        pullSoon(path, 300)
    }

    fun didClose(path: String) {
        val d = docs.remove(path) ?: return
        d.flush?.let { Api.main.removeCallbacks(it) }; d.pull?.let { Api.main.removeCallbacks(it) }
        notify("textDocument/didClose", JSONObject().put("textDocument", textDocument(path)))
    }

    fun knows(path: String): Boolean = docs.containsKey(path)

    /** Servers like the Kotlin server send errors only when asked (pull diagnostics, LSP 3.17). */
    private fun pullSoon(path: String, delayMs: Long) {
        if (!has("diagnosticProvider")) return
        val d = docs[path] ?: return
        d.pull?.let { Api.main.removeCallbacks(it) }
        val task = Runnable { pull(path) }
        d.pull = task
        Api.main.postDelayed(task, delayMs)
    }

    private fun pull(path: String) {
        val d = docs[path] ?: return
        if (d.pulling) { d.pullAgain = true; return }
        d.pulling = true
        val params = JSONObject().put("textDocument", textDocument(path))
        if (d.resultId.isNotEmpty()) params.put("previousResultId", d.resultId)
        request("textDocument/diagnostic", params, 120000) { r, _ ->
            d.pulling = false
            val o = r as? JSONObject
            if (o != null) {
                o.optString("resultId", "").let { if (it.isNotEmpty()) d.resultId = it }
                if (o.optString("kind") != "unchanged" && o.has("items")) onDiagnostics(Lsp.uriFor(path), o.optJSONArray("items"))
            }
            if (d.pullAgain) { d.pullAgain = false; pullSoon(path, 300) }
        }
    }

    private fun onDiagnostics(uri: String, arr: JSONArray?) {
        val path = Lsp.pathFor(uri)
        val out = ArrayList<Lsp.Diagnostic>()
        if (arr != null) for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val r = d.optJSONObject("range") ?: continue
            val s = r.optJSONObject("start") ?: continue
            val e = r.optJSONObject("end") ?: s
            val code = d.opt("code")
            out.add(Lsp.Diagnostic(s.optInt("line"), s.optInt("character"), e.optInt("line"), e.optInt("character"), d.optInt("severity", 1),
                d.optString("message"), d.optString("source", key), if (code == null || code == JSONObject.NULL) "" else code.toString()))
        }
        Lsp.publish(path, out)
    }

    // ---------------------------------------------------------------- the editor's requests (0-based positions)
    private fun positionParams(path: String, line: Int, col: Int): JSONObject =
        JSONObject().put("textDocument", textDocument(path)).put("position", JSONObject().put("line", line).put("character", col))

    fun completion(path: String, line: Int, col: Int, triggerChar: Char?, cb: (List<Lsp.CompletionItem>, Boolean) -> Unit) {
        if (!ready || !has("completionProvider")) { cb(emptyList(), false); return }
        sync(path)
        val ctx = JSONObject().put("triggerKind", if (triggerChar != null) 2 else 1)
        if (triggerChar != null) ctx.put("triggerCharacter", triggerChar.toString())
        request("textDocument/completion", positionParams(path, line, col).put("context", ctx)) { r, _ ->
            val items = when (r) { is JSONArray -> r; is JSONObject -> r.optJSONArray("items"); else -> null }
            val incomplete = (r as? JSONObject)?.optBoolean("isIncomplete", false) ?: false
            val out = ArrayList<Lsp.CompletionItem>()
            if (items != null) for (i in 0 until minOf(items.length(), 400)) {
                val it = items.optJSONObject(i) ?: continue
                val label = it.optString("label")
                val te = it.optJSONObject("textEdit")
                val add = ArrayList<Lsp.TextEdit>()
                it.optJSONArray("additionalTextEdits")?.let { a -> for (k in 0 until a.length()) Lsp.parseEdit(a.optJSONObject(k))?.let { add.add(it) } }
                val snippet = it.optInt("insertTextFormat", 1) == 2
                val insert = te?.optString("newText") ?: it.optString("insertText", label).ifEmpty { label }
                out.add(Lsp.CompletionItem(label, it.optInt("kind", 1), Lsp.str(it, "detail"), Lsp.docOf(it.opt("documentation")),
                    if (snippet) Lsp.plainSnippet(insert) else insert, Lsp.parseEdit(te), add, it.optString("sortText", label), it.optString("filterText", label), snippet, it))
            }
            cb(out, incomplete)
        }
    }

    /** Asks the server for the full item (documentation, additional edits such as an import); the raw item is sent back. */
    fun resolveCompletion(item: Lsp.CompletionItem, cb: (Lsp.CompletionItem) -> Unit) {
        val provider = capability("completionProvider")
        if (!ready || provider == null || !provider.optBoolean("resolveProvider", false)) { cb(item); return }
        request("completionItem/resolve", item.raw, 4000) { r, _ ->
            val o = r as? JSONObject
            if (o == null) { cb(item); return@request }
            val add = ArrayList<Lsp.TextEdit>(item.additional)
            if (add.isEmpty()) o.optJSONArray("additionalTextEdits")?.let { a -> for (k in 0 until a.length()) Lsp.parseEdit(a.optJSONObject(k))?.let { add.add(it) } }
            cb(Lsp.CompletionItem(item.label, item.kind, o.optString("detail", item.detail).ifEmpty { item.detail }, Lsp.docOf(o.opt("documentation")).ifEmpty { item.documentation },
                item.insertText, item.edit, add, item.sortText, item.filterText, item.snippet, o))
        }
    }

    fun hover(path: String, line: Int, col: Int, cb: (Lsp.Hover?) -> Unit) {
        if (!ready || !has("hoverProvider")) { cb(null); return }
        sync(path)
        request("textDocument/hover", positionParams(path, line, col)) { r, _ ->
            val o = r as? JSONObject
            if (o == null || !o.has("contents")) { cb(null); return@request }
            val md = Lsp.markdownOf(o.opt("contents"))
            if (md.isEmpty()) { cb(null); return@request }
            val range = o.optJSONObject("range")?.let { Lsp.parseLocation(JSONObject().put("uri", Lsp.uriFor(path)).put("range", it)) }
            cb(Lsp.Hover(md, range))
        }
    }

    fun signatureHelp(path: String, line: Int, col: Int, cb: (Lsp.SignatureHelp?) -> Unit) {
        if (!ready || !has("signatureHelpProvider")) { cb(null); return }
        sync(path)
        request("textDocument/signatureHelp", positionParams(path, line, col)) { r, _ ->
            val o = r as? JSONObject
            val sigs = o?.optJSONArray("signatures")
            if (sigs == null || sigs.length() == 0) { cb(null); return@request }
            val out = ArrayList<Lsp.Signature>()
            for (i in 0 until sigs.length()) {
                val s = sigs.optJSONObject(i) ?: continue
                val params = ArrayList<Pair<String, String>>()
                s.optJSONArray("parameters")?.let { ps ->
                    for (k in 0 until ps.length()) {
                        val p = ps.optJSONObject(k) ?: continue
                        val lab = p.opt("label")
                        val label = if (lab is JSONArray && lab.length() == 2) s.optString("label").let { l -> val a = lab.optInt(0); val b = lab.optInt(1); if (a in 0..b && b <= l.length) l.substring(a, b) else "" } else p.optString("label")
                        params.add(label to Lsp.docOf(p.opt("documentation")))
                    }
                }
                out.add(Lsp.Signature(s.optString("label"), Lsp.docOf(s.opt("documentation")), params))
            }
            cb(Lsp.SignatureHelp(out, o.optInt("activeSignature", 0), o.optInt("activeParameter", 0)))
        }
    }

    /** Definition, declaration, type definition or implementation, by the method name; the list may be empty. */
    fun locations(method: String, cap: String, path: String, line: Int, col: Int, includeDeclaration: Boolean, cb: (List<Lsp.Location>, String?) -> Unit) {
        if (!ready) { cb(emptyList(), "The language server is still starting."); return }
        if (!has(cap)) { cb(emptyList(), "The $key language server does not support this."); return }
        sync(path)
        val params = positionParams(path, line, col)
        if (method == "textDocument/references") params.put("context", JSONObject().put("includeDeclaration", includeDeclaration))
        request(method, params) { r, err -> cb(Lsp.parseLocations(r), err) }
    }

    /** The edits that format the whole document (Shift Alt F). */
    fun formatting(path: String, tabSize: Int, insertSpaces: Boolean, cb: (List<Lsp.TextEdit>?, String?) -> Unit) {
        if (!ready) { cb(null, "The language server is still starting."); return }
        if (!has("documentFormattingProvider")) { cb(null, "The $key language server cannot format documents."); return }
        sync(path)
        val params = JSONObject().put("textDocument", textDocument(path)).put("options", JSONObject().put("tabSize", tabSize).put("insertSpaces", insertSpaces))
        request("textDocument/formatting", params, 20000) { r, err ->
            if (err != null) { cb(null, err); return@request }
            val out = ArrayList<Lsp.TextEdit>()
            (r as? JSONArray)?.let { a -> for (i in 0 until a.length()) Lsp.parseEdit(a.optJSONObject(i))?.let { out.add(it) } }
            cb(out, null)
        }
    }

    /** Quick fixes and refactorings for a range, with the diagnostics that touch it (Ctrl . and the light bulb). */
    fun codeActions(path: String, line: Int, col: Int, endLine: Int, endCol: Int, diags: List<Lsp.Diagnostic>, cb: (List<Lsp.CodeAction>, String?) -> Unit) {
        if (!ready) { cb(emptyList(), "The language server is still starting."); return }
        if (!has("codeActionProvider")) { cb(emptyList(), "The $key language server offers no quick fixes."); return }
        sync(path)
        val arr = JSONArray()
        for (d in diags) {
            val o = JSONObject().put("range", Lsp.range(d.line, d.col, d.endLine, d.endCol)).put("message", d.message).put("severity", d.severity)
            if (d.source.isNotEmpty()) o.put("source", d.source)
            if (d.code.isNotEmpty()) o.put("code", d.code)
            arr.put(o)
        }
        val params = JSONObject().put("textDocument", textDocument(path)).put("range", Lsp.range(line, col, endLine, endCol))
            .put("context", JSONObject().put("diagnostics", arr))
        request("textDocument/codeAction", params, 10000) { r, err ->
            if (err != null) { cb(emptyList(), err); return@request }
            val out = ArrayList<Lsp.CodeAction>()
            (r as? JSONArray)?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    // A bare Command (title, command, arguments) or a CodeAction literal (title, kind, edit, command).
                    val bare = x.opt("command") is String
                    val edits = if (bare) emptyMap() else Lsp.parseWorkspaceEdit(x.optJSONObject("edit"))
                    val cmd = if (bare) x else x.optJSONObject("command")
                    if (edits.isEmpty() && cmd == null) continue
                    out.add(Lsp.CodeAction(x.optString("title"), x.optString("kind", "quickfix"), x.optBoolean("isPreferred", false), edits, cmd))
                }
            }
            cb(out.sortedByDescending { it.preferred }, null)
        }
    }

    /** Runs a command of a code action on the server; the server sends its edits back as workspace/applyEdit. */
    fun executeCommand(command: JSONObject, cb: (String?) -> Unit) {
        if (!ready) { cb("The language server is still starting."); return }
        val params = JSONObject().put("command", command.optString("command")).put("arguments", command.optJSONArray("arguments") ?: JSONArray())
        request("workspace/executeCommand", params, 20000) { _, err -> cb(err) }
    }

    /** The outline of a document: classes, functions and the rest, nested when the server sends a tree. */
    fun documentSymbols(path: String, cb: (List<Lsp.Symbol>, String?) -> Unit) {
        if (!ready) { cb(emptyList(), "The language server is still starting."); return }
        if (!has("documentSymbolProvider")) { cb(emptyList(), "The $key language server has no outline."); return }
        sync(path)
        request("textDocument/documentSymbol", JSONObject().put("textDocument", textDocument(path)), 15000) { r, err ->
            if (err != null) { cb(emptyList(), err); return@request }
            fun conv(o: JSONObject): Lsp.Symbol? {
                val loc = o.optJSONObject("location")
                val range = (loc?.optJSONObject("range")) ?: o.optJSONObject("selectionRange") ?: o.optJSONObject("range") ?: return null
                val s = range.optJSONObject("start") ?: return null
                val e = (o.optJSONObject("range") ?: range).optJSONObject("end") ?: s
                val kids = ArrayList<Lsp.Symbol>()
                o.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) c.optJSONObject(i)?.let { conv(it) }?.let { kids.add(it) } }
                return Lsp.Symbol(Lsp.str(o, "name"), Lsp.str(o, "detail").ifEmpty { Lsp.str(o, "containerName") }, o.optInt("kind", 13),
                    s.optInt("line"), s.optInt("character"), e.optInt("line"), e.optInt("character"), kids, path)
            }
            val out = ArrayList<Lsp.Symbol>()
            (r as? JSONArray)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { conv(it) }?.let { out.add(it) } }
            val sorted = out.sortedWith(compareBy({ it.line }, { it.col }))
            cb(if (sorted.any { it.children.isNotEmpty() }) sorted else nestFlat(sorted), null)
        }
    }

    /**
     * Servers that send a flat list (pylsp) name each symbol's container; the outline nests them by that name and by
     * position, so methods sit under their class. Variables local to a function are left out, as Cursor does.
     */
    private fun nestFlat(flat: List<Lsp.Symbol>): List<Lsp.Symbol> {
        class Node(val s: Lsp.Symbol) { val kids = ArrayList<Node>() }
        val roots = ArrayList<Node>()
        val all = ArrayList<Node>()
        val functions = setOf(6, 9, 12)
        for (sym in flat) {
            val container = sym.detail
            val parent = if (container.isEmpty()) null else all.lastOrNull { it.s.name == container && sym.line >= it.s.line && sym.line <= maxOf(it.s.endLine, it.s.line) }
            if (parent != null && parent.s.kind in functions && sym.kind in setOf(13, 14, 7, 8)) continue
            val n = Node(sym)
            all.add(n)
            if (parent != null) parent.kids.add(n) else roots.add(n)
        }
        fun build(n: Node): Lsp.Symbol = Lsp.Symbol(n.s.name, if (n.s.detail.isNotEmpty() && n.kids.isEmpty() && all.none { it.s.name == n.s.detail }) n.s.detail else "",
            n.s.kind, n.s.line, n.s.col, n.s.endLine, n.s.endCol, n.kids.map { build(it) }, n.s.path)
        return roots.map { build(it) }
    }

    /** Symbols of the whole project by name (Ctrl T), when the server can search them. */
    fun workspaceSymbols(query: String, cb: (List<Lsp.Symbol>, String?) -> Unit) {
        if (!ready || !has("workspaceSymbolProvider")) { cb(emptyList(), null); return }
        request("workspace/symbol", JSONObject().put("query", query), 15000) { r, err ->
            val out = ArrayList<Lsp.Symbol>()
            (r as? JSONArray)?.let { a ->
                for (i in 0 until minOf(a.length(), 200)) {
                    val o = a.optJSONObject(i) ?: continue
                    val loc = Lsp.parseLocation(o.optJSONObject("location")) ?: continue
                    out.add(Lsp.Symbol(Lsp.str(o, "name"), Lsp.str(o, "containerName"), o.optInt("kind", 13), loc.line, loc.col, loc.endLine, loc.endCol, emptyList(), loc.path))
                }
            }
            cb(out, err)
        }
    }

    fun rename(path: String, line: Int, col: Int, newName: String, cb: (Map<String, List<Lsp.TextEdit>>?, String?) -> Unit) {
        if (!ready) { cb(null, "The language server is still starting."); return }
        if (!has("renameProvider")) { cb(null, "The $key language server cannot rename."); return }
        sync(path)
        request("textDocument/rename", positionParams(path, line, col).put("newName", newName)) { r, err ->
            if (err != null) cb(null, err) else cb(Lsp.parseWorkspaceEdit(r as? JSONObject), null)
        }
    }
}
