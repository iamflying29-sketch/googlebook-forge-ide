package local.forge

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import org.json.JSONObject

/**
 * The settings page, the native twin of the web page's settings view: grouped sections, each row with its label,
 * its control and a gray hint. Values come from GET /api/settings ("effective") and every change is saved at once
 * with POST /api/settings {key: value, "_scope": "user"}; a toast confirms the save or reports the error. The GPU
 * engine card at the top shows the engine's state from EngineService and controls it.
 *
 * Entries of the web page's array that are not here, because they have no native meaning: "Editor font size"
 * (the font of the browser page's Monaco editor) and the keyboard shortcut list (the browser page's keys). The
 * extras the web page appends under User (rules, MCP servers, custom modes, command lists, memories, docs, hooks)
 * are not part of that array and are not here either.
 */
class SettingsActivity : Activity() {
    private class Entry(val key: String, val label: String, val type: String, val hint: String = "",
                        val options: List<String> = emptyList(), val decimal: Boolean = false)
    private class Group(val title: String, val note: String, val entries: List<Entry>)

    private val groups = listOf(
        Group("Model and engine", "", listOf(
            Entry("engine", "Inference engine", "select", "ollama: CPU inside the Linux VM. gpu: the Forge GPU engine (llama.cpp with Vulkan on the Intel graphics), started from this app. Switching clears the chat context.", listOf("ollama", "gpu")),
            Entry("model", "Model", "model", "Served by Ollama. forge-coder is the tuned Llama 3.1 build."),
            Entry("num_ctx", "Context window (tokens)", "number", "8192 is the sweet spot on this machine. Bigger means slower first replies and more RAM."),
            Entry("num_predict", "Max tokens per reply", "number"),
            Entry("temperature", "Temperature", "number", "0.1 to 0.3 for code.", decimal = true),
            Entry("num_thread", "CPU threads (0 = auto)", "number"),
            Entry("keep_alive", "Keep model loaded", "text", "For example 60m, 24h, or -1 for always."),
            Entry("ollama_url", "Ollama URL", "text", "Where the Forge server reaches Ollama inside the VM."))),
        Group("GPU engine", "", listOf(
            Entry("gpu_model", "GPU engine: model", "model", "The Ollama model whose weights the Forge app runs on the Intel graphics (downloaded to the Googlebook once per model). forge-coder is the tuned Llama 3.1 8B build; qwen3.5:4b is about half the size and about twice as fast per token with better tool use. Changing it restarts the engine on the next start."),
            Entry("gpu_ctx", "GPU engine context (tokens)", "number", "Context window the GPU engine is started with."),
            Entry("gpu_autostart", "Start the GPU engine by itself", "bool", "When the GPU engine is the chosen engine and it is not running, the Forge app starts it by itself: when you open Forge, when a message fails without it, and after the app was closed or updated. It never skips the free-memory check, never loads it next to another AI model, and does not retry a failed start for three minutes."),
            Entry("gpu_idle_unload_min", "GPU engine: unload after idle minutes", "number", "After this many minutes without a chat, the Forge app unloads the GPU engine so an idle laptop does not keep 3 to 4 GB of graphics memory busy. The next message starts it again by itself and waits for it (about 25 seconds with Qwen3.5 4B). 0 keeps the engine loaded."),
            Entry("gpu_pool_credit", "GPU engine: count the driver's held memory (percent)", "select", "After a Forge GPU process stops, the graphics driver keeps its memory for the next GPU process, and Android does not count it as free; measured on 2026-10-19: 5.4 GB held, a vision model took 1.4 GB straight out of it. At 0 (the default) the free-memory check only trusts what Android reports, so the engine may refuse to start until the Googlebook is restarted. At 50 or 80 it also counts that share of the measured held memory, with a guard that stops the engine the moment free memory gets scarce. Your decision: this is the one setting that lets Forge load a model into memory Android does not report as free.", listOf("0", "50", "80")),
            Entry("gpu_extra_args", "GPU engine: extra llama-server flags", "text", "Added to the engine command line at every start. The default caps the server's own prompt cache at 1 GB of RAM, uses one unified KV cache and turns on draft-free speculative decoding (ngram-mod), which guesses upcoming tokens from text already produced; code repeats, so replies get faster. If the engine refuses a flag, the app starts it again without the extras and says so in the engine log."))),
        Group("Vision", "", listOf(
            Entry("vision", "Vision for screenshots", "select", "auto: Qwen3-VL on the Intel GPU (4B when memory allows, otherwise 2B), then the CPU model, then text recognition only. ocr: text recognition only.", listOf("auto", "gpu", "cpu", "ocr")),
            Entry("vision_cpu_model", "CPU vision model (Ollama)", "text", "Used when the GPU is not available, only when memory allows."),
            Entry("vision_extra_args", "Vision model: extra llama-server flags", "text", "Added to the vision server command line. The default turns flash attention off, because inside the image encoder it is the slow path on this kind of GPU (minutes instead of seconds). Screenshots are sent scaled to 1280 pixels wide, about 1,000 image tokens; text recognition keeps the full picture."))),
        Group("Agent behavior", "", listOf(
            Entry("thinking", "Show thinking process", "bool", "The model reasons in a visible block before acting."),
            Entry("tool_mode", "Tool calling", "select", "prompt: Forge's robust tagged format (recommended for 8B models). native: Ollama's JSON tool API.", listOf("prompt", "native")),
            Entry("max_iterations", "Max tool steps per message", "number"),
            Entry("auto_approve_commands", "Auto-run commands", "bool", "Destructive commands and deletions still ask."),
            Entry("external_file_protection", "External-file protection", "bool", "The agent may read and change files anywhere on this computer. With this on, it asks before changing a file outside the project folder. System files and keys always ask."),
            Entry("semantic_index", "Codebase index (search by meaning)", "bool", "Forge turns your code into embedding vectors with a small separate model (about 250 MB, started only when needed and only when memory allows), so codebase search and @codebase find code by what it does, not only by its words. Indexing pauses while a chat is running."),
            Entry("repo_map_chars", "Repo map size (characters)", "number", "Outline of your workspace given to the model. Smaller is faster."),
            Entry("prewarm", "Pre-warm model on new chat", "bool", "Processes the system prompt in the background so the first reply is fast."),
            Entry("verbose", "Verbose mode", "bool", "Show raw model traffic, trimming and timing in the chat and Agent log."))),
        Group("Editor", "These settings drive the editor features the Forge server provides (language servers and completions); the browser page's editor uses them in full, this app's editor only the parts it has.", listOf(
            Entry("lsp", "Language servers (code intelligence)", "bool", "Suggestions, hover information, go to definition, find references, rename and live errors for Python, JavaScript, TypeScript, shell, JSON, CSS, HTML and C or C++. Each language server starts only when you open a file of that language and only when memory allows."),
            Entry("kotlin_project_model", "Kotlin project model", "select", "How the Kotlin language server learns your project. gradle: a full Gradle import at every start (exact libraries, but a second large Java process; needs about 4.7 GB free on the Googlebook). light: Forge writes the project description itself from your source folders, the JDK, the Kotlin library, android.jar and libraries Gradle already downloaded (about half the memory, 4.1 GB). auto: gradle when there is room, otherwise light.", listOf("auto", "light", "gradle")),
            Entry("tab_completions", "Tab completions while typing", "bool", "Slow on CPU; a completion can also be asked for by hand at any time."),
            Entry("completion_delay_ms", "Completion delay (ms)", "number"))),
        Group("Virtual devices", "", listOf(
            Entry("vd_updates", "Virtual device images: updates and upgrades", "select", "What Forge does daily for every emulator image: check shows what is available in the Android view; install also downloads and installs updates and upgrades (one at a time, never while that device runs); install-move also moves stopped devices to the newest installed version of their family; off never checks. Check now and Update and upgrade everything are in the Android view.", listOf("check", "install", "install-move", "off")))),
    )

