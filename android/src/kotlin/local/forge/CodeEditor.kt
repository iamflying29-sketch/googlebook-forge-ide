package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The code editing view: a monospace EditText that scrolls on its own in both directions (no wrapping), a gutter
 * with line numbers that follows the same layout and scroll offset, a current-line highlight, a find and replace
 * bar, an undo stack of our own, and syntax colors from Highlighter.
 *
 * Staying responsive on large files: the EditText keeps the viewport's height, so Android records and draws only
 * the visible lines; the tokenizer runs on one background thread over the visible lines plus a margin, 120 ms after
 * the last edit, and spans are applied to that region only. Nothing here ever runs a regex over the whole file.
 */
class CodeEditor(ctx: Context) : LinearLayout(ctx) {
    companion object {
        val lineHighlight = 0xff161a25.toInt()
        val activeNumber = 0xff9aa3bd.toInt()
        val selection = 0xff2b3550.toInt()
        private const val MARGIN_LINES = 150
        private const val MAX_UNDO = 500
        private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "forge-highlight").also { it.isDaemon = true } }
    }

    /** The language of the file, set by setContent(); changing it re-colors the visible region. */
    var lang: Highlighter.Lang = Highlighter.plain
        set(v) { field = v; scheduleHighlight(0) }

    /** Called on the main thread after every change the user makes (typing, undo, replace), never after setContent(). */
    var onChanged: (() -> Unit)? = null

    // The gutter is declared before the EditText on purpose: the EditText's constructor already reports a selection
    // change, and that callback repaints the gutter.
    private val gutter = Gutter(ctx)
    val edit = CodeEditText(ctx)

    private val findBar: LinearLayout
    private val findInput: EditText
    private val replaceInput: EditText
    private val findStatus: TextView
    private val caseBox: CheckBox
    private val findDivider: View

    private class Edit(var start: Int, var removed: String, var added: String, var time: Long)
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var muteHistory = false
    private var pendingRemoved = ""
    private var textVersion = 0
    private var hiFirst = -1
    private var hiLast = -1
    private val highlightTask = Runnable { runHighlight() }
    private val scrollTask = Runnable { afterScroll() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.bg)
        val pad = Ui.dp(ctx, 8)

        // ---- the find bar (hidden until Ctrl F)
        findInput = Ui.input(ctx, "Find", mono = true)
        replaceInput = Ui.input(ctx, "Replace", mono = true)
        findStatus = Ui.text(ctx, "", 11f, Ui.fg3)
        caseBox = CheckBox(ctx); caseBox.text = "Aa"; caseBox.setTextColor(Ui.fg2); caseBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        caseBox.setOnCheckedChangeListener { _, _ -> updateFindStatus() }
        val prev = smallButton("↑") { findNext(false) }
        val next = smallButton("↓") { findNext(true) }
        val replaceOne = smallButton("Replace") { replaceOne() }
        val replaceAll = smallButton("All") { replaceAll() }
        val close = smallButton("✕") { hideFind() }
        findBar = Ui.row(ctx, findInput, findStatus, prev, next, caseBox, replaceInput, replaceOne, replaceAll, close)
        findInput.layoutParams = Ui.lp(0, Ui.wrap, 1.2f)
        replaceInput.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        findStatus.setPadding(pad / 2, 0, pad / 2, 0)
        findBar.setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        findBar.setBackgroundColor(Ui.bg2)
        findBar.visibility = View.GONE
        addView(findBar, Ui.lp(Ui.match, Ui.wrap))
        findDivider = Ui.divider(ctx, false)
        findDivider.visibility = View.GONE
        addView(findDivider, Ui.lp(Ui.match, Ui.dp(ctx, 1)))
        val keyListener = View.OnKeyListener { v, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (v === replaceInput) replaceOne() else findNext(!ev.isShiftPressed); true }
                KeyEvent.KEYCODE_ESCAPE -> { hideFind(); true }
                else -> false
            }
        }
        findInput.setOnKeyListener(keyListener)
        replaceInput.setOnKeyListener(keyListener)
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateFindStatus() }
        })

        // ---- the text and its gutter
        edit.typeface = Typeface.MONOSPACE
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        edit.setTextColor(Ui.fg)
        edit.highlightColor = selection
        edit.background = null
        edit.gravity = Gravity.TOP or Gravity.START
        edit.setPadding(Ui.dp(ctx, 6), pad, pad, pad)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.imeOptions = edit.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        edit.setHorizontallyScrolling(true) // after inputType: a multi-line input type switches wrapping back on
        edit.isVerticalScrollBarEnabled = true
        edit.isHorizontalScrollBarEnabled = true
        edit.isSaveEnabled = false
        edit.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        edit.setLineSpacing(Ui.dp(ctx, 2).toFloat(), 1f)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (!muteHistory) pendingRemoved = s.subSequence(start, start + count).toString()
            }
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (!muteHistory) record(Edit(start, pendingRemoved, s.subSequence(start, start + count).toString(), System.currentTimeMillis()))
            }
            override fun afterTextChanged(s: Editable) {
                textVersion++
                gutter.onLinesChanged()
                scheduleHighlight(120)
                if (!muteHistory) onChanged?.invoke()
            }
        })
        gutter.setPadding(pad, pad, Ui.dp(ctx, 6), pad)
        gutter.setBackgroundColor(Ui.bg)
        val row = LinearLayout(ctx)
        row.orientation = HORIZONTAL
        row.addView(gutter, Ui.lp(Ui.wrap, Ui.match))
        row.addView(edit, Ui.lp(0, Ui.match, 1f))
        addView(row, Ui.lp(Ui.match, 0, 1f))
    }

    private fun smallButton(label: String, onClick: (View) -> Unit): View {
        val b = Ui.button(context, label, onClick = onClick)
        b.setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
        b.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.setMargins(Ui.dp(context, 3), 0, 0, 0) }
        return b
    }

    // ------------------------------------------------------------------ content
    /** Loads a file's text. The language comes from the path. Undo history and the dirty state start fresh. */
    fun setContent(text: String, path: String) {
        muteHistory = true
        edit.setText(text)
        muteHistory = false
        undoStack.clear(); redoStack.clear()
        edit.setSelection(0)
        edit.scrollTo(0, 0)
        hiFirst = -1; hiLast = -1
        lang = Highlighter.langFor(path)
    }

    /** Replaces the text but keeps the cursor and the scroll position where they were (for a file changed on disk). */
    fun replaceContent(text: String) {
        val sel = edit.selectionStart.coerceAtLeast(0)
        val sx = edit.scrollX; val sy = edit.scrollY
        muteHistory = true
        edit.setText(text)
        muteHistory = false
        undoStack.clear(); redoStack.clear()
        edit.setSelection(sel.coerceAtMost(text.length))
        post { edit.scrollTo(sx, sy.coerceAtMost(maxScrollY())) }
        hiFirst = -1; hiLast = -1
        scheduleHighlight(0)
    }

    fun text(): String = edit.text.toString()
    fun focus() { edit.requestFocus() }
    fun selectionText(): String { val t = edit.text; val a = edit.selectionStart; val b = edit.selectionEnd; return if (a in 0 until b) t.subSequence(a, b).toString() else "" }

    private fun maxScrollY(): Int {
        val l = edit.layout ?: return 0
        return (l.height + edit.extendedPaddingTop + edit.extendedPaddingBottom - edit.height).coerceAtLeast(0)
    }

    /** Puts the cursor at the start of a 1-based line and scrolls so the line sits in the upper third. */
    fun goToLine(n: Int) {
        val l = edit.layout
        if (l == null) { post { goToLine(n) }; return }
        val line = (n - 1).coerceIn(0, l.lineCount - 1)
        edit.setSelection(l.getLineStart(line))
        val y = (l.getLineTop(line) - edit.height / 3).coerceIn(0, maxScrollY())
        edit.scrollTo(0, y)
        edit.requestFocus()
    }

    fun askGoToLine() {
        val input = Ui.input(context, "Line number", mono = true)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        val lines = edit.layout?.lineCount ?: 1
        val dialog = AlertDialog.Builder(context).setTitle("Go to line (1 to $lines)").setView(input)
            .setPositiveButton("Go") { _, _ -> input.text.toString().trim().toIntOrNull()?.let { goToLine(it) } }
            .setNegativeButton("Cancel", null).create()
        input.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                input.text.toString().trim().toIntOrNull()?.let { goToLine(it) }; dialog.dismiss(); true
            } else false
        }
        dialog.show()
        input.requestFocus()
    }

    // ------------------------------------------------------------------ undo and redo
    private fun record(e: Edit) {
        redoStack.clear()
        val last = undoStack.lastOrNull()
        if (last != null && e.time - last.time < 1500) {
            // Typing one character after another becomes one undo step; so does a run of backspaces.
            if (last.removed.isEmpty() && e.removed.isEmpty() && e.added.length == 1 && e.added != "\n" && last.added.length < 60 && last.start + last.added.length == e.start) {
                last.added += e.added; last.time = e.time; return
            }
            if (last.added.isEmpty() && e.added.isEmpty() && e.removed.length == 1 && last.removed.length < 60 && e.start + 1 == last.start) {
                last.start = e.start; last.removed = e.removed + last.removed; last.time = e.time; return
            }
        }
        undoStack.addLast(e)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    fun undo() { val e = undoStack.removeLastOrNull() ?: return; applySilently(e.start, e.added.length, e.removed); redoStack.addLast(e) }
    fun redo() { val e = redoStack.removeLastOrNull() ?: return; applySilently(e.start, e.removed.length, e.added); undoStack.addLast(e) }

    private fun applySilently(start: Int, length: Int, replacement: String) {
        val t = edit.text
        val s = start.coerceIn(0, t.length)
        val e = (start + length).coerceIn(s, t.length)
        muteHistory = true
        t.replace(s, e, replacement)
        muteHistory = false
        edit.setSelection((s + replacement.length).coerceIn(0, t.length))
        onChanged?.invoke()
    }

    /** A recorded replacement (one undo step) that leaves the selection where asked. */
    private fun replaceText(start: Int, end: Int, replacement: String, selStart: Int = -1, selEnd: Int = -1) {
        val t = edit.text
        val s = start.coerceIn(0, t.length)
        val e = end.coerceIn(s, t.length)
        t.replace(s, e, replacement)
        val a = (if (selStart < 0) s + replacement.length else selStart).coerceIn(0, t.length)
        val b = (if (selEnd < 0) a else selEnd).coerceIn(0, t.length)
        edit.setSelection(a, b)
    }

    // ------------------------------------------------------------------ keys
    private fun handleKey(code: Int, ev: KeyEvent): Boolean {
        if (ev.isCtrlPressed && !ev.isAltPressed) {
            when (code) {
                KeyEvent.KEYCODE_Z -> { if (ev.isShiftPressed) redo() else undo(); return true }
                KeyEvent.KEYCODE_Y -> { redo(); return true }
                KeyEvent.KEYCODE_D -> { duplicateLine(); return true }
                KeyEvent.KEYCODE_SLASH -> { toggleComment(); return true }
                KeyEvent.KEYCODE_F -> { showFind(); return true }
                KeyEvent.KEYCODE_H -> { showFind(); replaceInput.requestFocus(); return true }
                KeyEvent.KEYCODE_G -> { askGoToLine(); return true }
            }
            return false
        }
        if (ev.isAltPressed) return false
        when (code) {
            KeyEvent.KEYCODE_TAB -> { if (ev.isShiftPressed) outdent() else indent(); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (ev.isShiftPressed) return false; newlineWithIndent(); return true }
            KeyEvent.KEYCODE_ESCAPE -> { if (findBar.visibility == View.VISIBLE) { hideFind(); return true } }
        }
        return false
    }

    private fun tabString(): String = " ".repeat(lang.tabSize)
    private fun lineStartOf(pos: Int): Int = TextUtils.lastIndexOf(edit.text, '\n', pos - 1) + 1
    private fun lineEndOf(pos: Int): Int { val i = TextUtils.indexOf(edit.text, '\n', pos); return if (i < 0) edit.text.length else i }
    private fun selStart(): Int = minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
    private fun selEnd(): Int = maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)

    private fun newlineWithIndent() {
        val t = edit.text
        val s = selStart(); val e = selEnd()
        val ls = lineStartOf(s)
        var i = ls
        while (i < s && (t[i] == ' ' || t[i] == '\t')) i++
        val indent = t.subSequence(ls, i).toString()
        var j = s - 1
        while (j >= ls && t[j] == ' ') j--
        val prev = if (j >= ls) t[j] else ' '
        val extra = if (prev == '{' || prev == '(' || prev == '[' || (lang.id == "python" && prev == ':')) tabString() else ""
        replaceText(s, e, "\n" + indent + extra)
    }

    /** Runs f over the complete lines touched by the selection and replaces them, keeping the block selected. */
    private fun mapSelectedLines(f: (List<String>) -> List<String>) {
        val s = selStart(); val e = selEnd()
        val bs = lineStartOf(s)
        val be = lineEndOf(if (e > s && e > 0 && edit.text[e - 1] == '\n') e - 1 else e)
        val lines = edit.text.subSequence(bs, be).toString().split('\n')
        val out = f(lines).joinToString("\n")
        replaceText(bs, be, out, bs, bs + out.length)
    }

    private fun indent() {
        val s = selStart(); val e = selEnd()
        if (s == e) {
            val col = s - lineStartOf(s)
            val n = lang.tabSize - (col % lang.tabSize)
            replaceText(s, s, " ".repeat(n))
            return
        }
        val tab = tabString()
        mapSelectedLines { lines -> lines.map { if (it.isEmpty()) it else tab + it } }
    }

    private fun outdent() {
        mapSelectedLines { lines -> lines.map { l ->
            var n = 0
            while (n < lang.tabSize && n < l.length && l[n] == ' ') n++
            if (n == 0 && l.startsWith("\t")) l.substring(1) else l.substring(n)
        } }
    }

    private fun duplicateLine() {
        val s = selStart(); val e = selEnd()
        if (s == e) {
            val ls = lineStartOf(s); val le = lineEndOf(s)
            val line = edit.text.subSequence(ls, le).toString()
            replaceText(le, le, "\n" + line, s + line.length + 1)
        } else {
            val sel = edit.text.subSequence(s, e).toString()
            replaceText(e, e, sel, e, e + sel.length)
        }
    }

    private fun toggleComment() {
        val lc = lang.lineComment
        if (lc == null) {
            val bc = lang.blockComment ?: return
            val s = selStart(); val e = selEnd()
            val bs = if (s == e) lineStartOf(s) else s
            val be = if (s == e) lineEndOf(s) else e
            val region = edit.text.subSequence(bs, be).toString()
            val trimmed = region.trim()
            val out = if (trimmed.startsWith(bc.first) && trimmed.endsWith(bc.second) && trimmed.length >= bc.first.length + bc.second.length) {
                val inner = trimmed.substring(bc.first.length, trimmed.length - bc.second.length)
                region.substring(0, region.indexOf(trimmed)) + inner.trim() + region.substring(region.indexOf(trimmed) + trimmed.length)
            } else bc.first + " " + region + " " + bc.second
            replaceText(bs, be, out, bs, bs + out.length)
            return
        }
        mapSelectedLines { lines ->
            val nonBlank = lines.filter { it.isNotBlank() }
            val allCommented = nonBlank.isNotEmpty() && nonBlank.all { it.trimStart().startsWith(lc) }
            if (allCommented) lines.map { l ->
                val i = l.indexOf(lc)
                if (i < 0) l else {
                    val after = i + lc.length
                    l.substring(0, i) + l.substring(if (after < l.length && l[after] == ' ') after + 1 else after)
                }
            } else {
                val minIndent = nonBlank.minOfOrNull { l -> l.length - l.trimStart().length } ?: 0
                lines.map { l -> if (l.isBlank()) l else l.substring(0, minIndent) + lc + " " + l.substring(minIndent) }
            }
        }
    }

    // ------------------------------------------------------------------ find and replace
    fun showFind() {
        findBar.visibility = View.VISIBLE
        findDivider.visibility = View.VISIBLE
        val sel = selectionText()
        if (sel.isNotEmpty() && !sel.contains('\n')) findInput.setText(sel)
        findInput.requestFocus()
        findInput.selectAll()
        updateFindStatus()
    }

    fun hideFind() { findBar.visibility = View.GONE; findDivider.visibility = View.GONE; edit.requestFocus() }

    private fun ignoreCase(): Boolean = !caseBox.isChecked

    private fun countMatches(t: String, q: String): Int {
        if (q.isEmpty()) return 0
        var n = 0; var i = t.indexOf(q, 0, ignoreCase())
        while (i >= 0) { n++; i = t.indexOf(q, i + q.length, ignoreCase()) }
        return n
    }

    private fun updateFindStatus(current: Int = -1) {
        val q = findInput.text.toString()
        if (q.isEmpty()) { findStatus.text = ""; return }
        val t = edit.text.toString()
        val total = countMatches(t, q)
        findStatus.text = when {
            total == 0 -> "No matches"
            current >= 0 -> { var ordinal = 0; var i = t.indexOf(q, 0, ignoreCase()); while (i >= 0 && i <= current) { ordinal++; i = t.indexOf(q, i + q.length, ignoreCase()) }; "$ordinal of $total" }
            else -> "$total matches"
        }
    }

    private fun findNext(forward: Boolean) {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val t = edit.text.toString()
        var idx: Int
        if (forward) {
            idx = t.indexOf(q, selEnd(), ignoreCase())
            if (idx < 0) idx = t.indexOf(q, 0, ignoreCase())
        } else {
            val from = selStart() - 1
            idx = if (from >= 0) t.lastIndexOf(q, from, ignoreCase()) else -1
            if (idx < 0) idx = t.lastIndexOf(q, t.length, ignoreCase())
        }
        if (idx < 0) { findStatus.text = "No matches"; return }
        edit.setSelection(idx, idx + q.length)
        edit.bringPointIntoView(idx)
        updateFindStatus(idx)
    }

    private fun replaceOne() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val sel = selectionText()
        if (sel.equals(q, ignoreCase())) {
            val r = replaceInput.text.toString()
            val s = selStart()
            replaceText(s, selEnd(), r)
        }
        findNext(true)
    }

    private fun replaceAll() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val r = replaceInput.text.toString()
        val t = edit.text.toString()
        val out = StringBuilder(t.length)
        var n = 0; var from = 0
        var i = t.indexOf(q, 0, ignoreCase())
        while (i >= 0) { out.append(t, from, i).append(r); from = i + q.length; n++; i = t.indexOf(q, from, ignoreCase()) }
        if (n == 0) { findStatus.text = "No matches"; return }
        out.append(t, from, t.length)
        val sel = selStart()
        replaceText(0, t.length, out.toString(), sel.coerceAtMost(out.length))
        findStatus.text = "Replaced $n"
    }

    // ------------------------------------------------------------------ syntax colors
    private fun scheduleHighlight(delay: Long) {
        Api.main.removeCallbacks(highlightTask)
        Api.main.postDelayed(highlightTask, delay)
    }

    private fun runHighlight() {
        if (!isAttachedToWindow) return
        val l = edit.layout
        if (l == null) { scheduleHighlight(100); return }
        val t = edit.text
        if (lang.id == "plaintext") {
            for (s in t.getSpans(0, t.length, ForegroundColorSpan::class.java)) t.removeSpan(s)
            hiFirst = 0; hiLast = l.lineCount - 1
            return
        }
        val first = l.getLineForVertical(edit.scrollY)
        val last = l.getLineForVertical(edit.scrollY + edit.height.coerceAtLeast(1))
        val startLine = (first - MARGIN_LINES).coerceAtLeast(0)
        val endLine = (last + MARGIN_LINES).coerceAtMost(l.lineCount - 1)
        val start = l.getLineStart(startLine)
        val end = l.getLineEnd(endLine).coerceAtMost(t.length)
        if (end <= start) return
        val version = textVersion
        val region = t.subSequence(start, end).toString()
        val language = lang
        worker.execute {
            val tokens = ArrayList<Highlighter.Token>()
            try { language.tokenize(region, start, tokens) } catch (e: Exception) { }
            Api.main.post { if (version == textVersion && isAttachedToWindow) applyTokens(tokens, startLine, endLine) }
        }
    }

    private fun applyTokens(tokens: List<Highlighter.Token>, startLine: Int, endLine: Int) {
        val t = edit.text
        for (s in t.getSpans(0, t.length, ForegroundColorSpan::class.java)) t.removeSpan(s)
        val n = t.length
        for (tk in tokens) {
            val a = tk.start; val b = tk.end.coerceAtMost(n)
            if (a in 0 until b) t.setSpan(ForegroundColorSpan(tk.color), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        hiFirst = startLine; hiLast = endLine
    }

    /** After scrolling: re-color when the view has come close to the edge of the colored region. */
    private fun afterScroll() {
        val l = edit.layout ?: return
        if (hiFirst < 0) { scheduleHighlight(0); return }
        val first = l.getLineForVertical(edit.scrollY)
        val last = l.getLineForVertical(edit.scrollY + edit.height)
        val nearTop = first < hiFirst + 40 && hiFirst > 0
        val nearBottom = last > hiLast - 40 && hiLast < l.lineCount - 1
        if (nearTop || nearBottom) scheduleHighlight(0)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleHighlight(0) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); Api.main.removeCallbacks(highlightTask); Api.main.removeCallbacks(scrollTask) }

    // ------------------------------------------------------------------ the two views
    /** The EditText: draws the current line's background, handles the editor keys, reports scrolling. */
    inner class CodeEditText(c: Context) : EditText(c) {
        private val linePaint = Paint().also { it.color = lineHighlight }

        override fun onDraw(canvas: Canvas) {
            val l = layout
            if (l != null) {
                val line = l.getLineForOffset(selectionEnd.coerceIn(0, text.length))
                val top = (l.getLineTop(line) + extendedPaddingTop).toFloat()
                val bottom = (l.getLineBottom(line) + extendedPaddingTop).toFloat()
                canvas.drawRect(scrollX.toFloat(), top, (scrollX + width).toFloat(), bottom, linePaint)
            }
            super.onDraw(canvas)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (handleKey(keyCode, event)) return true
            return super.onKeyDown(keyCode, event)
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            gutter.invalidate()
            invalidate()
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            gutter.invalidate()
            Api.main.removeCallbacks(scrollTask)
            Api.main.postDelayed(scrollTask, 60)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            gutter.invalidate()
            scheduleHighlight(30)
        }
    }

    /** The line numbers: drawn from the EditText's own layout and scroll offset, so they always line up. */
    inner class Gutter(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Typeface.MONOSPACE; it.textAlign = Paint.Align.RIGHT }
        private val fill = Paint().also { it.color = lineHighlight }
        private var digits = 3

        fun onLinesChanged() {
            val lines = edit.layout?.lineCount ?: 1
            val d = lines.toString().length.coerceAtLeast(3)
            if (d != digits) { digits = d; requestLayout() }
            invalidate()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            paint.textSize = edit.textSize
            val w = paddingLeft + paddingRight + (paint.measureText("0") * digits).toInt()
            setMeasuredDimension(w, MeasureSpec.getSize(heightSpec))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val l = edit.layout ?: return
            paint.textSize = edit.textSize
            val padTop = edit.extendedPaddingTop
            val sy = edit.scrollY
            val first = l.getLineForVertical(sy)
            val last = l.getLineForVertical(sy + height)
            val current = l.getLineForOffset(edit.selectionEnd.coerceIn(0, edit.text.length))
            val x = (width - paddingRight).toFloat()
            for (i in first..last) {
                val top = (l.getLineTop(i) + padTop - sy).toFloat()
                val bottom = (l.getLineBottom(i) + padTop - sy).toFloat()
                if (i == current) { canvas.drawRect(0f, top, width.toFloat(), bottom, fill); paint.color = activeNumber } else paint.color = Ui.fg3
                canvas.drawText((i + 1).toString(), x, (l.getLineBaseline(i) + padTop - sy).toFloat(), paint)
            }
        }
    }
}
