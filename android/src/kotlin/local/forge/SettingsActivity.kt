package local.forge

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The settings page.
 *
 * At the top sit two scope tabs: User (the settings for every folder) and Folder (overrides for this window's folder,
 * saved by the server in .forge/settings.json inside it). The server lists the keys a folder may override in
 * "folder_keys"; under the Folder tab only those rows are shown, each marked "(from User)" until it is overridden, and
 * an overridden row gets a Reset link. Values come from GET /api/settings, which returns {"effective", "user", "folder",
 * "folder_keys", "folder_path", "trust"}, and every change is saved at once with POST /api/settings {key: value,
 * "_scope": "user" | "folder"}; a Reset posts {"_scope": "folder", "_unset": [key]}.
 *
 * Below the grouped rows come the cards of SettingsExtras: folder trust (Folder tab), the codebase index, the custom
 * modes, the command allow and deny lists, the memories, the docs sources, the hooks, the rules for the AI, the MCP
 * servers and the vision status card, and at the end a button that opens the window's keyboard shortcuts page. The GPU
 * engine card shows the engine's state from EngineService and controls it; it is machine-wide and appears under User.
 * Settings opened at a section (MainActivity.openSettingsAt) scroll to that section's group.
 */
class SettingsActivity : Activity() {
    internal class Entry(val key: String, val label: String, val type: String, val hint: String = "",
                         val options: List<String> = emptyList(), val decimal: Boolean = false, val min: Long? = null, val max: Long? = null)
    private class Group(val title: String, val note: String, val entries: List<Entry>)

