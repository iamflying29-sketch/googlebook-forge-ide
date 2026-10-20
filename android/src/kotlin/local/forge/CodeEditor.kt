package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.UpdateLayout
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The code editing view: a monospace EditText that scrolls on its own in both directions (no wrapping), a gutter
 * with line numbers that follows the same layout and scroll offset, a current-line highlight, a find and replace
 * bar, an undo stack of our own, and syntax colors from Highlighter.
 *
 * Staying responsive on large files: the EditText keeps the viewport's height, so Android records and draws only
 * the visible lines; the tokenizer runs on one background thread over the visible lines plus a margin, 120 ms after
 * the last edit, and spans are applied to that region only. Nothing here ever runs a regex over the whole file.
 *
 * Decorations added for the debugger and the language servers: breakpoint dots and diagnostic marks in the gutter
 * (a click on the gutter toggles a breakpoint through Breakpoints), wavy underlines under diagnostics, a highlight
 * of the line the debugger is paused on, and ghost text for inline completions (Tab accepts it). The hooks below
 * let Completions.kt add the popups (completion list, hover, signature help) and the key commands (F12, F2, ...)
 * without this class knowing about them.
 */
class CodeEditor(ctx: Context) : LinearLayout(ctx) {
    companion object {
        val lineHighlight = 0xff161a25.toInt()
        val activeNumber = 0xff9aa3bd.toInt()
        val selection = 0xff2b3550.toInt()
        val executionFill = 0x2effd166
        val breakpointRed = 0xffe5484d.toInt()
        private const val MARGIN_LINES = 150
        private const val MAX_UNDO = 500
        private const val HOVER_DELAY_MS = 450L
        private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "forge-highlight").also { it.isDaemon = true } }

        fun severityColor(severity: Int): Int = when (severity) { 1 -> Ui.red; 2 -> Ui.yellow; 3 -> Ui.blue; else -> Ui.fg3 }
    }

    /** The language of the file, set by setContent(); changing it re-colors the visible region. */
    var lang: Highlighter.Lang = Highlighter.plain
        set(v) { field = v; scheduleHighlight(0) }

    /** The file shown, set by setContent(); the gutter asks Breakpoints about it. */
    var path: String = ""

    /** Called on the main thread after every change the user makes (typing, undo, replace), never after setContent(). */
    var onChanged: (() -> Unit)? = null

    /** Called after every text change, including programmatic ones (replaceContent, edits from a server). */
    var onTextChanged: (() -> Unit)? = null

    /** Runs before the editor's own key handling; return true to consume the key. */
    var keyHook: ((Int, KeyEvent) -> Boolean)? = null

    /** The mouse pointer rested for a moment over a text offset; x and y are inside the text view. */
    var hoverHook: ((Int, Int, Int) -> Unit)? = null
    var hoverEndHook: (() -> Unit)? = null

    /** A Ctrl click on a text offset (go to definition). */
    var ctrlClickHook: ((Int) -> Unit)? = null

    /** The cursor moved (also by typing); the new offset. */
    var cursorHook: ((Int) -> Unit)? = null

    /** The text scrolled; popups anchored to the text should go. */
    var scrollHook: (() -> Unit)? = null

    /** A mouse right-click on the text, at a point inside the text view; the editor's menu is built by EditorSmarts. */
    var contextMenuHook: ((Int, Int) -> Unit)? = null

    /** The text view gained the keyboard focus (the split editor tracks the focused group with it). */
    var focusHook: (() -> Unit)? = null

    /** Every change as (start, removed length, added text), for the twin editor of a split view; mirrored changes are not reported. */
    var changeHook: ((Int, Int, String) -> Unit)? = null
    private var mirroring = false

    /** A change under review (the inline diff of the agent's edits or of Ctrl K): lines b0 until b1 of the text are new
     *  and [removed] are the lines they replaced, shown in red above them. */
    class ReviewHunk(val b0: Int, val b1: Int, val removed: List<String>)
    /** A hunk's Keep (true) or Undo (false) button was clicked. */
    var onReviewHunk: ((Int, Boolean) -> Unit)? = null
    /** Keep all (true) or Undo all (false) on the review bar. */
    var onReviewAll: ((Boolean) -> Unit)? = null
    private var review: List<ReviewHunk> = emptyList()
    private var reviewIndex = -1
    private val zoneSpans = ArrayList<ZoneSpan>()
    /** Lines with the removed text of a hunk drawn below them: line to the height of that zone in pixels. */
    private val zoneBelow = HashMap<Int, Int>()
    private var zoneAboveFirst = 0
    private val chipRects = ArrayList<Triple<RectF, Int, Boolean>>()

    /** Changes against the last commit (git), by 0-based line: 1 added, 2 modified, 3 lines deleted above this line. */
    private var gitMarks: Map<Int, Int> = emptyMap()

    // Bracket matching, highlights of the word under the cursor and of find matches (offsets into the text).
    private var bracketA = -1
    private var bracketB = -1
    private val occurrences = ArrayList<Int>()
    private var occurrenceLen = 0
    private val findMatches = ArrayList<IntRange>()

    // The gutter and the minimap are declared before the EditText on purpose: the EditText's constructor already
    // reports a selection change, and that callback repaints them.
    private val gutter = Gutter(ctx)
    private val minimap = Minimap(ctx)
    val edit = CodeEditText(ctx)

    private val findBar: LinearLayout
    private val findInput: EditText
    private val replaceInput: EditText
    private val findStatus: TextView
    private var findCase = false
    private var findWord = false
    private var findRegex = false
    private val findToggles = ArrayList<Pair<TextView, () -> Boolean>>()
    private val findDivider: View
    private val reviewBar: LinearLayout
    private val reviewTitle: TextView
    private val reviewStats: TextView
    private val occurrenceTask = Runnable { findOccurrences() }

    /** One recorded change: [removed] at [start] was replaced by [added]. */
    class Edit(var start: Int, var removed: String, var added: String, var time: Long)
    /** The undo and redo stacks. Two editors that show the same file (the split editor) share one. */
    class History { val undo = ArrayDeque<Edit>(); val redo = ArrayDeque<Edit>(); fun clear() { undo.clear(); redo.clear() } }
    var history = History()
    private val undoStack: ArrayDeque<Edit> get() = history.undo
    private val redoStack: ArrayDeque<Edit> get() = history.redo
    private var muteHistory = false
    private var pendingRemoved = ""
    private var textVersion = 0
    private var hiFirst = -1
    private var hiLast = -1
    private val highlightTask = Runnable { runHighlight() }
    private val scrollTask = Runnable { afterScroll() }

    private var diagnostics: List<Lsp.Diagnostic> = emptyList()
    private var executionLine = -1
    private var ghost: String? = null
    private var ghostOffset = -1

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.bg)
        val pad = Ui.dp(ctx, 8)

        // ---- the find bar (hidden until Ctrl F): match case, whole word and regular expression toggles, icon buttons
        findInput = Ui.input(ctx, "Find", mono = true)
        replaceInput = Ui.input(ctx, "Replace", mono = true)
        findStatus = Ui.text(ctx, "", 11f, Ui.fg3)
        findStatus.isSingleLine = true
        val caseT = toggle("Aa", "Match case (Alt C)") { findCase = !findCase; findCase }
        val wordT = toggle("ab", "Match whole word (Alt W)") { findWord = !findWord; findWord }
        val regexT = toggle(".*", "Use regular expression (Alt R)") { findRegex = !findRegex; findRegex }
        findToggles.add(caseT to { findCase }); findToggles.add(wordT to { findWord }); findToggles.add(regexT to { findRegex })
        val prev = findIcon(R.drawable.ic_chevron_up, "Previous match (Shift Enter)") { findNext(false) }
        val next = findIcon(R.drawable.ic_chevron_down, "Next match (Enter)") { findNext(true) }
        val replaceOne = findIcon(R.drawable.ic_replace, "Replace (Enter in the replace field)") { replaceOne() }
        val replaceAll = findIcon(R.drawable.ic_replace_all, "Replace all (Ctrl Alt Enter)") { replaceAll() }
        val close = findIcon(R.drawable.ic_close, "Close (Escape)") { hideFind() }
        findBar = Ui.row(ctx, findInput, caseT, wordT, regexT, findStatus, prev, next, replaceInput, replaceOne, replaceAll, close)
        findInput.layoutParams = Ui.lp(0, Ui.wrap, 1.2f)
        replaceInput.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = pad }
        findStatus.layoutParams = Ui.lp(Ui.dp(ctx, 86), Ui.wrap)
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
            if (ev.isAltPressed) when (code) {
                KeyEvent.KEYCODE_C -> { caseT.performClick(); return@OnKeyListener true }
                KeyEvent.KEYCODE_W -> { wordT.performClick(); return@OnKeyListener true }
                KeyEvent.KEYCODE_R -> { regexT.performClick(); return@OnKeyListener true }
            }
            when (code) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (ev.isCtrlPressed && ev.isAltPressed) replaceAll() else if (v === replaceInput) replaceOne() else findNext(!ev.isShiftPressed); true
                }
                KeyEvent.KEYCODE_ESCAPE -> { hideFind(); true }
                else -> false
            }
        }

        // ---- the review bar (shown while changes from the agent or from Ctrl K wait to be kept or undone)
        reviewTitle = Ui.text(ctx, "", 12f, Ui.fg, bold = true)
        reviewStats = Ui.text(ctx, "", 12f, Ui.fg3, mono = true)
        reviewStats.setPadding(pad, 0, pad, 0)
        val spark = Ui.iconView(ctx, R.drawable.ic_sparkle, Ui.accent, 15)
        val rvPrev = findIcon(R.drawable.ic_chevron_up, "Previous change (Shift F7)") { stepReview(-1) }
        val rvNext = findIcon(R.drawable.ic_chevron_down, "Next change (F7)") { stepReview(1) }
        val undoAll = Ui.button(ctx, R.drawable.ic_undo, "Undo all", "Undo every change in this file (Ctrl Shift Backspace)") { onReviewAll?.invoke(false) }
        val keepAll = Ui.button(ctx, R.drawable.ic_check, "Keep all", "Keep every change in this file (Ctrl Shift Enter)") { onReviewAll?.invoke(true) }
        keepAll.background = Ui.rounded(Ui.accent, 6)
        for (i in 0 until keepAll.childCount) { val c = keepAll.getChildAt(i); if (c is TextView) c.setTextColor(Ui.onAccent); if (c is android.widget.ImageView) c.setColorFilter(Ui.onAccent) }
        reviewBar = Ui.row(ctx, spark, reviewTitle, reviewStats, View(ctx), rvPrev, rvNext, undoAll, keepAll)
        (spark.layoutParams as LinearLayout.LayoutParams).rightMargin = pad
        reviewBar.getChildAt(3).layoutParams = Ui.lp(0, 1, 1f)
        (undoAll.layoutParams as LinearLayout.LayoutParams).leftMargin = pad
        (keepAll.layoutParams as LinearLayout.LayoutParams).leftMargin = pad / 2
        reviewBar.setPadding(pad + pad / 2, pad / 2, pad, pad / 2)
        reviewBar.background = Ui.rounded(0xff1d1a17.toInt(), 0)
        reviewBar.visibility = View.GONE
        addView(reviewBar, Ui.lp(Ui.match, Ui.wrap))
        findInput.setOnKeyListener(keyListener)
        replaceInput.setOnKeyListener(keyListener)
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateFindStatus(); refreshFindMatches() }
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
                val added = s.subSequence(start, start + count).toString()
                if (!muteHistory) record(Edit(start, pendingRemoved, added, System.currentTimeMillis()))
                if (!mirroring) changeHook?.invoke(start, before, added)
            }
            override fun afterTextChanged(s: Editable) {
                textVersion++
                if (ghost != null) { ghost = null; ghostOffset = -1 }
                gutter.onLinesChanged()
                minimap.textChanged()
                scheduleHighlight(120)
                Api.main.removeCallbacks(occurrenceTask); Api.main.postDelayed(occurrenceTask, 250)
                if (!muteHistory) onChanged?.invoke()
                onTextChanged?.invoke()
            }
        })
        gutter.setPadding(pad, pad, Ui.dp(ctx, 6), pad)
        gutter.setBackgroundColor(Ui.bg)
        val row = LinearLayout(ctx)
        row.orientation = HORIZONTAL
        row.addView(gutter, Ui.lp(Ui.wrap, Ui.match))
        row.addView(edit, Ui.lp(0, Ui.match, 1f))
        row.addView(minimap, Ui.lp(Ui.dp(ctx, 72), Ui.match))
        addView(row, Ui.lp(Ui.match, 0, 1f))
    }

    private fun findIcon(icon: Int, tip: String, onClick: () -> Unit): ImageButton {
        val b = Ui.miniButton(context, icon, tip) { onClick() }
        b.layoutParams = Ui.lp(Ui.dp(context, 26), Ui.dp(context, 26)).also { it.leftMargin = Ui.dp(context, 2) }
        return b
    }

    /** A small two-state text button of the find bar (Aa, ab, .*); [flip] changes the option and returns its new state. */
    private fun toggle(label: String, tip: String, flip: () -> Boolean): TextView {
        val t = Ui.text(context, label, 11.5f, Ui.fg3, mono = true, bold = true)
        t.gravity = Gravity.CENTER
        t.tooltipText = tip
        t.contentDescription = tip
        t.setPadding(Ui.dp(context, 5), Ui.dp(context, 3), Ui.dp(context, 5), Ui.dp(context, 3))
        t.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(context, 3) }
        t.setOnClickListener { val on = flip(); styleToggle(t, on); updateFindStatus(); refreshFindMatches() }
        styleToggle(t, false)
        return t
    }

    private fun styleToggle(t: TextView, on: Boolean) {
        t.setTextColor(if (on) Ui.accent else Ui.fg3)
        t.background = if (on) Ui.rounded(Ui.accentSoft, 4, Ui.accent) else Ui.rounded(0, 4)
    }

    // ------------------------------------------------------------------ content
    /** Loads a file's text. The language comes from the path. Undo history and the dirty state start fresh. */
    fun setContent(text: String, path: String) {
        this.path = path
        diagnostics = emptyList()
        dropZones()
        executionLine = -1
        ghost = null; ghostOffset = -1
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
        dropZones()
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
    fun goToLine(n: Int) { goTo(n - 1, 0) }

    /** Puts the cursor at a 0-based line and column and scrolls the line into the upper third of the view. */
    fun goTo(line0: Int, col0: Int) {
        val l = edit.layout
        if (l == null) { post { goTo(line0, col0) }; return }
        val line = line0.coerceIn(0, l.lineCount - 1)
        val off = (l.getLineStart(line) + col0.coerceAtLeast(0)).coerceAtMost(l.getLineEnd(line))
        edit.setSelection(off)
        val y = (l.getLineTop(line) - edit.height / 3).coerceIn(0, maxScrollY())
        edit.scrollTo(0, y)
        edit.requestFocus()
    }

    /** Selects a 0-based range and scrolls to it. */
    fun selectRange(line: Int, col: Int, endLine: Int, endCol: Int) {
        val l = edit.layout
        if (l == null) { post { selectRange(line, col, endLine, endCol) }; return }
        val a = offsetOf(line, col); val b = offsetOf(endLine, endCol)
        val y = (l.getLineTop(l.getLineForOffset(a)) - edit.height / 3).coerceIn(0, maxScrollY())
        edit.scrollTo(0, y)
        edit.setSelection(a, b.coerceAtLeast(a))
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

    // ------------------------------------------------------------------ positions (0-based lines and columns)
    fun cursorOffset(): Int = edit.selectionEnd.coerceIn(0, edit.text.length)

    /** The cursor's line and column, 0-based, computed from the text so it is right even before a layout exists. */
    fun lineColOf(offset: Int): Pair<Int, Int> {
        val t = edit.text
        val off = offset.coerceIn(0, t.length)
        val ls = lineStartOf(off)
        var line = 0
        var i = 0
        while (true) { val nl = TextUtils.indexOf(t, '\n', i); if (nl < 0 || nl >= off) break; line++; i = nl + 1 }
        return line to (off - ls)
    }

    fun cursorLineCol(): Pair<Int, Int> = lineColOf(cursorOffset())

    fun offsetOf(line: Int, col: Int): Int {
        val t = edit.text
        var i = 0; var n = 0
        while (n < line) { val nl = TextUtils.indexOf(t, '\n', i); if (nl < 0) return t.length; i = nl + 1; n++ }
        return (i + col.coerceAtLeast(0)).coerceAtMost(lineEndOf(i))
    }

    /** The text offset under a point inside the text view (view coordinates, as a MotionEvent reports them). */
    fun offsetAt(x: Int, y: Int): Int {
        val l = edit.layout ?: return cursorOffset()
        val line = l.getLineForVertical((y + edit.scrollY - edit.extendedPaddingTop).coerceAtLeast(0))
        val off = l.getOffsetForHorizontal(line, (x + edit.scrollX - edit.compoundPaddingLeft).toFloat())
        return off.coerceIn(0, edit.text.length)
    }

    /** The word (identifier characters) around an offset, as start until end. */
    fun wordRangeAt(offset: Int): IntRange {
        val t = edit.text
        var a = offset.coerceIn(0, t.length); var b = a
        while (a > 0 && isWordChar(t[a - 1])) a--
        while (b < t.length && isWordChar(t[b])) b++
        return a until b
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** The rectangle of the cursor inside the text view (view coordinates), for anchoring popups. */
    fun cursorRect(): Rect {
        val l = edit.layout ?: return Rect(0, 0, 0, edit.lineHeight)
        val off = cursorOffset()
        val line = l.getLineForOffset(off)
        val x = (l.getPrimaryHorizontal(off) + edit.compoundPaddingLeft - edit.scrollX).toInt()
        val top = l.getLineTop(line) + edit.extendedPaddingTop - edit.scrollY
        val bottom = l.getLineBottom(line) + edit.extendedPaddingTop - edit.scrollY
        return Rect(x, top, x + 1, bottom)
    }

    fun lineCount(): Int = edit.layout?.lineCount ?: (edit.text.count { it == '\n' } + 1)

    // ------------------------------------------------------------------ decorations
    /** The diagnostics of this file: wavy underlines in the text and marks in the gutter. */
    fun setDiagnostics(list: List<Lsp.Diagnostic>) {
        diagnostics = list
        edit.invalidate(); gutter.invalidate(); minimap.invalidate()
    }

    fun diagnosticsAt(offset: Int): List<Lsp.Diagnostic> {
        if (diagnostics.isEmpty()) return emptyList()
        val (line, col) = lineColOf(offset)
        return diagnostics.filter { d ->
            val afterStart = line > d.line || (line == d.line && col >= d.col)
            val beforeEnd = line < d.endLine || (line == d.endLine && (col < d.endCol || (d.endLine == d.line && d.endCol <= d.col && col == d.col)))
            afterStart && beforeEnd
        }
    }

    fun diagnosticsOnLine(line0: Int): List<Lsp.Diagnostic> = diagnostics.filter { line0 >= it.line && line0 <= it.endLine }

    /** Highlights the 1-based line the debugger is paused on and scrolls to it; null clears the highlight. */
    fun showExecutionLine(line: Int?) {
        executionLine = if (line == null || line < 1) -1 else line - 1
        if (executionLine >= 0) {
            val l = edit.layout
            if (l != null) {
                val ln = executionLine.coerceAtMost(l.lineCount - 1)
                val top = l.getLineTop(ln); val bottom = l.getLineBottom(ln)
                val visibleTop = edit.scrollY; val visibleBottom = edit.scrollY + edit.height
                if (top < visibleTop || bottom > visibleBottom) edit.scrollTo(edit.scrollX, (top - edit.height / 3).coerceIn(0, maxScrollY()))
            }
        }
        edit.invalidate(); gutter.invalidate()
    }

    fun refreshGutter() { gutter.invalidate() }

    /** The git change bars of the gutter (see gitMarks). */
    fun setGitMarks(marks: Map<Int, Int>) { gitMarks = marks; gutter.invalidate(); minimap.invalidate() }

    /** The code's size in sp (Settings, Editor font size); review zones are measured again for the new line height. */
    fun setFontSize(sp: Float) {
        if (sp < 6f || sp > 40f) return
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        if (Math.abs(edit.textSize - px) < 0.5f) return
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gutter.requestLayout(); gutter.invalidate(); minimap.invalidate()
        if (review.isNotEmpty()) post { applyZones(); edit.invalidate() }
    }

    /** Shows or hides the minimap (Settings, Editor: minimap). */
    fun setMinimapVisible(on: Boolean) { minimap.visibility = if (on) View.VISIBLE else View.GONE }

    // ------------------------------------------------------------------ the split editor's twin
    /** Applies a change made in the twin editor of a split view: no undo record, no change hook, no dirty mark. */
    fun mirrorEdit(start: Int, removedLen: Int, added: String) {
        val t = edit.text
        val a = start.coerceIn(0, t.length); val b = (start + removedLen).coerceIn(a, t.length)
        mirroring = true; muteHistory = true
        try { t.replace(a, b, added) } finally { muteHistory = false; mirroring = false }
    }

    // ------------------------------------------------------------------ the inline review of changes
    /**
     * Shows hunks as an inline diff: new lines on a green background, the lines they replaced in red above them
     * (the space comes from a line height span on the line before, so no text is inserted), Keep and Undo buttons
     * on each hunk and the review bar on top. An empty list ends the review.
     */
    fun setReview(hunks: List<ReviewHunk>, label: String) {
        review = hunks
        if (hunks.isEmpty()) reviewIndex = -1 else if (reviewIndex >= hunks.size) reviewIndex = hunks.size - 1
        applyZones()
        if (hunks.isEmpty()) { reviewBar.visibility = View.GONE; if (!edit.isCursorVisible) edit.isCursorVisible = true }
        else {
            val added = hunks.sumOf { it.b1 - it.b0 }; val removed = hunks.sumOf { it.removed.size }
            reviewTitle.text = hunks.size.toString() + (if (hunks.size == 1) " change " else " changes ") + label
            reviewStats.text = "+$added −$removed"
            reviewBar.visibility = View.VISIBLE
        }
        edit.updateCursorMode()
        edit.invalidate(); gutter.invalidate(); minimap.invalidate()
    }

    fun clearReview() = setReview(emptyList(), "")
    fun reviewHunks(): List<ReviewHunk> = review
    fun hasReview(): Boolean = review.isNotEmpty()

    /** Moves the cursor to the next (1) or previous (-1) hunk and scrolls it into view. */
    fun stepReview(dir: Int) {
        if (review.isEmpty()) return
        reviewIndex = ((if (reviewIndex < 0 && dir < 0) 0 else reviewIndex) + dir + review.size) % review.size
        val h = review[reviewIndex]
        goTo(h.b0.coerceAtMost(lineCount() - 1), 0)
    }

    /** The line pitch of the text (one line's height including the extra spacing). */
    private fun linePitch(): Int {
        val fm = edit.paint.fontMetricsInt
        return fm.descent - fm.ascent + edit.lineSpacingExtra.toInt()
    }

    /** Removes the zone spans; the text keeps no trace of them. */
    private fun dropZones() {
        val t = edit.text
        for (z in zoneSpans) t.removeSpan(z)
        zoneSpans.clear(); zoneBelow.clear(); zoneAboveFirst = 0
    }

    private fun applyZones() {
        dropZones()
        val t = edit.text
        val pitch = linePitch()
        for (h in review) {
            if (h.removed.isEmpty()) continue
            val px = h.removed.size * pitch
            if (h.b0 <= 0) {
                val e = lineEndOf(0)
                val end = if (e < t.length) e + 1 else e
                if (end <= 0) continue
                val z = ZoneSpan(px, true); t.setSpan(z, 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); zoneSpans.add(z)
                zoneAboveFirst += px
            } else {
                var line = h.b0 - 1
                var a = offsetOf(line, 0)
                var e = lineEndOf(a)
                // An empty last line has no character to carry the span; the line above it carries the zone then.
                if (e >= t.length && a >= e && line > 0) { line--; a = offsetOf(line, 0); e = lineEndOf(a) }
                val end = if (e < t.length) e + 1 else e
                if (end <= a) continue
                val z = ZoneSpan(px, false); t.setSpan(z, a, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); zoneSpans.add(z)
                zoneBelow[line] = (zoneBelow[line] ?: 0) + px
            }
        }
    }

    /** Extra height above or below one line, where the removed lines of a hunk are drawn. */
    inner class ZoneSpan(val px: Int, val above: Boolean) : LineHeightSpan, UpdateLayout {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val base = edit.paint.fontMetricsInt
            if (above) { fm.ascent = base.ascent - px; fm.top = minOf(base.top, base.ascent) - px }
            else { fm.descent = base.descent + px; fm.bottom = maxOf(base.bottom, base.descent) + px }
        }
    }

    // ------------------------------------------------------------------ highlights: brackets, occurrences, find matches
    private fun updateBracket() {
        val t = edit.text
        val c = cursorOffset()
        bracketA = -1; bracketB = -1
        if (edit.selectionStart != edit.selectionEnd) return
        val opens = "([{"; val closes = ")]}"
        fun scan(at: Int) {
            val ch = t[at]
            val oi = opens.indexOf(ch); val ci = closes.indexOf(ch)
            if (oi >= 0) {
                var depth = 0; var i = at
                val limit = minOf(t.length, at + 20000)
                while (i < limit) { val x = t[i]; if (x == ch) depth++ else if (x == closes[oi]) { depth--; if (depth == 0) { bracketA = at; bracketB = i; return } }; i++ }
            } else if (ci >= 0) {
                var depth = 0; var i = at
                val limit = maxOf(0, at - 20000)
                while (i >= limit) { val x = t[i]; if (x == ch) depth++ else if (x == opens[ci]) { depth--; if (depth == 0) { bracketA = i; bracketB = at; return } }; i-- }
            }
        }
        if (c < t.length && (t[c] in "()[]{}")) scan(c)
        if (bracketA < 0 && c > 0 && (t[c - 1] in "()[]{}")) scan(c - 1)
    }

    /** The word under the cursor (or the selected text) everywhere in the visible lines, like VS Code. */
    private fun findOccurrences() {
        occurrences.clear(); occurrenceLen = 0
        val l = edit.layout
        val t = edit.text
        if (l == null || t.isEmpty()) { edit.invalidate(); return }
        val a = edit.selectionStart.coerceAtLeast(0); val b = edit.selectionEnd.coerceAtLeast(0)
        val needle: String
        var whole = false
        if (a != b) {
            val sel = t.subSequence(minOf(a, b), maxOf(a, b)).toString()
            if (sel.length < 2 || sel.length > 100 || sel.contains('\n') || sel.isBlank()) { edit.invalidate(); return }
            needle = sel
        } else {
            val r = wordRangeAt(a)
            if (r.isEmpty() || r.last - r.first + 1 < 2) { edit.invalidate(); return }
            needle = t.subSequence(r.first, r.last + 1).toString()
            if (needle.all { it.isDigit() }) { edit.invalidate(); return }
            whole = true
        }
        val first = (l.getLineForVertical(edit.scrollY) - 30).coerceAtLeast(0)
        val last = (l.getLineForVertical(edit.scrollY + edit.height) + 30).coerceAtMost(l.lineCount - 1)
        val from = l.getLineStart(first); val to = l.getLineEnd(last).coerceAtMost(t.length)
        val region = t.subSequence(from, to).toString()
        var i = region.indexOf(needle)
        while (i >= 0 && occurrences.size < 300) {
            val abs = from + i
            val okLeft = !whole || abs == 0 || !isWordChar(t[abs - 1])
            val okRight = !whole || abs + needle.length >= t.length || !isWordChar(t[abs + needle.length])
            if (okLeft && okRight) occurrences.add(abs)
            i = region.indexOf(needle, i + needle.length)
        }
        if (occurrences.size < 2) occurrences.clear()
        occurrenceLen = needle.length
        edit.invalidate()
    }

    // ------------------------------------------------------------------ ghost text (inline completions)
    /** Shows a completion after the cursor in gray; Tab inserts it, any edit or Escape removes it. */
    fun showGhost(text: String) {
        if (text.isEmpty()) { clearGhost(); return }
        ghost = text
        ghostOffset = cursorOffset()
        edit.invalidate()
    }

    fun clearGhost() { if (ghost != null) { ghost = null; ghostOffset = -1; edit.invalidate() } }
    fun hasGhost(): Boolean = ghost != null

    fun acceptGhost(): Boolean {
        val g = ghost ?: return false
        val at = ghostOffset
        ghost = null; ghostOffset = -1
        if (at != cursorOffset()) { edit.invalidate(); return false }
        replaceText(at, at, g)
        return true
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

    /** Replaces an offset range with text as one recorded edit; the cursor lands after the new text. */
    fun replaceRange(start: Int, end: Int, replacement: String) { replaceText(start, end, replacement) }

    /**
     * Applies text edits with 0-based ranges, last range first so earlier offsets stay valid, and keeps the cursor
     * where it was when possible. Used for completions' extra edits (imports), renames and server edits.
     */
    fun applyEdits(edits: List<Lsp.TextEdit>) {
        if (edits.isEmpty()) return
        val cursor = cursorOffset()
        val sorted = edits.map { Triple(offsetOf(it.line, it.col), offsetOf(it.endLine, it.endCol), it.newText) }
            .sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second })
        var newCursor = cursor
        for ((a, b, text) in sorted) {
            val s = a.coerceIn(0, edit.text.length); val e = b.coerceIn(s, edit.text.length)
            edit.text.replace(s, e, text)
            if (newCursor >= e) newCursor += text.length - (e - s) else if (newCursor > s) newCursor = s + text.length
        }
        edit.setSelection(newCursor.coerceIn(0, edit.text.length))
    }

    // ------------------------------------------------------------------ keys
    private fun handleKey(code: Int, ev: KeyEvent): Boolean {
        if (keyHook?.invoke(code, ev) == true) return true
        val shift = ev.isShiftPressed
        if (review.isNotEmpty()) {
            if (ev.isCtrlPressed && shift && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) { onReviewAll?.invoke(true); return true }
            if (ev.isCtrlPressed && shift && code == KeyEvent.KEYCODE_DEL) { onReviewAll?.invoke(false); return true }
            if (code == KeyEvent.KEYCODE_F7 && !ev.isCtrlPressed) { stepReview(if (shift) -1 else 1); return true }
        }
        if (ev.isCtrlPressed && !ev.isAltPressed) {
            when (code) {
                KeyEvent.KEYCODE_Z -> { if (shift) redo() else undo(); return true }
                KeyEvent.KEYCODE_Y -> { redo(); return true }
                KeyEvent.KEYCODE_D -> { duplicateLine(); return true }
                KeyEvent.KEYCODE_SLASH -> { toggleComment(); return true }
                KeyEvent.KEYCODE_F -> { showFind(); return true }
                KeyEvent.KEYCODE_H -> { showReplace(); return true }
                KeyEvent.KEYCODE_G -> { askGoToLine(); return true }
                KeyEvent.KEYCODE_K -> if (shift) { deleteLines(); return true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { insertLine(below = !shift); return true }
                KeyEvent.KEYCODE_RIGHT_BRACKET -> { indent(force = true); return true }
                KeyEvent.KEYCODE_LEFT_BRACKET -> { outdent(); return true }
            }
            return false
        }
        if (ev.isAltPressed) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (shift) copyLines(up = true) else moveLines(up = true); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (shift) copyLines(up = false) else moveLines(up = false); return true }
            }
            return false
        }
        if (ev.isMetaPressed) return false
        if (ev.unicodeChar != 0 && autoPair(ev.unicodeChar.toChar())) return true
        when (code) {
            KeyEvent.KEYCODE_MOVE_HOME -> { smartHome(shift); return true }
            KeyEvent.KEYCODE_DEL -> if (deletePair()) return true
            KeyEvent.KEYCODE_TAB -> {
                if (!ev.isShiftPressed && ghost != null) { if (acceptGhost()) return true }
                if (ev.isShiftPressed) outdent() else indent(); return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (ev.isShiftPressed) return false; newlineWithIndent(); return true }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (ghost != null) { clearGhost(); return true }
                if (findBar.visibility == View.VISIBLE) { hideFind(); return true }
            }
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
        val next = if (e < t.length) t[e] else ' '
        // Enter between a bracket pair opens a block: the closing bracket moves to its own line under the opening one.
        if (extra.isNotEmpty() && ((prev == '{' && next == '}') || (prev == '(' && next == ')') || (prev == '[' && next == ']'))) {
            val inner = "\n" + indent + extra
            replaceText(s, e, inner + "\n" + indent, s + inner.length)
            return
        }
        replaceText(s, e, "\n" + indent + extra)
    }

    // ------------------------------------------------------------------ brackets, quotes and line commands
    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')

    /** Auto-closing brackets and quotes, typing over a closer, and wrapping a selection in a pair. */
    private fun autoPair(ch: Char): Boolean {
        val t = edit.text
        val s = selStart(); val e = selEnd()
        val next = if (e < t.length) t[e] else '\u0000'
        val prev = if (s > 0) t[s - 1] else '\u0000'
        if (s == e && next == ch && (ch == ')' || ch == ']' || ch == '}' || ch == '"' || ch == '\'' || ch == '`')) { edit.setSelection(s + 1); return true }
        val close = pairs[ch] ?: return false
        if (lang.id == "plaintext" || (lang.id == "markdown" && ch != '(' && ch != '[' && ch != '`')) return false
        if (s != e) {
            val inner = t.subSequence(s, e).toString()
            replaceText(s, e, ch + inner + close, s + 1, s + 1 + inner.length)
            return true
        }
        if (ch == close) {
            if (isWordChar(prev) || isWordChar(next) || prev == ch) return false
            var n = 0
            var k = lineStartOf(s)
            while (k < s) { if (t[k] == ch && (k == 0 || t[k - 1] != '\\')) n++; k++ }
            if (n % 2 == 1) return false
        } else if (next != '\u0000' && !next.isWhitespace() && next !in ")]};,:") return false
        replaceText(s, s, "" + ch + close, s + 1, s + 1)
        return true
    }

    /** Backspace between an empty pair removes both characters. */
    private fun deletePair(): Boolean {
        val t = edit.text
        val s = selStart()
        if (s != selEnd() || s <= 0 || s >= t.length) return false
        val open = t[s - 1]
        if (pairs[open] != t[s]) return false
        replaceText(s - 1, s + 1, "")
        return true
    }

    /** Home goes to the first character of the line that is not a space, and from there to the very start. */
    private fun smartHome(extend: Boolean) {
        val t = edit.text
        val c = edit.selectionEnd.coerceAtLeast(0)
        val ls = lineStartOf(c)
        var first = ls
        while (first < t.length && (t[first] == ' ' || t[first] == '\t')) first++
        val target = if (c == first) ls else first
        if (extend) edit.setSelection(edit.selectionStart.coerceAtLeast(0), target) else edit.setSelection(target)
    }

    /** The full lines the selection touches, as start (inclusive) and end (the newline after the last, or the end). */
    private fun selectedLineBounds(): Pair<Int, Int> {
        val s = selStart(); var e = selEnd()
        if (e > s && edit.text[e - 1] == '\n') e--
        return lineStartOf(s) to lineEndOf(e)
    }

    private fun moveLines(up: Boolean) {
        val t = edit.text
        val (bs, be) = selectedLineBounds()
        val block = t.subSequence(bs, be).toString()
        val ss = selStart() - bs; val se = selEnd() - bs
        if (up) {
            if (bs == 0) return
            val ps = lineStartOf(bs - 1)
            val prevLine = t.subSequence(ps, bs - 1).toString()
            replaceText(ps, be, block + "\n" + prevLine, ps + ss, ps + se)
        } else {
            if (be >= t.length) return
            val ne = lineEndOf(be + 1)
            val nextLine = t.subSequence(be + 1, ne).toString()
            val shift = nextLine.length + 1
            replaceText(bs, ne, nextLine + "\n" + block, bs + shift + ss, bs + shift + se)
        }
    }

    private fun copyLines(up: Boolean) {
        val (bs, be) = selectedLineBounds()
        val block = edit.text.subSequence(bs, be).toString()
        val ss = selStart() - bs; val se = selEnd() - bs
        if (up) replaceText(bs, bs, block + "\n", bs + ss, bs + se)
        else replaceText(be, be, "\n" + block, be + 1 + ss, be + 1 + se)
    }

    private fun deleteLines() {
        val t = edit.text
        val (bs, be) = selectedLineBounds()
        if (be < t.length) replaceText(bs, be + 1, "", bs, bs)
        else if (bs > 0) replaceText(bs - 1, be, "", lineStartOf(bs - 1), lineStartOf(bs - 1))
        else replaceText(0, be, "", 0, 0)
    }

    /** Ctrl Enter opens a line below, Ctrl Shift Enter one above, with the current line's indentation. */
    private fun insertLine(below: Boolean) {
        val t = edit.text
        val c = selEnd()
        val ls = lineStartOf(c)
        var i = ls
        while (i < t.length && (t[i] == ' ' || t[i] == '\t')) i++
        val indent = t.subSequence(ls, i).toString()
        if (below) { val le = lineEndOf(c); replaceText(le, le, "\n" + indent) }
        else replaceText(ls, ls, indent + "\n", ls + indent.length, ls + indent.length)
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

    private fun indent(force: Boolean = false) {
        val s = selStart(); val e = selEnd()
        if (s == e && !force) {
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
        refreshFindMatches()
    }

    /** Opens the find bar with the replace field focused (Ctrl H). */
    fun showReplace() { showFind(); replaceInput.requestFocus() }

    fun hideFind() { findBar.visibility = View.GONE; findDivider.visibility = View.GONE; findMatches.clear(); edit.invalidate(); edit.requestFocus() }

    /** The find pattern from the query and the three toggles, or null when the query is empty or not a valid expression. */
    private fun findPattern(): Pattern? {
        val q = findInput.text.toString()
        if (q.isEmpty()) return null
        var body = if (findRegex) q else Pattern.quote(q)
        if (findWord) body = "(?<![A-Za-z0-9_])(?:$body)(?![A-Za-z0-9_])"
        return try { Pattern.compile(body, if (findCase) Pattern.MULTILINE else Pattern.MULTILINE or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }
        catch (e: PatternSyntaxException) { null }
    }

    private fun updateFindStatus(current: Int = -1) {
        val q = findInput.text.toString()
        if (q.isEmpty()) { findStatus.text = ""; return }
        val p = findPattern()
        if (p == null) { findStatus.text = "Bad pattern"; findStatus.setTextColor(Ui.red); return }
        findStatus.setTextColor(Ui.fg3)
        val m = p.matcher(edit.text)
        var total = 0; var ordinal = 0
        while (m.find() && total < 10000) {
            if (m.end() == m.start()) continue
            total++
            if (current >= 0 && m.start() <= current) ordinal = total
        }
        findStatus.text = when {
            total == 0 -> "No results"
            current >= 0 -> "$ordinal of $total"
            else -> "$total results"
        }
    }

    /** The find matches in and around the visible lines, drawn in the text while the find bar is open. */
    private fun refreshFindMatches() {
        findMatches.clear()
        val l = edit.layout
        val p = if (findBar.visibility == View.VISIBLE) findPattern() else null
        if (l == null || p == null) { edit.invalidate(); return }
        val first = (l.getLineForVertical(edit.scrollY) - 30).coerceAtLeast(0)
        val last = (l.getLineForVertical(edit.scrollY + edit.height) + 30).coerceAtMost(l.lineCount - 1)
        val from = l.getLineStart(first); val to = l.getLineEnd(last).coerceAtMost(edit.text.length)
        val m = p.matcher(edit.text).region(from, to)
        while (m.find() && findMatches.size < 500) { if (m.end() > m.start()) findMatches.add(m.start() until m.end()) else if (m.end() >= to) break }
        edit.invalidate()
    }

    private fun findNext(forward: Boolean) {
        val p = findPattern() ?: return
        val t = edit.text
        val m = p.matcher(t)
        var start = -1; var end = -1
        if (forward) {
            if (m.find(selEnd().coerceAtMost(t.length)) && m.end() > m.start()) { start = m.start(); end = m.end() }
            else if (m.find(0) && m.end() > m.start()) { start = m.start(); end = m.end() }
        } else {
            val limit = selStart()
            var lastS = -1; var lastE = -1; var wrapS = -1; var wrapE = -1
            m.reset()
            while (m.find()) { if (m.end() == m.start()) continue; if (m.start() < limit) { lastS = m.start(); lastE = m.end() } else { wrapS = m.start(); wrapE = m.end() } }
            if (lastS >= 0) { start = lastS; end = lastE } else if (wrapS >= 0) { start = wrapS; end = wrapE }
        }
        if (start < 0) { findStatus.text = "No results"; return }
        edit.setSelection(start, end)
        edit.bringPointIntoView(start)
        updateFindStatus(start)
        refreshFindMatches()
    }

    /** The replacement text for one match: with regular expressions on, $1, ${name} and backslash escapes are expanded. */
    private fun replacementFor(m: Matcher): String {
        val r = replaceInput.text.toString()
        if (!findRegex) return r
        val sb = StringBuilder()
        var i = 0
        while (i < r.length) {
            val c = r[i]
            if (c == '\\' && i + 1 < r.length) {
                when (val n = r[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(n) }
                i += 2; continue
            }
            if (c == '$' && i + 1 < r.length) {
                if (r[i + 1] == '{') {
                    val end = r.indexOf('}', i + 2)
                    if (end > 0) { sb.append(try { m.group(r.substring(i + 2, end)) ?: "" } catch (e: Exception) { "" }); i = end + 1; continue }
                }
                if (r[i + 1].isDigit()) {
                    var j = i + 1; var g = 0
                    while (j < r.length && r[j].isDigit() && g * 10 + (r[j] - '0') <= m.groupCount()) { g = g * 10 + (r[j] - '0'); j++ }
                    if (j > i + 1) { sb.append(m.group(g) ?: ""); i = j; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun replaceOne() {
        val p = findPattern() ?: return
        val s0 = selStart(); val e0 = selEnd()
        if (s0 != e0) {
            val m = p.matcher(edit.text).region(s0, e0)
            if (m.matches()) replaceText(s0, e0, replacementFor(m))
        }
        findNext(true)
    }

    private fun replaceAll() {
        val p = findPattern() ?: return
        val t = edit.text.toString()
        val m = p.matcher(t)
        val out = StringBuilder(t.length)
        var n = 0; var from = 0
        while (m.find()) {
            if (m.end() == m.start()) continue
            out.append(t, from, m.start()).append(replacementFor(m))
            from = m.end(); n++
        }
        if (n == 0) { findStatus.text = "No results"; return }
        out.append(t, from, t.length)
        val sel = selStart()
        replaceText(0, t.length, out.toString(), sel.coerceAtMost(out.length))
        findStatus.text = "Replaced $n"
        refreshFindMatches()
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
        if (findBar.visibility == View.VISIBLE) refreshFindMatches()
        val l = edit.layout ?: return
        if (hiFirst < 0) { scheduleHighlight(0); return }
        val first = l.getLineForVertical(edit.scrollY)
        val last = l.getLineForVertical(edit.scrollY + edit.height)
        val nearTop = first < hiFirst + 40 && hiFirst > 0
        val nearBottom = last > hiLast - 40 && hiLast < l.lineCount - 1
        if (nearTop || nearBottom) scheduleHighlight(0)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleHighlight(0) }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Api.main.removeCallbacks(highlightTask); Api.main.removeCallbacks(scrollTask)
        edit.cancelHover()
    }

    // ------------------------------------------------------------------ the two views
    /** The EditText: draws the current line's background, handles the editor keys, reports scrolling. */
    inner class CodeEditText(c: Context) : EditText(c) {
        private val linePaint = Paint().also { it.color = lineHighlight }
        private val execPaint = Paint().also { it.color = executionFill }
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = Ui.dp(c, 1).toFloat().coerceAtLeast(1.5f) }
        private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Typeface.MONOSPACE; it.color = Ui.fg3 }
        private val wave = Path()
        private val addedPaint = Paint().also { it.color = 0x2231c48d }
        private val addedEdge = Paint().also { it.color = 0xff2fb27a.toInt() }
        private val zonePaint = Paint().also { it.color = 0x33ff5c6c }
        private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Typeface.MONOSPACE; it.color = 0xffe6a3ab.toInt() }
        private val occurrencePaint = Paint().also { it.color = 0x24ffffff }
        private val findPaint = Paint().also { it.color = 0x55ff8a3d }
        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = 1.5f; it.color = 0xff8b93a8.toInt() }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Typeface.DEFAULT_BOLD }
        private val cursorPaint = Paint().also { it.color = Ui.accent }
        private var chipDown = -1
        private val hoverTask = Runnable { fireHover() }
        private var hoverX = 0; private var hoverY = 0
        private var lastHoverOffset = -1
        private var ctrlDown = false

        /** The top and bottom of a line's own text, without the review zone that may sit above or below it. */
        fun textTop(l: android.text.Layout, line: Int): Float = (l.getLineTop(line) + extendedPaddingTop + (if (line == 0) zoneAboveFirst else 0)).toFloat()
        fun textBottom(l: android.text.Layout, line: Int): Float = (l.getLineBottom(line) + extendedPaddingTop - (zoneBelow[line] ?: 0)).toFloat()

        /** Lines under a review zone get a cursor drawn here: the native one would be as tall as the line and its zone. */
        fun updateCursorMode() {
            val l = layout ?: return
            val line = l.getLineForOffset(selectionEnd.coerceIn(0, text.length))
            val zoned = review.isNotEmpty() && ((zoneBelow[line] ?: 0) > 0 || (line == 0 && zoneAboveFirst > 0))
            if (isCursorVisible == zoned) isCursorVisible = !zoned
        }

        override fun onDraw(canvas: Canvas) {
            val l = layout
            if (l != null) {
                val line = l.getLineForOffset(selectionEnd.coerceIn(0, text.length))
                val top = textTop(l, line)
                val bottom = textBottom(l, line)
                canvas.drawRect(scrollX.toFloat(), top, (scrollX + width).toFloat(), bottom, linePaint)
                drawReviewBackgrounds(canvas, l)
                drawHighlights(canvas, l)
                if (executionLine in 0 until l.lineCount) {
                    val et = (l.getLineTop(executionLine) + extendedPaddingTop).toFloat()
                    val eb = (l.getLineBottom(executionLine) + extendedPaddingTop).toFloat()
                    canvas.drawRect(scrollX.toFloat(), et, (scrollX + width).toFloat(), eb, execPaint)
                }
            }
            super.onDraw(canvas)
            if (l != null) {
                drawDiagnostics(canvas, l); drawGhost(canvas, l)
                if (review.isNotEmpty()) { drawChips(canvas, l); drawOwnCursor(canvas, l) }
            }
        }

        private fun visibleLines(l: android.text.Layout): IntRange {
            val first = l.getLineForVertical((scrollY - extendedPaddingTop).coerceAtLeast(0))
            val last = l.getLineForVertical(scrollY + height)
            return first..last
        }

        /** Green backgrounds for new lines and the red zones with the lines they replaced. */
        private fun drawReviewBackgrounds(canvas: Canvas, l: android.text.Layout) {
            if (review.isEmpty()) return
            val vis = visibleLines(l)
            val left = scrollX.toFloat(); val right = (scrollX + width).toFloat()
            val pitch = linePitch()
            val base = paint.fontMetricsInt
            zoneText.textSize = textSize
            val edgeW = Ui.dp(context, 2).toFloat()
            for (h in review) {
                for (ln in maxOf(h.b0, vis.first)..minOf(h.b1 - 1, vis.last, l.lineCount - 1)) {
                    canvas.drawRect(left, textTop(l, ln), right, textBottom(l, ln), addedPaint)
                    canvas.drawRect(left, textTop(l, ln), left + edgeW, textBottom(l, ln), addedEdge)
                }
                if (h.removed.isEmpty()) continue
                val zoneTop: Float = if (h.b0 <= 0) (l.getLineTop(0) + extendedPaddingTop).toFloat() else {
                    var line = (h.b0 - 1).coerceAtMost(l.lineCount - 1)
                    if (zoneBelow[line] == null && line > 0 && zoneBelow[line - 1] != null) line--
                    if (line < vis.first - 1 || line > vis.last) continue
                    (l.getLineBaseline(line) + extendedPaddingTop + base.descent).toFloat()
                }
                val zoneH = h.removed.size * pitch
                if (zoneTop > scrollY + height || zoneTop + zoneH < scrollY) continue
                canvas.drawRect(left, zoneTop, right, zoneTop + zoneH, zonePaint)
                val x = compoundPaddingLeft.toFloat()
                for ((i, t) in h.removed.withIndex()) {
                    val y = zoneTop + i * pitch - base.ascent
                    canvas.drawText(if (t.length > 400) t.substring(0, 400) else t, x, y, zoneText)
                }
            }
        }

        /** Occurrences of the word under the cursor, find matches and the matching bracket pair. */
        private fun drawHighlights(canvas: Canvas, l: android.text.Layout) {
            val n = text.length
            val padLeft = compoundPaddingLeft.toFloat()
            fun box(a: Int, b: Int, p: Paint) {
                if (a < 0 || b > n || b <= a) return
                val line = l.getLineForOffset(a)
                if (l.getLineForOffset(b) != line) return
                val x1 = l.getPrimaryHorizontal(a) + padLeft; val x2 = l.getPrimaryHorizontal(b) + padLeft
                canvas.drawRect(x1, textTop(l, line), x2, textBottom(l, line), p)
            }
            if (occurrenceLen > 0) for (o in occurrences) box(o, o + occurrenceLen, occurrencePaint)
            for (r in findMatches) box(r.first, r.last + 1, findPaint)
            if (bracketA >= 0 && bracketB >= 0) {
                for (o in intArrayOf(bracketA, bracketB)) {
                    if (o >= n) continue
                    val line = l.getLineForOffset(o)
                    val x1 = l.getPrimaryHorizontal(o) + padLeft; val x2 = x1 + paint.measureText(text, o, o + 1)
                    canvas.drawRect(x1, textTop(l, line) + 1f, x2, textBottom(l, line) - 1f, bracketPaint)
                }
            }
        }

        /** Keep and Undo buttons at the right end of each hunk's last line (or of its red zone). */
        private fun drawChips(canvas: Canvas, l: android.text.Layout) {
            chipRects.clear()
            val dp = context.resources.displayMetrics.density
            chipText.textSize = 11f * context.resources.displayMetrics.scaledDensity
            val pitch = linePitch()
            val base = paint.fontMetricsInt
            for ((i, h) in review.withIndex()) {
                val rowTop: Float = if (h.b1 > h.b0) textTop(l, (h.b1 - 1).coerceAtMost(l.lineCount - 1))
                    else if (h.b0 <= 0) (l.getLineTop(0) + extendedPaddingTop + (h.removed.size - 1) * pitch).toFloat()
                    else (l.getLineBaseline((h.b0 - 1).coerceAtMost(l.lineCount - 1)) + extendedPaddingTop + base.descent + (h.removed.size - 1) * pitch).toFloat()
                if (rowTop > scrollY + height || rowTop + pitch < scrollY) continue
                val chipH = pitch - 2 * dp
                var right = scrollX + width - 10 * dp
                for (keep in listOf(true, false)) {
                    val label = if (keep) "Keep" else "Undo"
                    val w = chipText.measureText(label) + 16 * dp
                    val rect = RectF(right - w, rowTop + dp, right, rowTop + dp + chipH)
                    chipPaint.color = if (keep) 0xff2fb27a.toInt() else 0xff3a3f52.toInt()
                    canvas.drawRoundRect(rect, 4 * dp, 4 * dp, chipPaint)
                    chipText.color = if (keep) 0xff06140d.toInt() else Ui.fg
                    canvas.drawText(label, rect.left + 8 * dp, rect.centerY() - (chipText.descent() + chipText.ascent()) / 2, chipText)
                    chipRects.add(Triple(rect, i, keep))
                    right = rect.left - 6 * dp
                }
            }
        }

        private fun drawOwnCursor(canvas: Canvas, l: android.text.Layout) {
            if (isCursorVisible || !isFocused || selectionStart != selectionEnd) return
            val off = selectionEnd.coerceIn(0, text.length)
            val line = l.getLineForOffset(off)
            val x = l.getPrimaryHorizontal(off) + compoundPaddingLeft
            val top = textTop(l, line)
            val bottom = (l.getLineBaseline(line) + extendedPaddingTop + paint.fontMetricsInt.descent).toFloat()
            canvas.drawRect(x, top, x + Ui.dp(context, 2), bottom, cursorPaint)
        }

        private fun drawDiagnostics(canvas: Canvas, l: android.text.Layout) {
            if (diagnostics.isEmpty()) return
            val firstVisible = l.getLineForVertical(scrollY)
            val lastVisible = l.getLineForVertical(scrollY + height)
            val padLeft = compoundPaddingLeft.toFloat()
            val amp = Ui.dp(context, 1).toFloat().coerceAtLeast(1.5f)
            val step = Ui.dp(context, 2).toFloat().coerceAtLeast(3f)
            val n = text.length
            for (d in diagnostics) {
                if (d.endLine < firstVisible || d.line > lastVisible) continue
                wavePaint.color = severityColor(d.severity)
                for (ln in d.line.coerceAtLeast(firstVisible)..d.endLine.coerceAtMost(lastVisible)) {
                    if (ln >= l.lineCount) break
                    val lineStart = l.getLineStart(ln); val lineEnd = l.getLineEnd(ln)
                    var a = if (ln == d.line) (lineStart + d.col).coerceAtMost(lineEnd) else lineStart
                    var b = if (ln == d.endLine) (lineStart + d.endCol).coerceAtMost(lineEnd) else lineEnd
                    a = a.coerceIn(0, n); b = b.coerceIn(0, n)
                    if (b <= a) b = (a + 1).coerceAtMost(n)
                    var x1 = l.getPrimaryHorizontal(a) + padLeft
                    var x2 = (if (b > a) l.getPrimaryHorizontal(b) else x1 + step * 2) + padLeft
                    if (x2 < x1 + step * 2) x2 = x1 + step * 2
                    val y = (l.getLineBaseline(ln) + extendedPaddingTop + amp * 2).toFloat()
                    wave.reset(); wave.moveTo(x1, y)
                    var x = x1; var up = true
                    while (x < x2) { x += step; wave.lineTo(x, if (up) y - amp else y + amp); up = !up }
                    canvas.drawPath(wave, wavePaint)
                }
            }
        }

        private fun drawGhost(canvas: Canvas, l: android.text.Layout) {
            val g = ghost ?: return
            if (ghostOffset < 0 || ghostOffset > text.length || ghostOffset != selectionEnd) return
            val line = l.getLineForOffset(ghostOffset)
            val x = l.getPrimaryHorizontal(ghostOffset) + compoundPaddingLeft
            val baseline = (l.getLineBaseline(line) + extendedPaddingTop).toFloat()
            ghostPaint.textSize = textSize
            val firstLine = g.substringBefore('\n')
            val more = g.count { it == '\n' }
            ghostPaint.color = Ui.fg3
            canvas.drawText(firstLine, x, baseline, ghostPaint)
            if (more > 0) {
                val firstWidth = ghostPaint.measureText(firstLine)
                val hint = "  ⏎ +$more more line" + (if (more > 1) "s" else "") + ", Tab inserts all"
                ghostPaint.color = Ui.accent2
                ghostPaint.textSize = textSize * 0.85f
                canvas.drawText(hint, x + firstWidth, baseline, ghostPaint)
            }
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            cancelHover()
            if (handleKey(keyCode, event)) return true
            return super.onKeyDown(keyCode, event)
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            if (ghost != null && selEnd != ghostOffset) { ghost = null; ghostOffset = -1 }
            if (layout != null) {
                updateBracket()
                updateCursorMode()
                Api.main.removeCallbacks(occurrenceTask); Api.main.postDelayed(occurrenceTask, 200)
            }
            gutter.invalidate()
            invalidate()
            cursorHook?.invoke(selEnd)
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            gutter.invalidate()
            minimap.invalidate()
            Api.main.removeCallbacks(scrollTask)
            Api.main.postDelayed(scrollTask, 60)
            if (t != oldt) { Api.main.removeCallbacks(occurrenceTask); Api.main.postDelayed(occurrenceTask, 150) }
            if (l != oldl || t != oldt) scrollHook?.invoke()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            gutter.invalidate()
            scheduleHighlight(30)
        }

        private var touching = false

        override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            val a = selectionStart; val b = selectionEnd
            super.onFocusChanged(focused, direction, previouslyFocusedRect)
            // Android moves the cursor to the end of the text when focus arrives before the first layout (a file
            // that was just opened); focus given by the program keeps the cursor where it was. A tap still places it.
            if (focused && !touching && a >= 0 && b >= 0 && (selectionStart != a || selectionEnd != b)) {
                val n = text.length
                setSelection(a.coerceAtMost(n), b.coerceAtMost(n))
            }
            if (focused) focusHook?.invoke() else cancelHover()
        }

        /** A mouse pointer resting over the text for a moment asks for hover information. */
        override fun onHoverEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                    hoverX = event.x.toInt(); hoverY = event.y.toInt()
                    Api.main.removeCallbacks(hoverTask)
                    if (hoverHook != null) Api.main.postDelayed(hoverTask, HOVER_DELAY_MS)
                }
                MotionEvent.ACTION_HOVER_EXIT -> cancelHover()
            }
            return super.onHoverEvent(event)
        }

        private fun fireHover() {
            val off = offsetAt(hoverX, hoverY)
            if (off == lastHoverOffset) return
            lastHoverOffset = off
            hoverHook?.invoke(off, hoverX, hoverY)
        }

        fun cancelHover() {
            Api.main.removeCallbacks(hoverTask)
            if (lastHoverOffset >= 0) { lastHoverOffset = -1; hoverEndHook?.invoke() }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> post { touching = false }
            }
            if (chipRects.isNotEmpty()) {
                val x = event.x + scrollX; val y = event.y + scrollY
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { chipDown = chipRects.indexOfFirst { it.first.contains(x, y) }; if (chipDown >= 0) return true }
                    MotionEvent.ACTION_MOVE -> if (chipDown >= 0) return true
                    MotionEvent.ACTION_UP -> if (chipDown >= 0) {
                        val hit = chipRects.getOrNull(chipDown)
                        chipDown = -1
                        if (hit != null && hit.first.contains(x, y)) onReviewHunk?.invoke(hit.second, hit.third)
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> if (chipDown >= 0) { chipDown = -1; return true }
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN && (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 && contextMenuHook != null) {
                // A right-click inside the selection keeps it (for Copy, Add to chat); elsewhere it moves the cursor there.
                val off = offsetAt(event.x.toInt(), event.y.toInt())
                val a = minOf(selectionStart, selectionEnd); val b = maxOf(selectionStart, selectionEnd)
                if (off < a || off > b || a == b) setSelection(off)
                if (!isFocused) requestFocus()
                contextMenuHook?.invoke(event.x.toInt(), event.y.toInt())
                return true
            }
            val ctrl = (event.metaState and KeyEvent.META_CTRL_ON) != 0
            if (event.actionMasked == MotionEvent.ACTION_DOWN) ctrlDown = ctrl
            val handled = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && ctrlDown && ctrl) {
                ctrlDown = false
                ctrlClickHook?.invoke(offsetAt(event.x.toInt(), event.y.toInt()))
            }
            return handled
        }
    }

    /**
     * The line numbers: drawn from the EditText's own layout and scroll offset, so they always line up. The zone on
     * the left holds the breakpoint dots, the diagnostic marks and the execution arrow; a click toggles a breakpoint.
     */
    inner class Gutter(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Typeface.MONOSPACE; it.textAlign = Paint.Align.RIGHT }
        private val fill = Paint().also { it.color = lineHighlight }
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrow = Path()
        private val markZone = Ui.dp(c, 14)
        private var digits = 3
        private var downY = -1f

        fun onLinesChanged() {
            val lines = edit.layout?.lineCount ?: 1
            val d = lines.toString().length.coerceAtLeast(3)
            if (d != digits) { digits = d; requestLayout() }
            invalidate()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            paint.textSize = edit.textSize
            val w = paddingLeft + paddingRight + markZone + (paint.measureText("0") * digits).toInt()
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
            val breakpoints = if (path.isNotEmpty()) Breakpoints.lines(path) else emptySet()
            val cx = paddingLeft / 2f + markZone / 2f
            val radius = Ui.dp(context, 4).toFloat()
            for (i in first..last) {
                val top = (l.getLineTop(i) + padTop - sy).toFloat()
                val bottom = (l.getLineBottom(i) + padTop - sy).toFloat()
                if (i == executionLine) { mark.color = executionFill; canvas.drawRect(0f, top, width.toFloat(), bottom, mark) }
                if (i == current) { canvas.drawRect(0f, top + (if (i == 0) zoneAboveFirst else 0), width.toFloat(), bottom - (zoneBelow[i] ?: 0), fill); paint.color = activeNumber } else paint.color = Ui.fg3
                val gm = gitMarks[i]
                if (gm != null && review.isEmpty()) {
                    val w3 = Ui.dp(context, 3).toFloat()
                    when (gm) {
                        1 -> { mark.color = 0xff3fae74.toInt(); canvas.drawRect(width - w3, top, width.toFloat(), bottom, mark) }
                        2 -> { mark.color = 0xff4d8fe0.toInt(); canvas.drawRect(width - w3, top, width.toFloat(), bottom, mark) }
                        3 -> {
                            mark.color = 0xffe5484d.toInt()
                            arrow.reset(); arrow.moveTo(width - w3 * 2, top - w3); arrow.lineTo(width.toFloat(), top); arrow.lineTo(width - w3 * 2, top + w3); arrow.close()
                            canvas.drawPath(arrow, mark)
                        }
                    }
                }
                if (review.isNotEmpty()) {
                    val textTop = top + (if (i == 0) zoneAboveFirst else 0); val textBottom = bottom - (zoneBelow[i] ?: 0)
                    if (review.any { i >= it.b0 && i < it.b1 }) { mark.color = 0xff2fb27a.toInt(); canvas.drawRect(width - Ui.dp(context, 3).toFloat(), textTop, width.toFloat(), textBottom, mark) }
                    val zb = zoneBelow[i] ?: 0
                    if (zb > 0) { mark.color = 0x55ff5c6c; canvas.drawRect(0f, textBottom, width.toFloat(), bottom, mark) }
                    if (i == 0 && zoneAboveFirst > 0) { mark.color = 0x55ff5c6c; canvas.drawRect(0f, top, width.toFloat(), top + zoneAboveFirst, mark) }
                }
                canvas.drawText((i + 1).toString(), x, (l.getLineBaseline(i) + padTop - sy).toFloat(), paint)
                val cy = (top + bottom) / 2f
                if ((i + 1) in breakpoints) { mark.color = breakpointRed; canvas.drawCircle(cx, cy, radius, mark) }
                val worst = diagnostics.filter { i >= it.line && i <= it.endLine }.minByOrNull { it.severity }
                if (worst != null) { mark.color = severityColor(worst.severity); canvas.drawRect(0f, top + 2f, Ui.dp(context, 3).toFloat(), bottom - 2f, mark) }
                if (i == executionLine) {
                    mark.color = Ui.yellow
                    arrow.reset()
                    arrow.moveTo(cx - radius, cy - radius); arrow.lineTo(cx + radius + 1f, cy); arrow.lineTo(cx - radius, cy + radius); arrow.close()
                    canvas.drawPath(arrow, mark)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.y; return true }
                MotionEvent.ACTION_UP -> {
                    if (downY >= 0 && Math.abs(event.y - downY) < Ui.dp(context, 8) && path.isNotEmpty()) {
                        val l = edit.layout
                        if (l != null) {
                            val line = l.getLineForVertical((event.y.toInt() + edit.scrollY - edit.extendedPaddingTop).coerceAtLeast(0))
                            if (line < l.lineCount) { Breakpoints.toggle(path, line + 1); invalidate() }
                        }
                    }
                    downY = -1f
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { downY = -1f; return true }
            }
            return super.onTouchEvent(event)
        }
    }

    /**
     * The minimap on the right, like VS Code's and Cursor's: every line as a thin bar (its indentation and length),
     * comment lines dimmer, with the problems, git changes, review hunks, find matches and the cursor marked, and the
     * visible part as a lighter band. A click or a drag scrolls the text there.
     */
    inner class Minimap(c: Context) : View(c) {
        private val bar = Paint()
        private val band = Paint().also { it.color = 0x14ffffff }
        private val bandEdge = Paint().also { it.color = 0x22ffffff }
        private var starts = IntArray(0)
        private var lens = IntArray(0)
        private var comment = BooleanArray(0)
        private var dirty = true
        private val rebuild = Runnable { measureLines(); invalidate() }

        init { setBackgroundColor(Ui.bg); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun textChanged() { dirty = true; removeCallbacks(rebuild); postDelayed(rebuild, 300) }

        private fun measureLines() {
            dirty = false
            val t = edit.text
            val n = t.length
            val lc = (t.count { it == '\n' } + 1).coerceAtMost(200000)
            starts = IntArray(lc); lens = IntArray(lc); comment = BooleanArray(lc)
            val lcm = lang.lineComment
            var line = 0; var i = 0
            while (line < lc && i <= n) {
                var ind = 0
                while (i + ind < n && (t[i + ind] == ' ' || t[i + ind] == '\t')) ind++
                var e = i
                while (e < n && t[e] != '\n') e++
                starts[line] = ind; lens[line] = e - i
                comment[line] = lcm != null && i + ind + lcm.length <= n && t.regionMatches(i + ind, lcm, 0, lcm.length)
                line++; i = e + 1
            }
        }

        /** Pixels per line: 2 when the whole file fits, smaller for long files. */
        private fun pitch(lines: Int): Float = if (lines <= 0) 2f else minOf(2f * resources.displayMetrics.density / 1.5f, (height - paddingTop - paddingBottom).toFloat() / lines)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (dirty) measureLines()
            val lines = starts.size
            if (lines == 0) return
            val p = pitch(lines)
            val w = width.toFloat()
            val cw = (w - 8f) / 100f
            val h = maxOf(1f, p - (if (p >= 2f) 0.6f else 0f))
            // The text, line by line (only the visible range of the map when the file is long).
            for (i in 0 until lines) {
                val y = i * p
                if (y > height) break
                if (lens[i] == 0) continue
                bar.color = if (comment[i]) 0x40b48cff else 0x5ab4bccc
                val x1 = 4f + minOf(starts[i], 100) * cw
                val x2 = 4f + minOf(lens[i], 100) * cw
                if (x2 > x1) canvas.drawRect(x1, y, x2, y + h, bar)
            }
            fun markLine(line: Int, color: Int, full: Boolean = false) {
                if (line < 0 || line >= lines) return
                bar.color = color
                val y = line * p
                if (full) canvas.drawRect(0f, y, w, y + maxOf(2f, h), bar) else canvas.drawRect(w - 5f, y, w - 1f, y + maxOf(3f, h), bar)
            }
            for ((line, k) in gitMarks) markLine(line, when (k) { 1 -> 0xff3fae74.toInt(); 2 -> 0xff4d8fe0.toInt(); else -> 0xffe5484d.toInt() })
            for (hk in review) { for (ln in hk.b0 until hk.b1) markLine(ln, 0x6631c48d, full = true); if (hk.removed.isNotEmpty()) markLine(hk.b0, 0xaaff5c6c.toInt()) }
            if (findMatches.isNotEmpty()) for (r in findMatches) markLine(lineColOf(r.first).first, 0xaaff8a3d.toInt())
            for (d in diagnostics) markLine(d.line, CodeEditor.severityColor(d.severity))
            // The visible part of the text.
            val l = edit.layout
            if (l != null && l.height > 0) {
                val first = l.getLineForVertical(edit.scrollY)
                val last = l.getLineForVertical(edit.scrollY + edit.height)
                val top = first * p; val bottom = (last + 1) * p
                canvas.drawRect(0f, top, w, bottom, band)
                canvas.drawRect(0f, top, w, top + 1f, bandEdge); canvas.drawRect(0f, bottom - 1f, w, bottom, bandEdge)
                val cur = l.getLineForOffset(edit.selectionEnd.coerceIn(0, edit.text.length))
                bar.color = 0xccff8a3d.toInt()
                canvas.drawRect(0f, cur * p, 3f, cur * p + maxOf(2f, h), bar)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val lines = starts.size
                    if (lines == 0) return true
                    val line = (event.y / pitch(lines)).toInt().coerceIn(0, lines - 1)
                    val l = edit.layout ?: return true
                    val target = (l.getLineTop(line.coerceAtMost(l.lineCount - 1)) - edit.height / 2).coerceIn(0, maxScrollY())
                    edit.scrollTo(edit.scrollX, target)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_UP -> { performClick(); return true }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean { super.performClick(); return true }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                edit.scrollTo(edit.scrollX, (edit.scrollY - (v * edit.lineHeight * 3).toInt()).coerceIn(0, maxScrollY()))
                return true
            }
            return super.onGenericMotionEvent(event)
        }
    }
}
