package local.forge

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A Markdown renderer for the chat, built from Android views: headings, bold, italic, inline code, fenced code
 * blocks with a language label and a Copy button, bullet and numbered lists, block quotes, links, horizontal rules
 * and tables (as aligned monospace). The text of a streaming reply is re-rendered as it grows: [Live] renders at
 * most every 80 ms and reuses the views of the blocks that did not change, so only the tail is rebuilt.
 */
object Markdown {
    val codeBg: Int = Color.parseColor("#0f1117")
    val inlineCodeBg: Int = Color.parseColor("#1b1f2a")
    val quoteBar: Int = Color.parseColor("#3a4157")
    val linkColor: Int = Ui.blue
    const val TEXT_SP = 13f
    const val CODE_SP = 12f

    // ------------------------------------------------------------------ blocks
    sealed class Block
    data class Para(val text: String) : Block()
    data class Heading(val level: Int, val text: String) : Block()
    data class Code(val lang: String, val body: String) : Block()
    data class Quote(val text: String) : Block()
    data class Item(val level: Int, val marker: String, val text: String)
    data class ListBlock(val items: List<Item>) : Block()
    data class Table(val header: List<String>, val align: List<Char>, val rows: List<List<String>>) : Block()
    object Rule : Block()

    private val fenceRe = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([A-Za-z0-9_+#.-]*)[^`]*$")
    private val headingRe = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val ruleRe = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val quoteRe = Regex("^\\s{0,3}>\\s?(.*)$")
    private val listRe = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")
    private val tableSepRe = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<Block>()
        val para = StringBuilder()
        var hardBreak = false
        fun flushPara() { if (para.isNotEmpty()) { out.add(Para(para.toString())); para.setLength(0) }; hardBreak = false }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = fenceRe.matchEntire(line)
            if (fence != null) {
                flushPara()
                val marker = fence.groupValues[1]
                val lang = fence.groupValues[2]
                val body = ArrayList<String>()
                i++
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (l.length >= marker.length && l.all { it == marker[0] }) break
                    body.add(lines[i]); i++
                }
                i++ // the closing fence, or one past the end while the block is still streaming
                out.add(Code(lang, body.joinToString("\n")))
                continue
            }
            if (line.isBlank()) { flushPara(); i++; continue }
            val heading = headingRe.matchEntire(line)
            if (heading != null) { flushPara(); out.add(Heading(heading.groupValues[1].length, heading.groupValues[2])); i++; continue }
            if (ruleRe.matches(line)) { flushPara(); out.add(Rule); i++; continue }
            if (quoteRe.matches(line)) {
                flushPara()
                val q = StringBuilder()
                while (i < lines.size) {
                    val m = quoteRe.matchEntire(lines[i]) ?: break
                    val inner = m.groupValues[1]
                    if (inner.isBlank()) q.append("\n\n") else { if (q.isNotEmpty() && !q.endsWith("\n")) q.append(' '); q.append(inner.trim()) }
                    i++
                }
                out.add(Quote(q.toString().trim()))
                continue
            }
            if (line.contains('|') && i + 1 < lines.size && lines[i + 1].contains('-') && tableSepRe.matches(lines[i + 1])) {
                val header = splitRow(line)
                val seps = splitRow(lines[i + 1])
                // A real table has the same number of cells in both rows; a lone "---" under prose with a pipe is a rule.
                if (header.size > 1 && seps.size == header.size) {
                    flushPara()
                    val align = header.indices.map { k ->
                        val s = seps.getOrNull(k)?.trim() ?: ""
                        if (s.startsWith(":") && s.endsWith(":")) 'c' else if (s.endsWith(":")) 'r' else 'l'
                    }
                    val rows = ArrayList<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows.add(splitRow(lines[i])); i++ }
                    out.add(Table(header, align, rows))
                    continue
                }
            }
            val li = listRe.matchEntire(line)
            if (li != null) {
                flushPara()
                val items = ArrayList<Item>()
                var text = StringBuilder()
                var level = 0; var marker = ""
                fun flushItem() { if (marker.isNotEmpty()) items.add(Item(level, marker, text.toString().trim())); text = StringBuilder(); marker = "" }
                while (i < lines.size) {
                    val cur = lines[i]
                    val m = listRe.matchEntire(cur)
                    if (m != null) {
                        flushItem()
                        val indent = m.groupValues[1].replace("\t", "    ").length
                        level = (indent / 2).coerceAtMost(5)
                        val raw = m.groupValues[2]
                        marker = if (raw[0].isDigit()) raw.dropLast(1) + "." else when (level) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
                        text.append(m.groupValues[3])
                        i++
                    } else if (cur.isBlank()) {
                        // A blank line ends the list unless another item follows it.
                        if (i + 1 < lines.size && listRe.matches(lines[i + 1])) { i++ } else break
                    } else if (cur.startsWith(" ") || cur.startsWith("\t")) {
                        text.append(' ').append(cur.trim()); i++
                    } else break
                }
                flushItem()
                out.add(ListBlock(items))
                continue
            }
            // An ordinary paragraph line. Two trailing spaces or a trailing backslash make a hard line break.
            val t = line.trim()
            if (para.isNotEmpty()) para.append(if (hardBreak) "\n" else " ")
            hardBreak = line.endsWith("  ") || line.endsWith("\\")
            para.append(if (line.endsWith("\\")) t.dropLast(1) else t)
            i++
        }
        flushPara()
        return out
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length && s[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (c == '|') { cells.add(cur.toString().trim()); cur.setLength(0) } else cur.append(c)
            k++
        }
        cells.add(cur.toString().trim())
        return cells
    }

    // ------------------------------------------------------------------ inline spans
    /** Bold, italic, strikethrough, inline code and links as a Spannable. */
    fun inline(src: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        inlineInto(src, sb)
        return sb
    }

    private fun span(sb: SpannableStringBuilder, start: Int, what: Any) {
        if (sb.length > start) sb.setSpan(what, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun inlineInto(s: String, sb: SpannableStringBuilder) {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            // Escapes.
            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!~|>".indexOf(s[i + 1]) >= 0) { sb.append(s[i + 1]); i += 2; continue }
            // Inline code: a run of backticks closed by the same run.
            if (c == '`') {
                var run = 0
                while (i + run < n && s[i + run] == '`') run++
                val ticks = s.substring(i, i + run)
                val close = s.indexOf(ticks, i + run)
                if (close < 0) { sb.append(ticks); i += run; continue }
                var code = s.substring(i + run, close)
                if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ")) code = code.substring(1, code.length - 1)
                val start = sb.length
                sb.append(code)
                span(sb, start, TypefaceSpan("monospace")); span(sb, start, BackgroundColorSpan(inlineCodeBg)); span(sb, start, RelativeSizeSpan(0.92f))
                i = close + run
                continue
            }
            // Images and links: ![alt](url) and [text](url).
            if ((c == '[') || (c == '!' && i + 1 < n && s[i + 1] == '[')) {
                val open = if (c == '!') i + 1 else i
                val closeBracket = matchingBracket(s, open)
                if (closeBracket > 0 && closeBracket + 1 < n && s[closeBracket + 1] == '(') {
                    val closeParen = s.indexOf(')', closeBracket + 2)
                    if (closeParen > 0) {
                        val label = s.substring(open + 1, closeBracket)
                        var url = s.substring(closeBracket + 2, closeParen).trim()
                        val sp = url.indexOf(' '); if (sp > 0) url = url.substring(0, sp)   // drop a "title"
                        val start = sb.length
                        if (c == '!') sb.append(if (label.isNotEmpty()) label else "image") else inlineInto(label, sb)
                        if (sb.length == start) sb.append(url)
                        span(sb, start, Link(url))
                        i = closeParen + 1
                        continue
                    }
                }
                sb.append(c); i++
                continue
            }
            // Bare web addresses.
            if (c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i))) {
                var j = i
                while (j < n && !s[j].isWhitespace() && s[j] != '<' && s[j] != '"' && s[j] != '>') j++
                while (j > i && ".,;:!?)]'".indexOf(s[j - 1]) >= 0) j--
                val url = s.substring(i, j)
                val start = sb.length
                sb.append(url)
                span(sb, start, Link(url))
                i = j
                continue
            }
            // Strikethrough.
            if (c == '~' && i + 1 < n && s[i + 1] == '~') {
                val close = s.indexOf("~~", i + 2)
                if (close > i + 2) {
                    val start = sb.length
                    inlineInto(s.substring(i + 2, close), sb)
                    span(sb, start, StrikethroughSpan())
                    i = close + 2
                    continue
                }
                sb.append("~~"); i += 2
                continue
            }
            // Emphasis with * or _.
            if (c == '*' || c == '_') {
                var run = 0
                while (i + run < n && s[i + run] == c && run < 3) run++
                val after = if (i + run < n) s[i + run] else ' '
                val before = if (i > 0) s[i - 1] else ' '
                val wordChar = { ch: Char -> ch.isLetterOrDigit() }
                val canOpen = !after.isWhitespace() && (c == '*' || !wordChar(before))
                if (canOpen) {
                    val close = findEmphasisClose(s, i + run, c, run)
                    if (close > 0) {
                        val start = sb.length
                        inlineInto(s.substring(i + run, close), sb)
                        if (run >= 2) span(sb, start, StyleSpan(Typeface.BOLD))
                        if (run == 1 || run == 3) span(sb, start, StyleSpan(Typeface.ITALIC))
                        i = close + run
                        continue
                    }
                }
                sb.append(s, i, i + run); i += run
                continue
            }
            sb.append(c); i++
        }
    }

    /** The index of the ']' matching the '[' at [open], honouring nested brackets, or -1. */
    private fun matchingBracket(s: String, open: Int): Int {
        var depth = 0
        var k = open
        while (k < s.length) {
            val ch = s[k]
            if (ch == '\\') { k += 2; continue }
            if (ch == '[') depth++ else if (ch == ']') { depth--; if (depth == 0) return k }
            k++
        }
        return -1
    }

    /** The position of a closing run of [run] delimiter characters [d] after [from], not preceded by a space. */
    private fun findEmphasisClose(s: String, from: Int, d: Char, run: Int): Int {
        var k = from
        while (k < s.length) {
            if (s[k] == '`') { // skip inline code
                var r = 0; while (k + r < s.length && s[k + r] == '`') r++
                val close = s.indexOf(s.substring(k, k + r), k + r)
                if (close > 0) { k = close + r; continue }
            }
            if (s[k] == d) {
                var r = 0
                while (k + r < s.length && s[k + r] == d) r++
                val prevOk = k > from && !s[k - 1].isWhitespace()
                val nextOk = d == '*' || k + r >= s.length || !s[k + r].isLetterOrDigit()
                if (r >= run && prevOk && nextOk) return k
                k += r
                continue
            }
            k++
        }
        return -1
    }

    /** A clickable link: opens the address with the system's browser or app. */
    class Link(val url: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                widget.context.startActivity(i)
            } catch (e: Exception) { Ui.toast(widget.context, "Nothing can open $url") }
        }
        override fun updateDrawState(ds: TextPaint) { ds.color = linkColor; ds.isUnderlineText = true }
    }

    /**
     * Selection by drag plus clickable links in one text view. Android's LinkMovementMethod forbids selection and
     * the selectable default ignores links, so this one handles a tap on a link and leaves everything else to the
     * selectable behaviour.
     */
    class LinkArrowMovement : ArrowKeyMovementMethod() {
        private var downX = 0f
        private var downY = 0f
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val action = event.action
            if (action == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
            if (action == MotionEvent.ACTION_UP) {
                val slop = Ui.dp(widget.context, 8)
                if (Math.abs(event.x - downX) < slop && Math.abs(event.y - downY) < slop) {
                    val link = linkAt(widget, buffer, event)
                    if (link != null && !widget.hasSelection()) { link.onClick(widget); return true }
                }
            }
            return super.onTouchEvent(widget, buffer, event)
        }

        private fun linkAt(widget: TextView, buffer: Spannable, event: MotionEvent): ClickableSpan? {
            val layout = widget.layout ?: return null
            val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
            val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
            if (y < 0 || y > layout.height) return null
            val line = layout.getLineForVertical(y)
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
            val off = layout.getOffsetForHorizontal(line, x.toFloat())
            val links = buffer.getSpans(off, off, ClickableSpan::class.java)
            return links.firstOrNull()
        }

        companion object { val instance = LinkArrowMovement() }
    }

    // ------------------------------------------------------------------ views
    /** A selectable text view for content: links open on tap, text selects on long press. */
    fun textView(c: Context, text: CharSequence, size: Float = TEXT_SP, color: Int = Ui.fg, mono: Boolean = false): TextView {
        val t = TextView(c)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        if (mono) t.typeface = Typeface.MONOSPACE
        t.setLineSpacing(0f, if (mono) 1.15f else 1.3f)
        t.setLinkTextColor(linkColor)
        t.setTextIsSelectable(true)
        t.movementMethod = LinkArrowMovement.instance
        return t
    }

    private fun margins(c: Context, top: Int, bottom: Int): LinearLayout.LayoutParams {
        val lp = Ui.lp(Ui.match, Ui.wrap); lp.topMargin = Ui.dp(c, top); lp.bottomMargin = Ui.dp(c, bottom); return lp
    }

    /** Renders [text] into [into] after clearing it. */
    fun render(c: Context, text: String, into: LinearLayout, onCopy: (String) -> Unit) {
        into.removeAllViews()
        for (b in parse(text)) into.addView(blockView(c, b, onCopy), margins(c, 3, 3))
    }

    fun blockView(c: Context, b: Block, onCopy: (String) -> Unit): View = when (b) {
        is Para -> textView(c, inline(b.text))
        is Heading -> {
            val size = when (b.level) { 1 -> 18f; 2 -> 16f; 3 -> 14.5f; else -> 13.5f }
            val t = textView(c, inline(b.text), size)
            t.setTypeface(t.typeface, Typeface.BOLD)
            t.setPadding(0, Ui.dp(c, if (b.level <= 2) 6 else 3), 0, 0)
            t
        }
        is Code -> codeBlock(c, b.lang, b.body, onCopy)
        is Quote -> {
            val bar = View(c); bar.setBackgroundColor(quoteBar)
            val t = textView(c, inline(b.text), TEXT_SP, Ui.fg2)
            t.setPadding(Ui.dp(c, 10), 0, 0, 0)
            val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL
            row.addView(bar, LinearLayout.LayoutParams(Ui.dp(c, 3), Ui.match))
            row.addView(t, Ui.lp(0, Ui.wrap, 1f))
            row
        }
        is ListBlock -> {
            val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL
            for (it in b.items) {
                val marker = TextView(c)
                marker.text = it.marker
                marker.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP); marker.setTextColor(Ui.fg2)
                marker.setLineSpacing(0f, 1.3f)
                marker.minWidth = Ui.dp(c, 18)
                marker.setPadding(0, 0, Ui.dp(c, 4), 0)
                val body = textView(c, inline(it.text))
                val row = LinearLayout(c); row.orientation = LinearLayout.HORIZONTAL
                row.addView(marker, Ui.lp(Ui.wrap, Ui.wrap))
                row.addView(body, Ui.lp(0, Ui.wrap, 1f))
                val lp = Ui.lp(Ui.match, Ui.wrap); lp.leftMargin = Ui.dp(c, 4 + 14 * it.level); lp.topMargin = Ui.dp(c, 1)
                col.addView(row, lp)
            }
            col
        }
        is Table -> tableView(c, b)
        is Rule -> {
            val v = View(c); v.setBackgroundColor(Ui.border)
            val wrap = LinearLayout(c); wrap.addView(v, LinearLayout.LayoutParams(Ui.match, Ui.dp(c, 1)))
            wrap.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6))
            wrap
        }
    }

    /** A fenced code block: a dark monospace box with a thin border, the language and a Copy button. */
    fun codeBlock(c: Context, lang: String, body: String, onCopy: (String) -> Unit): View {
        val box = LinearLayout(c); box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(codeBg, 6, Ui.border)
        val label = Ui.text(c, if (lang.isNotEmpty()) lang else "code", 10.5f, Ui.fg3)
        val copy = Ui.text(c, "Copy", 11f, Ui.fg2)
        copy.background = Ui.rounded(Ui.bg3, 5, Ui.border)
        copy.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2))
        copy.setOnClickListener { onCopy(body); copy.text = "Copied"; copy.postDelayed({ copy.text = "Copy" }, 1200) }
        val head = Ui.row(c, label, copy)
        label.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        head.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4))
        head.background = Ui.rounded(Ui.bg2, 6).also { it.cornerRadii = floatArrayOf(18f, 18f, 18f, 18f, 0f, 0f, 0f, 0f) }
        val code = textView(c, body, CODE_SP, Ui.fg, mono = true)
        code.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8))
        code.setOnLongClickListener { onCopy(body); Ui.toast(c, "Copied"); true }
        box.addView(head, Ui.lp(Ui.match, Ui.wrap))
        box.addView(Ui.divider(c, false))
        box.addView(code, Ui.lp(Ui.match, Ui.wrap))
        return box
    }

    /** A table as aligned monospace text, scrollable sideways when it is wider than the panel. */
    private fun tableView(c: Context, t: Table): View {
        val cols = t.header.size
        val widths = IntArray(cols) { k -> t.header[k].length }
        for (r in t.rows) for (k in 0 until cols) widths[k] = maxOf(widths[k], r.getOrNull(k)?.length ?: 0)
        fun pad(s: String, w: Int, a: Char): String {
            val gap = w - s.length
            if (gap <= 0) return s
            return when (a) { 'r' -> " ".repeat(gap) + s; 'c' -> " ".repeat(gap / 2) + s + " ".repeat(gap - gap / 2); else -> s + " ".repeat(gap) }
        }
        fun line(cells: List<String>): String = (0 until cols).joinToString("  ") { k -> pad(cells.getOrNull(k) ?: "", widths[k], t.align[k]) }
        val sb = SpannableStringBuilder()
        sb.append(line(t.header))
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n').append((0 until cols).joinToString("  ") { k -> "─".repeat(widths[k]) })
        for (r in t.rows) sb.append('\n').append(line(r))
        val tv = textView(c, sb, CODE_SP, Ui.fg, mono = true)
        tv.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6))
        tv.background = Ui.rounded(codeBg, 6, Ui.border)
        val h = HorizontalScrollView(c)
        h.isHorizontalScrollBarEnabled = false
        h.addView(tv)
        return h
    }

    // ------------------------------------------------------------------ streaming
    /**
     * Renders a growing text into [view] at most every 80 ms. Blocks that did not change keep their views; only the
     * tail is rebuilt. [finish] renders the final text once, right away.
     */
    class Live(private val c: Context, val view: LinearLayout, private val onCopy: (String) -> Unit) {
        private val handler = Handler(Looper.getMainLooper())
        private var text = ""
        private var lastRender = 0L
        private var scheduled = false
        private var shown: List<Block> = emptyList()
        private val task = Runnable { scheduled = false; renderNow() }

        fun update(t: String) {
            text = t
            if (scheduled) return
            val wait = 80 - (SystemClock.uptimeMillis() - lastRender)
            if (wait <= 0) renderNow() else { scheduled = true; handler.postDelayed(task, wait) }
        }

        fun finish(t: String) {
            text = t
            handler.removeCallbacks(task); scheduled = false
            renderNow()
        }

        fun cancel() { handler.removeCallbacks(task); scheduled = false }

        private fun renderNow() {
            lastRender = SystemClock.uptimeMillis()
            val blocks = parse(text)
            var keep = 0
            while (keep < blocks.size && keep < shown.size && keep < view.childCount && blocks[keep] == shown[keep]) keep++
            while (view.childCount > keep) view.removeViewAt(view.childCount - 1)
            for (k in keep until blocks.size) {
                val lp = Ui.lp(Ui.match, Ui.wrap); lp.topMargin = Ui.dp(c, 3); lp.bottomMargin = Ui.dp(c, 3)
                view.addView(blockView(c, blocks[k], onCopy), lp)
            }
            shown = blocks
        }
    }
}
