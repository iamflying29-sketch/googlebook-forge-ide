package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The AI panel: a chat with the agent, rendered with native views. A reply streams in through the server's
 * events; the view follows the newest line while the reader is at the bottom and leaves the view alone when the
 * reader scrolled up (a "Jump to the latest" button brings it back).
 */
class ChatPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val title = Ui.text(ctx, "New chat", 13f, Ui.fg, bold = true)
    private val strip = Ui.text(ctx, "", 11.5f, Ui.fg2)
    private val messages = LinearLayout(ctx)
    private val scroll = ScrollView(ctx)
    private val jump = Ui.button(ctx, "Jump to the latest") { stick = true; toBottom() }
    private val prompt = Ui.input(ctx, "Ask, or tell the agent what to build. Enter sends, Shift+Enter is a new line.", multiline = true)
    private val send = Ui.button(ctx, "Send", primary = true) { sendOrStop() }
    private val modeSpinner = Spinner(ctx)
    private val modelSpinner = Spinner(ctx)
    private val attachRow = LinearLayout(ctx)
    private val attachments = ArrayList<JSONObject>()
    private var models: List<Pair<String, String>> = listOf("" to "Default")
    private var sessionId: String = ""
    private var run: Run? = null
    private var stick = true

    /** One running reply. */
    private inner class Run(val id: String, val box: LinearLayout, val item: JSONObject) {
        var stream: Api.EventStream? = null
        var cur: TextView? = null
        var curKind: String = ""
        var curText = StringBuilder()
        val tools = HashMap<String, Pair<LinearLayout, TextView>>()
        val exchanges = HashMap<String, TextView>()
        var spinner: TextView? = null
    }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg2)
        val pad = Ui.dp(ctx, 8)
        val head = Ui.row(ctx, title, Ui.button(ctx, "New chat") { newChat() }, Ui.button(ctx, "History") { showHistory() })
        title.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        head.setPadding(pad, pad / 2, pad, pad / 2)
        head.setBackgroundColor(Ui.bg)
        view.addView(head, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.divider(ctx, false))
        strip.setPadding(pad, 2, pad, 2); strip.visibility = View.GONE
        view.addView(strip, Ui.lp(Ui.match, Ui.wrap))
        messages.orientation = LinearLayout.VERTICAL
        messages.setPadding(pad, pad, pad, pad)
        scroll.addView(messages, android.widget.FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        scroll.isFillViewport = true
        view.addView(scroll, Ui.lp(Ui.match, 0, 1f))
        scroll.viewTreeObserver.addOnScrollChangedListener { stick = nearBottom(); jump.visibility = if (!stick && run != null) View.VISIBLE else View.GONE }
        messages.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (stick) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
        val jumpRow = Ui.row(ctx, jump); jumpRow.gravity = Gravity.CENTER_HORIZONTAL; jump.visibility = View.GONE
        view.addView(jumpRow, Ui.lp(Ui.match, Ui.wrap))
        attachRow.orientation = LinearLayout.HORIZONTAL; attachRow.setPadding(pad, 0, pad, 0)
        view.addView(attachRow, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.divider(ctx, false))
        prompt.minLines = 2; prompt.maxLines = 8
        prompt.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN && !ev.isShiftPressed) { sendOrStop(); true } else false
        }
        val composer = Ui.column(ctx, prompt)
        composer.setPadding(pad, pad / 2, pad, pad / 2)
        prompt.layoutParams = Ui.lp(Ui.match, Ui.wrap)
        val modes = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("Agent", "Ask", "Plan"))
        modeSpinner.adapter = modes
        modelSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("Default"))
        val tools = Ui.row(ctx, modeSpinner, modelSpinner,
            Ui.button(ctx, "Image") { host.pickImage() }, Ui.button(ctx, "Mic") { host.dictate(prompt) }, send)
        modelSpinner.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        tools.setPadding(pad, 0, pad, pad / 2)
        view.addView(composer, Ui.lp(Ui.match, Ui.wrap))
        view.addView(tools, Ui.lp(Ui.match, Ui.wrap))
        showEmpty()
    }

    private fun nearBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return child.bottom - (scroll.height + scroll.scrollY) < Ui.dp(ctx, 80)
    }

    private fun toBottom() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN); jump.visibility = View.GONE } }

    private fun showEmpty() {
        messages.removeAllViews()
        val t = Ui.text(ctx, "Ask Forge anything about this folder, or tell the agent what to build. Attach a screenshot with Image.", 13f, Ui.fg3)
        t.gravity = Gravity.CENTER; t.setPadding(0, Ui.dp(ctx, 40), 0, 0)
        messages.addView(t)
    }

    // ---------------------------------------------------------------- sessions
    fun load() {
        Api.async("sessions", { Api.get("/api/sessions") }) { r, _ ->
            val list = r?.optJSONArray("sessions")
            if (list != null && list.length() > 0) openSession(list.getJSONObject(0).optString("id")) else newChat()
        }
        loadModels()
    }

    private fun loadModels() {
        Api.async("models", { Pair(Api.get("/api/models"), Api.get("/api/status")) }) { r, _ ->
            if (r == null) return@async
            val out = ArrayList<Pair<String, String>>()
            val engine = r.second.optString("engine")
            out.add("" to (if (engine == "gpu") "Default (GPU engine)" else "Default (" + r.second.optString("model") + ")"))
            if (engine != "gpu") out.add("__gpu" to "GPU engine (Intel)")
            val arr = r.first.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) { val n = arr.getJSONObject(i).optString("name"); if (n.isNotEmpty()) out.add(n to n) }
            models = out
            modelSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, out.map { it.second })
        }
    }

    private fun newChat() {
        Api.async("session-new", { Api.post("/api/session/new", JSONObject().put("title", "New chat")) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Could not start a chat: " + (e?.message ?: "")); return@async }
            sessionId = r.optString("id"); title.text = r.optString("title", "New chat"); showEmpty()
        }
    }

    private fun openSession(id: String) {
        Api.async("session", { Api.get("/api/session", mapOf("id" to id)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Could not open the chat: " + (e?.message ?: "")); return@async }
            sessionId = id; title.text = r.optString("title", "Chat")
            messages.removeAllViews()
            val t = r.optJSONArray("transcript") ?: JSONArray()
            if (t.length() == 0) showEmpty()
            for (i in 0 until t.length()) renderSaved(t.getJSONObject(i))
            stick = true; toBottom()
        }
    }

    private fun showHistory() {
        Api.async("history", { Api.get("/api/sessions") }) { r, _ ->
            val list = r?.optJSONArray("sessions") ?: return@async
            val names = ArrayList<String>(); val ids = ArrayList<String>()
            for (i in 0 until list.length()) { val s = list.getJSONObject(i); names.add(s.optString("title")); ids.add(s.optString("id")) }
            AlertDialog.Builder(ctx).setTitle("Chats in this folder").setItems(names.toTypedArray()) { _, which -> openSession(ids[which]) }
                .setNegativeButton("Close", null).show()
        }
    }

    // ---------------------------------------------------------------- rendering
    private fun userBubble(text: String, atts: JSONArray?): View {
        val box = Ui.column(ctx)
        val t = Ui.text(ctx, text, 13f, Ui.fg)
        t.background = Ui.rounded(Ui.bg3, 10, Ui.border); t.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
        t.setTextIsSelectable(true)
        box.addView(t)
        if (atts != null) for (i in 0 until atts.length()) {
            val a = atts.getJSONObject(i)
            box.addView(Ui.text(ctx, (if (a.optString("kind") == "image") "Image: " else "File: ") + a.optString("name", a.optString("id")), 11.5f, Ui.fg3))
        }
        box.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
        return box
    }

    private fun markdown(text: String, into: LinearLayout) {
        // Fenced code becomes a monospace block; everything else is shown as text (headings and lists keep their marks).
        val parts = text.split("```")
        for ((i, part) in parts.withIndex()) {
            if (i % 2 == 1) {
                val body = part.substringAfter("\n", part).trimEnd()
                val code = Ui.text(ctx, body, 12f, Ui.fg, mono = true)
                code.background = Ui.rounded(Ui.bg, 6, Ui.border); code.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
                code.setTextIsSelectable(true)
                code.setOnLongClickListener { host.copyToClipboard(body); Ui.toast(ctx, "Copied"); true }
                into.addView(code, Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = 6; it.bottomMargin = 6 })
            } else if (part.isNotBlank()) {
                val t = Ui.text(ctx, part.trim(), 13f, Ui.fg); t.setTextIsSelectable(true)
                into.addView(t)
            }
        }
    }

    private fun thinkingBlock(text: String, live: Boolean): Pair<LinearLayout, TextView> {
        val box = Ui.column(ctx)
        val label = Ui.text(ctx, if (live) "Thinking…" else "Thought process", 11.5f, Ui.purple)
        val body = Ui.text(ctx, text, 12f, Ui.fg2); body.setTypeface(body.typeface, Typeface.ITALIC)
        body.maxLines = 12; body.visibility = if (live) View.VISIBLE else View.GONE
        label.setOnClickListener { body.visibility = if (body.visibility == View.GONE) View.VISIBLE else View.GONE }
        box.addView(label); box.addView(body)
        box.background = Ui.rounded(Ui.bg, 6, Ui.border); box.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        return box to body
    }

    private fun toolCard(name: String, args: JSONObject?): Pair<LinearLayout, TextView> {
        val card = Ui.column(ctx)
        val summary = when (name) {
            "run_command" -> args?.optString("command") ?: ""
            else -> args?.optString("path") ?: (args?.toString() ?: "")
        }
        val head = Ui.text(ctx, "$name  $summary", 12f, Ui.fg2, mono = true); head.maxLines = 2
        val state = Ui.text(ctx, "running", 11f, Ui.fg3)
        val result = Ui.text(ctx, "", 11.5f, Ui.fg2, mono = true); result.visibility = View.GONE; result.maxLines = 40; result.setTextIsSelectable(true)
        head.setOnClickListener { result.visibility = if (result.visibility == View.GONE) View.VISIBLE else View.GONE }
        card.addView(Ui.row(ctx, head.also { it.layoutParams = Ui.lp(0, Ui.wrap, 1f) }, state))
        card.addView(result)
        card.background = Ui.rounded(Ui.bg, 6, Ui.border); card.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
        card.tag = state
        return card to result
    }

    private fun statusLine(text: String, error: Boolean = false): TextView {
        val t = Ui.text(ctx, text, 12f, if (error) Ui.red else Ui.fg3); t.setPadding(0, 4, 0, 4); return t
    }

    private fun renderSaved(turn: JSONObject) {
        if (turn.optString("role") == "user") { messages.addView(userBubble(turn.optString("text"), turn.optJSONArray("attachments"))); return }
        val box = Ui.column(ctx); box.setPadding(0, 4, 0, 8)
        val blocks = turn.optJSONArray("blocks") ?: JSONArray()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            when (b.optString("type")) {
                "thinking" -> box.addView(thinkingBlock(b.optString("text"), false).first)
                "text" -> markdown(b.optString("text"), box)
                "tool" -> { val (card, res) = toolCard(b.optString("name"), b.optJSONObject("arguments")); res.text = b.optString("result"); (card.tag as TextView).text = if (b.optString("state") == "ok") "done" else b.optString("state"); box.addView(card) }
                "status" -> box.addView(statusLine(b.optString("text"), b.optBoolean("error")))
                "vision" -> box.addView(statusLine("Vision model: " + b.optString("description").take(300)))
                "exchange" -> box.addView(statusLine((b.optString("asker") + " asks the vision model: ").replaceFirstChar { it.uppercase() } + b.optString("answer").take(300)))
            }
        }
        messages.addView(box)
    }

    // ---------------------------------------------------------------- sending and the event stream
    fun setPromptText(s: String) { prompt.setText(s); prompt.setSelection(s.length) }

    fun addAttachment(record: JSONObject) {
        attachments.add(record)
        val chip = Ui.text(ctx, (if (record.optString("kind") == "image") "Image: " else "File: ") + record.optString("name", record.optString("id")) + "  x", 11.5f, Ui.fg2)
        chip.background = Ui.rounded(Ui.bg3, 10, Ui.border); chip.setPadding(Ui.dp(ctx, 8), 4, Ui.dp(ctx, 8), 4)
        chip.setOnClickListener { attachments.remove(record); attachRow.removeView(chip) }
        attachRow.addView(chip, Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = 6; it.topMargin = 4 })
    }

    private fun sendOrStop() {
        val r = run
        if (r != null) {
            Api.async("cancel", { Api.post("/api/cancel", JSONObject().put("run_id", r.id)) }) { _, _ -> }
            return
        }
        val text = prompt.text.toString().trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val item = JSONObject().put("text", text).put("mode", modeSpinner.selectedItem.toString().lowercase())
            .put("attachments", Api.jsonArrayOf(attachments.toList()))
            .put("model", models.getOrNull(modelSpinner.selectedItemPosition)?.first ?: "")
        prompt.setText(""); attachments.clear(); attachRow.removeAllViews()
        startRun(item)
    }

    private fun startRun(item: JSONObject) {
        if (messages.childCount == 1 && messages.getChildAt(0) is TextView && run == null && sessionId.isNotEmpty() && (messages.getChildAt(0) as TextView).text.startsWith("Ask Forge")) messages.removeAllViews()
        val userView = userBubble(item.optString("text"), item.optJSONArray("attachments"))
        messages.addView(userView)
        val box = Ui.column(ctx); box.setPadding(0, 4, 0, 8)
        val spinner = statusLine("Thinking…")
        box.addView(spinner)
        messages.addView(box)
        stick = true; toBottom()
        send.text = "Stop"
        val body = JSONObject().put("session_id", sessionId).put("message", item.optString("text")).put("mode", item.optString("mode"))
            .put("attachments", item.optJSONArray("attachments") ?: JSONArray()).put("model", item.optString("model"))
        Api.async("chat", { Api.post("/api/chat", body, 120000) }) { r, e ->
            if (r == null) {
                spinner.text = "Error: " + (e?.message ?: "unknown"); spinner.setTextColor(Ui.red)
                box.addView(Ui.button(ctx, "Retry") { messages.removeView(userView); messages.removeView(box); startRun(item) })
                send.text = "Send"
                return@async
            }
            val run0 = Run(r.optString("run_id"), box, item)
            run0.spinner = spinner
            run = run0
            run0.stream = Api.EventStream(run0.id) { ev -> handle(run0, ev) }.also { it.start() }
        }
    }

    private fun closeCurrent(r: Run) {
        val cur = r.cur ?: return
        if (r.curKind == "thinking") {
            (cur.parent as? LinearLayout)?.let { box -> (box.getChildAt(0) as? TextView)?.text = "Thought process"; cur.visibility = View.GONE }
        } else if (r.curKind == "text") {
            val parent = cur.parent as LinearLayout
            val idx = parent.indexOfChild(cur)
            parent.removeView(cur)
            val holder = Ui.column(ctx); markdown(r.curText.toString(), holder)
            parent.addView(holder, idx)
        }
        r.cur = null; r.curKind = ""; r.curText = StringBuilder()
    }

    private fun startBlock(r: Run, kind: String) {
        closeCurrent(r); r.spinner?.let { r.box.removeView(it); r.spinner = null }
        r.curKind = kind
        if (kind == "thinking") { val (box, body) = thinkingBlock("", true); r.box.addView(box); r.cur = body }
        else { val t = Ui.text(ctx, "", 13f, Ui.fg); r.box.addView(t); r.cur = t }
    }

    private fun handle(r: Run, ev: JSONObject) {
        when (ev.optString("type")) {
            "thinking_delta" -> { if (r.curKind != "thinking") startBlock(r, "thinking"); r.curText.append(ev.optString("text")); r.cur?.text = r.curText }
            "text_delta" -> { if (r.curKind != "text") startBlock(r, "text"); r.curText.append(ev.optString("text")); r.cur?.text = r.curText }
            "tool_call" -> {
                closeCurrent(r); r.spinner?.let { r.box.removeView(it); r.spinner = null }
                val (card, res) = toolCard(ev.optString("name"), ev.optJSONObject("arguments"))
                r.tools[ev.optString("call_id")] = card to res
                r.box.addView(card)
            }
            "tool_result" -> {
                val pair = r.tools[ev.optString("call_id")] ?: return
                pair.second.text = ev.optString("result")
                val ok = ev.optBoolean("ok")
                (pair.first.tag as TextView).text = if (ok) "done" + (if (ev.has("seconds")) " · " + ev.optDouble("seconds") + " s" else "") else "failed"
                if (!ok) pair.second.visibility = View.VISIBLE
                if (ev.optString("name") == "write_file" || ev.optString("name") == "edit_file") host.fileChanged(ev.optJSONObject("arguments")?.optString("path") ?: "")
            }
            "approval_required" -> {
                closeCurrent(r)
                val args = ev.optJSONObject("arguments")
                val card = Ui.card(ctx, Ui.text(ctx, "Forge needs your approval: " + ev.optString("tool"), 12.5f, Ui.accent, bold = true),
                    Ui.text(ctx, (args?.optString("command")?.takeIf { it.isNotEmpty() } ?: args?.toString(1) ?: "") + (if (ev.optString("reason").isNotEmpty()) "\n" + ev.optString("reason") else ""), 12f, Ui.fg2, mono = true))
                val buttons = Ui.row(ctx)
                fun decide(d: String) {
                    Api.async("approve", { Api.post("/api/approve", JSONObject().put("run_id", ev.optString("run_id")).put("approval_id", ev.optString("approval_id")).put("decision", d)) }) { _, _ -> }
                    buttons.removeAllViews(); card.addView(statusLine(if (d == "approve") "Approved" else "Denied"))
                }
                buttons.addView(Ui.button(ctx, "Approve", primary = true) { decide("approve") }); buttons.addView(Ui.button(ctx, "Deny") { decide("deny") })
                card.addView(buttons)
                r.box.addView(card)
            }
            "ask_user" -> { closeCurrent(r); r.box.addView(statusLine("Question: " + ev.optString("question"))); prompt.requestFocus() }
            "vision_exchange" -> {
                closeCurrent(r); r.spinner?.let { r.box.removeView(it); r.spinner = null }
                if (ev.optString("phase") == "ask") {
                    val card = Ui.column(ctx)
                    card.addView(Ui.text(ctx, (if (ev.optString("asker") == "llama") "The model asks the vision model" else "Forge asks the vision model to describe image " + ev.optInt("number")), 12f, Ui.blue))
                    val ans = Ui.text(ctx, "Waiting for the vision model…", 12.5f, Ui.fg2); ans.setTextIsSelectable(true)
                    card.addView(ans)
                    card.background = Ui.rounded(Ui.bg, 6, Ui.border); card.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
                    r.exchanges[ev.optString("id")] = ans
                    r.box.addView(card)
                } else {
                    val ans = r.exchanges[ev.optString("id")] ?: return
                    val text = ev.optString("text")
                    ans.text = if (text.isNotEmpty()) text + (if (ev.has("seconds")) "\n(" + ev.optString("source") + ", " + ev.optDouble("seconds") + " s)" else "") else "The vision model could not answer" + (if (ev.optString("error").isNotEmpty()) ": " + ev.optString("error") else ".")
                }
            }
            "vision_delta" -> { val ans = r.exchanges[ev.optString("exchange")] ?: return; val cur = ans.text.toString(); ans.text = (if (cur.startsWith("Waiting")) "" else cur) + ev.optString("text") }
            "image_analysis" -> { closeCurrent(r); r.box.addView(statusLine("Vision model: " + ev.optString("description").take(400))) }
            "model_activity" -> { strip.visibility = View.VISIBLE; strip.text = ev.optString("label") + ": " + ev.optString("detail") }
            "status" -> { closeCurrent(r); r.box.addView(statusLine(ev.optString("text"))) }
            "tool_error" -> r.box.addView(statusLine(ev.optString("message"), true))
            "error" -> {
                closeCurrent(r); r.box.addView(statusLine("Error: " + ev.optString("message"), true))
                r.box.addView(Ui.button(ctx, "Retry") { startRun(r.item) })
                finish(r)
            }
            "stats" -> { closeCurrent(r); val tps = ev.optDouble("gen_tps", 0.0); if (tps > 0) r.box.addView(statusLine(String.format("%.1f tokens per second, %d prompt tokens", tps, ev.optInt("prompt_tokens")))) }
            "end" -> { closeCurrent(r); if (ev.optString("status") != "done") r.box.addView(statusLine("Stopped (" + ev.optString("status") + ")")); finish(r) }
        }
    }

    private fun finish(r: Run) {
        r.stream?.stop(); r.spinner?.let { r.box.removeView(it) }
        if (run === r) { run = null; send.text = "Send"; strip.visibility = View.GONE; jump.visibility = View.GONE }
        host.refreshTree()
    }

    fun focusPrompt() { prompt.requestFocus() }
    fun stop() { run?.stream?.stop() }
}
