package local.forge

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small pieces shared by the side views that were ported from the old page's modules: source control, notepads,
 * chat history and docs. Each side view starts with the 34 dp header built here, uses the same list rows, section
 * labels, confirmation dialogs and text prompts, and the source control view uses the line diff at the end.
 */
object SideKit {

    /** The 34 dp header of a side view: the view's name in small caps on the left, 24 dp action buttons on the right. */
    fun header(c: Context, title: String, vararg actions: View): LinearLayout {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundColor(Ui.panel)
        row.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0)
        val t = Ui.sectionLabel(c, title)
        t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
        row.addView(t, Ui.lp(0, Ui.wrap, 1f))
        for (a in actions) {
            val lp = Ui.lp(Ui.dp(c, 24), Ui.dp(c, 24)); lp.leftMargin = Ui.dp(c, 4)
            row.addView(a, lp)
        }
        row.layoutParams = Ui.lp(Ui.match, Ui.dp(c, 34))
        return row
    }

    /** A 24 dp square button that shows one character instead of an icon (the page's text ".mini" buttons). */
    fun textMini(c: Context, label: String, tooltip: String, onClick: (View) -> Unit): TextView {
        val t = TextView(c)
        t.text = label
        t.typeface = Typeface.MONOSPACE
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.gravity = Gravity.CENTER
        t.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_hovered), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(Ui.fg, Ui.fg, Ui.fg3)))
        t.background = Ui.states(null, Ui.rounded(Ui.bg4, 5), Ui.rounded(Ui.border2, 5))
        t.isClickable = true; t.isFocusable = true
        t.layoutParams = Ui.lp(Ui.dp(c, 24), Ui.dp(c, 24))
        t.tooltipText = tooltip; t.contentDescription = tooltip
        t.setOnClickListener(onClick)
        return t
    }

    /** Secondary text with the page's ".muted .pad" look: 10 dp above and below, 12 dp on the sides. */
    fun muted(c: Context, s: String): TextView {
        val t = Ui.text(c, s, 12.5f, Ui.fg3)
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10))
        t.layoutParams = Ui.lp(Ui.match, Ui.wrap)
        return t
    }

    /** A small uppercase section label inside a view, such as "STAGED CHANGES (2)". */
    fun section(c: Context, s: String, color: Int = Ui.fg3): TextView {
        val t = Ui.text(c, s.uppercase(), 11f, color, bold = true)
        t.letterSpacing = 0.07f
        t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 2))
        t.layoutParams = Ui.lp(Ui.match, Ui.wrap)
        return t
    }

    /** A clickable list row: transparent at rest, lighter while hovered or pressed, accent tinted when selected. */
    fun listRow(c: Context, selected: Boolean = false): LinearLayout {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isClickable = true; row.isFocusable = true
        row.background = if (selected) Ui.rounded(Ui.accentSoft, 0) else Ui.states(null, Ui.rounded(Ui.bg3, 0), Ui.rounded(Ui.bg4, 0))
        row.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4))
        row.layoutParams = Ui.lp(Ui.match, Ui.wrap)
        return row
    }

    /** The question the old page asked with confirm(): a title, a sentence and the action's own button. */
    fun confirm(c: Context, title: String, message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(c).setTitle(title).setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Asks for one line of text (a new name). Enter confirms. An empty answer is ignored. */
    fun askText(c: Context, title: String, initial: String, hint: String, action: String, onOk: (String) -> Unit) {
        val input = Ui.input(c, hint)
        input.setText(initial); input.setSelection(initial.length)
        val box = FrameLayout(c)
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), 0)
        box.addView(input, FrameLayout.LayoutParams(Ui.match, Ui.wrap))
        val d = AlertDialog.Builder(c).setTitle(title).setView(box)
            .setPositiveButton(action) { _, _ -> val s = input.text.toString().trim(); if (s.isNotEmpty()) onOk(s) }
            .setNegativeButton("Cancel", null).create()
        input.setOnEditorActionListener { _, _, _ -> d.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick(); true }
        input.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { d.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick(); true } else false
        }
        d.show()
        input.requestFocus()
    }

    /** Shows a command's output (or any longer text) in a scrollable monospace dialog with a Close button. */
    fun showText(c: Context, title: String, text: String, extraAction: String? = null, onExtra: (() -> Unit)? = null) {
        val t = Ui.text(c, text.ifBlank { "(no output)" }, 12f, Ui.fg2, mono = true)
        t.setTextIsSelectable(true)
        t.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 8))
        val b = AlertDialog.Builder(c).setTitle(title).setView(Ui.scroll(c, t)).setPositiveButton("Close", null)
        if (extraAction != null && onExtra != null) b.setNeutralButton(extraAction) { _, _ -> onExtra() }
        b.show()
    }

    /** "now", "5 min ago", "3 h ago", "2 d ago" for a Unix time in seconds (the page's timeAgo). */
    fun timeAgo(ts: Double): String {
        val d = System.currentTimeMillis() / 1000.0 - ts
        return when {
            ts <= 0 -> ""
            d < 60 -> "now"
            d < 3600 -> (d / 60).toInt().toString() + " min ago"
            d < 86400 -> (d / 3600).toInt().toString() + " h ago"
            else -> (d / 86400).toInt().toString() + " d ago"
        }
    }

    /** The last path element of a folder, for folder headings. */
    fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    // ---------------------------------------------------------------- line diff
    /** One line of a diff: kind is ' ' (unchanged), '-' (removed) or '+' (added). */
    class DiffLine(val kind: Char, val text: String)

    /**
     * The line diff of two texts: the common beginning and end are matched directly, the middle with a longest
     * common subsequence table. A middle larger than 1,500 by 1,500 lines is shown as "everything removed, then
     * everything added", so the table never grows past about nine megabytes.
     */
    fun lineDiff(original: String, modified: String): List<DiffLine> {
        val a = if (original.isEmpty()) emptyList() else original.split('\n')
        val b = if (modified.isEmpty()) emptyList() else modified.split('\n')
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size; var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
        val out = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until start) out.add(DiffLine(' ', a[i]))
        val midA = a.subList(start, endA); val midB = b.subList(start, endB)
        if (midA.size.toLong() * midB.size.toLong() <= 2_250_000L) lcs(midA, midB, out)
        else { for (l in midA) out.add(DiffLine('-', l)); for (l in midB) out.add(DiffLine('+', l)) }
        for (i in endA until a.size) out.add(DiffLine(' ', a[i]))
        return out
    }

    private fun lcs(a: List<String>, b: List<String>, out: MutableList<DiffLine>) {
        val n = a.size; val m = b.size; val w = m + 1
        val t = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            t[i * w + j] = if (a[i] == b[j]) t[(i + 1) * w + j + 1] + 1 else maxOf(t[(i + 1) * w + j], t[i * w + j + 1])
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(DiffLine(' ', a[i])); i++; j++ }
                t[(i + 1) * w + j] >= t[i * w + j + 1] -> { out.add(DiffLine('-', a[i])); i++ }
                else -> { out.add(DiffLine('+', b[j])); j++ }
            }
        }
        while (i < n) out.add(DiffLine('-', a[i++]))
        while (j < m) out.add(DiffLine('+', b[j++]))
    }
}
