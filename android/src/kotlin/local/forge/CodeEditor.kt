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
        /** Ctrl M: the Tab key moves the focus out of the editor instead of typing (for keyboard navigation). */
        var tabMovesFocus = false
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
    /** Set once the whole editor exists (the EditText calls back while it is being constructed). */
    private var editorReady = false

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
                if (editorReady) { shiftCursors(start, before, count); foldsAfterEdit(start, before, count) }
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
        dropFoldsForNewText(); extra.clear(); cursorUndo.clear(); expandStack.clear()
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
        dropFoldsForNewText(); extra.clear(); cursorUndo.clear(); expandStack.clear()
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
        val off = offsetOf(line0.coerceAtLeast(0), col0)
        edit.setSelection(off)
        val y = (l.getLineTop(l.getLineForOffset(off)) - edit.height / 3).coerceIn(0, maxScrollY())
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
        val lines = lineCount()
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

    /** The number of lines of the text itself (wrapped lines count once). */
    fun lineCount(): Int = logicalStarts().size

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

    /** F8 and Shift F8: the cursor goes to the next or previous problem of this file; false when there is none. */
    fun gotoDiagnostic(dir: Int): Boolean {
        if (diagnostics.isEmpty()) return false
        val here = cursorOffset()
        val offs = diagnostics.map { offsetOf(it.line, it.col) }.distinct().sorted()
        val target = if (dir > 0) offs.firstOrNull { it > here } else offs.lastOrNull { it < here }
        if (target == null) return false
        val (l, c) = lineColOf(target)
        goTo(l, c)
        val d = diagnostics.firstOrNull { offsetOf(it.line, it.col) == target }
        if (d != null) Ui.toast(context, d.message.take(200))
        return true
    }

    /** Ctrl Shift . and Ctrl Shift , : the number at the cursor goes up or down by one, true and false swap, and so on. */
    fun inPlaceReplace(dir: Int) {
        val t = edit.text
        val c = cursorOffset()
        // A number (with a sign and decimals) around the cursor.
        var a = c; var b = c
        while (a > 0 && (t[a - 1].isDigit() || t[a - 1] == '.')) a--
        while (b < t.length && (t[b].isDigit() || t[b] == '.')) b++
        if (a > 0 && t[a - 1] == '-' ) a--
        val num = t.subSequence(a, b).toString()
        if (num.isNotEmpty() && num != "-" && num != ".") {
            val out = if (num.contains('.')) { val dec = num.substringAfter('.').length; String.format(java.util.Locale.ROOT, "%." + dec + "f", num.toDouble() + dir * Math.pow(10.0, -dec.toDouble())) }
                else (num.toLongOrNull()?.plus(dir))?.toString() ?: return
            replaceText(a, b, out, a, a + out.length); return
        }
        val w = wordRangeAt(c); if (w.isEmpty()) return
        val word = t.subSequence(w.first, w.last + 1).toString()
        val cycles = listOf(listOf("true", "false"), listOf("True", "False"), listOf("TRUE", "FALSE"), listOf("yes", "no"), listOf("on", "off"), listOf("public", "protected", "private"), listOf("let", "const", "var"))
        for (cy in cycles) { val i = cy.indexOf(word); if (i >= 0) { val out = cy[((i + dir) % cy.size + cy.size) % cy.size]; replaceText(w.first, w.last + 1, out, w.first, w.first + out.length); return } }
    }

    /** Highlights the 1-based line the debugger is paused on and scrolls to it; null clears the highlight. */
    fun showExecutionLine(line: Int?) {
        executionLine = if (line == null || line < 1) -1 else line - 1
        if (executionLine >= 0) {
            val l = edit.layout
            if (l != null) {
                val ln = layoutLineOf(executionLine)
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
        setWordWrap(wrapWanted)
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

    /** Ctrl Right while a suggestion shows: only its next word goes in (Cursor's "accept next word"). */
    fun acceptGhostWord(): Boolean {
        val g = ghost ?: return false
        val at = ghostOffset
        if (at != cursorOffset()) return false
        var i = 0
        while (i < g.length && g[i].isWhitespace() && g[i] != '\n') i++
        if (i < g.length && g[i] == '\n') i++ else {
            if (i < g.length && isWordChar(g[i])) { while (i < g.length && isWordChar(g[i])) i++ } else if (i < g.length) i++
        }
        val word = g.substring(0, i)
        val rest = g.substring(i)
        ghost = null; ghostOffset = -1
        replaceText(at, at, word)
        if (rest.isNotEmpty()) showGhost(rest)
        return true
    }

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
        if (handleMultiKey(code, ev)) return true
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
                // Ctrl D adds the next occurrence as a cursor, as in VS Code and Cursor.
                KeyEvent.KEYCODE_D -> if (!shift) { addNextOccurrence(); return true }
                KeyEvent.KEYCODE_U -> if (!shift) { undoCursorOperation(); return true }
                KeyEvent.KEYCODE_SLASH -> { toggleComment(); return true }
                KeyEvent.KEYCODE_F -> if (!shift) { showFind(); return true }
                KeyEvent.KEYCODE_H -> if (!shift) { showReplace(); return true }
                KeyEvent.KEYCODE_G -> if (!shift) { askGoToLine(); return true }
                KeyEvent.KEYCODE_A -> if (shift) { toggleBlockComment(); return true }
                KeyEvent.KEYCODE_K -> if (shift) { deleteLines(); return true }
                KeyEvent.KEYCODE_C -> if (!shift && copyLineIfEmpty(cut = false)) return true
                KeyEvent.KEYCODE_X -> if (!shift && copyLineIfEmpty(cut = true)) return true
                KeyEvent.KEYCODE_V -> if (!shift && pasteLineIfCopied()) return true
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { insertLine(below = !shift); return true }
                KeyEvent.KEYCODE_RIGHT_BRACKET -> { if (shift) unfoldAtCursor() else indent(force = true); return true }
                KeyEvent.KEYCODE_LEFT_BRACKET -> { if (shift) foldAtCursor() else outdent(); return true }
                KeyEvent.KEYCODE_BACKSLASH -> if (shift) { jumpToBracket(); return true }
                KeyEvent.KEYCODE_F2 -> { selectAllOccurrences(); return true }
                KeyEvent.KEYCODE_F3 -> { findSelectionNext(!shift); return true }
                KeyEvent.KEYCODE_DEL -> { deleteWord(back = true); return true }
                KeyEvent.KEYCODE_FORWARD_DEL -> { deleteWord(back = false); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { cursorWordLeft(shift); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!shift && ghost != null && acceptGhostWord()) return true; cursorWordRight(shift); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (!shift) { scrollLines(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!shift) { scrollLines(1); return true }
                KeyEvent.KEYCODE_MOVE_HOME -> { cursorDocument(end = false, extend = shift); return true }
                KeyEvent.KEYCODE_MOVE_END -> { cursorDocument(end = true, extend = shift); return true }
            }
            return false
        }
        if (ev.isAltPressed) {
            val ctrl = ev.isCtrlPressed
            when (code) {
                // Alt Up and Down move lines; Ctrl Shift Alt (Linux) copies them; Shift Alt (Linux) and Ctrl Alt
                // (Windows) add a cursor above or below.
                KeyEvent.KEYCODE_DPAD_UP -> { if (ctrl && shift) copyLines(up = true) else if (shift || ctrl) addCursorVertical(-1) else moveLines(up = true); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (ctrl && shift) copyLines(up = false) else if (shift || ctrl) addCursorVertical(1) else moveLines(up = false); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (shift && !ctrl) { expandSelection(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (shift && !ctrl) { shrinkSelection(); return true }
                KeyEvent.KEYCODE_I -> if (shift && !ctrl) { cursorsAtLineEnds(); return true }
                KeyEvent.KEYCODE_Z -> if (!shift && !ctrl) { toggleWordWrap(); return true }
                KeyEvent.KEYCODE_PAGE_UP -> if (!ctrl) { scrollPage(-1); return true }
                KeyEvent.KEYCODE_PAGE_DOWN -> if (!ctrl) { scrollPage(1); return true }
            }
            return false
        }
        if (ev.isMetaPressed) return false
        if (ev.unicodeChar != 0 && autoPair(ev.unicodeChar.toChar())) return true
        when (code) {
            KeyEvent.KEYCODE_F3 -> { findNextMatch(!shift); return true }
            KeyEvent.KEYCODE_MOVE_HOME -> { smartHome(shift); return true }
            KeyEvent.KEYCODE_DEL -> if (deletePair()) return true
            KeyEvent.KEYCODE_TAB -> {
                if (tabMovesFocus) { edit.focusSearch(if (ev.isShiftPressed) View.FOCUS_BACKWARD else View.FOCUS_FORWARD)?.requestFocus(); return true }
                if (!ev.isShiftPressed && ghost != null) { if (acceptGhost()) return true }
                if (ev.isShiftPressed) outdent() else indent(); return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (ev.isShiftPressed) return false; newlineWithIndent(); return true }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (extra.isNotEmpty()) { clearExtraCursors(); return true }
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

    override fun onAttachedToWindow() { super.onAttachedToWindow(); editorReady = true; scheduleHighlight(0) }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Api.main.removeCallbacks(highlightTask); Api.main.removeCallbacks(scrollTask)
        edit.cancelHover()
    }

    // ------------------------------------------------------------------ logical lines (word wrap) and folding helpers
    /** Start offsets of the logical lines (the text's own lines), cached until the text changes. */
    private var starts = IntArray(1)
    private var startsVersion = -1

    private fun logicalStarts(): IntArray {
        if (startsVersion == textVersion) return starts
        val t = edit.text
        var n = 1
        for (i in 0 until t.length) if (t[i] == '\n') n++
        val a = IntArray(n)
        var k = 1
        for (i in 0 until t.length) if (t[i] == '\n') { a[k++] = i + 1 }
        starts = a; startsVersion = textVersion
        return a
    }

    /** The logical (0-based) line that holds an offset. */
    fun logicalLineAt(offset: Int): Int {
        val a = logicalStarts()
        var lo = 0; var hi = a.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (a[mid] <= offset) lo = mid else hi = mid - 1 }
        return lo
    }

    /** The first layout line of a logical line (the same number unless words wrap). */
    fun layoutLineOf(logical: Int): Int {
        val l = edit.layout ?: return logical
        if (!wordWrap) return logical.coerceIn(0, l.lineCount - 1)
        val a = logicalStarts()
        return l.getLineForOffset(a[logical.coerceIn(0, a.size - 1)])
    }

    /** The logical line a layout line belongs to. */
    fun logicalOfLayout(line: Int): Int {
        val l = edit.layout ?: return line
        if (!wordWrap) return line
        return logicalLineAt(l.getLineStart(line.coerceIn(0, l.lineCount - 1)))
    }

    /** True when a layout line begins a logical line (a wrapped continuation shows no line number). */
    fun startsLogicalLine(line: Int): Boolean {
        val l = edit.layout ?: return true
        if (!wordWrap) return true
        val s = l.getLineStart(line)
        return s == 0 || edit.text[s - 1] == '\n'
    }

    // ------------------------------------------------------------------ word wrap (Alt Z)
    /** Long lines wrap at the window's edge instead of scrolling sideways. While a change is under review the text does
     *  not wrap (the review's zones are measured per line), and it wraps again afterwards. */
    var wordWrap = false
        private set
    private var wrapWanted = false

    fun setWordWrap(on: Boolean) {
        wrapWanted = on
        val effective = on && review.isEmpty()
        if (effective == wordWrap) return
        wordWrap = effective
        val sel = edit.selectionEnd
        edit.setHorizontallyScrolling(!effective)
        if (effective) edit.scrollTo(0, edit.scrollY)
        post { edit.bringPointIntoView(sel.coerceIn(0, edit.text.length)); gutter.onLinesChanged(); minimap.invalidate() }
    }

    fun toggleWordWrap() = setWordWrap(!wrapWanted)

    // ------------------------------------------------------------------ render whitespace
    var renderWhitespace = false
        set(v) { field = v; edit.invalidate() }

    // ------------------------------------------------------------------ multiple cursors (Ctrl D, Alt click, Ctrl Alt arrows)
    /** One selection: [anchor] stays where it began and [head] is the caret. */
    class Sel(var anchor: Int, var head: Int) {
        val start: Int get() = minOf(anchor, head)
        val end: Int get() = maxOf(anchor, head)
    }

    /** The extra selections; the primary one is the EditText's own selection. */
    private val extra = ArrayList<Sel>()
    /** Earlier sets of cursors, for Ctrl U (undo the last cursor operation). The last entry of each is the primary. */
    private val cursorUndo = ArrayList<List<Sel>>()
    /** True while this class rewrites the text for every cursor, so the text watcher does not shift them again. */
    private var caretOps = false
    /** Text copied from several cursors, one piece per cursor, so a paste with as many cursors gives each its own. */
    private var multiClip: List<String>? = null
    /** Text copied by Ctrl C or Ctrl X with no selection (a whole line), which Ctrl V puts above the current line. */
    private var lineClip: String? = null

    fun cursorCount(): Int = extra.size + 1
    fun hasMultipleCursors(): Boolean = extra.isNotEmpty()

    private fun primarySel(): Sel = Sel(edit.selectionStart.coerceAtLeast(0), edit.selectionEnd.coerceAtLeast(0))
    private fun allSels(): List<Sel> = (extra.map { Sel(it.anchor, it.head) } + primarySel()).sortedBy { it.start }

    private fun snapshotCursors() {
        cursorUndo.add(extra.map { Sel(it.anchor, it.head) } + primarySel())
        if (cursorUndo.size > 60) cursorUndo.removeAt(0)
    }

    fun clearExtraCursors() {
        if (extra.isEmpty()) return
        extra.clear(); cursorUndo.clear()
        edit.invalidate()
    }

    /** Sets every cursor at once: overlapping selections merge, and the one whose caret is nearest [primaryHead]
     *  becomes the EditText's own selection. */
    private fun setCursors(sels: List<Sel>, primaryHead: Int) {
        val n = edit.text.length
        val clean = sels.map { Sel(it.anchor.coerceIn(0, n), it.head.coerceIn(0, n)) }.sortedBy { it.start }
        val merged = ArrayList<Sel>()
        for (s in clean) {
            val last = merged.lastOrNull()
            if (last != null && (s.start < last.end || s.start == last.start)) {
                val a = minOf(last.start, s.start); val b = maxOf(last.end, s.end)
                if (last.head >= last.anchor) { last.anchor = a; last.head = b } else { last.anchor = b; last.head = a }
                continue
            }
            merged.add(s)
        }
        if (merged.isEmpty()) return
        val p = merged.minByOrNull { Math.abs(it.head - primaryHead) } ?: merged.last()
        extra.clear()
        for (s in merged) if (s !== p) extra.add(s)
        caretOps = true
        try { edit.setSelection(p.anchor, p.head) } finally { caretOps = false }
        edit.bringPointIntoView(p.head)
        edit.invalidate(); gutter.invalidate()
    }

    /** Moves the extra cursors along with a change made somewhere else (typing at the primary cursor, an undo). */
    private fun shiftCursors(start: Int, before: Int, count: Int) {
        if (extra.isEmpty() || caretOps) return
        fun move(p: Int): Int = when {
            p >= start + before -> p + count - before
            p > start -> start + count
            else -> p
        }
        for (s in extra) { s.anchor = move(s.anchor); s.head = move(s.head) }
    }

    /** One replacement per cursor: [start] until [end] becomes [text]; afterwards the selection is [anchorAfter] to
     *  [headAfter], both counted from [start]. */
    private class Change(val start: Int, val end: Int, val text: String, val anchorAfter: Int, val headAfter: Int)

    /** Applies one change per cursor as a single undo step (one replacement of the region they span). */
    private fun editAll(f: (Sel) -> Change?) {
        val prim = primarySel()
        val sels = allSels()
        val planned = ArrayList<Pair<Sel, Change?>>()
        for (s in sels) planned.add(s to f(s))
        val changes = planned.mapNotNull { (s, c) -> if (c == null) null else s to c }.sortedBy { it.second.start }
        if (changes.isEmpty()) return
        val kept = ArrayList<Pair<Sel, Change>>()
        var lastEnd = -1
        for (p in changes) { if (p.second.start < lastEnd) continue; kept.add(p); lastEnd = p.second.end }
        val t = edit.text
        val regionStart = kept.first().second.start
        val regionEnd = kept.last().second.end
        val sb = StringBuilder()
        var pos = regionStart
        var delta = 0
        val out = ArrayList<Sel>()
        var primHead = prim.head
        val changedSels = HashSet<Sel>()
        for ((s, c) in kept) {
            sb.append(t, pos, c.start)
            val newStart = c.start + delta
            sb.append(c.text)
            out.add(Sel(newStart + c.anchorAfter, newStart + c.headAfter))
            if (s.anchor == prim.anchor && s.head == prim.head) primHead = newStart + c.headAfter
            delta += c.text.length - (c.end - c.start)
            pos = c.end
            changedSels.add(s)
        }
        // Cursors without a change of their own move with the changes before them.
        for ((s, _) in planned) {
            if (s in changedSels) continue
            var d = 0
            for ((_, c) in kept) if (c.end <= s.start) d += c.text.length - (c.end - c.start)
            out.add(Sel(s.anchor + d, s.head + d))
            if (s.anchor == prim.anchor && s.head == prim.head) primHead = s.head + d
        }
        caretOps = true
        try { replaceText(regionStart, regionEnd, sb.toString()) } finally { caretOps = false }
        setCursors(out, primHead)
    }

    private fun clipboard(): android.content.ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    private fun clipText(): String = try { clipboard().primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: "" } catch (e: Exception) { "" }

    private fun setClip(text: String) { try { clipboard().setPrimaryClip(android.content.ClipData.newPlainText("Forge", text)) } catch (e: Exception) { } }

    private fun lineIndentAt(pos: Int): String {
        val t = edit.text
        val ls = lineStartOf(pos)
        var i = ls
        while (i < t.length && (t[i] == ' ' || t[i] == '\t')) i++
        return t.subSequence(ls, i).toString()
    }

    private fun wordLeft(pos: Int): Int {
        val t = edit.text
        var i = pos
        while (i > 0 && t[i - 1].isWhitespace() && t[i - 1] != '\n') i--
        if (i > 0 && t[i - 1] == '\n' && i == pos) return i - 1
        if (i > 0 && isWordChar(t[i - 1])) { while (i > 0 && isWordChar(t[i - 1])) i-- }
        else if (i > 0 && !t[i - 1].isWhitespace()) { while (i > 0 && !isWordChar(t[i - 1]) && !t[i - 1].isWhitespace()) i-- }
        return i
    }

    private fun wordRight(pos: Int): Int {
        val t = edit.text
        var i = pos
        if (i < t.length && t[i] == '\n') return i + 1
        while (i < t.length && t[i].isWhitespace() && t[i] != '\n') i++
        if (i < t.length && isWordChar(t[i])) { while (i < t.length && isWordChar(t[i])) i++ }
        else if (i < t.length && t[i] != '\n') { while (i < t.length && !isWordChar(t[i]) && !t[i].isWhitespace()) i++ }
        return i
    }

    /** The caret one layout line up or down at the same horizontal position. */
    private fun verticalOffset(pos: Int, dir: Int): Int {
        val l = edit.layout ?: return pos
        var line = l.getLineForOffset(pos)
        val x = l.getPrimaryHorizontal(pos)
        var target = line + dir
        while (target in 0 until l.lineCount && isHiddenLine(target)) target += dir
        if (target < 0) return 0
        if (target >= l.lineCount) return edit.text.length
        line = target
        return l.getOffsetForHorizontal(line, x)
    }

    /** Keys while there are several cursors: typing, deleting, moving, copying and pasting apply to all of them. */
    private fun handleMultiKey(code: Int, ev: KeyEvent): Boolean {
        if (extra.isEmpty()) return false
        val ctrl = ev.isCtrlPressed; val alt = ev.isAltPressed; val shift = ev.isShiftPressed
        when (code) {
            KeyEvent.KEYCODE_ESCAPE -> { clearExtraCursors(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> if (!alt) { moveAll(code, shift, ctrl); return true }
            KeyEvent.KEYCODE_DEL -> if (!alt) { deleteAll(back = true, word = ctrl); return true }
            KeyEvent.KEYCODE_FORWARD_DEL -> if (!alt) { deleteAll(back = false, word = ctrl); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!ctrl && !alt) {
                editAll { s -> val ins = "\n" + lineIndentAt(s.start); Change(s.start, s.end, ins, ins.length, ins.length) }; return true
            }
            KeyEvent.KEYCODE_TAB -> if (!ctrl && !alt) {
                if (shift) editAll { s ->
                    val ls = lineStartOf(s.head); var n = 0
                    while (n < lang.tabSize && ls + n < edit.text.length && edit.text[ls + n] == ' ') n++
                    if (n == 0) null else Change(ls, ls + n, "", s.anchor - ls - n, s.head - ls - n)
                } else editAll { s -> val pad = " ".repeat(lang.tabSize - ((s.start - lineStartOf(s.start)) % lang.tabSize)); Change(s.start, s.end, pad, pad.length, pad.length) }
                return true
            }
        }
        if (ctrl && !alt) when (code) {
            KeyEvent.KEYCODE_C -> { copyAll(cut = false); return true }
            KeyEvent.KEYCODE_X -> { copyAll(cut = true); return true }
            KeyEvent.KEYCODE_V -> { pasteAll(); return true }
            KeyEvent.KEYCODE_A -> { clearExtraCursors(); return false }
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y -> { clearExtraCursors(); return false }
        }
        if (!ctrl && !alt && !ev.isMetaPressed && ev.unicodeChar != 0) {
            val ch = ev.unicodeChar.toChar().toString()
            editAll { s -> Change(s.start, s.end, ch, 1, 1) }
            return true
        }
        return false
    }

    private fun moveAll(code: Int, extend: Boolean, word: Boolean) {
        val t = edit.text
        val prim = primarySel()
        val out = ArrayList<Sel>()
        var primHead = prim.head
        for (s in allSels()) {
            val h = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!extend && s.start != s.end && !word) s.start else if (word) wordLeft(s.head) else (s.head - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!extend && s.start != s.end && !word) s.end else if (word) wordRight(s.head) else (s.head + 1).coerceAtMost(t.length)
                KeyEvent.KEYCODE_DPAD_UP -> verticalOffset(s.head, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> verticalOffset(s.head, 1)
                KeyEvent.KEYCODE_MOVE_HOME -> { val ls = lineStartOf(s.head); var f = ls; while (f < t.length && (t[f] == ' ' || t[f] == '\t')) f++; if (s.head == f) ls else f }
                else -> lineEndOf(s.head)
            }
            val ns = Sel(if (extend) s.anchor else h, h)
            if (s.anchor == prim.anchor && s.head == prim.head) primHead = h
            out.add(ns)
        }
        setCursors(out, primHead)
    }

    private fun deleteAll(back: Boolean, word: Boolean) {
        editAll { s ->
            if (s.start != s.end) Change(s.start, s.end, "", 0, 0)
            else if (back) { if (s.head == 0) null else { val from = if (word) wordLeft(s.head) else s.head - 1; Change(from, s.head, "", 0, 0) } }
            else { if (s.head >= edit.text.length) null else { val to = if (word) wordRight(s.head) else s.head + 1; Change(s.head, to, "", 0, 0) } }
        }
    }

    private fun copyAll(cut: Boolean) {
        val t = edit.text
        val pieces = ArrayList<String>()
        for (s in allSels()) {
            pieces.add(if (s.start != s.end) t.subSequence(s.start, s.end).toString() else t.subSequence(lineStartOf(s.head), lineEndOf(s.head)).toString())
        }
        multiClip = pieces
        val joined = pieces.joinToString("\n")
        setClip(joined)
        lineClip = null
        if (cut) editAll { s ->
            if (s.start != s.end) Change(s.start, s.end, "", 0, 0)
            else { val ls = lineStartOf(s.head); val le = lineEndOf(s.head); Change(ls, if (le < edit.text.length) le + 1 else le, "", 0, 0) }
        }
    }

    private fun pasteAll() {
        val clip = clipText()
        val pieces = multiClip?.takeIf { it.joinToString("\n") == clip && it.size == cursorCount() }
            ?: clip.split('\n').takeIf { it.size == cursorCount() && cursorCount() > 1 }
        var i = 0
        editAll { s -> val text = pieces?.getOrNull(i++) ?: clip; Change(s.start, s.end, text, text.length, text.length) }
    }

    /** Ctrl D: selects the word at the cursor, then adds the next occurrence of the selection as another cursor. */
    fun addNextOccurrence() {
        val t = edit.text
        val p = primarySel()
        if (p.start == p.end) {
            val r = wordRangeAt(p.head)
            if (r.isEmpty()) return
            snapshotCursors()
            edit.setSelection(r.first, r.last + 1)
            return
        }
        val needle = t.subSequence(p.start, p.end).toString()
        val sels = allSels()
        val from = sels.maxOf { it.end }
        var at = TextUtils.indexOf(t, needle, from)
        if (at < 0) at = TextUtils.indexOf(t, needle, 0)
        if (at < 0) return
        if (sels.any { it.start == at && it.end == at + needle.length }) { Ui.toast(context, "Every occurrence is already selected."); return }
        snapshotCursors()
        extra.add(p)
        caretOps = true
        try { edit.setSelection(at, at + needle.length) } finally { caretOps = false }
        edit.bringPointIntoView(at + needle.length)
        edit.invalidate()
    }

    /** Ctrl K Ctrl D: the last added selection moves to the next occurrence instead of adding one. */
    fun moveLastSelectionToNextOccurrence() {
        val p = primarySel()
        if (p.start == p.end) { addNextOccurrence(); return }
        val t = edit.text
        val needle = t.subSequence(p.start, p.end).toString()
        var at = TextUtils.indexOf(t, needle, p.end)
        if (at < 0) at = TextUtils.indexOf(t, needle, 0)
        if (at < 0 || at == p.start) return
        snapshotCursors()
        caretOps = true
        try { edit.setSelection(at, at + needle.length) } finally { caretOps = false }
        edit.bringPointIntoView(at + needle.length)
        edit.invalidate()
    }

    /** Ctrl F2 and Ctrl Shift L in VS Code: every occurrence of the selection, or of the word at the cursor. */
    fun selectAllOccurrences() {
        val t = edit.text
        val p = primarySel()
        val whole: Boolean
        val needle: String
        if (p.start == p.end) {
            val r = wordRangeAt(p.head); if (r.isEmpty()) return
            needle = t.subSequence(r.first, r.last + 1).toString(); whole = true
        } else { needle = t.subSequence(p.start, p.end).toString(); whole = false }
        if (needle.isEmpty()) return
        val out = ArrayList<Sel>()
        var i = TextUtils.indexOf(t, needle, 0)
        while (i >= 0 && out.size < 5000) {
            val okLeft = !whole || i == 0 || !isWordChar(t[i - 1])
            val okRight = !whole || i + needle.length >= t.length || !isWordChar(t[i + needle.length])
            if (okLeft && okRight) out.add(Sel(i, i + needle.length))
            i = TextUtils.indexOf(t, needle, i + needle.length)
        }
        if (out.isEmpty()) return
        snapshotCursors()
        setCursors(out, p.head)
    }

    /** Alt Enter in the find bar: every find match becomes a selection. */
    fun selectAllFindMatches() {
        if (findMatches.isEmpty()) refreshFindMatchesEverywhere()
        if (findMatches.isEmpty()) return
        snapshotCursors()
        setCursors(findMatches.map { Sel(it.first, it.last + 1) }, cursorOffset())
        hideFind()
    }

    /** Find matches in the whole file (the find bar itself only marks the visible part). */
    private fun refreshFindMatchesEverywhere() {
        val pat = findPattern() ?: return
        findMatches.clear()
        val m = pat.matcher(edit.text)
        while (m.find() && findMatches.size < 5000) { if (m.end() > m.start()) findMatches.add(m.start() until m.end()) }
    }

    /** Ctrl Alt Up and Down (Shift Alt Up and Down on Linux): another cursor on the line above or below. */
    fun addCursorVertical(dir: Int) {
        val p = primarySel()
        val sels = allSels()
        val base = if (dir < 0) sels.minByOrNull { it.head }!! else sels.maxByOrNull { it.head }!!
        val target = verticalOffset(base.head, dir)
        if (target == base.head) return
        snapshotCursors()
        val out = ArrayList(sels)
        out.add(Sel(target, target))
        setCursors(out, target)
        if (p.head == target) return
    }

    /** Shift Alt I: a cursor at the end of every line the selection touches. */
    fun cursorsAtLineEnds() {
        val p = primarySel()
        if (p.start == p.end) return
        val a = logicalLineAt(p.start); val b = logicalLineAt(if (p.end > p.start && edit.text[p.end - 1] == '\n') p.end - 1 else p.end)
        val st = logicalStarts()
        val out = ArrayList<Sel>()
        for (ln in a..b) { val e = lineEndOf(st[ln]); out.add(Sel(e, e)) }
        snapshotCursors()
        setCursors(out, out.last().head)
    }

    /** Alt click: a cursor more where the pointer is, or one less when there is one already. */
    fun toggleCursorAt(offset: Int) {
        val hit = extra.firstOrNull { it.head == offset && it.start == it.end }
        snapshotCursors()
        if (hit != null) { extra.remove(hit); edit.invalidate(); return }
        if (extra.isEmpty() && primarySel().head == offset) return
        extra.add(primarySel())
        caretOps = true
        try { edit.setSelection(offset) } finally { caretOps = false }
        edit.invalidate()
    }

    /** Shift Alt drag: a box selection, one selection per line between the two points, at the same columns. */
    fun columnSelect(fromOffset: Int, toOffset: Int) {
        val st = logicalStarts()
        val la = logicalLineAt(fromOffset); val lb = logicalLineAt(toOffset)
        val ca = fromOffset - st[la]; val cb = toOffset - st[lb]
        val out = ArrayList<Sel>()
        for (ln in minOf(la, lb)..maxOf(la, lb)) {
            val s = st[ln]; val len = lineEndOf(s) - s
            if (ca > len && cb > len && ln != la && ln != lb) continue
            out.add(Sel(s + minOf(ca, len), s + minOf(cb, len)))
        }
        if (out.isEmpty()) return
        setCursors(out, out.last().head)
    }

    /** Ctrl U: back to the cursors before the last cursor operation. */
    fun undoCursorOperation() {
        val last = cursorUndo.removeLastOrNull() ?: return
        val prim = last.last()
        extra.clear(); extra.addAll(last.dropLast(1))
        caretOps = true
        try { edit.setSelection(prim.anchor.coerceIn(0, edit.text.length), prim.head.coerceIn(0, edit.text.length)) } finally { caretOps = false }
        edit.invalidate()
    }

    // ------------------------------------------------------------------ copy, cut and paste of whole lines
    /** Ctrl C with no selection copies the whole line, as in VS Code. Returns false when a selection is there. */
    fun copyLineIfEmpty(cut: Boolean): Boolean {
        val s = selStart(); val e = selEnd()
        if (s != e) { lineClip = null; return false }
        val ls = lineStartOf(s); val le = lineEndOf(s)
        val line = edit.text.subSequence(ls, le).toString() + "\n"
        setClip(line); lineClip = line; multiClip = null
        if (cut) { if (le < edit.text.length) replaceText(ls, le + 1, "", ls, ls) else if (ls > 0) replaceText(ls - 1, le, "", lineStartOf(ls - 1), lineStartOf(ls - 1)) else replaceText(0, le, "", 0, 0) }
        return true
    }

    /** Ctrl V of a whole copied line puts it above the current line, as in VS Code. Returns false otherwise. */
    fun pasteLineIfCopied(): Boolean {
        val lc = lineClip ?: return false
        if (clipText() != lc || selStart() != selEnd()) return false
        val c = selStart(); val ls = lineStartOf(c)
        replaceText(ls, ls, lc, c + lc.length, c + lc.length)
        return true
    }

    // ------------------------------------------------------------------ moving and scrolling
    fun cursorWordLeft(extend: Boolean) { val h = wordLeft(edit.selectionEnd.coerceAtLeast(0)); if (extend) edit.setSelection(edit.selectionStart, h) else edit.setSelection(h) }
    fun cursorWordRight(extend: Boolean) { val h = wordRight(edit.selectionEnd.coerceAtLeast(0)); if (extend) edit.setSelection(edit.selectionStart, h) else edit.setSelection(h) }
    fun deleteWord(back: Boolean) {
        val s = selStart(); val e = selEnd()
        if (s != e) { replaceText(s, e, ""); return }
        if (back) { if (s > 0) replaceText(wordLeft(s), s, "") } else if (s < edit.text.length) replaceText(s, wordRight(s), "")
    }
    fun cursorDocument(end: Boolean, extend: Boolean) {
        val target = if (end) edit.text.length else 0
        if (extend) edit.setSelection(edit.selectionStart, target) else edit.setSelection(target)
        edit.bringPointIntoView(target)
    }
    /** Ctrl Up and Down scroll by a line, Alt Page Up and Down by a page, and the cursor stays where it is. */
    fun scrollLines(n: Int) {
        edit.scrollTo(edit.scrollX, (edit.scrollY + n * edit.lineHeight).coerceIn(0, maxScrollY()))
    }
    fun scrollPage(dir: Int) { scrollLines(dir * (edit.height / edit.lineHeight.coerceAtLeast(1) - 1).coerceAtLeast(1)) }

    /** Ctrl Shift \: the cursor jumps to the bracket that matches the one at (or around) it. */
    fun jumpToBracket() {
        updateBracket()
        val c = cursorOffset()
        if (bracketA >= 0 && bracketB >= 0) { edit.setSelection(if (c == bracketA || c == bracketA + 1) bracketB else bracketA); return }
        // Not at a bracket: go to the closing bracket of the innermost pair around the cursor.
        val t = edit.text
        var depth = 0; var i = c - 1
        val limit = maxOf(0, c - 50000)
        while (i >= limit) {
            val ch = t[i]
            if (ch in ")]}") depth++ else if (ch in "([{") { if (depth == 0) { edit.setSelection(i); updateBracket(); if (bracketB >= 0) edit.setSelection(bracketB); return }; depth-- }
            i--
        }
    }

    // ------------------------------------------------------------------ expand and shrink selection (Shift Alt Right and Left)
    private val expandStack = ArrayList<Pair<Int, Int>>()

    fun expandSelection() {
        val t = edit.text
        val s = selStart(); val e = selEnd()
        val candidates = ArrayList<Pair<Int, Int>>()
        val w = wordRangeAt(s)
        if (!w.isEmpty()) candidates.add(w.first to w.last + 1)
        // The string around the selection, its contents first.
        val ls = lineStartOf(s); val le = lineEndOf(e)
        for (q in charArrayOf('"', '\'', '`')) {
            var a = s - 1
            while (a >= ls && t[a] != q) a--
            var b = e
            while (b < le && t[b] != q) b++
            if (a >= ls && b < le) { candidates.add(a + 1 to b); candidates.add(a to b + 1) }
        }
        // Brackets around the selection: the contents, then the brackets with them.
        var depth = 0; var i = s - 1
        val limit = maxOf(0, s - 50000)
        while (i >= limit) {
            val ch = t[i]
            if (ch in ")]}") depth++ else if (ch in "([{") {
                if (depth == 0) {
                    val close = when (ch) { '(' -> ')'; '[' -> ']'; else -> '}' }
                    var d = 0; var j = i
                    val lim = minOf(t.length, i + 200000)
                    while (j < lim) { val x = t[j]; if (x == ch) d++ else if (x == close) { d--; if (d == 0) break }; j++ }
                    if (j < lim && j >= e) { candidates.add(i + 1 to j); candidates.add(i to j + 1) }
                } else depth--
            }
            i--
        }
        candidates.add(ls to le)
        candidates.add(lineStartOf(s) to (if (le < t.length) le + 1 else le))
        candidates.add(0 to t.length)
        val next = candidates.filter { (a, b) -> a <= s && b >= e && (a < s || b > e) }.minByOrNull { it.second - it.first } ?: return
        expandStack.add(s to e)
        edit.setSelection(next.first, next.second)
    }

    fun shrinkSelection() {
        val prev = expandStack.removeLastOrNull() ?: run { val c = cursorOffset(); edit.setSelection(c); return }
        edit.setSelection(prev.first, prev.second)
    }

    // ------------------------------------------------------------------ comments
    /** Ctrl K Ctrl C (add = true) and Ctrl K Ctrl U (add = false): line comments without toggling. */
    fun setLineComment(add: Boolean) {
        val lc = lang.lineComment ?: run { if (add) toggleBlockComment(); return }
        mapSelectedLines { lines ->
            if (add) {
                val nonBlank = lines.filter { it.isNotBlank() }
                val minIndent = nonBlank.minOfOrNull { l -> l.length - l.trimStart().length } ?: 0
                lines.map { l -> if (l.isBlank()) l else l.substring(0, minIndent) + lc + " " + l.substring(minIndent) }
            } else lines.map { l ->
                val i = l.indexOf(lc)
                if (i < 0 || l.substring(0, i).isNotBlank()) l else { val after = i + lc.length; l.substring(0, i) + l.substring(if (after < l.length && l[after] == ' ') after + 1 else after) }
            }
        }
    }

    /** Ctrl Shift A: a block comment around the selection, or removes the one that is there. */
    fun toggleBlockComment() {
        val bc = lang.blockComment ?: lang.lineComment?.let { it to "" } ?: return
        val s = selStart(); val e = selEnd()
        val a = if (s == e) lineStartOf(s) else s
        val b = if (s == e) lineEndOf(s) else e
        val region = edit.text.subSequence(a, b).toString()
        val trimmed = region.trim()
        if (bc.second.isNotEmpty() && trimmed.startsWith(bc.first) && trimmed.endsWith(bc.second) && trimmed.length >= bc.first.length + bc.second.length) {
            val off = region.indexOf(trimmed)
            val inner = trimmed.substring(bc.first.length, trimmed.length - bc.second.length).removePrefix(" ").removeSuffix(" ")
            val out = region.substring(0, off) + inner + region.substring(off + trimmed.length)
            replaceText(a, b, out, a, a + out.length)
        } else {
            val out = bc.first + " " + region + (if (bc.second.isEmpty()) "" else " " + bc.second)
            replaceText(a, b, out, a, a + out.length)
        }
    }

    // ------------------------------------------------------------------ transformations and line tools (command palette)
    /** Replaces the selection (or the word at the cursor) with f of it, and keeps it selected. */
    fun transformSelection(f: (String) -> String) {
        var s = selStart(); var e = selEnd()
        if (s == e) { val w = wordRangeAt(s); if (w.isEmpty()) return; s = w.first; e = w.last + 1 }
        if (extra.isNotEmpty()) { editAll { sel -> val a = if (sel.start == sel.end) wordRangeAt(sel.head).first else sel.start; val b = if (sel.start == sel.end) wordRangeAt(sel.head).last + 1 else sel.end; if (b <= a) null else { val out = f(edit.text.subSequence(a, b).toString()); Change(a, b, out, 0, out.length) } }; return }
        val out = f(edit.text.subSequence(s, e).toString())
        replaceText(s, e, out, s, s + out.length)
    }

    private fun words(s: String): List<String> =
        Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+").findAll(s).map { it.value }.toList()

    fun toUpper() = transformSelection { it.uppercase() }
    fun toLower() = transformSelection { it.lowercase() }
    fun toTitle() = transformSelection { s -> s.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { w -> w.lowercase().replaceFirstChar { c -> c.titlecase() } } }
    fun toSnake() = transformSelection { s -> words(s).joinToString("_") { it.lowercase() } }
    fun toKebab() = transformSelection { s -> words(s).joinToString("-") { it.lowercase() } }
    fun toCamel() = transformSelection { s -> words(s).mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { it.titlecase() } }.joinToString("") }
    fun toPascal() = transformSelection { s -> words(s).joinToString("") { w -> w.lowercase().replaceFirstChar { it.titlecase() } } }

    /** Runs f over the selected lines, or over the whole file when nothing is selected. */
    private fun mapLinesOrAll(f: (List<String>) -> List<String>) {
        if (selStart() == selEnd()) {
            val t = edit.text.toString()
            val out = f(t.split('\n')).joinToString("\n")
            if (out != t) replaceText(0, t.length, out, 0, 0)
        } else mapSelectedLines(f)
    }

    fun sortLines(descending: Boolean, ignoreCase: Boolean = false) = mapLinesOrAll { lines ->
        val c = if (ignoreCase) String.CASE_INSENSITIVE_ORDER else naturalOrder<String>()
        if (descending) lines.sortedWith(c.reversed()) else lines.sortedWith(c)
    }
    fun reverseLines() = mapLinesOrAll { it.reversed() }
    fun deleteDuplicateLines() = mapLinesOrAll { it.distinct() }
    fun trimTrailingWhitespace() {
        val t = edit.text.toString()
        val out = t.split('\n').joinToString("\n") { it.trimEnd(' ', '\t') }
        if (out != t) { val c = cursorOffset().coerceAtMost(out.length); replaceText(0, t.length, out, c, c) }
    }
    fun indentationToSpaces() = mapLinesOrAll { lines -> lines.map { l -> val i = l.length - l.trimStart('\t', ' ').length; l.substring(0, i).replace("\t", tabString()) + l.substring(i) } }
    fun indentationToTabs() = mapLinesOrAll { lines -> lines.map { l -> val i = l.length - l.trimStart('\t', ' ').length; val lead = l.substring(0, i).replace("\t", tabString()); "\t".repeat(lead.length / lang.tabSize) + " ".repeat(lead.length % lang.tabSize) + l.substring(i) } }

    /** Joins the selected lines (or the current line with the next one) into one, with single spaces. */
    fun joinLines() {
        val s = selStart(); val e = selEnd()
        val a = lineStartOf(s)
        var b = lineEndOf(e)
        if (s == e || lineStartOf(e) == a) { if (b >= edit.text.length) return; b = lineEndOf(b + 1) }
        val parts = edit.text.subSequence(a, b).toString().split('\n')
        val out = parts.first().trimEnd() + parts.drop(1).joinToString("") { val x = it.trim(); if (x.isEmpty()) "" else " $x" }
        replaceText(a, b, out, a + parts.first().trimEnd().length, a + parts.first().trimEnd().length)
    }

    fun duplicateSelection() = duplicateLine()
    fun selectLine() { val s = selStart(); val e = selEnd(); val a = lineStartOf(s); val b = lineEndOf(e); edit.setSelection(a, if (b < edit.text.length) b + 1 else b) }
    fun copyLinesUp() = copyLines(up = true)
    fun copyLinesDown() = copyLines(up = false)
    fun moveLinesUp() = moveLines(up = true)
    fun moveLinesDown() = moveLines(up = false)
    fun deleteLine() = deleteLines()
    fun indentLines() = indent(force = true)
    fun outdentLines() = outdent()
    fun toggleLineComment() = toggleComment()
    fun insertLineBelow() = insertLine(below = true)
    fun insertLineAbove() = insertLine(below = false)
    fun transposeLetters() {
        val c = cursorOffset(); val t = edit.text
        if (c <= 0 || c >= t.length || selStart() != selEnd()) return
        replaceText(c - 1, c + 1, "" + t[c] + t[c - 1], c + 1, c + 1)
    }
    fun findNextMatch(forward: Boolean) {
        if (findInput.text.isNullOrEmpty()) { val sel = selectionText(); if (sel.isNotEmpty() && !sel.contains('\n')) findInput.setText(sel) else { showFind(); return } }
        findNext(forward)
    }
    /** Ctrl F3: the selection or the word at the cursor becomes the search, and the next match is selected. */
    fun findSelectionNext(forward: Boolean) {
        var sel = selectionText()
        if (sel.isEmpty()) { val w = wordRangeAt(cursorOffset()); if (!w.isEmpty()) sel = edit.text.subSequence(w.first, w.last + 1).toString() }
        if (sel.isEmpty() || sel.contains('\n')) return
        findInput.setText(sel)
        findNext(forward)
    }

    // ------------------------------------------------------------------ folding
    /**
     * A folded region: its lines keep their text (nothing is removed from the file) but get no height, and their
     * characters are replaced by nothing when drawn. The header line above stays visible with a "⋯" after it.
     * The spans move with edits by themselves; an edit inside a folded region unfolds it.
     */
    inner class Fold {
        val height = FoldHeightSpan()
        val hides = ArrayList<FoldHideSpan>()
        fun start(): Int = edit.text.getSpanStart(height)
        fun end(): Int = edit.text.getSpanEnd(height)
    }

    /** Gives the folded lines no height: the line's own spacing is taken back, so 100 folded lines take no room. */
    inner class FoldHeightSpan : LineHeightSpan, UpdateLayout {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            // The line spacing is added after this height except on the last line, so the last line gets exactly 0.
            val extra = if (end >= text.length) 0 else edit.lineSpacingExtra.toInt()
            fm.top = 0; fm.ascent = 0
            fm.descent = -extra; fm.bottom = -extra
        }
    }

    /** Draws nothing and takes no width, for the text of one folded line (a replacement span never covers a newline). */
    class FoldHideSpan : android.text.style.ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = 0
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    private val folds = ArrayList<Fold>()
    /** The caret's previous position, to step over a folded region instead of landing inside it. */
    private var lastCaret = 0

    fun hasFolds(): Boolean = folds.isNotEmpty()

    /** The indentation width of a line, tabs counted as tab stops; -1 for a blank line. */
    private fun indentOf(line: String): Int {
        var w = 0
        for (ch in line) { if (ch == ' ') w++ else if (ch == '\t') w += lang.tabSize - (w % lang.tabSize) else return w }
        return -1
    }

    /** The foldable regions as (header line, last hidden line), by indentation, like VS Code without a language
     *  folding provider: a line whose following lines are indented deeper starts a region. Markdown folds by headings. */
    fun foldRegions(): List<Pair<Int, Int>> {
        val lines = edit.text.toString().split('\n')
        val out = ArrayList<Pair<Int, Int>>()
        if (lang.id == "markdown") {
            val level = lines.map { l -> if (l.startsWith("#")) l.takeWhile { it == '#' }.length else 0 }
            for (i in lines.indices) {
                if (level[i] == 0) continue
                var j = i + 1
                while (j < lines.size && (level[j] == 0 || level[j] > level[i])) j++
                var last = j - 1
                while (last > i && lines[last].isBlank()) last--
                if (last > i) out.add(i to last)
            }
            return out
        }
        val ind = IntArray(lines.size) { indentOf(lines[it]) }
        for (i in lines.indices) {
            if (ind[i] < 0) continue
            var j = i + 1
            while (j < lines.size && ind[j] < 0) j++
            if (j >= lines.size || ind[j] <= ind[i]) continue
            var k = j
            var last = j
            while (k < lines.size && (ind[k] < 0 || ind[k] > ind[i])) { if (ind[k] >= 0) last = k; k++ }
            if (last > i) out.add(i to last)
        }
        // Block comments of several lines fold too.
        val bc = lang.blockComment
        if (bc != null) {
            var i = 0
            while (i < lines.size) {
                val tl = lines[i].trimStart()
                if (tl.startsWith(bc.first) && tl.indexOf(bc.second, bc.first.length) < 0) {
                    var j = i + 1
                    while (j < lines.size && !lines[j].contains(bc.second)) j++
                    if (j < lines.size && j > i) { out.add(i to j); i = j }
                }
                i++
            }
        }
        return out.distinct().sortedBy { it.first }
    }

    private fun foldAtHeader(header: Int): Fold? = folds.firstOrNull { logicalLineAt(it.start()) == header + 1 }

    /** Folds lines header+1 until last (logical, inclusive). Folds inside it are opened first. */
    private fun addFold(header: Int, last: Int) {
        val st = logicalStarts()
        if (header < 0 || last <= header || last >= st.size) return
        if (foldAtHeader(header) != null) return
        val t = edit.text
        val a = st[header + 1]
        val lastEnd = lineEndOf(st[last])
        val b = if (lastEnd < t.length) lastEnd + 1 else lastEnd
        for (f in folds.filter { it.start() >= a && it.end() <= b }) removeFold(f)
        val f = Fold()
        // The caret must not stay inside the region it hides.
        val c = cursorOffset()
        if (c in a until b + (if (b == t.length) 1 else 0)) edit.setSelection(lineEndOf(st[header]))
        for (ln in header + 1..last) {
            val s = st[ln]; val e = lineEndOf(s)
            if (e > s) { val h = FoldHideSpan(); t.setSpan(h, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); f.hides.add(h) }
        }
        t.setSpan(f.height, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        folds.add(f)
        afterFoldChange()
    }

    private fun removeFold(f: Fold) {
        val t = edit.text
        t.removeSpan(f.height)
        for (h in f.hides) t.removeSpan(h)
        folds.remove(f)
    }

    private fun afterFoldChange() {
        edit.requestLayout(); edit.invalidate(); gutter.invalidate(); minimap.invalidate()
        scheduleHighlight(30)
    }

    /** The innermost region that holds the cursor's line (or starts on it). */
    private fun regionAtCursor(regions: List<Pair<Int, Int>> = foldRegions()): Pair<Int, Int>? {
        val line = logicalLineAt(cursorOffset())
        return regions.filter { (h, l) -> line in h..l }.minByOrNull { it.second - it.first }
    }

    /** Ctrl Shift [: folds the innermost open region at the cursor. */
    fun foldAtCursor() {
        val regions = foldRegions()
        val line = logicalLineAt(cursorOffset())
        val open = regions.filter { (h, l) -> line in h..l && foldAtHeader(h) == null }.minByOrNull { it.second - it.first } ?: return
        addFold(open.first, open.second)
    }

    /** Ctrl Shift ]: opens the fold on the cursor's line (or the one that holds it). */
    fun unfoldAtCursor() {
        val line = logicalLineAt(cursorOffset())
        val f = foldAtHeader(line) ?: folds.filter { logicalLineAt(it.start()) - 1 <= line && logicalLineAt((it.end() - 1).coerceAtLeast(0)) >= line }.maxByOrNull { it.start() } ?: return
        removeFold(f); afterFoldChange()
    }

    /** Ctrl K Ctrl L: folds or opens the region at the cursor. */
    fun toggleFold() {
        val line = logicalLineAt(cursorOffset())
        val f = foldAtHeader(line)
        if (f != null) { removeFold(f); afterFoldChange() } else foldAtCursor()
    }

    /** Ctrl K Ctrl [ and Ctrl K Ctrl ]: the region at the cursor and every region inside it. */
    fun foldRecursively(fold: Boolean) {
        val regions = foldRegions()
        val r = regionAtCursor(regions) ?: return
        if (!fold) { for (f in folds.filter { logicalLineAt(it.start()) - 1 >= r.first && logicalLineAt(it.start()) - 1 <= r.second }) removeFold(f); afterFoldChange(); return }
        addFold(r.first, r.second)
    }

    /** Ctrl K Ctrl 0 folds every region from the outside in; Ctrl K Ctrl J opens all of them. */
    fun foldAll() {
        val regions = foldRegions()
        // Only the outermost regions need a fold: folding one hides everything inside it.
        var coveredUntil = -1
        for ((h, l) in regions.sortedWith(compareBy({ it.first }, { -it.second }))) {
            if (h <= coveredUntil) continue
            addFold(h, l); coveredUntil = l
        }
    }

    fun unfoldAll() { for (f in folds.toList()) removeFold(f); afterFoldChange() }

    /** Ctrl K Ctrl 1 to 7: folds the regions at that depth (1 is the outermost). */
    fun foldLevel(level: Int) {
        val regions = foldRegions()
        fun depth(r: Pair<Int, Int>): Int = regions.count { o -> o != r && o.first <= r.first && o.second >= r.second && (o.first < r.first || o.second > r.second) } + 1
        unfoldAll()
        for (r in regions.filter { depth(it) == level }) addFold(r.first, r.second)
    }

    /** Ctrl K Ctrl /: every block comment of several lines. */
    fun foldBlockComments() {
        val bc = lang.blockComment ?: return
        val lines = edit.text.toString().split('\n')
        for ((h, l) in foldRegions()) if (lines[h].trimStart().startsWith(bc.first)) addFold(h, l)
    }

    /** Ctrl K Ctrl - : every region except the ones around the cursor. */
    fun foldAllExceptCursor() {
        val line = logicalLineAt(cursorOffset())
        var coveredUntil = -1
        for ((h, l) in foldRegions().sortedWith(compareBy({ it.first }, { -it.second }))) {
            if (line in h..l) continue
            if (h <= coveredUntil) continue
            addFold(h, l); coveredUntil = l
        }
    }

    /** A gutter click on a header line folds or opens its region. */
    fun toggleFoldAtLine(line: Int): Boolean {
        val f = foldAtHeader(line)
        if (f != null) { removeFold(f); afterFoldChange(); return true }
        val r = foldRegions().filter { it.first == line }.maxByOrNull { it.second - it.first } ?: return false
        addFold(r.first, r.second)
        return true
    }

    fun isFoldHeader(line: Int): Boolean = foldAtHeader(line) != null

    /** True when a layout line is hidden by a fold. */
    fun isHiddenLine(layoutLine: Int): Boolean {
        if (folds.isEmpty()) return false
        val l = edit.layout ?: return false
        val s = l.getLineStart(layoutLine)
        return folds.any { s >= it.start() && s < it.end() }
    }

    /** An edit that touched a folded region opens it; the caret never rests inside one. */
    private fun foldsAfterEdit(start: Int, before: Int, count: Int) {
        if (folds.isEmpty()) return
        var changed = false
        for (f in folds.toList()) {
            val a = f.start(); val b = f.end()
            if (a < 0 || b < 0 || (start < b && start + count > a) || (start in a until b)) { removeFold(f); changed = true }
        }
        if (changed) afterFoldChange()
    }

    private fun stepOverFolds(pos: Int) {
        if (folds.isEmpty()) { lastCaret = pos; return }
        val f = folds.firstOrNull { pos >= it.start() && pos < it.end() }
        if (f != null) {
            // Coming from below goes to the end of the header line; from above (or elsewhere) to the line after the fold.
            val target = if (lastCaret >= f.end()) (f.start() - 1).coerceAtLeast(0) else f.end().coerceAtMost(edit.text.length)
            lastCaret = target
            if (edit.selectionStart == edit.selectionEnd) edit.setSelection(target) else edit.setSelection(edit.selectionStart, target)
            return
        }
        lastCaret = pos
    }

    private fun dropFoldsForNewText() { folds.clear() }

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
                drawExtraSelections(canvas, l)
                if (executionLine >= 0) {
                    val el = layoutLineOf(executionLine)
                    val et = (l.getLineTop(el) + extendedPaddingTop).toFloat()
                    val eb = (l.getLineBottom(el) + extendedPaddingTop).toFloat()
                    canvas.drawRect(scrollX.toFloat(), et, (scrollX + width).toFloat(), eb, execPaint)
                }
            }
            super.onDraw(canvas)
            if (l != null) {
                drawWhitespace(canvas, l); drawFoldMarkers(canvas, l); drawExtraCarets(canvas, l)
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

        private val extraSelPaint = Paint().also { it.color = selection }
        private val extraCaretPaint = Paint().also { it.color = Ui.fg }
        private val foldBox = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = 0xff2a3040.toInt() }
        private val foldText = Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Typeface.MONOSPACE; it.color = Ui.fg3 }
        private val wsPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = 0x55858fa8 }
        val foldRects = ArrayList<Pair<RectF, Int>>()

        /** The extra cursors' selections, behind the text. */
        private fun drawExtraSelections(canvas: Canvas, l: android.text.Layout) {
            if (extra.isEmpty()) return
            val padLeft = compoundPaddingLeft.toFloat()
            val vis = visibleLines(l)
            for (s in extra) {
                if (s.start == s.end) continue
                val la = l.getLineForOffset(s.start); val lb = l.getLineForOffset(s.end)
                for (ln in maxOf(la, vis.first)..minOf(lb, vis.last)) {
                    val x1 = if (ln == la) l.getPrimaryHorizontal(s.start) else 0f
                    val x2 = if (ln == lb) l.getPrimaryHorizontal(s.end) else l.getLineRight(ln) + paint.measureText(" ")
                    canvas.drawRect(x1 + padLeft, textTop(l, ln), x2 + padLeft, textBottom(l, ln), extraSelPaint)
                }
            }
        }

        /** The extra cursors' carets, over the text. */
        private fun drawExtraCarets(canvas: Canvas, l: android.text.Layout) {
            if (extra.isEmpty()) return
            val padLeft = compoundPaddingLeft.toFloat()
            val w = Ui.dp(context, 2).toFloat()
            for (s in extra) {
                val off = s.head.coerceIn(0, text.length)
                val ln = l.getLineForOffset(off)
                val x = l.getPrimaryHorizontal(off) + padLeft
                canvas.drawRect(x, textTop(l, ln), x + w, (l.getLineBaseline(ln) + extendedPaddingTop + paint.fontMetricsInt.descent).toFloat(), extraCaretPaint)
            }
        }

        /** A "⋯" box after each folded header line; a click on it opens the fold. */
        private fun drawFoldMarkers(canvas: Canvas, l: android.text.Layout) {
            foldRects.clear()
            if (folds.isEmpty()) return
            val padLeft = compoundPaddingLeft.toFloat()
            val dp = context.resources.displayMetrics.density
            foldText.textSize = textSize
            for (f in folds) {
                val a = f.start()
                if (a <= 0) continue
                val headerEnd = a - 1
                val ln = l.getLineForOffset(headerEnd)
                val x = l.getPrimaryHorizontal(headerEnd) + padLeft + 6 * dp
                val label = " ⋯ "
                val w = foldText.measureText(label)
                val top = textTop(l, ln) + 2 * dp; val bottom = textBottom(l, ln) - 2 * dp
                val r = RectF(x, top, x + w, bottom)
                canvas.drawRoundRect(r, 3 * dp, 3 * dp, foldBox)
                canvas.drawText(label, x, (l.getLineBaseline(ln) + extendedPaddingTop).toFloat(), foldText)
                foldRects.add(r to logicalLineAt(headerEnd))
            }
        }

        /** Render whitespace: a dot for each space and an arrow for each tab in the visible lines. */
        private fun drawWhitespace(canvas: Canvas, l: android.text.Layout) {
            if (!renderWhitespace) return
            val padLeft = compoundPaddingLeft.toFloat()
            val vis = visibleLines(l)
            val r = Ui.dp(context, 1).toFloat()
            wsPaint.textSize = textSize
            for (ln in vis.first..minOf(vis.last, l.lineCount - 1)) {
                if (isHiddenLine(ln)) continue
                val a = l.getLineStart(ln); val b = l.getLineEnd(ln)
                val cy = (textTop(l, ln) + textBottom(l, ln)) / 2f
                for (i in a until b.coerceAtMost(text.length)) {
                    val ch = text[i]
                    if (ch == ' ') { val x1 = l.getPrimaryHorizontal(i); val x2 = if (i + 1 <= text.length) l.getPrimaryHorizontal(i + 1) else x1; canvas.drawCircle(padLeft + (x1 + x2) / 2f, cy, r, wsPaint) }
                    else if (ch == '\t') canvas.drawText("→", padLeft + l.getPrimaryHorizontal(i), (l.getLineBaseline(ln) + extendedPaddingTop).toFloat(), wsPaint)
                }
            }
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
                // Diagnostics count text lines; the layout lines they cover come from their offsets (words may wrap).
                val da = offsetOf(d.line, d.col); val db = offsetOf(d.endLine, d.endCol)
                val la = l.getLineForOffset(da); val lb = l.getLineForOffset(db)
                if (lb < firstVisible || la > lastVisible) continue
                wavePaint.color = severityColor(d.severity)
                for (ln in la.coerceAtLeast(firstVisible)..lb.coerceAtMost(lastVisible)) {
                    if (ln >= l.lineCount) break
                    if (isHiddenLine(ln)) continue
                    val lineStart = l.getLineStart(ln); val lineEnd = l.getLineEnd(ln)
                    var a = if (ln == la) da.coerceIn(lineStart, lineEnd) else lineStart
                    var b = if (ln == lb) db.coerceIn(lineStart, lineEnd) else lineEnd
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
            // The EditText reports a selection while it is being built, before the fold list exists.
            if (!caretOps && editorReady) stepOverFolds(selEnd)
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
        private var boxFrom = -1

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
            // A click on a fold's "⋯" opens it.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && foldRects.isNotEmpty()) {
                val x = event.x + scrollX; val y = event.y + scrollY
                val hit = foldRects.firstOrNull { it.first.contains(x, y) }
                if (hit != null) { toggleFoldAtLine(hit.second); return true }
            }
            // Alt click adds a cursor; Shift Alt drag selects a box (one selection per line).
            val altOn = (event.metaState and KeyEvent.META_ALT_ON) != 0
            val shiftOn = (event.metaState and KeyEvent.META_SHIFT_ON) != 0
            if (altOn && (event.buttonState and MotionEvent.BUTTON_SECONDARY) == 0) {
                val off = offsetAt(event.x.toInt(), event.y.toInt())
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { if (!isFocused) requestFocus(); if (shiftOn) { boxFrom = cursorOffset(); columnSelect(boxFrom, off) } else { boxFrom = -1; toggleCursorAt(off) } }
                    MotionEvent.ACTION_MOVE -> if (shiftOn && boxFrom >= 0) columnSelect(boxFrom, off)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> boxFrom = -1
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN && (event.buttonState and MotionEvent.BUTTON_SECONDARY) == 0) clearExtraCursors()
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
        /** The zone right of the numbers with the fold chevrons. */
        private val foldZone = Ui.dp(c, 14)
        private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeWidth = Ui.dp(c, 1).toFloat().coerceAtLeast(1.5f); it.color = Ui.fg3 }
        private var digits = 3
        private var downY = -1f
        private var downX = -1f
        private var headerCache: Set<Int> = emptySet()
        private var headerVersion = -1

        /** The lines that start a foldable region, worked out again only when the text has changed. */
        private fun headers(): Set<Int> {
            if (headerVersion != textVersion) { headerVersion = textVersion; headerCache = try { foldRegions().map { it.first }.toSet() } catch (e: Exception) { emptySet() } }
            return headerCache
        }

        fun onLinesChanged() {
            val lines = lineCount()
            val d = lines.toString().length.coerceAtLeast(3)
            if (d != digits) { digits = d; requestLayout() }
            invalidate()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            paint.textSize = edit.textSize
            val w = paddingLeft + paddingRight + markZone + foldZone + (paint.measureText("0") * digits).toInt()
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
            val currentLayout = l.getLineForOffset(edit.selectionEnd.coerceIn(0, edit.text.length))
            val current = logicalOfLayout(currentLayout)
            val x = (width - paddingRight - foldZone).toFloat()
            val breakpoints = if (path.isNotEmpty()) Breakpoints.lines(path) else emptySet()
            val cx = paddingLeft / 2f + markZone / 2f
            val radius = Ui.dp(context, 4).toFloat()
            val heads = headers()
            for (li in first..last) {
                // Hidden (folded) lines have no height; a wrapped line's continuation shows no number.
                if (isHiddenLine(li) || !startsLogicalLine(li)) continue
                val i = logicalOfLayout(li)
                val top = (l.getLineTop(li) + padTop - sy).toFloat()
                val bottom = (l.getLineBottom(li) + padTop - sy).toFloat()
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
                canvas.drawText((i + 1).toString(), x, (l.getLineBaseline(li) + padTop - sy).toFloat(), paint)
                val cy = (top + bottom) / 2f
                if (i in heads) {
                    // ▾ for an open region, ▸ for a folded one, like VS Code's fold controls.
                    val fx = width - paddingRight - foldZone / 2f
                    val k = Ui.dp(context, 3).toFloat()
                    val p = Path()
                    if (isFoldHeader(i)) { p.moveTo(fx - k / 2, cy - k); p.lineTo(fx + k / 2, cy); p.lineTo(fx - k / 2, cy + k) }
                    else { p.moveTo(fx - k, cy - k / 2); p.lineTo(fx, cy + k / 2); p.lineTo(fx + k, cy - k / 2) }
                    chevron.color = if (isFoldHeader(i)) Ui.fg else Ui.fg3
                    canvas.drawPath(p, chevron)
                }
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
                MotionEvent.ACTION_DOWN -> { downY = event.y; downX = event.x; return true }
                MotionEvent.ACTION_UP -> {
                    if (downY >= 0 && Math.abs(event.y - downY) < Ui.dp(context, 8)) {
                        val l = edit.layout
                        if (l != null) {
                            val li = l.getLineForVertical((event.y.toInt() + edit.scrollY - edit.extendedPaddingTop).coerceAtLeast(0))
                            val line = logicalOfLayout(li)
                            // The fold zone (right of the numbers) folds; the rest of the gutter sets breakpoints.
                            if (downX >= width - paddingRight - foldZone) { if (toggleFoldAtLine(line)) invalidate() }
                            else if (li < l.lineCount && path.isNotEmpty()) { Breakpoints.toggle(path, line + 1); invalidate() }
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
                val first = logicalOfLayout(l.getLineForVertical(edit.scrollY))
                val last = logicalOfLayout(l.getLineForVertical(edit.scrollY + edit.height))
                val top = first * p; val bottom = (last + 1) * p
                canvas.drawRect(0f, top, w, bottom, band)
                canvas.drawRect(0f, top, w, top + 1f, bandEdge); canvas.drawRect(0f, bottom - 1f, w, bottom, bandEdge)
                val cur = logicalLineAt(edit.selectionEnd.coerceIn(0, edit.text.length))
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
                    val target = (l.getLineTop(layoutLineOf(line)) - edit.height / 2).coerceIn(0, maxScrollY())
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
