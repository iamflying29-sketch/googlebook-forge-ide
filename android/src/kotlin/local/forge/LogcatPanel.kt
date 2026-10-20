package local.forge

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The LOGCAT tab: the Googlebook's own log, streamed by the Forge server over adb (the server picks the connected
 * Googlebook). An app id narrows it to one app, the level drops quieter lines, the filter hides lines that do not
 * contain the text. Warnings are yellow and errors red, as on the old page. At most 2,000 lines are kept.
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
        val head = Ui.row(ctx, appId, levelPill, filter, toggle, clear, state)
        appId.layoutParams = Ui.lp(Ui.dp(ctx, 200), Ui.wrap)
        levelPill.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 6) }
        filter.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = Ui.dp(ctx, 6) }
        for (b in listOf(toggle, clear)) b.layoutParams = Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)).also { it.leftMargin = Ui.dp(ctx, 6) }
        state.setPadding(Ui.dp(ctx, 8), 0, 0, 0)
        head.setPadding(pad, Ui.dp(ctx, 4), pad, Ui.dp(ctx, 4))
        head.setBackgroundColor(Ui.bg2)
        view.addView(head, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.divider(ctx, false))
        out.typeface = Typeface.MONOSPACE
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        out.setTextColor(Ui.fg2)
        out.setTextIsSelectable(true)
        out.setPadding(pad, Ui.dp(ctx, 4), pad, Ui.dp(ctx, 4))
        out.text = "Press the start button to stream the Googlebook's log here."
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
        val q = "/ws/logcat?app_id=" + URLEncoder.encode(appId.text.toString().trim(), "UTF-8") + "&level=" + level
        lines.add("[Logcat started" + (if (appId.text.isNotBlank()) " for " + appId.text.toString().trim() else "") + "]")
        render()
        val client = Api.WebSocketClient(q, { text -> onMessage(text) }, { why -> ws = null; lines.add("[The log stream ended: $why]"); render(); state.text = "stopped"; toggle.setImageDrawable(Ui.icon(ctx, R.drawable.ic_run, 0)) })
        ws = client
        client.connect()
        state.text = "streaming"
        toggle.setImageDrawable(Ui.icon(ctx, R.drawable.ic_stop, 0))
        toggle.tooltipText = "Stop the log"
    }

    private fun stop() {
        ws?.close(); ws = null
        state.text = "stopped"
        toggle.setImageDrawable(Ui.icon(ctx, R.drawable.ic_run, 0))
        toggle.tooltipText = "Start the log"
    }

    private fun onMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "info" -> lines.add("[" + msg.optString("text") + "]")
            "end" -> lines.add("[The log stream ended.]")
            "lines" -> { val arr = msg.optJSONArray("lines") ?: return; for (i in 0 until arr.length()) lines.add(arr.getString(i)) }
            else -> return
        }
        while (lines.size > 2000) lines.removeAt(0)
        render()
    }

    private fun render() {
        val f = filter.text.toString().trim().lowercase()
        val sb = SpannableStringBuilder()
        for (line in lines) {
            if (f.isNotEmpty() && !line.lowercase().contains(f) && !line.startsWith("[")) continue
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

    fun close() { stop() }

    /** Shows one app's log (the Android view's App logcat button): the app id goes in and the stream restarts. */
    fun openFor(app: String) {
        appId.setText(app)
        if (ws != null) stop()
        start()
    }
}
