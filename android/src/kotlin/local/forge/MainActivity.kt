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
        /** Forge windows open now (test windows are not counted); closing the last one quits Forge. */
        @Volatile var liveWindows = 0
        /** Every Forge window that exists now, test windows included (to know which folders still have a window). */
        val windows = ArrayList<MainActivity>()
        /** Settings' "Show the keyboard shortcuts": the window shows them when it comes back to the front. */
        @Volatile var showShortcutsNext = false
        /** Set once Forge is quitting, so the windows that close on the way do not start a second quit. */
        @Volatile var quitting = false

        /**
         * Everything Forge runs stops: the VM side first (POST /api/quit), then the vision model and the GPU engine,
         * the engine service, the notifications, every Forge window, and last the app's own process. Used by Quit
         * Forge and by closing the last Forge window.
         */
        fun quitEverything(app: Context, done: ((JSONObject?) -> Unit)? = null) {
            quitting = true
            Api.async("quit", {
                val r = try { Api.post("/api/quit", JSONObject(), 240000) } catch (e: Exception) { JSONObject().put("error", e.message ?: "no answer") }
                try { VisionControl.stop("Forge quit") } catch (e: Exception) { }
                try { EngineService.stopNow("Forge quit") } catch (e: Exception) { }
                r
            }) { r, _ ->
                Log.i("Forge", "quit: " + (r?.optJSONArray("steps")?.toString() ?: r?.optString("error").orEmpty()))
                try { done?.invoke(r) } catch (e: Exception) { }
                try { app.stopService(Intent(app, EngineService::class.java)) } catch (e: Exception) { }
                try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll() } catch (e: Exception) { }
                try { for (t in (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks) t.finishAndRemoveTask() } catch (e: Exception) { }
                // Nothing of Forge stays behind on the Googlebook either: the app's own process ends.
                Api.main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 700)
            }
        }

        const val VERSION = "4.1"
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
    lateinit var editor: EditorPanel; private set
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
    private val probeTask = Runnable { Api.withFolder(myFolder) { connect() } }
    private val statusTask = object : Runnable { override fun run() { Api.withFolder(myFolder) { refreshStatus() }; Api.main.postDelayed(this, STATUS_MS) } }
    private val indexTask = object : Runnable { override fun run() { Api.withFolder(myFolder) { refreshIndex(this) } } }

    /** The folder this window shows (the folder of the window in front while this one has none yet). */
    fun windowFolder(): String = myFolder.ifEmpty { Api.folder }

    /** True for the self-closing test windows (snapshot_only); they never write the window state of a folder. */
    val testWindow: Boolean get() = intent?.getBooleanExtra("snapshot_only", false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(this)
        Ui.loadTheme(this)
        if (intent?.getBooleanExtra("snapshot_only", false) != true) { counted = true; liveWindows++ }
        windows.add(this)
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
                    // search-rep:<query>|<files to include>|<replacement>
                    step.startsWith("search-rep:") -> step.removePrefix("search-rep:").split('|').let { a -> showSearch(); searchPanel.testSearch(a[0], a.getOrElse(1) { "" }, a.getOrElse(2) { "" }) }
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
                    // open-json:<path>: a file as plain text, as a notebook's "{ } JSON" button opens it
                    step.startsWith("open-json:") -> editor.openAsText(step.removePrefix("open-json:"))
                    step == "chat:history" -> openChatHistory()
                    // Chat steps for pictures of a running reply: a new chat, a question, text left in the box, Stop.
                    step == "chat:new" -> chat.newChatTab()
                    step.startsWith("chat:ask:") -> chat.quickAsk(step.removePrefix("chat:ask:"), "ask")
                    step.startsWith("chat:type:") -> chat.typeForTest(step.removePrefix("chat:type:"))
                    step.startsWith("chat:append:") -> chat.appendForTest(step.removePrefix("chat:append:"))
                    // chat:tab:<n>: shows the chat tab at that position (0 is the first), as a click on its tab does
                    step.startsWith("chat:tab:") -> chat.switchTabForTest(step.removePrefix("chat:tab:").toIntOrNull() ?: 0)
                    step == "chat:stop" -> chat.stopForTest()
                    step == "chat:select" -> chat.selectInLastMessageForTest()
                    step == "quit" -> doQuit()
                    // Key presses inside a test window, for pictures of the key bindings: "key:Ctrl+K Ctrl+0".
                    step.startsWith("key:") -> for (k in step.removePrefix("key:").split(' ')) testKey(k)
                    // Reload Window in a test window: the reloaded window runs the steps after "reload:" ("+" between them).
                    step.startsWith("reload:") -> { intent?.putExtra("snapshot", step.removePrefix("reload:").replace('+', ',')); intent?.putExtra("path", myFolder); recreate() }
                    step == "explorer" -> toggleExplorer()
                    step == "settings" -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("snapshot", "settings"))
                    // settings-at:<section>: Settings opened at a section, as Ctrl Shift J and Edit modes open it
                    step.startsWith("settings-at:") -> step.removePrefix("settings-at:").let { sec -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", sec).putExtra("snapshot", "settings-$sec")) }
                    // folder:<path>: Open folder in this window (the window is built again for that folder)
                    step.startsWith("folder:") -> goToFolder(step.removePrefix("folder:"))
                }
            }, finished = {
                // A window opened only for pictures closes itself and leaves nothing in Recents.
                // A window that reloaded itself hands the task to its successor, so the old one must not close it.
                if (!isDestroyed && intent?.getBooleanExtra("snapshot_only", false) == true) finishAndRemoveTask()
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
            onChat = { if (agentLayout) toggleAgentLayout() else toggleChat() },
            onNewWindow = { newWindow() },
            onSettings = { openSettings() },
            onQuit = { quitForge() })
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
            Chrome.ActivityItem("search", R.drawable.ic_search, "Search (Ctrl Shift F)", { showSideById("search", toggle = true) }),
            Chrome.ActivityItem("git", R.drawable.ic_source_control, "Source control", { showSideById("git", toggle = true) }),
            Chrome.ActivityItem("debug", R.drawable.ic_run, "Run and debug", { showSideById("debug", toggle = true) }),
            Chrome.ActivityItem("android", R.drawable.ic_android, "Android and toolchains", { showSideById("android", toggle = true) }),
            Chrome.ActivityItem("review", R.drawable.ic_review, "AI changes (review)", { showSideById("review", toggle = true) }),
            Chrome.ActivityItem("notepads", R.drawable.ic_notes, "Notepads", { showSideById("notepads", toggle = true) }),
            Chrome.ActivityItem("memories", R.drawable.ic_bulb, "Memories (global and this project)", { showSideById("memories", toggle = true) }),
            Chrome.ActivityItem("docs", R.drawable.ic_docs, "Documentation", { showSideById("docs", toggle = true) }),
            Chrome.ActivityItem("history", R.drawable.ic_history, "Chat history", { showSideById("history", toggle = true) }),
            Chrome.ActivityItem("settings", R.drawable.ic_settings, "Settings (Ctrl ,)", { openSettings() }, bottom = true),
        ))
        activityBar.setActive("explorer")

        // The side host shows the side views (search, source control, debug and the rest) next to the activity bar; the explorer itself lives in the editor panel.
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
        statusBar = Chrome.statusBar(this) { openSettingsAt("index") }
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
        encItem = statusBar.addItem(this, 0, "The file's encoding: click to reopen or save it with another one") { encodingMenu() }
        eolItem = statusBar.addItem(this, 0, "The file's line ending: click to change it") { eolMenu() }
        encItem.first.visibility = View.GONE; eolItem.first.visibility = View.GONE
        tabItem = statusBar.addItem(this, R.drawable.ic_sparkle, "AI Tab completions: click to turn them on or off (Alt \\ asks at once)") { toggleTabCompletions() }
        posItem.first.visibility = View.GONE; langItem.first.visibility = View.GONE
        ide.addView(statusBar.view)
        settingsListener = ForgeSettings.onChange { f ->
            if (f == windowFolder()) Api.withFolder(f) {
                refreshTabItem()
                editor.settingsChanged()
                terminal.setFontSize(ForgeSettings.int("font_size", 13).coerceIn(8, 32) * 12.5f / 13f)
                askFolderTrust(f)
            }
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
        panelTabs.actions.addView(Ui.miniButton(this, R.drawable.ic_maximize, "Maximize the panel (press again to restore it)") { maximizeBottom() })
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

    /** Shows the AI panel when it is hidden (dictation and other routes that put text into the chat's input). */
    fun ensureChatShown() { if (!chatShown) toggleChat() }

    fun toggleChat() {
        chatShown = !chatShown
        chat.view.visibility = if (chatShown) View.VISIBLE else View.GONE
        rightSplitter.visibility = chat.view.visibility
        Ui.setActive(topBar.chatButton, chatShown)
    }

    /** Shows or hides the Explorer tree, which is the first part of the editor panel's view. */
    /** What the side bar showed before Ctrl B hid it: the side view (or null for the Explorer) and the Explorer flag. */
    private var sideBarHidden: Pair<String?, Boolean>? = null

    /** Ctrl B, as in Cursor: the whole side bar hides, whatever view it shows, and comes back with that view. */
    private fun toggleSideBar() {
        val hidden = sideBarHidden
        if (hidden == null && (sideView != null || explorerShown)) {
            sideBarHidden = Pair(sideView, explorerShown)
            explorerShown = false
            if (sideView != null) closeSide() else { setExplorerVisible(false); activityBar.setActive(null) }
        } else {
            sideBarHidden = null
            explorerShown = hidden?.second ?: true
            val s = hidden?.first
            if (s != null) showSideById(s) else { setExplorerVisible(explorerShown); activityBar.setActive(if (explorerShown) "explorer" else null) }
        }
    }

    private fun toggleExplorer() {
        sideBarHidden = null
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
    val debugPanel: DebugPanel by lazy { DebugPanel(this, this).also { d -> d.saveBeforeRun = { done -> editor.saveAll(done) }; d.runTask = { command, label, done -> showTerminal(); terminal.runTask(command, label, "", done) } } }
    val androidPanel: AndroidPanel by lazy {
        AndroidPanel(this, this).also { a ->
            a.runInTerminal = { command, label -> showTerminal(); terminal.runCommand(command, label) }
            a.runTask = { command, label, done -> showTerminal(); terminal.runTask(command, label, "", done) }
            a.attachToChat = { record -> addToChat(record) }
            a.attachDebugger = { appId -> showSideById("debug"); debugPanel.attachAndroid(appId) }
            a.openLogcat = { appId -> if (!bottomShown) toggleBottomPanel(); selectPanel(4); logcat.openFor(appId) }
        }
    }

    /** The side views by their activity bar id. */
    /** Shows a side view. [toggle] (the activity bar's icons): a second press closes it. Commands, keys and links only
     *  ever show it, as Cursor's Show commands do. */
    private fun showSideById(id: String, toggle: Boolean = false) {
        if (sideView == id && !toggle) return
        when (id) {
            "git" -> showSide(id) { GitPanel(this, this).view }
            "debug" -> showSide(id) { debugPanel.view }
            "android" -> showSide(id) { androidPanel.view }
            "review" -> showSide(id) { ReviewPanel(this, this).view }
            "notepads" -> showSide(id) { NotepadsPanel(this, this).view }
            "memories" -> showSide(id) { MemoriesPanel(this, this).view }
            "docs" -> showSide(id) { DocsPanel(this, this).view }
            "history" -> showSide(id) { HistoryPanel(this, this).view }
            "search" -> showSide(id) { searchPanel.view }
            "extensions" -> showSide(id) { ExtensionsPanel(this, this).view }
        }
    }

    /** The AI changes view kept part of an agent change: the editor's inline review follows. */
    fun reviewBaseChanged(path: String, base: String) = editor.setReviewBase(path, base)

    /** Opens a file as plain text even when it has its own view (a notebook's JSON). */
    fun openFileAsText(path: String) = editor.openAsText(path)

    /** The Android and toolchains view (the Extensions view links to it). */
    fun showAndroidView() = showSideById("android")

    /** Shows one side view next to the activity bar; pressing its icon again closes it. */
    private fun showSide(id: String, make: () -> View) {
        sideBarHidden = null
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
        val codec = if (ed != null) editor.activeCodec() else null
        if (codec != null) { encItem.second.text = codec.first; eolItem.second.text = codec.third; encItem.first.visibility = View.VISIBLE; eolItem.first.visibility = View.VISIBLE }
        else { encItem.first.visibility = View.GONE; eolItem.first.visibility = View.GONE }
        if (ed == null) { posItem.first.visibility = View.GONE; langItem.first.visibility = if (path.isEmpty()) View.GONE else View.VISIBLE; langItem.second.text = if (path.isEmpty()) "" else Highlighter.languageId(path); return }
        val (line, col) = ed.cursorLineCol()
        val sel = Math.abs(ed.edit.selectionEnd - ed.edit.selectionStart)
        posItem.second.text = "Ln " + (line + 1) + ", Col " + (col + 1) + (if (sel > 0) "  ($sel selected)" else "")
        posItem.first.visibility = View.VISIBLE
        val lang = ed.lang.id
        val client = Lsp.serverFor(path)?.let { Lsp.status(windowFolder()).firstOrNull { s -> s.startsWith(it.first + ":") } }
        langItem.second.text = lang + (if (client != null) "  ·  " + (if (client.contains("ready")) "LSP" else "LSP starting") else "")
        langItem.first.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- encodings and line endings
    private lateinit var encItem: Pair<LinearLayout, TextView>
    private lateinit var eolItem: Pair<LinearLayout, TextView>
    private var encodingList: List<Pair<String, String>> = emptyList()

    private fun withEncodings(then: (List<Pair<String, String>>) -> Unit) {
        if (encodingList.isNotEmpty()) { then(encodingList); return }
        Api.async("encodings", { Api.get("/api/encodings") }) { r, e ->
            val a = r?.optJSONArray("encodings")
            if (a == null) { flashStatus("Could not list the encodings: " + (e?.message ?: "")); return@async }
            encodingList = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("id") to it.optString("name") } }
            then(encodingList)
        }
    }

    /** Change File Encoding: Reopen with Encoding or Save with Encoding, then the encoding, as in Cursor. */
    fun encodingMenu() {
        val c = editor.activeCodec() ?: run { flashStatus("Open a text file first."); return }
        AlertDialog.Builder(this).setTitle("Change file encoding (now " + c.first + ")")
            .setItems(arrayOf("Reopen with encoding", "Save with encoding")) { _, w -> pickEncoding(reopen = w == 0) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickEncoding(reopen: Boolean) {
        val c = editor.activeCodec() ?: return
        withEncodings { list ->
            AlertDialog.Builder(this).setTitle(if (reopen) "Reopen with encoding" else "Save with encoding")
                .setItems(list.map { (if (it.first == c.second) "✓ " else "    ") + it.second }.toTypedArray()) { _, w ->
                    val (id, name) = list[w]
                    if (reopen) editor.reopenWithEncoding(id, name) else editor.saveWithEncoding(id, name)
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    /** Change End of Line Sequence: LF or CRLF. */
    fun eolMenu() {
        val c = editor.activeCodec() ?: run { flashStatus("Open a text file first."); return }
        val choices = arrayOf("LF", "CRLF")
        AlertDialog.Builder(this).setTitle("Change end of line sequence")
            .setItems(choices.map { (if (it == c.third) "✓ " else "    ") + it + (if (it == "LF") "  (Linux, macOS)" else "  (Windows)") }.toTypedArray()) { _, w -> editor.setEol(choices[w]) }
            .setNegativeButton("Cancel", null).show()
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

    private var settingsListener: ((String) -> Unit)? = null
    private val trustAsked = HashSet<String>()

    /**
     * Folder trust, as in Cursor and VS Code: a folder's own .forge settings and hooks that would loosen safety (Auto-run,
     * allowed commands, a protection turned off, hooks) are ignored until you trust the folder. Asked once per window and
     * folder; Settings, Folder has the same choice later.
     */
    private fun askFolderTrust(f: String) {
        val wants = ForgeSettings.heldBack(f)
        if (wants.isEmpty() || f in trustAsked) return
        trustAsked.add(f)
        val name = f.trimEnd('/').substringAfterLast('/')
        AlertDialog.Builder(this).setTitle("Trust the folder $name?")
            .setMessage("This folder's own Forge settings or hooks would:\n\n" + wants.joinToString("\n") { "• $it" } +
                "\n\nForge ignores these until you trust the folder. Trust it only if you trust where its files came from.")
            .setPositiveButton("Trust this folder") { _, _ -> ForgeSettings.trust(f, true) { ok, err -> flashStatus(if (ok) "This folder is trusted. Its settings and hooks apply." else "Not changed: $err") } }
            .setNegativeButton("Not now", null).show()
    }

    private fun refreshTabItem() {
        if (!::tabItem.isInitialized) return
        val on = ForgeSettings.bool("tab_completions", false, windowFolder())
        tabItem.second.text = tabStatus ?: (if (on) "Tab" else "Tab off")
        tabItem.second.setTextColor(if (tabStatus != null) Ui.accent2 else if (on) Ui.fg2 else Ui.fg3)
    }

    private fun toggleTabCompletions() {
        val on = !ForgeSettings.bool("tab_completions", false, windowFolder())
        ForgeSettings.set("tab_completions", on, windowFolder()) { ok, err ->
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
        val folder = windowFolder()
        val status = Lsp.status(folder)
        menu.menu.add(if (status.isEmpty()) "No language server is running" else "Running: " + status.joinToString(", ")).isEnabled = false
        val restart = if (path.isNotEmpty() && Lsp.serverFor(path) != null) menu.menu.add("Restart the language server of this file") else null
        val wasOn = Lsp.enabled(folder)
        val toggle = menu.menu.add(if (wasOn) "Turn the language servers off" else "Turn the language servers on")
        menu.setOnMenuItemClickListener { item ->
            when {
                item === restart -> restartLanguageServer(path)
                item === toggle -> {
                    val on = !wasOn
                    ForgeSettings.set("lsp", on, folder) { ok, err ->
                        if (!ok) { flashStatus("Not changed: $err"); return@set }
                        // Turning them off stops the running servers (ForgeSettings does that); turning them on starts
                        // the servers of the files that are open now.
                        if (on) for (p in editor.openPaths()) editor.textOf(p)?.let { Lsp.open(p, it, folder) }
                        flashStatus(if (on) "Language servers on for this folder." else "Language servers off for this folder. The running ones were stopped.")
                    }
                }
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
        val base = listOf(
            C("New chat", "Ctrl N in the chat") { if (!chatShown) toggleChat(); chat.newChatTab() },
            C("Toggle the AI panel", "Ctrl I") { toggleChat() },
            C("Add the selection to the chat", "Ctrl Shift L") { addSelectionToChat() },
            C("Edit the selection with AI", "Ctrl K") { sel()?.openInlineEdit() ?: Ui.toast(this, "Open a file first.") },
            C("Toggle the Explorer", "Ctrl B") { toggleExplorer() },
            C("Toggle the bottom panel", "Ctrl J") { toggleBottomPanel() },
            C("Toggle the terminal", "Ctrl `") { toggleTerminal() },
            C("New terminal", "Ctrl Shift `") { showTerminal(); terminal.addTerminal() },
            C("Terminal: write a command with AI", "Ctrl K in the terminal") { showTerminal(); terminal.openAi() },
            C("Terminal: add the output to the chat") { addTerminalToChat(terminal.recentText(120)) },
            C("Open a folder", "Ctrl K, Ctrl O") { openFolderDialog() },
            C("New window", "Ctrl Shift N") { newWindow() },
            C("Developer: Reload Window", "Ctrl R") { reloadWindow() },
            C("Quit Forge", "Ctrl Q") { quitForge() },
            C("File: Exit", "Ctrl Q") { quitForge() },
            C("Open a path anywhere on this computer") { openPalette("/") },
            C("Add a folder to this window (several folders in one window)") { FolderDialog.show(this, onOpen = { path -> addRoot(path) }, onNewWindow = { path -> newWindow(path) }, onAdd = { path -> addRoot(path) }, addMode = true) },
            C("New file") { editor.newFileAt("") },
            C("Save", "Ctrl S") { editor.saveActive() },
            C("Save all", "Ctrl K, S") { editor.saveAll(null) },
            C("Close the editor tab", "Ctrl W") { editor.closeActive() },
            C("Split the editor", "Ctrl \\") { editor.toggleSplit() },
            C("Markdown: open the preview to the side", "Ctrl K V") { editor.openPreview() },
            C("Markdown: toggle the preview", "Ctrl Shift V") { editor.togglePreview() },
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
            C("Show the AI changes") { showSideById("review") },
            C("Review: open the next file with agent changes") { editor.nextReview() },
            C("Review: keep every pending agent change") { editor.keepAllReviews() },
            C("Show the chat history") { showSideById("history") },
            C("Source control", "Ctrl Shift G") { showSideById("git") },
            C("Run and debug", "Ctrl Shift D") { showSideById("debug") },
            C("Debug: start debugging the open file") { debugPanel.startOpenFile() },
            C("Debug: start debugging the selected configuration", "F5") { debugPanel.start(false) },
            C("Debug: run without debugging", "Ctrl F5") { debugPanel.start(true) },
            C("Debug: stop", "Shift F5") { debugPanel.stop() },
            C("Debug: toggle a breakpoint", "F9") { toggleBreakpointAtCursor() },
            C("Android and toolchains") { showSideById("android") },
            C("Android: a screenshot of the target device to the chat") { androidPanel.screenshotToChat(null) },
            C("Android: logcat") { if (!bottomShown) toggleBottomPanel(); selectPanel(4) },
            C("New Jupyter notebook") { editor.newFileAt("notebook.ipynb") },
            C("Notepads") { showSideById("notepads") },
            C("Memories: see, add and edit what the agent remembers (global and this project)") { showSideById("memories") },
            C("Documentation for @docs") { showSideById("docs") },
            C("Agent log", "Ctrl Shift U") { if (!bottomShown) toggleBottomPanel(); selectPanel(1) },
            C("Debug console", "Ctrl Shift Y") { showDebugConsole() },
            C("Voice input (start or stop dictation)", "Ctrl Shift Space") { chat.dictateIntoPrompt() },
            C("Export the chat as Markdown") { chat.exportChatPublic() },
            C("Toggle the verbose mode of the chat") { chat.toggleSettingPublic("verbose") },
            C("Turn the model's thinking step on or off") { chat.toggleSettingPublic("thinking") },
            C("Chat with @codebase context") { if (!chatShown) toggleChat(); chat.insertText("@codebase ") },
            C("Chat with @web context") { if (!chatShown) toggleChat(); chat.insertText("@web ") },
            C("Settings", "Ctrl ,") { openSettings() },
            C("Restart the language server of this file") { val p = editor.activePath(); if (p.isEmpty()) Ui.toast(this, "Open a file first.") else restartLanguageServer(p) },
            C("Refresh the Explorer") { editor.refresh() },
            C("Unload the model from memory") { unloadModels() },
        )
        // Every key binding is a command too, and so are the editing commands Cursor has without a key.
        val e = { editor.activeEditor() ?: run { flashStatus("Open a file first."); null } }
        val more = listOf(
            C("Transform to uppercase") { e()?.toUpper() },
            C("Transform to lowercase") { e()?.toLower() },
            C("Transform to title case") { e()?.toTitle() },
            C("Transform to snake case") { e()?.toSnake() },
            C("Transform to kebab case") { e()?.toKebab() },
            C("Transform to camel case") { e()?.toCamel() },
            C("Transform to Pascal case") { e()?.toPascal() },
            C("Sort lines ascending") { e()?.sortLines(false) },
            C("Sort lines descending") { e()?.sortLines(true) },
            C("Sort lines ascending, ignoring case") { e()?.sortLines(false, ignoreCase = true) },
            C("Reverse lines") { e()?.reverseLines() },
            C("Delete duplicate lines") { e()?.deleteDuplicateLines() },
            C("Join lines") { e()?.joinLines() },
            C("Transpose characters around the cursor") { e()?.transposeLetters() },
            C("Duplicate the selection") { e()?.duplicateSelection() },
            C("Expand line selection") { e()?.selectLine() },
            C("Convert indentation to spaces") { e()?.indentationToSpaces() },
            C("Convert indentation to tabs") { e()?.indentationToTabs() },
            // A setting, as in Cursor: every open editor and every editor opened later follows it.
            C("Toggle render whitespace") { val on = !ForgeSettings.bool("render_whitespace", false, windowFolder()); ForgeSettings.set("render_whitespace", on, windowFolder()) { ok, err -> flashStatus(if (!ok) "Not saved: $err" else if (on) "Whitespace is shown." else "Whitespace is hidden.") } },
            C("Change file encoding") { encodingMenu() },
            C("Reopen with encoding") { pickEncoding(reopen = true) },
            C("Save with encoding") { pickEncoding(reopen = false) },
            C("Change end of line sequence") { eolMenu() },
            C("File: Open Recent") { openRecentDialog() },
            C("Toggle the minimap") { ForgeSettings.set("minimap", !ForgeSettings.bool("minimap", true)) },
            C("Toggle preview tabs (a single click opens a file for a look)") { val on = !ForgeSettings.bool("preview_tabs", true); ForgeSettings.set("preview_tabs", on); flashStatus(if (on) "Preview tabs are on." else "Preview tabs are off.") },
            C("Close the other editors") { editor.closeOtherTabs() },
            C("Close the editors to the right") { editor.closeTabsToTheRight() },
            C("Select all occurrences of the find match", "Alt Enter in the find bar") { e()?.selectAllFindMatches() },
            C("Undo the last cursor operation", "Ctrl U") { e()?.undoCursorOperation() },
            C("Remove the extra cursors", "Escape") { e()?.clearExtraCursors() },
        )
        val have = HashSet<String>(); for (c in base) have.add(c.label)
        val fromKeys = keymap.filter { it.title.isNotEmpty() && it.title !in have && it.enabled() }.distinctBy { it.title }
            .map { C(it.title, Keymap.pretty(it.keys), it.run) }
        return base + fromKeys + more.filter { it.label !in have }
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
            // A window opened without a folder (from the launcher) takes the most recent folder, not the folder another
            // window of this app happened to leave in the shared Api.folder.
            if (r != null && (Api.folder.isEmpty() || myFolder.isEmpty())) Api.folder = r.optString("default_folder").ifEmpty { r.optString("workspace") }
            if (myFolder.isEmpty()) myFolder = Api.folder
            editor.bindLsp()
            ForgeSettings.refresh(myFolder)
            editor.restoreReviews()
            setWindowTitle("")
            editor.refresh(); chat.load(); terminal.connect()
            startZoom()
            // The editors this folder had open come back (Reload Window, opening the folder again), then the unsaved
            // files kept by hot exit.
            editor.restoreOpenState { hotExitRestore() }
            intent?.getStringExtra("open_file")?.let { f -> intent?.removeExtra("open_file"); Api.main.postDelayed({ editor.openFile(f) }, 600) }
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
                val need = g?.opt("need_min_mb") ?: g?.opt("need_mb")
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
                // The graphics driver's pool: memory freed by Forge's GPU processes that the driver keeps for the next one.
                // Android does not count it as free, but the engine takes from it first, so it is shown next to it.
                val pool = try { GpuBudget.poolMb(this) } catch (e: Exception) { 0L }
                if (pool >= 300) text += " (+ " + gb(pool.toInt()) + " GB in the graphics driver)"
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
    /** Selected text from a chat message (or any rendered Markdown) into the chat input: quoted as a reply, or as
     *  plain text. The AI panel opens when it is hidden. */
    fun quoteToChat(text: String, reply: Boolean) {
        if (!chatShown) toggleChat()
        chat.quoteIntoPrompt(text, reply)
        flashStatus(if (reply) "Quoted in your reply. Type your answer under it." else "Added to your prompt.")
    }

    /** The text selected in a chat message (or another rendered text) that has the focus, or an empty string. */
    private fun selectedMessageText(): String {
        val f = currentFocus as? TextView ?: return ""
        if (f is android.widget.EditText) return ""
        return Markdown.selectionOf(f)
    }

    /** File: Open Recent: the folders opened lately, newest first; the one picked opens in this window. */
    private fun openRecentDialog() {
        Api.async("settings", { Api.get("/api/settings") }) { r, e ->
            val a = r?.optJSONObject("user")?.optJSONArray("recent_workspaces")
            val list = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            if (list.isEmpty()) { flashStatus("No recent folders" + (if (e != null) ": " + e.message else ".")); return@async }
            AlertDialog.Builder(this).setTitle("Open recent")
                .setItems(list.map { it.trimEnd('/').substringAfterLast('/') + "    " + it }.toTypedArray()) { _, w -> goToFolder(list[w]) }
                .setNegativeButton("Cancel", null).show()
        }
    }

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

    /**
     * Unload the model from memory: the GPU engine's model on the Googlebook (after a question, because it stops the
     * engine) and every model Ollama holds in the Linux VM. The next message loads what it needs again.
     */
    private fun unloadModels() {
        fun ollama(prefix: String) {
            Api.async("unload", { Api.post("/api/unload", JSONObject()) }) { r, e ->
                if (r == null) { flashStatus(prefix + "Ollama did not answer: " + (e?.message ?: "")); return@async }
                val names = r.optJSONArray("unloaded")
                val list = if (names == null) emptyList() else (0 until names.length()).map { names.optString(it).removeSuffix(":latest") }
                flashStatus(prefix + (if (list.isEmpty()) "Ollama had no model loaded in the VM." else "Ollama unloaded " + list.joinToString(", ") + " from the VM.") + " The next message loads what it needs.")
            }
        }
        val engineUp = EngineService.isRunning()
        if (!engineUp) { ollama(""); return }
        AlertDialog.Builder(this).setTitle("Unload the GPU engine's model?")
            .setMessage("This stops the GPU engine, which frees its memory on the Googlebook. The next chat message starts it again (about 25 seconds). Ollama's models in the Linux VM are unloaded too.")
            .setPositiveButton("Unload") { _, _ -> EngineService.stop(this, byUser = true); ollama("The GPU engine stopped. ") }
            .setNegativeButton("Cancel", null).show()
    }

    /** The language server of a file stops and starts again with the file's current text. */
    private fun restartLanguageServer(path: String) {
        val folder = windowFolder()
        Lsp.restart(path, folder)
        editor.textOf(path)?.let { Lsp.open(path, it, folder) }
        flashStatus("Restarting the language server for " + path.substringAfterLast('/'))
    }

    /** A folder as the server sees it: "~" expanded, links resolved, and checked to be a folder. */
    private fun resolveFolder(path: String, then: (String) -> Unit) {
        Api.async("dirs", { Api.get("/api/dirs", mapOf("path" to path)) }) { r, e ->
            val p = r?.optString("path").orEmpty()
            if (p.isEmpty()) { Ui.toast(this, "Cannot open $path: " + (e?.message ?: "it is not a folder.")); return@async }
            then(p)
        }
    }

    /**
     * Opens another folder in this window, as Cursor's Open Folder does: unsaved files and running chat replies are
     * asked about first, the open editors of this folder are remembered for when it is opened again, and the window is
     * built again for the new folder (its Explorer, editors, chats, terminal, settings and language servers). The old
     * folder's language servers stop when no other window shows it.
     */
    private fun goToFolder(path: String) = resolveFolder(path) { p ->
        if (p == myFolder) { flashStatus("This window already shows $p."); return@resolveFolder }
        val dirty = editor.dirtyCount()
        val busy = chat.busyCount()
        if (dirty == 0 && busy == 0) { switchFolder(p); return@resolveFolder }
        val why = ArrayList<String>()
        if (dirty > 0) why.add(if (dirty == 1) "One file has unsaved changes. Your changes are lost if you do not save them." else "$dirty files have unsaved changes. Your changes are lost if you do not save them.")
        if (busy > 0) why.add(if (busy == 1) "A chat reply is still running, and opening another folder stops it." else "$busy chat replies are still running, and opening another folder stops them.")
        val b = AlertDialog.Builder(this).setTitle("Open " + p.trimEnd('/').substringAfterLast('/') + " in this window?").setMessage(why.joinToString("\n\n"))
            .setNegativeButton("Cancel", null)
        if (dirty > 0) {
            b.setPositiveButton("Save all and open") { _, _ -> editor.saveAll { if (editor.dirtyCount() == 0) switchFolder(p) else Ui.toast(this, "Some files could not be saved, so the folder was not opened.") } }
            b.setNeutralButton("Do not save") { _, _ -> switchFolder(p) }
        } else b.setPositiveButton("Open") { _, _ -> switchFolder(p) }
        b.show()
    }

    private fun switchFolder(p: String) {
        val old = myFolder
        editor.saveOpenState()
        Api.async("workspace", { Api.post("/api/workspace", JSONObject().put("path", p)) }) { r, e ->
            if (r == null) { Ui.toast(this, "Cannot open $p: " + (e?.message ?: "")); return@async }
            myFolder = r.optString("workspace").ifEmpty { p }
            Api.folder = myFolder
            if (old.isNotEmpty() && old != myFolder && windows.none { it !== this && it.myFolder == old }) Lsp.shutdownFolder(old)
            doReload()
        }
    }

    private fun newWindow(path: String = windowFolder()) = resolveFolder(path) { p ->
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("path", p)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivity(i)
    }

    // ---------------------------------------------------------------- Cursor's key bindings (see Keymap)
    private val keymap: List<Keymap.Binding> by lazy { buildBindings() }
    private var chordPrefix = ""
    private var chordFallback: (() -> Unit)? = null
    private val chordTimeout = Runnable { val f = chordFallback; chordPrefix = ""; chordFallback = null; f?.invoke() }
    private var lastEscape = 0L

    /** The active editor, or a short note that a file must be open first. */
    private fun ed(): CodeEditor? = editor.activeEditor() ?: run { flashStatus("Open a file first."); null }

    /** The text selected in the active editor, with the file and lines, kept when copied so Ctrl V in the chat can
     *  add it as a reference to those lines (Cursor's copied code as context). */
    private var copiedRef: Triple<String, IntRange, String>? = null

    private fun copyFromEditor(cut: Boolean) {
        val e = editor.activeEditor() ?: return
        val text = e.selectionText()
        if (text.isEmpty()) { e.copyLineIfEmpty(cut); copiedRef = null; return }
        copyToClipboard(text)
        val a = minOf(e.edit.selectionStart, e.edit.selectionEnd); val b = maxOf(e.edit.selectionStart, e.edit.selectionEnd)
        copiedRef = Triple(editor.activePath(), (e.lineColOf(a).first + 1)..(e.lineColOf(b).first + 1), text)
        if (cut) e.replaceRange(a, b, "")
    }

    private fun clipboardText(): String = try { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "" } catch (e: Exception) { "" }

    private fun buildBindings(): List<Keymap.Binding> {
        val W = Keymap.Where.WINDOW; val E = Keymap.Where.EDITOR; val C = Keymap.Where.CHAT; val T = Keymap.Where.TERMINAL; val A = Keymap.Where.ANYWHERE
        fun b(keys: String, where: Keymap.Where, title: String, enabled: () -> Boolean = { true }, run: () -> Unit) = Keymap.Binding(keys, where, title, run, enabled)
        val sm = { editor.activeSmarts() }
        return listOf(
            // ---- Cursor: the AI panel, agent and chat
            // In the Agent layout the chat is the whole window, so Ctrl I goes back to the editor layout instead of
            // hiding the chat and leaving an empty window.
            b("Ctrl+I", A, "Toggle the AI panel (Agent)") { if (agentLayout) toggleAgentLayout() else { toggleChat(); if (chatShown) chat.focusPrompt() } },
            b("Ctrl+Shift+L", A, "Reply to the text selected in a chat message (quote it in your input)", { selectedMessageText().isNotBlank() }) { quoteToChat(selectedMessageText(), reply = true) },
            b("Ctrl+L", A, "Add the text selected in a chat message to your prompt", { selectedMessageText().isNotBlank() }) { quoteToChat(selectedMessageText(), reply = false) },
            b("Ctrl+L", W, "Add the selection to a new chat (or toggle the AI panel)") { if (editor.selectionText().isNotEmpty()) { if (!chatShown) toggleChat(); chat.newChatTab(); Api.main.postDelayed({ addSelectionToChat() }, 400) } else { toggleChat(); if (chatShown) chat.focusPrompt() } },
            b("Ctrl+Shift+L", W, "Add the selection to the chat") { addSelectionToChat() },
            b("Ctrl+E", W, "Toggle the Agent layout (the chat takes the window)") { toggleAgentLayout() },
            b("Ctrl+Shift+J", A, "Cursor settings (Forge's AI settings: model, agent, rules, memories and MCP)") { openSettingsAt("ai") },
            b("Ctrl+Alt+I", A, "Open the chat view") { if (!chatShown) toggleChat(); chat.focusPrompt() },
            b("Ctrl+Shift+Alt+I", A, "Open the chat in Agent mode") { if (!chatShown) toggleChat(); chat.setModePublic("agent"); chat.focusPrompt() },
            b("Ctrl+Shift+Alt+L", A, "Quick chat (ask a question in a small box)") { quickChatDialog() },
            b("Ctrl+Alt+.", A, "Pick the language model") { if (!chatShown) toggleChat(); chat.modelMenu() },
            b("Ctrl+Alt+/", A, "Model toggle (pick the model)") { if (!chatShown) toggleChat(); chat.modelMenu() },
            b("Ctrl+N", C, "New chat") { chat.newChatTab() },
            b("Ctrl+R", C, "New chat (Cursor's Ctrl R in the chat input)") { chat.newChatTab() },
            b("Ctrl+T", C, "New chat tab") { chat.newChatTab() },
            b("Ctrl+[", C, "Previous chat") { chat.stepChat(-1) },
            b("Ctrl+]", C, "Next chat") { chat.stepChat(1) },
            b("Ctrl+W", C, "Close the chat tab") { chat.closeChat() },
            b("Ctrl+.", C, "Mode menu (Agent, Ask, Plan)") { chat.modeMenu() },
            b("Shift+Tab", C, "Rotate between the agent modes") { chat.nextMode() },
            b("Ctrl+/", C, "Loop between the AI models") { chat.nextModel() },
            b("Ctrl+Shift+Backspace", C, "Cancel the reply (or undo the last reply's changes)") { chat.cancelOrReject() },
            b("Ctrl+Enter", C, "Accept all changes (with suggested changes waiting)", { !chat.isRunning() && chat.promptEmpty() && editor.pendingReviews().isNotEmpty() }) { editor.keepAllReviews(); flashStatus("Kept every change.") },
            b("Ctrl+Shift+V", C, "Paste the clipboard into the input as text") { chat.pasteAsText() },
            b("Ctrl+V", C, "Paste copied code as context (a reference to its lines)", { copiedRef?.third == clipboardText() && clipboardText().isNotEmpty() }) { copiedRef?.let { chat.pasteCodeReference(it.first, it.second.first, it.second.last, it.third) } },
            b("Up", C, "Edit your previous message (empty input)", { chat.promptEmpty() && !chat.isRunning() }) { chat.recallMessage(1) },
            b("Tab", C, "Cycle to the next message (your earlier messages)", { chat.promptEmpty() }) { chat.recallMessage(1) },
            b("Escape", C, "Leave the input (back to the editor)", { !chat.isRunning() }) { editor.focusEditor() },
            // In the code editor these keys show the parameter hints (below), as in Cursor; everywhere else, voice input.
            b("Ctrl+Shift+Space", A, "Voice input (start or stop dictation)", { currentFocus !is CodeEditor.CodeEditText }) { chat.dictateIntoPrompt() },

            // ---- general
            b("Ctrl+Shift+P", A, "Show all commands") { openPalette(">") },
            b("F1", W, "Show all commands") { openPalette(">") },
            b("Ctrl+P", A, "Go to file (Quick Open)") { openPalette("") },
            b("Ctrl+Shift+N", A, "New window") { newWindow() },
            b("Ctrl+Shift+W", W, "Close the window (the last window quits Forge; unsaved files are kept for next time)") { closeWindow() },
            b("Alt+F4", A, "Close the window (the last window quits Forge; unsaved files are kept for next time)") { closeWindow() },
            b("Ctrl+,", A, "Settings") { openSettings() },
            b("Ctrl+K Ctrl+S", W, "Keyboard shortcuts") { showKeyboardShortcuts() },
            b("Ctrl+Q", A, "Quit Forge") { quitForge() },
            b("Ctrl+K Ctrl+T", W, "Color theme") { colorThemeDialog() },
            b("Ctrl+K Ctrl+M", W, "Toggle the tab moves focus mode (same as Ctrl M)") { toggleTabFocusMode() },
            b("Ctrl+M", W, "Toggle the Tab key moves focus") { toggleTabFocusMode() },

            // ---- files
            b("Ctrl+N", W, "New file (Untitled)") { editor.newUntitled() },
            b("Ctrl+O", W, "Open a file") { openPalette("/") },
            b("Ctrl+K Ctrl+O", W, "Open a folder") { openFolderDialog() },
            b("Ctrl+K F", W, "Close the folder: close its editors and pick another folder") { closeFolder() },
            b("Ctrl+S", W, "Save") { editor.saveActive() },
            b("Ctrl+Shift+S", W, "Save as") { editor.saveAsActive() },
            b("Ctrl+K S", W, "Save all") { editor.saveAll(null) },
            b("Ctrl+Alt+S", W, "Save all") { editor.saveAll(null) },
            b("Ctrl+W", W, "Close the editor (the window when no editor is open)") { if (editor.hasOpenTabs()) editor.closeActive() else closeWindow() },
            b("Ctrl+F4", W, "Close the editor") { editor.closeActive() },
            b("Ctrl+K W", W, "Close the editor group") { editor.closeGroupTabs() },
            b("Ctrl+K Ctrl+W", W, "Close all editors") { editor.closeAllTabs() },
            b("Ctrl+K U", W, "Close the saved editors") { editor.closeSavedTabs() },
            b("Ctrl+Shift+T", W, "Reopen the closed editor") { editor.reopenClosed() },
            b("Ctrl+K Enter", W, "Keep the editor open (end preview mode)") { editor.keepOpen() },
            b("Ctrl+K P", W, "Copy the path of the active file") { editor.copyActivePath(relative = false) },
            b("Ctrl+K Ctrl+Shift+C", W, "Copy the relative path of the active file") { editor.copyActivePath(relative = true) },
            b("Ctrl+Shift+Alt+C", W, "Copy the relative path of the active file") { editor.copyActivePath(relative = true) },
            b("Ctrl+K R", W, "Reveal the active file in the Explorer") { editor.revealActive() },
            b("Ctrl+K O", W, "Show the active file in a new window") { openActiveInNewWindow() },

            // ---- editor groups and tabs
            b("Ctrl+\\", W, "Split the editor") { editor.toggleSplit() },
            *listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth").mapIndexed { i, nth -> b("Ctrl+" + (i + 1), W, "Focus the $nth editor group") { editor.focusGroupAt(i) } }.toTypedArray(),
            b("Ctrl+K Ctrl+Left", W, "Focus the editor group on the left") { editor.focusNextGroup(-1) },
            b("Ctrl+K Ctrl+Right", W, "Focus the editor group on the right") { editor.focusNextGroup(1) },
            b("Ctrl+K Left", W, "Move the active editor group left") { editor.swapGroups(-1) },
            b("Ctrl+K Right", W, "Move the active editor group right") { editor.swapGroups(1) },
            b("Ctrl+Alt+Right", W, "Move the editor into the next group") { editor.moveTabToGroup(1) },
            b("Ctrl+Alt+Left", W, "Move the editor into the previous group") { editor.moveTabToGroup(-1) },
            b("Ctrl+Shift+PageUp", W, "Move the editor left") { editor.moveTab(-1) },
            b("Ctrl+Shift+PageDown", W, "Move the editor right") { editor.moveTab(1) },
            b("Ctrl+PageDown", W, "Open the next editor") { editor.cycleTab(1) },
            b("Ctrl+PageUp", W, "Open the previous editor") { editor.cycleTab(-1) },
            b("Ctrl+Tab", W, "Open the next recently used editor") { editor.cycleRecent(1) },
            b("Ctrl+Shift+Tab", W, "Open the previous recently used editor") { editor.cycleRecent(-1) },
            b("Shift+Alt+0", W, "Toggle the editor group layout (side by side or stacked)") { editor.toggleGroupLayout() },
            *(1..9).map { n -> b("Alt+$n", W, "Open editor $n of the group") { editor.openTabAt(n) } }.toTypedArray(),
            b("Alt+0", W, "Open the last editor of the group") { editor.openTabAt(0) },

            // ---- navigation
            b("Ctrl+G", E, "Go to line") { ed()?.askGoToLine() },
            b("Ctrl+T", W, "Show all symbols") { openPalette("#") },
            b("Ctrl+Shift+O", W, "Go to a symbol in the file") { openPalette("@") },
            b("Ctrl+Shift+M", W, "Show the problems") { showProblems() },
            b("F8", W, "Go to the next problem") { nextProblem(1) },
            b("Shift+F8", W, "Go to the previous problem") { nextProblem(-1) },
            b("Ctrl+Alt+-", W, "Go back") { editor.goBack() },
            b("Ctrl+Shift+-", W, "Go forward") { editor.goForward() },
            b("Ctrl+K Ctrl+Q", W, "Go to the last edit location") { editor.goToLastEdit() },
            b("Ctrl+Shift+;", W, "Breadcrumbs: pick a symbol of the file (the @ list)") { openPalette("@") },

            // ---- display
            b("F11", W, "Toggle full screen", { !debugPanel.active() }) { toggleFullScreen() },
            b("Ctrl+K Z", W, "Toggle Zen mode (Escape twice leaves it)") { toggleZen() },
            b("Ctrl+=", A, "Zoom in") { zoomBy(1) },
            b("Ctrl+Shift+=", A, "Zoom in") { zoomBy(1) },
            b("Ctrl+-", A, "Zoom out") { zoomBy(-1) },
            b("Ctrl+Numpad0", A, "Reset the zoom") { zoomReset() },
            b("Ctrl+0", W, "Reset the zoom") { zoomReset() },
            b("Ctrl+B", W, "Toggle the side bar") { toggleSideBar() },
            b("Ctrl+Alt+B", W, "Toggle the secondary side bar (the AI panel)") { toggleChat() },
            b("Ctrl+J", W, "Toggle the panel") { toggleBottomPanel() },
            b("Ctrl+Shift+E", W, "Show the Explorer") { showExplorer() },
            b("Ctrl+Shift+F", A, "Search in files") { showSearch(editor.selectionText().takeIf { !it.contains('\n') } ?: "") },
            b("Ctrl+Shift+H", W, "Replace in files") { showSearch(editor.selectionText().takeIf { !it.contains('\n') } ?: "") },
            b("Ctrl+Shift+G", W, "Show source control") { showSideById("git") },
            b("Ctrl+Shift+D", W, "Show run and debug") { showSideById("debug") },
            b("Ctrl+Shift+X", W, "Show the extensions (language servers and toolchains)") { showSideById("extensions") },
            b("Ctrl+K Ctrl+H", W, "Show the output (the agent log)") { if (!bottomShown) toggleBottomPanel(); selectPanel(1) },
            b("Ctrl+Shift+U", W, "Show the output (the agent log)") { if (!bottomShown) toggleBottomPanel(); selectPanel(1) },
            b("Ctrl+Shift+Y", W, "Show the debug console") { showDebugConsole() },
            b("Ctrl+Shift+C", W, "Bring the Linux Terminal app to the front (Cursor: open an external terminal)") { openTerminalApp() },
            b("Ctrl+Shift+V", E, "Toggle the Markdown preview") { editor.togglePreview() },
            b("Ctrl+K V", W, "Open the Markdown preview to the side") { editor.openPreview() },
            b("Ctrl+`", A, "Toggle the terminal") { if (currentFocus is TerminalView) { toggleBottomPanel(); editor.focusEditor() } else { showTerminal(); terminal.focusInput() } },
            b("Ctrl+Shift+`", A, "New terminal") { showTerminal(); terminal.addTerminal() },
            b("Ctrl+Shift+5", A, "Split the terminal") { showTerminal(); terminal.split() },
            b("Ctrl+PageDown", T, "Focus the next terminal") { terminal.step(1) },
            b("Ctrl+PageUp", T, "Focus the previous terminal") { terminal.step(-1) },
            b("Ctrl+Shift+C", T, "Copy the terminal selection", { false }) { },
            b("Ctrl+Shift+V", T, "Paste into the terminal", { false }) { },
            b("Ctrl+Shift+Up", T, "Scroll the terminal up a line", { false }) { },
            b("Ctrl+Shift+Down", T, "Scroll the terminal down a line", { false }) { },
            b("Shift+PageUp", T, "Scroll the terminal up a page", { false }) { },
            b("Shift+PageDown", T, "Scroll the terminal down a page", { false }) { },
            b("Shift+Home", T, "Scroll the terminal to the top", { false }) { },
            b("Shift+End", T, "Scroll the terminal to the bottom", { false }) { },
            b("Ctrl+K", T, "Write a command with AI (Ctrl Enter in its box runs it)", { false }) { },

            // ---- debug and tasks
            b("F9", W, "Toggle a breakpoint") { toggleBreakpointAtCursor() },
            b("Ctrl+F5", W, "Run without debugging") { debugPanel.start(true) },
            b("Ctrl+Shift+F5", W, "Restart the debugger") { debugPanel.restart() },
            b("Ctrl+Shift+B", W, "Run the build task") { runBuildTask() },

            // ---- the editor (Cursor's and VS Code's editing keys)
            b("Ctrl+K", E, "Edit the selection with AI (inline edit)", { false }) { },
            b("Ctrl+C", E, "Copy (the whole line when nothing is selected)", { editor.activeEditor()?.hasMultipleCursors() == false }) { copyFromEditor(cut = false) },
            b("Ctrl+X", E, "Cut (the whole line when nothing is selected)", { editor.activeEditor()?.hasMultipleCursors() == false }) { copyFromEditor(cut = true) },
            b("Ctrl+Shift+K", E, "Delete the line (with the inline edit open: back to its input)") { val s0 = sm(); if (s0 != null && s0.inlineOpen()) s0.focusInline() else ed()?.deleteLine() },
            b("Ctrl+Enter", E, "Insert a line below", { editor.activeEditor()?.hasReview() != true }) { ed()?.insertLineBelow() },
            b("Ctrl+Shift+Enter", E, "Insert a line above", { editor.activeEditor()?.hasReview() != true }) { ed()?.insertLineAbove() },
            b("Alt+Up", E, "Move the line up") { ed()?.moveLinesUp() },
            b("Alt+Down", E, "Move the line down") { ed()?.moveLinesDown() },
            b("Ctrl+Shift+Alt+Up", E, "Copy the line up") { ed()?.copyLinesUp() },
            b("Ctrl+Shift+Alt+Down", E, "Copy the line down") { ed()?.copyLinesDown() },
            b("Shift+Alt+Up", E, "Add a cursor above") { ed()?.addCursorVertical(-1) },
            b("Shift+Alt+Down", E, "Add a cursor below") { ed()?.addCursorVertical(1) },
            b("Ctrl+Alt+Up", E, "Add a cursor above") { ed()?.addCursorVertical(-1) },
            b("Ctrl+Alt+Down", E, "Add a cursor below") { ed()?.addCursorVertical(1) },
            b("Ctrl+D", E, "Add the selection to the next find match") { ed()?.addNextOccurrence() },
            b("Ctrl+K Ctrl+D", W, "Move the last selection to the next find match") { ed()?.moveLastSelectionToNextOccurrence() },
            b("Ctrl+U", E, "Undo the last cursor operation") { ed()?.undoCursorOperation() },
            b("Shift+Alt+I", E, "A cursor at the end of each selected line") { ed()?.cursorsAtLineEnds() },
            b("Ctrl+F2", E, "Select all occurrences of the current word") { ed()?.selectAllOccurrences() },
            b("Shift+Alt+Right", E, "Expand the selection") { ed()?.expandSelection() },
            b("Shift+Alt+Left", E, "Shrink the selection") { ed()?.shrinkSelection() },
            b("Ctrl+Shift+\\", E, "Jump to the matching bracket") { ed()?.jumpToBracket() },
            b("Ctrl+]", E, "Indent the line") { ed()?.indentLines() },
            b("Ctrl+[", E, "Outdent the line") { ed()?.outdentLines() },
            b("Ctrl+Up", E, "Scroll a line up") { ed()?.scrollLines(-1) },
            b("Ctrl+Down", E, "Scroll a line down") { ed()?.scrollLines(1) },
            b("Alt+PageUp", E, "Scroll a page up") { ed()?.scrollPage(-1) },
            b("Alt+PageDown", E, "Scroll a page down") { ed()?.scrollPage(1) },
            b("Ctrl+Shift+[", E, "Fold the region") { ed()?.foldAtCursor() },
            b("Ctrl+Shift+]", E, "Unfold the region") { ed()?.unfoldAtCursor() },
            b("Ctrl+K Ctrl+L", W, "Toggle the fold") { ed()?.toggleFold() },
            b("Ctrl+K Ctrl+[", W, "Fold recursively (the region at the cursor and every region inside it)") { ed()?.foldRecursively(true) },
            b("Ctrl+K Ctrl+]", W, "Unfold all subregions") { ed()?.foldRecursively(false) },
            b("Ctrl+K Ctrl+0", W, "Fold all regions") { ed()?.foldAll() },
            b("Ctrl+K Ctrl+J", W, "Unfold all regions") { ed()?.unfoldAll() },
            *(1..7).map { n -> b("Ctrl+K Ctrl+$n", W, "Fold level $n") { ed()?.foldLevel(n) } }.toTypedArray(),
            b("Ctrl+K Ctrl+/", W, "Fold all block comments") { ed()?.foldBlockComments() },
            b("Ctrl+K Ctrl+-", W, "Fold all regions except the one with the cursor") { ed()?.foldAllExceptCursor() },
            b("Ctrl+K Ctrl+C", W, "Add a line comment") { ed()?.setLineComment(true) },
            b("Ctrl+K Ctrl+U", W, "Remove the line comment") { ed()?.setLineComment(false) },
            b("Ctrl+/", E, "Toggle the line comment") { ed()?.toggleLineComment() },
            b("Ctrl+Shift+A", E, "Toggle a block comment") { ed()?.toggleBlockComment() },
            b("Alt+Z", W, "Toggle word wrap") { toggleWordWrapEverywhere() },
            b("Ctrl+F", E, "Find") { ed()?.showFind() },
            b("Ctrl+H", E, "Replace") { ed()?.showReplace() },
            b("F3", E, "Find the next match") { ed()?.findNextMatch(true) },
            b("Shift+F3", E, "Find the previous match") { ed()?.findNextMatch(false) },
            b("Ctrl+F3", E, "Find the next match of the selection") { ed()?.findSelectionNext(true) },
            b("Ctrl+Shift+F3", E, "Find the previous match of the selection") { ed()?.findSelectionNext(false) },
            b("Ctrl+Space", E, "Trigger the suggestions") { sm()?.requestCompletionPublic() },
            b("Ctrl+Shift+Space", E, "Trigger the parameter hints") { sm()?.requestSignaturePublic() },
            b("Ctrl+Shift+I", E, "Format the document") { sm()?.format() },
            b("Shift+Alt+F", E, "Format the document") { sm()?.format() },
            b("Ctrl+K Ctrl+F", W, "Format the selection") { sm()?.formatSelection() },
            b("F12", E, "Go to the definition") { sm()?.goToDefinition() },
            b("Alt+F12", E, "Peek the definition") { sm()?.peekDefinition() },
            b("Ctrl+Shift+F10", E, "Peek the definition") { sm()?.peekDefinition() },
            b("Ctrl+K F12", W, "Open the definition to the side") { definitionToSide() },
            b("Ctrl+F12", E, "Go to the implementations") { sm()?.goToImplementation() },
            b("Shift+F12", E, "Go to the references") { sm()?.references() },
            b("F2", E, "Rename the symbol") { sm()?.renameDialog() },
            b("Ctrl+.", E, "Quick fix") { sm()?.codeActions() },
            b("Ctrl+K Ctrl+I", W, "Show the hover") { sm()?.hoverAtCursor() },
            b("Ctrl+K Ctrl+X", W, "Trim trailing whitespace") { ed()?.trimTrailingWhitespace() },
            b("Ctrl+K M", W, "Change the language mode") { languageModeDialog() },
            b("Ctrl+Shift+.", E, "Replace with the next value") { ed()?.inPlaceReplace(1) },
            b("Ctrl+Shift+,", E, "Replace with the previous value") { ed()?.inPlaceReplace(-1) },
            b("Alt+\\", E, "Trigger an AI completion now") { sm()?.requestGhost(true) },
            b("Ctrl+Right", E, "Accept the next word of the suggestion (or move a word right)") { val e = ed() ?: return@b; if (!(e.hasGhost() && e.acceptGhostWord())) e.cursorWordRight(false) },
        )
    }

    /** A key press made by a test step: the same path as a real key (the bindings, then the focused view). */
    private fun testKey(stroke: String) {
        val parts = stroke.split('+')
        var meta = 0
        for (m in parts.dropLast(1)) meta = meta or when (m) { "Ctrl" -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON; "Shift" -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON; "Alt" -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON; else -> 0 }
        val name = parts.last()
        val code = (0..KeyEvent.getMaxKeyCode()).firstOrNull { Keymap.keyName(it) == name } ?: return
        val now = android.os.SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** Runs a key press through the bindings; true when one took it. */
    private fun handleBinding(event: KeyEvent): Boolean {
        val focus = currentFocus
        val inTerminal = focus is TerminalView
        val inEditor = focus is CodeEditor.CodeEditText
        val inChat = chat.promptHasFocus()
        val stroke = Keymap.stroke(event)
        if (stroke.isEmpty()) return false
        if (chordPrefix.isNotEmpty()) {
            val prefix = chordPrefix
            chordPrefix = ""; chordFallback = null
            Api.main.removeCallbacks(chordTimeout)
            // Ctrl R chords are Cursor's names for VS Code's Ctrl K chords.
            val chord = "Ctrl+K $stroke"
            val b = keymap.firstOrNull { it.keys == chord && Keymap.applies(it, inEditor, inChat, inTerminal) && it.enabled() }
            if (b != null) { b.run(); return true }
            flashStatus("The key combination (" + Keymap.pretty(prefix) + ", " + Keymap.pretty(stroke) + ") is not a command.")
            return true
        }
        // In the chat input Ctrl R starts a new chat, as in Cursor, instead of beginning a chord.
        if (stroke == "Ctrl+R" && inChat) { chat.newChatTab(); return true }
        if (stroke in Keymap.chordPrefixes && !inTerminal) {
            chordPrefix = stroke
            // Ctrl K alone opens the editor's inline edit (Cursor) and Ctrl R alone reloads the window, unless a second
            // key comes first.
            chordFallback = if (stroke == "Ctrl+K") (if (inEditor) ({ editor.activeSmarts()?.openInlineEdit() }) else null) else ({ reloadWindow() })
            flashStatus("(" + Keymap.pretty(stroke) + ") was pressed. Waiting for the second key of the chord.")
            Api.main.removeCallbacks(chordTimeout)
            if (chordFallback != null) Api.main.postDelayed(chordTimeout, 800)
            return true
        }
        if (stroke == "Escape" && zenMode) {
            val now = System.currentTimeMillis()
            if (now - lastEscape < 600) { toggleZen(); lastEscape = 0; return true }
            lastEscape = now
        }
        val b = keymap.firstOrNull { it.keys == stroke && Keymap.applies(it, inEditor, inChat, inTerminal) && it.enabled() } ?: return false
        b.run()
        return true
    }

    // ---------------------------------------------------------------- zoom (Ctrl = and Ctrl -), full screen, Zen and the Agent layout
    private var zoomLevel = 0
    private val zoomPrefs by lazy { getSharedPreferences("forge-window", Context.MODE_PRIVATE) }

    /** The whole window grows or shrinks by 10 percent per step, like Cursor's zoom; the level is remembered. */
    private fun zoomBy(step: Int) { zoomLevel = (zoomLevel + step).coerceIn(-5, 8); applyZoom(); flashStatus("Zoom " + Math.round(zoomFactor() * 100) + "%") }
    private fun zoomReset() { zoomLevel = 0; applyZoom(); flashStatus("Zoom 100%") }
    private fun zoomFactor(): Float = Math.pow(1.1, zoomLevel.toDouble()).toFloat()

    private fun applyZoom() {
        try { zoomPrefs.edit().putInt("zoom", zoomLevel).apply() } catch (e: Exception) { }
        val f = zoomFactor()
        val w = root.width - root.paddingLeft - root.paddingRight
        val h = root.height - root.paddingTop - root.paddingBottom
        if (w <= 0 || h <= 0) { root.post { applyZoom() }; return }
        ide.pivotX = 0f; ide.pivotY = 0f
        ide.scaleX = f; ide.scaleY = f
        val lp = ide.layoutParams as FrameLayout.LayoutParams
        val nw = if (zoomLevel == 0) FrameLayout.LayoutParams.MATCH_PARENT else (w / f).toInt()
        val nh = if (zoomLevel == 0) FrameLayout.LayoutParams.MATCH_PARENT else (h / f).toInt()
        if (lp.width != nw || lp.height != nh) { lp.width = nw; lp.height = nh; ide.layoutParams = lp }
    }

    private var zoomWatcher = false
    private fun startZoom() {
        zoomLevel = try { zoomPrefs.getInt("zoom", 0) } catch (e: Exception) { 0 }
        if (!zoomWatcher) { zoomWatcher = true; root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (zoomLevel != 0 && (r - l != or2 - ol || b - t != ob - ot)) root.post { applyZoom() } } }
        if (zoomLevel != 0) applyZoom()
    }

    private var fullScreen = false

    /** F11: the window fills the screen (Android's own full screen mode for a desktop window), F11 again restores it. */
    private fun toggleFullScreen() {
        fullScreen = !fullScreen
        try {
            requestFullscreenMode(if (fullScreen) android.app.Activity.FULLSCREEN_MODE_REQUEST_ENTER else android.app.Activity.FULLSCREEN_MODE_REQUEST_EXIT,
                object : android.os.OutcomeReceiver<Void, Throwable> {
                    override fun onResult(result: Void?) {}
                    override fun onError(error: Throwable) { runOnUiThread { fullScreen = !fullScreen; flashStatus("Full screen is not available here: " + (error.message ?: "")) } }
                })
        } catch (e: Exception) {
            val c = window.insetsController
            if (fullScreen) c?.hide(WindowInsets.Type.systemBars()) else c?.show(WindowInsets.Type.systemBars())
        }
    }

    private var zenMode = false
    private var zenSaved: BooleanArray? = null

    /** Ctrl K Z: only the editor stays (no side bar, panel, AI panel, activity bar, top bar or status bar), in full
     *  screen; Escape twice or Ctrl K Z again brings everything back. */
    private fun toggleZen() {
        zenMode = !zenMode
        if (zenMode) {
            zenSaved = booleanArrayOf(explorerShown, bottomShown, chatShown, sideView != null)
            if (sideView != null) closeSide()
            if (explorerShown) toggleExplorer()
            if (bottomShown) toggleBottomPanel()
            if (chatShown) toggleChat()
            activityBar.view.visibility = View.GONE; topBar.view.visibility = View.GONE; statusBar.view.visibility = View.GONE
            if (!fullScreen) toggleFullScreen()
            flashStatus("Zen mode. Press Escape twice to leave it.")
        } else {
            val s = zenSaved ?: booleanArrayOf(true, true, true, false)
            activityBar.view.visibility = View.VISIBLE; topBar.view.visibility = View.VISIBLE; statusBar.view.visibility = View.VISIBLE
            if (s[0] != explorerShown) toggleExplorer()
            if (s[1] != bottomShown) toggleBottomPanel()
            if (s[2] != chatShown) toggleChat()
            if (fullScreen) toggleFullScreen()
        }
        editor.focusEditor()
    }

    private var agentLayout = false
    private var agentSaved: BooleanArray? = null

    /** Ctrl E: Cursor's Agent layout, where the chat takes the whole window; Ctrl E again brings the editor back. */
    private fun toggleAgentLayout() {
        agentLayout = !agentLayout
        val lp = chat.view.layoutParams as LinearLayout.LayoutParams
        if (agentLayout) {
            agentSaved = booleanArrayOf(chatShown, bottomShown)
            if (!chatShown) toggleChat()
            center.visibility = View.GONE; sideHost.visibility = View.GONE; rightSplitter.visibility = View.GONE
            lp.width = 0; lp.weight = 1f
            chat.focusPrompt()
        } else {
            center.visibility = View.VISIBLE; if (sideView != null) sideHost.visibility = View.VISIBLE
            lp.width = chatWidth; lp.weight = 0f
            rightSplitter.visibility = if (chatShown) View.VISIBLE else View.GONE
            val s = agentSaved
            if (s != null && !s[0] && chatShown) toggleChat()
        }
        chat.view.layoutParams = lp
    }

    // ---------------------------------------------------------------- more commands
    private fun toggleTabFocusMode() {
        CodeEditor.tabMovesFocus = !CodeEditor.tabMovesFocus
        flashStatus(if (CodeEditor.tabMovesFocus) "Tab moves the focus. Ctrl M again to type tabs." else "Tab types a tab again.")
    }

    private fun toggleWordWrapEverywhere() {
        val on = !(editor.activeEditor()?.wordWrap ?: wrapAll)
        wrapAll = on
        editor.forEachEditor { it.setWordWrap(on) }
        flashStatus(if (on) "Word wrap is on." else "Word wrap is off.")
    }
    private var wrapAll = false

    private fun closeWindow() {
        val dirty = editor.dirtyCount()
        if (dirty == 0) { finishAndRemoveTask(); return }
        AlertDialog.Builder(this).setTitle("Close the window?").setMessage(if (dirty == 1) "One file has unsaved changes." else "$dirty files have unsaved changes.")
            .setPositiveButton("Save and close") { _, _ -> editor.saveAll { finishAndRemoveTask() } }
            .setNeutralButton("Close without saving") { _, _ -> finishAndRemoveTask() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Ctrl K F: every editor of the folder closes and the folder picker opens for the next one. */
    private fun closeFolder() {
        editor.closeAllTabs()
        Api.main.postDelayed({ if (!editor.hasOpenTabs()) openFolderDialog() }, 300)
    }

    private fun openActiveInNewWindow() {
        val p = editor.activePath()
        if (p.isEmpty() || editor.isUntitled(p)) { flashStatus("Open a saved file first."); return }
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("path", myFolder.ifEmpty { Api.folder })
        i.putExtra("open_file", p)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivity(i)
    }

    /** F8 and Shift F8: the next or previous problem, in this file first and then in the Problems list. */
    private fun nextProblem(dir: Int) {
        val e = editor.activeEditor()
        if (e != null && e.gotoDiagnostic(dir)) return
        showProblems()
        problems.step(dir)
    }

    /** Ctrl K F12: the definition opens in the editor group to the right (made when there is none), even when its file
     *  is already open in this group, as Cursor's Open Definition to the Side does. */
    private fun definitionToSide() {
        val smarts = editor.activeSmarts() ?: run { flashStatus("Open a file first."); return }
        smarts.findDefinition { loc ->
            if (loc == null) { flashStatus("No definition found for this symbol."); return@findDefinition }
            editor.openToSideAt(loc.path, loc.line, loc.col)
        }
    }

    private fun quickChatDialog() {
        val input = Ui.input(this, "Ask a quick question (Enter asks, the answer appears in the AI panel)", mono = false)
        val d = AlertDialog.Builder(this).setTitle("Quick chat").setView(input)
            .setPositiveButton("Ask") { _, _ -> val q = input.text.toString().trim(); if (q.isNotEmpty()) quickAsk(q, "ask") }
            .setNegativeButton("Cancel", null).create()
        input.setOnKeyListener { _, code, ev -> if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_ENTER && !ev.isShiftPressed) { val q = input.text.toString().trim(); if (q.isNotEmpty()) quickAsk(q, "ask"); d.dismiss(); true } else false }
        d.show(); input.requestFocus()
    }

    /** Ctrl K M: the language the active file is colored and commented as. */
    private fun languageModeDialog() {
        val e = ed() ?: return
        val ids = Highlighter.allIds()
        AlertDialog.Builder(this).setTitle("Language mode of " + editor.displayName(editor.activePath()))
            .setItems(ids.map { if (it == e.lang.id) "✓ $it" else "    $it" }.toTypedArray()) { _, w -> e.lang = Highlighter.lang(ids[w]); flashStatus("Language mode: " + ids[w]) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Ctrl K Ctrl T: Forge's color themes. */
    private fun colorThemeDialog() {
        val names = Ui.themeNames()
        val cur = Ui.currentTheme(this)
        AlertDialog.Builder(this).setTitle("Color theme")
            .setItems(names.map { if (it == cur) "✓ $it" else "    $it" }.toTypedArray()) { _, w ->
                if (names[w] != cur) { Ui.setTheme(this, names[w]); reloadWindow() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Ctrl Shift B: the build tasks this folder has (scripts in package.json, a Makefile, Gradle, Cargo, Go, CMake,
     *  a build script), run in a new terminal tab. */
    private fun runBuildTask() {
        Api.async("build-tasks", {
            val out = ArrayList<Pair<String, String>>()
            fun exists(p: String): Boolean = try { Api.get("/api/file", mapOf("path" to p)); true } catch (e: Exception) { false }
            fun read(p: String): String = try { Api.get("/api/file", mapOf("path" to p)).optString("content") } catch (e: Exception) { "" }
            val pkg = read("package.json")
            if (pkg.isNotEmpty()) try { JSONObject(pkg).optJSONObject("scripts")?.keys()?.forEach { k -> out.add("npm: $k" to "npm run $k") } } catch (e: Exception) { }
            val mk = read("Makefile")
            if (mk.isNotEmpty()) { out.add("make" to "make"); Regex("^([A-Za-z0-9_.-]+):", RegexOption.MULTILINE).findAll(mk).map { it.groupValues[1] }.filter { !it.startsWith(".") }.distinct().take(15).forEach { out.add("make $it" to "make $it") } }
            // Builds go through Forge's wrappers, which check the free memory first and run inside a memory cap, as the
            // agent's builds do (a raw ./gradlew or cargo could squeeze the VM).
            if (exists("gradlew")) { out.add("gradle: build" to "forge-gradle build"); out.add("gradle: assembleDebug" to "forge-gradle assembleDebug") }
            if (exists("Cargo.toml")) { out.add("cargo build" to "forge-cargo build"); out.add("cargo run" to "forge-cargo run"); out.add("cargo test" to "forge-cargo test") }
            if (exists("go.mod")) { out.add("go build" to "go build ./..."); out.add("go test" to "go test ./...") }
            if (exists("CMakeLists.txt")) out.add("cmake: configure and build" to "cmake -S . -B build && cmake --build build")
            if (exists("build.sh")) out.add("./build.sh" to "bash ./build.sh")
            if (exists("android/build.sh")) out.add("android/build.sh" to "bash android/build.sh")
            if (exists("pyproject.toml")) out.add("python: build" to "python3 -m build")
            out
        }) { r, e ->
            val tasks = r ?: emptyList()
            if (tasks.isEmpty()) { flashStatus("No build task found in this folder" + (if (e != null) ": " + e.message else ".")); return@async }
            AlertDialog.Builder(this).setTitle("Run a build task")
                .setItems(tasks.map { it.first + "    " + it.second }.toTypedArray()) { _, w -> showTerminal(); terminal.runCommand(tasks[w].second, tasks[w].first) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    /** Ctrl K Ctrl S: every key binding, searchable, with where it applies. */
    private fun showKeyboardShortcuts() {
        // The debugger's keys are handled by the Run and debug view itself (DebugPanel.handleKey); they are listed too.
        val debugKeys = listOf("F5" to "Start debugging, or continue when stopped", "Ctrl+F5" to "Run without debugging",
            "Shift+F5" to "Stop debugging", "Ctrl+Shift+F5" to "Restart debugging", "F6" to "Pause", "F10" to "Step over",
            "F11" to "Step into", "Shift+F11" to "Step out").map { (k, t) -> Keymap.Binding(k, Keymap.Where.WINDOW, t, {}) }
        val all = keymap.filter { it.title.isNotEmpty() } + debugKeys
        val box = Ui.column(this)
        val search = Ui.input(this, "Type to search the keyboard shortcuts", mono = false)
        val list = Ui.column(this)
        fun where(w: Keymap.Where): String = when (w) { Keymap.Where.EDITOR -> "editor"; Keymap.Where.CHAT -> "chat input"; Keymap.Where.TERMINAL -> "terminal"; Keymap.Where.WINDOW -> "window"; Keymap.Where.ANYWHERE -> "everywhere" }
        fun render() {
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            for (b in all) {
                val keys = Keymap.pretty(b.keys)
                if (q.isNotEmpty() && !b.title.lowercase().contains(q) && !keys.lowercase().contains(q)) continue
                val k = Ui.text(this, keys, 12f, Ui.accent2, mono = true)
                val t = Ui.text(this, b.title, 12.5f, Ui.fg)
                val w = Ui.text(this, where(b.where), 11f, Ui.fg3)
                val row = Ui.row(this, t, k, w)
                t.layoutParams = Ui.lp(0, Ui.wrap, 1f)
                k.layoutParams = Ui.lp(Ui.dp(this, 200), Ui.wrap)
                w.layoutParams = Ui.lp(Ui.dp(this, 90), Ui.wrap)
                row.setPadding(Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5))
                list.addView(row, Ui.lp(Ui.match, Ui.wrap))
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        })
        render()
        box.addView(search, Ui.lp(Ui.match, Ui.wrap))
        box.addView(Ui.scroll(this, list), Ui.lp(Ui.match, Ui.dp(this, 480)))
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0)
        AlertDialog.Builder(this).setTitle("Keyboard shortcuts (" + all.size + ")").setView(box).setPositiveButton("Close", null).show()
        search.requestFocus()
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
        // The selection's real lines in the file (1-based), so the model and the chip name the right place.
        val ed = editor.activeEditor()
        val a = ed?.let { minOf(it.edit.selectionStart, it.edit.selectionEnd) } ?: 0
        val b = ed?.let { maxOf(it.edit.selectionStart, it.edit.selectionEnd) } ?: 0
        val first = (ed?.lineColOf(a)?.first ?: 0) + 1
        val last = first + text.trimEnd('\n').count { it == '\n' }
        return JSONObject().put("kind", "selection").put("path", path).put("text", text).put("start", first).put("end", maxOf(first, last)).put("language", lang)
            .put("name", path.substringAfterLast('/') + " (lines " + first + (if (last > first) " to $last" else "") + ")")
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
        attachUri(uri)
    }

    /**
     * A picture or file that Android hands over (picked, pasted into the chat or dropped on it): uploaded to the Forge
     * server and attached to the message. Pictures go to the vision model; text, code and PDF files are read for the
     * model. 25 MB at most, read with a cap so a huge file cannot fill the app's memory.
     */
    fun attachUri(uri: android.net.Uri) {
        val name = try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        Api.async("upload", {
            val cap = 25 * 1024 * 1024
            val bytes = contentResolver.openInputStream(uri)?.use { s ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                while (true) { val n = s.read(buf); if (n < 0) break; if (out.size() + n > cap) throw Exception("$name is larger than 25 MB"); out.write(buf, 0, n) }
                out.toByteArray()
            } ?: throw Exception("cannot read $name")
            Api.upload(name, bytes)
        }) { r, e -> if (r == null) Ui.toast(this, "Not attached: " + (e?.message ?: "")) else { ensureChatShown(); chat.addAttachment(r); flashStatus("Attached $name.") } }
    }

    /** The microphone button and Ctrl Shift Space: dictation starts, or stops when it is already on. */
    fun dictate(into: EditText) {
        if (voice?.listening == true) { stopDictation(); return }
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
        flashStatus("Listening. Speak, then press the microphone again (or Ctrl Shift Space) to stop.")
        v.start("")
        afterMic = null
    }

    /** Dictation stops (the microphone button again, sending the message, the window going to the background). */
    fun stopDictation() {
        val v = voice ?: return
        if (!v.listening) return
        v.stop()
        flashStatus("Dictation stopped.")
    }

    // The dictated words go in at the cursor and replace only themselves as the recognizer improves them, so text
    // typed meanwhile and the input's tokens stay as they are.
    private var voiceAt = 0
    private var voiceLen = 0
    private fun putDictated(into: EditText, text: String, final: Boolean) {
        val e = into.text
        val a = voiceAt.coerceIn(0, e.length)
        val b = (a + voiceLen).coerceIn(a, e.length)
        val lead = if (a > 0 && !e[a - 1].isWhitespace() && text.isNotEmpty()) " " else ""
        val piece = lead + text
        e.replace(a, b, piece)
        voiceLen = piece.length
        into.setSelection((a + voiceLen).coerceAtMost(e.length))
        if (final) { voiceAt = a + voiceLen; voiceLen = 0 }
    }

    private fun onVoice(ev: JSONObject) {
        val kind = ev.optString("type", ev.optString("kind"))
        val text = ev.optString("text", ev.optString("value", ev.optString("message")))
        val into = dictateInto ?: return
        when (kind) {
            "state" -> {
                if (text == "listening") { voiceAt = into.selectionEnd.coerceAtLeast(0); voiceLen = 0 }
                chat.setMicListening(voice?.listening == true)
            }
            "partial" -> putDictated(into, text, final = false)
            "final" -> putDictated(into, text, final = true)
            "error" -> { Ui.toast(this, text); chat.setMicListening(false) }
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
        if (event.action == KeyEvent.ACTION_DOWN && loaded && handleBinding(event)) return true
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
            KeyEvent.KEYCODE_Q -> if (!shift) { quitForge(); return true }
            KeyEvent.KEYCODE_COMMA -> { openSettings(); return true }
            KeyEvent.KEYCODE_I -> if (!shift) { toggleChat(); if (chatShown) chat.focusPrompt(); return true }
            KeyEvent.KEYCODE_F -> if (shift) { showSearch(editor.selectionText().takeIf { !it.contains('\n') } ?: ""); return true }
        }
        if (inTerminal) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_S -> { if (shift) editor.saveAll(null) else editor.saveActive(); return true }
            KeyEvent.KEYCODE_E -> if (!shift) { openPalette(""); return true } else { showExplorer(); return true }
            KeyEvent.KEYCODE_T -> if (!shift) { openPalette("#"); return true }
            KeyEvent.KEYCODE_O -> { if (shift) openPalette("@") else openPalette("/"); return true }
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
            KeyEvent.KEYCODE_R -> if (!shift) { if (!loaded) connect() else reloadWindow(); return true }
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

    override fun onPause() {
        super.onPause(); resumed = false
        if (loaded && !testWindow) editor.saveOpenState()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (myFolder.isNotEmpty() && Api.folder != myFolder) Api.folder = myFolder
        if (loaded) { ForgeSettings.refresh(windowFolder()); chat.reloadSettings() }
        if (showShortcutsNext) { showShortcutsNext = false; Api.main.postDelayed({ showKeyboardShortcuts() }, 300) }
    }

    // The window in front decides the folder that requests carry (Api.folder); a window that gets the focus takes it
    // back. Several Forge windows can be visible and resumed at once, so onResume alone is not enough.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && myFolder.isNotEmpty()) Api.folder = myFolder
    }

    override fun onTopResumedActivityChanged(isTop: Boolean) {
        super.onTopResumedActivityChanged(isTop)
        if (isTop && myFolder.isNotEmpty()) Api.folder = myFolder
    }

    override fun onStart() {
        super.onStart(); started = true
        if (!loaded) connect() else { Api.main.post(statusTask); Api.main.post(indexTask) }
    }
    override fun onStop() {
        super.onStop(); started = false
        Api.main.removeCallbacks(probeTask); Api.main.removeCallbacks(statusTask); Api.main.removeCallbacks(indexTask)
        stopDictation()
    }
    override fun onDestroy() {
        val wasCounted = counted
        if (counted) { counted = false; liveWindows-- }
        windows.remove(this)
        settingsListener?.let { ForgeSettings.removeListener(it) }
        try { editor.unbindLsp(closing = true); problems.release() } catch (e: Exception) { }
        // A window that closes for good (not a reload) stops its folder's language servers when no other window shows
        // that folder; closing the last window stops everything anyway.
        if (isFinishing && !reloading && myFolder.isNotEmpty() && windows.none { it.myFolder == myFolder }) Lsp.shutdownFolder(myFolder)
        // The last Forge window was closed by its X (not reloaded, not a test window, not closing because Forge quits).
        if (wasCounted && isFinishing && !isChangingConfigurations && !reloading && !quitting && liveWindows <= 0 && loaded) lastWindowClosed()
        super.onDestroy()
        // The repeating tasks belong to this window; a reloaded window starts its own.
        for (r in listOf(flashTask, probeTask, statusTask, indexTask)) Api.main.removeCallbacks(r)
        terminal.close(); logcat.close(); chat.stop()
    }

    /**
     * Developer: Reload Window (Ctrl R, as in Cursor): the window is built again from scratch in the same folder, with
     * its open files, chat tabs and drafts restored, and it reconnects to the server, the shells and the GPU engine
     * status. Unsaved files and a running chat reply are asked about first, because a reload would lose them.
     */
    fun reloadWindow() {
        val dirty = editor.dirtyCount()
        val busy = chat.busyCount()
        if (dirty == 0 && busy == 0) { doReload(); return }
        val why = ArrayList<String>()
        if (dirty > 0) why.add(if (dirty == 1) "One file has unsaved changes." else "$dirty files have unsaved changes.")
        if (busy > 0) why.add(if (busy == 1) "A chat reply is still running, and reloading stops it." else "$busy chat replies are still running, and reloading stops them.")
        val b = AlertDialog.Builder(this).setTitle("Reload the window?").setMessage(why.joinToString(" "))
            .setNegativeButton("Cancel", null)
        if (dirty > 0) {
            b.setPositiveButton("Save and reload") { _, _ -> editor.saveAll { doReload() } }
            b.setNeutralButton("Reload without saving") { _, _ -> doReload() }
        } else b.setPositiveButton("Reload") { _, _ -> doReload() }
        b.show()
    }

    /**
     * Quit Forge (Ctrl Q, the power button in the top bar): everything Forge runs stops. On the Googlebook that is the
     * GPU engine, the vision model, the engine notification and every Forge window, and last the app's own process.
     * In the Linux VM the server stops chat replies, virtual devices, language servers, kernels, builds and shells,
     * Ollama and the Forge Android guard, and then itself (POST /api/quit). Opening Forge starts it all again.
     */
    fun quitForge() {
        val dirty = editor.dirtyCount()
        val busy = chat.busyCount()
        val parts = ArrayList<String>()
        parts.add("This stops everything Forge runs: the GPU engine, the vision model, chat replies, terminals, language servers, virtual devices, Ollama and the Forge Android guard. Opening Forge starts them again.")
        if (dirty > 0) parts.add(if (dirty == 1) "One file has unsaved changes." else "$dirty files have unsaved changes.")
        if (busy > 0) parts.add(if (busy == 1) "A chat reply is still running, and it will be stopped." else "$busy chat replies are still running, and they will be stopped.")
        val b = AlertDialog.Builder(this).setTitle("Quit Forge?").setMessage(parts.joinToString("\n\n"))
            .setNegativeButton("Cancel", null)
        if (dirty > 0) {
            b.setPositiveButton("Save and quit") { _, _ -> editor.saveAll { doQuit() } }
            b.setNeutralButton("Quit without saving") { _, _ -> doQuit() }
        } else b.setPositiveButton("Quit") { _, _ -> doQuit() }
        b.show()
    }

    private fun doQuit() {
        val wait = AlertDialog.Builder(this).setTitle("Quitting Forge").setMessage("Stopping everything Forge runs. This takes a few seconds, longer when virtual devices are running.")
            .setCancelable(false).show()
        chat.stopAllRuns()
        val app = applicationContext
        quitEverything(app) { r ->
            try { wait.dismiss() } catch (e: Exception) { }
            val err = r?.optString("error").orEmpty()
            if (err.isNotEmpty()) Ui.toast(app, "Forge quit. The Linux VM did not answer ($err), so its part may still be running.")
        }
    }

    // ---------------------------------------------------------------- closing the last window quits Forge (with hot exit)
    private var counted = false
    private var reloading = false

    /** The window's X (or Ctrl Shift W, Alt F4) on the last Forge window: unsaved files are kept for the next start, as
     *  Cursor's hot exit does, and Forge quits. */
    private fun lastWindowClosed() {
        hotExitSave()
        try { chat.stopAllRuns() } catch (e: Exception) { }
        quitEverything(applicationContext)
    }

    private fun hotExitFile() = java.io.File(filesDir, "hot-exit.json")

    /** Unsaved files of this window's folder, written to the app's own storage (the Linux VM may be stopping). */
    private fun hotExitSave() {
        try {
            val docs = editor.unsavedDocs()
            if (docs.length() == 0) return
            val f = hotExitFile()
            val all = try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
            all.put(myFolder.ifEmpty { Api.folder }, docs)
            f.writeText(all.toString())
            Log.i(TAG, "hot exit: kept " + docs.length() + " unsaved files")
        } catch (e: Exception) { Log.w(TAG, "hot exit not saved", e) }
    }

    /** At start: the unsaved files kept at the last close come back, still unsaved. */
    private fun hotExitRestore() {
        try {
            val f = hotExitFile()
            if (!f.exists()) return
            val all = JSONObject(f.readText())
            val key = myFolder.ifEmpty { Api.folder }
            val docs = all.optJSONArray(key) ?: return
            all.remove(key)
            f.writeText(all.toString())
            editor.restoreUnsaved(docs)
            flashStatus(if (docs.length() == 1) "Restored one unsaved file from the last session." else "Restored " + docs.length() + " unsaved files from the last session.")
        } catch (e: Exception) { Log.w(TAG, "hot exit not restored", e) }
    }

    private fun doReload() {
        reloading = true
        chat.stopAllRuns()
        intent?.putExtra("path", myFolder)
        intent?.removeExtra("snapshot"); intent?.removeExtra("snapshot_only")
        recreate()
    }
}
