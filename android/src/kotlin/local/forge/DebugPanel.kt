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
 *
 * Every start first saves the open editors (as Cursor does), then runs the configuration's preLaunchTask, if it has
 * one, in a terminal tab and goes on only when that task succeeded. Besides program, args, cwd, env and stopOnEntry,
 * a configuration may use module and python (Python), runtimeExecutable and runtimeArgs (Node.js), mainClass (Java
 * and Kotlin), environment and stopAtEntry (C and C++), envFile, preLaunchTask and postDebugTask; tasks come from
 * .vscode/tasks.json (shell, process, npm and typescript tasks and their dependsOn), or are the npm scripts and tsc
 * builds that VS Code finds by itself ("npm: build", "tsc: build - tsconfig.json"). A configuration Forge cannot run
 * is listed with the reason. Restart (Ctrl Shift F5) ends the session and, once it has ended, begins the same one
 * again: the same configuration, file, arguments, attach target or Android app.
 */
class DebugPanel(private val ctx: Context, private val host: MainActivity) : DebugSession.Listener {
    val view: LinearLayout = LinearLayout(ctx)

    /** Saves the open editors before a run, as Cursor does; the main window wires it to the editor's Save all. */
    var saveBeforeRun: ((done: () -> Unit) -> Unit)? = null
    /** Runs a command in a new terminal tab and reports its exit code (-1 when the tab was killed first): TerminalTabs.runTask. */
    var runTask: ((command: String, label: String, onDone: (Int) -> Unit) -> Unit)? = null

    /** One step of a task: its shell line, built when it runs (so ${file} is the file open then). */
    private class TaskStep(val label: String, val build: (file: String) -> String)
    /** A task a configuration names, with the steps of its dependencies first. */
    private class Task(val label: String, val steps: List<TaskStep>)
    /** What tasks can be named: .vscode/tasks.json's by label, and package.json's npm scripts. */
    private class TaskDefs(val defs: Map<String, JSONObject>, val npmScripts: Set<String>, val error: String)

    /** One entry of the picker: a configuration read from a launch file. [unsupported] says why Forge cannot run it. */
    private class LaunchConfig(val name: String, val type: String, val lang: String?, val request: String, val program: String, val args: String,
                               val cwd: String, val stopOnEntry: Boolean, val env: JSONObject?, val hostName: String, val port: Int, val source: String,
                               val module: String, val python: String, val runtimeExecutable: String, val runtimeArgs: List<String>,
                               val mainClass: String, val envFile: String, val preLaunch: Task?, val postDebug: Task?, val unsupported: String)

    // How a session began; DebugSession.origin keeps it, so Restart begins the next session the same way.
    private class Launch(val noDebug: Boolean, val lang: String, val prog: String, val argv: List<String>, val cfg: LaunchConfig?)
    private class AttachJvm(val hostName: String, val port: Int, val name: String, val cfg: LaunchConfig?)
    private class AndroidApp(val appId: String)

