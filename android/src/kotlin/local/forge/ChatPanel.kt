package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The AI panel, built from native views: chat tabs (several chats at once), the model strip, the transcript with
 * its cards (thought process, tools, approvals, the vision model's exchanges, to-do lists, file changes), and the
 * composer with Mode and Model pickers, Image, Mic, Thinking and Auto-run and the Send button that becomes Stop.
 *
 * A reply streams in through the server's events. The view follows the newest line while the reader is at the
 * bottom and leaves the view alone when the reader scrolled up (a "Jump to the latest" pill brings it back).
 * The transcript is saved to the server after every reply in the same shape the web page used, so a chat opened
 * later renders with the same cards.
 */
class ChatPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)

    companion object {
        val bg4: Int = Color.parseColor("#222738")
        val border2: Int = Color.parseColor("#2f3548")
        val yellow: Int = Color.parseColor("#ffd166")
        val accentSoft: Int = Color.argb(36, 255, 138, 61)
        val accentLine: Int = Color.argb(64, 255, 138, 61)
        val purpleSoft: Int = Color.argb(20, 180, 140, 255)
        val purpleLine: Int = Color.argb(90, 180, 140, 255)
        val blueSoft: Int = Color.argb(20, 110, 168, 254)
        val blueLine: Int = Color.argb(115, 110, 168, 254)
        val TOOL_ICONS = mapOf("read_file" to "📄", "write_file" to "✎", "edit_file" to "✎", "list_dir" to "▤", "find_files" to "🔍", "search" to "🔍",
            "run_command" to "$", "web_fetch" to "🌐", "web_search" to "🌐", "codebase_search" to "🔍", "delete_path" to "🗑", "ask_user" to "?",
            "look_at_image" to "👁", "todo_write" to "☑", "remember" to "🧠", "docs_search" to "📚")
        val TOOL_LABELS = mapOf("read_file" to "Read file", "write_file" to "Write file", "edit_file" to "Edit file", "list_dir" to "List folder",
            "find_files" to "Find files", "search" to "Search code", "run_command" to "Run command", "web_fetch" to "Fetch URL", "web_search" to "Web search",
            "codebase_search" to "Search codebase", "delete_path" to "Move to trash", "ask_user" to "Question", "look_at_image" to "Look at image",
            "todo_write" to "Update to-do list", "remember" to "Save memory", "docs_search" to "Search docs")
        val MODEL_STATES = mapOf(
            "llama" to mapOf("loading" to "Loading into memory", "waiting" to "Waiting for its GPU engine", "resumed" to "GPU engine resumed", "reading" to "Reading",
                "thinking" to "Thinking", "writing" to "Writing", "tool" to "Calling a tool", "done" to "Step finished", "pausing" to "Lending the GPU",
                "paused" to "Paused (GPU lent to the vision model)", "resuming" to "Resuming on the GPU", "failed" to "Stopped", "idle" to "Idle"),
            "vision" to mapOf("starting" to "Starting", "preparing" to "Copying its files", "loading" to "Loading", "looking" to "Looking at the image",
                "answering" to "Answering", "done" to "Answered", "released" to "Finished", "cached" to "Answer reused", "failed" to "Failed", "skipped" to "Skipped",
                "unavailable" to "Not available", "off" to "Turned off", "idle" to "Idle"),
            "ocr" to mapOf("working" to "Reading the text", "done" to "Finished", "idle" to "Idle"))
        val BUSY = setOf("loading", "reading", "thinking", "writing", "tool", "starting", "preparing", "looking", "answering", "working")
        val WAITING = setOf("waiting", "pausing", "paused", "resuming")
        const val BOTTOM_DP = 100
        const val EMPTY_TEXT = "Ask Forge anything about this folder, or tell the agent what to build.\n\nAgent mode edits files and runs commands for you. Ask mode just answers. Plan mode writes a plan first. Attach a screenshot with Image."
    }

    // ---------------------------------------------------------------- widgets
    private val tabBar = LinearLayout(ctx)
    private val tabScroll = HorizontalScrollView(ctx)
    private val strip = LinearLayout(ctx)
    private val stripScroll = HorizontalScrollView(ctx)
    private val panes = FrameLayout(ctx)
    private val scroll = ScrollView(ctx)
    private val emptyView: TextView = Ui.text(ctx, EMPTY_TEXT, 13f, Ui.fg3)
    private val jump: TextView
    private val attachRow = LinearLayout(ctx)
    private val attachScroll = HorizontalScrollView(ctx)
    private val prompt: EditText = Ui.input(ctx, "Ask, or tell the agent what to build. Enter sends, Shift+Enter is a new line.", multiline = true)
    private val modePill: TextView
    private val modelPill: TextView
    private val thinkingToggle: TextView
    private val verboseToggle: TextView
    private val autoToggle: TextView
    private val send: ImageButton
    private val sendNowButton: ImageButton
    private val queueButton: ImageButton
    private val statsText: TextView

    private val tabs = ArrayList<Tab>()
    private var active: Tab? = null
    private val attachments = ArrayList<JSONObject>()
    private var models: List<Pair<String, String>> = listOf("" to "Default")
    private var mode = "agent"
    private var settings = JSONObject()
    private val prefs = ctx.getSharedPreferences("forge-chat", Context.MODE_PRIVATE)
    private val thumbs = Thumbs()
    private lateinit var mentions: ChatMentions
    private val queueBox = Ui.column(ctx)
    private var generation = 0
    private var stripTicking = false
    private val stripTick = object : Runnable {
        override fun run() {
            val t = active
            val show = t != null && (t.run != null || System.currentTimeMillis() < t.modelsUntil + 1500)
            if (show) { renderStrip(); Api.main.postDelayed(this, 1000) } else { stripTicking = false; renderStrip() }
        }
    }

    private class ModelState(var state: String, var label: String, var detail: String, var since: Long)

    /** One open chat. */
    private inner class Tab(var id: String, var title: String) {
        val pane = LinearLayout(ctx)
        var transcript = JSONArray()
        var run: Run? = null
        var pending = false
        var stick = true
        var scrollY = 0
        var draft = ""
        /** Messages sent while a reply runs; they go out one after another when it ends. */
        val queue = ArrayList<JSONObject>()
        var attention = false
        var modelChoice = ""
        val models = LinkedHashMap<String, ModelState>()
        var modelsUntil = 0L
        val dot = View(ctx)
        val titleView = Ui.text(ctx, title, 12f, Ui.fg3)
        val close = Ui.text(ctx, "✕", 10f, Ui.fg3)
        val button = Ui.row(ctx, dot, titleView, close)

        init {
            pane.orientation = LinearLayout.VERTICAL
            pane.visibility = View.GONE
            pane.setPadding(dp(10), dp(8), dp(10), dp(16))
            dot.layoutParams = Ui.lp(dp(6), dp(6)).also { it.rightMargin = dp(5) }
            titleView.isSingleLine = true; titleView.ellipsize = TextUtils.TruncateAt.END; titleView.maxWidth = dp(130)
            close.setPadding(dp(6), dp(2), dp(4), dp(2))
            close.setOnClickListener { closeTab(this) }
            button.setPadding(dp(8), dp(5), dp(4), dp(5))
            button.setOnClickListener { switchTo(this) }
            refreshButton()
        }

        fun refreshButton() {
            val isActive = this === active
            titleView.text = if (title.isEmpty()) "New chat" else title
            titleView.setTextColor(if (isActive) Ui.fg else Ui.fg3)
            button.background = if (isActive) Ui.rounded(Ui.bg3, 6, Ui.border) else null
            dot.background = circle(if (run != null) Ui.accent else if (attention) yellow else Color.TRANSPARENT)
            close.visibility = if (isActive || attention) View.VISIBLE else View.INVISIBLE
        }

        /** Renders the saved transcript. */
        fun render() {
            pane.removeAllViews()
            val n = transcript.length()
            for (k in 0 until n) {
                val turn = transcript.getJSONObject(k)
                if (turn.optString("role") == "user") { pane.addView(userBubble(turn)); continue }
                val m = renderAssistant(turn)
                // A saved attempt that failed before any answer gets a Retry button too (its message is just above).
                val prev = if (k > 0) transcript.getJSONObject(k - 1) else null
                val blocks = turn.optJSONArray("blocks") ?: JSONArray()
                var failed = false; var answered = false
                for (b in blocks.objects()) { if (b.optString("type") == "status" && b.optBoolean("error")) failed = true; if (b.optString("type") == "text" || b.optString("type") == "tool") answered = true }
                val lastBlock = blocks.optJSONObject(blocks.length() - 1)
                val endedInError = lastBlock != null && lastBlock.optString("type") == "status" && lastBlock.optBoolean("error")
                if ((failed || endedInError) && endedInError && prev != null && prev.optString("role") == "user" && k == n - 1) {
                    val item = JSONObject().put("text", prev.optString("text")).put("attachments", prev.optJSONArray("attachments") ?: JSONArray()).put("mode", turn.optString("mode", mode))
                    val tab = this
                    // A reply that failed partway keeps what came back, and the new attempt follows below it.
                    if (answered) m.addView(retryRow(tab, item, null)) else m.addView(retryRow(tab, item) {
                        if (tab.transcript.length() >= 2) { tab.transcript.remove(tab.transcript.length() - 1); tab.transcript.remove(tab.transcript.length() - 1) }
                        val idx = pane.indexOfChild(m)
                        pane.removeView(m)
                        if (idx > 0) pane.removeViewAt(idx - 1)
                    })
                }
                pane.addView(m)
            }
        }
    }

    /** One running reply. */
    private inner class Run(val tab: Tab, val msgEl: LinearLayout, val turn: JSONObject, val item: JSONObject, val userTurn: JSONObject, val userEl: View) {
        var id = ""
        var stream: Api.EventStream? = null
        var spinner: TextView? = null
        var curBlock: JSONObject? = null
        var curKind = ""
        val curText = StringBuilder()
        var live: Markdown.Live? = null
        var liveHolder: LinearLayout? = null
        var thinking: ThinkingBlock? = null
        val tools = HashMap<String, ToolCard>()
        val exchanges = HashMap<String, ExchangeCard>()
        var todosView: View? = null
        var filesRow: FilesRow? = null
    }

    // ---------------------------------------------------------------- building the panel
    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg2)
        // The tabs row: one tab per open chat, a plus button and History.
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.gravity = Gravity.BOTTOM
        tabScroll.addView(tabBar, FrameLayout.LayoutParams(Ui.wrap, Ui.match))
        tabScroll.isHorizontalScrollBarEnabled = false
        val plus = Ui.miniButton(ctx, R.drawable.ic_plus, "New chat in a new tab (Ctrl N)") { newChat(null) }
        val history = Ui.miniButton(ctx, R.drawable.ic_history, "Chat history") { showHistory() }
        val export = Ui.miniButton(ctx, R.drawable.ic_download, "Export this chat as a Markdown file") { exportChat() }
        verboseToggle = pill("V") { toggleSetting("verbose") }
        verboseToggle.tooltipText = "Verbose mode: show the raw model traffic in the Agent log"
        val head = Ui.row(ctx, tabScroll, plus, history, export, verboseToggle)
        verboseToggle.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(4) }
        tabScroll.layoutParams = Ui.lp(0, dp(34), 1f)
        for (b in listOf(plus, history, export)) b.layoutParams = Ui.lp(dp(24), dp(24)).also { it.leftMargin = dp(4) }
        head.setPadding(dp(4), dp(3), dp(6), dp(3))
        head.setBackgroundColor(Ui.bg)
        view.addView(head, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.divider(ctx, false))
        // The model strip: which model works right now.
        strip.orientation = LinearLayout.HORIZONTAL
        strip.setPadding(dp(8), dp(5), dp(8), dp(5))
        stripScroll.addView(strip)
        stripScroll.isHorizontalScrollBarEnabled = false
        stripScroll.setBackgroundColor(Ui.bg2)
        stripScroll.visibility = View.GONE
        view.addView(stripScroll, Ui.lp(Ui.match, Ui.wrap))
        // The transcript.
        emptyView.gravity = Gravity.CENTER
        emptyView.setPadding(dp(24), dp(40), dp(24), dp(40))
        panes.addView(emptyView, FrameLayout.LayoutParams(Ui.match, Ui.wrap, Gravity.CENTER))
        scroll.addView(panes, FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = true
        scroll.setBackgroundColor(Ui.bg2)
        scroll.viewTreeObserver.addOnScrollChangedListener { val t = active ?: return@addOnScrollChangedListener; t.stick = nearBottom(); updateJump() }
        panes.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> val t = active; if (t != null && t.stick) toBottom() }
        jump = Ui.text(ctx, "Jump to the latest", 12f, Ui.fg)
        jump.background = Ui.rounded(Ui.bg3, 14, border2)
        jump.setPadding(dp(12), dp(5), dp(12), dp(5))
        jump.elevation = dp(4).toFloat()
        jump.visibility = View.GONE
        jump.setOnClickListener { val t = active ?: return@setOnClickListener; followBottom(t, true) }
        val messagesFrame = FrameLayout(ctx)
        messagesFrame.addView(scroll, FrameLayout.LayoutParams(Ui.match, Ui.match))
        messagesFrame.addView(jump, FrameLayout.LayoutParams(Ui.wrap, Ui.wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = dp(8) })
        view.addView(messagesFrame, Ui.lp(Ui.match, 0, 1f))
        view.addView(Ui.divider(ctx, false))
        // The composer.
        val composer = Ui.column(ctx)
        composer.setBackgroundColor(Ui.bg2)
        composer.setPadding(dp(10), dp(6), dp(10), dp(8))
        thinkingToggle = pill("Thinking") { toggleSetting("thinking") }
        autoToggle = pill("Auto-run") { toggleSetting("auto_approve_commands") }
        val addContext = Ui.button(ctx, R.drawable.ic_at, "Add context", "Files, folders, code, docs, web, git, terminal, problems, chats, notepads (or type @)") { openMentionMenu() }
        val addSelection = Ui.button(ctx, R.drawable.ic_plus, "Selection", "Attach the text selected in the editor") { addSelection() }
        val spacerTop = View(ctx)
        val top = Ui.row(ctx, addContext, addSelection, spacerTop, thinkingToggle, autoToggle)
        addSelection.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(4) }
        spacerTop.layoutParams = Ui.lp(0, 1, 1f)
        autoToggle.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) }
        composer.addView(top, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = dp(6) })
        attachRow.orientation = LinearLayout.HORIZONTAL
        attachScroll.addView(attachRow)
        attachScroll.isHorizontalScrollBarEnabled = false
        attachScroll.visibility = View.GONE
        composer.addView(attachScroll, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = dp(6) })
        queueBox.visibility = View.GONE
        composer.addView(queueBox, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = dp(6) })
        prompt.minLines = 2; prompt.maxLines = 8
        prompt.setOnKeyListener { _, code, ev ->
            when {
                mentions.handleKey(code, ev) -> true
                code == KeyEvent.KEYCODE_ENTER && !ev.isShiftPressed -> { if (ev.action == KeyEvent.ACTION_DOWN) send(now = ev.isCtrlPressed); true }
                code == KeyEvent.KEYCODE_ESCAPE && ev.action == KeyEvent.ACTION_DOWN && active?.run != null -> { stopActive(); true }
                else -> false
            }
        }
        prompt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { prefs.edit().putString(draftKey(), s?.toString() ?: "").apply() }
        })
        composer.addView(prompt, Ui.lp(Ui.match, Ui.wrap))
        mentions = ChatMentions(ctx, host, prompt, composer) { item -> pickMention(item) }
        modePill = pill("Agent ▾") { showModeMenu(it) }
        modelPill = pill("Default ▾") { showModelMenu(it) }
        statsText = Ui.text(ctx, "", 11f, Ui.fg3)
        val attach = Ui.miniButton(ctx, R.drawable.ic_attach, "Attach a file") { host.pickFile() }
        val image = Ui.miniButton(ctx, R.drawable.ic_image, "Attach an image or a screenshot") { host.pickImage() }
        val mic = Ui.miniButton(ctx, R.drawable.ic_microphone, "Dictate") { host.dictate(prompt) }
        send = ImageButton(ctx)
        send.setImageDrawable(Ui.icon(ctx, R.drawable.ic_send, Ui.onAccent))
        send.background = Ui.rounded(Ui.accent, 17)
        send.scaleType = ImageView.ScaleType.CENTER_INSIDE
        send.setPadding(dp(8), dp(8), dp(8), dp(8))
        send.tooltipText = "Send (Enter)"; send.contentDescription = "Send"
        // Send turns into Stop while a reply runs, like Cursor.
        send.setOnClickListener { if (active?.run != null) stopActive() else send() }
        // While a reply runs, two more buttons appear before Stop: Queue (waits for the reply) and Send now.
        sendNowButton = ImageButton(ctx)
        sendNowButton.setImageDrawable(Ui.icon(ctx, R.drawable.ic_bolt, Ui.accent))
        sendNowButton.background = Ui.rounded(Ui.bg4, 17, Ui.accent)
        sendNowButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        sendNowButton.setPadding(dp(9), dp(9), dp(9), dp(9))
        sendNowButton.tooltipText = "Send now (Ctrl Enter): stops the current reply and sends this message at once"; sendNowButton.contentDescription = "Send now"
        sendNowButton.setOnClickListener { send(now = true) }
        sendNowButton.visibility = View.GONE
        queueButton = Ui.miniButton(ctx, R.drawable.ic_queue, "Queue this message (Enter): it is sent when the current reply finishes") { send(now = false) }
        queueButton.visibility = View.GONE
        // The model pill takes the room that is left and shortens its name, so the buttons always stay on screen.
        val bar = Ui.row(ctx, modePill, modelPill, attach, image, mic, queueButton, sendNowButton, send)
        modelPill.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(6); it.rightMargin = dp(4) }
        modelPill.isSingleLine = true; modelPill.ellipsize = TextUtils.TruncateAt.END
        modePill.isSingleLine = true
        for (b in listOf(attach, image, mic)) b.layoutParams = Ui.lp(dp(28), dp(28)).also { it.leftMargin = dp(2) }
        queueButton.layoutParams = Ui.lp(dp(28), dp(28)).also { it.leftMargin = dp(2) }
        sendNowButton.layoutParams = Ui.lp(dp(34), dp(34)).also { it.leftMargin = dp(6) }
        send.layoutParams = Ui.lp(dp(34), dp(34)).also { it.leftMargin = dp(6) }
        // The speed and context readout has its own line, so it can never push the buttons away.
        statsText.isSingleLine = true; statsText.ellipsize = TextUtils.TruncateAt.END
        statsText.gravity = Gravity.END
        composer.addView(statsText, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(3) })
        composer.addView(bar, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(4) })
        view.addView(composer, Ui.lp(Ui.match, Ui.wrap))
        mode = prefs.getString("mode", "agent") ?: "agent"
        if (mode.isEmpty()) mode = "agent"
        updateModePill()
    }

    private fun dp(v: Int): Int = Ui.dp(ctx, v)

    private fun circle(color: Int): GradientDrawable { val d = GradientDrawable(); d.shape = GradientDrawable.OVAL; d.setColor(color); return d }

    /** A rounded pill button (Mode, Model, the toggles). */
    private fun pill(label: String, onClick: (View) -> Unit): TextView {
        val t = Ui.text(ctx, label, 12f, Ui.fg2)
        t.background = Ui.rounded(Ui.bg3, 14, border2)
        t.setPadding(dp(10), dp(4), dp(10), dp(4))
        t.isSingleLine = true
        t.isClickable = true; t.isFocusable = true
        t.setOnClickListener(onClick)
        return t
    }

    private fun JSONArray.objects(): List<JSONObject> { val out = ArrayList<JSONObject>(); for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }; return out }

    // ---------------------------------------------------------------- following the newest line
    private fun nearBottom(): Boolean = panes.height - (scroll.height + scroll.scrollY) < dp(BOTTOM_DP)

    private fun toBottom() {
        scroll.post {
            val target = Math.max(0, panes.height - scroll.height)
            if (scroll.scrollY != target) scroll.scrollTo(0, target)
            updateJump()
        }
    }

    private fun followBottom(t: Tab, force: Boolean) {
        if (t !== active) return
        if (force) t.stick = true
        if (t.stick) toBottom()
        updateJump()
    }

    private fun updateJump() {
        val t = active
        jump.visibility = if (t != null && t.run != null && !t.stick && !nearBottom()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- loading: sessions, models and settings
    fun load() {
        generation++
        val gen = generation
        for (t in tabs) { t.run?.stream?.stop(); panes.removeView(t.pane) }
        tabs.clear(); active = null
        emptyView.visibility = View.VISIBLE
        renderTabBar(); refreshComposer(); renderStrip()
        loadModels(); loadSettings()
        val saved = try { JSONObject(prefs.getString(tabsKey(), null) ?: "{}") } catch (e: Exception) { JSONObject() }
        val ids = saved.optJSONArray("ids") ?: JSONArray()
        val wantActive = saved.optString("active")
        Api.async("sessions", {
            val out = ArrayList<JSONObject>()
            for (i in 0 until Math.min(ids.length(), 12)) {
                try { val s = Api.get("/api/session", mapOf("id" to ids.getString(i))); if (s.optString("id").isNotEmpty()) out.add(s) } catch (e: Exception) { /* a deleted chat */ }
            }
            if (out.isEmpty()) {
                val list = Api.get("/api/sessions").optJSONArray("sessions")
                if (list != null && list.length() > 0) {
                    try { out.add(Api.get("/api/session", mapOf("id" to list.getJSONObject(0).optString("id")))) } catch (e: Exception) { /* fall through */ }
                }
            }
            out
        }) { r, _ ->
            if (gen != generation) return@async
            if (r != null) for (s in r) addTab(s, false)
            val want = tabs.firstOrNull { it.id == wantActive }
            if (want != null) switchTo(want) else if (tabs.isNotEmpty()) switchTo(tabs.last()) else newChat(null)
            val draft = prefs.getString(draftKey(), "") ?: ""
            if (draft.isNotBlank() && prompt.text.isBlank()) { setPromptText(draft); active?.draft = draft }
        }
    }

    private fun tabsKey(): String = "tabs:" + Api.folder

    private fun saveTabs() {
        val ids = JSONArray(); for (t in tabs) ids.put(t.id)
        prefs.edit().putString(tabsKey(), JSONObject().put("ids", ids).put("active", active?.id ?: "").toString()).apply()
    }

    private fun loadModels() {
        Api.async("models", { Pair(Api.get("/api/models"), Api.get("/api/status")) }) { r, _ ->
            if (r == null) return@async
            val out = ArrayList<Pair<String, String>>()
            val engine = r.second.optString("engine")
            out.add("" to (if (engine == "gpu") "Default (GPU engine)" else "Default (" + r.second.optString("model").removeSuffix(":latest") + ")"))
            out.add("__gpu" to "GPU engine (Intel)")
            val arr = r.first.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i).optString("name")
                if (n.isNotEmpty() && !Regex("vl|embed", RegexOption.IGNORE_CASE).containsMatchIn(n)) out.add(n to n.removeSuffix(":latest") + " (CPU)")
            }
            models = out
            updateModelPill()
        }
    }

    private fun loadSettings() {
        Api.async("settings", { Api.get("/api/settings") }) { r, _ ->
            if (r == null) return@async
            settings = r.optJSONObject("effective") ?: JSONObject()
            updateToggles()
        }
    }

    private fun updateToggles() {
        styleToggle(thinkingToggle, "Thinking", settings.optBoolean("thinking"))
        styleToggle(verboseToggle, "V", settings.optBoolean("verbose"))
        styleToggle(autoToggle, "Auto-run", settings.optBoolean("auto_approve_commands"))
    }

    private fun styleToggle(t: TextView, label: String, on: Boolean) {
        t.text = (if (on) "✓ " else "") + label
        t.setTextColor(if (on) Ui.fg else Ui.fg2)
        t.background = Ui.rounded(if (on) accentSoft else Ui.bg3, 14, if (on) Ui.accent else border2)
    }

    private fun toggleSetting(key: String) {
        val value = !settings.optBoolean(key)
        settings.put(key, value)
        updateToggles()
        Api.async("setting", { Api.post("/api/settings", JSONObject().put(key, value).put("_scope", "user")) }) { r, e ->
            if (r == null) { settings.put(key, !value); updateToggles(); Ui.toast(ctx, "Could not save the setting: " + (e?.message ?: "")) }
        }
    }

    private fun updateModePill() { modePill.text = mode.replaceFirstChar { it.uppercase() } + " ▾" }

    private fun updateModelPill() {
        val choice = active?.modelChoice ?: ""
        val label = models.firstOrNull { it.first == choice }?.second ?: models.firstOrNull()?.second ?: "Default"
        modelPill.text = label + " ▾"
    }

    /** Agent, Ask and Plan, your custom modes (Settings, Custom modes) and a way to edit them. */
    private fun showModeMenu(anchor: View) {
        val m = PopupMenu(ctx, anchor)
        for ((v, label) in listOf("agent" to "Agent", "ask" to "Ask", "plan" to "Plan")) m.menu.add((if (mode == v) "✓ " else "    ") + label).setOnMenuItemClickListener { mode = v; prefs.edit().putString("mode", v).apply(); updateModePill(); true }
        val custom = settings.optJSONArray("custom_modes")
        if (custom != null) for (i in 0 until custom.length()) {
            val cm = custom.optJSONObject(i) ?: continue
            val name = cm.optString("name")
            if (name.isEmpty()) continue
            m.menu.add((if (mode == name) "✓ " else "    ") + name).setOnMenuItemClickListener { mode = name; prefs.edit().putString("mode", name).apply(); updateModePill(); true }
        }
        m.menu.add("    Edit modes…").setOnMenuItemClickListener { host.openSettingsAt("modes"); true }
        m.show()
    }

    private fun showModelMenu(anchor: View) {
        val m = PopupMenu(ctx, anchor)
        for ((v, label) in models) m.menu.add(label).setOnMenuItemClickListener {
            val t = active
            if (t != null) { t.modelChoice = v; updateModelPill(); Ui.toast(ctx, "This chat now uses $label.") }
            true
        }
        m.show()
    }

    // ---------------------------------------------------------------- tabs
    private fun renderTabBar() {
        tabBar.removeAllViews()
        for (t in tabs) tabBar.addView(t.button, Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = dp(2) })
        for (t in tabs) t.refreshButton()
        val a = active ?: return
        tabScroll.post {
            val b = a.button
            if (b.right > tabScroll.scrollX + tabScroll.width || b.left < tabScroll.scrollX) tabScroll.smoothScrollTo(b.left, 0)
        }
    }

    private fun switchTo(t: Tab) {
        val prev = active
        if (prev === t) return
        if (prev != null) { prev.scrollY = scroll.scrollY; prev.draft = prompt.text.toString(); prev.pane.visibility = View.GONE }
        active = t
        t.attention = false
        t.pane.visibility = View.VISIBLE
        prompt.setText(t.draft); prompt.setSelection(t.draft.length)
        emptyView.visibility = if (t.transcript.length() == 0) View.VISIBLE else View.GONE
        renderTabBar(); refreshComposer(); renderStrip(); updateModelPill(); renderQueue()
        if (!t.stick && t.scrollY > 0) scroll.post { scroll.scrollTo(0, t.scrollY); updateJump() } else followBottom(t, true)
        saveTabs()
    }

    private fun addTab(session: JSONObject, activate: Boolean): Tab {
        val id = session.optString("id")
        val existing = tabs.firstOrNull { it.id == id }
        if (existing != null) { if (activate) switchTo(existing); return existing }
        val t = Tab(id, session.optString("title", "New chat"))
        t.transcript = session.optJSONArray("transcript") ?: JSONArray()
        t.modelChoice = session.optString("model_choice")
        t.render()
        panes.addView(t.pane, FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        tabs.add(t)
        if (activate || active == null) switchTo(t) else renderTabBar()
        saveTabs()
        return t
    }

    private fun closeTab(t: Tab) {
        if (t.run != null) {
            AlertDialog.Builder(ctx).setMessage("This chat is still working. Stop it and close the tab?")
                .setPositiveButton("Stop and close") { _, _ -> cancelRun(t); closeNow(t) }.setNegativeButton("Keep", null).show()
            return
        }
        closeNow(t)
    }

    private fun closeNow(t: Tab) {
        t.run?.stream?.stop(); t.run = null
        val i = tabs.indexOf(t)
        tabs.remove(t)
        panes.removeView(t.pane)
        if (active === t) {
            active = null
            if (tabs.isNotEmpty()) switchTo(tabs[Math.max(0, i - 1)]) else newChat(null)
        }
        renderTabBar(); saveTabs()
    }

    private fun newChat(then: (() -> Unit)?) {
        val gen = generation
        Api.async("session-new", { Api.post("/api/session/new", JSONObject().put("title", "New chat")) }) { r, e ->
            if (gen != generation) return@async
            if (r == null) { Ui.toast(ctx, "Could not start a chat: " + (e?.message ?: "")); return@async }
            addTab(r, true)
            prompt.requestFocus()
            then?.invoke()
        }
    }

    /** Opens a saved chat in a tab (the History view and the history dialog use this). */
    fun openSession(id: String) = openChat(id)

    /**
     * Sends a question from another panel ("Fix with AI", "Explain", the terminal's Ask button): the text goes
     * into the prompt, the mode switches when asked, and the message is sent at once.
     */
    fun quickAsk(text: String, askMode: String = "", attachment: JSONObject? = null) {
        if (askMode.isNotEmpty() && askMode != mode) { mode = askMode; prefs.edit().putString("mode", mode).apply(); updateModePill(); refreshComposer() }
        if (attachment != null) addAttachment(attachment)
        setPromptText(text)
        send()
    }

    fun newChatTab() = newChat(null)
    fun typeForTest(text: String) = setPromptText(text)
    fun stopForTest() = stopActive()
    fun exportChatPublic() = exportChat()
    fun toggleSettingPublic(key: String) = toggleSetting(key)
    fun dictateIntoPrompt() { prompt.requestFocus(); host.dictate(prompt) }
    fun activeChatId(): String = active?.id ?: ""

    /** Inserts text at the prompt's cursor (the palette's @codebase and @web commands). */
    fun insertText(t: String) {
        val pos = prompt.selectionStart.coerceAtLeast(0)
        prompt.text.insert(pos, t)
        prompt.requestFocus()
    }

    /** The @ menu from the Add context button: an @ goes into the prompt, which opens the menu. */
    private fun openMentionMenu() {
        val pos = prompt.selectionStart.coerceAtLeast(0)
        val before = prompt.text.toString().substring(0, pos)
        prompt.text.insert(pos, if (before.isEmpty() || before.endsWith(" ") || before.endsWith("\n")) "@" else " @")
        prompt.requestFocus()
    }

    /** A pick from the @ or / menu becomes a context chip or runs a slash command. */
    private fun pickMention(it: ChatMentions.Item) {
        val d = it.data
        when (it.kind) {
            "command" -> runCommand(d, "")
            "file" -> { val p = d.optString("path"); if (attachments.none { a -> a.optString("kind") == "file" && a.optString("path") == p }) addAttachment(JSONObject().put("kind", "file").put("path", p).put("name", p)) }
            "folder" -> addAttachment(JSONObject().put("kind", "folder").put("path", d.optString("path")).put("name", d.optString("path")))
            "codebase", "web", "git" -> if (attachments.none { a -> a.optString("kind") == it.kind }) addAttachment(JSONObject().put("kind", it.kind).put("path", "@" + it.kind).put("name", "@" + it.kind))
            "terminal" -> { val t = host.terminalText(); if (t.isBlank()) Ui.toast(ctx, "The terminal has no output yet.") else { attachments.removeAll { a -> a.optString("kind") == "terminal" }; addAttachment(JSONObject().put("kind", "terminal").put("text", t).put("name", "Terminal output")) } }
            "lints" -> addLints()
            "docs" -> addAttachment(JSONObject().put("kind", "docs").put("name", d.optString("name")))
            "docs-add" -> host.showDocs()
            "chat" -> addAttachment(JSONObject().put("kind", "chat").put("id", d.optString("id")).put("title", d.optString("title")))
            "notepad" -> addAttachment(JSONObject().put("kind", "text").put("title", "Notepad: " + d.optString("name")).put("text", d.optString("text")).put("name", "notepad: " + d.optString("name")))
        }
    }

    private fun addLints(): Boolean {
        val lines = host.problems.allLines()
        if (lines.isEmpty()) { Ui.toast(ctx, "There are no linter errors right now."); return false }
        attachments.removeAll { a -> a.optString("title") == "Linter errors" }
        addAttachment(JSONObject().put("kind", "text").put("title", "Linter errors").put("text", lines.joinToString("\n")).put("name", "Linter errors"))
        return true
    }

    /** Slash commands: built-in actions run at once; prompt commands fill the input so details can be added. */
    private fun runCommand(cmd: JSONObject, rest: String) {
        val n = cmd.optString("name")
        when (n) {
            "new" -> { newChat(null); return }
            "reset" -> { val t = active ?: return; Api.async("reset", { Api.post("/api/session/reset", JSONObject().put("id", t.id)) }) { r, e -> Ui.toast(ctx, if (r != null) "The model's context was reset. The transcript stays." else "Not reset: " + (e?.message ?: "")) }; return }
            "export" -> { exportChat(); return }
            "plan", "ask", "agent" -> { mode = n; prefs.edit().putString("mode", n).apply(); updateModePill(); host.flashStatus(n.replaceFirstChar { c -> c.uppercase() } + " mode"); return }
            "review" -> if (attachments.none { it.optString("kind") == "git" }) addAttachment(JSONObject().put("kind", "git").put("path", "@git").put("name", "@git"))
            "explain", "tests" -> host.editorSelection()?.let { s -> attachments.removeAll { it.optString("kind") == "selection" }; addAttachment(s) }
            "fix-lints" -> if (!addLints()) return
        }
        val text = cmd.optString("prompt").trim() + (if (rest.isNotEmpty()) "\n\n" + rest else "")
        setPromptText(text)
        prompt.requestFocus()
    }

    private fun draftKey(): String = "draft:" + Api.folder

    private fun openChat(id: String) {
        val open = tabs.firstOrNull { it.id == id }
        if (open != null) { switchTo(open); return }
        val gen = generation
        Api.async("session", { Api.get("/api/session", mapOf("id" to id)) }) { r, e ->
            if (gen != generation) return@async
            if (r == null) { Ui.toast(ctx, "Could not open the chat: " + (e?.message ?: "")); return@async }
            addTab(r, true)
        }
    }

    /** The list of past chats in this folder; opening one adds a tab. Also used by the window's Chats button. */
    /** "@ Add context": a file of this folder goes into the message as a file attachment. */
    private fun addContextFile() {
        val input = Ui.input(ctx, "Type part of a file name")
        val list = android.widget.ListView(ctx)
        val items = ArrayList<String>()
        val ad = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, items)
        list.adapter = ad
        val box = Ui.column(ctx, input, list); list.layoutParams = Ui.lp(Ui.match, dp(320))
        val dialog = android.app.AlertDialog.Builder(ctx).setTitle("Add a file as context").setView(box).setNegativeButton("Close", null).create()
        fun search(q: String) {
            Api.async("files", { Api.get("/api/files", mapOf("q" to q)) }) { r, _ ->
                items.clear()
                val arr = r?.optJSONArray("files")
                if (arr != null) for (i in 0 until minOf(arr.length(), 200)) items.add(arr.getString(i))
                ad.notifyDataSetChanged()
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { search(s.toString()) }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val path = items[pos]
            if (attachments.none { it.optString("kind") == "file" && it.optString("path") == path }) addAttachment(JSONObject().put("kind", "file").put("path", path).put("name", path))
            dialog.dismiss()
        }
        dialog.show(); search(""); input.requestFocus()
    }

    /** "+ Selection": the editor's selected text goes into the message. */
    private fun addSelection() {
        val sel = host.editorSelection()
        if (sel == null) { Ui.toast(ctx, "Select some text in the editor first."); return }
        attachments.removeAll { it.optString("kind") == "selection" }
        addAttachment(sel)
        prompt.requestFocus()
    }

    /** Writes the active chat as a Markdown file through the server (it lands in Documents/Forge/Chats). */
    private fun exportChat() {
        val t = active ?: return
        Api.async("export", {
            val s = Api.get("/api/session", mapOf("id" to t.id))
            val sb = StringBuilder("# " + s.optString("title", "Chat") + "\n\n")
            val tr = s.optJSONArray("transcript") ?: JSONArray()
            for (i in 0 until tr.length()) {
                val turn = tr.getJSONObject(i)
                if (turn.optString("role") == "user") { sb.append("## You\n\n").append(turn.optString("text")).append("\n\n"); continue }
                sb.append("## Forge\n\n")
                val blocks = turn.optJSONArray("blocks") ?: JSONArray()
                for (k in 0 until blocks.length()) {
                    val b = blocks.getJSONObject(k)
                    when (b.optString("type")) {
                        "text" -> sb.append(b.optString("text")).append("\n\n")
                        "thinking" -> sb.append("<details><summary>Thought process</summary>\n\n").append(b.optString("text")).append("\n\n</details>\n\n")
                        "tool" -> sb.append("**Tool ").append(b.optString("name")).append("**\n\n```\n").append(b.optString("result").take(4000)).append("\n```\n\n")
                        "status" -> sb.append("_").append(b.optString("text")).append("_\n\n")
                    }
                }
            }
            Api.post("/api/session/export", JSONObject().put("id", t.id).put("markdown", sb.toString()))
        }) { r, e -> Ui.toast(ctx, if (r != null) "Exported to " + r.optString("path") else "Export failed: " + (e?.message ?: "")) }
    }

    fun showHistory(attempt: Int = 0) {
        Api.async("history", { Api.get("/api/sessions") }) { r, e ->
            val list = r?.optJSONArray("sessions")
            if (list == null) {
                // The server may be restarting for a few seconds: try again quietly before saying anything failed.
                if (attempt < 10) { if (attempt == 0) host.flashStatus("Connecting to the Forge server…"); Api.main.postDelayed({ showHistory(attempt + 1) }, 2000); return@async }
                Ui.toast(ctx, "Could not list the chats: " + (e?.message ?: "")); return@async
            }
            if (list.length() == 0) { Ui.toast(ctx, "No chats in this folder yet."); return@async }
            val names = ArrayList<String>(); val ids = ArrayList<String>()
            for (s in list.objects()) {
                val open = tabs.any { it.id == s.optString("id") }
                names.add(s.optString("title", "Chat") + "   " + timeAgo(s.optDouble("updated", 0.0)) + (if (open) "   (open)" else ""))
                ids.add(s.optString("id"))
            }
            AlertDialog.Builder(ctx).setTitle("Chats in this folder").setItems(names.toTypedArray()) { _, which -> openChat(ids[which]) }
                .setNegativeButton("Close", null).show()
        }
    }

    private fun timeAgo(ts: Double): String {
        val d = System.currentTimeMillis() / 1000.0 - ts
        return when {
            ts <= 0 -> ""
            d < 60 -> "now"
            d < 3600 -> (d / 60).toInt().toString() + " min ago"
            d < 86400 -> (d / 3600).toInt().toString() + " h ago"
            else -> (d / 86400).toInt().toString() + " d ago"
        }
    }

    private fun saveTranscript(t: Tab) {
        val copy = JSONArray(t.transcript.toString())
        val body = JSONObject().put("id", t.id).put("transcript", copy).put("title", t.title)
        Api.async("transcript", { Api.post("/api/session/transcript", body) }) { _, _ -> }
    }

    // ---------------------------------------------------------------- the model strip
    private fun modelName(m: String, label: String): String = when (m) {
        "llama" -> "Llama 3.1"
        "ocr" -> "Text recognition"
        else -> label.replace(Regex("\\s*\\(.*\\)$"), "").ifEmpty { "Vision model" }
    }
    private fun stateText(m: String, st: String): String = MODEL_STATES[m]?.get(st) ?: st
    private fun dotColor(st: String): Int = when {
        BUSY.contains(st) -> Ui.green
        WAITING.contains(st) -> yellow
        st == "failed" || st == "skipped" || st == "unavailable" -> Ui.red
        else -> Ui.fg3
    }
    private fun modelLine(m: String): Int = when (m) { "llama" -> accentLine; "vision" -> purpleLine; else -> blueLine }

    private fun renderStrip() {
        val t = active
        val show = t != null && t.models.isNotEmpty() && (t.run != null || System.currentTimeMillis() < t.modelsUntil)
        stripScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (t == null || !show) return
        strip.removeAllViews()
        val now = System.currentTimeMillis()
        for (m in listOf("llama", "vision", "ocr")) {
            val st = t.models[m] ?: continue
            if (m == "ocr" && st.state == "idle") continue
            val secs = Math.max(0L, (now - st.since) / 1000)
            val busy = BUSY.contains(st.state) || WAITING.contains(st.state)
            strip.addView(modelPillView(m, modelName(m, st.label), stateText(m, st.state) + (if (busy) ", $secs s" else ""), dotColor(st.state)))
        }
        if (!t.models.containsKey("vision")) strip.addView(modelPillView("vision", "Vision model", "Idle", Ui.fg3))
    }

    private fun modelPillView(m: String, name: String, state: String, dot: Int): View {
        val d = View(ctx); d.background = circle(dot)
        val n = Ui.text(ctx, name, 11.5f, Ui.fg, bold = true)
        val s = Ui.text(ctx, state, 11.5f, Ui.fg2); s.isSingleLine = true
        val row = Ui.row(ctx, d, n, s)
        d.layoutParams = Ui.lp(dp(8), dp(8)).also { it.rightMargin = dp(6) }
        s.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) }
        row.background = Ui.rounded(Ui.bg3, 14, modelLine(m))
        row.setPadding(dp(9), dp(3), dp(9), dp(3))
        row.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = dp(6) }
        return row
    }

    private fun startStripTicker() { if (!stripTicking) { stripTicking = true; Api.main.post(stripTick) } }

    // ---------------------------------------------------------------- building blocks of the transcript
    private fun statusLine(text: String, error: Boolean = false): TextView {
        val t = Markdown.textView(ctx, text, 11.5f, if (error) Ui.red else Ui.fg3)
        t.setPadding(0, dp(3), 0, dp(3))
        return t
    }

    private fun sectionLabel(name: String): TextView {
        val t = Ui.text(ctx, name.uppercase(), 10.5f, Ui.fg3)
        t.letterSpacing = 0.06f
        t.setPadding(dp(10), dp(5), dp(10), 0)
        return t
    }

    private fun pre(text: String): TextView {
        val t = Markdown.textView(ctx, text, 11.5f, Ui.fg2, mono = true)
        t.setPadding(dp(10), dp(4), dp(10), dp(6))
        return t
    }

    private fun copyText(s: String) { host.copyToClipboard(s) }

    private fun cardMargins(): LinearLayout.LayoutParams { val lp = Ui.lp(Ui.match, Ui.wrap); lp.topMargin = dp(4); lp.bottomMargin = dp(4); return lp }

    private fun assistantBox(): LinearLayout { val box = Ui.column(ctx); box.setPadding(0, dp(4), 0, dp(8)); return box }

    /** The user's message: a right-aligned bubble, with image thumbnails and chips for other attachments above it. */
    private fun userBubble(turn: JSONObject): View {
        val box = Ui.column(ctx)
        box.gravity = Gravity.END
        box.setPadding(0, dp(6), 0, dp(6))
        val atts = turn.optJSONArray("attachments")?.objects() ?: emptyList()
        val images = atts.filter { it.optString("kind") == "image" }
        val others = atts.filter { it.optString("kind") != "image" && it.optString("kind") != "editor" }
        if (images.isNotEmpty()) {
            val row = Ui.row(ctx); row.gravity = Gravity.END
            for (a in images) {
                val iv = ImageView(ctx)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.background = Ui.rounded(Ui.bg, 8, border2)
                iv.contentDescription = a.optString("name", "image")
                row.addView(iv, Ui.lp(dp(120), dp(84)).also { it.leftMargin = dp(6) })
                val url = a.optString("url")
                if (url.isNotEmpty()) { thumbs.load(url, 200) { bmp -> if (bmp != null) iv.setImageBitmap(bmp) }; iv.setOnClickListener { showImage(url) } }
            }
            box.addView(row, Ui.lp(Ui.wrap, Ui.wrap).also { it.bottomMargin = dp(4) })
        }
        if (others.isNotEmpty()) {
            val row = Ui.row(ctx); row.gravity = Gravity.END
            for (a in others) row.addView(chip(contextLabel(a), null), Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(4) })
            box.addView(row, Ui.lp(Ui.wrap, Ui.wrap).also { it.bottomMargin = dp(4) })
        }
        val t = Markdown.textView(ctx, turn.optString("text"), 13f, Ui.fg)
        t.background = Ui.rounded(bg4, 12)
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        box.addView(t, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(40) })
        if (turn.optString("run_id").isNotEmpty()) {
            val restore = Ui.button(ctx, R.drawable.ic_restore, "Restore checkpoint", "Undo the file changes of this message and everything after it, and continue the chat from here") { restoreCheckpoint(turn) }
            box.addView(restore, Ui.lp(Ui.wrap, Ui.wrap).also { it.topMargin = dp(2) })
        }
        return box
    }

    /** Undoes the file changes of a message and every later one, cuts the chat there and puts the message back in the prompt. */
    private fun restoreCheckpoint(turn: JSONObject) {
        val tab = active ?: return
        if (tab.run != null) { Ui.toast(ctx, "Wait until this chat has finished, or stop it first."); return }
        AlertDialog.Builder(ctx).setTitle("Restore to before this message?")
            .setMessage("File changes from this message onward are undone, and the chat continues from here with your message back in the prompt.")
            .setPositiveButton("Restore") { _, _ ->
                Api.async("rewind", { Api.post("/api/session/rewind", JSONObject().put("id", tab.id).put("run_id", turn.optString("run_id")), 120000) }) { r, e ->
                    if (r == null) { Ui.toast(ctx, "Not restored: " + (e?.message ?: "")); return@async }
                    var idx = -1
                    for (i in 0 until tab.transcript.length()) if (tab.transcript.opt(i) === turn || tab.transcript.optJSONObject(i)?.optString("run_id") == turn.optString("run_id")) { idx = i; break }
                    if (idx >= 0) { val kept = JSONArray(); for (i in 0 until idx) kept.put(tab.transcript.get(i)); tab.transcript = kept }
                    tab.render()
                    saveTranscript(tab)
                    val msgs = r.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until msgs.length()) Regex("^(?:Restored|Removed) (\\S+)").find(msgs.optString(i))?.let { m -> host.fileChanged(m.groupValues[1]) }
                    host.refreshTree()
                    if (tab === active) {
                        setPromptText(turn.optString("text"))
                        attachments.clear()
                        (turn.optJSONArray("attachments") ?: JSONArray()).objects().filter { it.optString("kind") != "editor" }.forEach { attachments.add(it) }
                        renderAttachments()
                        emptyView.visibility = if (tab.transcript.length() > 0) View.GONE else View.VISIBLE
                    }
                    Ui.toast(ctx, "Restored. " + msgs.length() + " file " + (if (msgs.length() == 1) "change" else "changes") + " undone.")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun contextLabel(a: JSONObject): String = when (a.optString("kind")) {
        "selection" -> a.optString("path") + ":" + a.optInt("start") + "-" + a.optInt("end")
        "terminal" -> "Terminal output"
        "upload" -> a.optString("name")
        "docs" -> "Docs: " + a.optString("name")
        "chat" -> "Chat: " + a.optString("title")
        "folder" -> "Folder: " + a.optString("path")
        "image" -> "Image: " + a.optString("name", "image")
        else -> a.optString("path").ifEmpty { a.optString("title").ifEmpty { a.optString("name").ifEmpty { a.optString("kind") } } }
    }

    /** A small chip; with [onRemove] it gets a cross. */
    private fun chip(label: String, onRemove: (() -> Unit)?): View {
        val t = Ui.text(ctx, label, 11f, Ui.fg2)
        t.isSingleLine = true; t.ellipsize = TextUtils.TruncateAt.MIDDLE; t.maxWidth = dp(220)
        val row = Ui.row(ctx, t)
        if (onRemove != null) {
            val x = Ui.text(ctx, "✕", 10f, Ui.fg3)
            x.setPadding(dp(6), 0, dp(2), 0)
            x.setOnClickListener { onRemove() }
            row.addView(x)
        }
        row.background = Ui.rounded(bg4, 5, border2)
        row.setPadding(dp(7), dp(2), dp(7), dp(2))
        return row
    }

    /** The model's thought process: open while live, collapsed after, purple label. */
    private inner class ThinkingBlock(text: String, live: Boolean) {
        val view = Ui.column(ctx)
        private val chev = Ui.text(ctx, if (live) "▾" else "▸", 12f, Ui.purple)
        private val label = Ui.text(ctx, if (live) "Thinking…" else "Thought process", 11.5f, Ui.purple)
        private val body = Markdown.textView(ctx, text, 12f, Ui.fg2)
        private val scroller = MaxHeightScroll(ctx, dp(260))
        private var open = live
        private var bodyStick = true
        private var pending = text
        private val throttle = Throttle(80) { body.text = pending; if (open && bodyStick) scroller.post { scroller.scrollTo(0, Math.max(0, body.height - scroller.height)) } }

        init {
            view.background = Ui.rounded(Ui.bg2, 8, Ui.border)
            val head = Ui.row(ctx, chev, label)
            label.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) }
            head.setPadding(dp(10), dp(6), dp(10), dp(6))
            head.setOnClickListener { open = !open; apply() }
            body.setTypeface(body.typeface, Typeface.ITALIC)
            body.setPadding(dp(12), 0, dp(12), dp(8))
            scroller.addView(body, FrameLayout.LayoutParams(Ui.match, Ui.wrap))
            scroller.isVerticalScrollBarEnabled = true
            scroller.viewTreeObserver.addOnScrollChangedListener { bodyStick = body.height - (scroller.height + scroller.scrollY) < dp(40) }
            view.addView(head, Ui.lp(Ui.match, Ui.wrap))
            view.addView(scroller, Ui.lp(Ui.match, Ui.wrap))
            apply()
        }

        private fun apply() { scroller.visibility = if (open) View.VISIBLE else View.GONE; chev.text = if (open) "▾" else "▸" }
        fun update(t: String) { pending = t; throttle.request() }
        fun close(t: String) { pending = t; throttle.flush(); label.text = "Thought process"; open = false; apply() }
    }

    private fun argSummary(name: String, a0: JSONObject?): String {
        val a = a0 ?: JSONObject()
        return when (name) {
            "run_command" -> a.optString("command")
            "search" -> a.optString("pattern") + (if (a.optString("path").isNotEmpty() && a.optString("path") != ".") " in " + a.optString("path") else "")
            "find_files" -> a.optString("pattern")
            "web_fetch" -> a.optString("url")
            "web_search", "codebase_search" -> a.optString("query")
            "docs_search" -> a.optString("name") + ": " + a.optString("query")
            "read_file" -> a.optString("path") + (if (a.has("start_line")) ":" + a.optInt("start_line") + "-" + (if (a.has("end_line")) a.optInt("end_line").toString() else "") else "")
            "look_at_image" -> a.optString("question") + (if (a.optString("image").isNotEmpty()) "  [" + a.optString("image") + "]" else "")
            "remember" -> a.optString("memory")
            "todo_write" -> (a.optJSONArray("todos")?.length() ?: 0).toString() + " items"
            else -> a.optString("path").ifEmpty { a.toString() }
        }
    }

    /** A tool call: icon, label, argument summary, state badge, and a collapsible body with arguments and result. */
    private inner class ToolCard(val block: JSONObject) {
        val view = Ui.column(ctx)
        private val stateView = Ui.text(ctx, "running", 11f, Ui.fg3)
        private val resultView: TextView
        private val bodyWrap = Ui.column(ctx)
        private var open = false

        init {
            view.background = Ui.rounded(Ui.bg2, 8, Ui.border)
            val name = block.optString("name")
            val args = block.optJSONObject("arguments")
            val icon = Ui.text(ctx, TOOL_ICONS[name] ?: "⚙", 11f, Ui.fg2)
            icon.gravity = Gravity.CENTER
            icon.background = Ui.rounded(bg4, 5)
            val label = Ui.text(ctx, TOOL_LABELS[name] ?: name, 12f, Ui.fg, bold = true)
            val summary = argSummary(name, args)
            val arg = Ui.text(ctx, summary, 11f, Ui.fg3, mono = true)
            arg.isSingleLine = true; arg.ellipsize = TextUtils.TruncateAt.END
            val head = Ui.row(ctx, icon, label, arg, stateView)
            icon.layoutParams = Ui.lp(dp(18), dp(18)).also { it.rightMargin = dp(8) }
            arg.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(8); it.rightMargin = dp(8) }
            head.setPadding(dp(10), dp(6), dp(10), dp(6))
            head.setOnClickListener { setOpen(!open) }
            val body = Ui.column(ctx)
            val big = listOf("content", "old_text", "new_text")
            val small = JSONObject()
            if (args != null) for (k in args.keys()) if (k !in big) small.put(k, args.opt(k))
            body.addView(sectionLabel("arguments")); body.addView(pre(try { small.toString(1) } catch (e: Exception) { small.toString() }))
            if (args != null) for (k in big) if (args.has(k)) { body.addView(sectionLabel(k)); body.addView(pre(args.optString(k))) }
            resultView = pre(block.optString("result"))
            body.addView(sectionLabel("result")); body.addView(resultView)
            val scroller = MaxHeightScroll(ctx, dp(320))
            scroller.addView(body, FrameLayout.LayoutParams(Ui.match, Ui.wrap))
            bodyWrap.addView(Ui.divider(ctx, false))
            bodyWrap.addView(scroller, Ui.lp(Ui.match, Ui.wrap))
            bodyWrap.visibility = View.GONE
            view.addView(head, Ui.lp(Ui.match, Ui.wrap))
            view.addView(bodyWrap, Ui.lp(Ui.match, Ui.wrap))
            applyState()
        }

        fun setOpen(on: Boolean) { open = on; bodyWrap.visibility = if (on) View.VISIBLE else View.GONE }

        fun setResult(ok: Boolean, result: String, seconds: Double?) {
            block.put("state", if (ok) "ok" else "fail").put("result", result)
            if (seconds != null) block.put("seconds", seconds)
            resultView.text = result
            applyState()
            if (!ok) setOpen(true)
        }

        private fun applyState() {
            when (block.optString("state")) {
                "ok" -> { stateView.text = "done" + (if (block.has("seconds")) ", " + block.optDouble("seconds") + " s" else ""); stateView.setTextColor(Ui.green) }
                "fail" -> { stateView.text = "failed"; stateView.setTextColor(Ui.red) }
                else -> { stateView.text = "running"; stateView.setTextColor(Ui.fg3) }
            }
        }
    }

    /** Files the reply changed, as chips, with Review (inline in the editor) and Undo all, like Cursor's change list. */
    private inner class FilesRow(var runId: String) {
        val view = Ui.column(ctx)
        private val chips = LinearLayout(ctx)
        private val shown = HashSet<String>()
        init {
            chips.orientation = LinearLayout.HORIZONTAL
            val h = HorizontalScrollView(ctx); h.isHorizontalScrollBarEnabled = false; h.addView(chips)
            val title = Ui.text(ctx, "FILES CHANGED", 10.5f, Ui.fg3).also { it.letterSpacing = 0.06f }
            val review = Ui.button(ctx, R.drawable.ic_review, "Review", "Show these changes inline in the editor, with Keep and Undo for each change") { if (runId.isNotEmpty()) host.reviewRun(runId) }
            val undo = Ui.button(ctx, R.drawable.ic_undo, "Undo all", "Undo every file change of this reply") { undoAll() }
            val head = Ui.row(ctx, title, View(ctx), review, undo)
            head.getChildAt(1).layoutParams = Ui.lp(0, 1, 1f)
            (undo.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(4)
            view.addView(head, Ui.lp(Ui.match, Ui.wrap))
            view.addView(h, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(3) })
        }
        fun add(path: String, kind: String) {
            if (!shown.add(path)) return
            val c = chip(path + "  " + kind, null)
            c.setOnClickListener { host.openFileAt(path, 0) }
            c.tooltipText = "Open $path (the changes show inline, with Keep and Undo)"
            chips.addView(c, Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = dp(5) })
        }
        private fun undoAll() {
            if (runId.isEmpty()) return
            AlertDialog.Builder(ctx).setTitle("Undo every file change of this reply?").setPositiveButton("Undo all") { _, _ ->
                Api.async("revert-all", { Api.post("/api/revert_all", JSONObject().put("run_id", runId)) }) { r, e ->
                    if (r == null) { Ui.toast(ctx, "Not undone: " + (e?.message ?: "")); return@async }
                    for (p in shown) { host.fileChanged(p); host.endReview(p) }
                    host.refreshTree()
                    Ui.toast(ctx, (r.optJSONArray("messages")?.length() ?: 0).toString() + " files restored.")
                }
            }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun todosBlock(b: JSONObject): View {
        val box = Ui.column(ctx)
        box.background = Ui.rounded(Ui.bg2, 8, Ui.border)
        box.setPadding(dp(10), dp(7), dp(10), dp(7))
        val todos = b.optJSONArray("todos")?.objects() ?: emptyList()
        val done = todos.count { it.optString("status") == "completed" }
        box.addView(Ui.text(ctx, "TO-DO LIST, $done OF ${todos.size} DONE", 11f, Ui.fg3).also { it.letterSpacing = 0.05f; it.setPadding(0, 0, 0, dp(3)) })
        for (t in todos) {
            val st = t.optString("status")
            val tick = Ui.text(ctx, when (st) { "completed" -> "✓"; "in_progress" -> "▸"; else -> "○" }, 12f, when (st) { "completed" -> Ui.green; "in_progress" -> Ui.accent; else -> Ui.fg3 })
            val text = SpannableStringBuilder(t.optString("content"))
            if (st == "completed") text.setSpan(StrikethroughSpan(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val tv = Markdown.textView(ctx, text, 12f, when (st) { "in_progress" -> Ui.fg; "completed" -> Ui.fg3; else -> Ui.fg2 })
            val row = Ui.row(ctx, tick, tv)
            row.gravity = Gravity.TOP
            tick.layoutParams = Ui.lp(dp(16), Ui.wrap).also { it.rightMargin = dp(6) }
            tv.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            box.addView(row, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(2) })
        }
        return box
    }

    private fun askCard(question: String): View {
        val box = Ui.column(ctx, Ui.text(ctx, "Forge asks", 12.5f, Ui.blue, bold = true), Markdown.textView(ctx, question, 13f, Ui.fg).also { it.setPadding(0, dp(3), 0, 0) })
        box.background = Ui.rounded(blueSoft, 8, Ui.blue)
        box.setPadding(dp(10), dp(8), dp(10), dp(8))
        return box
    }

    /** What the vision model saw in an attached image (collapsible). */
    private fun visionBlock(b: JSONObject): View {
        val box = Ui.column(ctx)
        box.background = Ui.rounded(Ui.bg2, 8, Ui.border)
        val source = b.optString("source")
        val chev = Ui.text(ctx, "▸", 12f, Ui.blue)
        val head = Ui.row(ctx, chev, Ui.text(ctx, "👁 What the vision model sees in image " + b.optInt("number") + (if (source.isNotEmpty() && source != "unavailable" && source != "off") " ($source)" else ""), 12f, Ui.blue).also { it.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { lp -> lp.leftMargin = dp(6) } })
        head.setPadding(dp(10), dp(6), dp(10), dp(6))
        val body = Ui.column(ctx)
        body.setPadding(dp(12), 0, dp(12), dp(8))
        if (b.optString("description").isNotEmpty()) { body.addView(sectionLabel("description").also { it.setPadding(0, dp(4), 0, dp(2)) }); body.addView(Markdown.textView(ctx, b.optString("description"), 12f, Ui.fg2)) }
        else body.addView(Ui.text(ctx, "The vision model was not available, so only the recognized text was used.", 12f, Ui.fg3))
        if (b.optString("ocr").isNotEmpty()) { body.addView(sectionLabel("recognized text").also { it.setPadding(0, dp(6), 0, dp(2)) }); body.addView(Markdown.textView(ctx, b.optString("ocr"), 11.5f, Ui.fg2, mono = true)) }
        val scroller = MaxHeightScroll(ctx, dp(300)); scroller.addView(body, FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        scroller.visibility = View.GONE
        head.setOnClickListener { val on = scroller.visibility != View.VISIBLE; scroller.visibility = if (on) View.VISIBLE else View.GONE; chev.text = if (on) "▾" else "▸" }
        box.addView(head, Ui.lp(Ui.match, Ui.wrap)); box.addView(scroller, Ui.lp(Ui.match, Ui.wrap))
        return box
    }

    private fun badge(sb: SpannableStringBuilder, text: String, color: Int, bg: Int) {
        val start = sb.length
        sb.append(" ").append(text).append(" ")
        sb.setSpan(BackgroundColorSpan(bg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    private fun muted(sb: SpannableStringBuilder, text: String) {
        val start = sb.length; sb.append(text)
        sb.setSpan(ForegroundColorSpan(Ui.fg3), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** One exchange with the vision model: who asks, the image, the question, the streaming answer, the time. */
    private inner class ExchangeCard(val b: JSONObject) {
        val view = Ui.column(ctx)
        private val head = Ui.text(ctx, "", 12.5f, Ui.fg)
        private val thumb = ImageView(ctx)
        private val question = Markdown.textView(ctx, "", 12.5f, Ui.fg)
        private val ansHead = Ui.text(ctx, "", 12.5f, Ui.fg)
        private val status = Ui.text(ctx, "", 11.5f, Ui.fg3)
        private val ans = Markdown.textView(ctx, "", 12.5f, Ui.fg)
        private val ocr = Ui.column(ctx)
        private val error = Ui.text(ctx, "", 11.5f, Ui.red)
        private val foot = Ui.text(ctx, "", 11.5f, Ui.fg3)
        private var loadedUrl = ""

        init {
            view.background = Ui.rounded(purpleSoft, 10, purpleLine)
            view.setPadding(dp(10), dp(8), dp(10), dp(8))
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.background = Ui.rounded(Ui.bg, 5, border2)
            thumb.visibility = View.GONE
            question.background = Ui.rounded(accentSoft, 8, accentLine)
            question.setPadding(dp(9), dp(5), dp(9), dp(5))
            val row = Ui.row(ctx, thumb, question)
            row.gravity = Gravity.TOP
            thumb.layoutParams = Ui.lp(dp(72), dp(54)).also { it.rightMargin = dp(8) }
            question.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            ans.background = Ui.rounded(purpleSoft, 8, purpleLine)
            ans.setPadding(dp(9), dp(6), dp(9), dp(6))
            view.addView(head, Ui.lp(Ui.match, Ui.wrap))
            view.addView(row, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) })
            view.addView(ansHead, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) })
            view.addView(status, Ui.lp(Ui.match, Ui.wrap))
            view.addView(ans, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(4) })
            view.addView(ocr, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(4) })
            view.addView(error, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(4) })
            view.addView(foot, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) })
            refresh()
        }

        private fun visionName(): String = if (b.optString("vision_label").isNotEmpty()) modelName("vision", b.optString("vision_label")) else "Vision model"

        fun refresh() {
            val llama = b.optString("asker") == "llama"
            val answered = b.optString("state") == "answered"
            val hb = SpannableStringBuilder()
            if (llama) badge(hb, "Llama 3.1", Ui.accent, accentSoft) else badge(hb, "Forge", Ui.fg2, bg4)
            hb.append(" asks ")
            badge(hb, if (b.optString("vision_label").isNotEmpty()) visionName() else "the vision model", Ui.purple, purpleSoft)
            muted(hb, if (llama) " (Llama cannot see images, so it asks for help)" else " to describe image " + b.optInt("number") + " for Llama 3.1, which cannot see images")
            head.text = hb
            question.text = if (llama) b.optString("question") else "Describe this image precisely for a programmer who cannot see it.\n" + b.optString("question")
            if (!llama) question.setTextColor(Ui.fg2)
            val url = b.optString("image_url")
            if (url.isNotEmpty() && url != loadedUrl) {
                loadedUrl = url
                thumb.visibility = View.VISIBLE
                thumbs.load(url, 200) { bmp -> if (bmp != null) thumb.setImageBitmap(bmp) }
                thumb.setOnClickListener { showImage(url) }
            }
            val ah = SpannableStringBuilder()
            badge(ah, visionName(), Ui.purple, purpleSoft)
            ah.append(if (answered) (if (b.optString("answer").isNotEmpty()) " answers" + (if (b.optBoolean("cached")) " (saved answer from earlier)" else "") else " could not answer") else " is working")
            if (b.has("seconds") && !b.isNull("seconds")) muted(ah, ",  " + b.optDouble("seconds") + " s")
            ansHead.text = ah
            val stText = b.optString("status").ifEmpty { if (answered) "" else "Waiting for the vision model…" }
            status.text = stText; status.visibility = if (stText.isEmpty()) View.GONE else View.VISIBLE
            val answer = b.optString("answer")
            ans.text = answer; ans.visibility = if (answer.isEmpty()) View.GONE else View.VISIBLE
            ocr.removeAllViews()
            val o = b.optString("ocr")
            if (o.isNotEmpty()) {
                val label = Ui.text(ctx, "▸ Text recognition (tesseract) also read " + o.length + " characters", 11.5f, Ui.fg3)
                val body = Markdown.textView(ctx, o, 11.5f, Ui.fg2, mono = true); body.visibility = View.GONE
                label.setOnClickListener { val on = body.visibility != View.VISIBLE; body.visibility = if (on) View.VISIBLE else View.GONE; label.text = (if (on) "▾" else "▸") + label.text.toString().substring(1) }
                ocr.addView(label); ocr.addView(body)
            }
            val err = b.optString("error")
            error.text = err; error.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE
            if (answered) {
                val fb = SpannableStringBuilder()
                badge(fb, "Llama 3.1", Ui.accent, accentSoft)
                fb.append(if (llama) " gets this answer back as the result of its look_at_image call and continues."
                    else (if (answer.isNotEmpty()) " reads this description" else " reads") + (if (o.isNotEmpty()) " and the recognized text" else "") + " together with your message.")
                foot.text = fb; foot.visibility = View.VISIBLE
            } else foot.visibility = View.GONE
        }

        fun appendAnswer(delta: String) {
            b.put("answer", b.optString("answer") + delta)
            ans.text = b.optString("answer"); ans.visibility = View.VISIBLE
        }
    }

    private fun approvalTitle(tool: String): String = when (tool) {
        "run_command" -> "Run this command?"
        "delete_path" -> "Move to trash?"
        "remember" -> "Save this memory?"
        "write_file", "edit_file" -> "Change a file outside this project?"
        else -> "Allow $tool?"
    }

    /** Forge asks before a command, a deletion, a memory or a file outside the project: Approve or Deny. */
    private fun approvalCard(a: JSONObject, resolved0: Boolean): View {
        val card = Ui.column(ctx)
        val tool = a.optString("tool")
        val args = a.optJSONObject("arguments") ?: JSONObject()
        val isWrite = tool == "write_file" || tool == "edit_file"
        val resolved = resolved0 || a.optString("decision").isNotEmpty()
        card.background = Ui.rounded(if (resolved) Ui.bg2 else accentSoft, 8, if (resolved) Ui.border else Ui.accent)
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        card.addView(Ui.text(ctx, approvalTitle(tool), 12.5f, Ui.fg, bold = true))
        if (a.optString("reason").isNotEmpty()) card.addView(Ui.text(ctx, a.optString("reason"), 11.5f, Ui.fg3).also { it.setPadding(0, dp(2), 0, 0) })
        val editable = when (tool) { "run_command" -> "command"; "remember" -> "memory"; else -> null }
        val text = if (editable != null) args.optString(editable)
            else if (isWrite) args.optString("path") + "\n\n" + (if (tool == "write_file") "New content:\n" + args.optString("content").take(1500)
                else "Replace:\n" + args.optString("old_text").take(700) + "\n\nWith:\n" + args.optString("new_text").take(700))
            else args.toString()
        var field: EditText? = null
        if (!resolved && editable != null) {
            val e = Ui.input(ctx, "", mono = true, multiline = true)
            e.setText(text); e.minLines = 1; e.maxLines = 6
            e.background = Ui.rounded(Ui.bg, 6, Ui.border)
            card.addView(e, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) })
            field = e
            if (tool == "remember") card.addView(Ui.text(ctx, "Scope: " + (if (args.optString("scope") == "global") "every folder" else "this project") + ". You can edit it before saving.", 11.5f, Ui.fg3))
        } else {
            val p = Markdown.textView(ctx, text, 12f, Ui.fg2, mono = true)
            p.background = Ui.rounded(Ui.bg, 6); p.setPadding(dp(8), dp(6), dp(8), dp(6))
            val s = MaxHeightScroll(ctx, dp(160)); s.addView(p, FrameLayout.LayoutParams(Ui.match, Ui.wrap))
            card.addView(s, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) })
        }
        if (resolved) {
            card.addView(statusLine(when (a.optString("decision")) { "approved" -> "Approved"; "denied" -> "Denied"; else -> "" }))
            card.alpha = 0.75f
            return card
        }
        val actions = Ui.row(ctx)
        fun decide(decision: String) {
            val edited = field?.text?.toString()?.trim()
            val changed = if (editable != null && edited != null && edited != text) JSONObject().put(editable, edited) else null
            val body = JSONObject().put("run_id", a.optString("run_id")).put("approval_id", a.optString("approval_id")).put("decision", decision)
            if (changed != null) body.put("arguments", changed)
            Api.async("approve", { Api.post("/api/approve", body) }) { _, e -> if (e != null) Ui.toast(ctx, "Could not send the decision: " + e.message) }
            a.put("decision", if (decision == "deny") "denied" else "approved")
            card.removeView(actions)
            card.addView(statusLine(if (decision == "deny") "Denied" else "Approved"))
            card.background = Ui.rounded(Ui.bg2, 8, Ui.border); card.alpha = 0.75f
            field?.let { f -> f.isEnabled = false }
        }
        actions.addView(Ui.button(ctx, "Approve", primary = true) { decide("approve") })
        if (tool != "remember") actions.addView(Ui.button(ctx, "Always allow this chat") { decide("always") }, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) })
        actions.addView(Ui.button(ctx, "Deny") { decide("deny") }, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) })
        card.addView(actions, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(8) })
        return card
    }

    private fun statLine(s: JSONObject): View {
        val parts = ArrayList<String>()
        if (s.optDouble("gen_tps", 0.0) > 0) parts.add(s.optDouble("gen_tps").toString() + " tokens per second")
        if (s.optInt("completion_tokens") > 0) parts.add(s.optInt("completion_tokens").toString() + " tokens out")
        if (s.optInt("prompt_tokens") > 0) parts.add(s.optInt("prompt_tokens").toString() + " in")
        if (s.has("first_token_s") && !s.isNull("first_token_s")) parts.add("first token after " + s.optDouble("first_token_s") + " s")
        if (s.optDouble("total_s", 0.0) > 0) parts.add(s.optDouble("total_s").toString() + " s in total")
        return Ui.text(ctx, parts.joinToString(", "), 11f, Ui.fg3).also { it.setPadding(0, dp(3), 0, 0) }
    }

    /** A Retry button under a failed message: sends the same message again. [before] runs first (it may remove the failed attempt). */
    private fun retryRow(tab: Tab, item: JSONObject, before: (() -> Unit)?): View {
        val row = Ui.row(ctx)
        row.setPadding(0, dp(4), 0, dp(6))
        // A real button: a filled accent pill with an icon, so it reads as something to press.
        val label = Ui.text(ctx, "Retry", 13f, Ui.onAccent, bold = true)
        val btn = Ui.row(ctx, Ui.iconView(ctx, R.drawable.ic_refresh, Ui.onAccent, 16), label)
        label.setPadding(dp(6), 0, 0, 0)
        btn.background = Ui.states(Ui.rounded(Ui.accent, 8), Ui.rounded(Ui.accent2, 8), Ui.rounded(Ui.accent2, 8))
        btn.setPadding(dp(14), dp(7), dp(16), dp(7))
        btn.isClickable = true; btn.isFocusable = true
        btn.tooltipText = "Send the same message again"
        btn.setOnClickListener {
            if (tab.run != null || tab.pending) { Ui.toast(ctx, "Wait until this chat has finished, or stop it first."); return@setOnClickListener }
            (row.parent as? ViewGroup)?.removeView(row)
            before?.invoke()
            val copy = JSONObject().put("text", item.optString("text")).put("attachments", JSONArray(item.optJSONArray("attachments")?.toString() ?: "[]")).put("mode", item.optString("mode", mode))
            startRun(tab, copy)
        }
        row.addView(btn, Ui.lp(Ui.wrap, Ui.wrap))
        return row
    }

    /** A saved assistant turn, rendered with the same cards as a live one. */
    private fun renderAssistant(turn: JSONObject): LinearLayout {
        val m = assistantBox()
        val blocks = turn.optJSONArray("blocks")?.objects() ?: emptyList()
        blocks.firstOrNull { it.optString("type") == "todos" }?.let { m.addView(todosBlock(it), cardMargins()) }
        for (b in blocks) {
            when (b.optString("type")) {
                "thinking" -> m.addView(ThinkingBlock(b.optString("text"), false).view, cardMargins())
                "text" -> { val holder = Ui.column(ctx); Markdown.render(ctx, b.optString("text"), holder, ::copyText); m.addView(holder) }
                "exchange" -> m.addView(ExchangeCard(b).view, cardMargins())
                "tool" -> m.addView(ToolCard(b).view, cardMargins())
                "approval" -> m.addView(approvalCard(b, true), cardMargins())
                "ask" -> m.addView(askCard(b.optString("question")), cardMargins())
                "vision" -> m.addView(visionBlock(b), cardMargins())
                "status" -> m.addView(statusLine(b.optString("text"), b.optBoolean("error")))
                "files" -> {
                    val files = b.optJSONArray("files")?.objects() ?: emptyList()
                    val fr = FilesRow(b.optString("run_id").ifEmpty { files.firstOrNull()?.optString("run_id") ?: "" })
                    for (f in files) fr.add(f.optString("path"), f.optString("kind"))
                    m.addView(fr.view, cardMargins())
                }
            }
        }
        turn.optJSONObject("stats")?.let { m.addView(statLine(it)) }
        return m
    }

    // ---------------------------------------------------------------- attachments and the composer
    fun setPromptText(s: String) { prompt.setText(s); prompt.setSelection(s.length) }

    fun addAttachment(record: JSONObject) {
        attachments.add(record)
        renderAttachments()
        prompt.requestFocus()
    }

    private fun renderAttachments() {
        attachRow.removeAllViews()
        for (a in attachments) {
            val c = chip(contextLabel(a)) { attachments.remove(a); renderAttachments() }
            attachRow.addView(c, Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = dp(5) })
        }
        attachScroll.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshComposer() {
        val busy = active?.run != null
        send.setImageDrawable(Ui.icon(ctx, if (busy) R.drawable.ic_stop else R.drawable.ic_send, Ui.onAccent))
        send.tooltipText = if (busy) "Stop the reply (Escape)" else "Send (Enter)"
        send.contentDescription = if (busy) "Stop" else "Send"
        prompt.hint = if (busy) "The agent is working. Enter queues your message, Ctrl Enter sends it now, Escape stops the reply."
                      else "Ask, or tell the agent what to build. Enter sends, Shift+Enter is a new line."
        sendNowButton.visibility = if (busy) View.VISIBLE else View.GONE
        queueButton.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun stopActive() { val t = active ?: return; cancelRun(t) }

    private fun cancelRun(t: Tab) {
        val r = t.run ?: return
        Api.async("cancel", { Api.post("/api/cancel", JSONObject().put("run_id", r.id)) }) { _, _ -> }
    }

    /** Sends the prompt; while a reply runs the message waits in the queue and goes out when the reply ends. */
    private fun send(now: Boolean = true) {
        val t = active
        if (t == null) { newChat { send() }; return }
        if (t.pending) return
        mentions.dismiss()
        var text = prompt.text.toString().trim()
        val slash = Regex("^/([\\w-]+)\\s*([\\s\\S]*)$").find(text)
        if (slash != null) {
            val name = slash.groupValues[1]; val rest = slash.groupValues[2]
            if (!mentions.commandsReady()) { mentions.loadNow { send() }; return }
            val cmd = mentions.command(name)
            if (cmd != null && (cmd.optBoolean("builtin") || rest.isEmpty())) { prompt.setText(""); runCommand(cmd, rest); return }
            if (cmd != null) text = cmd.optString("prompt").trim() + "\n\n" + rest
        }
        // Mentions typed by hand work like ones picked from the menu.
        for (m in Regex("(?:^|\\s)@(codebase|web|git)\\b").findAll(text)) {
            val k = m.groupValues[1]
            if (attachments.none { it.optString("kind") == k }) attachments.add(JSONObject().put("kind", k).put("path", "@$k").put("name", "@$k"))
        }
        if (Regex("(?:^|\\s)@terminal\\b").containsMatchIn(text) && attachments.none { it.optString("kind") == "terminal" }) {
            val out = host.terminalText()
            if (out.isNotBlank()) attachments.add(JSONObject().put("kind", "terminal").put("text", out).put("name", "Terminal output"))
        }
        if (text.isEmpty() && attachments.any { it.optString("kind") == "image" }) text = "Look at the attached image."
        if (text.isEmpty() && attachments.isNotEmpty()) text = "Look at the attached context."
        if (text.isEmpty()) return
        val atts = ArrayList(attachments)
        // Like Cursor, the model is told which file is open and where the cursor is (name and line only).
        val viewing = host.activeFile()
        if (viewing.isNotEmpty() && atts.none { it.optString("path") == viewing }) {
            val line = host.activeEditor()?.cursorLineCol()?.first?.plus(1)
            atts.add(JSONObject().put("kind", "editor").put("path", viewing).put("line", line ?: JSONObject.NULL))
        }
        val item = JSONObject().put("text", text).put("mode", mode).put("attachments", Api.jsonArrayOf(atts))
        prompt.setText(""); t.draft = ""
        prefs.edit().remove(draftKey()).apply()
        attachments.clear(); renderAttachments()
        if (t.run != null) {
            if (now) {
                // Send now: the running reply stops, and this message goes out first as soon as it has ended.
                t.queue.add(0, item); renderQueue(); cancelRun(t)
                host.flashStatus("Stopping the current reply and sending your message.")
            } else { t.queue.add(item); renderQueue(); host.flashStatus("Queued. It is sent when the current reply finishes.") }
            return
        }
        startRun(t, item)
    }

    /** The queued messages of the active chat, above the prompt, each with a button to remove it. */
    private fun renderQueue() {
        queueBox.removeAllViews()
        val t = active
        if (t == null || t.queue.isEmpty()) { queueBox.visibility = View.GONE; return }
        queueBox.visibility = View.VISIBLE
        for (item in t.queue.toList()) {
            val label = Ui.text(ctx, item.optString("text").replace('\n', ' '), 12f, Ui.fg2)
            label.isSingleLine = true; label.ellipsize = TextUtils.TruncateAt.END
            val icon = Ui.iconView(ctx, R.drawable.ic_queue, Ui.fg3, 14)
            val remove = Ui.miniButton(ctx, R.drawable.ic_close, "Remove from the queue") { t.queue.remove(item); renderQueue() }
            val row = Ui.row(ctx, icon, label, remove)
            label.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(6) }
            row.background = Ui.rounded(Ui.bg3, 6, border2)
            row.setPadding(dp(8), dp(2), dp(4), dp(2))
            queueBox.addView(row, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = dp(3) })
        }
    }

    // ---------------------------------------------------------------- a reply and its event stream
    private fun startRun(tab: Tab, item: JSONObject) {
        if (tab === active) emptyView.visibility = View.GONE
        val now = System.currentTimeMillis()
        val userTurn = JSONObject().put("role", "user").put("text", item.optString("text")).put("attachments", item.optJSONArray("attachments") ?: JSONArray()).put("ts", now)
        tab.transcript.put(userTurn)
        val userEl = userBubble(userTurn)
        tab.pane.addView(userEl)
        if (tab.title == "New chat" || tab.title.isEmpty()) { tab.title = item.optString("text").take(60); renderTabBar() }
        val turn = JSONObject().put("role", "assistant").put("blocks", JSONArray()).put("mode", item.optString("mode", mode)).put("ts", now)
        tab.transcript.put(turn)
        val msgEl = assistantBox()
        tab.pane.addView(msgEl)
        val spinner = statusLine("Thinking…")
        msgEl.addView(spinner)
        val R = Run(tab, msgEl, turn, item, userTurn, userEl)
        R.spinner = spinner
        tab.pending = true
        followBottom(tab, true)
        val body = JSONObject().put("session_id", tab.id).put("message", item.optString("text")).put("mode", item.optString("mode", mode))
            .put("attachments", item.optJSONArray("attachments") ?: JSONArray()).put("model", tab.modelChoice)
        Api.async("chat", { Api.post("/api/chat", body, 120000) }) { r, e ->
            tab.pending = false
            if (r == null) {
                val msg = "Error: " + (e?.message ?: "unknown")
                spinner.text = msg; spinner.setTextColor(Ui.red)
                turn.getJSONArray("blocks").put(JSONObject().put("type", "status").put("text", msg).put("error", true))
                msgEl.addView(retryRow(tab, item) {
                    removeTurn(tab, userTurn); removeTurn(tab, turn)
                    tab.pane.removeView(userEl); tab.pane.removeView(msgEl)
                })
                saveTranscript(tab)
                return@async
            }
            userTurn.put("run_id", r.optString("run_id"))
            R.id = r.optString("run_id")
            tab.run = R
            tab.models.clear()
            tab.models["llama"] = ModelState("reading", "", "Starting", System.currentTimeMillis())
            renderStrip(); startStripTicker(); renderTabBar(); refreshComposer()
            R.stream = Api.EventStream(R.id) { ev -> handle(R, ev) }.also { it.start() }
        }
    }

    private fun removeTurn(tab: Tab, turn: JSONObject) {
        for (i in 0 until tab.transcript.length()) if (tab.transcript.opt(i) === turn) { tab.transcript.remove(i); return }
    }

    private fun blocks(R: Run): JSONArray = R.turn.getJSONArray("blocks")

    private fun removeSpinner(R: Run) { R.spinner?.let { R.msgEl.removeView(it); R.spinner = null } }

    private fun closeCurrent(R: Run) {
        val kind = R.curKind
        if (kind.isEmpty()) return
        val text = R.curText.toString()
        R.curBlock?.put("text", text)
        if (kind == "text") {
            R.live?.finish(text)
            if (text.isBlank()) { R.liveHolder?.let { R.msgEl.removeView(it) }; R.curBlock?.let { b -> removeBlock(R, b) } }
        } else if (kind == "thinking") R.thinking?.close(text)
        R.cur(null, "")
        R.live = null; R.liveHolder = null; R.thinking = null
    }

    private fun Run.cur(block: JSONObject?, kind: String) { curBlock = block; curKind = kind; curText.setLength(0) }

    private fun removeBlock(R: Run, b: JSONObject) {
        val arr = blocks(R)
        for (i in 0 until arr.length()) if (arr.opt(i) === b) { arr.remove(i); return }
    }

    private fun startBlock(R: Run, kind: String) {
        closeCurrent(R); removeSpinner(R)
        val block = JSONObject().put("type", kind).put("text", "")
        blocks(R).put(block)
        R.cur(block, kind)
        if (kind == "thinking") {
            val tb = ThinkingBlock("", true); R.thinking = tb
            R.msgEl.addView(tb.view, cardMargins())
        } else {
            val holder = Ui.column(ctx); R.liveHolder = holder
            R.live = Markdown.Live(ctx, holder, ::copyText)
            R.msgEl.addView(holder)
        }
        followBottom(R.tab, false)
    }

    private fun handle(R: Run, ev: JSONObject) {
        if (R.tab.run !== R) return
        val tab = R.tab
        when (ev.optString("type")) {
            "thinking_delta" -> { if (R.curKind != "thinking") startBlock(R, "thinking"); R.curText.append(ev.optString("text")); R.thinking?.update(R.curText.toString()) }
            "text_delta" -> { if (R.curKind != "text") startBlock(R, "text"); R.curText.append(ev.optString("text")); R.live?.update(R.curText.toString()) }
            "tool_call_start" -> closeCurrent(R)
            "model_activity" -> {
                val m = ev.optString("model")
                val prev = tab.models[m]
                tab.models[m] = ModelState(ev.optString("state"), ev.optString("label").ifEmpty { prev?.label ?: "" }, ev.optString("detail"), System.currentTimeMillis())
                val xid = ev.optString("exchange")
                val x = if (xid.isNotEmpty()) R.exchanges[xid] else null
                if (x != null) {
                    if (m == "vision" && ev.optString("label").isNotEmpty()) x.b.put("vision_label", ev.optString("label"))
                    x.b.put("status", (if (m == "llama") "Llama 3.1: " else if (m == "ocr") "Text recognition: " else "") + ev.optString("detail").ifEmpty { stateText(m, ev.optString("state")) })
                    if (x.b.optString("state") != "answered") x.refresh()
                }
                if (tab === active) renderStrip()
            }
            "vision_exchange" -> {
                closeCurrent(R); removeSpinner(R)
                if (ev.optString("phase") == "ask") {
                    val b = JSONObject().put("type", "exchange").put("id", ev.optString("id")).put("asker", ev.optString("asker")).put("question", ev.optString("question"))
                        .put("number", ev.optInt("number")).put("image_url", ev.optString("image_url")).put("answer", "").put("state", "asking")
                    blocks(R).put(b)
                    val card = ExchangeCard(b)
                    R.exchanges[ev.optString("id")] = card
                    R.msgEl.addView(card.view, cardMargins())
                    followBottom(tab, false)
                } else if (ev.optString("phase") == "answer") {
                    val x = R.exchanges[ev.optString("id")] ?: return
                    x.b.put("state", "answered").put("answer", ev.optString("text").ifEmpty { x.b.optString("answer") }).put("ocr", ev.optString("ocr"))
                        .put("source", ev.optString("source")).put("cached", ev.optBoolean("cached")).put("error", ev.optString("error")).put("status", "")
                    if (ev.has("seconds")) x.b.put("seconds", ev.optDouble("seconds"))
                    val src = ev.optString("source")
                    if (x.b.optString("vision_label").isEmpty() && src.isNotEmpty() && src != "unavailable" && src != "off") x.b.put("vision_label", src)
                    x.refresh()
                    followBottom(tab, false)
                }
            }
            "vision_delta" -> { val x = R.exchanges[ev.optString("exchange")] ?: return; x.appendAnswer(ev.optString("text")); followBottom(tab, false) }
            "tool_call" -> {
                closeCurrent(R); removeSpinner(R)
                val t = JSONObject().put("type", "tool").put("call_id", ev.optString("call_id")).put("name", ev.optString("name")).put("arguments", ev.optJSONObject("arguments") ?: JSONObject()).put("state", "running").put("result", "")
                blocks(R).put(t)
                val card = ToolCard(t)
                R.tools[ev.optString("call_id")] = card
                R.msgEl.addView(card.view, cardMargins())
                followBottom(tab, false)
            }
            "tool_result" -> {
                val card = R.tools[ev.optString("call_id")] ?: return
                card.setResult(ev.optBoolean("ok"), ev.optString("result"), if (ev.has("seconds") && !ev.isNull("seconds")) ev.optDouble("seconds") else null)
                val name = ev.optString("name")
                if (name == "write_file" || name == "edit_file") {
                    val p = card.block.optJSONObject("arguments")?.optString("path") ?: ""
                    host.fileChanged(p)
                    val res = ev.optString("result")
                    host.reportCheck(p, if (res.contains("SYNTAX CHECK FAILED")) res.substring(res.indexOf("SYNTAX CHECK FAILED")) else "")
                }
                followBottom(tab, false)
            }
            "file_changed" -> {
                var fb: JSONObject? = null
                for (b in blocks(R).objects()) if (b.optString("type") == "files") fb = b
                if (fb == null) { fb = JSONObject().put("type", "files").put("files", JSONArray()).put("run_id", ev.optString("run_id")); blocks(R).put(fb); R.filesRow = FilesRow(ev.optString("run_id", R.id)); R.msgEl.addView(R.filesRow!!.view, cardMargins()) }
                val files = fb.getJSONArray("files")
                var found = false
                for (i in 0 until files.length()) if (files.getJSONObject(i).optString("path") == ev.optString("path")) { files.put(i, JSONObject().put("path", ev.optString("path")).put("kind", ev.optString("kind")).put("run_id", ev.optString("run_id"))); found = true }
                if (!found) files.put(JSONObject().put("path", ev.optString("path")).put("kind", ev.optString("kind")).put("run_id", ev.optString("run_id")))
                R.filesRow?.add(ev.optString("path"), ev.optString("kind"))
                // The editor reloads the file and shows the change under review (green and red lines, Keep and Undo).
                host.agentChanged(ev.optString("run_id", R.id), ev.optString("path"))
            }
            "todos" -> {
                var b: JSONObject? = null
                for (x in blocks(R).objects()) if (x.optString("type") == "todos") b = x
                if (b == null) { b = JSONObject().put("type", "todos"); blocks(R).put(b) }
                b.put("todos", ev.optJSONArray("todos") ?: JSONArray())
                val fresh = todosBlock(b)
                val old = R.todosView
                if (old != null) { val idx = R.msgEl.indexOfChild(old); R.msgEl.removeView(old); R.msgEl.addView(fresh, idx, cardMargins()) } else R.msgEl.addView(fresh, 0, cardMargins())
                R.todosView = fresh
            }
            "approval_required" -> {
                closeCurrent(R)
                val a = JSONObject().put("type", "approval").put("run_id", ev.optString("run_id")).put("approval_id", ev.optString("approval_id")).put("tool", ev.optString("tool"))
                    .put("arguments", ev.optJSONObject("arguments") ?: JSONObject()).put("reason", ev.optString("reason"))
                blocks(R).put(a)
                R.msgEl.addView(approvalCard(a, false), cardMargins())
                followBottom(tab, true)
                if (tab !== active) { tab.attention = true; renderTabBar() }
                if (tab !== active || !host.inFront()) host.notifyUser("Forge needs your approval", approvalTitle(ev.optString("tool")) + ": " + argSummary(ev.optString("tool"), ev.optJSONObject("arguments")))
            }
            "ask_user" -> {
                closeCurrent(R)
                blocks(R).put(JSONObject().put("type", "ask").put("question", ev.optString("question")))
                R.msgEl.addView(askCard(ev.optString("question")), cardMargins())
                if (tab === active) prompt.requestFocus()
                followBottom(tab, false)
            }
            "image_analysis" -> {
                closeCurrent(R); removeSpinner(R)
                val b = JSONObject().put("type", "vision").put("number", ev.optInt("number")).put("source", ev.optString("source")).put("description", ev.optString("description")).put("ocr", ev.optString("ocr"))
                blocks(R).put(b)
                R.msgEl.addView(visionBlock(b), cardMargins())
                followBottom(tab, false)
            }
            "tool_error" -> { blocks(R).put(JSONObject().put("type", "status").put("text", ev.optString("message")).put("error", true)); R.msgEl.addView(statusLine(ev.optString("message"), true)) }
            "status" -> { closeCurrent(R); blocks(R).put(JSONObject().put("type", "status").put("text", ev.optString("text"))); R.msgEl.addView(statusLine(ev.optString("text"))); followBottom(tab, false) }
            "error" -> {
                closeCurrent(R)
                val msg = "Error: " + ev.optString("message")
                blocks(R).put(JSONObject().put("type", "status").put("text", msg).put("error", true))
                R.msgEl.addView(statusLine(msg + (if (ev.optString("detail").isNotEmpty()) "\n" + ev.optString("detail") else ""), true))
                R.msgEl.addView(retryRow(tab, R.item, null))
                followBottom(tab, false)
                // The server sends "end" after its own errors; a lost connection sends nothing more, so finish here.
                if (ev.optString("message").startsWith("Connection to the Forge server was lost")) finishRun(R)
            }
            "stats" -> {
                R.turn.put("stats", ev)
                val parts = ArrayList<String>()
                if (ev.optDouble("gen_tps", 0.0) > 0) parts.add(String.format("%.1f tok/s", ev.optDouble("gen_tps")))
                if (ev.optInt("context_estimate") > 0) parts.add("~" + ev.optInt("context_estimate") + " ctx")
                statsText.text = parts.joinToString(" · ")
            }
            "verbose" -> host.agentLog(ev.optString("kind"), ev.optString("text"))
            "end" -> {
                closeCurrent(R)
                R.turn.optJSONObject("stats")?.let { R.msgEl.addView(statLine(it)) }
                finishRun(R)
            }
        }
    }

    private fun finishRun(R: Run) {
        val tab = R.tab
        if (tab.run !== R) return
        R.stream?.stop()
        removeSpinner(R)
        closeCurrent(R)
        tab.run = null
        for (st in tab.models.values) if (BUSY.contains(st.state) || WAITING.contains(st.state) || st.state == "done") { st.state = "idle"; st.since = System.currentTimeMillis() }
        tab.modelsUntil = System.currentTimeMillis() + 8000
        if (tab !== active) tab.attention = true
        renderStrip(); renderTabBar(); refreshComposer(); updateJump()
        saveTranscript(tab)
        host.refreshTree()
        if (tab.queue.isNotEmpty()) {
            val next = tab.queue.removeAt(0)
            if (tab === active) renderQueue()
            Api.main.postDelayed({ if (tab.run == null && !tab.pending) startRun(tab, next) else tab.queue.add(0, next) }, 400)
        }
    }

    fun focusPrompt() { prompt.requestFocus() }

    fun stop() { for (t in tabs) t.run?.stream?.stop() }

    // ---------------------------------------------------------------- images
    private fun showImage(url: String) {
        val iv = ImageView(ctx)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(8), dp(8), dp(8), dp(8))
        val dialog = AlertDialog.Builder(ctx).setView(iv).setNegativeButton("Close", null).create()
        thumbs.load(url, 1200) { bmp -> if (bmp != null) iv.setImageBitmap(bmp) else Ui.toast(ctx, "Could not load the image") }
        dialog.show()
    }

    /** Small bitmaps from the server, loaded on a background thread with a size cap and kept in a small cache. */
    private class Thumbs {
        private val cache = LruCache<String, Bitmap>(24)

        fun load(url: String, maxPx: Int, into: (Bitmap?) -> Unit) {
            val key = "$url@$maxPx"
            val hit = cache.get(key)
            if (hit != null) { into(hit); return }
            Api.async("thumb", { fetch(url, maxPx) }) { bmp, _ -> if (bmp != null) cache.put(key, bmp); into(bmp) }
        }

        private fun fetch(url: String, maxPx: Int): Bitmap? {
            val full = if (url.startsWith("http://") || url.startsWith("https://")) url else Api.SERVER + url
            val c = URL(full).openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 10000
            try {
                if (c.responseCode != 200) return null
                if (c.contentLength > 25 * 1024 * 1024) return null
                val bytes = c.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options(); bounds.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) sample *= 2
                val opts = BitmapFactory.Options(); opts.inSampleSize = sample
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                val longest = Math.max(bmp.width, bmp.height)
                if (longest <= maxPx) return bmp
                val scale = maxPx.toFloat() / longest
                return Bitmap.createScaledBitmap(bmp, Math.max(1, (bmp.width * scale).toInt()), Math.max(1, (bmp.height * scale).toInt()), true)
            } finally { c.disconnect() }
        }
    }

    /** A scroll box that grows with its content up to a maximum height. */
    private class MaxHeightScroll(c: Context, private val maxH: Int) : ScrollView(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    /** Runs an action at most once per [ms] milliseconds on the main thread; [flush] runs it right away. */
    private class Throttle(private val ms: Long, private val action: () -> Unit) {
        private var last = 0L
        private var scheduled = false
        private val task = Runnable { scheduled = false; fire() }
        fun request() {
            if (scheduled) return
            val wait = ms - (SystemClock.uptimeMillis() - last)
            if (wait <= 0) fire() else { scheduled = true; Api.main.postDelayed(task, wait) }
        }
        fun flush() { Api.main.removeCallbacks(task); scheduled = false; fire() }
        private fun fire() { last = SystemClock.uptimeMillis(); action() }
    }
}
