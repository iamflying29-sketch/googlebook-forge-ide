package local.forge

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Run and debug, the native version of the old page's module: a launch configuration picker (the open file, or
 * the configurations of the project's .vscode/launch.json and .forge/launch.json), the program and its
 * arguments, Start, Run without debugging, and while a session runs the Continue, Pause, Step over, Step into,
 * Step out, Restart and Stop controls, a status line, the threads and the call stack (a tap opens the frame's
 * file at its line), the scopes and variables as an expandable tree, watch expressions, and the breakpoints with
 * an enable box and a remove button each.
 *
 * The session speaks the Debug Adapter Protocol through [DebugSession]. Python runs under debugpy; C, C++ and
 * Rust are built with debug information by /api/debug/prepare and run under GDB; Kotlin and Java are compiled by
 * /api/debug/prepare and run under the Kotlin language server's debugger; JavaScript runs under js-debug and
 * TypeScript is compiled by tsc first. An Android app on the target device is debugged through [attachAndroid]:
 * /api/debug/android starts it waiting for the debugger and the Kotlin debugger attaches through the forwarded
 * port.
 */
class DebugPanel(private val ctx: Context, private val host: MainActivity) : DebugSession.Listener {
    val view: LinearLayout = LinearLayout(ctx)

    /** One entry of the picker: the open file, or a configuration read from a launch file. */
    private class LaunchConfig(val name: String, val lang: String?, val request: String, val program: String, val args: String,
                               val cwd: String, val stopOnEntry: Boolean, val env: JSONObject?, val hostName: String, val port: Int, val source: String)

    private val configChip: LinearLayout
    private val program = Ui.input(ctx, "File to run (default: the open file)", mono = true)
    private val args = Ui.input(ctx, "Arguments (optional)", mono = true)
    private val startButton: android.widget.Button
    private val runButton: android.widget.Button
    private val tools: LinearLayout
    private val continueButton: LinearLayout
    private val pauseButton: LinearLayout
    private val overButton: LinearLayout
    private val intoButton: LinearLayout
    private val outButton: LinearLayout
    private val restartButton: LinearLayout
    private val stopButton: LinearLayout
    private val status = Ui.text(ctx, "", 11.5f, Ui.accent2)
    private val dynamic = Ui.column(ctx)
    private val watchInput = Ui.input(ctx, "Add an expression to watch", mono = true)

    private var session: DebugSession? = null
    private val configs = ArrayList<LaunchConfig>()
    private var selected: LaunchConfig? = null
    private val watches = ArrayList<String>()
    private var renderSeq = 0
    private val prefs = ctx.applicationContext.getSharedPreferences("forge-debug", Context.MODE_PRIVATE)

