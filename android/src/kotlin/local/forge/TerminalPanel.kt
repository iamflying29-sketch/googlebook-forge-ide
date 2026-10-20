package local.forge

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The terminal panel: a header bar like the web page's bottom panel (the TERMINAL label, the connection state in
 * small gray capitals, and Clear, Reconnect and Hide buttons) over a real terminal emulator (TerminalView and
 * TerminalEmulator). The shell runs in the Linux VM behind the Forge server's /ws/term WebSocket: the server sends
 * the pty's raw output and takes raw input; a resize travels as a small JSON text frame. When the connection drops
 * the panel says so inside the terminal and reconnects by itself after a short wait.
 */
class TerminalPanel(private val ctx: Context, private val host: MainActivity, private val termId: String = "main") {
    val view: LinearLayout = LinearLayout(ctx)
    private val terminal = TerminalView(ctx)
    private val state: TextView
    private var ws: Api.WebSocketClient? = null
    private var connected = false
    private var closed = false
    private var reconnectDelayS = 3
    private var generation = 0
    private val reconnectTask = Runnable { if (!closed && ws == null) connect() }
    private val resizeTask = Runnable { sendResize() }
    private val gray = Ui.fg3

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)

        // No header of its own: TerminalTabs shows one bar for all shells with this shell's state on it.
        state = capsLabel("NOT CONNECTED", gray)
        terminal.onMenuAction = { action, text -> if (action == "add-to-chat") host.addTerminalToChat(text) }

        // The terminal itself, inset like the web page's .term-host (4 px top and bottom, 10 px left, 6 px right).
        terminal.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4))
        terminal.onInput = { s -> send(s) }
        terminal.onResize = { _, _ ->
            // Layout passes can come in bursts; the shell gets the final size a moment later.
            Api.main.removeCallbacks(resizeTask); Api.main.postDelayed(resizeTask, 80)
        }
        view.addView(terminal, Ui.lp(Ui.match, 0, 1f))
    }

    private fun capsLabel(text: String, color: Int): TextView {
        val t = Ui.text(ctx, text, 11f, color, bold = true)
        t.letterSpacing = 0.08f
        t.isSingleLine = true
        return t
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        val b = Ui.button(ctx, label) { onClick() }
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        b.setTextColor(Ui.fg2)
        b.setPadding(Ui.dp(ctx, 9), Ui.dp(ctx, 3), Ui.dp(ctx, 9), Ui.dp(ctx, 3))
        b.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.setMargins(Ui.dp(ctx, 4), 0, 0, 0) }
        b.isFocusable = false   // a click must not take the keyboard away from the terminal
        return b
    }

    /** The shell's connection state for the bar of TerminalTabs ("CONNECTED" in green, and so on). */
    var stateText = "NOT CONNECTED"; private set
    var stateColor = gray; private set
    var onState: (() -> Unit)? = null

    private fun setState(text: String, color: Int = gray) { state.text = text; state.setTextColor(color); stateText = text; stateColor = color; onState?.invoke() }

    fun clear() { terminal.clear() }
    fun setFontSize(sp: Float) = terminal.setFontSize(sp)
    fun reconnect() { reconnectDelayS = 3; connect() }
    /** The last lines of this shell (scrollback and screen), for @terminal context and "add to chat". */
    fun recentText(maxLines: Int = 200): String = terminal.recentText(maxLines)
    fun selectedText(): String = terminal.selectedText()
    /** Types text into the shell as if it was pasted (a command from Ctrl K is not run until Enter is pressed). */
    fun pasteText(text: String) { if (text.isNotEmpty()) send(text.replace("\r\n", "\r").replace('\n', '\r')); terminal.requestFocus() }

    /** Opens (or reopens) the WebSocket to the shell. Safe to call again at any time. */
    fun connect() {
        closed = false
        Api.main.removeCallbacks(reconnectTask)
        ws?.close()
        ws = null
        connected = false
        val gen = ++generation
        setState("CONNECTING")
        // The server replays the session's recent output on attach, so the screen starts clean and is rebuilt.
        terminal.reset()
        val cols = maxOf(10, terminal.gridCols); val rows = maxOf(3, terminal.gridRows)
        val folder = if (Api.folder.isNotEmpty()) "&folder=" + URLEncoder.encode(Api.folder, "UTF-8") else ""
        val client = Api.WebSocketClient("/ws/term?id=$termId&cols=$cols&rows=$rows$folder",
            { text -> if (gen == generation) onMessage(text) },
            { why -> if (gen == generation) onClosed(why) })
        ws = client
        client.connect()
        // Nothing announces the handshake, so the size is sent once the shell has had a moment to attach, and again
        // with the first output; a resize is harmless when nothing changed.
        Api.main.postDelayed(resizeTask, 400)
    }

    private fun onMessage(text: String) {
        if (!connected) { connected = true; reconnectDelayS = 3; setState("CONNECTED", Ui.green); sendResize() }
        if (text.length < 200 && text.startsWith("{\"type\"") && text.endsWith("}")) {
            val type = try { JSONObject(text).optString("type") } catch (e: Exception) { "" }
            if (type == "exit") { terminal.write("\r\n\u001b[90m[process exited]\u001b[0m\r\n"); return }
            if (type == "pong") return
        }
        terminal.write(text)
    }

    private fun onClosed(why: String) {
        ws = null
        connected = false
        if (closed) { setState("CLOSED"); return }
        val wait = reconnectDelayS
        terminal.write("\r\n\u001b[90m[disconnected: $why. Reconnecting in $wait s]\u001b[0m\r\n")
        setState("RECONNECTING IN $wait S", Ui.red)
        Api.main.removeCallbacks(reconnectTask)
        Api.main.postDelayed(reconnectTask, wait * 1000L)
        reconnectDelayS = minOf(15, wait + 4)
    }

    private fun sendResize() {
        val c = ws ?: return
        val cols = terminal.gridCols; val rows = terminal.gridRows
        if (cols < 2 || rows < 1) return
        c.sendText(JSONObject().put("type", "resize").put("cols", cols).put("rows", rows).toString())
    }

    private fun send(s: String) {
        val c = ws
        if (c == null) { if (!closed) { Api.main.removeCallbacks(reconnectTask); connect() }; return }
        // The server reads a text frame that starts with a brace as a command, so such input travels inside one.
        if (s.startsWith("{")) c.sendText(JSONObject().put("type", "input").put("data", s).toString())
        else c.sendText(s)
    }

    fun focusInput() { terminal.requestFocus() }

    /** Ends this shell on the server (the Kill button of the terminal tabs). */
    fun kill() {
        close()
        Api.async("terminal-kill", { Api.post("/api/terminal/kill", JSONObject().put("id", termId)) }) { _, _ -> }
        terminal.reset()
        setState("KILLED")
    }

    fun close() {
        closed = true
        Api.main.removeCallbacks(reconnectTask)
        Api.main.removeCallbacks(resizeTask)
        ws?.close(); ws = null
        connected = false
    }
}
