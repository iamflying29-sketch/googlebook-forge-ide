package local.forge

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * The shells of the bottom panel, like Cursor's terminal: one bar with a tab per shell ("bash 1", "bash 2"), the
 * active shell's connection state, and the buttons (AI command, add the output to the chat, clear, reconnect, new
 * shell, kill shell) over one terminal panel per shell. Each shell is its own session on the server (id "main",
 * "t2", "t3" and so on), so a closed window can come back to the same shells.
 *
 * Ctrl K (or the sparkle button) opens a line under the bar where a command is described in words; the model writes
 * the command (POST /api/terminal_command) and it is typed into the shell without being run, so it can be read and
 * changed before Enter.
 */
class TerminalTabs(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val tabRow = LinearLayout(ctx)
    private val state = Ui.text(ctx, "", 10.5f, Ui.fg3, bold = true)
    /** The shells side by side: the shown shell and, after Split (Ctrl Shift 5), a second one next to it. */
    private val content = LinearLayout(ctx)
    private var splitWith: TerminalPanel? = null
    private val panels = ArrayList<TerminalPanel>()
    /** Declared before init: init opens the first shell, which reads it. */
    private var fontSp = 12.5f
    private val titles = HashMap<TerminalPanel, String>()
    private var active: TerminalPanel? = null
    private var counter = 0
    private var online = false

    // The AI command line (Ctrl K).
    private val aiBar = LinearLayout(ctx)
    private val aiInput = Ui.input(ctx, "Describe a command, for example: find the five biggest files here", mono = false)
    private val aiStatus = Ui.text(ctx, "", 11.5f, Ui.fg3)
    private var aiBusy = false

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.gravity = Gravity.CENTER_VERTICAL
        val scroll = HorizontalScrollView(ctx)
        scroll.addView(tabRow, FrameLayout.LayoutParams(Ui.wrap, Ui.match))
        scroll.isHorizontalScrollBarEnabled = false
        state.letterSpacing = 0.08f
        state.isSingleLine = true
        state.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 6), 0)
        val bar = Ui.row(ctx, scroll, state,
            mini(R.drawable.ic_sparkle, "Write a command with AI (Ctrl K)") { openAi() },
            mini(R.drawable.ic_chat, "Add the output to the chat (the selection, or the last lines)") { addToChat() },
            mini(R.drawable.ic_trash, "Clear the terminal") { active?.clear() },
            mini(R.drawable.ic_refresh, "Reconnect the shell") { active?.reconnect() },
            mini(R.drawable.ic_plus, "New terminal (Ctrl Shift `)") { addTerminal() },
            mini(R.drawable.ic_close, "Kill this terminal") { killActive() })
        scroll.layoutParams = Ui.lp(0, Ui.match, 1f)
        bar.setBackgroundColor(Ui.bg2)
        bar.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0)
        view.addView(bar, Ui.lp(Ui.match, Ui.dp(ctx, 30)))
        view.addView(Ui.divider(ctx, false))

        // The AI command line, hidden until Ctrl K.
        aiBar.orientation = LinearLayout.HORIZONTAL
        aiBar.gravity = Gravity.CENTER_VERTICAL
        aiBar.setBackgroundColor(Ui.bg2)
        aiBar.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4))
        aiBar.addView(Ui.iconView(ctx, R.drawable.ic_sparkle, Ui.accent, 15), Ui.lp(Ui.dp(ctx, 18), Ui.dp(ctx, 18)).also { it.rightMargin = Ui.dp(ctx, 8) })
        aiInput.isSingleLine = true
        aiInput.imeOptions = EditorInfo.IME_ACTION_GO
        aiBar.addView(aiInput, Ui.lp(0, Ui.wrap, 1f))
        aiStatus.setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 6), 0)
        aiStatus.isSingleLine = true; aiStatus.ellipsize = TextUtils.TruncateAt.END
        aiBar.addView(aiStatus, Ui.lp(Ui.wrap, Ui.wrap))
        aiBar.addView(mini(R.drawable.ic_close, "Close (Escape)") { closeAi() })
        aiBar.visibility = View.GONE
        view.addView(aiBar, Ui.lp(Ui.match, Ui.wrap))
        aiInput.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { runAi(run = ev.isCtrlPressed); true }
                KeyEvent.KEYCODE_ESCAPE -> { closeAi(); true }
                else -> false
            }
        }
        aiInput.setOnEditorActionListener { _, _, _ -> runAi(); true }

        view.addView(content, Ui.lp(Ui.match, 0, 1f))
        addTerminal()
    }

    private fun mini(icon: Int, tip: String, onClick: () -> Unit): View =
        Ui.miniButton(ctx, icon, tip) { onClick() }.also { it.layoutParams = Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)).also { p -> p.leftMargin = Ui.dp(ctx, 3) } }

    /** Opens one more shell and shows it; [title] names its tab instead of "bash N". */
    fun addTerminal(title: String = ""): TerminalPanel {
        counter++
        val id = if (counter == 1) "main" else "t$counter"
        val p = TerminalPanel(ctx, host, id)
        p.onState = { if (p === active) showState() }
        p.setFontSize(fontSp)
        panels.add(p)
        if (title.isNotEmpty()) titles[p] = title
        content.addView(p.view, Ui.lp(0, Ui.match, 1f))
        activate(p)
        if (online) { p.connect(); p.focusInput() }
        return p
    }

    /** Runs a command in a new shell tab named [label] (the Android view's build, install and preview buttons). */
    fun runCommand(command: String, label: String) {
        val p = addTerminal(label.take(28))
        // The shell needs a moment to attach and print its prompt before it takes input.
        Api.main.postDelayed({ p.pasteText(command + "\n") }, 900)
    }

    /** A new shell that starts in a folder of the project (the Explorer's Open a terminal here). */
    fun openIn(dir: String) {
        val p = addTerminal(dir.trimEnd('/').substringAfterLast('/').ifEmpty { "" })
        if (dir.isEmpty() || dir == ".") return
        val quoted = "'" + dir.replace("'", "'\\''") + "'"
        Api.main.postDelayed({ p.pasteText("cd -- $quoted\n") }, 900)
    }

    private fun activate(p: TerminalPanel) {
        active = p
        if (splitWith === p) splitWith = null
        for (q in panels) q.view.visibility = if (q === p || q === splitWith) View.VISIBLE else View.GONE
        renderTabs()
        showState()
    }

    private fun showState() {
        val p = active ?: return
        state.text = p.stateText
        state.setTextColor(p.stateColor)
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        for ((i, p) in panels.withIndex()) {
            val icon = Ui.iconView(ctx, R.drawable.ic_terminal, if (p === active) Ui.fg else Ui.fg3, 13)
            val label = Ui.text(ctx, titles[p] ?: ("bash " + (i + 1)), 12f, if (p === active) Ui.fg else Ui.fg3)
            label.setPadding(Ui.dp(ctx, 5), 0, 0, 0)
            val t = Ui.row(ctx, icon, label)
            t.setPadding(Ui.dp(ctx, 9), Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4))
            t.background = if (p === active) Ui.rounded(Ui.bg3, 6, Ui.border) else null
            t.setOnClickListener { activate(p); p.focusInput() }
            t.tooltipText = "Shell " + (i + 1) + ". Middle-click kills it."
            tabRow.addView(t, Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = Ui.dp(ctx, 4) })
        }
    }

    /** Ctrl Shift 5: a new shell next to the shown one; again, the two show one at a time. */
    fun split() {
        val keep = active ?: return
        if (splitWith != null) { splitWith = null; activate(keep); return }
        val p = addTerminal()
        splitWith = keep
        activate(p)
    }

    /** Ctrl Page Up and Down in the terminal: the previous or next shell. */
    fun step(dir: Int) {
        val p = active ?: return
        if (panels.size < 2) return
        val i = panels.indexOf(p)
        val n = panels[((i + dir) % panels.size + panels.size) % panels.size]
        activate(n); n.focusInput()
    }

    /** Ends the shown shell on the server; the last shell is replaced by a fresh one instead of leaving nothing. */
    fun killActive() {
        val p = active ?: return
        if (splitWith === p) splitWith = null
        p.kill()
        if (panels.size == 1) { if (online) p.connect(); return }
        panels.remove(p)
        titles.remove(p)
        content.removeView(p.view)
        activate(panels.last())
        panels.last().focusInput()
    }

    /** The text size of every shell (Settings, Editor font size). */
    fun setFontSize(sp: Float) { fontSp = sp; for (p in panels) p.setFontSize(sp) }

    fun connect() { online = true; for (p in panels) p.connect() }
    fun focusInput() { active?.focusInput() }
    fun close() { online = false; for (p in panels) p.close() }

    /** The shown shell's last lines, for @terminal context. */
    fun recentText(maxLines: Int = 200): String = active?.recentText(maxLines) ?: ""

    /** Types a command into the shown shell without running it (Run in terminal buttons of the chat). */
    fun paste(text: String) { active?.pasteText(text) }

    private fun addToChat() {
        val p = active ?: return
        val sel = p.selectedText()
        host.addTerminalToChat(if (sel.isNotEmpty()) sel else p.recentText(120))
    }

    // ---------------------------------------------------------------- Ctrl K: a command written by the model
    fun openAi() {
        aiBar.visibility = View.VISIBLE
        aiStatus.text = ""
        aiInput.isEnabled = true
        aiInput.setText("")
        aiInput.requestFocus()
    }

    private fun closeAi() {
        aiBar.visibility = View.GONE
        active?.focusInput()
    }

    /** [run]: Ctrl Enter runs the command as soon as it is written (Cursor's "run generated command"). */
    private fun runAi(run: Boolean = false) {
        val instruction = aiInput.text.toString().trim()
        if (instruction.isEmpty() || aiBusy) return
        aiBusy = true
        aiInput.isEnabled = false
        aiStatus.setTextColor(Ui.fg3)
        aiStatus.text = "Asking the model…"
        val body = JSONObject().put("instruction", instruction).put("cwd", Api.folder)
        Api.async("terminal-command", { Api.post("/api/terminal_command", body, 620000) }) { r, e ->
            aiBusy = false
            aiInput.isEnabled = true
            if (r == null) { aiStatus.setTextColor(Ui.red); aiStatus.text = "Not written: " + (e?.message ?: "no answer"); return@async }
            val cmd = r.optString("command").trim()
            if (cmd.isEmpty()) { aiStatus.setTextColor(Ui.red); aiStatus.text = "The model returned no command."; return@async }
            val dangerous = r.optBoolean("dangerous", false)
            if (run && !dangerous) { active?.pasteText(cmd + "\n"); closeAi(); return@async }
            active?.pasteText(cmd)
            aiStatus.setTextColor(if (dangerous) Ui.yellow else Ui.green)
            aiStatus.text = if (dangerous) "This command looks destructive. Read it before you press Enter." else "Typed into the terminal. Press Enter there to run it."
            Api.main.postDelayed({ if (!aiBusy && aiBar.visibility == View.VISIBLE && !aiInput.hasFocus()) aiBar.visibility = View.GONE }, 6000)
        }
    }
}
