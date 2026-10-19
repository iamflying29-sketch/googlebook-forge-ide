package local.forge

/**
 * A screen model for a VT100/xterm style terminal: a grid of rows by cols cells plus a scrollback, fed with the
 * bytes a shell writes (already decoded to text) and read by TerminalView, which draws it. It understands the
 * control sequences the programs on this laptop use (bash, ls, git, vim, less, top, htop, tmux, python, kotlinc):
 * cursor movement, erasing, inserting and deleting, scroll regions, SGR colors including 256 and 24-bit colors,
 * the alternate screen, cursor save and restore, DEC private modes, OSC strings and device status reports.
 * Unknown sequences are consumed and never printed. Everything runs on the thread that calls write(), which in
 * Forge is the main thread.
 */
class TerminalEmulator(cols: Int, rows: Int, private val maxScrollback: Int = 5000) {
    companion object {
        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val INVERSE = 16
        const val HIDDEN = 32
        /** A cell color that means "the terminal's default foreground or background". */
        const val DEFAULT_COLOR = -1
        /** Bit 24 marks a 24-bit color in the low three bytes; values 0 to 255 are palette indices. */
        const val RGB_FLAG = 0x1000000
        /** The code point stored in the right half of a wide (two cell) character. */
        const val WIDE_FILLER = 0

        private const val GROUND = 0
        private const val ESC = 1
        private const val CSI = 2
        private const val OSC = 3
        private const val OSC_ESC = 4
        private const val STRING = 5
        private const val STRING_ESC = 6
        private const val CHARSET = 7
        private const val ESC_HASH = 8
        private const val ESC_SKIP = 9
        private const val NONE = '\u0000'

        /** The DEC special graphics set (ESC ( 0): box drawing for tree, tmux and dialog programs. */
        private val LINE_DRAWING = mapOf(
            '`' to '◆', 'a' to '▒', 'b' to '␉', 'c' to '␌', 'd' to '␍', 'e' to '␊',
            'f' to '°', 'g' to '±', 'h' to '␤', 'i' to '␋', 'j' to '┘', 'k' to '┐',
            'l' to '┌', 'm' to '└', 'n' to '┼', 'o' to '⎺', 'p' to '⎻', 'q' to '─',
            'r' to '⎼', 's' to '⎽', 't' to '├', 'u' to '┤', 'v' to '┴', 'w' to '┬',
            'x' to '│', 'y' to '≤', 'z' to '≥', '{' to 'π', '|' to '≠', '}' to '£', '~' to '·')

        /** True for code points that take two cells (East Asian wide and full width characters, most emoji). */
        fun isWide(cp: Int): Boolean {
            if (cp < 0x1100) return false
            return (cp in 0x1100..0x115F) || (cp in 0x2E80..0x303E) || (cp in 0x3041..0x33FF) || (cp in 0x3400..0x4DBF) ||
                (cp in 0x4E00..0x9FFF) || (cp in 0xA000..0xA4CF) || (cp in 0xAC00..0xD7A3) || (cp in 0xF900..0xFAFF) ||
                (cp in 0xFE30..0xFE4F) || (cp in 0xFF00..0xFF60) || (cp in 0xFFE0..0xFFE6) || (cp in 0x1F300..0x1F64F) ||
                (cp in 0x1F680..0x1F6FF) || (cp in 0x1F900..0x1F9FF) || (cp in 0x20000..0x3FFFD)
        }
    }

    /** One row of cells. A line kept in the scrollback is trimmed to its used width, so its size can be below cols. */
    class Line(cols: Int) {
        var text = IntArray(cols).also { it.fill(' '.code) }
        var fg = IntArray(cols).also { it.fill(DEFAULT_COLOR) }
        var bg = IntArray(cols).also { it.fill(DEFAULT_COLOR) }
        var attr = IntArray(cols)
        /** True when the line ended because text wrapped onto the next line (kept for a future copy feature). */
        var wrapped = false
        val size: Int get() = text.size

