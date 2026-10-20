package local.forge

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The LOGCAT tab: the log of the target device (the one chosen with Use in the Android view: this Googlebook, a
 * virtual device or a paired device), streamed by the Forge server over adb; the header names the device once the
 * stream has started. An app id narrows it to that app's process, and the server follows the app when its process
 * starts again (a crash, a new install); while the app does not run, the log says so and waits for it. The level
 * drops quieter lines, the filter hides lines that do not contain the text. Warnings are yellow and errors red, as
 * on the old page. At most 2,000 lines are kept, and the chat button attaches the last 400 shown lines.
 */
class LogcatPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val out = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val appId = Ui.input(ctx, "app id (optional)")
    private val filter = Ui.input(ctx, "filter")
    private val levelPill: TextView
    private val toggle = Ui.miniButton(ctx, R.drawable.ic_run, "Start the log") { if (ws == null) start() else stop() }
    private val state = Ui.text(ctx, "stopped", 11f, Ui.fg3)
    /** The device the stream reads, as the server named it. */
    private val deviceLabel = Ui.text(ctx, "", 11f, Ui.fg2)
    private var deviceName = ""
    private var level = "V"
    private var ws: Api.WebSocketClient? = null
    private val lines = ArrayList<String>()
    private var stick = true

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)
        val pad = Ui.dp(ctx, 8)
        levelPill = Ui.text(ctx, "Level V ▾", 12f, Ui.fg)
        levelPill.background = Ui.rounded(Ui.bg3, 12, Ui.border)
        levelPill.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4))
        levelPill.setOnClickListener { v ->
            val m = PopupMenu(ctx, v)
            for (l in listOf("V", "D", "I", "W", "E")) m.menu.add(l)
            m.setOnMenuItemClickListener { item -> level = item.title.toString(); levelPill.text = "Level $level ▾"; if (ws != null) { stop(); start() }; true }
            m.show()
        }
        val clear = Ui.miniButton(ctx, R.drawable.ic_trash, "Clear") { lines.clear(); render() }
        val chat = Ui.miniButton(ctx, R.drawable.ic_chat, "Add the shown log lines to the chat (the last 400)") { addToChat() }
        deviceLabel.isSingleLine = true
        deviceLabel.ellipsize = TextUtils.TruncateAt.END
        val head = Ui.row(ctx, appId, levelPill, filter, toggle, chat, clear, state, deviceLabel)
        appId.layoutParams = Ui.lp(Ui.dp(ctx, 200), Ui.wrap)
        levelPill.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 6) }
        filter.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = Ui.dp(ctx, 6) }
        for (b in listOf(toggle, chat, clear)) b.layoutParams = Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)).also { it.leftMargin = Ui.dp(ctx, 6) }
        state.setPadding(Ui.dp(ctx, 8), 0, 0, 0)
        deviceLabel.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 8) }
        deviceLabel.maxWidth = Ui.dp(ctx, 260)
        head.setPadding(pad, Ui.dp(ctx, 4), pad, Ui.dp(ctx, 4))
        head.setBackgroundColor(Ui.bg2)
        view.addView(head, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.divider(ctx, false))
        out.typeface = Typeface.MONOSPACE
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        out.setTextColor(Ui.fg2)
        out.setTextIsSelectable(true)
        out.setPadding(pad, Ui.dp(ctx, 4), pad, Ui.dp(ctx, 4))
        out.text = "Press the start button to stream the target device's log here. That is the device chosen with Use in the Android view. When it is not chosen or not online, it is a running virtual device, then this Googlebook, then any other connected device."
        scroll.addView(out, android.widget.FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        scroll.viewTreeObserver.addOnScrollChangedListener { val c = scroll.getChildAt(0); stick = c == null || c.bottom - (scroll.height + scroll.scrollY) < Ui.dp(ctx, 60) }
        view.addView(scroll, Ui.lp(Ui.match, 0, 1f))
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        })
    }

    private fun start() {
        val app = appId.text.toString().trim()
        val q = "/ws/logcat?app_id=" + URLEncoder.encode(app, "UTF-8") + "&level=" + level
        lines.add("[Logcat started" + (if (app.isNotEmpty()) " for $app" else "") + "]")
        render()
        lateinit var client: Api.WebSocketClient
        client = Api.WebSocketClient(q, { text -> if (ws === client) onMessage(text) }, { why ->
            if (ws !== client) return@WebSocketClient
            lines.add("[The log stream ended: $why]")
            render()
            stopped()
        })
        ws = client
        client.connect()
        state.text = "streaming"
        toggle.setImageDrawable(Ui.icon(ctx, R.drawable.ic_stop, 0))
        toggle.tooltipText = "Stop the log"
    }

    private fun stop() {
        ws?.close()
        stopped()
    }

    /** The stream is over, whoever ended it: the state and the button say so. */
    private fun stopped() {
        ws = null
        state.text = "stopped"
        toggle.setImageDrawable(Ui.icon(ctx, R.drawable.ic_run, 0))
        toggle.tooltipText = "Start the log"
    }

    private fun onMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optText("type")) {
            "device" -> {
                deviceName = msg.optText("name").ifEmpty { msg.optText("serial") }
                deviceLabel.text = deviceName
                deviceLabel.tooltipText = "The log comes from $deviceName (" + msg.optText("serial") + ")"
                lines.add("[Streaming the log of $deviceName]")
            }
            "info" -> lines.add("[" + msg.optText("text") + "]")
            "end" -> {
                // The server ended the stream (its time or size limit, or the device went away): nothing streams now.
                lines.add("[The log stream ended.]")
                ws?.close()
                stopped()
            }
            "lines" -> { val arr = msg.optJSONArray("lines") ?: return; for (i in 0 until arr.length()) lines.add(arr.optText(i)) }
            else -> return
        }
        while (lines.size > 2000) lines.removeAt(0)
        render()
    }

    /** The lines the filter lets through (Forge's own bracketed notes always show). */
    private fun shownLines(): List<String> {
        val f = filter.text.toString().trim().lowercase()
        return if (f.isEmpty()) lines else lines.filter { it.startsWith("[") || it.lowercase().contains(f) }
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        for (line in shownLines()) {
            val start = sb.length
            sb.append(line).append('\n')
            val color = when {
                line.startsWith("[") -> Ui.fg3
                Regex("^\\S+ \\S+\\s+\\d+\\s+\\d+ ([VDIWEF]) ").find(line)?.groupValues?.get(1) == "E" -> Ui.red
                Regex("^\\S+ \\S+\\s+\\d+\\s+\\d+ ([VDIWEF]) ").find(line)?.groupValues?.get(1) == "W" -> Ui.yellow
                Regex("^\\S+ \\S+\\s+\\d+\\s+\\d+ ([VDIWEF]) ").find(line)?.groupValues?.get(1) == "I" -> Ui.fg
                else -> Ui.fg2
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        out.text = sb
        if (stick) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** The last 400 shown log lines (at most 12,000 characters, the newest kept) go to the chat as context. */
    private fun addToChat() {
        val shown = shownLines().filter { !it.startsWith("[") }
        if (shown.isEmpty()) { Ui.toast(ctx, "The log has no lines to add."); return }
        val picked = ArrayList<String>()
        var size = 0
        for (line in shown.takeLast(400).asReversed()) {
            if (size + line.length + 1 > 12000) break
            picked.add(line); size += line.length + 1
        }
        picked.reverse()
        val app = appId.text.toString().trim()
        val f = filter.text.toString().trim()
        val title = "Logcat" + (if (deviceName.isNotEmpty()) " of $deviceName" else "") + (if (app.isNotEmpty()) " for $app" else "") +
            ", level $level and up" + (if (f.isNotEmpty()) ", lines containing \"$f\"" else "") + " (the last ${picked.size} lines)"
        host.addToChat(JSONObject().put("kind", "text").put("title", title).put("name", "Logcat")
            .put("text", "```\n" + picked.joinToString("\n") + "\n```"))
    }

    fun close() { stop() }

    /** Shows one app's log (the Android view's App logcat button): the app id goes in and the stream restarts. */
    fun openFor(app: String) {
        appId.setText(app)
        if (ws != null) stop()
        start()
    }
}