    /** The web page's --accent-2 (group headings) and the switch colors that Ui has no names for. */
    private val accent2 = Color.parseColor("#ffb07a")
    private val accentSoft = Color.parseColor("#24ff8a3d")
    private val bg4 = Color.parseColor("#222738")

    private lateinit var page: LinearLayout
    private lateinit var form: LinearLayout
    private var settings = JSONObject()
    private var models: List<String> = emptyList()
    /** Called after a save so every row shows the server's effective value. */
    private val refreshers = ArrayList<() -> Unit>()

    // GPU engine card
    private lateinit var engineState: TextView
    private lateinit var engineModels: TextView
    private lateinit var engineMemory: TextView
    private var started = false
    private val engineTask = object : Runnable { override fun run() { refreshEngine(); if (started) Api.main.postDelayed(this, 4000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Forge settings"
        val c = this
        val outer = FrameLayout(c)
        outer.setBackgroundColor(Ui.bg)
        page = Ui.column(c)
        val widthDp = minOf(resources.configuration.screenWidthDp, 780)
        page.layoutParams = FrameLayout.LayoutParams(Ui.dp(c, widthDp), Ui.wrap, Gravity.CENTER_HORIZONTAL)
        page.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 24))
        val scroll = Ui.scroll(c, page)
        scroll.isFillViewport = false
        outer.addView(scroll, FrameLayout.LayoutParams(Ui.match, Ui.match))
        setContentView(outer)

        val heading = Ui.text(c, "Settings", 18f, Ui.fg, bold = true)
        heading.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        val head = Ui.row(c, heading, Ui.button(c, "Close") { finish() })
        head.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4))
        page.addView(head)
        page.addView(Ui.text(c, "These settings apply to every folder and are saved on the Forge server as you change them.", 11.5f, Ui.fg3))
        page.addView(engineCard().also { it.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { p -> p.topMargin = Ui.dp(c, 12) } })

        form = Ui.column(c)
        page.addView(form)
        page.addView(Ui.text(c, "Forge app " + MainActivity.VERSION + ", native.", 11f, Ui.fg3).also { it.setPadding(0, Ui.dp(c, 18), 0, 0) })
        loadSettings()
    }

    override fun onStart() { super.onStart(); started = true; Api.main.post(engineTask) }
    override fun onStop() { super.onStop(); started = false; Api.main.removeCallbacks(engineTask) }

    // ------------------------------------------------------------------ loading and saving
    private fun loadSettings() {
        form.removeAllViews()
        refreshers.clear()
        form.addView(Ui.text(this, "Loading the settings from the Forge server...", 12f, Ui.fg3).also { it.setPadding(0, Ui.dp(this, 12), 0, 0) })
        Api.async("settings", {
            val s = Api.get("/api/settings")
            // The model list is optional: without it the model pickers offer the current value only.
            val m = try { Api.get("/api/models") } catch (e: Exception) { null }
            Pair(s, m)
        }) { r, e ->
            form.removeAllViews()
            if (r == null) {
                form.addView(Ui.text(this, "Could not load the settings: " + (e?.message ?: "unknown error"), 12f, Ui.red).also { it.setPadding(0, Ui.dp(this, 12), 0, 0) })
                form.addView(Ui.button(this, "Try again") { loadSettings() }.also { it.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { p -> p.topMargin = Ui.dp(this, 8) } })
                return@async
            }
            settings = r.first.optJSONObject("effective") ?: JSONObject()
            val names = ArrayList<String>()
            r.second?.optJSONArray("models")?.let { a -> for (i in 0 until a.length()) { val n = a.optJSONObject(i)?.optString("name") ?: ""; if (n.isNotEmpty()) names.add(n) } }
            models = names
            for (g in groups) {
                form.addView(groupHeader(g.title))
                if (g.note.isNotEmpty()) form.addView(Ui.text(this, g.note, 11f, Ui.fg3))
                for (en in g.entries) form.addView(renderRow(en))
            }
        }
    }

    private fun groupHeader(title: String): TextView {
        val t = Ui.text(this, title.uppercase(), 11f, accent2)
        t.letterSpacing = 0.08f
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4))
        return t
    }

    /**
     * Saves one value. The engine is switched through /api/engine/select first, as the web page does, because the
     * server refuses "gpu" while the engine is not reachable. On failure the control goes back to the old value.
     */
    private fun save(en: Entry, value: Any, revert: () -> Unit) {
        Api.async("settings-save", {
            if (en.key == "engine") Api.post("/api/engine/select", JSONObject().put("engine", value))
            Api.post("/api/settings", JSONObject().put(en.key, value).put("_scope", "user"))
        }) { r, e ->
            if (r == null) {
                Ui.toast(this, "Not saved: " + (e?.message ?: "unknown error"))
                revert()
                return@async
            }
            settings = r.optJSONObject("effective") ?: settings.put(en.key, value)
            Ui.toast(this, "Saved " + en.label)
            for (f in refreshers) f()
        }
    }

    private fun current(en: Entry): String {
        val v = settings.opt(en.key) ?: return ""
        return if (v is Double && v == Math.floor(v) && !en.decimal) v.toLong().toString() else v.toString()
    }

    // ------------------------------------------------------------------ rows and controls
    private fun renderRow(en: Entry): View {
        val c = this
        val row = Ui.column(c)
        row.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8))
        val label = Ui.text(c, en.label, 12.5f, Ui.fg2)
        val hint = if (en.hint.isEmpty()) null else Ui.text(c, en.hint, 11f, Ui.fg3)
        when (en.type) {
            "bool" -> {
                // An inline row: the label on the left, the switch on the right, like .srow.inline on the web page.
                label.layoutParams = Ui.lp(0, Ui.wrap, 1f)
                val toggle = Toggle(c, settings.optBoolean(en.key))
                toggle.onChange = { on -> save(en, on) { toggle.set(!on, false) } }
                refreshers.add { toggle.set(settings.optBoolean(en.key), false) }
                row.addView(Ui.row(c, label, toggle))
            }
            "select", "model" -> {
                row.addView(label)
                val picker = Picker(c)
                val options = { optionsFor(en) }
                picker.show(current(en))
                picker.setOnClickListener {
                    val opts = options()
                    showPicker(picker, opts, current(en)) { chosen ->
                        val old = current(en)
                        if (chosen == old) return@showPicker
                        picker.show(chosen)
                        val value: Any = if (en.key == "gpu_pool_credit") (chosen.toIntOrNull() ?: 0) else chosen
                        save(en, value) { picker.show(old) }
                    }
                }
                refreshers.add { picker.show(current(en)) }
                row.addView(picker)
            }
            else -> {
                row.addView(label)
                val input = Ui.input(c, "")
                input.layoutParams = Ui.lp(Ui.match, Ui.wrap)
                input.setText(current(en))
                if (en.type == "number") {
                    input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    if (en.key.endsWith("_args") || en.key == "ollama_url") input.typeface = android.graphics.Typeface.MONOSPACE
                }
                input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                val commit = { commitText(en, input) }
                input.setOnEditorActionListener { _, _, _ -> commit(); true }
                input.setOnKeyListener { _, code, ev ->
                    if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { commit(); true } else false
                }
                input.setOnFocusChangeListener { _, has -> if (!has) commit() }
                refreshers.add { if (!input.hasFocus()) input.setText(current(en)) }
                row.addView(input)
            }
        }
        if (hint != null) { hint.setPadding(0, Ui.dp(c, 3), 0, 0); row.addView(hint) }
        return row
    }

    /** The choices of a picker: the fixed options, or the model names with the current value kept in the list. */
    private fun optionsFor(en: Entry): List<String> {
        if (en.type != "model") return en.options
        val cur = current(en)
        val list = ArrayList(models)
        if (cur.isNotEmpty() && !list.contains(cur) && !list.contains("$cur:latest")) list.add(0, cur)
        if (list.isEmpty()) list.add(cur)
        return list
    }

    /** Saves a text or number field when its value changed; a value that is not a number is refused. */
    private fun commitText(en: Entry, input: EditText) {
        val raw = input.text.toString().trim()
        val old = current(en)
        if (raw == old) return
        val value: Any
        if (en.type == "number") {
            val d = raw.toDoubleOrNull()
            if (d == null) { Ui.toast(this, en.label + ": " + (if (raw.isEmpty()) "a number is needed" else "\"$raw\" is not a number")); input.setText(old); return }
            value = if (!en.decimal && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d
        } else value = raw
        save(en, value) { input.setText(old) }
    }

    /** A dark drop-down list under the picker; the current value is drawn in the accent color. */
    private fun showPicker(anchor: TextView, options: List<String>, current: String, onPick: (String) -> Unit) {
        val c = this
        val popup = ListPopupWindow(c)
        popup.anchorView = anchor
        popup.width = anchor.width.coerceAtLeast(Ui.dp(c, 200))
        popup.isModal = true
        popup.setBackgroundDrawable(Ui.rounded(Ui.bg3, 8, dialogBorder))
        popup.verticalOffset = Ui.dp(c, 2)
        popup.setAdapter(object : BaseAdapter() {
            override fun getCount(): Int = options.size
            override fun getItem(position: Int): Any = options[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val t = (convertView as? TextView) ?: Ui.text(c, "", 13f, Ui.fg).also { it.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8)); it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END }
                t.text = options[position]
                t.setTextColor(if (options[position] == current) Ui.accent else Ui.fg)
                return t
            }
        })
        popup.setOnItemClickListener { _, _, pos, _ -> popup.dismiss(); onPick(options[pos]) }
        popup.show()
        popup.listView?.let { lv ->
            lv.divider = null
            lv.selector = Ui.rounded(accentSoft, 6)
            val idx = options.indexOf(current); if (idx >= 0) lv.setSelection(idx)
        }
    }

    /** A field-like control that shows the chosen value and a small arrow; tapping it opens the list. */
    private inner class Picker(c: Context) : TextView(c) {
        init {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Ui.fg)
            background = Ui.rounded(Ui.bg2, 8, Ui.border)
            setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true; isFocusable = true
            layoutParams = Ui.lp(Ui.match, Ui.wrap)
            setOnKeyListener { v, code, ev -> if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE) && ev.action == KeyEvent.ACTION_DOWN) { v.performClick(); true } else false }
        }
        fun show(value: String) { text = value + "   ▾" }
    }

    /** A 34 by 18 dp switch like the web page's .switch: a pill with a knob that slides to the right when on. */
    private inner class Toggle(c: Context, initial: Boolean) : FrameLayout(c) {
        var on = initial
            private set
        var onChange: ((Boolean) -> Unit)? = null
        private val knob = View(c)
        private val h = Ui.dp(c, 18)
        private val travel = Ui.dp(c, 16)
        init {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(c, 34), h)
            val k = Ui.dp(c, 12)
            addView(knob, LayoutParams(k, k, Gravity.CENTER_VERTICAL).also { it.leftMargin = Ui.dp(c, 2) })
            isClickable = true; isFocusable = true
            contentDescription = "switch"
            paint(false)
            setOnClickListener { set(!on, true) }
            setOnKeyListener { _, code, ev -> if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE) && ev.action == KeyEvent.ACTION_DOWN) { set(!on, true); true } else false }
        }
        fun set(v: Boolean, fire: Boolean) {
            val changed = v != on
            on = v
            paint(changed)
            if (fire) onChange?.invoke(on)
        }
        private fun paint(animate: Boolean) {
            background = pill(if (on) accentSoft else bg4, h, if (on) Ui.accent else dialogBorder)
            knob.background = circle(if (on) Ui.accent else Ui.fg3)
            val x = if (on) travel.toFloat() else 0f
            if (animate) knob.animate().translationX(x).setDuration(150).start() else knob.translationX = x
        }
    }

    // ------------------------------------------------------------------ the GPU engine card
    private fun engineCard(): View {
        val c = this
        engineState = Ui.text(c, "", 12.5f, Ui.fg)
        engineModels = Ui.text(c, "", 11.5f, Ui.fg2)
        engineMemory = Ui.text(c, "", 11.5f, Ui.fg2)
        val buttons = Ui.row(c,
            Ui.button(c, "Start engine", primary = true) { EngineService.start(c); Ui.toast(c, "Starting the GPU engine"); Api.main.postDelayed({ refreshEngine() }, 800) },
            gap(8), Ui.button(c, "Stop engine") { EngineService.stop(c); Ui.toast(c, "Stopping the GPU engine"); Api.main.postDelayed({ refreshEngine() }, 800) },
            gap(8), Ui.button(c, "Refresh") { refreshEngine() },
            gap(8), Ui.button(c, "Engine log") { showEngineLog() })
        buttons.setPadding(0, Ui.dp(c, 8), 0, 0)
        val card = Ui.card(c, Ui.text(c, "GPU engine", 13.5f, Ui.fg, bold = true),
            Ui.text(c, "llama.cpp with Vulkan on the Intel graphics, run by this app as a foreground service.", 11f, Ui.fg3),
            engineState.also { it.setPadding(0, Ui.dp(c, 6), 0, 0) }, engineModels, engineMemory, buttons)
        refreshEngine()
        return card
    }

    private fun gap(dp: Int): View { val v = View(this); v.layoutParams = Ui.lp(Ui.dp(this, dp), 1); return v }

    private fun refreshEngine() {
        val st = EngineService.status(this)
        val state = st.optString("state", "unknown")
        val detail = st.optString("detail")
        engineState.text = "State: " + state + (if (detail.isNotEmpty()) " (" + detail + ")" else "")
        engineState.setTextColor(when (state) { "running" -> Ui.green; "error" -> Ui.red; "starting", "downloading" -> Ui.accent; else -> Ui.fg })
        val files = st.optString("models")
        engineModels.text = "Model files on this Googlebook: " + (if (files.isEmpty() || files == "[]") "none downloaded yet" else files.trim('[', ']'))
        val free = st.optLong("hostMemAvailableMb")
        val vision = st.optString("vision")
        engineMemory.text = "Free memory reported by Android: " + free + " MB" +
            (if (!st.optBoolean("binary", true)) ". The engine binary is missing from this build." else "") +
            (if (vision.isNotEmpty() && vision != "off" && vision != "stopped") ". Vision model: " + vision + (if (st.optString("visionTier").isNotEmpty()) " (" + st.optString("visionTier") + ")" else "") else "")
    }

    /** The engine log in a dark dialog: monospace text, a Copy button, Refresh and Close. */
    private fun showEngineLog() {
        val c = this
        val box = Ui.column(c)
        val heading = Ui.text(c, "Engine log", 14f, Ui.fg, bold = true)
        heading.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        lateinit var dialog: Dialog
        val head = Ui.row(c, heading, glyphButton(c, "✕", "Close") { dialog.dismiss() })
        head.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8))
        box.addView(head)
        box.addView(Ui.divider(c, false))
        val body = Ui.text(c, "", 11.5f, Ui.fg, mono = true)
        body.setTextIsSelectable(true)
        body.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8))
        val fill = {
            val log = EngineService.logTail()
            body.text = if (log.isBlank()) "The engine log is empty. It fills when the engine starts." else log
        }
        fill()
        val scroll = Ui.scroll(c, body)
        scroll.isFillViewport = false
        scroll.layoutParams = Ui.lp(Ui.match, (resources.displayMetrics.heightPixels * 0.6).toInt())
        box.addView(scroll)
        box.addView(Ui.divider(c, false))
        val spacer = View(c); spacer.layoutParams = Ui.lp(0, 1, 1f)
        val foot = Ui.row(c,
            Ui.button(c, "Copy") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Forge engine log", body.text))
                Ui.toast(c, "Engine log copied")
            }, gap(8), Ui.button(c, "Refresh") { fill(); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } },
            spacer, Ui.button(c, "Close", primary = true) { dialog.dismiss() })
        foot.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 10))
        box.addView(foot)
        dialog = darkDialog(c, box, 900)
        dialog.show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
