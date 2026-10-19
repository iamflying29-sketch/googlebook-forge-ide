package local.forge

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The DEBUG CONSOLE tab: the output of a debug session and a line to evaluate expressions in it. The native
 * debugger (the Run and debug view) is not built yet, so until then the console says so and keeps the input
 * ready; the server's debug adapter endpoint is /ws/dap.
 */
class DebugConsolePanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val out = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val input = Ui.input(ctx, "Evaluate an expression in the paused program", mono = true)

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)
        val pad = Ui.dp(ctx, 8)
        out.typeface = Typeface.MONOSPACE
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        out.setTextColor(Ui.fg2)
        out.setTextIsSelectable(true)
        out.setPadding(pad, Ui.dp(ctx, 6), pad, Ui.dp(ctx, 6))
        out.text = "No debug session. Start one from Run and debug in the activity bar; the program's output and your expressions appear here."
        scroll.addView(out, android.widget.FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        view.addView(scroll, Ui.lp(Ui.match, 0, 1f))
        view.addView(Ui.divider(ctx, false))
        input.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) {
                append("> " + input.text.toString()); append("No debug session is running.")
                input.setText(""); true
            } else false
        }
        view.addView(input, Ui.lp(Ui.match, Ui.wrap).also { it.setMargins(pad, Ui.dp(ctx, 4), pad, Ui.dp(ctx, 6)) })
    }

    fun append(line: String) {
        out.append("\n" + line)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
