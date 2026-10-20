package local.forge

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One debug session: a Debug Adapter Protocol client over the server's /ws/dap WebSocket, which bridges the window
 * to the adapter for the language (debugpy for Python, GDB for C and C++, rust-gdb for Rust, js-debug for
 * JavaScript and TypeScript, and the Kotlin language server's debugger for Kotlin and Java).
 *
 * The wire format, the same as the old page used: every WebSocket text frame is one JSON object. Messages from
 * this side are DAP requests `{seq, type: "request", command, arguments}`; the adapter answers with
 * `{type: "response", request_seq, success, message, body}` and sends `{type: "event", event, body}`. Before any
 * DAP traffic the bridge sends its own `{"forge": "ready"}` once the adapter runs, or `{"forge": "error",
 * "message"}` when it could not start; `{"forge": "exit"}` means the adapter process ended. A request that the
 * adapter sends to the client (for example runInTerminal) is answered with a failed response.
 *
 * Every request has a deadline, the connection has the WebSocket client's own timeouts, and closing the session
 * fails every waiting request, so nothing here can wait forever. All callbacks arrive on the main thread.
 */
class DebugSession(val lang: String, val noDebug: Boolean, private val listener: Listener) {
    interface Listener {
        fun onOutput(text: String, category: String)
        fun onStopped(body: JSONObject)
        fun onContinued()
        fun onExited(code: Int)
        /** The session ended: a terminated event, the adapter exited, or the connection dropped. Called once, with the
         *  session that ended (during a Restart the listener may already have begun the next one). */
        fun onEnded(ended: DebugSession)
    }

    enum class State { STARTING, RUNNING, STOPPED, ENDED }

    @Volatile var state = State.STARTING
    var threadId = 0
    var threads: List<JSONObject> = emptyList()
    var frames: List<JSONObject> = emptyList()
    var frameId = -1
    var frame: JSONObject? = null
    var scopes: List<JSONObject> = emptyList()
    var reason = ""
    /** The reply of /api/debug/android when this session is attached to an app on a device. */
    var android: JSONObject? = null
    /** How the view began this session (launch, attach or an Android app), so Restart begins the next one the same way. */
    var origin: Any? = null
    /** True once the adapter sent its initialized event (it is ready for breakpoints). */
    var initialized = false
        private set

    private var ws: Api.WebSocketClient? = null
    private var seq = 1
    private class Pending(val command: String, val cb: (JSONObject?, Exception?) -> Unit, val deadline: Runnable)
    private val pending = HashMap<Int, Pending>()
    private val initializedWaiters = ArrayList<() -> Unit>()
    private var ready = false
    private var ended = false
    private var connectCb: ((Exception?) -> Unit)? = null
    private var connectDeadline: Runnable? = null

    // ---------------------------------------------------------------- connection
    /** Opens the bridge; [cb] gets null once the adapter is ready, or the reason it did not start. */
    fun connect(cb: (Exception?) -> Unit) {
        val folder = if (Api.folder.isNotEmpty()) "&folder=" + encode(Api.folder) else ""
        val client = Api.WebSocketClient("/ws/dap?lang=" + encode(lang) + folder, { text -> onText(text) }, { why -> onClosed(why) })
        ws = client
        connectCb = cb
        val deadline = Runnable {
            if (!ready) {
                val c = connectCb; connectCb = null
                c?.invoke(Exception("The debugger did not start in time."))
                close()
            }
        }
        connectDeadline = deadline
        Api.main.postDelayed(deadline, if (lang == "kotlin" || lang == "java") 120000L else 20000L)
        client.connect()
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Closes the connection and fails every waiting request. The listener's onEnded is not called by this. */
    fun close() {
        ended = true
        state = State.ENDED
        connectDeadline?.let { Api.main.removeCallbacks(it) }
        ws?.close()
        ws = null
        failPending("The debugger disconnected.")
    }

    private fun failPending(why: String) {
        val all = ArrayList(pending.values)
        pending.clear()
        for (p in all) { Api.main.removeCallbacks(p.deadline); p.cb(null, Exception(why)) }
    }

    private fun onClosed(why: String) {
        if (!ready) {
            val c = connectCb; connectCb = null
            connectDeadline?.let { Api.main.removeCallbacks(it) }
            c?.invoke(Exception("Cannot connect to the debugger ($why)."))
            return
        }
        failPending("The debugger disconnected ($why).")
        end()
    }

    private fun end() {
        if (ended) return
        ended = true
        state = State.ENDED
        failPending("The debugger disconnected.")
        listener.onEnded(this)
    }

    // ---------------------------------------------------------------- messages
    private fun onText(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (msg.has("forge")) {
            when (msg.optText("forge")) {
                "ready" -> {
                    ready = true
                    connectDeadline?.let { Api.main.removeCallbacks(it) }
                    val c = connectCb; connectCb = null
                    c?.invoke(null)
                }
                "error" -> {
                    connectDeadline?.let { Api.main.removeCallbacks(it) }
                    val c = connectCb; connectCb = null
                    if (c != null) c(Exception(msg.optText("message", "The debugger did not start."))) else listener.onOutput(msg.optText("message", "The debugger reported an error.") + "\n", "stderr")
                    if (!ready) close()
                }
                "exit" -> end()
            }
            return
        }
        when (msg.optText("type")) {
            "response" -> {
                val p = pending.remove(msg.optInt("request_seq")) ?: return
                Api.main.removeCallbacks(p.deadline)
                if (msg.optBoolean("success", false)) p.cb(msg.optJSONObject("body") ?: JSONObject(), null)
                else p.cb(null, Exception(errorText(msg, p.command)))
            }
            "event" -> onEvent(msg.optText("event"), msg.optJSONObject("body") ?: JSONObject())
            "request" -> send(JSONObject().put("type", "response").put("request_seq", msg.optInt("seq")).put("command", msg.optText("command"))
                .put("success", false).put("message", "not supported by Forge"))
        }
    }

    /** The adapter's error message, with the DAP error format's {variables} filled in. */
    private fun errorText(msg: JSONObject, command: String): String {
        val err = msg.optJSONObject("body")?.optJSONObject("error")
        var text = msg.optText("message", "")
        if (err != null) {
            var f = err.optText("format", "")
            val vars = err.optJSONObject("variables")
            if (vars != null) for (k in vars.keys()) f = f.replace("{$k}", vars.optText(k))
            if (f.isNotEmpty()) text = f
        }
        return if (text.isEmpty()) "$command failed" else text
    }

    private fun onEvent(event: String, body: JSONObject) {
        when (event) {
            "initialized" -> {
                initialized = true
                val w = ArrayList(initializedWaiters); initializedWaiters.clear()
                for (f in w) f()
            }
            "output" -> {
                val category = body.optText("category", "stdout")
                val text = body.optText("output", "")
                if (category != "telemetry" && text.isNotEmpty()) listener.onOutput(text, category)
            }
            "stopped" -> {
                state = State.STOPPED
                val t = body.optInt("threadId", 0)
                if (t != 0) threadId = t
                val text = body.optText("text", "")
                val desc = body.optText("description", "")
                reason = body.optText("reason", "") + (if (text.isNotEmpty()) ": $text" else if (desc.isNotEmpty()) ": $desc" else "")
                listener.onStopped(body)
            }
            "continued" -> {
                if (body.optBoolean("allThreadsContinued", true) || body.optInt("threadId", 0) == threadId) {
                    state = State.RUNNING
                    frames = emptyList(); frame = null; frameId = -1; scopes = emptyList()
                    listener.onContinued()
                }
            }
            "thread" -> if (threadId == 0 && body.optText("reason") == "started") threadId = body.optInt("threadId", 0)
            "exited" -> listener.onExited(body.optInt("exitCode", 0))
            "terminated" -> end()
        }
    }

    private fun send(obj: JSONObject) { ws?.sendText(obj.toString()) }

    /** Sends one request; [cb] gets the response body, or the error. Never both, never neither. */
    fun request(command: String, args: JSONObject = JSONObject(), timeoutMs: Long = 30000, cb: (JSONObject?, Exception?) -> Unit) {
        if (ended || ws == null) { cb(null, Exception("The debugger is not connected.")); return }
        val id = seq++
        val deadline = Runnable {
            val p = pending.remove(id) ?: return@Runnable
            p.cb(null, Exception("$command timed out"))
        }
        pending[id] = Pending(command, cb, deadline)
        Api.main.postDelayed(deadline, timeoutMs)
        send(JSONObject().put("seq", id).put("type", "request").put("command", command).put("arguments", args))
    }

    /** Runs [f] once the initialized event has arrived (at once when it already has). */
    fun onceInitialized(f: () -> Unit) { if (initialized) f() else initializedWaiters.add(f) }

    // ---------------------------------------------------------------- the requests the view uses
    fun initialize(adapterId: String, cb: (JSONObject?, Exception?) -> Unit) {
        request("initialize", JSONObject().put("clientID", "forge").put("clientName", "Forge IDE").put("adapterID", adapterId)
            .put("pathFormat", "path").put("linesStartAt1", true).put("columnsStartAt1", true).put("supportsVariableType", true)
            .put("supportsRunInTerminalRequest", false).put("locale", "en"), 30000, cb)
    }

    fun launch(args: JSONObject, timeoutMs: Long, cb: (JSONObject?, Exception?) -> Unit) = request("launch", args, timeoutMs, cb)
    fun attach(args: JSONObject, timeoutMs: Long, cb: (JSONObject?, Exception?) -> Unit) = request("attach", args, timeoutMs, cb)

    /** Replaces the breakpoints of one file (an absolute path) with [lines]. */
    fun setBreakpoints(absPath: String, lines: List<Int>, cb: (JSONObject?, Exception?) -> Unit) {
        val bps = JSONArray()
        for (l in lines) bps.put(JSONObject().put("line", l))
        request("setBreakpoints", JSONObject()
            .put("source", JSONObject().put("path", absPath).put("name", absPath.substringAfterLast('/')))
            .put("breakpoints", bps).put("lines", JSONArray(lines)), 30000, cb)
    }

    fun setExceptionBreakpoints(filters: List<String>, cb: (JSONObject?, Exception?) -> Unit) =
        request("setExceptionBreakpoints", JSONObject().put("filters", JSONArray(filters)), 30000, cb)

    fun configurationDone(cb: (JSONObject?, Exception?) -> Unit) = request("configurationDone", JSONObject(), 30000, cb)

    private fun threadArgs() = JSONObject().put("threadId", if (threadId != 0) threadId else 1)
    fun continueRun(cb: (JSONObject?, Exception?) -> Unit) = request("continue", threadArgs(), 30000, cb)
    fun next(cb: (JSONObject?, Exception?) -> Unit) = request("next", threadArgs(), 30000, cb)
    fun stepIn(cb: (JSONObject?, Exception?) -> Unit) = request("stepIn", threadArgs(), 30000, cb)
    fun stepOut(cb: (JSONObject?, Exception?) -> Unit) = request("stepOut", threadArgs(), 30000, cb)
    fun pause(cb: (JSONObject?, Exception?) -> Unit) = request("pause", threadArgs(), 30000, cb)

    /** Ends the program (or detaches from an attached one) and the adapter. */
    fun terminate(cb: (JSONObject?, Exception?) -> Unit) =
        request("disconnect", JSONObject().put("terminateDebuggee", android == null), 5000, cb)

    fun threads(cb: (List<JSONObject>?, Exception?) -> Unit) {
        request("threads", JSONObject(), 15000) { r, e -> if (r == null) cb(null, e) else cb(list(r.optJSONArray("threads")), null) }
    }

    fun stackTrace(thread: Int, cb: (List<JSONObject>?, Exception?) -> Unit) {
        request("stackTrace", JSONObject().put("threadId", thread).put("startFrame", 0).put("levels", 60), 30000) { r, e ->
            if (r == null) cb(null, e) else cb(list(r.optJSONArray("stackFrames")), null)
        }
    }

    fun scopes(frameId: Int, cb: (List<JSONObject>?, Exception?) -> Unit) {
        request("scopes", JSONObject().put("frameId", frameId), 30000) { r, e -> if (r == null) cb(null, e) else cb(list(r.optJSONArray("scopes")), null) }
    }

    fun variables(reference: Int, cb: (List<JSONObject>?, Exception?) -> Unit) {
        request("variables", JSONObject().put("variablesReference", reference), 30000) { r, e ->
            if (r == null) cb(null, e) else cb(list(r.optJSONArray("variables")), null)
        }
    }

    /** Evaluates an expression in the selected frame; [context] is "repl", "watch" or "hover". */
    fun evaluate(expression: String, context: String, cb: (JSONObject?, Exception?) -> Unit) {
        val args = JSONObject().put("expression", expression).put("context", context)
        if (frameId >= 0) args.put("frameId", frameId)
        request("evaluate", args, 30000, cb)
    }

    fun exceptionInfo(cb: (JSONObject?, Exception?) -> Unit) = request("exceptionInfo", JSONObject().put("threadId", threadId), 15000, cb)

    private fun list(a: JSONArray?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    companion object {
        /** The adapters send plain paths, except the Kotlin debugger, which sends file URIs. */
        fun sourcePath(p: String): String =
            if (p.startsWith("file://")) try { URLDecoder.decode(p.substring(7), "UTF-8") } catch (e: Exception) { p.substring(7) } else p

        /** The language family of a file, or null when Forge cannot debug it. */
        fun langOf(path: String): String? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "py" -> "python"
                "c", "h" -> "c"
                "cc", "cpp", "cxx", "hpp" -> "cpp"
                "rs" -> "rust"
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "js", "mjs", "cjs" -> "javascript"
                "ts", "mts", "cts" -> "typescript"
                else -> null
            }
        }

        /** Whether a file's breakpoints belong to a session's language (the adapter would refuse the others). */
        fun sameFamily(lang: String, path: String): Boolean {
            val l = langOf(path) ?: return false
            return when (lang) {
                "python" -> l == "python"
                "c", "cpp" -> l == "c" || l == "cpp"
                "rust" -> l == "rust"
                "kotlin", "java" -> l == "kotlin" || l == "java"
                "javascript", "typescript" -> l == "javascript" || l == "typescript"
                else -> false
            }
        }

        fun adapterId(lang: String): String = when (lang) {
            "python" -> "debugpy"
            "kotlin", "java" -> "intellij_jvm"
            "javascript", "typescript" -> "pwa-node"
            else -> "gdb"
        }
    }
}
