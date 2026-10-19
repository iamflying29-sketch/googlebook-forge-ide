package local.forge

import android.content.Context
import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URLEncoder

/**
 * A plain terminal: the shell runs in the Linux VM through the server's WebSocket; its output is shown as text
 * (control sequences stripped) and a line you type is sent with Enter. Not a full terminal emulator: programs
 * that draw screens (vim, top) are not for this panel.
 */
class TerminalPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val out = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val input = Ui.input(ctx, "Type a command and press Enter", mono = true)
    private val state = Ui.text(ctx, "not connected", 11f, Ui.fg3)
    private var ws: Api.WebSocketClient? = null
    private val lines = StringBuilder()
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*(\u0007|\u001B\\\\)|\u001B[()][A-Z0-9]|\u001B[=>]|\r")
    private var stick = true

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)
        val pad = Ui.dp(ctx, 8)
        val head = Ui.row(ctx, Ui.text(ctx, "TERMINAL", 11f, Ui.fg3, bold = true), state,
            Ui.button(ctx, "Clear") { lines.setLength(0); out.text = "" }, Ui.button(ctx, "Reconnect") { connect() }, Ui.button(ctx, "Hide") { host.toggleTerminal() })
        state.layoutParams = Ui.lp(0, Ui.wrap, 1f); state.setPadding(pad, 0, 0, 0)
        head.setPadding(pad, pad / 2, pad / 2, pad / 2); head.setBackgroundColor(Ui.bg2)
        view.addView(head, Ui.lp(Ui.match, Ui.wrap))
        out.typeface = Typeface.MONOSPACE; out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); out.setTextColor(Ui.fg)
        out.setPadding(pad, pad / 2, pad, pad / 2); out.setTextIsSelectable(true)
        scroll.addView(out, android.widget.FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        scroll.viewTreeObserver.addOnScrollChangedListener { val c = scroll.getChildAt(0); stick = c == null || c.bottom - (scroll.height + scroll.scrollY) < Ui.dp(ctx, 60) }
        view.addView(scroll, Ui.lp(Ui.match, 0, 1f))
        input.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { send(input.text.toString() + "\n"); input.setText(""); true }
            else if (code == KeyEvent.KEYCODE_C && ev.action == KeyEvent.ACTION_DOWN && ev.isCtrlPressed) { send("\u0003"); true }
            else if (code == KeyEvent.KEYCODE_D && ev.action == KeyEvent.ACTION_DOWN && ev.isCtrlPressed) { send("\u0004"); true }
            else false
        }
        input.setPadding(pad, pad / 2, pad, pad / 2)
        view.addView(input, Ui.lp(Ui.match, Ui.wrap).also { it.setMargins(pad, 0, pad, pad / 2) })
    }

    fun connect() {
        ws?.close()
        state.text = "connecting…"
        val folder = if (Api.folder.isNotEmpty()) "&folder=" + URLEncoder.encode(Api.folder, "UTF-8") else ""
        val client = Api.WebSocketClient("/ws/term?id=main&cols=120&rows=30$folder", { text -> append(text) }, { why -> state.text = "closed ($why)" })
        ws = client
        client.connect()
        state.text = "connected"
    }

    private fun append(text: String) {
        lines.append(ansi.replace(text, ""))
        if (lines.length > 200000) lines.delete(0, lines.length - 150000)
        out.text = lines
        if (stick) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send(s: String) { val c = ws ?: run { connect(); return }; c.sendText(s) }
    fun focusInput() { input.requestFocus() }
    fun close() { ws?.close(); ws = null }
}