        fun set(x: Int, cp: Int, f: Int, b: Int, a: Int) { text[x] = cp; fg[x] = f; bg[x] = b; attr[x] = a }

        /** Erases the cells from (inclusive) to (exclusive), leaving the given background, as xterm does. */
        fun erase(from: Int, to: Int, b: Int) {
            val lo = maxOf(0, from); val hi = minOf(size, to)
            if (lo >= hi) return
            text.fill(' '.code, lo, hi); fg.fill(DEFAULT_COLOR, lo, hi); bg.fill(b, lo, hi); attr.fill(0, lo, hi)
        }

        fun resize(n: Int) {
            if (n == size) return
            val old = size
            text = text.copyOf(n); fg = fg.copyOf(n); bg = bg.copyOf(n); attr = attr.copyOf(n)
            if (n > old) { text.fill(' '.code, old, n); fg.fill(DEFAULT_COLOR, old, n); bg.fill(DEFAULT_COLOR, old, n) }
        }

        fun isBlank(): Boolean {
            for (i in 0 until size) if (text[i] != ' '.code || bg[i] != DEFAULT_COLOR) return false
            return true
        }

        /** The line cut down to its last used cell, for the scrollback. */
        fun trimmed(): Line {
            var last = size - 1
            while (last >= 0 && text[last] == ' '.code && bg[last] == DEFAULT_COLOR && attr[last] == 0) last--
            if (last == size - 1) return this
            resize(last + 1)
            return this
        }

        fun insertBlank(x: Int, n: Int, b: Int) {
            if (x < 0 || x >= size) return
            val count = minOf(n, size - x)
            System.arraycopy(text, x, text, x + count, size - x - count)
            System.arraycopy(fg, x, fg, x + count, size - x - count)
            System.arraycopy(bg, x, bg, x + count, size - x - count)
            System.arraycopy(attr, x, attr, x + count, size - x - count)
            erase(x, x + count, b)
        }

        fun delete(x: Int, n: Int, b: Int) {
            if (x < 0 || x >= size) return
            val count = minOf(n, size - x)
            System.arraycopy(text, x + count, text, x, size - x - count)
            System.arraycopy(fg, x + count, fg, x, size - x - count)
            System.arraycopy(bg, x + count, bg, x, size - x - count)
            System.arraycopy(attr, x + count, attr, x, size - x - count)
            erase(size - count, size, b)
        }
    }

    var cols = maxOf(2, cols); private set
    var rows = maxOf(1, rows); private set

    private var lines = ArrayList<Line>(this.rows).also { for (i in 0 until this.rows) it.add(Line(this.cols)) }
    private val scrollback = ArrayDeque<Line>()
    private var savedMain: ArrayList<Line>? = null
    /** True while a full screen program (vim, less, htop) shows the alternate screen, which has no scrollback. */
    var altScreen = false; private set

    var cursorX = 0; private set
    var cursorY = 0; private set
    var cursorVisible = true; private set
    /** 0 or 1 or 2 is a block (the default), 3 or 4 an underline, 5 or 6 a bar, from CSI SP q. */
    var cursorStyle = 1; private set
    private var pendingWrap = false

    private var curFg = DEFAULT_COLOR
    private var curBg = DEFAULT_COLOR
    private var curAttr = 0
    private var lastPrinted = ' '.code

    private var top = 0
    private var bottom = this.rows - 1

    private var autowrap = true
    private var originMode = false
    private var insertMode = false
    /** Arrow keys send ESC O A instead of ESC [ A while this is on (vim and less turn it on). */
    var appCursorKeys = false; private set
    /** Pasted text should be wrapped in ESC [ 200 ~ and ESC [ 201 ~ while this is on. */
    var bracketedPaste = false; private set
    /** True while a program asked for mouse reports; the view then has nothing to scroll locally. */
    var mouseTracking = false; private set

    private var g0LineDrawing = false
    private var g1LineDrawing = false
    private var useG1 = false