    private val configChip: LinearLayout
    private val configNote = Ui.text(ctx, "", 11f, Ui.fg3)
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
    /** The session that Restart ended; when it has ended, the same session begins again. */
    private var restartAfter: DebugSession? = null
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
        body.addView(configChip, Ui.lp(Ui.wrap, Ui.wrap).also { it.bottomMargin = Ui.dp(ctx, 4) })
        configNote.visibility = View.GONE
        body.addView(configNote, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = Ui.dp(ctx, 6) })
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
            for (i in 0 until a.length()) watches.add(a.optText(i))
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
        for ((i, c) in configs.withIndex()) menu.menu.add(0, i + 1, i + 1, c.name + "  (" + c.source + (if (c.unsupported.isNotEmpty()) ", not supported in Forge" else "") + ")")
        menu.menu.add(0, configs.size + 1, configs.size + 1, "Reload launch configurations")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { selected = null; program.setText(""); args.setText(""); Ui.setChip(configChip, "Open file", Ui.fg3); showConfigNote() }
                configs.size + 1 -> loadConfigs(true)
                else -> {
                    val c = configs[item.itemId - 1]
                    selected = c
                    program.setText(c.program); args.setText(c.args)
                    Ui.setChip(configChip, c.name, if (c.unsupported.isEmpty()) Ui.accent else Ui.red)
                    showConfigNote()
                }
            }
            true
        }
        menu.show()
    }

    /** Under the picker: why the chosen configuration cannot run, or what it does besides running the program. */
    private fun showConfigNote() {
        val c = selected
        val text = when {
            c == null -> ""
            c.unsupported.isNotEmpty() -> "Forge cannot run this configuration. " + c.unsupported
            else -> listOfNotNull(
                c.preLaunch?.let { "Runs the task \"${it.label}\" in a terminal tab first and starts only when it succeeds." },
                if (c.envFile.isNotEmpty()) "Reads the environment file ${c.envFile}." else null,
                c.postDebug?.let { "Runs the task \"${it.label}\" when the session ends." }).joinToString(" ")
        }
        configNote.text = text
        configNote.setTextColor(if (c != null && c.unsupported.isNotEmpty()) Ui.red else Ui.fg3)
        configNote.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Reads .vscode/launch.json and .forge/launch.json, with the tasks they name; a missing file is simply skipped. */
    private fun loadConfigs(announce: Boolean) {
        Api.async("launch-configs", {
            fun read(path: String): String? = try { Api.get("/api/file", mapOf("path" to path), 10000).optText("content") } catch (e: Exception) { null }
            val problems = ArrayList<String>()
            val tasks = readTasks(read(".vscode/tasks.json"), read("package.json"))
            if (tasks.error.isNotEmpty()) problems.add(tasks.error)
            val found = ArrayList<LaunchConfig>()
            for (file in listOf(".forge/launch.json", ".vscode/launch.json")) {
                val text = read(file) ?: continue
                try { parseConfigs(text, file, tasks, found) } catch (e: Exception) { problems.add("Could not read $file: " + (e.message ?: "it is not valid JSON")) }
            }
            Pair(found, problems)
        }) { r, e ->
            if (r == null) { if (announce) Ui.toast(ctx, "Could not read the launch files: " + (e?.message ?: "")); return@async }
            configs.clear(); configs.addAll(r.first)
            val keep = selected?.let { s -> configs.firstOrNull { it.name == s.name && it.source == s.source } }
            selected = keep
            if (keep == null) Ui.setChip(configChip, "Open file", Ui.fg3)
            showConfigNote()
            if (r.second.isNotEmpty()) Ui.toast(ctx, r.second.joinToString(" "))
            else if (announce) Ui.toast(ctx, if (configs.isEmpty()) "No launch configurations in .vscode/launch.json or .forge/launch.json." else configs.size.toString() + " launch configurations.")
        }
    }

    private fun parseConfigs(text: String, source: String, tasks: TaskDefs, into: MutableList<LaunchConfig>) {
        val root = JSONObject(stripJsonComments(text))
        val list = root.optJSONArray("configurations") ?: return
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val type = c.optText("type", "").lowercase()
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
            val request = c.optText("request", "launch")
            val a = c.opt("args")
            val argsText = when {
                a is JSONArray -> (0 until a.length()).joinToString(" ") { quote(a.optText(it)) }
                a is String -> a
                else -> ""
            }
            // env (an object), and C and C++'s environment ([{name, value}]).
            val env = JSONObject()
            c.optJSONObject("env")?.let { e -> for (k in e.keys()) env.put(k, e.opt(k)) }
            c.optJSONArray("environment")?.let { arr -> for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { x -> if (x.optText("name").isNotEmpty()) env.put(x.optText("name"), x.optText("value")) } }
            val runtimeArgs = c.optJSONArray("runtimeArgs")?.let { arr -> (0 until arr.length()).map { arr.optText(it) } } ?: emptyList()
            val module = c.optText("module")
            val runtime = c.optText("runtimeExecutable")
            val mainClass = c.optText("mainClass")
            val preName = c.optText("preLaunchTask")
            val postName = c.optText("postDebugTask")
            val (pre, preWhy) = if (preName.isNotEmpty()) resolveTask(preName, tasks, emptySet()) else Pair(null, "")
            val (post, postWhy) = if (postName.isNotEmpty()) resolveTask(postName, tasks, emptySet()) else Pair(null, "")
            val jvm = lang == "kotlin" || lang == "java"
            val why = when {
                lang == null -> "Forge has no debugger for configurations of type \"$type\"."
                request == "attach" && !(jvm && c.optInt("port", 0) > 0) -> "Forge attaches only to a Java or Kotlin program that waits for a debugger on a port (hostName and port)."
                request != "launch" && request != "attach" -> "Its request is \"$request\"; Forge knows launch and attach."
                module.isNotEmpty() && lang != "python" -> "module is a Python setting, and this is a $type configuration."
                runtime.isNotEmpty() && lang != "javascript" && lang != "typescript" -> "runtimeExecutable is a Node.js setting, and this is a $type configuration."
                preWhy.isNotEmpty() -> "Its preLaunchTask cannot run: $preWhy"
                postWhy.isNotEmpty() -> "Its postDebugTask cannot run: $postWhy"
                else -> ""
            }
            into.add(LaunchConfig(c.optText("name", "configuration " + (i + 1)), type, lang, request,
                c.optText("program", c.optText("file", "")), argsText, c.optText("cwd", ""), c.optBoolean("stopOnEntry", c.optBoolean("stopAtEntry", false)),
                if (env.length() > 0) env else null, c.optText("hostName", c.optText("host", "127.0.0.1")), c.optInt("port", 0), source,
                module, c.optText("python", c.optText("pythonPath", "")), runtime, runtimeArgs, mainClass, c.optText("envFile"), pre, post, why))
        }
    }

    /** The tasks a preLaunchTask or postDebugTask can name: those of .vscode/tasks.json by label, and the npm scripts. */
    private fun readTasks(tasksJson: String?, packageJson: String?): TaskDefs {
        val defs = LinkedHashMap<String, JSONObject>()
        var error = ""
        if (tasksJson != null) try {
            val list = JSONObject(stripJsonComments(tasksJson)).optJSONArray("tasks")
            if (list != null) for (i in 0 until list.length()) {
                val t = list.optJSONObject(i) ?: continue
                // A task without a label is named the way VS Code names it ("npm: build", "tsc: build - tsconfig.json").
                val label = t.optText("label").ifEmpty { t.optText("taskName") }.ifEmpty {
                    when (t.optText("type")) {
                        "npm" -> if (t.optText("script").isNotEmpty()) "npm: " + t.optText("script") else ""
                        "typescript" -> "tsc: " + (if (t.optText("option") == "watch") "watch" else "build") + " - " + t.optText("tsconfig", "tsconfig.json")
                        else -> ""
                    }
                }
                if (label.isNotEmpty()) defs[label] = t
            }
        } catch (e: Exception) { error = "Could not read .vscode/tasks.json: " + (e.message ?: "it is not valid JSON") }
        val npm = HashSet<String>()
        if (packageJson != null) try { JSONObject(packageJson).optJSONObject("scripts")?.keys()?.forEach { npm.add(it) } } catch (e: Exception) { }
        return TaskDefs(defs, npm, error)
    }

    /** A task's steps (its dependencies first), or why Forge cannot run it. */
    private fun resolveTask(name: String, defs: TaskDefs, seen: Set<String>): Pair<Task?, String> {
        if (name in seen) return Pair(null, "the task \"$name\" depends on itself.")
        val t = defs.defs[name]
        if (t == null) {
            val script = name.removePrefix("npm: ")
            if (name.startsWith("npm: ") && script in defs.npmScripts) return Pair(Task(name, listOf(TaskStep(name) { "npm run " + shQuote(script) })), "")
            Regex("^tsc: build - (.+)$").find(name)?.let { m -> val cfg = m.groupValues[1]; return Pair(Task(name, listOf(TaskStep(name) { tsc(cfg) })), "") }
            if (name.startsWith("tsc: watch - ")) return Pair(null, "the task \"$name\" watches for changes and never ends, so Forge cannot wait for it.")
            return Pair(null, "the task \"$name\" is not in .vscode/tasks.json, and it is not an npm script of package.json.")
        }
        if (t.optBoolean("isBackground")) return Pair(null, "the task \"$name\" runs in the background (VS Code waits for a pattern in its output), and Forge cannot wait for that.")
        val steps = ArrayList<TaskStep>()
        val dep = t.opt("dependsOn")
        val deps: List<String> = if (dep is String) listOf(dep)
            else if (dep is JSONArray) (0 until dep.length()).map { dep.optText(it) }.filter { it.isNotEmpty() }
            else emptyList()
        for (d in deps) {
            val (sub, why) = resolveTask(d, defs, seen + name)
            if (sub == null) return Pair(null, why)
            steps.addAll(sub.steps)
        }
        val type = t.optText("type", "shell")
        val command = t.optText("command")
        val argList = t.optJSONArray("args")?.let { arr -> (0 until arr.length()).map { arr.opt(it) } } ?: emptyList()
        val opts = t.optJSONObject("options")
        val cwd = opts?.optText("cwd") ?: ""
        val env = opts?.optJSONObject("env")
        val body: ((String) -> String)? = when {
            type == "npm" -> ({ file: String -> (if (t.optText("path").isNotEmpty()) "cd -- " + shQuote(substitute(t.optText("path"), file)) + " || exit 1; " else "") + "npm run " + shQuote(t.optText("script")) })
            type == "typescript" && t.optText("option") == "watch" -> return Pair(null, "the task \"$name\" watches for changes and never ends, so Forge cannot wait for it.")
            type == "typescript" -> ({ _: String -> tsc(t.optText("tsconfig", "tsconfig.json")) })
            command.isNotEmpty() && type == "shell" -> ({ file: String -> substitute(command, file) + argList.joinToString("") { " " + taskArg(it, file, false) } })
            command.isNotEmpty() && type == "process" -> ({ file: String -> shQuote(substitute(command, file)) + argList.joinToString("") { " " + taskArg(it, file, true) } })
            command.isEmpty() && deps.isNotEmpty() -> null   // a task that only runs its dependencies
            else -> return Pair(null, "the task \"$name\" has the type \"$type\", and Forge runs shell, process, npm and typescript tasks.")
        }
        if (body != null) steps.add(TaskStep(name) { file ->
            (if (cwd.isNotEmpty()) "cd -- " + shQuote(substitute(cwd, file)) + " || exit 1; " else "") + exports(env, file) + body(file)
        })
        return Pair(Task(name, steps), "")
    }

    /** One argument of a task: a process task's are quoted one by one; a shell task's are quoted when they hold spaces. */
    private fun taskArg(a: Any?, file: String, always: Boolean): String {
        val v = substitute(if (a is JSONObject) a.optText("value") else a?.toString() ?: "", file)
        return if (always || a is JSONObject || v.isEmpty() || v.any { it.isWhitespace() }) shQuote(v) else v
    }

    private fun exports(env: JSONObject?, file: String): String {
        if (env == null) return ""
        val sb = StringBuilder()
        for (k in env.keys()) if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(k)) sb.append("export ").append(k).append('=').append(shQuote(substitute(env.optText(k), file))).append("; ")
        return sb.toString()
    }

    /** A TypeScript build the way VS Code's tsc task runs it: the project's own tsc, else one on the PATH, else Forge's. */
    private fun tsc(config: String): String =
        "T=node_modules/.bin/tsc; [ -x \"\$T\" ] || T=\$(command -v tsc || echo \"\$HOME/.local/lib/forge-lsp/node_modules/.bin/tsc\"); \"\$T\" -p " + shQuote(config)

    /** A word for bash, in single quotes. */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

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

    /** A .env file's variables: KEY=VALUE lines, with "export " and quotes allowed, comments and blank lines skipped. */
    private fun parseDotEnv(text: String): JSONObject {
        val out = JSONObject()
        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("export ")) line = line.removePrefix("export ").trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length - 1).replace("\\n", "\n").replace("\\\"", "\"")
            else if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) value = value.substring(1, value.length - 1)
            else { val hash = value.indexOf(" #"); if (hash >= 0) value = value.substring(0, hash).trim() }
            if (Regex("^[A-Za-z_][A-Za-z0-9_.]*$").matches(key)) out.put(key, value)
        }
        return out
    }

    // ---------------------------------------------------------------- the session
    private fun console(): DebugConsolePanel? = DebugConsolePanel.current
    private fun out(text: String, kind: String = "", raw: Boolean = false) { console()?.out(text, kind, raw) }

    /** Starts the selected configuration, or the program in the Program field, or the open file; [noDebug] runs it without stopping at breakpoints. */
    fun start(noDebug: Boolean) {
        val running = session
        if (running != null) {
            if (running.state == DebugSession.State.STOPPED) cont() else Ui.toast(ctx, "A debug session is already running.")
            return
        }
        val cfg = selected
        if (cfg != null && cfg.unsupported.isNotEmpty()) { Ui.toast(ctx, cfg.name + ": " + cfg.unsupported); return }
        if (cfg != null && cfg.request == "attach") { begin(AttachJvm(cfg.hostName, cfg.port, cfg.name, cfg), "kotlin", false); return }
        val active = host.activeFilePath()
        val programText = substitute(program.text.toString().trim(), active)
        // A module, a runtime (npm, tsx and the like) or a main class names what runs; otherwise it is a file.
        val needsFile = cfg == null || (cfg.module.isEmpty() && cfg.runtimeExecutable.isEmpty() && cfg.mainClass.isEmpty())
        val prog = if (programText.isNotEmpty()) programText else if (needsFile) active else ""
        if (prog.isEmpty() && needsFile) { Ui.toast(ctx, "Open the file you want to run, or type its path in Run and debug."); return }
        prefs.edit().putString("program:" + Api.folder, program.text.toString()).putString("args:" + Api.folder, args.text.toString()).apply()
        val lang = cfg?.lang ?: DebugSession.langOf(prog)
        if (lang == null) { Ui.toast(ctx, "Forge can debug Python, C, C++, Rust, Kotlin, Java, JavaScript and TypeScript files."); return }
        val argv = splitArgs(substitute(args.text.toString(), active))
        begin(Launch(noDebug, lang, prog, argv, cfg), lang, noDebug)
    }

    /**
     * Debugs (with [noDebug], runs) one file as it is: without the Program field, the Arguments field or a selected
     * configuration (the command palette's "Debug: start debugging the open file").
     */
    fun startOpenFile(path: String = host.activeFilePath(), noDebug: Boolean = false) {
        val running = session
        if (running != null) {
            if (running.state == DebugSession.State.STOPPED) cont() else Ui.toast(ctx, "A debug session is already running.")
            return
        }
        if (path.isEmpty()) { Ui.toast(ctx, "Open the file you want to debug."); return }
        val lang = DebugSession.langOf(path)
        if (lang == null) { Ui.toast(ctx, "Forge can debug Python, C, C++, Rust, Kotlin, Java, JavaScript and TypeScript files."); return }
        begin(Launch(noDebug, lang, path, emptyList(), null), lang, noDebug)
    }

    /** Debugs an installed debug build on the target device: the Android view calls this with the app id. */
    fun attachAndroid(appId: String) {
        if (session != null) { Ui.toast(ctx, "A debug session is already running."); return }
        begin(AndroidApp(appId), "kotlin", false)
    }

    /** Every session begins here, and Restart begins the same one again: save the editors, run the preLaunchTask, then launch or attach. */
    private fun begin(o: Any, lang: String, noDebug: Boolean) {
        host.showDebugConsole()
        console()?.clear()
        val s = DebugSession(lang, noDebug, this)
        s.origin = o
        session = s
        console()?.attach(s)
        render()
        val cfg = when (o) { is Launch -> o.cfg; is AttachJvm -> o.cfg; else -> null }
        saveFirst(s) {
            runTaskSteps(s, cfg?.preLaunch, "preLaunchTask") {
                when (o) {
                    is Launch -> launchSession(s, o)
                    is AttachJvm -> attachJvmSession(s, o)
                    is AndroidApp -> attachAndroidSession(s, o)
                }
            }
        }
    }

    /** Saves the open editors first, as Cursor does before every run; when the saving has not answered after 20 seconds, the run goes on. */
    private fun saveFirst(s: DebugSession, then: () -> Unit) {
        val save = saveBeforeRun ?: run { then(); return }
        var done = false
        val deadline = Runnable {
            if (done || session !== s) return@Runnable
            done = true
            out("Not every open file was saved within 20 seconds, so the run uses the files as they are on disk.", "err")
            then()
        }
        Api.main.postDelayed(deadline, 20000)
        save {
            Api.main.post {
                if (done) return@post
                done = true
                Api.main.removeCallbacks(deadline)
                if (session === s) then()
            }
        }
    }

    /** Runs a task's steps one after another, each in a terminal tab, and goes on only when every step succeeded. */
    private fun runTaskSteps(s: DebugSession, task: Task?, what: String, then: () -> Unit) {
        if (task == null || task.steps.isEmpty()) { then(); return }
        val runner = runTask
        if (runner == null) { fail(s, "The $what \"${task.label}\" needs Forge's terminal, which is not connected to Run and debug yet."); return }
        val file = host.activeFilePath()
        fun step(i: Int) {
            if (session !== s) return
            // The task's terminal tab was in front; the session's output goes to the Debug console again.
            if (i >= task.steps.size) { host.showDebugConsole(); then(); return }
            val st = task.steps[i]
            out("Running the $what \"${st.label}\" in a terminal tab...", "info")
            runner(st.build(file), "Task: " + st.label) done@{ code ->
                if (session !== s) return@done
                when {
                    code == 0 -> step(i + 1)
                    code < 0 -> fail(s, "The $what \"${st.label}\" did not finish: its terminal was killed first.")
                    else -> fail(s, "The $what \"${st.label}\" ended with exit code $code, so the program was not started. Its terminal tab shows why.")
                }
            }
        }
        step(0)
    }

    /** A postDebugTask: its steps run one after another in terminal tabs after the session; a failure is reported. */
    private fun runPostTask(task: Task) {
        val runner = runTask
        if (runner == null) { Ui.toast(ctx, "The postDebugTask \"${task.label}\" needs Forge's terminal, which is not connected to Run and debug yet."); return }
        val file = host.activeFilePath()
        fun step(i: Int) {
            if (i >= task.steps.size) return
            val st = task.steps[i]
            runner(st.build(file), "Task: " + st.label) done@{ code ->
                if (code == 0) step(i + 1) else Ui.toast(ctx, "The postDebugTask \"${st.label}\" " + (if (code < 0) "did not finish: its terminal was killed first." else "ended with exit code $code."))
            }
        }
        step(0)
    }

    private fun launchSession(s: DebugSession, o: Launch) {
        val cfg = o.cfg
        val what = when {
            cfg != null && cfg.module.isNotEmpty() -> "python -m " + cfg.module
            cfg != null && cfg.runtimeExecutable.isNotEmpty() -> (substitute(cfg.runtimeExecutable, o.prog) + " " + cfg.runtimeArgs.joinToString(" ") + " " + o.prog).trim()
            cfg != null && cfg.mainClass.isNotEmpty() && o.prog.isEmpty() -> cfg.mainClass
            else -> o.prog
        }
        out((if (o.noDebug) "Running " else "Debugging ") + what + (if (o.argv.isEmpty()) "" else " " + o.argv.joinToString(" ")), "info")
        readEnv(s, cfg) { env ->
            resolveMainClass(s, o) { prog ->
                val isSource = DebugSession.langOf(prog) != null
                // A runtime (tsx, ts-node, nodemon) runs the sources itself, so Forge builds nothing then.
                val compile = o.lang != "python" && o.lang != "javascript" && isSource && (cfg == null || cfg.runtimeExecutable.isEmpty())
                if (compile) {
                    out("Building with debug information...", "info")
                    Api.async("debug-prepare", { Api.post("/api/debug/prepare", JSONObject().put("path", prog), 920000) }) { r, e ->
                        if (session !== s) return@async
                        if (r == null) { fail(s, "Could not start: " + (e?.message ?: "the program could not be prepared.")); return@async }
                        val build = r.optText("build", "")
                        if (build.isNotEmpty()) out("$ $build", "info")
                        val output = r.optText("output", "").trim()
                        if (output.isNotEmpty()) out(output, if (r.optBoolean("ok", false)) "" else "err")
                        if (!r.optBoolean("ok", false)) { fail(s, "Could not start: " + r.optText("message", "The program could not be prepared.")); return@async }
                        connectAndLaunch(s, o, prog, r, env)
                    }
                } else {
                    connectAndLaunch(s, o, prog, JSONObject().put("program", if (prog.isEmpty()) "" else Breakpoints.absolute(prog)), env)
                }
            }
        }
    }

    /** The configuration's env with its envFile's variables under it (env wins, as in VS Code), every value with its ${...} filled in. */
    private fun readEnv(s: DebugSession, cfg: LaunchConfig?, then: (JSONObject?) -> Unit) {
        val file = host.activeFilePath()
        fun finish(base: JSONObject) {
            cfg?.env?.let { e -> for (k in e.keys()) base.put(k, if (e.isNull(k)) JSONObject.NULL else substitute(e.optText(k), file)) }
            then(if (base.length() > 0) base else null)
        }
        if (cfg == null || cfg.envFile.isEmpty()) { finish(JSONObject()); return }
        val path = substitute(cfg.envFile, file)
        Api.async("debug-envfile", { Api.get("/api/file", mapOf("path" to path), 10000).optText("content") }) { text, e ->
            if (session !== s) return@async
            if (text == null) {
                // As in VS Code, a missing environment file is not an error; the run goes on without it.
                out("The envFile $path could not be read (" + (e?.message ?: "no answer") + "), so the program starts without it.", "err")
                finish(JSONObject())
            } else finish(parseDotEnv(text))
        }
    }

    /** A Java or Kotlin configuration that names its main class (mainClass) runs the file that declares that class. */
    private fun resolveMainClass(s: DebugSession, o: Launch, then: (String) -> Unit) {
        val cfg = o.cfg
        if (cfg == null || cfg.mainClass.isEmpty() || o.prog.isNotEmpty() || (o.lang != "kotlin" && o.lang != "java")) { then(o.prog); return }
        val cls = cfg.mainClass.substringAfterLast('.')
        val pkg = if (cfg.mainClass.contains('.')) cfg.mainClass.substringBeforeLast('.') else ""
        val names = listOfNotNull(if (cls.endsWith("Kt")) cls.dropLast(2) + ".kt" else null, "$cls.java", "$cls.kt").distinct()
        Api.async("debug-main-class", {
            var found = ""
            for (n in names) {
                val files = Api.get("/api/files", mapOf("q" to n), 20000).optJSONArray("files")
                val list = if (files != null) (0 until files.length()).map { files.optText(it) } else emptyList()
                for (f in list.filter { it.substringAfterLast('/') == n }.take(20)) {
                    val text = try { Api.get("/api/file", mapOf("path" to f), 10000).optText("content") } catch (e: Exception) { continue }
                    val p = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: ""
                    if (p == pkg) { found = f; break }
                }
                if (found.isNotEmpty()) break
            }
            found
        }) { f, e ->
            if (session !== s) return@async
            if (f.isNullOrEmpty()) { fail(s, "Could not find the file that declares the main class ${cfg.mainClass}" + (if (e != null) ": " + e.message else ".")); return@async }
            out("${cfg.mainClass} is declared in $f.", "info")
            then(f)
        }
    }

    private fun connectAndLaunch(s: DebugSession, o: Launch, prog: String, prep: JSONObject, env: JSONObject?) {
        val lang = o.lang
        val cfg = o.cfg
        val folder = Api.folder.trimEnd('/')
        val cwd = if (cfg != null && cfg.cwd.isNotEmpty()) substitute(cfg.cwd, prog) else prep.optText("cwd", folder).ifEmpty { folder }
        val jvm = lang == "kotlin" || lang == "java"
        val js = lang == "javascript" || lang == "typescript"
        val launchArgs = JSONObject().put("name", "Forge").put("args", JSONArray(o.argv)).put("cwd", cwd)
        when {
            lang == "python" -> {
                launchArgs.put("type", "python").put("request", "launch")
                    .put("console", "internalConsole").put("redirectOutput", true).put("justMyCode", true).put("showReturnValue", true)
                    .put("noDebug", s.noDebug).put("stopOnEntry", cfg?.stopOnEntry ?: false)
                if (cfg != null && cfg.module.isNotEmpty()) launchArgs.put("module", substitute(cfg.module, prog)) else launchArgs.put("program", Breakpoints.absolute(prog))
                if (cfg != null && cfg.python.isNotEmpty()) launchArgs.put("python", substitute(cfg.python, prog))
            }
            jvm -> launchArgs.put("type", "intellij_jvm").put("request", "launch").put("tool", "java").put("mainClass", prep.optText("main_class"))
                .put("file", prep.optText("file")).put("console", "internalConsole").put("noDebug", s.noDebug)
                .put("classPaths", prep.optJSONArray("class_paths") ?: JSONArray()).put("javaExec", prep.optText("java_exec"))
                .put("vmArgs", JSONArray(listOf("-Xmx256m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC")))
            js -> {
                launchArgs.put("type", "pwa-node").put("request", "launch").put("__workspaceFolder", folder)
                    .put("console", "internalConsole").put("outputCapture", "std").put("skipFiles", JSONArray(listOf("<node_internals>/**")))
                    .put("sourceMaps", true).put("noDebug", s.noDebug)
                val p = prep.optText("program")
                if (p.isNotEmpty()) launchArgs.put("program", p)
                if (cfg != null && cfg.runtimeExecutable.isNotEmpty()) {
                    // js-debug starts the runtime (npm, tsx, nodemon and the like) with these arguments, then the program.
                    launchArgs.put("runtimeExecutable", substitute(cfg.runtimeExecutable, prog))
                    launchArgs.put("runtimeArgs", JSONArray(cfg.runtimeArgs.map { substitute(it, prog) }))
                }
                val outDir = prep.optText("out_dir", "")
                if (outDir.isNotEmpty()) {
                    launchArgs.put("outFiles", JSONArray(listOf("$outDir/**/*.js")))
                    launchArgs.put("resolveSourceMapLocations", JSONArray(listOf("$outDir/**", "$folder/**", "!**/node_modules/**")))
                }
                if (cfg?.stopOnEntry == true) launchArgs.put("stopOnEntry", true)
            }
            else -> launchArgs.put("program", prep.optText("program")).put("stopAtBeginningOfMainSubprogram", cfg?.stopOnEntry ?: false)
        }
        if (env != null) {
            // debugpy takes text values only; js-debug and Forge's GDB bridge read a null as "remove this variable".
            if (lang == "python") for (k in env.keys().asSequence().toList()) if (env.isNull(k)) env.remove(k)
            if (env.length() > 0) launchArgs.put("env", env)
        }
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

    private fun attachAndroidSession(s: DebugSession, o: AndroidApp) {
        val appId = o.appId
        out("Starting $appId on the target device so that it waits for the debugger...", "info")
        Api.async("debug-android", { Api.post("/api/debug/android", JSONObject().put("app_id", appId), 90000) }) { r, e ->
            if (session !== s) return@async
            if (r == null) { fail(s, "Could not attach: " + (e?.message ?: "")); return@async }
            if (!r.optBoolean("ok", false)) { fail(s, "Could not attach: " + r.optText("message", "")); return@async }
            s.android = r
            out(r.optText("message", ""), "info")
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
    private fun attachJvmSession(s: DebugSession, o: AttachJvm) {
        out("Attaching to ${o.hostName}:${o.port} (${o.name})...", "info")
        val attachArgs = JSONObject().put("type", "intellij_jvm").put("request", "attach").put("name", "Forge")
            .put("hostName", o.hostName).put("port", o.port).put("timeout", 30000)
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
                    .put("serial", android.optText("serial")).put("port", android.optInt("port")), 20000)
            }) { _, _ -> }
        }
        val started = s.state == DebugSession.State.RUNNING || s.state == DebugSession.State.STOPPED
        session = null
        console()?.attach(null)
        s.close()
        render()
        val o = s.origin
        if (restartAfter === s) {
            // Restart: the session has ended, so the same one begins again.
            restartAfter = null
            if (o != null) begin(o, s.lang, s.noDebug)
            return
        }
        // A postDebugTask runs after a session that ran, as in VS Code; not after one that never started.
        val post = (o as? Launch)?.cfg?.postDebug ?: (o as? AttachJvm)?.cfg?.postDebug
        if (post != null && started) runPostTask(post)
    }

    fun stop() {
        restartAfter = null
        val s = session ?: return
        terminate(s)
    }

    /** Ctrl Shift F5: the session ends, and the terminate callback (or the adapter's own end) begins it again. */
    fun restart() {
        val s = session ?: return
        restartAfter = s
        terminate(s)
    }

    private fun terminate(s: DebugSession) {
        // The adapter may send its terminated event before it answers, which ends the session first; the note
        // is printed in both orders.
        s.terminate { _, _ -> out("Stopped.", "info"); if (session === s) end(s) }
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
        if (body.optText("reason") == "exception") {
            s.exceptionInfo { r, _ -> if (r != null) out("Exception: " + r.optText("exceptionId", "") + " " + r.optText("description", ""), "err") }
        }
    }

    private fun loadStack(s: DebugSession, thread: Int) {
        s.stackTrace(thread) { frames, e ->
            if (session !== s || s.state != DebugSession.State.STOPPED) return@stackTrace
            if (frames == null) { out(e?.message ?: "stackTrace failed", "err"); return@stackTrace }
            s.threadId = thread
            s.frames = frames
            val first = frames.firstOrNull { it.optJSONObject("source")?.optText("path", "")?.isNotEmpty() == true } ?: frames.firstOrNull()
            if (first != null) selectFrame(first) else render()
        }
    }

    private fun selectFrame(frame: JSONObject) {
        val s = session ?: return
        s.frameId = frame.optInt("id", -1)
        s.frame = frame
        val src = frame.optJSONObject("source")
        val path = src?.optText("path", "") ?: ""
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

    // Only the session that ended is ended: when the adapter's terminated event comes before its answer to Restart's
    // disconnect, the answer's callback has already begun the next session, which must keep running.
    override fun onEnded(ended: DebugSession) { if (session === ended) end(ended) }

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
                mono(t.optText("name", "Thread $id"), if (current) Ui.fg else Ui.fg2).also { it.setPadding(Ui.dp(ctx, 4), 0, 0, 0) })
            clickable(head)
            head.setOnClickListener { if (session === s && s.state == DebugSession.State.STOPPED && !current) loadStack(s, id) }
            col.addView(head, Ui.lp(Ui.match, Ui.wrap))
            if (!current) continue
            if (s.frames.isEmpty()) { col.addView(Ui.text(ctx, "Loading the call stack...", 11f, Ui.fg3).also { it.setPadding(Ui.dp(ctx, 16), 0, 0, 0) }); continue }
            for (f in s.frames) {
                val src = f.optJSONObject("source")
                val where = (src?.optText("name", "") ?: "").ifEmpty { (src?.optText("path", "") ?: "").substringAfterLast('/') } + ":" + f.optInt("line", 0)
                val name = mono(f.optText("name", "?"))
                name.layoutParams = Ui.lp(0, Ui.wrap, 1f)
                val row = Ui.row(ctx, name, mono(where, Ui.fg3).also { it.setPadding(Ui.dp(ctx, 8), 0, 0, 0) })
                clickable(row, f.optInt("id", -1) == s.frameId)
                row.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
                row.tooltipText = src?.optText("path", "") ?: ""
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
            val name = sc.optText("name", "")
            val large = (sc.optBoolean("expensive", false) || sc.optText("presentationHint", "") == "registers" || name.equals("registers", true)) && name != "Locals"
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
        val value = v.optText("value", "")
        val type = v.optText("type", "")
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(ctx, 14 * depth), Ui.dp(ctx, 1), 0, Ui.dp(ctx, 1))
        val twisty = Ui.iconView(ctx, R.drawable.ic_chevron_right, if (ref > 0) Ui.fg3 else 0, 12)
        if (ref <= 0) twisty.setImageDrawable(null)
        head.addView(twisty)
        head.addView(mono(v.optText("name", ""), Ui.blue).also { it.setPadding(Ui.dp(ctx, 3), 0, 0, 0) })
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
                if (r != null) value.text = r.optText("result", "") else { value.text = e?.message ?: "failed"; value.setTextColor(Ui.red) }
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
