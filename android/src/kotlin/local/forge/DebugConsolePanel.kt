package local.forge

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The DEBUG CONSOLE tab: the program's output, the build output before a native debug, the expressions you type
 * with their results, and errors in red, as on the old page. The input line evaluates an expression in the paused
 * frame of the attached session (the Run and debug view attaches it through [attach]).
 *
 * Program output arrives in pieces; a piece continues the open line until it prints a newline, so a prompt and
 * the answer typed after it stay on one line. At most 3,000 lines are kept.
 */
class DebugConsolePanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val out = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val input = Ui.input(ctx, "Evaluate an expression in the paused program", mono = true)
    private var session: DebugSession? = null
    /** The kind of the line still open for raw output, or null when the next piece starts a new line. */
    private var openKind: String? = null
    private var lineCount = 0
    private var stick = true

    companion object {
        /** The console of this window, for the Run and debug view (the window builds exactly one). */
        @Volatile var current: DebugConsolePanel? = null
        const val MAX_LINES = 3000
    }

    init {
        current = this
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)
        val pad = Ui.dp(ctx, 8)
        out.typeface = Typeface.MONOSPACE
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        out.setTextColor(Ui.fg2)
        out.setTextIsSelectable(true)
        out.setPadding(pad, Ui.dp(ctx, 6), pad, Ui.dp(ctx, 6))
        out.setText("", TextView.BufferType.EDITABLE)
        scroll.addView(out, android.widget.FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        scroll.setOnScrollChangeListener { v, _, y, _, _ ->
            val s = v as ScrollView
            val child = s.getChildAt(0)
            stick = child == null || y + s.height >= child.height - Ui.dp(ctx, 24)
        }
        view.addView(scroll, Ui.lp(Ui.match, 0, 1f))
        view.addView(Ui.divider(ctx, false))
        input.setOnKeyListener { _, code, ev ->
            if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && ev.action == KeyEvent.ACTION_DOWN) {
                val expr = input.text.toString().trim()
                if (expr.isNotEmpty()) { input.setText(""); evaluate(expr) }
                true
            } else false
        }
        view.addView(input, Ui.lp(Ui.match, Ui.wrap).also { it.setMargins(pad, Ui.dp(ctx, 4), pad, Ui.dp(ctx, 6)) })
        out("No debug session. Start one from Run and debug in the activity bar; the program's output and your expressions appear here.", "info")
    }

    /** The session whose paused frame evaluates the typed expressions; null when none runs. */
    fun attach(session: DebugSession?) { this.session = session }

    /** Appends one line of plain output. */
    fun append(line: String) = out(line, "")

    fun clear() {
        (out.text as? Editable)?.clear()
        lineCount = 0
        openKind = null
    }

    /**
     * Writes text. [kind] is "" for program output, "err" for errors (red), "info" for Forge's own notes (dim),
     * "in" for a typed expression (blue) and "res" for its result (green). With [raw] the text continues the open
     * line of the same kind and only a newline in it ends the line, as the page did for program output.
     */
    fun out(text: String, kind: String = "", raw: Boolean = false) {
        val e = out.editableText ?: return
        val color = when (kind) { "err" -> Ui.red; "info" -> Ui.fg3; "in" -> Ui.blue; "res" -> Ui.green; else -> Ui.fg2 }
        if (raw) {
            val parts = text.split('\n')
            for ((i, part) in parts.withIndex()) {
                if (openKind != kind) { startLine(e); openKind = kind }
                if (part.isNotEmpty()) e.append(colored(part, color))
                if (i < parts.size - 1) openKind = null
            }
        } else {
            startLine(e)
            openKind = null
            e.append(colored(text, color))
        }
        trim(e)
        if (stick) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startLine(e: Editable) { if (e.isNotEmpty()) e.append("\n"); lineCount++ }

    private fun colored(s: String, color: Int): CharSequence {
        val sp = SpannableString(s)
        sp.setSpan(ForegroundColorSpan(color), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    /** Drops the oldest lines when more than MAX_LINES are kept. */
    private fun trim(e: Editable) {
        if (lineCount <= MAX_LINES) return
        var cut = 0
        var drop = lineCount - MAX_LINES
        while (drop > 0 && cut < e.length) {
            val nl = e.indexOf('\n', cut)
            if (nl < 0) break
            cut = nl + 1
            drop--
        }
        if (cut > 0) { e.delete(0, cut); lineCount = MAX_LINES }
    }

    private fun evaluate(expr: String) {
        out("› $expr", "in")
        val s = session
        if (s == null || s.state != DebugSession.State.STOPPED) {
            out("The program is not paused. Expressions are evaluated in the paused frame.", "err")
            return
        }
        s.evaluate(expr, "repl") { r, e ->
            if (r != null) out(r.optString("result", ""), "res") else out(e?.message ?: "The evaluation failed.", "err")
        }
    }

    fun focusInput() { input.requestFocus() }
}