    // Saved cursor (ESC 7, CSI s, and the 1049 mode).
    private var savedX = 0; private var savedY = 0; private var savedFg = DEFAULT_COLOR; private var savedBg = DEFAULT_COLOR
    private var savedAttr = 0; private var savedOrigin = false; private var savedG0 = false; private var savedG1 = false; private var savedUseG1 = false
    private var savedPendingWrap = false

    private var state = GROUND
    private val seq = StringBuilder()
    private var stringLength = 0
    private var charsetSlot = 0
    private var highSurrogate = 0

    /** Goes up on every visible change; the view redraws when it differs from what it drew last. */
    var version = 0L; private set
    /** Replies the terminal sends back to the program (cursor position reports and the like). */
    var onResponse: ((String) -> Unit)? = null
    /** The window title a program set with OSC 0 or OSC 2 (nothing is done with it yet). */
    var title: String = ""; private set

    val scrollbackSize: Int get() = scrollback.size

    /** The line at an index in the combined space: 0 is the oldest scrollback line, scrollbackSize is the top screen row. */
    fun lineAt(index: Int): Line? {
        if (index < 0) return null
        if (index < scrollback.size) return scrollback[index]
        val r = index - scrollback.size
        return if (r < lines.size) lines[r] else null
    }

    // ------------------------------------------------------------------ public operations
    /** Feeds the text a program wrote. */
    fun write(s: String) {
        for (ch in s) process(ch)
        version++
    }

    /** Drops everything and starts with a blank screen, the way a new terminal starts. */
    fun reset() {
        lines = ArrayList<Line>(rows).also { for (i in 0 until rows) it.add(Line(cols)) }
        scrollback.clear(); savedMain = null; altScreen = false
        cursorX = 0; cursorY = 0; pendingWrap = false
        softReset()
        state = GROUND; seq.setLength(0); highSurrogate = 0
        title = ""
        version++
    }

    /** Clears the scrollback and the screen except the cursor's line, which becomes the first row (like xterm's clear). */
    fun clear() {
        scrollback.clear()
        if (!altScreen) {
            val keep = lines[cursorY]
            lines = ArrayList<Line>(rows).also { it.add(keep); for (i in 1 until rows) it.add(Line(cols)) }
            cursorY = 0
        }
        version++
    }

    /** Changes the grid size without reflowing text: columns are cut or extended, rows move to or from the scrollback. */
    fun resize(newCols: Int, newRows: Int) {
        val c = maxOf(2, newCols); val r = maxOf(1, newRows)
        if (c == cols && r == rows) return
        for (l in lines) l.resize(c)
        savedMain?.let { saved -> for (l in saved) l.resize(c) }
        cols = c
        cursorY = adjustRows(lines, r, !altScreen, cursorY)
        savedMain?.let { saved -> savedY = adjustRows(saved, r, false, savedY) }
        rows = r
        top = 0; bottom = rows - 1
        cursorX = minOf(cursorX, cols - 1); cursorY = minOf(cursorY, rows - 1)
        savedX = minOf(savedX, cols - 1); savedY = minOf(savedY, rows - 1)
        pendingWrap = false
        version++
    }

    /** Brings a screen buffer to newRows rows and returns the cursor row that keeps the cursor on the same text. */
    private fun adjustRows(buf: ArrayList<Line>, newRows: Int, useScrollback: Boolean, cy: Int): Int {
        var y = cy
        if (newRows < buf.size) {
            var remove = buf.size - newRows
            // Blank lines below the cursor go first, then the top lines move into the scrollback.
            while (remove > 0 && buf.size - 1 > y && buf[buf.size - 1].isBlank()) { buf.removeAt(buf.size - 1); remove-- }
            while (remove > 0 && buf.isNotEmpty()) {
                val l = buf.removeAt(0)
                if (useScrollback) pushScrollback(l)
                y--; remove--
            }
            if (y < 0) y = 0
        } else if (newRows > buf.size) {
            var add = newRows - buf.size
            while (add > 0 && useScrollback && scrollback.isNotEmpty()) {
                val l = scrollback.removeLast(); l.resize(cols)
                buf.add(0, l); y++; add--
            }
            while (add > 0) { buf.add(Line(cols)); add-- }
        }
        return y
    }