    init {
        Breakpoints.init(ctx)
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(Chrome.panelHeader(ctx, "Run and debug", Ui.miniButton(ctx, R.drawable.ic_refresh, "Reload the launch configurations") { loadConfigs(true) }))
        val pad = Ui.dp(ctx, 10)
        val body = Ui.column(ctx)
        body.setPadding(pad, 0, pad, pad)

        configChip = Ui.chip(ctx, "Open file", Ui.fg3) { v -> showConfigMenu(v) }
        body.addView(configChip, Ui.lp(Ui.wrap, Ui.wrap).also { it.bottomMargin = Ui.dp(ctx, 6) })
        program.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        args.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        program.setText(prefs.getString("program:" + Api.folder, "") ?: "")
        args.setText(prefs.getString("args:" + Api.folder, "") ?: "")
        body.addView(program, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = Ui.dp(ctx, 5) })
        body.addView(args, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = Ui.dp(ctx, 6) })

        startButton = Ui.button(ctx, "Start debugging (F5)", primary = true) { start(false) }
        runButton = Ui.button(ctx, "Run without debugging") { start(true) }
        val actions = Ui.row(ctx, startButton, runButton)
        (runButton.layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(ctx, 6)
        body.addView(actions, Ui.lp(Ui.wrap, Ui.wrap))

        continueButton = Ui.button(ctx, R.drawable.ic_continue, null, "Continue (F5)") { cont() }
        pauseButton = Ui.button(ctx, R.drawable.ic_pause, null, "Pause (F6)") { pause() }
        overButton = Ui.button(ctx, R.drawable.ic_step_over, null, "Step over (F10)") { step("next") }
        intoButton = Ui.button(ctx, R.drawable.ic_step_into, null, "Step into (F11)") { step("stepIn") }
        outButton = Ui.button(ctx, R.drawable.ic_step_out, null, "Step out (Shift F11)") { step("stepOut") }
        restartButton = Ui.button(ctx, R.drawable.ic_refresh, null, "Restart (Ctrl Shift F5)") { restart() }
        stopButton = Ui.button(ctx, R.drawable.ic_stop, null, "Stop (Shift F5)") { stop() }
        tint(continueButton, Ui.green); tint(stopButton, Ui.red)
        tools = Ui.row(ctx, continueButton, pauseButton, overButton, intoButton, outButton, restartButton, stopButton)
        tools.background = Ui.rounded(Ui.bg3, 7, Ui.border)
        val tp = Ui.dp(ctx, 3); tools.setPadding(tp, tp, tp, tp)
        body.addView(tools, Ui.lp(Ui.wrap, Ui.wrap).also { it.topMargin = Ui.dp(ctx, 8); it.bottomMargin = Ui.dp(ctx, 4) })
        body.addView(status, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = Ui.dp(ctx, 6) })
        body.addView(dynamic, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.scroll(ctx, body), Ui.lp(Ui.match, 0, 1f))

        watchInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        watchInput.setOnKeyListener { _, code, ev ->
            if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && ev.action == KeyEvent.ACTION_DOWN) {
                val w = watchInput.text.toString().trim()
                if (w.isNotEmpty()) { watches.add(w); watchInput.setText(""); persistWatches(); render() }
                true
            } else false
        }
        try {
            val a = JSONArray(prefs.getString("watches", "[]") ?: "[]")
            for (i in 0 until a.length()) watches.add(a.optString(i))
        } catch (e: Exception) { }

        Breakpoints.sessionListener = { path -> onBreakpointsChanged(path) }
        render()
        loadConfigs(false)
    }

    private fun tint(b: LinearLayout, color: Int) {
        val tint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_hovered), intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(color, color, color))
        for (i in 0 until b.childCount) (b.getChildAt(i) as? android.widget.ImageView)?.imageTintList = tint
    }

    private fun enable(b: View, on: Boolean) { b.isEnabled = on; b.alpha = if (on) 1f else 0.35f }

    fun active(): Boolean = session != null

    // ---------------------------------------------------------------- launch configurations
    private fun showConfigMenu(anchor: View) {
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 0, 0, "Open file (Python, C, C++, Rust, Kotlin, Java, JavaScript, TypeScript)")
        for ((i, c) in configs.withIndex()) menu.menu.add(0, i + 1, i + 1, c.name + "  (" + c.source + ")")
        menu.menu.add(0, configs.size + 1, configs.size + 1, "Reload launch configurations")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { selected = null; program.setText(""); args.setText(""); Ui.setChip(configChip, "Open file", Ui.fg3) }
                configs.size + 1 -> loadConfigs(true)
                else -> {
                    val c = configs[item.itemId - 1]
                    selected = c
                    program.setText(c.program); args.setText(c.args)
                    Ui.setChip(configChip, c.name, Ui.accent)
                }
            }
            true
        }
        menu.show()
    }

    /** Reads .vscode/launch.json and .forge/launch.json through the server; a missing file is simply skipped. */
    private fun loadConfigs(announce: Boolean) {
        Api.async("launch-configs", {
            val found = ArrayList<LaunchConfig>()
            for (file in listOf(".forge/launch.json", ".vscode/launch.json")) {
                val text = try { Api.get("/api/file", mapOf("path" to file), 10000).optString("content", "") } catch (e: Exception) { continue }
                try { parseConfigs(text, file, found) } catch (e: Exception) { }
            }
            found
        }) { r, e ->
            if (r == null) { if (announce) Ui.toast(ctx, "Could not read the launch files: " + (e?.message ?: "")); return@async }
            configs.clear(); configs.addAll(r)
            val keep = selected?.let { s -> configs.firstOrNull { it.name == s.name && it.source == s.source } }
            selected = keep
            if (keep == null) Ui.setChip(configChip, "Open file", Ui.fg3)
            if (announce) Ui.toast(ctx, if (configs.isEmpty()) "No launch configurations in .vscode/launch.json or .forge/launch.json." else configs.size.toString() + " launch configurations.")
        }
    }

    private fun parseConfigs(text: String, source: String, into: MutableList<LaunchConfig>) {
        val root = JSONObject(stripJsonComments(text))
        val list = root.optJSONArray("configurations") ?: return
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val type = c.optString("type", "").lowercase()
            val lang = when (type) {
                "python", "debugpy" -> "python"
                "node", "pwa-node", "node-terminal", "javascript" -> "javascript"
                "typescript" -> "typescript"
                "kotlin" -> "kotlin"
                "java" -> "java"
                "cppdbg", "gdb", "cppvsdbg", "lldb", "c", "cpp" -> "cpp"
                "rust", "rust-gdb" -> "rust"
                else -> null
            }
            val a = c.opt("args")
            val argsText = when {
                a is JSONArray -> (0 until a.length()).joinToString(" ") { quote(a.optString(it)) }
                a is String -> a
                else -> ""
            }
            into.add(LaunchConfig(c.optString("name", "configuration " + (i + 1)), lang, c.optString("request", "launch"),
                c.optString("program", c.optString("file", "")), argsText, c.optString("cwd", ""), c.optBoolean("stopOnEntry", false),
                c.optJSONObject("env"), c.optString("hostName", c.optString("host", "127.0.0.1")), c.optInt("port", 0), source))
        }
    }

    private fun quote(s: String): String = if (s.isEmpty() || s.any { it.isWhitespace() || it == '"' }) "\"" + s.replace("\"", "\\\"") + "\"" else s

    /** launch.json files may carry comments and trailing commas, which JSONObject does not accept. */
    private fun stripJsonComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                sb.append(ch)
                if (ch == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                if (ch == '"') inString = false
                i++; continue
            }
            if (ch == '"') { inString = true; sb.append(ch); i++; continue }
            if (ch == '/' && i + 1 < text.length && text[i + 1] == '/') { while (i < text.length && text[i] != '\n') i++; continue }
            if (ch == '/' && i + 1 < text.length && text[i + 1] == '*') { val end = text.indexOf("*/", i + 2); i = if (end < 0) text.length else end + 2; continue }
            sb.append(ch); i++
        }
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }

    /** The ${...} variables of launch.json that make sense here. */
    private fun substitute(s: String, file: String): String {
        val folder = Api.folder.trimEnd('/')
        val abs = if (file.isEmpty()) "" else Breakpoints.absolute(file)
        var out = s.replace("\${workspaceFolder}", folder).replace("\${workspaceRoot}", folder)
            .replace("\${workspaceFolderBasename}", folder.substringAfterLast('/'))
            .replace("\${file}", abs).replace("\${relativeFile}", Breakpoints.normalize(abs))
            .replace("\${fileBasename}", abs.substringAfterLast('/')).replace("\${fileDirname}", abs.substringBeforeLast('/', ""))
            .replace("\${fileBasenameNoExtension}", abs.substringAfterLast('/').substringBeforeLast('.'))
        out = Regex("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}").replace(out) { m -> System.getenv(m.groupValues[1]) ?: "" }
        return out
    }

    /** Splits an argument line the way the page did: spaces separate, quotes group. */
    private fun splitArgs(s: String): List<String> {
        val out = ArrayList<String>()
        for (m in Regex("(?:[^\\s\"']+|\"[^\"]*\"|'[^']*')+").findAll(s.trim())) {
            var a = m.value
            if (a.length >= 2 && ((a.startsWith("\"") && a.endsWith("\"")) || (a.startsWith("'") && a.endsWith("'")))) a = a.substring(1, a.length - 1)
            out.add(a)
        }
        return out
    }

    // ---------------------------------------------------------------- the session
    private fun console(): DebugConsolePanel? = DebugConsolePanel.current
    private fun out(text: String, kind: String = "", raw: Boolean = false) { console()?.out(text, kind, raw) }

    /** Starts the selected program; with [noDebug] it runs without stopping at breakpoints. */
    fun start(noDebug: Boolean) {
        val running = session
        if (running != null) {
            if (running.state == DebugSession.State.STOPPED) cont() else Ui.toast(ctx, "A debug session is already running.")
            return
        }
        val active = host.activeFilePath()
        val cfg = selected
        val programText = substitute(program.text.toString().trim(), active)
        val prog = if (programText.isNotEmpty()) programText else active
        if (prog.isEmpty()) { Ui.toast(ctx, "Open the file you want to run, or type its path in Run and debug."); return }
        prefs.edit().putString("program:" + Api.folder, program.text.toString()).putString("args:" + Api.folder, args.text.toString()).apply()
        val lang = cfg?.lang ?: DebugSession.langOf(prog)
        if (lang == null) { Ui.toast(ctx, "Forge can debug Python, C, C++, Rust, Kotlin, Java, JavaScript and TypeScript files."); return }
        val argv = splitArgs(substitute(args.text.toString(), active))
        if (cfg != null && cfg.request == "attach") {
            if ((lang == "kotlin" || lang == "java") && cfg.port > 0) { attachJvm(cfg.hostName, cfg.port, cfg.name); return }
            Ui.toast(ctx, "Attach configurations are supported for Kotlin and Java (hostName and port) only."); return
        }
        host.showDebugConsole()
        console()?.clear()
        out((if (noDebug) "Running " else "Debugging ") + prog + (if (argv.isEmpty()) "" else " " + argv.joinToString(" ")), "info")
        val s = DebugSession(lang, noDebug, this)
        session = s
        console()?.attach(s)
        render()
        val isSource = DebugSession.langOf(prog) != null
        if (lang != "python" && lang != "javascript" && isSource) {
            out("Building with debug information...", "info")
            Api.async("debug-prepare", { Api.post("/api/debug/prepare", JSONObject().put("path", prog), 920000) }) { r, e ->
                if (session !== s) return@async
                if (r == null) { fail(s, "Could not start: " + (e?.message ?: "the program could not be prepared.")); return@async }
                val build = r.optString("build", "")
                if (build.isNotEmpty()) out("$ $build", "info")
                val output = r.optString("output", "").trim()
                if (output.isNotEmpty()) out(output, if (r.optBoolean("ok", false)) "" else "err")
                if (!r.optBoolean("ok", false)) { fail(s, "Could not start: " + r.optString("message", "The program could not be prepared.")); return@async }
                connectAndLaunch(s, lang, prog, argv, r, cfg)
            }
        } else {
            connectAndLaunch(s, lang, prog, argv, JSONObject().put("program", Breakpoints.absolute(prog)), cfg)
        }
    }

    private fun connectAndLaunch(s: DebugSession, lang: String, prog: String, argv: List<String>, prep: JSONObject, cfg: LaunchConfig?) {
        val folder = Api.folder.trimEnd('/')
        val cwd = if (cfg != null && cfg.cwd.isNotEmpty()) substitute(cfg.cwd, prog) else prep.optString("cwd", folder).ifEmpty { folder }
        val jvm = lang == "kotlin" || lang == "java"
        val js = lang == "javascript" || lang == "typescript"
        val launchArgs = JSONObject().put("name", "Forge").put("args", JSONArray(argv)).put("cwd", cwd)
        when {
            lang == "python" -> launchArgs.put("type", "python").put("request", "launch").put("program", Breakpoints.absolute(prog))
                .put("console", "internalConsole").put("redirectOutput", true).put("justMyCode", true).put("showReturnValue", true)
                .put("noDebug", s.noDebug).put("stopOnEntry", cfg?.stopOnEntry ?: false)
            jvm -> launchArgs.put("type", "intellij_jvm").put("request", "launch").put("tool", "java").put("mainClass", prep.optString("main_class"))
                .put("file", prep.optString("file")).put("console", "internalConsole").put("noDebug", s.noDebug)
                .put("classPaths", prep.optJSONArray("class_paths") ?: JSONArray()).put("javaExec", prep.optString("java_exec"))
                .put("vmArgs", JSONArray(listOf("-Xmx256m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC")))
            js -> {
                launchArgs.put("type", "pwa-node").put("request", "launch").put("program", prep.optString("program")).put("__workspaceFolder", folder)
                    .put("console", "internalConsole").put("outputCapture", "std").put("skipFiles", JSONArray(listOf("<node_internals>/**")))
                    .put("sourceMaps", true).put("noDebug", s.noDebug)
                val outDir = prep.optString("out_dir", "")
                if (outDir.isNotEmpty()) {
                    launchArgs.put("outFiles", JSONArray(listOf("$outDir/**/*.js")))
                    launchArgs.put("resolveSourceMapLocations", JSONArray(listOf("$outDir/**", "$folder/**", "!**/node_modules/**")))
                }
                if (cfg?.stopOnEntry == true) launchArgs.put("stopOnEntry", true)
            }
            else -> launchArgs.put("program", prep.optString("program")).put("stopAtBeginningOfMainSubprogram", cfg?.stopOnEntry ?: false)
        }
        if (cfg?.env != null) launchArgs.put("env", cfg.env)
        s.connect { e ->
            if (session !== s) return@connect
            if (e != null) { fail(s, "Could not start: " + e.message); return@connect }
            s.initialize(DebugSession.adapterId(lang)) { _, ie ->
                if (session !== s) return@initialize
                if (ie != null) { fail(s, "Could not start: " + ie.message); return@initialize }
                launchOrAttach(s, "launch", launchArgs, if (jvm) 600000L else 60000L)
            }
        }
    }

    /**
     * The launch or attach step. The adapter answers the launch only after configurationDone (debugpy, GDB) or
     * before it (the Kotlin debugger), so like the page this waits for whichever comes first, the initialized event
     * or the launch response, then sends the breakpoints and configurationDone, then waits for the response.
     */
    private fun launchOrAttach(s: DebugSession, command: String, launchArgs: JSONObject, timeoutMs: Long) {
        var configured = false
        var launchResult: Pair<JSONObject?, Exception?>? = null
        var finished = false
        val finish = {
            if (!finished && session === s) {
                finished = true
                val r = launchResult
                if (r != null && r.second != null) fail(s, "Could not " + (if (command == "attach") "attach: " else "start: ") + r.second!!.message)
                else {
                    if (s.state == DebugSession.State.STARTING) s.state = DebugSession.State.RUNNING
                    if (command == "attach") out("Attached. The app continues; it stops at your breakpoints. Expressions in the Debug console may not work while attached to Android (a known limit of the Kotlin debugger).", "info")
                    render()
                }
            }
        }
        val configure = {
            if (!configured && session === s) {
                configured = true
                val files = if (s.noDebug) emptyList() else Breakpoints.files().filter { DebugSession.sameFamily(s.lang, it) }
                sendAll(s, files) {
                    val afterExceptions = {
                        s.configurationDone { _, _ -> if (launchResult != null) finish() }
                    }
                    if (s.lang == "python") s.setExceptionBreakpoints(listOf("uncaught")) { _, _ -> afterExceptions() } else afterExceptions()
                }
            }
        }
        s.request(command, launchArgs, timeoutMs) { r, e ->
            launchResult = Pair(r, e)
            if (e != null) { finish(); return@request }
            if (!configured) configure() else finish()
        }
        s.onceInitialized { if (launchResult == null || launchResult!!.second == null) configure() }
    }

    /** Sends the breakpoints of [files] one after another, then calls [done]. */
    private fun sendAll(s: DebugSession, files: List<String>, done: () -> Unit) {
        if (files.isEmpty() || session !== s) { done(); return }
        s.setBreakpoints(Breakpoints.absolute(files[0]), Breakpoints.enabledLines(files[0])) { _, _ -> sendAll(s, files.drop(1), done) }
    }

    /** Debugs an installed debug build on the target device: the Android view calls this with the app id. */
    fun attachAndroid(appId: String) {
        if (session != null) { Ui.toast(ctx, "A debug session is already running."); return }
        host.showDebugConsole()
        console()?.clear()
        out("Starting $appId on the target device so that it waits for the debugger...", "info")
        val s = DebugSession("kotlin", false, this)
        session = s
        console()?.attach(s)
        render()
        Api.async("debug-android", { Api.post("/api/debug/android", JSONObject().put("app_id", appId), 90000) }) { r, e ->
            if (session !== s) return@async
            if (r == null) { fail(s, "Could not attach: " + (e?.message ?: "")); return@async }
            if (!r.optBoolean("ok", false)) { fail(s, "Could not attach: " + r.optString("message", "")); return@async }
            s.android = r
            out(r.optString("message", ""), "info")
            val attachArgs = JSONObject().put("type", "intellij_jvm").put("request", "attach").put("name", "Forge")
                .put("hostName", "127.0.0.1").put("port", r.optInt("local_port")).put("timeout", 30000)
            s.connect { ce ->
                if (session !== s) return@connect
                if (ce != null) { fail(s, "Could not attach: " + ce.message); return@connect }
                s.initialize("intellij_jvm") { _, ie ->
                    if (session !== s) return@initialize
                    if (ie != null) { fail(s, "Could not attach: " + ie.message); return@initialize }
                    launchOrAttach(s, "attach", attachArgs, 120000L)
                }
            }
        }
    }

    /** An attach configuration from launch.json for a JVM that listens for a debugger (hostName and port). */
    private fun attachJvm(hostName: String, port: Int, name: String) {
        host.showDebugConsole()
        console()?.clear()
        out("Attaching to $hostName:$port ($name)...", "info")
        val s = DebugSession("kotlin", false, this)
        session = s
        console()?.attach(s)
        render()
        val attachArgs = JSONObject().put("type", "intellij_jvm").put("request", "attach").put("name", "Forge")
            .put("hostName", hostName).put("port", port).put("timeout", 30000)
        s.connect { ce ->
            if (session !== s) return@connect
            if (ce != null) { fail(s, "Could not attach: " + ce.message); return@connect }
            s.initialize("intellij_jvm") { _, ie ->
                if (session !== s) return@initialize
                if (ie != null) { fail(s, "Could not attach: " + ie.message); return@initialize }
                launchOrAttach(s, "attach", attachArgs, 120000L)
            }
        }
    }

    private fun fail(s: DebugSession, message: String) {
        out(message, "err")
        Ui.toast(ctx, message)
        end(s)
    }

    private fun end(s: DebugSession) {
        host.showExecution(null, 0)
        if (session !== s) return
        val android = s.android
        if (android != null && android.optInt("local_port", 0) > 0) {
            Api.async("debug-android-release", {
                Api.post("/api/debug/android", JSONObject().put("release", true).put("local_port", android.optInt("local_port"))
                    .put("serial", android.optString("serial")).put("port", android.optInt("port")), 20000)
            }) { _, _ -> }
        }
        session = null
        console()?.attach(null)
        s.close()
        render()
    }

    fun stop() {
        val s = session ?: return
        // The adapter may send its terminated event before it answers, which ends the session first; the note
        // is printed in both orders.
        s.terminate { _, _ -> out("Stopped.", "info"); if (session === s) end(s) }
    }

    fun restart() {
        val s = session ?: return
        val noDebug = s.noDebug
        stop()
        Api.main.postDelayed({ if (session == null) start(noDebug) }, 400)
    }

    private fun step(command: String) {
        val s = session ?: return
        if (s.state != DebugSession.State.STOPPED) return
        s.state = DebugSession.State.RUNNING
        s.frames = emptyList(); s.frame = null; s.frameId = -1; s.scopes = emptyList()
        render()
        val cb: (JSONObject?, Exception?) -> Unit = { _, e -> if (e != null) out(e.message ?: command, "err") }
        when (command) { "continue" -> s.continueRun(cb); "next" -> s.next(cb); "stepIn" -> s.stepIn(cb); "stepOut" -> s.stepOut(cb) }
    }

    fun cont() = step("continue")

    fun pause() {
        val s = session ?: return
        if (s.state == DebugSession.State.RUNNING) s.pause { _, e -> if (e != null) out(e.message ?: "pause failed", "err") }
    }

    /** The debugger's function keys, for the window's key handling: F5, F6, F10, F11 and their Shift variants. */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val s = session
        when (event.keyCode) {
            KeyEvent.KEYCODE_F5 -> {
                if (event.isCtrlPressed && event.isShiftPressed) restart()
                else if (event.isCtrlPressed) start(true)
                else if (event.isShiftPressed) stop()
                else if (s != null && s.state == DebugSession.State.STOPPED) cont() else start(false)
                return true
            }
            KeyEvent.KEYCODE_F6 -> { pause(); return true }
            KeyEvent.KEYCODE_F10 -> { step("next"); return true }
            KeyEvent.KEYCODE_F11 -> { if (event.isShiftPressed) step("stepOut") else step("stepIn"); return true }
        }
        return false
    }

    // ---------------------------------------------------------------- session events (main thread)
    override fun onOutput(text: String, category: String) {
        out(text, when (category) { "stderr" -> "err"; "console" -> "info"; else -> "" }, raw = true)
    }

    override fun onStopped(body: JSONObject) {
        val s = session ?: return
        render()
        s.threads { list, _ -> if (session === s && list != null) { s.threads = list; render() } }
        loadStack(s, s.threadId)
        if (body.optString("reason") == "exception") {
            s.exceptionInfo { r, _ -> if (r != null) out("Exception: " + r.optString("exceptionId", "") + " " + r.optString("description", ""), "err") }
        }
    }

    private fun loadStack(s: DebugSession, thread: Int) {
        s.stackTrace(thread) { frames, e ->
            if (session !== s || s.state != DebugSession.State.STOPPED) return@stackTrace
            if (frames == null) { out(e?.message ?: "stackTrace failed", "err"); return@stackTrace }
            s.threadId = thread
            s.frames = frames
            val first = frames.firstOrNull { it.optJSONObject("source")?.optString("path", "")?.isNotEmpty() == true } ?: frames.firstOrNull()
            if (first != null) selectFrame(first) else render()
        }
    }

    private fun selectFrame(frame: JSONObject) {
        val s = session ?: return
        s.frameId = frame.optInt("id", -1)
        s.frame = frame
        val src = frame.optJSONObject("source")
        val path = src?.optString("path", "") ?: ""
        if (path.isNotEmpty()) {
            val p = DebugSession.sourcePath(path)
            src!!.put("path", p)
            host.openFileAt(Breakpoints.normalize(p), frame.optInt("line", 0))
            host.showExecution(Breakpoints.normalize(p), frame.optInt("line", 0))
        }
        s.scopes(s.frameId) { sc, _ ->
            if (session !== s || s.frameId != frame.optInt("id", -1)) return@scopes
            s.scopes = sc ?: emptyList()
            render()
        }
        render()
    }

    override fun onContinued() { host.showExecution(null, 0); render() }

    override fun onExited(code: Int) { out("The program exited with code $code.", if (code != 0) "err" else "info") }

    override fun onEnded() { val s = session ?: return; end(s) }

    private fun onBreakpointsChanged(path: String) {
        val s = session
        if (s != null && !s.noDebug && s.state != DebugSession.State.STARTING && DebugSession.sameFamily(s.lang, path)) {
            s.setBreakpoints(Breakpoints.absolute(path), Breakpoints.enabledLines(path)) { _, e -> if (e != null) out("Breakpoints: " + e.message, "err") }
        }
        render()
    }

    // ---------------------------------------------------------------- rendering
    private fun render() {
        val s = session
        val stopped = s != null && s.state == DebugSession.State.STOPPED
        val running = s != null && s.state == DebugSession.State.RUNNING
        startButton.text = if (s == null) "Start debugging (F5)" else if (stopped) "Continue (F5)" else "Running..."
        enable(startButton, s == null || stopped)
        enable(runButton, s == null)
        tools.visibility = if (s == null) View.GONE else View.VISIBLE
        enable(continueButton, stopped); enable(pauseButton, running)
        enable(overButton, stopped); enable(intoButton, stopped); enable(outButton, stopped)
        enable(restartButton, s != null); enable(stopButton, s != null)
        status.text = when {
            s == null -> "No debug session."
            stopped -> "Paused" + (if (s.reason.isNotEmpty()) " (" + s.reason + ")" else "")
            s.state == DebugSession.State.STARTING -> "Starting..."
            else -> "Running"
        }
        status.setTextColor(if (s == null) Ui.fg3 else Ui.accent2)

        val mine = ++renderSeq
        dynamic.removeAllViews()
        if (s != null && stopped) {
            dynamic.addView(section("Threads and call stack", buildStack(s)))
            dynamic.addView(section("Variables", buildVariables(s, mine)))
        }
        dynamic.addView(section("Watch", buildWatch(s, mine)))
        dynamic.addView(section("Breakpoints", buildBreakpoints()))
    }

    private fun section(title: String, content: View): LinearLayout {
        val head = Ui.text(ctx, title.uppercase(), 11f, Ui.fg3)
        head.letterSpacing = 0.06f
        val col = Ui.column(ctx, head, content)
        (head.layoutParams as LinearLayout.LayoutParams).bottomMargin = Ui.dp(ctx, 4)
        (col.layoutParams as? LinearLayout.LayoutParams ?: Ui.lp(Ui.match, Ui.wrap).also { col.layoutParams = it }).topMargin = Ui.dp(ctx, 10)
        return col
    }

    private fun mono(s: String, color: Int = Ui.fg): TextView {
        val t = Ui.text(ctx, s, 11.5f, color, mono = true)
        t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    private fun clickable(row: LinearLayout, active: Boolean = false) {
        row.isClickable = true; row.isFocusable = true
        row.background = if (active) Ui.rounded(Ui.accentSoft, 4) else Ui.states(null, Ui.rounded(Ui.bg3, 4), Ui.rounded(Ui.bg4, 4))
        row.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
    }

    private fun buildStack(s: DebugSession): View {
        val col = Ui.column(ctx)
        val threads = if (s.threads.isEmpty()) listOf(JSONObject().put("id", s.threadId).put("name", "Thread " + s.threadId)) else s.threads
        for (t in threads) {
            val id = t.optInt("id", 0)
            val current = id == s.threadId
            val head = Ui.row(ctx, Ui.iconView(ctx, if (current) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, Ui.fg3, 12),
                mono(t.optString("name", "Thread $id"), if (current) Ui.fg else Ui.fg2).also { it.setPadding(Ui.dp(ctx, 4), 0, 0, 0) })
            clickable(head)
            head.setOnClickListener { if (session === s && s.state == DebugSession.State.STOPPED && !current) loadStack(s, id) }
            col.addView(head, Ui.lp(Ui.match, Ui.wrap))
            if (!current) continue
            if (s.frames.isEmpty()) { col.addView(Ui.text(ctx, "Loading the call stack...", 11f, Ui.fg3).also { it.setPadding(Ui.dp(ctx, 16), 0, 0, 0) }); continue }
            for (f in s.frames) {
                val src = f.optJSONObject("source")
                val where = (src?.optString("name", "") ?: "").ifEmpty { (src?.optString("path", "") ?: "").substringAfterLast('/') } + ":" + f.optInt("line", 0)
                val name = mono(f.optString("name", "?"))
                name.layoutParams = Ui.lp(0, Ui.wrap, 1f)
                val row = Ui.row(ctx, name, mono(where, Ui.fg3).also { it.setPadding(Ui.dp(ctx, 8), 0, 0, 0) })
                clickable(row, f.optInt("id", -1) == s.frameId)
                row.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
                row.tooltipText = src?.optString("path", "") ?: ""
                row.setOnClickListener { if (session === s && s.state == DebugSession.State.STOPPED) selectFrame(f) }
                col.addView(row, Ui.lp(Ui.match, Ui.wrap))
            }
        }
        return col
    }

    private fun buildVariables(s: DebugSession, mine: Int): View {
        val col = Ui.column(ctx)
        if (s.scopes.isEmpty()) col.addView(Ui.text(ctx, if (s.frame == null) "Loading..." else "No scopes.", 11f, Ui.fg3))
        for (sc in s.scopes) {
            val name = sc.optString("name", "")
            val large = (sc.optBoolean("expensive", false) || sc.optString("presentationHint", "") == "registers" || name.equals("registers", true)) && name != "Locals"
            if (large) {
                // Registers and other large scopes start collapsed, as on the page.
                col.addView(varRow(s, JSONObject().put("name", name).put("value", "").put("variablesReference", sc.optInt("variablesReference", 0)), 0), Ui.lp(Ui.match, Ui.wrap))
                continue
            }
            col.addView(Ui.text(ctx, name, 11f, Ui.fg3).also { it.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 2)) })
            val holder = Ui.column(ctx)
            holder.addView(Ui.text(ctx, "Loading...", 11f, Ui.fg3))
            col.addView(holder, Ui.lp(Ui.match, Ui.wrap))
            s.variables(sc.optInt("variablesReference", 0)) { vars, e ->
                if (mine != renderSeq) return@variables
                holder.removeAllViews()
                if (vars == null) { holder.addView(Ui.text(ctx, e?.message ?: "failed", 11f, Ui.fg3)); return@variables }
                for (v in vars.take(300)) holder.addView(varRow(s, v, 0), Ui.lp(Ui.match, Ui.wrap))
                if (vars.isEmpty()) holder.addView(Ui.text(ctx, "No variables.", 11f, Ui.fg3))
            }
        }
        return col
    }

    /** One variable: name = value, with a twisty that loads its children when it has any. */
    private fun varRow(s: DebugSession, v: JSONObject, depth: Int): View {
        val ref = v.optInt("variablesReference", 0)
        val value = v.optString("value", "")
        val type = v.optString("type", "")
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(ctx, 14 * depth), Ui.dp(ctx, 1), 0, Ui.dp(ctx, 1))
        val twisty = Ui.iconView(ctx, R.drawable.ic_chevron_right, if (ref > 0) Ui.fg3 else 0, 12)
        if (ref <= 0) twisty.setImageDrawable(null)
        head.addView(twisty)
        head.addView(mono(v.optString("name", ""), Ui.blue).also { it.setPadding(Ui.dp(ctx, 3), 0, 0, 0) })
        head.addView(mono(" = ", Ui.fg3))
        val valueView = mono(value, Ui.fg)
        valueView.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        head.addView(valueView)
        head.tooltipText = (if (type.isNotEmpty()) "$type: " else "") + value
        val wrapper = Ui.column(ctx, head)
        if (ref > 0) {
            head.isClickable = true; head.isFocusable = true
            head.background = Ui.states(null, Ui.rounded(Ui.bg3, 4), Ui.rounded(Ui.bg4, 4))
            var kids: LinearLayout? = null
            head.setOnClickListener {
                val open = kids
                if (open != null) { wrapper.removeView(open); kids = null; twisty.setImageDrawable(Ui.icon(ctx, R.drawable.ic_chevron_right, Ui.fg3)); return@setOnClickListener }
                val k = Ui.column(ctx)
                kids = k
                twisty.setImageDrawable(Ui.icon(ctx, R.drawable.ic_chevron_down, Ui.fg3))
                k.addView(Ui.text(ctx, "Loading...", 11f, Ui.fg3).also { it.setPadding(Ui.dp(ctx, 14 * (depth + 1)), 0, 0, 0) })
                wrapper.addView(k, Ui.lp(Ui.match, Ui.wrap))
                s.variables(ref) { vars, e ->
                    if (kids !== k) return@variables
                    k.removeAllViews()
                    if (vars == null) { k.addView(Ui.text(ctx, e?.message ?: "failed", 11f, Ui.fg3).also { it.setPadding(Ui.dp(ctx, 14 * (depth + 1)), 0, 0, 0) }); return@variables }
                    for (c in vars.take(300)) k.addView(varRow(s, c, depth + 1), Ui.lp(Ui.match, Ui.wrap))
                    if (vars.isEmpty()) k.addView(Ui.text(ctx, "No children.", 11f, Ui.fg3).also { it.setPadding(Ui.dp(ctx, 14 * (depth + 1)), 0, 0, 0) })
                }
            }
        }
        return wrapper
    }

    private fun buildWatch(s: DebugSession?, mine: Int): View {
        val col = Ui.column(ctx)
        val stopped = s != null && s.state == DebugSession.State.STOPPED
        for ((i, w) in watches.withIndex()) {
            val name = mono(w, Ui.blue)
            val value = mono(if (stopped) "..." else "not paused", if (stopped) Ui.fg else Ui.fg3)
            value.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            val remove = Ui.miniButton(ctx, R.drawable.ic_close, "Remove") { watches.removeAt(i); persistWatches(); render() }
            val row = Ui.row(ctx, name, mono(" = ", Ui.fg3), value, remove)
            row.setPadding(Ui.dp(ctx, 4), 0, 0, 0)
            col.addView(row, Ui.lp(Ui.match, Ui.wrap))
            if (s != null && stopped) s.evaluate(w, "watch") { r, e ->
                if (mine != renderSeq) return@evaluate
                if (r != null) value.text = r.optString("result", "") else { value.text = e?.message ?: "failed"; value.setTextColor(Ui.red) }
            }
        }
        (watchInput.parent as? LinearLayout)?.removeView(watchInput)
        col.addView(watchInput, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = Ui.dp(ctx, 4) })
        return col
    }

    private fun persistWatches() { prefs.edit().putString("watches", JSONArray(watches).toString()).apply() }

    private fun buildBreakpoints(): View {
        val col = Ui.column(ctx)
        var count = 0
        for (file in Breakpoints.files()) {
            for (line in Breakpoints.lines(file)) {
                count++
                val box = CheckBox(ctx)
                box.isChecked = Breakpoints.isEnabled(file, line)
                box.buttonTintList = ColorStateList.valueOf(if (box.isChecked) Ui.red else Ui.fg3)
                box.setPadding(0, 0, 0, 0)
                box.setOnCheckedChangeListener { _, on -> Breakpoints.setEnabled(file, line, on) }
                val label = mono(file.substringAfterLast('/') + ":" + line, if (box.isChecked) Ui.fg else Ui.fg3)
                label.layoutParams = Ui.lp(0, Ui.wrap, 1f)
                label.tooltipText = file
                val remove = Ui.miniButton(ctx, R.drawable.ic_close, "Remove breakpoint") { Breakpoints.remove(file, line) }
                val row = Ui.row(ctx, box, label, remove)
                row.isClickable = true
                row.background = Ui.states(null, Ui.rounded(Ui.bg3, 4), Ui.rounded(Ui.bg4, 4))
                row.setOnClickListener { host.openFileAt(file, line) }
                col.addView(row, Ui.lp(Ui.match, Ui.wrap))
            }
        }
        if (count == 0) {
            val hint = Ui.text(ctx, "Click left of a line number in the editor to add a breakpoint.", 11.5f, Ui.fg3)
            hint.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2))
            col.addView(hint)
        } else {
            // clear() notifies the session listener for every file, which clears them in the adapter and redraws.
            col.addView(Ui.button(ctx, "Remove all breakpoints") { Breakpoints.clear() }, Ui.lp(Ui.wrap, Ui.wrap).also { it.topMargin = Ui.dp(ctx, 6) })
        }
        return col
    }
}
