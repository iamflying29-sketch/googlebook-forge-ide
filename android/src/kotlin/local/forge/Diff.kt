package local.forge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A line diff for the review of the agent's changes, ported from the old page's review.js: Myers' algorithm with
 * the same size guards (one block for very different texts), plus a memory cap the browser version did not have.
 * A [Hunk] is a pair of half-open line ranges: lines a0 until a1 of the original were replaced by lines b0 until b1
 * of the new text. The renderer draws the hunks natively: removed lines on a red background, added lines on a
 * green one, three lines of context around each hunk, and the line numbers of both sides in a gutter.
 */
object Diff {
    data class Hunk(val a0: Int, val a1: Int, val b0: Int, val b1: Int) {
        val removed: Int get() = a1 - a0
        val added: Int get() = b1 - b0
    }

    /** Splits a text into lines the way the old page did (text.split('\n')); an empty text is one empty line. */
    fun split(text: String): List<String> = text.split("\n")

    /** The hunks between the lines [a] (the original) and [b] (the new text). */
    fun lines(a: List<String>, b: List<String>): List<Hunk> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val aa = a.subList(pre, a.size - suf)
        val bb = b.subList(pre, b.size - suf)
        val n = aa.size
        val m = bb.size
        if (n == 0 && m == 0) return emptyList()
        val oneBlock = listOf(Hunk(pre, pre + n, pre, pre + m))
        if (n == 0 || m == 0 || n * m < 4 || n + m > 20000) return oneBlock
        val max = n + m
        val off = max + 1
        val width = 2 * max + 3
        val v = IntArray(width)
        val trace = ArrayList<IntArray>()
        var found = -1
        var d = 0
        while (d <= max && found < 0) {
            // Very different texts: one block. The second guard keeps the trace below about 24 MB.
            if (d > 1500 || d.toLong() * width > 6_000_000L) return oneBlock
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && aa[x] == bb[y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) { found = d; break }
                k += 2
            }
            d++
        }
        if (found < 0) return oneBlock
        // Walk back through the trace to list the equal line pairs.
        val equal = ArrayList<IntArray>()
        var x = n
        var y = m
        for (dd in found downTo 1) {
            val vv = trace[dd]
            val k = x - y
            val prevK = if (k == -dd || (k != dd && vv[off + k - 1] < vv[off + k + 1])) k + 1 else k - 1
            val px = vv[off + prevK]
            val py = px - prevK
            while (x > px && y > py) { equal.add(intArrayOf(x - 1, y - 1)); x--; y-- }
            x = px; y = py
        }
        while (x > 0 && y > 0) { equal.add(intArrayOf(x - 1, y - 1)); x--; y-- }
        equal.reverse()
        equal.add(intArrayOf(n, m))
        val out = ArrayList<Hunk>()
        var ia = 0
        var ib = 0
        for (e in equal) {
            val ea = e[0]; val eb = e[1]
            if (ea > ia || eb > ib) out.add(Hunk(pre + ia, pre + ea, pre + ib, pre + eb))
            ia = ea + 1; ib = eb + 1
        }
        return out
    }

    /** The original with one hunk accepted: its old lines are replaced by the new ones, so the hunk disappears. */
    fun keepHunk(base: List<String>, now: List<String>, h: Hunk): List<String> {
        val out = ArrayList<String>(base.size + h.added - h.removed)
        out.addAll(base.subList(0, h.a0))
        out.addAll(now.subList(h.b0, h.b1))
        out.addAll(base.subList(h.a1, base.size))
        return out
    }

    /** The new text with one hunk undone: its new lines are replaced by the original ones. */
    fun undoHunk(base: List<String>, now: List<String>, h: Hunk): List<String> {
        val out = ArrayList<String>(now.size + h.removed - h.added)
        out.addAll(now.subList(0, h.b0))
        out.addAll(base.subList(h.a0, h.a1))
        out.addAll(now.subList(h.b1, now.size))
        return out
    }

    /** The total of added and removed lines over all hunks. */
    fun stats(hunks: List<Hunk>): Pair<Int, Int> {
        var added = 0; var removed = 0
        for (h in hunks) { added += h.added; removed += h.removed }
        return Pair(added, removed)
    }

    // ------------------------------------------------------------------ rendering
    val addedBg: Int = 0x2e4cc38a
    val removedBg: Int = 0x2eff6b6b
    const val CONTEXT = 3

    /**
     * A monospace text whose lines can each have a full-width background color (the text spans of Android only
     * color the text itself). The text never wraps; the caller puts it into a HorizontalScrollView.
     */
    class DiffText(c: Context) : TextView(c) {
        var lineColors: IntArray = IntArray(0)
        private val paint = Paint()

        init {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Ui.fg)
            setHorizontallyScrolling(true)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            val p = Ui.dp(c, 8)
            setPadding(p, Ui.dp(c, 4), p, Ui.dp(c, 4))
        }

        override fun onDraw(canvas: Canvas) {
            val l = layout
            if (l != null && lineColors.isNotEmpty()) {
                val w = Math.max(width, l.width + paddingLeft + paddingRight).toFloat()
                val n = Math.min(l.lineCount, lineColors.size)
                for (i in 0 until n) {
                    val color = lineColors[i]
                    if (color == 0) continue
                    paint.color = color
                    val top = (l.getLineTop(i) + paddingTop).toFloat()
                    val bottom = (l.getLineBottom(i) + paddingTop).toFloat()
                    canvas.drawRect(0f, top, w, bottom, paint)
                }
            }
            super.onDraw(canvas)
        }
    }

    /**
     * Renders the hunks between [base] and [now] into a column: for every hunk a header line with the line ranges,
     * an optional action view from [actions] (Keep and Undo buttons, for example) and the lines themselves with
     * three lines of context on each side. Context lines are never shown twice when hunks are close together.
     */
    fun render(c: Context, base: List<String>, now: List<String>, hunks: List<Hunk>, actions: ((Int) -> View?)? = null): LinearLayout {
        val col = Ui.column(c)
        val digits = Math.max(2, Math.max(base.size, now.size).toString().length)
        var shownUntil = 0   // original lines below this index were already shown as context
        for ((i, h) in hunks.withIndex()) {
            val header = Ui.text(c, "@@ -" + (h.a0 + 1) + "," + h.removed + " +" + (h.b0 + 1) + "," + h.added + " @@", 11f, Ui.blue, mono = true)
            header.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 2))
            val headRow = Ui.row(c, header)
            header.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            val act = actions?.invoke(i)
            if (act != null) headRow.addView(act, Ui.lp(Ui.wrap, Ui.wrap))
            col.addView(headRow, Ui.lp(Ui.match, Ui.wrap))

            val sb = SpannableStringBuilder()
            val colors = ArrayList<Int>()
            fun gutter(a: Int, b: Int, marker: Char) {
                val start = sb.length
                sb.append(if (a > 0) a.toString().padStart(digits) else " ".repeat(digits))
                sb.append(' ')
                sb.append(if (b > 0) b.toString().padStart(digits) else " ".repeat(digits))
                sb.append(' ').append(marker).append(' ')
                sb.setSpan(ForegroundColorSpan(Ui.fg3), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            fun line(text: String, color: Int, bg: Int) {
                val start = sb.length
                sb.append(text)
                if (color != 0) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append('\n')
                colors.add(bg)
            }
            // Context before the hunk.
            val ctxStart = Math.max(Math.max(h.a0 - CONTEXT, shownUntil), 0)
            for (j in ctxStart until h.a0) {
                gutter(j + 1, h.b0 - (h.a0 - j) + 1, ' ')
                line(base[j], 0, 0)
            }
            for (j in h.a0 until h.a1) { gutter(j + 1, 0, '-'); line(base[j], Ui.red, removedBg) }
            for (j in h.b0 until h.b1) { gutter(0, j + 1, '+'); line(now[j], Ui.green, addedBg) }
            // Context after the hunk, stopping before the next hunk's own lines.
            val nextA0 = if (i + 1 < hunks.size) hunks[i + 1].a0 else base.size
            val ctxEnd = Math.min(Math.min(h.a1 + CONTEXT, nextA0), base.size)
            for (j in h.a1 until ctxEnd) {
                gutter(j + 1, h.b1 + (j - h.a1) + 1, ' ')
                line(base[j], 0, 0)
            }
            shownUntil = Math.max(shownUntil, ctxEnd)
            if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)

            val t = DiffText(c)
            t.text = sb
            t.lineColors = colors.toIntArray()
            t.background = Ui.rounded(Markdown.codeBg, 6, Ui.border)
            val scroll = HorizontalScrollView(c)
            scroll.isHorizontalScrollBarEnabled = true
            scroll.addView(t, LinearLayout.LayoutParams(Ui.wrap, Ui.wrap))
            val lp = Ui.lp(Ui.match, Ui.wrap); lp.leftMargin = Ui.dp(c, 8); lp.rightMargin = Ui.dp(c, 8); lp.bottomMargin = Ui.dp(c, 4)
            col.addView(scroll, lp)
        }
        return col
    }
}