    private fun pushScrollback(l: Line) {
        scrollback.addLast(l.trimmed())
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    // ------------------------------------------------------------------ the parser
    private fun process(ch: Char) {
        val c = ch.code
        when (state) {
            GROUND -> if (c < 0x20 || c == 0x7f) control(c) else printChar(ch)
            ESC -> escape(ch)
            CSI -> when {
                c == 0x1b -> { state = ESC; seq.setLength(0) }
                c == 0x18 || c == 0x1a -> state = GROUND
                c < 0x20 -> control(c)
                c in 0x30..0x3f || c in 0x20..0x2f -> { seq.append(ch); if (seq.length > 64) state = GROUND }
                c in 0x40..0x7e -> { val s = seq.toString(); seq.setLength(0); state = GROUND; csi(s, ch) }
                else -> state = GROUND
            }
            OSC -> when (c) {
                0x07 -> { state = GROUND; oscDone() }
                0x1b -> state = OSC_ESC
                else -> if (c >= 0x20) { if (seq.length < 4096) seq.append(ch) }
            }
            OSC_ESC -> { state = GROUND; oscDone(); if (ch != '\\') escape(ch) }
            STRING -> when (c) {
                0x1b -> state = STRING_ESC
                0x18, 0x1a -> state = GROUND
                else -> if (++stringLength > 200000) state = GROUND
            }
            STRING_ESC -> { state = GROUND; if (ch != '\\') escape(ch) }
            CHARSET -> {
                val drawing = ch == '0'
                if (charsetSlot == 0) g0LineDrawing = drawing else if (charsetSlot == 1) g1LineDrawing = drawing
                state = GROUND
            }
            ESC_HASH -> { if (ch == '8') fillScreen('E'.code); state = GROUND }
            ESC_SKIP -> state = GROUND
        }
    }

    private fun control(c: Int) {
        when (c) {
            0x07 -> { }
            0x08 -> { pendingWrap = false; if (cursorX > 0) cursorX-- }
            0x09 -> { pendingWrap = false; cursorX = nextTab(cursorX) }
            0x0a, 0x0b, 0x0c -> lineFeed()
            0x0d -> { cursorX = 0; pendingWrap = false }
            0x0e -> useG1 = true
            0x0f -> useG1 = false
            0x1b -> { state = ESC; seq.setLength(0) }
            else -> { }
        }
    }

    private fun escape(ch: Char) {
        state = GROUND
        when (ch) {
            '[' -> { state = CSI; seq.setLength(0) }
            ']' -> { state = OSC; seq.setLength(0) }
            'P', '^', '_', 'X' -> { state = STRING; stringLength = 0 }
            '(' -> { state = CHARSET; charsetSlot = 0 }
            ')' -> { state = CHARSET; charsetSlot = 1 }
            '*', '+', '-', '.', '/' -> { state = CHARSET; charsetSlot = 2 }
            '#' -> state = ESC_HASH
            '%', ' ' -> state = ESC_SKIP
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { cursorX = 0; pendingWrap = false; lineFeed() }
            'H' -> { }
            'M' -> reverseIndex()
            'Z' -> respond("\u001b[?6c")
            'c' -> reset()
            else -> { }   // ESC = and ESC > (keypad modes), ESC N and ESC O, ESC \\ and the rest are accepted and ignored
        }
    }

    private fun oscDone() {
        val s = seq.toString(); seq.setLength(0)
        val semi = s.indexOf(';')
        if (semi > 0) {
            val code = s.substring(0, semi)
            if (code == "0" || code == "2") title = s.substring(semi + 1)
        }
    }

    private fun respond(s: String) { onResponse?.invoke(s) }

    // ------------------------------------------------------------------ printing
    private fun printChar(ch: Char) {
        var cp: Int
        if (Character.isHighSurrogate(ch)) { highSurrogate = ch.code; return }
        if (Character.isLowSurrogate(ch)) {
            if (highSurrogate == 0) return
            cp = Character.toCodePoint(highSurrogate.toChar(), ch); highSurrogate = 0
        } else {
            highSurrogate = 0
            cp = ch.code
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || cp == 0x200d || cp == 0xfe0f) return
            val drawing = if (useG1) g1LineDrawing else g0LineDrawing
            if (drawing && ch in '`'..'~') cp = (LINE_DRAWING[ch] ?: ch).code
        }
        val width = if (isWide(cp)) 2 else 1
        if (pendingWrap) {
            pendingWrap = false
            if (autowrap) { lines[cursorY].wrapped = true; cursorX = 0; lineFeed() }
        }
        if (width == 2 && cursorX == cols - 1) {
            // A wide character does not fit in the last column: pad it and wrap first.
            lines[cursorY].erase(cursorX, cols, curBg)
            if (autowrap) { lines[cursorY].wrapped = true; cursorX = 0; lineFeed() } else cursorX = maxOf(0, cols - 2)
        }
        val line = lines[cursorY]
        if (insertMode) line.insertBlank(cursorX, width, curBg)
        line.set(cursorX, cp, curFg, curBg, curAttr)
        if (width == 2 && cursorX + 1 < cols) line.set(cursorX + 1, WIDE_FILLER, curFg, curBg, curAttr)
        lastPrinted = cp
        val next = cursorX + width
        if (next >= cols) { cursorX = cols - 1; if (autowrap) pendingWrap = true } else cursorX = next
    }