    private val groups = listOf(
        Group("Model and engine", "", listOf(
            Entry("engine", "Inference engine", "select", "ollama: CPU inside the Linux VM. gpu: the Forge GPU engine (llama.cpp with Vulkan on the Intel graphics), started from this app. A chat continues with its whole conversation after a switch.", listOf("ollama", "gpu")),
            Entry("model", "Model (Ollama)", "model", "The model Ollama serves when the engine is ollama. forge-coder is the tuned Llama 3.1 build. With the GPU engine, chats use the GPU engine's model below, and a chat's own model choice in the chat's model menu wins over both."),
            Entry("num_ctx", "Context window (tokens, Ollama)", "number", "8192 is the sweet spot on this machine. Bigger means slower first replies and more RAM. The GPU engine uses its own context below. From 2048 to 32768.", min = 2048, max = 32768),
            Entry("num_predict", "Max tokens per reply", "number"),
            Entry("temperature", "Temperature", "number", "0.1 to 0.3 for code.", decimal = true),
            Entry("top_p", "Top p", "number", "The share of the most likely next words the model picks from (0.9 by default). Both engines use it.", decimal = true),
            Entry("top_k", "Top k", "number", "The number of most likely next words the model picks from (40 by default). Both engines use it."),
            Entry("repeat_penalty", "Repeat penalty", "number", "Above 1 makes the model repeat itself less (1.05 by default). Both engines use it.", decimal = true),
            Entry("fallback_model", "Fallback model (Ollama)", "model", "Used when the chosen model is not installed in Ollama. When neither is installed, Forge picks the first installed chat model."),
            Entry("num_thread", "CPU threads (0 = auto)", "number"),
            Entry("keep_alive", "Keep model loaded (Ollama)", "text", "How long Ollama keeps the model in the VM's memory after a reply: for example 5m, 60m or 24h, or -1 to keep it until it is unloaded."),
            Entry("ollama_url", "Ollama URL", "text", "Where the Forge server reaches Ollama inside the VM."))),
        Group("GPU engine", "", listOf(
            Entry("gpu_model", "GPU engine: model", "model", "The Ollama model whose weights the Forge app runs on the Intel graphics (downloaded to the Googlebook once per model). forge-coder is the tuned Llama 3.1 8B build; qwen3.5:4b is about half the size and about twice as fast per token with better tool use. A change takes effect the next time the engine starts (Stop engine, then Start engine on the engine card)."),
            Entry("gpu_ctx", "GPU engine context (tokens)", "number", "Context window the GPU engine is started with. The memory check counts what this context needs. From 2048 to 32768.", min = 2048, max = 32768),
            Entry("gpu_autostart", "Start the GPU engine by itself", "bool", "When the GPU engine is the chosen engine and it is not running, the Forge app starts it by itself: when you open Forge, when a message fails without it, and after the app was closed or updated. It never skips the free-memory check, never loads it next to another AI model, and does not retry a failed start for three minutes."),
            Entry("gpu_idle_unload_min", "GPU engine: unload after idle minutes", "number", "After this many minutes without a chat, the Forge app unloads the GPU engine so an idle laptop does not keep 3 to 4 GB of graphics memory busy. The next message starts it again by itself and waits for it (about 25 seconds with Qwen3.5 4B). 0 keeps the engine loaded. It only unloads while Start the GPU engine by itself is on, because something must start it again."),
            Entry("gpu_pool_credit", "GPU engine: count the driver's held memory (percent)", "select", "After a Forge GPU process stops, the graphics driver keeps its memory for the next GPU process, and Android does not count it as free; measured on 2026-10-19: 5.4 GB held, a vision model took 1.4 GB straight out of it. At 0 (the default) the free-memory check trusts what Android reports, with one exception: for 15 minutes after Forge's own GPU process stopped (when the engine and the vision model take turns), it counts the memory that process left with the driver, but never more than was free before Forge's GPU work began. Otherwise the engine may refuse to start until the Googlebook is restarted. At 50 or 80 it also counts that share of the measured held memory at any time, with a guard that stops the engine the moment free memory gets scarce. Your decision: this is the setting that lets Forge load a model into memory Android does not report as free.", listOf("0", "50", "80")),
            Entry("gpu_extra_args", "GPU engine: extra llama-server flags", "text", "Added to the engine command line at every start. The default caps the server's own prompt cache at 1 GB of RAM, uses one unified KV cache and turns on draft-free speculative decoding (ngram-mod), which guesses upcoming tokens from text already produced; code repeats, so replies get faster. If the engine refuses a flag, the app starts it again without the extras and says so in the engine log."),
            Entry("gpu_vision", "GPU engine: look at images itself", "bool", "When the GPU engine's model has a vision projector (Qwen3.5 4B), the engine loads it and describes screenshots itself, so the GPU never unloads the model for a second vision model. Takes effect at the engine's next start."),
            Entry("gpu_image_tokens", "GPU engine: image tokens per picture", "number", "The most tokens one picture becomes when the GPU engine looks at it. Pictures are scaled to 1024 pixels on the longer side first, which is about 640 tokens, so values above 640 add little; 640 reads a screenshot in about 8 seconds."))),
        Group("Vision", "", listOf(
            Entry("vision", "Vision for screenshots", "select", "auto: the GPU engine itself when its model can see (Qwen3.5 4B), otherwise Qwen3-VL on the Intel GPU (4B when memory allows, otherwise 2B), then the CPU model, then text recognition only. ocr: text recognition only.", listOf("auto", "gpu", "cpu", "ocr")),
            Entry("vision_cpu_model", "CPU vision model (Ollama)", "text", "Used when the GPU is not available, only when memory allows."),
            Entry("vision_extra_args", "Vision model: extra llama-server flags", "text", "Added to the vision server command line. The default turns flash attention off, because inside the image encoder it is the slow path on this kind of GPU (minutes instead of seconds). Screenshots are sent scaled so the longer side is 1024 pixels, at most 640 image tokens; text recognition keeps the full picture."))),
        Group("Agent behavior", "", listOf(
            Entry("thinking", "Thinking step", "bool", "The model writes a short reasoning block (shown as its thought process) before it acts. Off: it acts directly, which is a little faster."),
            Entry("tool_mode", "Tool calling", "select", "prompt: Forge's robust tagged format (recommended for 8B models). native: Ollama's JSON tool API, MCP tools included. The GPU engine and Ask and Plan mode always use the tagged format.", listOf("prompt", "native")),
            Entry("max_iterations", "Max tool steps per message", "number"),
            Entry("auto_approve_commands", "Auto-run commands", "bool", "Commands run without asking. Destructive commands, denylisted commands and moves to the trash still ask, and so do MCP tools while MCP tools protection is on."),
            Entry("mcp_tool_protection", "MCP tools protection", "bool", "As in Cursor: tools from MCP servers ask before they run, even with Auto-run on. A tool you allow for one chat (Always allow on its card) runs there without asking."),
            Entry("external_file_protection", "External-file protection", "bool", "The agent may read and change files anywhere on this computer. With this on, it asks before it writes or edits a file outside the project folder (its commands are covered by Auto-run, not by this). System files and keys always ask."),
            Entry("semantic_index", "Codebase index (search by meaning)", "bool", "Forge turns your code into embedding vectors with a small separate model (about 250 MB, started only when needed and only when memory allows), so codebase search and @codebase find code by what it does, not only by its words. Indexing pauses while a chat is running."),
            Entry("repo_map_chars", "Repo map size (characters)", "number", "Outline of your workspace given to the model, refreshed at most every 15 minutes per chat. Smaller is faster."),
            Entry("prewarm", "Pre-warm model on new chat", "bool", "Processes the system prompt in the background so the first reply is fast."),
            Entry("verbose", "Verbose mode", "bool", "Shows the raw model traffic, context trimming and timing as small gray lines in the chat. The Agent log in the bottom panel records them either way."))),
        Group("Editor", "The code editor: language servers, AI Tab completions, the minimap and the font size.", listOf(
            Entry("lsp", "Language servers (code intelligence)", "bool", "Suggestions, hover information, go to definition, find references, rename and live errors for Python, JavaScript, TypeScript, shell, JSON, CSS, HTML, C and C++, Go, Rust, Java, Kotlin and XML (each when its server is installed). Each language server starts only when you open a file of that language and only when memory allows; turning this off stops the running ones."),
            Entry("kotlin_project_model", "Kotlin project model", "select", "How the Kotlin language server learns your project. gradle: a full Gradle import at every start (exact libraries, but a second large Java process; needs about 4.7 GB free on the Googlebook). light: Forge writes the project description itself from your source folders, the JDK, the Kotlin library, android.jar and libraries Gradle already downloaded (about half the memory, 4.1 GB). auto: gradle when there is room, otherwise light.", listOf("auto", "light", "gradle")),
            Entry("tab_completions", "AI Tab completions while typing", "bool", "After a pause in typing, the model suggests how the line goes on, in gray; Tab inserts it. Alt backslash asks for one at any time, even when this is off."),
            Entry("completion_delay_ms", "AI Tab completion delay (ms)", "number", "How long typing must pause before a suggestion is asked for."),
            Entry("render_whitespace", "Render whitespace", "bool", "Spaces and tabs show as faint dots and arrows in every editor."),
            Entry("minimap", "Minimap", "bool", "A small map of the whole file on the right of the editor, with problems, git changes and review marks; click or drag it to scroll."),
            Entry("preview_tabs", "Preview tabs", "bool", "A single click in the Explorer opens a file in a preview tab (its name in italics) that the next one replaces, as in Cursor. Editing the file, a double click on the tab or Ctrl K Enter keeps it."),
            Entry("skills_claude_codex", "Skills: also load Claude's and Codex's skill folders", "bool", "Like Cursor, Forge loads skills (folders with a SKILL.md) from .cursor/skills, .agents/skills and .forge/skills in the project and in your home folder, and with this on also from .claude/skills and .codex/skills. The agent sees each skill's name and description and loads one when your request fits; you run one with / and its name."),
            Entry("font_size", "Editor font size", "number", "The size of the code in the editor and the terminal, in points (13 is the default)."))),
        Group("Virtual devices", "", listOf(
            Entry("vd_updates", "Virtual device images: updates and upgrades", "select", "What Forge does daily for every emulator image: check shows what is available in the Android view; install also downloads and installs updates and upgrades (one at a time, never while that device runs); install-move also moves stopped devices to the newest installed version of their family; off never checks. Check now and Update and upgrade everything are in the Android view.", listOf("check", "install", "install-move", "off")))),
    )

