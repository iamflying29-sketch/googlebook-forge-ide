package local.forge

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Several shells side by side, like the old page's "bash 1", "bash 2" tabs: a tab row with a plus button and a
 * trash button over one terminal panel per shell. Each shell is its own session on the server (id "main", "t2",
 * "t3" and so on), so a closed window can come back to the same shells.
 */
class TerminalTabs(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val tabRow = LinearLayout(ctx)
    private val content = FrameLayout(ctx)
    private val panels = ArrayList<TerminalPanel>()
    private var active: TerminalPanel? = null
    private var counter = 0
    private var online = false

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.gravity = Gravity.CENTER_VERTICAL
        val scroll = HorizontalScrollView(ctx)
        scroll.addView(tabRow, FrameLayout.LayoutParams(Ui.wrap, Ui.match))
        scroll.isHorizontalScrollBarEnabled = false
        val plus = Ui.miniButton(ctx, R.drawable.ic_plus, "New terminal") { addTerminal() }
        val trash = Ui.miniButton(ctx, R.drawable.ic_trash, "Kill this terminal") { killActive() }
        val bar = Ui.row(ctx, scroll, plus, trash)
        scroll.layoutParams = Ui.lp(0, Ui.match, 1f)
        for (b in listOf(plus, trash)) b.layoutParams = Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)).also { it.leftMargin = Ui.dp(ctx, 4) }
        bar.setBackgroundColor(Ui.bg2)
        bar.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0)
        view.addView(bar, Ui.lp(Ui.match, Ui.dp(ctx, 30)))
        view.addView(Ui.divider(ctx, false))
        view.addView(content, Ui.lp(Ui.match, 0, 1f))
        addTerminal()
    }

    /** Opens one more shell and shows it. */
    fun addTerminal() {
        counter++
        val id = if (counter == 1) "main" else "t$counter"
        val p = TerminalPanel(ctx, host, id)
        panels.add(p)
        content.addView(p.view, FrameLayout.LayoutParams(Ui.match, Ui.match))
        activate(p)
        if (online) { p.connect(); p.focusInput() }
    }

    private fun activate(p: TerminalPanel) {
        active = p
        for (q in panels) q.view.visibility = if (q === p) View.VISIBLE else View.GONE
        renderTabs()
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        for ((i, p) in panels.withIndex()) {
            val t = TextView(ctx)
            t.text = "bash " + (i + 1)
            t.textSize = 12f
            t.setTextColor(if (p === active) Ui.fg else Ui.fg3)
            t.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6))
            t.background = if (p === active) Ui.rounded(Ui.bg3, 6, Ui.border) else null
            t.setOnClickListener { activate(p); p.focusInput() }
            tabRow.addView(t, Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = Ui.dp(ctx, 4) })
        }
    }

    /** Ends the shown shell on the server; the last shell is replaced by a fresh one instead of leaving nothing. */
    fun killActive() {
        val p = active ?: return
        p.kill()
        if (panels.size == 1) { if (online) p.connect(); return }
        panels.remove(p)
        content.removeView(p.view)
        activate(panels.last())
        panels.last().focusInput()
    }

    fun connect() { online = true; for (p in panels) p.connect() }
    fun focusInput() { active?.focusInput() }
    fun close() { online = false; for (p in panels) p.close() }
}