    private fun fillScreen(cp: Int) {
        for (l in lines) for (x in 0 until cols) l.set(x, cp, DEFAULT_COLOR, DEFAULT_COLOR, 0)
        cursorX = 0; cursorY = 0; top = 0; bottom = rows - 1; pendingWrap = false
    }

    private fun nextTab(x: Int): Int { val n = (x / 8 + 1) * 8; return minOf(n, cols - 1) }
    private fun prevTab(x: Int): Int { if (x <= 0) return 0; return ((x - 1) / 8) * 8 }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorY == bottom) scrollUp(top, bottom, 1, !altScreen && top == 0)
        else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == top) scrollDown(top, bottom, 1) else if (cursorY > 0) cursorY--
    }

    /** Moves the region's lines up by n; the lines that leave at the top go to the scrollback when asked. */
    private fun scrollUp(t: Int, b: Int, n: Int, toScrollback: Boolean) {
        val count = minOf(n, b - t + 1)
        for (i in 0 until count) {
            val l = lines.removeAt(t)
            if (toScrollback) pushScrollback(l)
            lines.add(b, Line(cols).also { if (curBg != DEFAULT_COLOR) it.erase(0, cols, curBg) })
        }
    }

    private fun scrollDown(t: Int, b: Int, n: Int) {
        val count = minOf(n, b - t + 1)
        for (i in 0 until count) {
            lines.removeAt(b)
            lines.add(t, Line(cols).also { if (curBg != DEFAULT_COLOR) it.erase(0, cols, curBg) })
        }
    }

    private fun saveCursor() {
        savedX = cursorX; savedY = cursorY; savedFg = curFg; savedBg = curBg; savedAttr = curAttr
        savedOrigin = originMode; savedG0 = g0LineDrawing; savedG1 = g1LineDrawing; savedUseG1 = useG1; savedPendingWrap = pendingWrap
    }

    private fun restoreCursor() {
        cursorX = minOf(savedX, cols - 1); cursorY = minOf(savedY, rows - 1); curFg = savedFg; curBg = savedBg; curAttr = savedAttr
        originMode = savedOrigin; g0LineDrawing = savedG0; g1LineDrawing = savedG1; useG1 = savedUseG1; pendingWrap = savedPendingWrap
    }

    private fun softReset() {
        curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curAttr = 0
        top = 0; bottom = rows - 1
        autowrap = true; originMode = false; insertMode = false; cursorVisible = true
        appCursorKeys = false; bracketedPaste = false; mouseTracking = false
        g0LineDrawing = false; g1LineDrawing = false; useG1 = false
        cursorStyle = 1
        savedX = 0; savedY = 0; savedFg = DEFAULT_COLOR; savedBg = DEFAULT_COLOR; savedAttr = 0
    }

    private fun moveTo(row: Int, col: Int) {
        var y = row
        if (originMode) y = minOf(maxOf(y + top, top), bottom) else y = minOf(maxOf(y, 0), rows - 1)
        cursorY = y
        cursorX = minOf(maxOf(col, 0), cols - 1)
        pendingWrap = false
    }

    private fun enterAlt(saveCursorToo: Boolean) {
        if (altScreen) return
        if (saveCursorToo) saveCursor()
        savedMain = lines
        lines = ArrayList<Line>(rows).also { for (i in 0 until rows) it.add(Line(cols)) }
        altScreen = true
        pendingWrap = false
    }

    private fun leaveAlt(restoreCursorToo: Boolean) {
        if (!altScreen) return
        val main = savedMain
        lines = if (main != null && main.size == rows) main else ArrayList<Line>(rows).also { for (i in 0 until rows) it.add(Line(cols)) }
        savedMain = null
        altScreen = false
        if (restoreCursorToo) restoreCursor()
        cursorX = minOf(cursorX, cols - 1); cursorY = minOf(cursorY, rows - 1)
        pendingWrap = false
    }

    // ------------------------------------------------------------------ CSI dispatch
    private fun csi(raw: String, final: Char) {
        var prefix = ' '
        var intermediate = NONE
        val digits = StringBuilder()
        val params = ArrayList<Int>()
        // sub[i] is true when parameter i was introduced by a colon (a sub-parameter, as in 38:2::r:g:b).
        val sub = ArrayList<Boolean>()
        var colon = false
        for (ch in raw) {
            when {
                ch in '<'..'?' && params.isEmpty() && digits.isEmpty() && prefix == ' ' -> prefix = ch
                ch in '0'..'9' -> { if (digits.length < 7) digits.append(ch) }
                ch == ';' || ch == ':' -> {
                    params.add(if (digits.isEmpty()) 0 else digits.toString().toInt()); sub.add(colon); digits.setLength(0)
                    colon = ch == ':'
                }
                ch in ' '..'/' -> intermediate = ch
                else -> { }
            }
        }
        if (digits.isNotEmpty() || raw.endsWith(";") || raw.endsWith(":")) { params.add(if (digits.isEmpty()) 0 else digits.toString().toInt()); sub.add(colon) }
        fun p(i: Int, def: Int): Int = if (i < params.size && params[i] > 0) params[i] else def

        if (intermediate != NONE) {
            // CSI ! p is a soft reset; CSI SP q picks the cursor shape. Other intermediates are ignored.
            if (intermediate == '!' && final == 'p') { softReset(); cursorX = 0; cursorY = 0 }
            else if (intermediate == ' ' && final == 'q') cursorStyle = if (params.isEmpty()) 1 else params[0]
            return
        }
        if (prefix == '?') {
            when (final) {
                'h' -> for (m in params) decMode(m, true)
                'l' -> for (m in params) decMode(m, false)
                'n' -> if (p(0, 0) == 6) respond("\u001b[?${reportRow()};${cursorX + 1}R")
                else -> { }
            }
            return
        }
        if (prefix == '>') {
            if (final == 'c') respond("\u001b[>0;276;0c")
            return
        }
        if (prefix != ' ') return

        when (final) {
            '@' -> lines[cursorY].insertBlank(cursorX, p(0, 1), curBg)
            'A' -> { val limit = if (cursorY >= top) top else 0; cursorY = maxOf(limit, cursorY - p(0, 1)); pendingWrap = false }
            'B', 'e' -> { val limit = if (cursorY <= bottom) bottom else rows - 1; cursorY = minOf(limit, cursorY + p(0, 1)); pendingWrap = false }
            'C', 'a' -> { cursorX = minOf(cols - 1, cursorX + p(0, 1)); pendingWrap = false }
            'D' -> { cursorX = maxOf(0, cursorX - p(0, 1)); pendingWrap = false }
            'E' -> { val limit = if (cursorY <= bottom) bottom else rows - 1; cursorY = minOf(limit, cursorY + p(0, 1)); cursorX = 0; pendingWrap = false }
            'F' -> { val limit = if (cursorY >= top) top else 0; cursorY = maxOf(limit, cursorY - p(0, 1)); cursorX = 0; pendingWrap = false }
            'G', '`' -> { cursorX = minOf(cols - 1, p(0, 1) - 1); pendingWrap = false }
            'H', 'f' -> moveTo(p(0, 1) - 1, p(1, 1) - 1)
            'I' -> { for (i in 0 until p(0, 1)) cursorX = nextTab(cursorX); pendingWrap = false }
            'J' -> eraseDisplay(if (params.isEmpty()) 0 else params[0])
            'K' -> eraseLine(if (params.isEmpty()) 0 else params[0])
            'L' -> if (cursorY in top..bottom) { scrollDown(cursorY, bottom, p(0, 1)); pendingWrap = false }
            'M' -> if (cursorY in top..bottom) { scrollUp(cursorY, bottom, p(0, 1), false); pendingWrap = false }
            'P' -> lines[cursorY].delete(cursorX, p(0, 1), curBg)
            'S' -> scrollUp(top, bottom, p(0, 1), false)
            'T' -> if (params.size <= 1) scrollDown(top, bottom, p(0, 1))
            'X' -> lines[cursorY].erase(cursorX, cursorX + p(0, 1), curBg)
            'Z' -> { for (i in 0 until p(0, 1)) cursorX = prevTab(cursorX); pendingWrap = false }
            'b' -> { val n = minOf(p(0, 1), cols * rows); for (i in 0 until n) printCodePoint(lastPrinted) }
            'c' -> respond("\u001b[?1;2c")
            'd' -> { val y = p(0, 1) - 1; cursorY = if (originMode) minOf(maxOf(y + top, top), bottom) else minOf(maxOf(y, 0), rows - 1); pendingWrap = false }
            'g' -> { }
            'h' -> for (m in params) ansiMode(m, true)
            'l' -> for (m in params) ansiMode(m, false)
            'm' -> sgr(params, sub)
            'n' -> when (p(0, 0)) { 5 -> respond("\u001b[0n"); 6 -> respond("\u001b[${reportRow()};${cursorX + 1}R") }
            'r' -> {
                val t = p(0, 1) - 1; val b = p(1, rows) - 1
                if (t >= 0 && b < rows && t < b) { top = t; bottom = b; moveTo(0, 0) }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> if (p(0, 0) == 18) respond("\u001b[8;$rows;${cols}t")
            else -> { }
        }
    }

    private fun reportRow(): Int = if (originMode) cursorY - top + 1 else cursorY + 1

    /** Prints an already translated code point (used by the repeat sequence). */
    private fun printCodePoint(cp: Int) {
        if (cp < 0x20) return
        if (cp > 0xffff) { val pair = Character.toChars(cp); printChar(pair[0]); printChar(pair[1]) }
        else {
            // Bypass the charset translation so the repeat prints exactly what was printed last.
            val g0 = g0LineDrawing; val g1 = g1LineDrawing
            g0LineDrawing = false; g1LineDrawing = false
            printChar(cp.toChar())
            g0LineDrawing = g0; g1LineDrawing = g1
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { lines[cursorY].erase(cursorX, cols, curBg); for (y in cursorY + 1 until rows) lines[y].erase(0, cols, curBg) }
            1 -> { for (y in 0 until cursorY) lines[y].erase(0, cols, curBg); lines[cursorY].erase(0, cursorX + 1, curBg) }
            2 -> for (l in lines) l.erase(0, cols, curBg)
            3 -> scrollback.clear()
        }
        pendingWrap = false
    }

    private fun eraseLine(mode: Int) {
        val l = lines[cursorY]
        when (mode) {
            0 -> l.erase(cursorX, cols, curBg)
            1 -> l.erase(0, cursorX + 1, curBg)
            2 -> l.erase(0, cols, curBg)
        }
        pendingWrap = false
    }

    private fun ansiMode(m: Int, on: Boolean) {
        when (m) {
            4 -> insertMode = on
            else -> { }
        }
    }

    private fun decMode(m: Int, on: Boolean) {
        when (m) {
            1 -> appCursorKeys = on
            6 -> { originMode = on; moveTo(0, 0) }
            7 -> autowrap = on
            25 -> cursorVisible = on
            47 -> if (on) enterAlt(false) else leaveAlt(false)
            1047 -> if (on) enterAlt(false) else { if (altScreen) for (l in lines) l.erase(0, cols, DEFAULT_COLOR); leaveAlt(false) }
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) enterAlt(true) else leaveAlt(true)
            1000, 1002, 1003 -> mouseTracking = on
            2004 -> bracketedPaste = on
            else -> { }
        }
    }

    private fun sgr(params: List<Int>, sub: List<Boolean>) {
        if (params.isEmpty()) { curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curAttr = 0; return }
        var i = 0
        while (i < params.size) {
            val v = params[i]
            when {
                v == 0 -> { curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curAttr = 0 }
                v == 1 -> curAttr = curAttr or BOLD
                v == 2 -> curAttr = curAttr or DIM
                v == 3 -> curAttr = curAttr or ITALIC
                v == 4 || v == 21 -> curAttr = curAttr or UNDERLINE
                v == 7 -> curAttr = curAttr or INVERSE
                v == 8 -> curAttr = curAttr or HIDDEN
                v == 22 -> curAttr = curAttr and (BOLD or DIM).inv()
                v == 23 -> curAttr = curAttr and ITALIC.inv()
                v == 24 -> curAttr = curAttr and UNDERLINE.inv()
                v == 27 -> curAttr = curAttr and INVERSE.inv()
                v == 28 -> curAttr = curAttr and HIDDEN.inv()
                v in 30..37 -> curFg = v - 30
                v == 38 -> { val r = extendedColor(params, sub, i); if (r.first != Int.MIN_VALUE) curFg = r.first; i = r.second }
                v == 39 -> curFg = DEFAULT_COLOR
                v in 40..47 -> curBg = v - 40
                v == 48 -> { val r = extendedColor(params, sub, i); if (r.first != Int.MIN_VALUE) curBg = r.first; i = r.second }
                v == 49 -> curBg = DEFAULT_COLOR
                v == 58 -> { val r = extendedColor(params, sub, i); i = r.second }
                v in 90..97 -> curFg = v - 90 + 8
                v in 100..107 -> curBg = v - 100 + 8
                else -> { }
            }
            i++
        }
    }

    /**
     * Reads 5;n or 2;r;g;b after a 38, 48 or 58 (also the colon form 38:2::r:g:b, whose empty color space entry is
     * skipped); returns the color (Int.MIN_VALUE for none) and the index of the last parameter used.
     */
    private fun extendedColor(params: List<Int>, sub: List<Boolean>, at: Int): Pair<Int, Int> {
        if (at + 1 >= params.size) return Pair(Int.MIN_VALUE, at)
        fun isSub(i: Int) = i < sub.size && sub[i]
        return when (params[at + 1]) {
            5 -> if (at + 2 < params.size) Pair(params[at + 2] and 0xff, at + 2) else Pair(Int.MIN_VALUE, at + 1)
            2 -> {
                // With colons and five or more sub-parameters the third one is the color space, which is skipped.
                val start = if (isSub(at + 2) && isSub(at + 5) && at + 5 < params.size) at + 3 else at + 2
                if (start + 2 < params.size) {
                    val r = params[start] and 0xff; val g = params[start + 1] and 0xff; val b = params[start + 2] and 0xff
                    Pair(RGB_FLAG or (r shl 16) or (g shl 8) or b, start + 2)
                } else Pair(Int.MIN_VALUE, params.size - 1)
            }
            else -> Pair(Int.MIN_VALUE, at + 1)
        }
    }
}
