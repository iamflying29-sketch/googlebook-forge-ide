package local.forge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
        const val VERSION = "4.0"
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
    lateinit var problems: ProblemsPanel
    private val searchPanel: SearchPanel by lazy { SearchPanel(this, this) }
    private val palette by lazy { CommandPalette(this, this) }
    private lateinit var posItem: Pair<LinearLayout, TextView>
    private lateinit var langItem: Pair<LinearLayout, TextView>
    private lateinit var problemsItem: Pair<LinearLayout, TextView>
    private lateinit var reviewItem: Pair<LinearLayout, TextView>
    private lateinit var tabItem: Pair<LinearLayout, TextView>
    private lateinit var lspItem: Pair<LinearLayout, TextView>
    private lateinit var branchItem: Pair<LinearLayout, TextView>
    private lateinit var speedItem: Pair<LinearLayout, TextView>
    private var lastSpeed: JSONObject? = null
    private var tabStatus: String? = null
    /** The view a snapshot step wants pictured instead of the window (a dialog's content). */
    private var snapshotView: View? = null
    /** This window's folder; several windows share one process, so the window in front sets Api.folder. */
    private var myFolder = ""
    private val flashTask = Runnable { statusBar.right.text = "Forge $VERSION"; statusBar.right.setTextColor(Ui.fg3) }
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
        if (p != null && p.startsWith("/")) { Api.folder = p; myFolder = p }
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
        // Chat code blocks get Insert at the cursor and Apply to the open file, like Cursor's.
        Markdown.codeActions = { c, lang, body ->
            listOf(Ui.miniButton(c, R.drawable.ic_code, "Insert at the cursor") { insertCode(body) },
                Ui.miniButton(c, R.drawable.ic_wand, "Apply to the open file (shown as changes to keep or undo)") { applyCode(lang, body) })
        }
        if (intent?.getBooleanExtra("start_engine", false) == true) EngineService.start(this)
        if (intent?.getBooleanExtra("stop_engine", false) == true) EngineService.stop(this)
        connect()
        intent?.getStringExtra("snapshot")?.let { startSnapshots(it) }
    }

    /**
     * Snapshot mode, for checking the window's look while the screen is off: the steps (separated by commas) run one
     * after another, and after each one the window renders itself and sends the picture to the server (Snapshot.kt).
     * Steps: "ide", "side:<view id>", "close-side", "panel:<bottom tab number>", "open:<file path>", "chat:history",
     * "explorer", "settings".
     */
    private fun startSnapshots(spec: String) {
        val steps = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (steps.isEmpty()) return
        Api.main.postDelayed({
            Snapshot.sequence(root, steps, 3000, target = { snapshotView ?: root }, action = { step ->
                snapshotView = null
                when {
                    step.startsWith("palette:") -> { openPalette(step.removePrefix("palette:")); Api.main.postDelayed({ snapshotView = palette.lastView }, 1500) }
                    step.startsWith("search:") -> showSearch(step.removePrefix("search:"))
                    step == "split" -> editor.toggleSplit()
                    step == "preview" -> editor.togglePreview()
                    step == "reviewdemo" -> {
                        // A test of the inline review: the base is the file with a few lines taken out and one changed.
                        val p = editor.activePath(); val t = editor.textOf(p)
                        if (t != null) {
                            val lines = t.split('\n').toMutableList()
                            if (lines.size > 30) { lines.removeAt(8); lines.removeAt(8); lines[20] = lines[20] + "  # an older version of this line"; lines.add(14, "    old_line = True") }
                            editor.startReview(p, lines.joinToString("\n"), "from the snapshot test", inline = true)
                        }
                    }
                    step.startsWith("goto:") -> step.removePrefix("goto:").split(':').let { a -> editor.openAt(a[0], (a.getOrNull(1)?.toIntOrNull() ?: 1) - 1, 0) }
                    // Self-tests of the editor's popups and AI features (1-based lines): the picture shows the popup.
                    step.startsWith("t-complete:") -> step.split(':').let { a -> editor.activeSmarts()?.testCompletion(a[1].toInt() - 1, a[2].toInt()); Api.main.postDelayed({ snapshotView = editor.activeSmarts()?.popupView() }, 2600) }
                    step.startsWith("t-hover:") -> step.split(':').let { a -> editor.activeSmarts()?.testHover(a[1].toInt() - 1, a[2].toInt()); Api.main.postDelayed({ snapshotView = editor.activeSmarts()?.popupView() }, 2600) }
                    step.startsWith("t-inline:") -> step.split(':', limit = 4).let { a -> editor.activeSmarts()?.testInline(a[1].toInt() - 1, a[2].toInt() - 1, a[3]); Api.main.postDelayed({ snapshotView = editor.activeSmarts()?.popupView() }, 2000) }
                    step.startsWith("t-ghost:") -> step.split(':').let { a -> editor.activeSmarts()?.testGhost(a[1].toInt() - 1, a[2].toInt()) }
                    step == "wait" || step.startsWith("shot") -> { }
                    step.startsWith("side:") -> { val id = step.removePrefix("side:"); if (sideView != id) showSideById(id) }
                    step == "close-side" -> closeSide()
                    step.startsWith("panel:") -> { if (!bottomShown) toggleBottomPanel(); selectPanel(step.removePrefix("panel:").toIntOrNull() ?: 0) }
                    step.startsWith("open:") -> editor.openFile(step.removePrefix("open:"))
                    step == "chat:history" -> openChatHistory()
                    step == "explorer" -> toggleExplorer()
                    step == "settings" -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("snapshot", "settings"))
                }
            }, finished = {
                // A window opened only for pictures closes itself and leaves nothing in Recents.
                if (intent?.getBooleanExtra("snapshot_only", false) == true) finishAndRemoveTask()
            })
        }, 6000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("start_engine", false) == true) EngineService.start(this)
        if (intent?.getBooleanExtra("stop_engine", false) == true) EngineService.stop(this)
        intent?.getStringExtra("snapshot")?.let { startSnapshots(it) }
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
            Chrome.ActivityItem("search", R.drawable.ic_search, "Search (Ctrl Shift F)", { showSideById("search") }),
            Chrome.ActivityItem("git", R.drawable.ic_source_control, "Source control", { showSideById("git") }),
            Chrome.ActivityItem("debug", R.drawable.ic_run, "Run and debug", { showSideById("debug") }),
            Chrome.ActivityItem("android", R.drawable.ic_android, "Android and toolchains", { showSideById("android") }),
            Chrome.ActivityItem("review", R.drawable.ic_review, "AI changes (review)", { showSideById("review") }),
            Chrome.ActivityItem("notepads", R.drawable.ic_notes, "Notepads", { showSideById("notepads") }),
            Chrome.ActivityItem("docs", R.drawable.ic_docs, "Documentation", { showSideById("docs") }),
            Chrome.ActivityItem("history", R.drawable.ic_history, "Chat history", { showSideById("history") }),
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
        branchItem = statusBar.addItem(this, R.drawable.ic_source_control, "Source control", left = true) { showSideById("git") }
        branchItem.first.visibility = View.GONE
        speedItem = statusBar.addItem(this, R.drawable.ic_bolt, "The GPU engine's writing speed: click for a speed test", left = true) { showSpeedMenu(it) }
        speedItem.first.visibility = View.GONE
        problemsItem = statusBar.addItem(this, R.drawable.ic_error, "Problems (Ctrl Shift M)") { showProblems() }
        problemsItem.second.text = "0  ⚠ 0"
        reviewItem = statusBar.addItem(this, R.drawable.ic_review, "Files with changes to review: click for the next one") { editor.nextReview() }
        reviewItem.first.visibility = View.GONE
        posItem = statusBar.addItem(this, 0, "Go to line (Ctrl G)") { openPalette(":") }
        langItem = statusBar.addItem(this, 0, "The language of the file and its language server") { showLspMenu(it) }
        lspItem = langItem
        tabItem = statusBar.addItem(this, R.drawable.ic_sparkle, "AI Tab completions: click to turn them on or off (Alt \\ asks at once)") { toggleTabCompletions() }
        posItem.first.visibility = View.GONE; langItem.first.visibility = View.GONE
        ide.addView(statusBar.view)
        ForgeSettings.onChange {
            refreshTabItem()
            editor.settingsChanged()
            terminal.setFontSize(ForgeSettings.int("font_size", 13).coerceIn(8, 32) * 12.5f / 13f)
        }
        refreshTabItem()
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
        problems = ProblemsPanel(this, this)
        problems.onCounts = { e, w -> problemsItem.second.text = "$e  ⚠ $w"; problemsItem.second.setTextColor(if (e > 0) Ui.red else if (w > 0) Ui.yellow else Ui.fg2); panelTabs.setBadge(2, if (e + w > 0) (e + w).toString() else "") }
        panelViews.add(problems.view)
        panelViews.add(debugConsole.view)
        panelViews.add(logcat.view)
        for ((i, v) in panelViews.withIndex()) { content.addView(v, FrameLayout.LayoutParams(Ui.match, Ui.match)); v.visibility = if (i == 0) View.VISIBLE else View.GONE }
        panelTabs = Chrome.panelTabs(this, listOf("Terminal", "Agent log", "Problems", "Debug console", "Logcat")) { i -> selectPanel(i) }
        panelTabs.actions.addView(Ui.miniButton(this, R.drawable.ic_maximize, "Maximize the panel") { maximizeBottom() })
        panelTabs.actions.addView(Ui.miniButton(this, R.drawable.ic_close, "Close the panel (Ctrl J)") { toggleBottomPanel() })
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
        if (sideView != null) { explorerShown = true; closeSide(); return }
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

    /** The debugger and the Android view keep state (a session, device polling), so they live as long as the window. */
    val debugPanel: DebugPanel by lazy { DebugPanel(this, this) }
    val androidPanel: AndroidPanel by lazy {
        AndroidPanel(this, this).also { a ->
            a.runInTerminal = { command, label -> showTerminal(); terminal.runCommand(command, label) }
            a.attachToChat = { record -> addToChat(record) }
            a.attachDebugger = { appId -> showSideById("debug"); debugPanel.attachAndroid(appId) }
            a.openLogcat = { appId -> if (!bottomShown) toggleBottomPanel(); selectPanel(4); logcat.openFor(appId) }
        }
    }

    /** The side views by their activity bar id. */
    private fun showSideById(id: String) {
        when (id) {
            "git" -> showSide(id) { GitPanel(this, this).view }
            "debug" -> showSide(id) { debugPanel.view }
            "android" -> showSide(id) { androidPanel.view }
            "review" -> showSide(id) { ReviewPanel(this, this).view }
            "notepads" -> showSide(id) { NotepadsPanel(this, this).view }
            "docs" -> showSide(id) { DocsPanel(this, this).view }
            "history" -> showSide(id) { HistoryPanel(this, this).view }
            "search" -> showSide(id) { searchPanel.view }
        }
    }

    /** Shows one side view next to the activity bar; pressing its icon again closes it. */
    private fun showSide(id: String, make: () -> View) {
        if (sideView == id) { closeSide(); return }
        if (sideView == "android") androidPanel.onHidden()
        sideView = id
        sideHost.removeAllViews()
        sideHost.addView(make(), FrameLayout.LayoutParams(Ui.match, Ui.match))
        sideHost.visibility = View.VISIBLE
        // One side view at a time, like Cursor: the Explorer steps aside while another view is open.
        setExplorerVisible(false)
        activityBar.setActive(id)
        if (id == "android") androidPanel.onShown()
    }

    private fun closeSide() {
        if (sideView == "android") androidPanel.onHidden()
        sideView = null
        sideHost.visibility = View.GONE
        sideHost.removeAllViews()
        setExplorerVisible(explorerShown)
        activityBar.setActive(if (explorerShown) "explorer" else null)
    }

    /** Opens a file in the editor at a 1-based line and a 0-based column (problems, stack frames, search results); line 0 just opens it. */
    fun openFileAt(path: String, line: Int, col: Int = 0) {
        if (line > 0) editor.openAt(path, line - 1, col) else editor.openFile(path)
    }

    /** The path of the file in the active editor tab, relative to the folder, or "" when none is open. */
    fun activeFilePath(): String = editor.activePath()

    /** Shows the bottom panel on its DEBUG CONSOLE tab (a debug session started or wrote output). */
    fun showDebugConsole() {
        if (!bottomShown) toggleBottomPanel()
        selectPanel(3)
    }

    /** Sends a question to the chat from another panel and shows the chat; [attachment] goes with it. */
    fun quickAsk(text: String, mode: String = "", attachment: JSONObject? = null) { if (!chatShown) toggleChat(); chat.quickAsk(text, mode, attachment) }

    // ---------------------------------------------------------------- services for the panels
    fun activeEditor(): CodeEditor? = editor.activeEditor()
    fun activeFile(): String = editor.activePath()
    fun currentSymbols(): List<Lsp.Symbol> = editor.currentSymbols()
    fun editorSmarts(): EditorSmarts? = editor.activeSmarts()
    fun dirtyEditors(): Int = editor.dirtyCount()
    fun saveAll(done: () -> Unit) = editor.saveAll(done)
    fun goToFolderPath(p: String) = goToFolder(p)
    fun newWindowAt(p: String) = newWindow(p)
    fun reportCheck(path: String, text: String) = problems.reportCheck(path, text)
    fun agentChanged(runId: String, path: String) = editor.agentChanged(runId, path)
    fun reviewRun(runId: String) = editor.reviewRun(runId)
    fun keepAllReviews() = editor.keepAllReviews()

    /** The palette at the top: files, or commands with ">", lines with ":", symbols with "@" and "#". */
    fun openPalette(prefix: String) = palette.open(prefix, commands())

    fun showExplorer() {
        if (sideView != null) closeSide()
        if (!explorerShown) toggleExplorer()
    }

    fun showTerminal() { if (!bottomShown) toggleBottomPanel(); selectPanel(0) }

    private var resumed = false
    /** True while this window is in front and has the input. */
    fun inFront(): Boolean = resumed && hasWindowFocus()

    /** A notification that brings this window back (an approval waits while the window is in the background). */
    fun notifyUser(title: String, text: String) {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel("forge_chat") == null) nm.createNotificationChannel(android.app.NotificationChannel("forge_chat", "Forge chat", android.app.NotificationManager.IMPORTANCE_HIGH))
            val open = android.app.PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(this, "forge_chat").setSmallIcon(R.drawable.ic_forge_logo).setContentTitle(title)
                .setContentText(text.take(200)).setStyle(android.app.Notification.BigTextStyle().bigText(text.take(600)))
                .setContentIntent(open).setAutoCancel(true).build()
            nm.notify(1001, n)
        } catch (e: Exception) { Log.w(TAG, "notification", e) }
    }

    /** The active terminal's last lines, for @terminal. */
    fun terminalText(): String = terminal.recentText(200)

    fun showDocs() = showSideById("docs")

    /** The debugger paused on a line (or ran on, with null): the editor marks it. */
    fun showExecution(path: String?, line: Int) = editor.showExecution(path, line)

    fun activeChatId(): String = chat.activeChatId()

    /** Opens Settings scrolled to a section ("modes" for the custom modes). */
    fun openSettingsAt(section: String) { startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", section)) }

    /** Ends the inline review of a file (its changes were undone from the chat). */
    fun endReview(path: String) = editor.endReview(path)

    fun showProblems() { if (!bottomShown) toggleBottomPanel(); selectPanel(2) }

    /** Something goes into the chat as context (a file, a folder, terminal output, a screenshot). */
    fun addToChat(record: JSONObject) { if (!chatShown) toggleChat(); chat.addAttachment(record); chat.focusPrompt() }

    fun addTerminalToChat(text: String) {
        if (text.isBlank()) { Ui.toast(this, "The terminal has no output to add."); return }
        addToChat(JSONObject().put("kind", "terminal").put("text", text.takeLast(12000)).put("name", "Terminal output"))
    }

    /** The Search view, with a query or a folder to search in. */
    fun showSearch(query: String = "", include: String = "") {
        if (sideView != "search") showSideById("search")
        searchPanel.prefill(query, include)
    }

    fun terminalIn(dir: String) { showTerminal(); terminal.openIn(dir) }

    /** A short message on the right of the status bar for a few seconds. */
    fun flashStatus(msg: String) {
        statusBar.right.text = msg
        statusBar.right.setTextColor(Ui.fg2)
        Api.main.removeCallbacks(flashTask); Api.main.postDelayed(flashTask, 4000)
    }

    /** The active editor changed or its cursor moved: the position and language items of the status bar follow. */
    fun onEditorState(ed: CodeEditor?, path: String) {
        if (ed == null) { posItem.first.visibility = View.GONE; langItem.first.visibility = if (path.isEmpty()) View.GONE else View.VISIBLE; langItem.second.text = if (path.isEmpty()) "" else Highlighter.languageId(path); return }
        val (line, col) = ed.cursorLineCol()
        val sel = Math.abs(ed.edit.selectionEnd - ed.edit.selectionStart)
        posItem.second.text = "Ln " + (line + 1) + ", Col " + (col + 1) + (if (sel > 0) "  ($sel selected)" else "")
        posItem.first.visibility = View.VISIBLE
        val lang = ed.lang.id
        val client = Lsp.serverFor(path)?.let { Lsp.status().firstOrNull { s -> s.startsWith(it.first + ":") } }
        langItem.second.text = lang + (if (client != null) "  ·  " + (if (client.contains("ready")) "LSP" else "LSP starting") else "")
        langItem.first.visibility = View.VISIBLE
    }

    /** git: the branch item on the left of the status bar (hidden outside a repository). */
    fun onGit(branch: String, summary: String, changes: Int) {
        if (branch.isEmpty()) { branchItem.first.visibility = View.GONE; return }
        val ahead = Regex("ahead (\\d+)").find(summary)?.groupValues?.get(1)
        val behind = Regex("behind (\\d+)").find(summary)?.groupValues?.get(1)
        branchItem.second.text = branch + (if (ahead != null) "  ↑$ahead" else "") + (if (behind != null) "  ↓$behind" else "") + (if (changes > 0) "  ·  $changes changed" else "")
        branchItem.first.tooltipText = summary.removePrefix("## ") + "\nClick for Source control."
        branchItem.first.visibility = View.VISIBLE
    }

    fun onReviewsChanged(n: Int) {
        reviewItem.second.text = if (n == 1) "1 file to review" else "$n files to review"
        reviewItem.second.setTextColor(Ui.accent2)
        reviewItem.first.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    fun setTabStatus(s: String?) { tabStatus = s; refreshTabItem() }

    private fun refreshTabItem() {
        if (!::tabItem.isInitialized) return
        val on = ForgeSettings.bool("tab_completions", false)
        tabItem.second.text = tabStatus ?: (if (on) "Tab" else "Tab off")
        tabItem.second.setTextColor(if (tabStatus != null) Ui.accent2 else if (on) Ui.fg2 else Ui.fg3)
    }

    private fun toggleTabCompletions() {
        val on = !ForgeSettings.bool("tab_completions", false)
        ForgeSettings.set("tab_completions", on) { ok, err ->
            flashStatus(if (!ok) "Not saved: $err" else if (on) "AI Tab completions are on: pause while typing to see a suggestion, Tab inserts it." else "AI Tab completions are off. Alt \\ still asks for one.")
            refreshTabItem()
        }
    }

    /** The GPU speed item: the median writing speed of the last replies, yellow when far below the best measured. */
    private fun showSpeed(sp: JSONObject?, sees: Boolean) {
        lastSpeed = sp
        val recent = sp?.opt("recent_tps")
        if (sp == null || recent !is Number) { speedItem.first.visibility = if (sp != null) View.VISIBLE else View.GONE; speedItem.second.text = "GPU speed"; speedItem.second.setTextColor(Ui.fg3); return }
        val best = (sp.opt("best_tps") as? Number)?.toDouble() ?: 0.0
        val now = recent.toDouble()
        val slow = best > 0 && now < best * 0.75
        speedItem.second.text = String.format("%.1f tok/s", now) + (if (sees) "  ·  sees images" else "")
        speedItem.second.setTextColor(if (slow) Ui.yellow else Ui.fg2)
        speedItem.first.tooltipText = "The GPU engine writes " + String.format("%.1f", now) + " tokens per second (median of its last replies)" +
            (if (best > 0) "; the best measured for this model is " + String.format("%.1f", best) + "." else ".") +
            (if (slow) " It is slower than its best; a restart of the Googlebook brings the speed back. Click for a speed test." else " Click for a speed test.")
        speedItem.first.visibility = View.VISIBLE
    }

    private fun showSpeedMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        val test = menu.menu.add("Run a speed test now (one reply of 128 tokens)")
        val why = menu.menu.add("Why can the GPU get slower?")
        menu.setOnMenuItemClickListener { item ->
            when (item) {
                test -> {
                    flashStatus("Speed test running on the GPU engine…")
                    Api.async("bench", { Api.post("/api/engine/bench", JSONObject(), 320000) }) { r, e ->
                        if (r == null) { flashStatus("Speed test failed: " + (e?.message ?: "")); return@async }
                        val sp = r.optJSONObject("speed")
                        val best = sp?.optDouble("best_tps", 0.0) ?: 0.0
                        AlertDialog.Builder(this).setTitle("GPU speed test")
                            .setMessage("Writing: " + String.format("%.2f", r.optDouble("gen_tps")) + " tokens per second (" + r.optInt("tokens") + " tokens).\n" +
                                "Reading the prompt: " + String.format("%.1f", r.optDouble("prompt_tps")) + " tokens per second.\n" +
                                (if (best > 0) "Best measured for this model: " + String.format("%.2f", best) + " tokens per second." else ""))
                            .setPositiveButton("Close", null).show()
                        showSpeed(sp, false)
                    }
                }
                why -> AlertDialog.Builder(this).setTitle("Why the GPU can get slower")
                    .setMessage("Measured on 2026-10-20 after 9 days without a restart: the GPU read the model's weights at about 10 GB per second, " +
                        "while a processor core read memory at 30 GB per second at the same time, and the Googlebook's memory was badly fragmented " +
                        "(98 percent of its compaction attempts failed). The same small vision test that wrote 19 tokens per second on October 14 wrote 8 on October 20.\n\n" +
                        "A restart brings the speed back: 3 minutes after a restart on 2026-10-20 the speed test wrote 16.7 tokens per second, " +
                        "against 4.7 before it. The slowdown builds up with uptime; the explanation that fits is fragmented memory, which leaves " +
                        "the graphics driver only small memory pages, so the GPU spends its time translating addresses. When this item turns " +
                        "yellow, restart the Googlebook when it suits you.\n\n" +
                        "Forge now avoids the churn that fragments memory: the GPU engine looks at images itself instead of unloading for a second model, " +
                        "and the Linux VM no longer hands its cache back on a schedule.")
                    .setPositiveButton("Close", null).show()
            }
            true
        }
        menu.show()
    }

    private fun showLspMenu(anchor: View) {
        val path = editor.activePath()
        val menu = android.widget.PopupMenu(this, anchor)
        val status = Lsp.status()
        menu.menu.add(if (status.isEmpty()) "No language server is running" else "Running: " + status.joinToString(", ")).isEnabled = false
        val restart = if (path.isNotEmpty() && Lsp.serverFor(path) != null) menu.menu.add("Restart the language server of this file") else null
        val toggle = menu.menu.add(if (Lsp.enabled) "Turn the language servers off" else "Turn the language servers on")
        menu.setOnMenuItemClickListener { item ->
            when {
                item === restart -> { Lsp.restart(path); val t = editor.textOf(path); if (t != null) Lsp.open(path, t); flashStatus("Restarting the language server for " + path.substringAfterLast('/')) }
                item === toggle -> ForgeSettings.set("lsp", !Lsp.enabled) { ok, _ -> if (ok) { if (!Lsp.enabled) Lsp.shutdownAll(); flashStatus(if (Lsp.enabled) "Language servers on. They start when you open a file." else "Language servers off.") } }
            }
            true
        }
        menu.show()
    }

    private var bottomMaximized = false
    private var bottomBeforeMax = 0
    private fun maximizeBottom() {
        if (!bottomShown) toggleBottomPanel()
        val lp = bottomPanel.layoutParams as LinearLayout.LayoutParams
        if (!bottomMaximized) { bottomBeforeMax = bottomHeight; bottomHeight = (center.height - Ui.dp(this, 60)).coerceAtLeast(Ui.dp(this, 200)) } else bottomHeight = bottomBeforeMax
        bottomMaximized = !bottomMaximized
        lp.height = bottomHeight; bottomPanel.layoutParams = lp
    }

    /** Every command of the palette (Ctrl Shift P), with its shortcut. */
    fun commands(): List<CommandPalette.Command> {
        fun C(label: String, hint: String = "", run: () -> Unit) = CommandPalette.Command(label, hint, run)
        val sel = { editor.activeSmarts() }
        return listOf(
            C("New chat", "Ctrl N") { if (!chatShown) toggleChat(); chat.newChatTab() },
            C("Toggle the AI panel", "Ctrl I") { toggleChat() },
            C("Add the selection to the chat", "Ctrl L") { addSelectionToChat() },
            C("Edit the selection with AI", "Ctrl K") { sel()?.openInlineEdit() ?: Ui.toast(this, "Open a file first.") },
            C("Toggle the Explorer", "Ctrl B") { toggleExplorer() },
            C("Toggle the bottom panel", "Ctrl J") { toggleBottomPanel() },
            C("Toggle the terminal", "Ctrl `") { toggleTerminal() },
            C("New terminal", "Ctrl Shift `") { showTerminal(); terminal.addTerminal() },
            C("Terminal: write a command with AI", "Ctrl K in the terminal") { showTerminal(); terminal.openAi() },
            C("Terminal: add the output to the chat") { addTerminalToChat(terminal.recentText(120)) },
            C("Open a folder", "Ctrl O") { openFolderDialog() },
            C("New window", "Ctrl Shift N") { newWindow() },
            C("Open a path anywhere on this computer") { openPalette("/") },
            C("Add a folder to this window (several folders in one window)") { openFolderDialog() },
            C("New file") { editor.newFileAt("") },
            C("Save", "Ctrl S") { editor.saveActive() },
            C("Save all", "Ctrl Shift S") { editor.saveAll(null) },
            C("Close the editor tab", "Ctrl W") { editor.closeActive() },
            C("Split the editor", "Ctrl \\") { editor.toggleSplit() },
            C("Markdown: open the preview to the side", "Ctrl Shift V") { editor.togglePreview() },
            C("Go to a file", "Ctrl P") { openPalette("") },
            C("Go to a line", "Ctrl G") { openPalette(":") },
            C("Go to a symbol in the file", "Ctrl Shift O") { openPalette("@") },
            C("Go to a symbol in the project", "Ctrl T") { openPalette("#") },
            C("Go to the definition", "F12") { sel()?.goToDefinition() },
            C("Peek the definition", "Alt F12") { sel()?.peekDefinition() },
            C("Go to the implementation", "Ctrl F12") { sel()?.goToImplementation() },
            C("Find all references", "Shift F12") { sel()?.references() },
            C("Rename the symbol", "F2") { sel()?.renameDialog() },
            C("Format the document", "Shift Alt F") { sel()?.format() },
            C("Quick fix", "Ctrl .") { sel()?.codeActions() },
            C("Trigger an AI completion now", "Alt \\") { sel()?.requestGhost(true) },
            C("Toggle AI Tab completions") { toggleTabCompletions() },
            C("Search in files", "Ctrl Shift F") { showSearch() },
            C("Replace in files", "Ctrl Shift H") { showSearch() },
            C("Show the problems", "Ctrl Shift M") { showProblems() },
            C("Fix all problems with AI") { val lines = problems.allLines(); if (lines.isEmpty()) Ui.toast(this, "There are no problems.") else quickAsk("Fix these errors and warnings. Edit the files directly and keep the behavior the same.", "agent", JSONObject().put("kind", "text").put("title", "Problems").put("name", "Problems").put("text", lines.joinToString("\n"))) },
            C("Show the AI changes", "Ctrl Shift G G") { showSideById("review") },
            C("Review: open the next file with agent changes") { editor.nextReview() },
            C("Review: keep every pending agent change") { editor.keepAllReviews() },
            C("Show the chat history", "Ctrl Shift H") { showSideById("history") },
            C("Source control", "Ctrl Shift G") { showSideById("git") },
            C("Run and debug", "Ctrl Shift D") { showSideById("debug") },
            C("Debug: start debugging the open file", "F5") { debugPanel.start(false) },
            C("Debug: run without debugging", "Ctrl F5") { debugPanel.start(true) },
            C("Debug: stop", "Shift F5") { debugPanel.stop() },
            C("Debug: toggle a breakpoint", "F9") { toggleBreakpointAtCursor() },
            C("Android and toolchains") { showSideById("android") },
            C("Android: a screenshot of the target device to the chat") { androidPanel.screenshotToChat(null) },
            C("Android: logcat") { if (!bottomShown) toggleBottomPanel(); selectPanel(4) },
            C("New Jupyter notebook") { editor.newFileAt("notebook.ipynb") },
            C("Notepads") { showSideById("notepads") },
            C("Documentation for @docs") { showSideById("docs") },
            C("Agent log", "Ctrl Shift U") { if (!bottomShown) toggleBottomPanel(); selectPanel(1) },
            C("Debug console", "Ctrl Shift Y") { showDebugConsole() },
            C("Voice input", "Ctrl Shift Space") { chat.dictateIntoPrompt() },
            C("Export the chat as Markdown") { chat.exportChatPublic() },
            C("Toggle the verbose mode of the chat") { chat.toggleSettingPublic("verbose") },
            C("Toggle the thinking display") { chat.toggleSettingPublic("thinking") },
            C("Chat with @codebase context") { if (!chatShown) toggleChat(); chat.insertText("@codebase ") },
            C("Chat with @web context") { if (!chatShown) toggleChat(); chat.insertText("@web ") },
            C("Settings", "Ctrl ,") { openSettings() },
            C("Restart the language server of this file") { val p = editor.activePath(); if (p.isEmpty()) Ui.toast(this, "Open a file first.") else { Lsp.restart(p); editor.textOf(p)?.let { Lsp.open(p, it) } } },
            C("Refresh the Explorer", "Ctrl R") { editor.refresh() },
            C("Unload the model from memory") { Api.async("unload", { Api.post("/api/unload", JSONObject()) }) { r, e -> flashStatus(if (r != null) "Model unloaded. It loads again on the next message." else "Not unloaded: " + (e?.message ?: "")) } },
        )
    }

    /** A chat code block goes in at the editor's cursor (replacing the selection). */
    private fun insertCode(code: String) {
        val ed = editor.activeEditor() ?: run { Ui.toast(this, "Open a file first, then click Insert."); return }
        val a = minOf(ed.edit.selectionStart, ed.edit.selectionEnd).coerceAtLeast(0)
        val b = maxOf(ed.edit.selectionStart, ed.edit.selectionEnd).coerceAtLeast(0)
        ed.replaceRange(a, b, code)
        ed.focus()
    }

    /**
     * Apply from the chat: a block that is the whole file replaces it; a smaller one is merged into the file by the
     * model (Ctrl K over the whole file). Either way the result waits as changes to keep or undo, hunk by hunk.
     */
    private fun applyCode(lang: String, code: String) {
        val path = editor.activePath()
        val ed = editor.activeEditor()
        if (ed == null || path.isEmpty()) { Ui.toast(this, "Open the file the code is for, then click Apply."); return }
        val cur = ed.text()
        val curLines = cur.count { it == '\n' } + 1
        val newLines = code.count { it == '\n' } + 1
        if (cur.isBlank() || newLines >= curLines * 0.6) {
            val base = cur
            ed.replaceRange(0, cur.length, code)
            editor.startReview(path, base, "from the chat", inline = true)
            flashStatus("Applied to " + path.substringAfterLast('/') + ". Keep or undo each change; save with Ctrl S.")
            return
        }
        val fence = if (lang.isNotEmpty()) lang else ""
        editor.activeSmarts()?.openInlineEdit("Apply this change from the chat to the file and keep everything else as it is:\n```" + fence + "\n" + code + "\n```", whole = true, auto = true)
    }

    private fun addSelectionToChat() {
        if (!chatShown) toggleChat()
        editorSelection()?.let { s -> chat.addAttachment(s) }
        chat.focusPrompt()
    }

    private fun toggleBreakpointAtCursor() {
        val ed = editor.activeEditor() ?: run { Ui.toast(this, "Open a file first."); return }
        Breakpoints.toggle(editor.activePath(), ed.cursorLineCol().first + 1)
        ed.refreshGutter()
    }

    /** Opens a saved chat (the History view). */
    fun openChatSession(id: String) { if (!chatShown) toggleChat(); chat.openSession(id) }

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
            if (myFolder.isEmpty()) myFolder = Api.folder
            ForgeSettings.refresh()
            editor.restoreReviews()
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
            showSpeed(r.optJSONObject("gpu_speed"), r.optBoolean("gpu_vision", false))
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
        FolderDialog.show(this, onOpen = { path -> goToFolder(path) }, onNewWindow = { path -> newWindow(path) }, onAdd = { path -> addRoot(path) })
    }

    /** Several folders in one window: the folder shows in the Explorer next to this window's folder. */
    private fun addRoot(path: String) {
        Api.async("roots", { Api.post("/api/roots", JSONObject().put("action", "add").put("path", path)) }) { r, e ->
            if (r == null) { Ui.toast(this, "Not added: " + (e?.message ?: "")); return@async }
            editor.refresh(); showExplorer()
            flashStatus("Added " + path + " to this window.")
        }
    }

    private fun goToFolder(path: String) {
        Api.folder = path
        myFolder = path
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
        if (event.action == KeyEvent.ACTION_DOWN && loaded) {
            val inTerminal = currentFocus is TerminalView
            if (event.isCtrlPressed && !event.isAltPressed && handleShortcut(event, inTerminal)) return true
            if (!event.isCtrlPressed && !event.isAltPressed && !inTerminal && handleFunctionKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Window shortcuts, handled before the focused view sees the key so they work from the editor, the chat prompt
     * and the panels. In the terminal only the window's own keys are taken (Ctrl P, Ctrl Shift P, Ctrl `, Ctrl K
     * and a few more); Ctrl R, Ctrl W, Ctrl L, Ctrl B and the rest go to the shell as they should.
     */
    private fun handleShortcut(event: KeyEvent, inTerminal: Boolean): Boolean {
        val shift = event.isShiftPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_P -> { openPalette(if (shift) ">" else ""); return true }
            KeyEvent.KEYCODE_GRAVE -> {
                if (shift) { showTerminal(); terminal.addTerminal(); return true }
                if (inTerminal) { toggleBottomPanel(); editor.focusEditor(); return true }
                showTerminal(); terminal.focusInput(); return true
            }
            KeyEvent.KEYCODE_K -> if (inTerminal && !shift) { terminal.openAi(); return true }
            KeyEvent.KEYCODE_N -> if (shift) { newWindow(); return true }
            KeyEvent.KEYCODE_COMMA -> { openSettings(); return true }
            KeyEvent.KEYCODE_I -> if (!shift) { toggleChat(); if (chatShown) chat.focusPrompt(); return true }
            KeyEvent.KEYCODE_F -> if (shift) { showSearch(editor.selectionText().takeIf { !it.contains('\n') } ?: ""); return true }
        }
        if (inTerminal) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_S -> { if (shift) editor.saveAll(null) else editor.saveActive(); return true }
            KeyEvent.KEYCODE_E -> if (!shift) { openPalette(""); return true } else { showExplorer(); return true }
            KeyEvent.KEYCODE_T -> if (!shift) { openPalette("#"); return true }
            KeyEvent.KEYCODE_O -> { if (shift) openPalette("@") else openFolderDialog(); return true }
            KeyEvent.KEYCODE_L -> if (!shift) { addSelectionToChat(); return true }
            KeyEvent.KEYCODE_N -> if (!shift) { if (!chatShown) toggleChat(); chat.newChatTab(); return true }
            KeyEvent.KEYCODE_B -> if (!shift) { toggleExplorer(); return true }
            KeyEvent.KEYCODE_J -> if (!shift) { toggleBottomPanel(); return true }
            KeyEvent.KEYCODE_W -> if (!shift) { editor.closeActive(); return true }
            KeyEvent.KEYCODE_TAB -> { editor.cycleTab(if (shift) -1 else 1); return true }
            KeyEvent.KEYCODE_BACKSLASH -> { editor.toggleSplit(); return true }
            KeyEvent.KEYCODE_V -> if (shift && editor.activePath().let { PreviewPanel.isMarkdown(it) }) { editor.togglePreview(); return true }
            KeyEvent.KEYCODE_H -> if (shift) { showSearch(); return true }
            KeyEvent.KEYCODE_G -> if (shift) { showSideById("git"); return true }
            KeyEvent.KEYCODE_D -> if (shift) { showSideById("debug"); return true }
            KeyEvent.KEYCODE_M -> if (shift) { showProblems(); return true }
            KeyEvent.KEYCODE_U -> if (shift) { if (!bottomShown) toggleBottomPanel(); selectPanel(1); return true }
            KeyEvent.KEYCODE_Y -> if (shift) { showDebugConsole(); return true }
            // Ctrl Shift Space: voice input, except in the code editor, where it shows signature help (as in Cursor).
            KeyEvent.KEYCODE_SPACE -> if (shift && currentFocus !is CodeEditor.CodeEditText) { chat.dictateIntoPrompt(); return true }
            KeyEvent.KEYCODE_R -> if (!shift) { if (!loaded) connect() else { editor.refresh(); refreshStatus() }; return true }
            KeyEvent.KEYCODE_F5 -> return debugPanel.handleKey(event)
        }
        return false
    }

    /** F5 and the other debugger keys, and F9 for a breakpoint, outside the terminal. */
    private fun handleFunctionKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11 -> {
                // F10 and F11 step only while a debug session runs; otherwise they stay with the focused view.
                if ((event.keyCode == KeyEvent.KEYCODE_F10 || event.keyCode == KeyEvent.KEYCODE_F11) && !debugPanel.active()) return false
                return debugPanel.handleKey(event)
            }
            KeyEvent.KEYCODE_F9 -> { toggleBreakpointAtCursor(); return true }
        }
        return false
    }

    override fun onPause() { super.onPause(); resumed = false }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (myFolder.isNotEmpty() && Api.folder != myFolder) Api.folder = myFolder
        if (loaded) ForgeSettings.refresh()
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
