package local.forge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a TerminalEmulator's grid with a monospace Paint and turns the laptop's keys into the bytes a terminal
 * sends. It looks like the web page's xterm: the dark Forge background, the same 16 colors, a block cursor in the
 * accent color. The mouse wheel and touch drags move through the scrollback; a tap gives the view the keyboard.
 * While a program asks for mouse reports (vim, htop, tmux, less with --mouse), the mouse's presses, releases,
 * drags, moves and wheel go to it as xterm reports them (classic, UTF-8, SGR or urxvt encoding), and Shift with
 * the mouse still selects text and opens the menu here.
 * A hardware keyboard is the normal case on this laptop, so input arrives through onKeyDown; the input connection
 * only exists so an input method that commits text (dead keys, compose sequences) still reaches the shell.
 */
class TerminalView(ctx: Context) : View(ctx) {
    companion object {
        private const val BLINK_MS = 530L
        /** The web page's xterm theme (static/term.js): the normal eight colors then the bright eight. */
        private val BASE16 = intArrayOf(
            0xff1a1e2a.toInt(), 0xffff6b6b.toInt(), 0xff4fd18b.toInt(), 0xffffd166.toInt(),
            0xff6ea8fe.toInt(), 0xffb48cff.toInt(), 0xff5ad3d3.toInt(), 0xffd7dbe7.toInt(),
            0xff5b6378.toInt(), 0xffff8787.toInt(), 0xff7ee2a8.toInt(), 0xffffe08a.toInt(),
            0xff8fbcff.toInt(), 0xffc9aaff.toInt(), 0xff86e3e3.toInt(), 0xffffffff.toInt())
        private val PALETTE = IntArray(256).also { p ->
            for (i in 0 until 16) p[i] = BASE16[i]
            // The 6 by 6 by 6 color cube and the 24 grays of xterm's 256 color mode.
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            for (i in 0 until 216) p[16 + i] = Color.rgb(steps[i / 36], steps[(i / 6) % 6], steps[i % 6])
            for (i in 0 until 24) { val v = 8 + i * 10; p[232 + i] = Color.rgb(v, v, v) }
        }
        private val FG = Ui.fg
        private val BG = Ui.bg
        private val CURSOR = Ui.accent
    }

    val term = TerminalEmulator(80, 24)
    /** Bytes for the shell (typed keys, pasted text, the emulator's replies). */
    var onInput: ((String) -> Unit)? = null
    /** Called with the new grid size after a layout change. */
    var onResize: ((Int, Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).also {
        it.typeface = Typeface.MONOSPACE
        it.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12.5f, resources.displayMetrics)
    }
    private val fill = Paint()
    private var cellW = 1f
    private var cellH = 1
    private var baseline = 1f
    private var cols = 80
    private var rows = 24

