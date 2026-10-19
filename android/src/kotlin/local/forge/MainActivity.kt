package local.forge

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Forge's native window: the Explorer and editor, the terminal and the AI panel, built with Android views and
 * talking to the Forge server in the Linux VM. No browser anywhere. The GPU engine and the vision model are run
 * by the services of this app (EngineService, VisionControl), as before.
 *
 * The layout follows the web page it replaces: a 34 dp top bar, the activity bar on the left, the editor column
 * with the bottom panel (terminal, agent log, problems) in the middle, the AI panel on the right behind a
 * draggable splitter, and a 34 dp status bar at the bottom.
 */
class MainActivity : Activity() {
    companion object {
        const val VERSION = "3.2"
        private const val TAG = "Forge"
        private const val TERMINAL_PACKAGE = "com.android.virtualization.terminal"
        private const val PROBE_MS = 3000L
        private const val STATUS_MS = 20000L
        private const val INDEX_MS = 30000L
        private const val INDEX_BUSY_MS = 5000L
        private const val PICK_IMAGE = 42
        private const val PICK_FILE = 44
        private const val REQ_MIC = 43
    }

    private lateinit var root: FrameLayout
    private lateinit var ide: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var center: LinearLayout
    private lateinit var sideHost: FrameLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var bottomSplitter: View
    private lateinit var rightSplitter: View
    private lateinit var panelTabs: Chrome.PanelTabs
    private lateinit var topBar: Chrome.TopBar
    private lateinit var activityBar: Chrome.ActivityBar
    private lateinit var statusBar: Chrome.StatusBar
    private lateinit var waiting: LinearLayout
    private lateinit var waitingText: TextView
    private lateinit var chat: ChatPanel
    private lateinit var editor: EditorPanel
    private lateinit var terminal: TerminalTabs
    private lateinit var logcat: LogcatPanel
    private lateinit var debugConsole: DebugConsolePanel
    private val panelViews = ArrayList<View>()
    private var sideView: String? = null
    private var explorerShown = true
    private var bottomShown = true
    private var chatShown = true
    private var chatWidth = 0
    private var bottomHeight = 0
    private var started = false
    private var checking = false
    private var failedProbes = 0
    private var offlineSince = 0L
    private var terminalOpened = false
    private var loaded = false
    private var voice: Voice? = null
    private var dictateInto: EditText? = null
    private var afterMic: Runnable? = null
    private val probeTask = Runnable { connect() }
    private val statusTask = object : Runnable { override fun run() { refreshStatus(); Api.main.postDelayed(this, STATUS_MS) } }
    private val indexTask = object : Runnable { override fun run() { refreshIndex(this) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Ui.bg))
        val p = intent?.getStringExtra("path")
        if (p != null && p.startsWith("/")) Api.folder = p
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.bg)
        buildIde()
        buildWaiting()
        root.addView(ide, FrameLayout.LayoutParams(Ui.match, Ui.match))
        root.addView(waiting, FrameLayout.LayoutParams(Ui.match, Ui.match))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        setWindowTitle("")
        VisionControl.ensure(this)
        if (intent?.getBooleanExtra("start_engine", false) == true) EngineService.start(this)
        if (intent?.getBooleanExtra("stop_engine", false) == true) EngineService.stop(this)
        connect()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("start_engine", false) == true) EngineService.start(this)
        if (intent?.getBooleanExtra("stop_engine", false) == true) EngineService.stop(this)
    }

    // ---------------------------------------------------------------- layout
    private fun buildIde() {
        ide = LinearLayout(this); ide.orientation = LinearLayout.VERTICAL
        chatWidth = resources.getDimensionPixelSize(R.dimen.chat_width)
        bottomHeight = resources.getDimensionPixelSize(R.dimen.bottom_panel_height)

        topBar = Chrome.topBar(this,
            onFolder = { openFolderDialog() },
            onSearch = { editor.quickOpen() },
            onTerminal = { toggleTerminal() },
            onChat = { toggleChat() },
            onNewWindow = { newWindow() },
            onSettings = { openSettings() })
        // The model pill next to the engine chip, like the old page's model picker: the model's name and size.
        modelPill = Ui.chip(this, "model", Ui.fg3) { v -> showModelMenu(v) }
        (topBar.engineChip.parent as LinearLayout).let { p -> p.addView(modelPill, p.indexOfChild(topBar.engineChip), Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = Ui.dp(this, 6) }) }
        ide.addView(topBar.view)
        ide.addView(Ui.divider(this, false))

        editor = EditorPanel(this, this)
        terminal = TerminalTabs(this, this)
        logcat = LogcatPanel(this, this)
        debugConsole = DebugConsolePanel(this, this)
        chat = ChatPanel(this, this)

        // The activity bar on the left.
        activityBar = Chrome.activityBar(this, listOf(
            Chrome.ActivityItem("explorer", R.drawable.ic_files, "Explorer (Ctrl B)", { toggleExplorer() }),
            Chrome.ActivityItem("search", R.drawable.ic_search, "Search in files (Ctrl Shift F)", { closeSide(); editor.findInFiles() }),
            Chrome.ActivityItem("git", R.drawable.ic_source_control, "Source control", { showSide("git", R.drawable.ic_source_control, "Source control",
                "The branch, the changed files, staging and commits will live here, as on the web page.") }),
            Chrome.ActivityItem("debug", R.drawable.ic_run, "Run and debug", { showSide("debug", R.drawable.ic_run, "Run and debug",
                "Run configurations, breakpoints and the debugger for Python, Kotlin and JavaScript will live here.") }),
            Chrome.ActivityItem("android", R.drawable.ic_android, "Android and toolchains", { showSide("android", R.drawable.ic_android, "Android and toolchains",
                "Virtual devices, real devices over Wi-Fi, SDK tools and emulator images will live here.") }),
            Chrome.ActivityItem("notepads", R.drawable.ic_notes, "Notepads", { showSide("notepads", R.drawable.ic_notes, "Notepads",
                "Reusable notes that you can attach to any chat with @notepad will live here.") }),
            Chrome.ActivityItem("history", R.drawable.ic_history, "Chat history", { openChatHistory() }),
            Chrome.ActivityItem("settings", R.drawable.ic_settings, "Settings (Ctrl ,)", { openSettings() }, bottom = true),
        ))
        activityBar.setActive("explorer")

        // The side host shows the placeholder panels next to the activity bar; the explorer itself lives in the editor panel.
        sideHost = FrameLayout(this)
        sideHost.setBackgroundColor(Ui.panel)
        sideHost.visibility = View.GONE

        // The center column: editor above, the bottom panel below, a splitter between them.
        center = Ui.column(this)
        center.setBackgroundColor(Ui.bg)
        center.addView(editor.view, Ui.lp(Ui.match, 0, 1f))
        bottomSplitter = Chrome.splitter(this, vertical = false, onDrag = { delta -> resizeBottom(-delta) })
        center.addView(bottomSplitter)
        bottomPanel = buildBottomPanel()
        center.addView(bottomPanel, Ui.lp(Ui.match, bottomHeight))

        // The whole body: activity bar, side host, center, splitter, AI panel.
        body = LinearLayout(this); body.orientation = LinearLayout.HORIZONTAL
        body.addView(activityBar.view)
        body.addView(Ui.divider(this, true))
        body.addView(sideHost, Ui.lp(resources.getDimensionPixelSize(R.dimen.side_panel_width), Ui.match))
        body.addView(center, Ui.lp(0, Ui.match, 1f))
        rightSplitter = Chrome.splitter(this, vertical = true, onDrag = { delta -> resizeChat(-delta) })
        body.addView(rightSplitter)
        chat.view.setBackgroundColor(Ui.panel)
        body.addView(chat.view, Ui.lp(chatWidth, Ui.match))
        ide.addView(body, Ui.lp(Ui.match, 0, 1f))

        ide.addView(Ui.divider(this, false))
        statusBar = Chrome.statusBar(this) { openSettings() }
        statusBar.right.text = "Forge $VERSION"
        ide.addView(statusBar.view)
    }

    /** The bottom panel: TERMINAL, AGENT LOG and PROBLEMS tabs over one content area. */
    private fun buildBottomPanel(): LinearLayout {
        val col = Ui.column(this)
        col.setBackgroundColor(Ui.panel)
        val content = FrameLayout(this)
        panelViews.clear()
        panelViews.add(terminal.view)
        val log = TextView(this)
        log.typeface = android.graphics.Typeface.MONOSPACE; log.textSize = 11.5f; log.setTextColor(Ui.fg2); log.setTextIsSelectable(true)
        log.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6))
        log.text = "The raw traffic between the agent and the model, and every tool call, appears here while a reply runs.\n"
        agentLogView = log
        panelViews.add(Ui.scroll(this, log))
        panelViews.add(Chrome.placeholder(this, R.drawable.ic_close, "Problems", "Syntax checks after saves and agent edits will show up here, with a Fix with AI button."))
        panelViews.add(debugConsole.view)
        panelViews.add(logcat.view)
        for ((i, v) in panelViews.withIndex()) { content.addView(v, FrameLayout.LayoutParams(Ui.match, Ui.match)); v.visibility = if (i == 0) View.VISIBLE else View.GONE }
        panelTabs = Chrome.panelTabs(this, listOf("Terminal", "Agent log", "Problems", "Debug console", "Logcat")) { i -> selectPanel(i) }
        panelTabs.actions.addView(Ui.miniButton(this, R.drawable.ic_chat, "Ask the AI about the terminal (Ctrl L)") { if (!chatShown) toggleChat(); chat.focusPrompt() })
        panelTabs.actions.addView(Ui.miniButton(this, R.drawable.ic_plus, "New terminal") { selectPanel(0); terminal.addTerminal() })
        panelTabs.actions.addView(Ui.miniButton(this, R.drawable.ic_trash, "Kill this terminal") { selectPanel(0); terminal.killActive() })
        panelTabs.actions.addView(Ui.miniButton(this, R.drawable.ic_close, "Close panel (Ctrl J)") { toggleBottomPanel() })
        col.addView(panelTabs.view)
        col.addView(Ui.divider(this, false))
        col.addView(content, Ui.lp(Ui.match, 0, 1f))
        return col
    }

    private fun selectPanel(i: Int) {
        panelTabs.select(i)
        for ((k, v) in panelViews.withIndex()) v.visibility = if (k == i) View.VISIBLE else View.GONE
    }

    private fun buildWaiting() {
        waiting = LinearLayout(this); waiting.orientation = LinearLayout.VERTICAL; waiting.gravity = Gravity.CENTER
        waiting.setBackgroundColor(Ui.bg)
        val pad = Ui.dp(this, 24)
        waiting.addView(Ui.iconView(this, R.drawable.ic_forge_logo, 0, 56).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
        val title = Ui.text(this, "Forge", 26f, Ui.fg, bold = true); title.gravity = Gravity.CENTER; title.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4))
        waiting.addView(title, Ui.lp(Ui.match, Ui.wrap))
        val sub = Ui.text(this, "Your local AI coding IDE. Nothing leaves the machine.", 13f, Ui.fg3); sub.gravity = Gravity.CENTER
        waiting.addView(sub, Ui.lp(Ui.match, Ui.wrap))
        waitingText = Ui.text(this, "Connecting to the Forge server in the Linux Terminal.", 14f, Ui.fg2)
        waitingText.gravity = Gravity.CENTER
        waitingText.setPadding(pad, pad, pad, pad)
        waiting.addView(waitingText, Ui.lp(Ui.dp(this, 560), Ui.wrap).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        val openTerminal = Ui.button(this, "Open the Terminal") { openTerminalApp() }
        val buttons = Ui.row(this, Ui.button(this, "Check now", primary = true) { connect() }, openTerminal)
        (openTerminal.layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(this, 10)
        buttons.gravity = Gravity.CENTER
        waiting.addView(buttons, Ui.lp(Ui.match, Ui.wrap))
        val foot = Ui.text(this, "Forge $VERSION", 11f, Ui.fg3); foot.gravity = Gravity.CENTER; foot.setPadding(0, Ui.dp(this, 28), 0, 0)
        waiting.addView(foot, Ui.lp(Ui.match, Ui.wrap))
        waiting.visibility = View.GONE
    }

    // ---------------------------------------------------------------- panels the host shows and hides
    /** Shows or hides the bottom panel. The terminal tab is selected when the panel comes back. */
    fun toggleTerminal() {
        if (!bottomShown) { toggleBottomPanel(); selectPanel(0) }
        else if (panelTabs.selected != 0) selectPanel(0)
        else toggleBottomPanel()
    }

    private fun toggleBottomPanel() {
        bottomShown = !bottomShown
        bottomPanel.visibility = if (bottomShown) View.VISIBLE else View.GONE
        bottomSplitter.visibility = bottomPanel.visibility
        Ui.setActive(topBar.terminalButton, bottomShown)
    }

    fun toggleChat() {
        chatShown = !chatShown
        chat.view.visibility = if (chatShown) View.VISIBLE else View.GONE
        rightSplitter.visibility = chat.view.visibility
        Ui.setActive(topBar.chatButton, chatShown)
    }

    /** Shows or hides the Explorer tree, which is the first part of the editor panel's view. */
    private fun toggleExplorer() {
        if (sideView != null) { closeSide(); return }
        explorerShown = !explorerShown
        setExplorerVisible(explorerShown)
        activityBar.setActive(if (explorerShown) "explorer" else null)
    }

    private fun setExplorerVisible(show: Boolean) {
        val v = editor.view
        // Everything before the last child of the editor panel is the Explorer (the tree and its divider).
        if (v.childCount < 2) return
        for (i in 0 until v.childCount - 1) v.getChildAt(i).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showSide(id: String, icon: Int, title: String, body: String) {
        if (sideView == id) { closeSide(); return }
        sideView = id
        sideHost.removeAllViews()
        sideHost.addView(Chrome.placeholder(this, icon, title, body), FrameLayout.LayoutParams(Ui.match, Ui.match))
        sideHost.visibility = View.VISIBLE
        activityBar.setActive(id)
    }

    private fun closeSide() {
        sideView = null
        sideHost.visibility = View.GONE
        sideHost.removeAllViews()
        activityBar.setActive(if (explorerShown) "explorer" else null)
    }

    private fun resizeChat(delta: Int) {
        val min = Ui.dp(this, 280)
        val max = (body.width - activityBar.view.width - Ui.dp(this, 360)).coerceAtLeast(min)
        chatWidth = (chatWidth + delta).coerceIn(min, max)
        val lp = chat.view.layoutParams as LinearLayout.LayoutParams
        lp.width = chatWidth; chat.view.layoutParams = lp
    }

    private fun resizeBottom(delta: Int) {
        val min = Ui.dp(this, 80)
        val max = (center.height - Ui.dp(this, 120)).coerceAtLeast(min)
        bottomHeight = (bottomHeight + delta).coerceIn(min, max)
        val lp = bottomPanel.layoutParams as LinearLayout.LayoutParams
        lp.height = bottomHeight; bottomPanel.layoutParams = lp
    }

    /** The window title: the folder name, or "file · folder" when a file is open. The taskbar shows the folder. */
    fun setWindowTitle(name: String) {
        val folder = folderName()
        topBar.title.text = if (name.isEmpty()) folder else "$name  ·  $folder"
        topBar.folderLabel.text = folder
        title = if (name.isEmpty()) folder else "$name · $folder"
        try { setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(folder).build()) } catch (e: Exception) { Log.w(TAG, "task description", e) }
    }
    private fun folderName(): String = if (Api.folder.isEmpty()) "Forge" else Api.folder.trimEnd('/').substringAfterLast('/')
    fun refreshTree() { editor.refresh() }
    fun fileChanged(path: String) { editor.fileChanged(path) }
    fun copyToClipboard(s: String) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Forge", s)) }

    private fun openSettings() { startActivity(Intent(this, SettingsActivity::class.java)) }

    /** Opens the chat history list of the AI panel, showing the panel first if it is hidden. */
    private fun openChatHistory() {
        if (!chatShown) toggleChat()
        chat.showHistory()
    }

    // ---------------------------------------------------------------- connecting
    /** Load the IDE if the server answers, otherwise show the waiting screen and keep probing every few seconds
     *  while this window is open. Two misses in a row open the Terminal app once per outage (its VM boots in about
     *  half a minute and starts Forge with it). */
    private fun connect() {
        Api.main.removeCallbacks(probeTask)
        if (checking) return
        checking = true
        Api.async("probe", { Api.serverUp() }) { up, _ ->
            checking = false
            if (up == true) {
                failedProbes = 0; terminalOpened = false; offlineSince = 0
                waiting.visibility = View.GONE
                if (!loaded) loadIde()
            } else {
                failedProbes++
                showWaiting()
                if (started) Api.main.postDelayed(probeTask, PROBE_MS)
            }
        }
    }

    private fun showWaiting() {
        if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
        val secs = (System.currentTimeMillis() - offlineSince) / 1000
        val times = if (failedProbes == 1) "once" else "$failedProbes times"
        waitingText.text = "The Forge server in the Linux Terminal is not answering yet. Checked $times over $secs seconds. " +
            (if (terminalOpened) "The Terminal app was opened; its VM takes about half a minute to start Forge." else "Checking again every ${PROBE_MS / 1000} seconds.")
        waiting.visibility = View.VISIBLE
        statusBar.setOllama(null)
        if (!terminalOpened && failedProbes >= 2) { terminalOpened = true; openTerminalApp() }
    }

    private fun openTerminalApp() {
        val i = packageManager.getLaunchIntentForPackage(TERMINAL_PACKAGE)
        if (i == null) { Log.w(TAG, "the Terminal app is not installed"); Ui.toast(this, "The Terminal app is not installed"); return }
        try { startActivity(i) } catch (e: Exception) { Log.w(TAG, "cannot open the Terminal", e) }
    }

    private fun loadIde() {
        loaded = true
        Api.async("status", { Api.get("/api/status") }) { r, _ ->
            if (r != null && Api.folder.isEmpty()) Api.folder = r.optString("workspace")
            setWindowTitle("")
            editor.refresh(); chat.load(); terminal.connect()
            Api.main.removeCallbacks(statusTask); Api.main.post(statusTask)
            Api.main.removeCallbacks(indexTask); Api.main.post(indexTask)
        }
    }

    private fun gb(mb: Int): String = String.format("%.1f", mb / 1024.0)

    /** The status bar and the engine chip, from GET /api/status every 20 seconds. */
    private lateinit var modelPill: LinearLayout
    private var modelList: List<JSONObject> = emptyList()
    private var engineName = ""
    private var gpuModelName = ""
    private var cpuModelName = ""

    /** The model pill's text: the model that answers right now and its size. */
    private fun refreshModelPill() {
        Api.async("models", { Pair(Api.get("/api/models"), Api.get("/api/settings")) }) { r, _ ->
            if (r == null) return@async
            val arr = r.first.optJSONArray("models")
            val list = ArrayList<JSONObject>()
            if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            modelList = list
            val eff = r.second.optJSONObject("effective") ?: JSONObject()
            engineName = eff.optString("engine", "ollama")
            gpuModelName = eff.optString("gpu_model", "forge-coder")
            cpuModelName = r.first.optString("active", eff.optString("model", ""))
            val name = if (engineName == "gpu") gpuModelName else cpuModelName
            val size = list.firstOrNull { it.optString("name") == name || it.optString("name") == "$name:latest" }?.optJSONObject("details")?.optString("parameter_size") ?: ""
            Ui.setChip(modelPill, name + (if (size.isNotEmpty()) " · $size" else "") + (if (engineName == "gpu") "  GPU" else "  CPU"), if (engineName == "gpu") Ui.accent else Ui.blue, Ui.fg)
        }
    }

    private fun showModelMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        val gpu = menu.menu.add("Use the GPU engine (Intel graphics)")
        val cpu = menu.menu.add("Use Ollama in the VM (processor)")
        val heading = menu.menu.add(if (engineName == "gpu") "Model for the GPU engine:" else "Model for Ollama:")
        heading.isEnabled = false
        val items = HashMap<android.view.MenuItem, String>()
        for (mdl in modelList) {
            val n = mdl.optString("name")
            val size = mdl.optJSONObject("details")?.optString("parameter_size") ?: ""
            val current = n == (if (engineName == "gpu") gpuModelName else cpuModelName) || n == (if (engineName == "gpu") gpuModelName else cpuModelName) + ":latest"
            items[menu.menu.add((if (current) "✓ " else "    ") + n + (if (size.isNotEmpty()) " · $size" else ""))] = n
        }
        menu.setOnMenuItemClickListener { item ->
            when {
                item === gpu -> Api.async("engine-select", { Api.post("/api/engine/select", JSONObject().put("engine", "gpu")) }) { r, e -> Ui.toast(this, if (r != null) "The GPU engine answers chats now." else "Not switched: " + (e?.message ?: "")); refreshModelPill(); refreshStatus() }
                item === cpu -> Api.async("engine-select", { Api.post("/api/engine/select", JSONObject().put("engine", "ollama")) }) { r, e -> Ui.toast(this, if (r != null) "Ollama in the VM answers chats now." else "Not switched: " + (e?.message ?: "")); refreshModelPill(); refreshStatus() }
                items.containsKey(item) -> {
                    val n = items[item]!!
                    val key = if (engineName == "gpu") "gpu_model" else "model"
                    Api.async("model-set", { Api.post("/api/settings", JSONObject().put(key, n).put("_scope", "user")) }) { r, e ->
                        Ui.toast(this, if (r != null) (if (key == "gpu_model") "$n runs on the GPU engine from its next start." else "$n answers chats now.") else "Not saved: " + (e?.message ?: ""))
                        refreshModelPill()
                    }
                }
            }
            true
        }
        menu.show()
    }

    private fun refreshStatus() {
        refreshModelPill()
        Api.async("status", { Api.get("/api/status") }) { r, e ->
            if (r == null) {
                statusBar.setOllama(null); statusBar.setEngine(""); statusBar.setMemory("")
                Ui.setChip(topBar.engineChip, "Server not answering", Ui.red, Ui.red)
                if (e != null) { loaded = false; connect() }
                return@async
            }
            statusBar.setOllama(r.optBoolean("ollama", false))
            val g = r.optJSONObject("gpu_engine")
            val health = g?.optJSONObject("health")?.optString("status")?.ifEmpty { "down" } ?: "down"
            val hostMem = r.opt("host_mem_mb")
            val host = if (hostMem is Number && hostMem.toInt() >= 0) hostMem.toInt() else -1
            if (r.optString("engine") == "gpu") {
                val need = g?.opt("need_mb")
                val waitingForMemory = health != "ok" && g != null && g.optString("note").isNotEmpty()
                if (health == "ok") {
                    statusBar.setEngine("GPU engine: ready", Ui.fg2)
                    Ui.setChip(topBar.engineChip, "GPU engine ready", Ui.green)
                } else if (waitingForMemory) {
                    val numbers = if (host >= 0) " (" + gb(host) + " GB free on the Googlebook" + (if (need is Number) ", needs " + gb(need.toInt()) + " GB" else "") + ")" else ""
                    statusBar.setEngine("GPU engine: waiting for memory$numbers", Ui.accent)
                    statusBar.engine.tooltipText = g.optString("note")
                    Ui.setChip(topBar.engineChip, "GPU engine waiting for memory", Ui.accent, Ui.accent2)
                } else {
                    statusBar.setEngine("GPU engine: $health", Ui.red)
                    Ui.setChip(topBar.engineChip, "GPU engine $health", Ui.red)
                }
            } else {
                val model = r.optString("model")
                statusBar.setEngine(if (model.isEmpty()) "" else "Model: $model", Ui.fg2)
                Ui.setChip(topBar.engineChip, if (model.isEmpty()) "No model" else model, if (r.optBoolean("ollama")) Ui.green else Ui.red)
            }
            val mem = r.optJSONObject("mem")
            if (mem != null && mem.optInt("total_mb") > 0) {
                val avail = mem.optInt("available_mb")
                var text = "VM " + gb(avail) + " GB free"
                if (host >= 0) text += "  ·  Googlebook " + gb(host) + " GB free"
                if (mem.optInt("swap_used_mb") > 200) text += "  ·  swap " + mem.optInt("swap_used_mb") + " MB"
                val color = if (avail < 700 || (host in 0..2499)) Ui.red else if (avail < 1500 || (host in 0..3499)) Ui.yellow else Ui.fg2
                statusBar.setMemory(text, color)
            } else statusBar.setMemory("")
        }
    }

    /** The codebase index item of the status bar, from GET /api/index/status (every 30 seconds, 5 while indexing). */
    private fun refreshIndex(again: Runnable) {
        Api.main.removeCallbacks(again)
        Api.async("index", { Api.get("/api/index/status") }) { r, _ ->
            var busy = false
            if (r == null || !r.optBoolean("available") || !r.optBoolean("enabled")) statusBar.setIndex("")
            else {
                val st = r.optJSONObject("status")
                val state = st?.optString("state") ?: ""
                busy = state == "indexing" || state.startsWith("waiting")
                statusBar.setIndex(when {
                    state == "indexing" -> "Indexing " + st!!.optInt("done") + "/" + st.optInt("total")
                    state == "ready" -> "Indexed " + st!!.optInt("chunks") + " items"
                    state == "paused" -> "Index paused"
                    state.startsWith("waiting") -> "Index waiting"
                    state == "error" -> "Index error"
                    else -> ""
                })
                if (st != null) statusBar.index.tooltipText = "Codebase index: $state, " + st.optInt("chunks") + " chunks" + (if (st.optString("error").isNotEmpty() && st.optString("error") != "null") "\n" + st.optString("error") else "")
            }
            if (started) Api.main.postDelayed(again, if (busy) INDEX_BUSY_MS else INDEX_MS)
        }
    }

    // ---------------------------------------------------------------- folders and windows
    private fun openFolderDialog() {
        FolderDialog.show(this, onOpen = { path -> goToFolder(path) }, onNewWindow = { path -> newWindow(path) })
    }

    private fun goToFolder(path: String) {
        Api.folder = path
        Api.async("workspace", { Api.post("/api/workspace", JSONObject().put("path", path)) }) { _, _ ->
            setWindowTitle(""); editor.refresh(); chat.load(); terminal.connect()
        }
    }

    private fun newWindow(path: String = Api.folder) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("path", path)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivity(i)
    }

    // ---------------------------------------------------------------- images and voice
    fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT); i.type = "image/*"; i.addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(Intent.createChooser(i, "Attach an image"), PICK_IMAGE) } catch (e: Exception) { Ui.toast(this, "No image picker available") }
    }

    /** Any file as an attachment (the server reads text files for the model; images go to the vision model). */
    fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT); i.type = "*/*"; i.addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(Intent.createChooser(i, "Attach a file"), PICK_FILE) } catch (e: Exception) { Ui.toast(this, "No file picker available") }
    }

    /** The editor's selected text as a selection attachment, or null when nothing is selected. */
    fun editorSelection(): JSONObject? {
        val text = editor.selectionText()
        if (text.isBlank()) return null
        val path = editor.activePath()
        val ext = path.substringAfterLast('.', "")
        val lang = mapOf("kt" to "kotlin", "java" to "java", "py" to "python", "js" to "javascript", "ts" to "typescript", "json" to "json", "md" to "markdown",
            "sh" to "bash", "c" to "c", "cpp" to "cpp", "h" to "c", "html" to "html", "css" to "css", "yml" to "yaml", "yaml" to "yaml", "rs" to "rust", "go" to "go")[ext] ?: ""
        val lines = text.count { it == '\n' } + 1
        return JSONObject().put("kind", "selection").put("path", path).put("text", text).put("start", 1).put("end", lines).put("language", lang)
            .put("name", path.substringAfterLast('/') + " (selection)")
    }

    private var agentLogView: TextView? = null
    private var agentLogLines = 0
    /** The AGENT LOG tab: the raw traffic between the agent and the model, and every tool call. */
    fun agentLog(kind: String, text: String) {
        val v = agentLogView ?: return
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        v.append("$stamp  $kind  $text\n")
        agentLogLines++
        if (agentLogLines > 500) { val t = v.text.toString(); val cut = t.indexOf('\n', t.length / 4); if (cut > 0) { v.text = t.substring(cut + 1); agentLogLines = v.text.count { it == '\n' } } }
        (v.parent as? android.widget.ScrollView)?.post { (v.parent as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    @Deprecated("Android's classic result callback is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode != PICK_IMAGE && requestCode != PICK_FILE) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image.png"
        Api.async("upload", {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("cannot read the image")
            if (bytes.size > 15 * 1024 * 1024) throw Exception("the image is larger than 15 MB")
            Api.upload(name, bytes)
        }) { r, e -> if (r == null) Ui.toast(this, "Not attached: " + (e?.message ?: "")) else chat.addAttachment(r) }
    }

    fun dictate(into: EditText) {
        dictateInto = into
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            afterMic = Runnable { startVoice() }
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startVoice()
    }

    private fun startVoice() {
        if (!Voice.available(this)) { Ui.toast(this, "No speech recognition service is installed"); return }
        val v = voice ?: Voice(this) { ev -> Api.main.post { onVoice(ev) } }.also { voice = it }
        Ui.toast(this, "Listening. Speak, then press Mic again to stop.")
        v.start("")
        afterMic = null
    }

    private var voiceBase = ""
    private fun onVoice(ev: JSONObject) {
        val kind = ev.optString("type", ev.optString("kind"))
        val text = ev.optString("text", ev.optString("value", ev.optString("message")))
        val into = dictateInto ?: return
        when (kind) {
            "state" -> if (text == "listening") voiceBase = into.text.toString()
            "partial" -> into.setText((voiceBase + " " + text).trim())
            "final" -> { voiceBase = (voiceBase + " " + text).trim(); into.setText(voiceBase); into.setSelection(into.text.length) }
            "error" -> Ui.toast(this, text)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_MIC && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) afterMic?.run()
    }

    // ---------------------------------------------------------------- keys and lifecycle
    /**
     * Window shortcuts. They are handled here, before any focused EditText sees the key, so they work from the
     * editor, the terminal input and the chat prompt alike. Everything else passes through.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && !event.isAltPressed && handleShortcut(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleShortcut(event: KeyEvent): Boolean {
        val shift = event.isShiftPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_S -> if (!shift) { editor.saveActive(); return true }
            KeyEvent.KEYCODE_P -> if (!shift) { editor.quickOpen(); return true }
            KeyEvent.KEYCODE_F -> if (shift) { editor.findInFiles(); return true }
            KeyEvent.KEYCODE_L -> if (!shift) { if (!chatShown) toggleChat(); chat.focusPrompt(); return true }
            KeyEvent.KEYCODE_GRAVE -> { if (!bottomShown || panelTabs.selected != 0) { if (!bottomShown) toggleBottomPanel(); selectPanel(0) }; terminal.focusInput(); return true }
            KeyEvent.KEYCODE_O -> if (!shift) { openFolderDialog(); return true }
            KeyEvent.KEYCODE_N -> if (shift) { newWindow(); return true }
            KeyEvent.KEYCODE_COMMA -> { openSettings(); return true }
            KeyEvent.KEYCODE_B -> if (!shift) { toggleExplorer(); return true }
            KeyEvent.KEYCODE_J -> if (!shift) { toggleBottomPanel(); return true }
            KeyEvent.KEYCODE_R -> if (!shift) { if (!loaded) connect() else { editor.refresh(); refreshStatus() }; return true }
        }
        return false
    }

    override fun onStart() {
        super.onStart(); started = true
        if (!loaded) connect() else { Api.main.post(statusTask); Api.main.post(indexTask) }
    }
    override fun onStop() {
        super.onStop(); started = false
        Api.main.removeCallbacks(probeTask); Api.main.removeCallbacks(statusTask); Api.main.removeCallbacks(indexTask)
        voice?.stop()
    }
    override fun onDestroy() { super.onDestroy(); terminal.close(); logcat.close(); chat.stop() }
}