    private lateinit var page: LinearLayout
    private lateinit var form: LinearLayout
    private lateinit var userTab: TextView
    private lateinit var folderTab: TextView
    private lateinit var scopeHint: TextView
    private lateinit var engineCardView: View

    /** The server's settings payload: effective, user, folder, folder_keys and folder_path. */
    internal var payload = JSONObject()
        private set
    /** "user" or "folder": which scope the page shows and saves to. Remembered across openings of the page. */
    internal var scope = "user"
        private set
    private var models: List<String> = emptyList()
    /** Called after a save so every row shows the value of the current scope. */
    private val refreshers = ArrayList<() -> Unit>()
    private var extras: SettingsExtras? = null

    // GPU engine card
    private lateinit var engineState: TextView
    private lateinit var engineModels: TextView
    private lateinit var engineMemory: TextView
    private var started = false
    private var ticks = 0
    private val engineTask = object : Runnable { override fun run() { refreshEngine(); if (started) Api.main.postDelayed(this, 4000) } }
    /** The slow tick (every 5 seconds) drives the polling cards of the extras: index, vision and docs. */
    private val slowTask = object : Runnable { override fun run() { ticks++; extras?.tick(ticks); if (started) Api.main.postDelayed(this, 5000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.loadTheme(this)
        super.onCreate(savedInstanceState)
        title = "Forge settings"
        scope = if (getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("scope", "user") == "folder") "folder" else "user"
        val c = this
        val outer = FrameLayout(c)
        outer.setBackgroundColor(Ui.bg)
        page = Ui.column(c)
        val widthDp = minOf(resources.configuration.screenWidthDp, 780)
        page.layoutParams = FrameLayout.LayoutParams(Ui.dp(c, widthDp), Ui.wrap, Gravity.CENTER_HORIZONTAL)
        page.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 24))
        val scroll = Ui.scroll(c, page)
        pageScroll = scroll
        scroll.isFillViewport = false
        outer.addView(scroll, FrameLayout.LayoutParams(Ui.match, Ui.match))
        setContentView(outer)
        // Snapshot mode (see Snapshot.kt): the page renders itself once its values have arrived.
        intent?.getStringExtra("snapshot")?.let { name -> Api.main.postDelayed({ Snapshot.render(outer, name) }, 4000) }

        val heading = Ui.text(c, "Settings", 18f, Ui.fg, bold = true)
        heading.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        val head = Ui.row(c, heading, Ui.button(c, "Close") { finish() })
        head.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4))
        page.addView(head)

        // The scope tabs, like the web page's .scope-tabs: two equal buttons, the active one in the accent colors.
        userTab = scopeTab("User", "Your settings for every folder") { setScope("user") }
        folderTab = scopeTab("Folder", "Overrides for this folder only, saved in .forge/settings.json inside it") { setScope("folder") }
        val tabs = Ui.row(c, userTab, gapView(c, 4), folderTab)
        tabs.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 2))
        page.addView(tabs)
        scopeHint = Ui.text(c, "", 11.5f, Ui.fg3)
        scopeHint.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 6))
        page.addView(scopeHint)

        engineCardView = engineCard()
        page.addView(engineCardView.also { it.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { p -> p.topMargin = Ui.dp(c, 8) } })

        form = Ui.column(c)
        page.addView(form)
        page.addView(Ui.text(c, "Forge app " + MainActivity.VERSION + ", native.", 11f, Ui.fg3).also { it.setPadding(0, Ui.dp(c, 18), 0, 0) })
        paintScopeTabs()
        loadSettings()
    }

    override fun onStart() { super.onStart(); started = true; Api.main.post(engineTask); Api.main.postDelayed(slowTask, 5000) }
    override fun onStop() { super.onStop(); started = false; Api.main.removeCallbacks(engineTask); Api.main.removeCallbacks(slowTask) }
    override fun onDestroy() { super.onDestroy(); extras?.stop() }

    // ------------------------------------------------------------------ scopes
    private fun scopeTab(label: String, tooltip: String, onClick: () -> Unit): TextView {
        val t = Ui.text(this, label, 12f, Ui.fg2)
        t.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        t.gravity = Gravity.CENTER
        t.isSingleLine = true
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6))
        t.tooltipText = tooltip
        t.isClickable = true; t.isFocusable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun paintScopeTabs() {
        for ((t, name) in listOf(userTab to "user", folderTab to "folder")) {
            val active = scope == name
            t.background = Ui.rounded(if (active) Ui.accentSoft else Ui.bg3, 6, if (active) Ui.accent else Ui.border)
            t.setTextColor(if (active) Ui.accent2 else Ui.fg2)
        }
        val folder = folderPath()
        folderTab.text = "Folder: " + folderName(folder)
        scopeHint.text = if (scope == "user") "These settings apply to every folder unless a folder overrides them."
            else "Changes here apply only to $folder and are saved in .forge/settings.json inside it. Settings without an override follow your User settings. Engine and connection settings are machine-wide and appear only under User."
        engineCardView.visibility = if (scope == "user") View.VISIBLE else View.GONE
    }

    private fun setScope(s: String) {
        if (s == scope) return
        scope = s
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("scope", s).apply()
        paintScopeTabs()
        renderForm()
    }

    /** The folder these settings belong to: the server's folder_path, or this window's folder before the payload arrived. */
    internal fun folderPath(): String {
        val p = payload.optString("folder_path")
        return if (p.isNotEmpty()) p else (if (Api.folder.isEmpty()) "the default folder" else Api.folder)
    }

    private fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    internal fun isFolderKey(key: String): Boolean {
        val a = payload.optJSONArray("folder_keys") ?: return false
        for (i in 0 until a.length()) if (a.optString(i) == key) return true
        return false
    }

    internal fun hasFolderOverride(key: String): Boolean = payload.optJSONObject("folder")?.has(key) == true

    /**
     * The value a row shows: under Folder the folder's override when there is one, otherwise the User value;
     * under User the User value. The server's effective value is the fallback for a key the user file lacks.
     */
    internal fun valueOf(key: String): Any? {
        val folder = payload.optJSONObject("folder")
        if (scope == "folder" && folder != null && folder.has(key)) return folder.opt(key)
        val user = payload.optJSONObject("user")
        if (user != null && user.has(key)) return user.opt(key)
        return payload.optJSONObject("effective")?.opt(key)
    }

    internal fun boolOf(key: String): Boolean = valueOf(key) as? Boolean ?: false

    /** The strings of a list setting (the command lists) in the current scope. */
    internal fun stringListOf(key: String): List<String> {
        val a = valueOf(key) as? JSONArray ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) { val s = a.optString(i); if (s.isNotEmpty()) out.add(s) }
        return out
    }

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
            payload = r.first
            val names = ArrayList<String>()
            r.second?.optJSONArray("models")?.let { a -> for (i in 0 until a.length()) { val n = a.optJSONObject(i)?.optString("name") ?: ""; if (n.isNotEmpty()) names.add(n) } }
            // Vision and embedding models cannot chat, so the model pickers leave them out (as the chat's model menu does).
            models = ArrayList(names.filter { !Regex("vl|embed", RegexOption.IGNORE_CASE).containsMatchIn(it) })
            paintScopeTabs()
            renderForm()
        }
    }

    /** Builds the rows of the current scope and the sections below them. */
    private fun renderForm() {
        form.removeAllViews()
        refreshers.clear()
        extras?.stop()
        for (g in groups) {
            val entries = if (scope == "folder") g.entries.filter { isFolderKey(it.key) } else g.entries
            if (entries.isEmpty()) continue
            form.addView(groupHeader(g.title))
            if (g.note.isNotEmpty()) form.addView(Ui.text(this, g.note, 11f, Ui.fg3))
            for (en in entries) form.addView(renderRow(en))
        }
        val x = SettingsExtras(this, scope)
        extras = x
        x.build(form)
        scrollToSection()
        // The real list of every key binding lives in the main window (built from its bindings), so it can never go
        // stale here; this opens it.
        form.addView(groupHeader("Shortcuts"))
        form.addView(Ui.text(this, "Every keyboard shortcut of the window, the editor, the chat and the terminal is listed, with a search, in Keyboard shortcuts (Ctrl K, then Ctrl S, in the main window).", 11f, Ui.fg3))
        form.addView(Ui.row(this, Ui.button(this, "Show the keyboard shortcuts") { MainActivity.showShortcutsNext = true; finish() }).also { it.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6)) })
    }

    // ------------------------------------------------------------------ opening at a section (openSettingsAt)
    private var pageScroll: android.widget.ScrollView? = null
    private val headers = HashMap<String, View>()
    private var sectionDone = false

    /** The section names other parts of Forge open Settings at, and the group each one means. */
    private val sections = mapOf("ai" to "model and engine", "index" to "codebase index", "modes" to "custom modes",
        "mcp" to "mcp servers", "memories" to "memories", "rules" to "rules for the ai", "hooks" to "hooks", "engine" to "gpu engine")

    /** Scrolls to the section the window asked for (once, after the page and its cards are built). */
    private fun scrollToSection() {
        if (sectionDone) return
        val want = intent?.getStringExtra("section") ?: return
        val title = sections[want] ?: want.lowercase()
        Api.main.postDelayed({
            val h = headers[title] ?: headers.entries.firstOrNull { it.key.startsWith(title) }?.value ?: return@postDelayed
            var y = 0; var v: View? = h
            while (v != null && v !== page) { y += v.top; v = v.parent as? View }
            pageScroll?.smoothScrollTo(0, maxOf(0, y - Ui.dp(this, 8)))
            sectionDone = true
        }, 350)
    }

    internal fun groupHeader(title: String): TextView {
        val t = Ui.text(this, title.uppercase(), 11f, Ui.accent2)
        headers[title.lowercase()] = t
        t.letterSpacing = 0.08f
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4))
        return t
    }

    /**
     * Saves one value in the current scope. A key the folder may not override goes to User, as on the web page.
     * The engine is switched through /api/engine/select first, because the server refuses "gpu" while the engine
     * is not reachable. [done] gets true when the server accepted the value; the rows then show the new payload.
     */
    internal fun saveSetting(key: String, value: Any, label: String, done: (Boolean) -> Unit) {
        val target = if (scope == "folder" && isFolderKey(key)) "folder" else "user"
        Api.async("settings-save", {
            if (key == "engine") Api.post("/api/engine/select", JSONObject().put("engine", value))
            Api.post("/api/settings", JSONObject().put(key, value).put("_scope", target))
        }) { r, e ->
            if (r == null) {
                Ui.toast(this, "Not saved: " + (e?.message ?: "unknown error"))
                done(false)
                return@async
            }
            payload = r
            Ui.toast(this, "Saved " + label + (if (target == "folder") " for this folder" else ""))
            for (f in refreshers) f()
            done(true)
        }
    }

    /** The Folder tab's cards (custom modes, the command lists): the override goes and the whole page is built again. */
    internal fun resetFolderCard(key: String, label: String) {
        Api.async("settings-reset", { Api.post("/api/settings", JSONObject().put("_scope", "folder").put("_unset", JSONArray().put(key))) }) { r, e ->
            if (r == null) { Ui.toast(this, "Not reset: " + (e?.message ?: "unknown error")); return@async }
            payload = r
            Ui.toast(this, label + " follows your User settings again")
            renderForm()
        }
    }

    /** Removes a folder override: the row follows the User value again. */
    private fun resetFolderSetting(key: String, label: String) {
        Api.async("settings-reset", { Api.post("/api/settings", JSONObject().put("_scope", "folder").put("_unset", JSONArray().put(key))) }) { r, e ->
            if (r == null) { Ui.toast(this, "Not reset: " + (e?.message ?: "unknown error")); return@async }
            payload = r
            Ui.toast(this, label + " follows your User settings again")
            for (f in refreshers) f()
        }
    }

    private fun current(en: Entry): String {
        val v = valueOf(en.key) ?: return ""
        return if (v is Double && v == Math.floor(v) && !en.decimal) v.toLong().toString() else v.toString()
    }

    // ------------------------------------------------------------------ rows and controls
    /**
     * The small note after a label, like the web page's .ovr and .inh spans: under Folder, "Reset" (a link that
     * removes the override) or "(from User)"; under User, "(overridden in this folder)" when the folder has its own value.
     */
    private fun annotate(annot: TextView, en: Entry) {
        val overridden = hasFolderOverride(en.key)
        when {
            scope == "folder" && overridden -> {
                annot.text = "Reset"; annot.setTextColor(Ui.accent2); annot.isClickable = true; annot.tooltipText = "Remove this folder override"
                annot.setOnClickListener { resetFolderSetting(en.key, en.label) }
            }
            scope == "folder" -> { annot.text = "(from User)"; annot.setTextColor(Ui.fg3); annot.isClickable = false; annot.setOnClickListener(null) }
            overridden -> { annot.text = "(overridden in this folder)"; annot.setTextColor(Ui.fg3); annot.isClickable = false; annot.setOnClickListener(null) }
            else -> { annot.text = ""; annot.isClickable = false; annot.setOnClickListener(null) }
        }
        annot.visibility = if (annot.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderRow(en: Entry): View {
        val c = this
        val row = Ui.column(c)
        row.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8))
        val label = Ui.text(c, en.label, 12.5f, Ui.fg2)
        val annot = Ui.text(c, "", 11f, Ui.fg3)
        annot.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0)
        annotate(annot, en)
        refreshers.add { annotate(annot, en) }
        val hint = if (en.hint.isEmpty()) null else Ui.text(c, en.hint, 11f, Ui.fg3)
        when (en.type) {
            "bool" -> {
                // An inline row: the label on the left, the switch on the right, like .srow.inline on the web page.
                label.layoutParams = Ui.lp(0, Ui.wrap, 1f)
                val toggle = Toggle(c, boolOf(en.key))
                toggle.onChange = { on -> saveSetting(en.key, on, en.label) { ok -> if (!ok) toggle.set(!on, false) } }
                refreshers.add { toggle.set(boolOf(en.key), false) }
                row.addView(Ui.row(c, label, annot, toggle))
            }
            "select", "model" -> {
                row.addView(labelRow(label, annot))
                val picker = Picker(c)
                picker.show(current(en))
                picker.setOnClickListener {
                    showPicker(c, picker, optionsFor(en), current(en)) { chosen ->
                        val old = current(en)
                        if (chosen == old) return@showPicker
                        picker.show(chosen)
                        val value: Any = if (en.key == "gpu_pool_credit") (chosen.toIntOrNull() ?: 0) else chosen
                        saveSetting(en.key, value, en.label) { ok -> if (!ok) picker.show(old) }
                    }
                }
                refreshers.add { picker.show(current(en)) }
                row.addView(picker)
            }
            else -> {
                row.addView(labelRow(label, annot))
                val input = Ui.input(c, "")
                input.layoutParams = Ui.lp(Ui.match, Ui.wrap)
                input.setText(current(en))
                if (en.type == "number") {
                    input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    if (en.key.endsWith("_args") || en.key == "ollama_url") input.typeface = android.graphics.Typeface.MONOSPACE
                }
                commitOn(input, true) { commitText(en, input) }
                refreshers.add { if (!input.hasFocus()) input.setText(current(en)) }
                row.addView(input)
            }
        }
        if (hint != null) { hint.setPadding(0, Ui.dp(c, 3), 0, 0); row.addView(hint) }
        return row
    }

    private fun labelRow(label: TextView, annot: TextView): LinearLayout {
        val r = Ui.row(this, label, annot)
        r.setPadding(0, 0, 0, Ui.dp(this, 3))
        return r
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
            if ((en.min != null && d < en.min) || (en.max != null && d > en.max)) {
                Ui.toast(this, en.label + ": from " + en.min + " to " + en.max + "."); input.setText(old); return
            }
            value = if (!en.decimal && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d
        } else value = raw
        saveSetting(en.key, value, en.label) { ok -> if (!ok) input.setText(old) }
    }

    // ------------------------------------------------------------------ the GPU engine card
    private fun engineCard(): View {
        val c = this
        engineState = Ui.text(c, "", 12.5f, Ui.fg)
        engineModels = Ui.text(c, "", 11.5f, Ui.fg2)
        engineMemory = Ui.text(c, "", 11.5f, Ui.fg2)
        val buttons = actionRow(c,
            Ui.button(c, "Start engine", primary = true) { EngineService.start(c); Ui.toast(c, "Starting the GPU engine"); Api.main.postDelayed({ refreshEngine() }, 800) },
            Ui.button(c, "Stop engine") { EngineService.stop(c); Ui.toast(c, "Stopping the GPU engine"); Api.main.postDelayed({ refreshEngine() }, 800) },
            // The manual's "Use GPU engine for chats": the chats switch to this engine (the server checks that it answers).
            Ui.button(c, "Use GPU engine for chats") {
                Api.async("engine-select", { Api.post("/api/engine/select", JSONObject().put("engine", "gpu")) }) { r, e ->
                    Ui.toast(c, if (r != null) "The GPU engine answers chats now." else "Not switched: " + (e?.message ?: ""))
                    if (r != null) loadSettings()
                }
            },
            Ui.button(c, "Refresh") { refreshEngine() },
            Ui.button(c, "Engine log") { showEngineLog() })
        val card = Ui.card(c, Ui.text(c, "GPU engine", 13.5f, Ui.fg, bold = true),
            Ui.text(c, "llama.cpp with Vulkan on the Intel graphics, run by this app as a foreground service.", 11f, Ui.fg3),
            engineState.also { it.setPadding(0, Ui.dp(c, 6), 0, 0) }, engineModels, engineMemory, buttons)
        refreshEngine()
        return card
    }

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
        lateinit var dialog: Dialog
        box.addView(dialogHead(c, "Engine log") { dialog.dismiss() })
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
            }, gapView(c, 8), Ui.button(c, "Refresh") { fill(); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } },
            spacer, Ui.button(c, "Close", primary = true) { dialog.dismiss() })
        foot.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 10))
        box.addView(foot)
        dialog = darkDialog(c, box, 900)
        dialog.show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val PREFS = "forge-settings"
    }
}