    /** How many lines above the live screen the view is scrolled; 0 shows the live screen. */
    private var scrollOffset = 0
    private var cursorOn = true
    private val handler = Handler(Looper.getMainLooper())
    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            if (isFocused && isAttachedToWindow && windowVisibility == VISIBLE) handler.postDelayed(this, BLINK_MS)
        }
    }
    private var drawnVersion = -1L
    private var touchY = 0f
    private var touchMoved = false
    private val runText = StringBuilder()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        setBackgroundColor(BG)
        val fm = paint.fontMetrics
        cellH = ceil((fm.descent - fm.ascent).toDouble()).toInt().coerceAtLeast(1)
        baseline = -fm.ascent
        cellW = paint.measureText("MMMMMMMMMM") / 10f
        term.onResponse = { s -> onInput?.invoke(s) }
    }

    /** The text size in sp (Settings, Editor font size); the grid is measured again and the shell learns its new size. */
    fun setFontSize(sp: Float) {
        if (sp < 6f || sp > 40f) return
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        if (Math.abs(paint.textSize - px) < 0.5f) return
        paint.textSize = px
        val fm = paint.fontMetrics
        cellH = ceil((fm.descent - fm.ascent).toDouble()).toInt().coerceAtLeast(1)
        baseline = -fm.ascent
        cellW = paint.measureText("MMMMMMMMMM") / 10f
        if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
        invalidate()
    }

    /** Feeds output from the shell and redraws. */
    fun write(s: String) {
        term.write(s)
        if (scrollOffset > term.scrollbackSize) scrollOffset = term.scrollbackSize
        restartBlink()
        invalidate()
    }

    fun clear() { term.clear(); scrollOffset = 0; invalidate() }

    fun reset() { term.reset(); scrollOffset = 0; invalidate() }

    val gridCols: Int get() = cols
    val gridRows: Int get() = rows

    // ------------------------------------------------------------------ selection and text
    /** Called with an action the terminal's right-click menu asked for: "add-to-chat" with the selected or recent text. */
    var onMenuAction: ((String, String) -> Unit)? = null

    /** A selection in the combined line space (0 is the oldest scrollback line), anchor and moving end, as (line, column). */
    private var selAnchor: Pair<Int, Int>? = null
    private var selHead: Pair<Int, Int>? = null
    private var selecting = false
    private val selPaint = Paint().also { it.color = 0x55ff8a3d }

    fun hasSelection(): Boolean { val a = selAnchor; val b = selHead; return a != null && b != null && a != b }
    fun clearSelection() { if (selAnchor != null) { selAnchor = null; selHead = null; invalidate() } }

    private fun ordered(): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val a = selAnchor ?: return null; val b = selHead ?: return null
        return if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a to b else b to a
    }

    private fun lineString(index: Int): String {
        val line = term.lineAt(index) ?: return ""
        val sb = StringBuilder()
        for (i in 0 until line.size) { val cp = line.text[i]; if (cp != TerminalEmulator.WIDE_FILLER) sb.appendCodePoint(cp) }
        return sb.toString().trimEnd()
    }

    /** The selected text, lines joined with newlines and trailing blanks removed. */
    fun selectedText(): String {
        val (a, b) = ordered() ?: return ""
        val out = StringBuilder()
        for (ln in a.first..b.first) {
            val full = lineString(ln)
            val from = if (ln == a.first) a.second.coerceAtMost(full.length) else 0
            val to = if (ln == b.first) b.second.coerceAtMost(full.length) else full.length
            if (to > from) out.append(full, from, to)
            if (ln != b.first) out.append('\n')
        }
        return out.toString()
    }

    /** The last lines of the terminal (scrollback and screen), without trailing empty lines: @terminal context. */
    fun recentText(maxLines: Int = 200): String {
        val total = term.scrollbackSize + term.rows
        var last = total - 1
        while (last >= 0 && lineString(last).isEmpty()) last--
        if (last < 0) return ""
        val first = (last - maxLines + 1).coerceAtLeast(0)
        return (first..last).joinToString("\n") { lineString(it) }
    }

    fun selectAll() {
        val total = term.scrollbackSize + term.rows
        selAnchor = 0 to 0; selHead = (total - 1) to cols
        invalidate()
    }

    fun copySelection(): Boolean {
        val t = selectedText()
        if (t.isEmpty()) return false
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Terminal", t))
        return true
    }

    /** The cell under a point, in the combined line space. */
    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val total = term.scrollbackSize + term.rows
        val first = total - term.rows - scrollOffset
        val r = ((y - paddingTop) / cellH).toInt().coerceIn(0, rows - 1)
        val c = ((x - paddingLeft) / cellW).roundToInt().coerceIn(0, cols)
        return (first + r) to c
    }

    private fun showMenu(x: Float, y: Float) {
        val menu = PopupMenu(context, this)
        val copy = menu.menu.add("Copy  (Ctrl Shift C)"); copy.isEnabled = hasSelection()
        menu.menu.add("Paste  (Ctrl Shift V)")
        val ask = menu.menu.add(if (hasSelection()) "Add the selection to the chat" else "Add the recent output to the chat")
        menu.menu.add("Select all")
        menu.menu.add("Clear the terminal")
        menu.setOnMenuItemClickListener { item ->
            when (item.title.toString().substringBefore("  (")) {
                "Copy" -> copySelection()
                "Paste" -> paste()
                "Select all" -> selectAll()
                "Clear the terminal" -> { clear(); clearSelection() }
                else -> if (item === ask) onMenuAction?.invoke("add-to-chat", if (hasSelection()) selectedText() else recentText(120))
            }
            true
        }
        menu.show()
    }

    // ------------------------------------------------------------------ size
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val innerW = w - paddingLeft - paddingRight
        val innerH = h - paddingTop - paddingBottom
        if (innerW <= 0 || innerH <= 0) return
        val c = (innerW / cellW.coerceAtLeast(1f)).toInt().coerceIn(2, 1000)
        val r = (innerH / cellH.coerceAtLeast(1)).coerceIn(1, 500)
        if (c == cols && r == rows && term.cols == c && term.rows == r) return
        cols = c; rows = r
        term.resize(c, r)
        if (scrollOffset > term.scrollbackSize) scrollOffset = term.scrollbackSize
        onResize?.invoke(c, r)
        invalidate()
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawnVersion = term.version
        val total = term.scrollbackSize + term.rows
        val first = total - term.rows - scrollOffset
        val x0 = paddingLeft.toFloat()
        val y0 = paddingTop.toFloat()
        for (r in 0 until rows) {
            val line = term.lineAt(first + r) ?: continue
            drawLine(canvas, line, x0, y0 + r * cellH)
        }
        if (scrollOffset == 0 && term.cursorVisible && term.cursorY < rows) drawCursor(canvas, x0, y0, first)
        drawSelection(canvas, x0, y0, first)
        if (total > rows) drawScrollbar(canvas)
    }

    private fun drawSelection(canvas: Canvas, x0: Float, y0: Float, first: Int) {
        val (a, b) = ordered() ?: return
        if (a == b) return
        for (r in 0 until rows) {
            val ln = first + r
            if (ln < a.first || ln > b.first) continue
            val from = if (ln == a.first) a.second else 0
            val to = if (ln == b.first) b.second else cols
            if (to <= from) continue
            canvas.drawRect(x0 + from * cellW, y0 + r * cellH, x0 + to * cellW, y0 + (r + 1) * cellH, selPaint)
        }
    }

    private fun drawLine(canvas: Canvas, line: TerminalEmulator.Line, x0: Float, top: Float) {
        val n = minOf(line.size, cols)
        var x = 0
        val bottom = top + cellH
        while (x < n) {
            val fg = line.fg[x]; val bg = line.bg[x]; val attr = line.attr[x]
            // One run: the cells to the right with the same style, cut before any wide or unusual glyph so cell
            // positions stay exact even when the font's advance for that glyph is not one cell.
            var end = x + 1
            val narrow = line.text[x] < 0x2e80
            if (narrow) while (end < n && line.fg[end] == fg && line.bg[end] == bg && line.attr[end] == attr && line.text[end] < 0x2e80) end++
            val inverse = attr and TerminalEmulator.INVERSE != 0
            val bold = attr and TerminalEmulator.BOLD != 0
            var fgColor = color(fg, true, bold)
            var bgColor = color(bg, false, false)
            if (inverse) { val t = fgColor; fgColor = bgColor; bgColor = t }
            if (bgColor != BG) {
                fill.color = bgColor
                canvas.drawRect(x0 + x * cellW, top, x0 + end * cellW, bottom, fill)
            }
            if (attr and TerminalEmulator.HIDDEN == 0) {
                runText.setLength(0)
                var blank = true
                for (i in x until end) {
                    val cp = line.text[i]
                    if (cp == TerminalEmulator.WIDE_FILLER) { runText.append(' '); continue }
                    if (cp != ' '.code) blank = false
                    runText.appendCodePoint(cp)
                }
                if (!blank) {
                    paint.color = fgColor
                    paint.isFakeBoldText = bold
                    paint.textSkewX = if (attr and TerminalEmulator.ITALIC != 0) -0.25f else 0f
                    paint.alpha = if (attr and TerminalEmulator.DIM != 0) 140 else 255
                    canvas.drawText(runText, 0, runText.length, x0 + x * cellW, top + baseline, paint)
                }
                if (attr and TerminalEmulator.UNDERLINE != 0) {
                    fill.color = fgColor
                    val uy = top + baseline + 2f
                    canvas.drawRect(x0 + x * cellW, uy, x0 + end * cellW, uy + 1f, fill)
                }
            }
            x = end
        }
    }

    private fun drawCursor(canvas: Canvas, x0: Float, y0: Float, first: Int) {
        val cx = term.cursorX; val cy = term.cursorY
        val left = x0 + cx * cellW; val top = y0 + cy * cellH
        val style = term.cursorStyle
        if (!isFocused) {
            // An unfocused terminal shows a hollow block, like xterm.
            fill.color = CURSOR
            canvas.drawRect(left, top, left + cellW, top + 1f, fill)
            canvas.drawRect(left, top + cellH - 1f, left + cellW, top + cellH, fill)
            canvas.drawRect(left, top, left + 1f, top + cellH, fill)
            canvas.drawRect(left + cellW - 1f, top, left + cellW, top + cellH, fill)
            return
        }
        if (!cursorOn) return
        fill.color = CURSOR
        when (style) {
            3, 4 -> { canvas.drawRect(left, top + cellH - 2f, left + cellW, top + cellH, fill); return }
            5, 6 -> { canvas.drawRect(left, top, left + 2f, top + cellH, fill); return }
        }
        canvas.drawRect(left, top, left + cellW, top + cellH, fill)
        val line = term.lineAt(first + cy) ?: return
        if (cx < line.size) {
            val cp = line.text[cx]
            if (cp > ' '.code) {
                runText.setLength(0); runText.appendCodePoint(cp)
                paint.color = BG; paint.isFakeBoldText = false; paint.textSkewX = 0f; paint.alpha = 255
                canvas.drawText(runText, 0, runText.length, left, top + baseline, paint)
            }
        }
    }

    private fun drawScrollbar(canvas: Canvas) {
        val total = term.scrollbackSize + term.rows
        val trackH = (height - paddingTop - paddingBottom).toFloat()
        val thumbH = maxOf(Ui.dp(context, 24).toFloat(), trackH * rows / total)
        val firstLine = total - rows - scrollOffset
        val thumbTop = paddingTop + (trackH - thumbH) * firstLine / maxOf(1, total - rows)
        fill.color = if (scrollOffset > 0) 0x80ff8a3d.toInt() else 0x40ffffff
        val w = Ui.dp(context, 3).toFloat()
        canvas.drawRect(width - w - 1f, thumbTop, width - 1f, thumbTop + thumbH, fill)
    }

    private fun color(c: Int, isFg: Boolean, bold: Boolean): Int = when {
        c == TerminalEmulator.DEFAULT_COLOR -> if (isFg) FG else BG
        c and TerminalEmulator.RGB_FLAG != 0 -> 0xff000000.toInt() or (c and 0xffffff)
        c < 8 && isFg && bold -> PALETTE[c + 8]
        else -> PALETTE[c and 0xff]
    }

    // ------------------------------------------------------------------ cursor blinking and focus
    private fun restartBlink() {
        handler.removeCallbacks(blink)
        cursorOn = true
        if (isFocused && isAttachedToWindow && windowVisibility == VISIBLE) handler.postDelayed(blink, BLINK_MS)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        restartBlink(); invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restartBlink() }
    override fun onDetachedFromWindow() { handler.removeCallbacks(blink); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); restartBlink() }

    // ------------------------------------------------------------------ scrolling with the wheel or a finger
    /** Moves the view through the scrollback; positive goes back in time. */
    fun scrollLines(delta: Int) {
        val target = (scrollOffset + delta).coerceIn(0, term.scrollbackSize)
        if (target == scrollOffset) return
        scrollOffset = target
        invalidate()
    }

    fun scrollToBottom() { if (scrollOffset != 0) { scrollOffset = 0; invalidate() } }

    // ------------------------------------------------------------------ mouse reports for programs (xterm's protocol)
    /** The button held since the last report press (0 left, 1 middle, 2 right), or -1. */
    private var mouseButton = -1
    private var lastMouseCell = -1
    private var wheelRest = 0f

    /** The live screen's cell under a point, 1-based (column, row), as mouse reports count them. */
    private fun reportCell(x: Float, y: Float): Pair<Int, Int> =
        Pair(((x - paddingLeft) / cellW).toInt().coerceIn(0, cols - 1) + 1, ((y - paddingTop) / cellH).toInt().coerceIn(0, rows - 1) + 1)

    private fun buttonOf(state: Int): Int = when {
        state and MotionEvent.BUTTON_SECONDARY != 0 -> 2
        state and MotionEvent.BUTTON_TERTIARY != 0 -> 1
        else -> 0
    }

    /** Shift 4, Alt 8 and Ctrl 16 added to the button code, as xterm reports them (not in X10 mode). */
    private fun modifierBits(meta: Int): Int {
        if (term.mouseMode == 9) return 0
        var m = 0
        if (meta and KeyEvent.META_SHIFT_ON != 0) m += 4
        if (meta and KeyEvent.META_ALT_ON != 0) m += 8
        if (meta and KeyEvent.META_CTRL_ON != 0) m += 16
        return m
    }

    /**
     * Sends one mouse report in the encoding the program chose. [code] is the button code (0 to 2 a button, 64 and 65
     * the wheel, plus 32 for a move, plus the modifier bits); [release] marks a button's release. The classic encoding
     * writes positions as single characters, which a UTF-8 terminal can carry only up to 94, so beyond that it
     * reports nothing (programs that want large screens ask for the SGR encoding).
     */
    private fun sendMouse(code: Int, col: Int, row: Int, release: Boolean) {
        val s = when (term.mouseEncoding) {
            1006 -> "\u001b[<$code;$col;$row" + (if (release) "m" else "M")
            1015 -> "\u001b[" + (32 + (if (release) 3 or (code and 28) else code)) + ";$col;${row}M"
            1005 -> "\u001b[M" + (32 + (if (release) 3 or (code and 28) else code)).toChar() + (32 + col).toChar() + (32 + row).toChar()
            else -> if (col > 94 || row > 94) null else "\u001b[M" + (32 + (if (release) 3 or (code and 28) else code)).toChar() + (32 + col).toChar() + (32 + row).toChar()
        } ?: return
        send(s)
    }

    /** A mouse press, move or release for a program that asked for reports. Returns false when the event is not one. */
    private fun mouseToProgram(event: MotionEvent): Boolean {
        val (col, row) = reportCell(event.x, event.y)
        val mods = modifierBits(event.metaState)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isFocused) requestFocus()
                mouseButton = buttonOf(event.buttonState)
                lastMouseCell = row * 1000 + col
                sendMouse(mouseButton + mods, col, row, false)
            }
            MotionEvent.ACTION_MOVE -> {
                val cell = row * 1000 + col
                if (mouseButton >= 0 && cell != lastMouseCell && (term.mouseMode == 1002 || term.mouseMode == 1003)) {
                    lastMouseCell = cell
                    sendMouse(mouseButton + 32 + mods, col, row, false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mouseButton >= 0 && term.mouseMode != 9) sendMouse(mouseButton + mods, col, row, true)
                mouseButton = -1
            }
            else -> return false
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (term.mouseTracking && event.actionMasked == MotionEvent.ACTION_HOVER_MOVE && term.mouseMode == 1003) {
            // Any-event tracking: moves without a button are reported too (button code 3 plus 32).
            val (col, row) = reportCell(event.x, event.y)
            val cell = row * 1000 + col
            if (cell != lastMouseCell) { lastMouseCell = cell; sendMouse(35 + modifierBits(event.metaState), col, row, false) }
            return true
        }
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v == 0f) return true
            if (term.mouseTracking && term.mouseMode != 9) {
                // The wheel goes to the program as buttons 64 (up) and 65 (down), one report per notch; a touchpad's
                // small steps add up to whole notches first.
                wheelRest += v
                val (col, row) = reportCell(event.x, event.y)
                val mods = modifierBits(event.metaState)
                var n = 0
                while (wheelRest >= 1f && n < 10) { sendMouse(64 + mods, col, row, false); wheelRest -= 1f; n++ }
                while (wheelRest <= -1f && n < 10) { sendMouse(65 + mods, col, row, false); wheelRest += 1f; n++ }
                if (n == 10) wheelRest = 0f
                return true
            }
            val steps = (v * 3).roundToInt().let { if (it == 0) (if (v > 0) 1 else -1) else it }
            if (term.altScreen && !term.mouseTracking) {
                // Full screen programs have no scrollback; the wheel moves their cursor instead, as many terminals do.
                val key = if (steps > 0) (if (term.appCursorKeys) "\u001bOA" else "\u001b[A") else (if (term.appCursorKeys) "\u001bOB" else "\u001b[B")
                val s = StringBuilder(); for (i in 0 until abs(steps)) s.append(key)
                send(s.toString())
            } else scrollLines(steps)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        // While a program asked for mouse reports, the mouse belongs to it; Shift keeps selecting and the menu here, as in xterm.
        val shift = event.metaState and KeyEvent.META_SHIFT_ON != 0
        if (mouse && (term.mouseTracking || mouseButton >= 0) && !shift && mouseToProgram(event)) return true
        if (mouse) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isFocused) requestFocus()
                    if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) { showMenu(event.x, event.y); return true }
                    if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) { paste(); return true }
                    val cell = cellAt(event.x, event.y)
                    selAnchor = cell; selHead = cell; selecting = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> { if (selecting) { selHead = cellAt(event.x, event.y); invalidate() }; return true }
                MotionEvent.ACTION_UP -> {
                    if (selecting) { selecting = false; selHead = cellAt(event.x, event.y); if (!hasSelection()) { clearSelection(); performClick() } else invalidate() }
                    return true
                }
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchY = event.y; touchMoved = false
                if (!isFocused) requestFocus()
                if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 || event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) paste()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - touchY
                val steps = (dy / cellH).toInt()
                if (steps != 0) { touchMoved = true; scrollLines(steps); touchY += steps * cellH }
                return true
            }
            MotionEvent.ACTION_UP -> { if (!touchMoved) performClick(); return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // ------------------------------------------------------------------ the keyboard
    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL: no text field for the input method to manage, so hardware keys arrive as key events and the
        // soft keyboard is not asked for. Text an input method commits (dead key compositions) still goes through.
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this@TerminalView, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null && text.isNotEmpty()) send(text.toString())
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val s = StringBuilder(); for (i in 0 until beforeLength.coerceIn(0, 64)) s.append('\u007f')
                if (s.isNotEmpty()) send(s.toString())
                return true
            }
        }
    }

    private fun send(s: String) {
        scrollToBottom()
        restartBlink()
        onInput?.invoke(s)
    }

    /** Sends the clipboard's text as typed input, wrapped for bracketed paste when the program asked for it. */
    fun paste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isEmpty()) return
        val body = text.replace("\r\n", "\r").replace('\n', '\r')
        send(if (term.bracketedPaste) "\u001b[200~$body\u001b[201~" else body)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        // Shortcuts that belong to the window, not to the shell: Ctrl ` (terminal), Ctrl P (quick open),
        // Ctrl O (open folder) and Ctrl Shift N (new window) pass through to MainActivity.
        if (ctrl && (keyCode == KeyEvent.KEYCODE_GRAVE || keyCode == KeyEvent.KEYCODE_P || keyCode == KeyEvent.KEYCODE_O ||
                (shift && keyCode == KeyEvent.KEYCODE_N))) return super.onKeyDown(keyCode, event)
        if (ctrl && shift && keyCode == KeyEvent.KEYCODE_V) { paste(); return true }
        if (ctrl && shift && keyCode == KeyEvent.KEYCODE_C) { copySelection(); return true }
        if (ctrl && !shift && keyCode == KeyEvent.KEYCODE_C && hasSelection()) { copySelection(); clearSelection(); return true }
        if (ctrl && shift && keyCode == KeyEvent.KEYCODE_A) { selectAll(); return true }
        if (!ctrl && !alt && hasSelection() && keyCode != KeyEvent.KEYCODE_SHIFT_LEFT && keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT) clearSelection()
        if (shift && keyCode == KeyEvent.KEYCODE_INSERT) { paste(); return true }
        if (shift && keyCode == KeyEvent.KEYCODE_PAGE_UP) { scrollLines(rows - 1); return true }
        if (shift && keyCode == KeyEvent.KEYCODE_PAGE_DOWN) { scrollLines(-(rows - 1)); return true }
        // Cursor's terminal keys: Ctrl Shift Up and Down scroll a line, Shift Home and End go to the top and the bottom.
        if (ctrl && shift && keyCode == KeyEvent.KEYCODE_DPAD_UP) { scrollLines(1); return true }
        if (ctrl && shift && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { scrollLines(-1); return true }
        if (shift && !ctrl && keyCode == KeyEvent.KEYCODE_MOVE_HOME) { scrollLines(term.scrollbackSize); return true }
        if (shift && !ctrl && keyCode == KeyEvent.KEYCODE_MOVE_END) { scrollToBottom(); return true }

        val mod = modifierParam(shift, alt, ctrl)
        val app = term.appCursorKeys
        val special: String? = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (alt) "\u001b\r" else "\r"
            KeyEvent.KEYCODE_DEL -> if (alt) "\u001b\u007f" else if (ctrl) "\u0008" else "\u007f"
            KeyEvent.KEYCODE_TAB -> if (shift) "\u001b[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> cursorKey('A', app, mod)
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorKey('B', app, mod)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorKey('C', app, mod)
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorKey('D', app, mod)
            KeyEvent.KEYCODE_MOVE_HOME -> cursorKey('H', app, mod)
            KeyEvent.KEYCODE_MOVE_END -> cursorKey('F', app, mod)
            KeyEvent.KEYCODE_INSERT -> tildeKey(2, mod)
            KeyEvent.KEYCODE_FORWARD_DEL -> tildeKey(3, mod)
            KeyEvent.KEYCODE_PAGE_UP -> tildeKey(5, mod)
            KeyEvent.KEYCODE_PAGE_DOWN -> tildeKey(6, mod)
            KeyEvent.KEYCODE_F1 -> if (mod == 0) "\u001bOP" else "\u001b[1;${mod}P"
            KeyEvent.KEYCODE_F2 -> if (mod == 0) "\u001bOQ" else "\u001b[1;${mod}Q"
            KeyEvent.KEYCODE_F3 -> if (mod == 0) "\u001bOR" else "\u001b[1;${mod}R"
            KeyEvent.KEYCODE_F4 -> if (mod == 0) "\u001bOS" else "\u001b[1;${mod}S"
            KeyEvent.KEYCODE_F5 -> tildeKey(15, mod)
            KeyEvent.KEYCODE_F6 -> tildeKey(17, mod)
            KeyEvent.KEYCODE_F7 -> tildeKey(18, mod)
            KeyEvent.KEYCODE_F8 -> tildeKey(19, mod)
            KeyEvent.KEYCODE_F9 -> tildeKey(20, mod)
            KeyEvent.KEYCODE_F10 -> tildeKey(21, mod)
            KeyEvent.KEYCODE_F11 -> tildeKey(23, mod)
            KeyEvent.KEYCODE_F12 -> tildeKey(24, mod)
            else -> null
        }
        if (special != null) { send(special); return true }

        if (ctrl) {
            val code: Int = when {
                keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> keyCode - KeyEvent.KEYCODE_A + 1
                keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_AT || keyCode == KeyEvent.KEYCODE_2 -> 0
                keyCode == KeyEvent.KEYCODE_LEFT_BRACKET || keyCode == KeyEvent.KEYCODE_3 -> 0x1b
                keyCode == KeyEvent.KEYCODE_BACKSLASH || keyCode == KeyEvent.KEYCODE_4 -> 0x1c
                keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET || keyCode == KeyEvent.KEYCODE_5 -> 0x1d
                keyCode == KeyEvent.KEYCODE_6 -> 0x1e
                keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_7 || keyCode == KeyEvent.KEYCODE_SLASH -> 0x1f
                keyCode == KeyEvent.KEYCODE_8 -> 0x7f
                else -> -1
            }
            if (code >= 0) {
                val s = code.toChar().toString()
                send(if (alt) "\u001b$s" else s)
                return true
            }
            // Ctrl with a key that has no control code (Ctrl Shift S, Ctrl 1, ...) is swallowed rather than typed.
            if (keyCode != KeyEvent.KEYCODE_CTRL_LEFT && keyCode != KeyEvent.KEYCODE_CTRL_RIGHT) return true
            return super.onKeyDown(keyCode, event)
        }

        val meta = event.metaState and (KeyEvent.META_ALT_MASK or KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv()
        val uni = event.getUnicodeChar(meta)
        if (uni and android.view.KeyCharacterMap.COMBINING_ACCENT != 0) return true   // a dead key: wait for the composed character
        if (uni > 0 && uni != 0x7f && (uni >= 0x20)) {
            val s = StringBuilder().appendCodePoint(uni).toString()
            send(if (alt) "\u001b$s" else s)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    /** xterm's modifier parameter: 1 plus Shift (1), Alt (2) and Ctrl (4); 0 when none is held. */
    private fun modifierParam(shift: Boolean, alt: Boolean, ctrl: Boolean): Int {
        var m = 0
        if (shift) m += 1
        if (alt) m += 2
        if (ctrl) m += 4
        return if (m == 0) 0 else m + 1
    }

    private fun cursorKey(final: Char, app: Boolean, mod: Int): String = when {
        mod != 0 -> "\u001b[1;$mod$final"
        app -> "\u001bO$final"
        else -> "\u001b[$final"
    }

    private fun tildeKey(code: Int, mod: Int): String = if (mod == 0) "\u001b[$code~" else "\u001b[$code;$mod~"
}
