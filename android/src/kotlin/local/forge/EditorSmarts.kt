package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import android.widget.EditText

/**
 * The intelligence of one code editor, the native port of the old page's lsp.js providers and of the AI parts of
 * app.js: everything that makes the editor feel like Cursor.
 *
 * Language servers (Lsp.kt): the document is opened, changed and saved on the server; diagnostics become wavy
 * underlines and gutter marks; a completion list follows typing (trigger characters, Ctrl Space, fuzzy filtering,
 * documentation of the selected item, extra edits such as imports); hovering shows types, documentation and the
 * problems under the pointer with Quick fix and Fix with AI; signature help appears after an opening parenthesis or
 * a comma; F12 and Ctrl click go to the definition, Ctrl F12 to the implementation, Shift F12 lists the references,
 * F2 renames everywhere, Shift Alt F formats, Ctrl . offers quick fixes (and always Fix with AI).
 *
 * AI: Tab completions from the model as gray ghost text after a pause in typing (setting tab_completions, delay
 * completion_delay_ms; Alt backslash asks at once even when the setting is off) and Ctrl K inline edits: a bar over
 * the code takes an instruction, the new code streams into the editor and stays under review (green and red lines,
 * Keep and Undo) until it is kept or undone.
 */
class EditorSmarts(private val ctx: Context, private val host: MainActivity, private val panel: EditorPanel, val editor: CodeEditor) {
    companion object {
        private const val COMPLETION_DELAY_MS = 90L
        private const val MAX_ROWS = 10
        /** Short letters and colors for completion item kinds, by the protocol's numbers, in the style of VS Code. */
        private val KIND_BADGE = mapOf(
            2 to ("m" to 0xffc792ea.toInt()), 3 to ("f" to 0xffc792ea.toInt()), 4 to ("c" to 0xffffcb6b.toInt()),
            5 to ("p" to 0xff82aaff.toInt()), 6 to ("v" to 0xff82aaff.toInt()), 7 to ("C" to 0xffffcb6b.toInt()),
            8 to ("I" to 0xff89ddff.toInt()), 9 to ("M" to 0xfff78c6c.toInt()), 10 to ("p" to 0xff82aaff.toInt()),
            13 to ("E" to 0xffffcb6b.toInt()), 14 to ("k" to 0xffff8a3d.toInt()), 15 to ("s" to 0xff9aa3bd.toInt()),
            17 to ("F" to 0xff9aa3bd.toInt()), 19 to ("D" to 0xff9aa3bd.toInt()), 20 to ("e" to 0xffffcb6b.toInt()),
            21 to ("K" to 0xff82aaff.toInt()), 22 to ("S" to 0xffffcb6b.toInt()), 25 to ("T" to 0xff89ddff.toInt()))

        fun badgeFor(kind: Int): Pair<String, Int> = KIND_BADGE[kind] ?: ("t" to 0xff6b7489.toInt())
    }

    private fun dp(v: Int) = Ui.dp(ctx, v)
    private val path: String get() = editor.path

    private var applying = false
    private var lastTyped: Char = '\u0000'
    private var detached = false

    // ---- the completion list
    private var items: List<Lsp.CompletionItem> = emptyList()
    private var shown: List<Lsp.CompletionItem> = emptyList()
    private var selected = 0
    private var incomplete = false
    private var completionStart = -1
    private var completionGen = 0
    private var completionPopup: PopupWindow? = null
    private val completionList = ListView(ctx)
    private val completionDocs = Ui.text(ctx, "", 12f, Ui.fg2)
    private val completionDocsScroll = ScrollView(ctx)
    private val completionAdapter = CompletionAdapter()
    private val completionTask = Runnable { requestCompletion(null) }
    private var resolveGen = 0

    // ---- hover and signature help
    private var hoverPopup: PopupWindow? = null
    private var hoverGen = 0
    private val hoverDismiss = Runnable { hoverPopup?.dismiss(); hoverPopup = null }
    private var signaturePopup: PopupWindow? = null
    private var signatureGen = 0
    private val signatureTask = Runnable { requestSignature() }

    // ---- AI Tab completions
    private var ghostGen = 0
    private val ghostTask = Runnable { requestGhost(false) }

    // ---- Ctrl K
    private var inlineBar: PopupWindow? = null
    private var inlineStream: Api.EventStream? = null
    private var inlineRunId = ""
    var generating = false; private set

    /** The folder of this editor's window; its language servers are that folder's. */
    private val folder = host.windowFolder()

    private val diagnosticsListener: (String, String, List<Lsp.Diagnostic>) -> Unit = { f, p, list -> if (f == folder && p == path) editor.setDiagnostics(list) }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            lastTyped = if (!applying && before == 0 && count == 1 && editor.edit.hasFocus()) s[start] else '\u0000'
        }
        override fun afterTextChanged(s: Editable?) {
            if (detached) return
            Lsp.change(path, editor.text(), folder)
            val typed = lastTyped
            lastTyped = '\u0000'
            if (typed != '\u0000') Api.main.post { onTyped(typed) }
            else if (completionPopup != null && !applying) Api.main.post { refilter() }
        }
    }

    init {
        completionList.adapter = completionAdapter
        completionList.divider = null
        completionList.selector = ColorDrawable(Color.TRANSPARENT)
        completionList.isVerticalScrollBarEnabled = true
        completionList.setOnItemClickListener { _, _, pos, _ -> selected = pos; accept() }
        completionDocs.setPadding(dp(10), dp(8), dp(10), dp(8))
        completionDocs.setTextIsSelectable(false)
        completionDocsScroll.addView(completionDocs)

        editor.edit.addTextChangedListener(watcher)
        editor.keyHook = { code, ev -> onKey(code, ev) }
        editor.hoverHook = { off, x, y -> requestHover(off, x, y) }
        editor.hoverEndHook = { Api.main.removeCallbacks(hoverDismiss); Api.main.postDelayed(hoverDismiss, 350) }
        editor.ctrlClickHook = { off -> goToLocation("textDocument/definition", "definitionProvider", off) }
        editor.scrollHook = { dismissPopups(keepInline = true) }
        editor.cursorHook = { off -> onCursor(off) }
        editor.contextMenuHook = { x, y -> contextMenu(x, y) }
        Lsp.listeners.add(diagnosticsListener)
        Lsp.diagnosticsOf(folder)[path]?.let { editor.setDiagnostics(it) }
    }

    fun saved() { Lsp.save(path, folder) }

    fun detach() {
        detached = true
        cancelInline()
        dismissPopups(keepInline = false)
        Lsp.listeners.remove(diagnosticsListener)
        editor.edit.removeTextChangedListener(watcher)
        Api.main.removeCallbacks(completionTask); Api.main.removeCallbacks(ghostTask); Api.main.removeCallbacks(signatureTask)
    }

    fun dismissPopups(keepInline: Boolean) {
        closeCompletion()
        hoverPopup?.dismiss(); hoverPopup = null
        signaturePopup?.dismiss(); signaturePopup = null
        if (!keepInline) { inlineBar?.dismiss(); inlineBar = null }
    }

    // ---------------------------------------------------------------- keys
    private fun onKey(code: Int, ev: KeyEvent): Boolean {
        if (generating) {
            if (code == KeyEvent.KEYCODE_ESCAPE) { cancelInline(); return true }
            return true   // the code is being written into this editor; typing would collide with it
        }
        val ctrl = ev.isCtrlPressed; val shift = ev.isShiftPressed; val alt = ev.isAltPressed
        if (completionPopup != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveSelection(1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { moveSelection(-1); return true }
                KeyEvent.KEYCODE_PAGE_DOWN -> { moveSelection(MAX_ROWS); return true }
                KeyEvent.KEYCODE_PAGE_UP -> { moveSelection(-MAX_ROWS); return true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> if (!ctrl && !alt) { accept(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { closeCompletion(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> closeCompletion()
            }
        }
        // A snippet's tab stops: Tab goes to the next placeholder, Shift Tab back, Escape leaves them.
        if (snippetStops.isNotEmpty() && !ctrl && !alt) {
            if (code == KeyEvent.KEYCODE_TAB) { goToStop(snippetAt + if (shift) -1 else 1); return true }
            if (code == KeyEvent.KEYCODE_ESCAPE) { endSnippet(); return true }
        }
        if (code == KeyEvent.KEYCODE_ESCAPE && signaturePopup != null) { signaturePopup?.dismiss(); signaturePopup = null; return true }
        if (code == KeyEvent.KEYCODE_ESCAPE && hoverPopup != null) { hoverPopup?.dismiss(); hoverPopup = null; return true }
        if (ctrl && !alt && code == KeyEvent.KEYCODE_SPACE) { if (shift) requestSignature() else requestCompletion(null); return true }
        if (ctrl && !alt && !shift && code == KeyEvent.KEYCODE_K) { openInlineEdit(); return true }
        if (ctrl && !alt && !shift && code == KeyEvent.KEYCODE_PERIOD) { codeActions(); return true }
        if (ctrl && shift && !alt && code == KeyEvent.KEYCODE_O) { panel.symbolPicker(); return true }
        if (alt && !ctrl && code == KeyEvent.KEYCODE_BACKSLASH) { requestGhost(true); return true }
        if (shift && alt && !ctrl && code == KeyEvent.KEYCODE_F) { format(); return true }
        when (code) {
            KeyEvent.KEYCODE_F12 -> {
                when {
                    shift -> references()
                    ctrl -> goToLocation("textDocument/implementation", "implementationProvider", editor.cursorOffset())
                    alt -> peekDefinition()
                    else -> goToLocation("textDocument/definition", "definitionProvider", editor.cursorOffset())
                }
                return true
            }
            KeyEvent.KEYCODE_F2 -> if (!ctrl && !alt) { renameDialog(); return true }
        }
        return false
    }

    private fun onCursor(off: Int) {
        panel.cursorMoved(editor)
        if (completionPopup != null && !applying) {
            if (completionStart < 0 || off < completionStart) closeCompletion()
            else { val t = editor.edit.text; for (i in completionStart until off.coerceAtMost(t.length)) if (!isWord(t[i])) { closeCompletion(); break } }
        }
        if (signaturePopup != null) { Api.main.removeCallbacks(signatureTask); Api.main.postDelayed(signatureTask, 150) }
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun onTyped(c: Char) {
        if (detached) return
        hoverPopup?.dismiss(); hoverPopup = null
        val client = Lsp.clientFor(path, folder)
        val triggers = client?.triggerCharacters("completionProvider") ?: emptySet()
        val sigTriggers = (client?.triggerCharacters("signatureHelpProvider") ?: emptySet()) + setOf('(', ',')
        when {
            c in triggers && !isWord(c) -> { Api.main.removeCallbacks(completionTask); requestCompletion(c) }
            isWord(c) -> {
                if (completionPopup != null && !incomplete) refilter()
                else { Api.main.removeCallbacks(completionTask); Api.main.postDelayed(completionTask, COMPLETION_DELAY_MS) }
            }
            else -> closeCompletion()
        }
        if (c in sigTriggers && client != null) requestSignature()
        else if (c == ')') { signaturePopup?.dismiss(); signaturePopup = null }
        scheduleGhost()
    }

    // ---------------------------------------------------------------- completion list
    private fun wordStart(off: Int): Int {
        val t = editor.edit.text
        var a = off.coerceIn(0, t.length)
        while (a > 0 && isWord(t[a - 1])) a--
        return a
    }

    private fun requestCompletion(trigger: Char?) {
        val client = Lsp.clientFor(path, folder) ?: return
        if (!client.ready) return
        val off = editor.cursorOffset()
        val start = wordStart(off)
        if (trigger == null && start == off && completionPopup == null && lastTyped != '\u0000') return
        val (line, col) = editor.lineColOf(off)
        val gen = ++completionGen
        client.completion(path, line, col, trigger) { list, inc ->
            if (gen != completionGen || detached) return@completion
            if (editor.cursorOffset() < start) return@completion
            items = list; incomplete = inc; completionStart = start
            if (list.isEmpty()) { closeCompletion(); return@completion }
            refilter()
        }
    }

    /** Fuzzy score of a candidate for the typed word: higher is better, -1 means no match. */
    private fun score(word: String, cand: String): Int {
        if (word.isEmpty()) return 1
        if (cand.startsWith(word)) return 3000 - cand.length
        if (cand.startsWith(word, ignoreCase = true)) return 2500 - cand.length
        val idx = cand.indexOf(word, ignoreCase = true)
        if (idx >= 0) return 1500 - idx * 10 - cand.length
        var i = 0; var gaps = 0; var last = -1
        for (ch in word) {
            var found = -1
            var k = i
            while (k < cand.length) { if (cand[k].equals(ch, ignoreCase = true)) { found = k; break }; k++ }
            if (found < 0) return -1
            if (last >= 0 && found != last + 1) gaps++
            last = found; i = found + 1
        }
        return 800 - gaps * 40 - cand.length
    }

    private fun refilter() {
        if (items.isEmpty()) { closeCompletion(); return }
        val off = editor.cursorOffset()
        if (completionStart < 0 || off < completionStart) { closeCompletion(); return }
        if (incomplete && completionPopup != null) { Api.main.removeCallbacks(completionTask); Api.main.postDelayed(completionTask, 60) }
        val word = editor.edit.text.subSequence(completionStart, off.coerceAtMost(editor.edit.text.length)).toString()
        shown = items.mapNotNull { it -> val s = score(word, it.filterText.ifEmpty { it.label }); if (s < 0) null else it to s }
            .sortedWith(compareByDescending<Pair<Lsp.CompletionItem, Int>> { it.second }.thenBy { it.first.sortText })
            .map { it.first }.take(200)
        if (shown.isEmpty() || (shown.size == 1 && shown[0].label == word)) { closeCompletion(); return }
        selected = 0
        showCompletion()
    }

    private fun showCompletion() {
        completionAdapter.notifyDataSetChanged()
        completionList.setSelection(0)
        val rowH = dp(24)
        val listH = rowH * minOf(MAX_ROWS, shown.size) + dp(4)
        updateDocs()
        val p = completionPopup
        val rect = editor.cursorRect()
        val loc = IntArray(2); editor.edit.getLocationInWindow(loc)
        var x = loc[0] + rect.left - dp(30)
        var y = loc[1] + rect.bottom + dp(2)
        val winH = editor.rootView.height
        val width = dp(440) + dp(300)
        if (y + listH > winH - dp(8)) y = loc[1] + rect.top - listH - dp(2)
        x = x.coerceIn(0, (editor.rootView.width - width).coerceAtLeast(0))
        if (p != null && p.isShowing) { p.update(x, y, width, listH); return }
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.HORIZONTAL
        val listBox = FrameLayout(ctx)
        listBox.background = Ui.rounded(Ui.bg2, 6, Ui.border2)
        listBox.setPadding(dp(2), dp(2), dp(2), dp(2))
        (completionList.parent as? ViewGroup)?.removeView(completionList)
        listBox.addView(completionList, FrameLayout.LayoutParams(Ui.match, Ui.match))
        box.addView(listBox, Ui.lp(dp(440), Ui.match))
        (completionDocsScroll.parent as? ViewGroup)?.removeView(completionDocsScroll)
        completionDocsScroll.background = Ui.rounded(Ui.bg3, 6, Ui.border2)
        box.addView(completionDocsScroll, Ui.lp(dp(296), Ui.wrap).also { it.leftMargin = dp(4) })
        val pw = PopupWindow(box, width, listH, false)
        pw.isTouchable = true
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(8).toFloat()
        pw.setOnDismissListener { if (completionPopup === pw) completionPopup = null }
        completionPopup = pw
        try { pw.showAtLocation(editor.edit, Gravity.NO_GRAVITY, x, y) } catch (e: Exception) { completionPopup = null }
    }

    private fun closeCompletion() {
        Api.main.removeCallbacks(completionTask)
        completionGen++
        completionPopup?.dismiss(); completionPopup = null
        completionStart = -1
    }

    private fun moveSelection(d: Int) {
        if (shown.isEmpty()) return
        selected = ((selected + d) % shown.size + shown.size) % shown.size
        completionAdapter.notifyDataSetChanged()
        completionList.setSelection(maxOf(0, selected - MAX_ROWS / 2))
        updateDocs()
    }

    private fun updateDocs() {
        val it = shown.getOrNull(selected) ?: return
        fillDocs(it)
        val client = Lsp.clientFor(path, folder) ?: return
        if (it.documentation.isEmpty() && client.capability("completionProvider")?.optBoolean("resolveProvider") == true) {
            val g = ++resolveGen
            client.resolveCompletion(it) { full -> if (g == resolveGen && shown.getOrNull(selected) === it) fillDocs(full) }
        }
    }

    private fun fillDocs(it: Lsp.CompletionItem) {
        val sb = SpannableStringBuilder()
        if (it.detail.isNotEmpty()) {
            sb.append(it.detail)
            sb.setSpan(ForegroundColorSpan(Ui.fg), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.TypefaceSpan("monospace"), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (it.documentation.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(docText(it.documentation.take(4000)))
        }
        completionDocs.text = sb
        completionDocsScroll.visibility = if (sb.isEmpty()) View.INVISIBLE else View.VISIBLE
        completionDocsScroll.scrollTo(0, 0)
    }

    /** Documentation in Markdown: fenced code in monospace and the editor's colors, the rest as inline Markdown. */
    private fun docText(md: String): CharSequence {
        val out = SpannableStringBuilder()
        val parts = md.split("```")
        for ((i, part) in parts.withIndex()) {
            if (i % 2 == 0) { val t = part.trim('\n'); if (t.isNotBlank()) { if (out.isNotEmpty()) out.append("\n"); out.append(Markdown.inline(t)) } ; continue }
            val lang = part.substringBefore('\n').trim()
            val code = part.substringAfter('\n', part).trimEnd()
            if (out.isNotEmpty()) out.append("\n")
            val start = out.length
            out.append(Markdown.highlighted(lang.ifEmpty { editor.lang.id }, code))
            out.setSpan(android.text.style.TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append("\n")
        }
        return out
    }

    // ---------------------------------------------------------------- snippet tab stops
    /** Marks one tab stop in the text; the mark grows with what is typed into it. */
    private class TabStopMark : android.text.NoCopySpan
    private var snippetStops: List<TabStopMark> = emptyList()
    private var snippetAt = -1

    /** After a snippet was inserted at [base]: its stops are marked and the first one is selected. Stop 0 (the final
     *  cursor place) comes last; a stop that appears twice is visited once. */
    private fun startSnippet(base: Int, stops: List<Lsp.SnippetStop>) {
        endSnippet()
        val order = stops.filter { it.index > 0 }.distinctBy { it.index }.sortedBy { it.index } + stops.filter { it.index == 0 }.take(1)
        if (order.isEmpty()) return
        val t = editor.edit.text
        snippetStops = order.map { st ->
            val m = TabStopMark()
            t.setSpan(m, (base + st.start).coerceIn(0, t.length), (base + st.end).coerceIn(0, t.length), android.text.Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            m
        }
        goToStop(0)
    }

    private fun goToStop(i: Int) {
        val t = editor.edit.text
        val m = snippetStops.getOrNull(i.coerceAtLeast(0)) ?: run { endSnippet(); return }
        val a = t.getSpanStart(m); val b = t.getSpanEnd(m)
        if (a < 0) { endSnippet(); return }
        snippetAt = i.coerceAtLeast(0)
        editor.edit.setSelection(a, maxOf(a, b))
        // The last stop ends the snippet: the cursor stays there and Tab types a tab again.
        if (snippetAt == snippetStops.size - 1) endSnippet()
    }

    private fun endSnippet() {
        val t = editor.edit.text
        for (m in snippetStops) t.removeSpan(m)
        snippetStops = emptyList(); snippetAt = -1
    }

    /** Inserts the selected item: its own edit range when it has one, else over the typed word; then its extra edits. */
    private fun accept() {
        val it = shown.getOrNull(selected) ?: run { closeCompletion(); return }
        val start = completionStart
        closeCompletion()
        val t = editor.edit.text
        val off = editor.cursorOffset()
        var a = if (start in 0..off) start else wordStart(off)
        var b = off
        val e = it.edit
        if (e != null) {
            a = minOf(editor.offsetOf(e.line, e.col), a)
            // The end of the server's range was computed before the last keys; the word typed since then ends at the cursor.
            var end = off
            while (end < t.length && isWord(t[end])) end++
            b = maxOf(off, minOf(editor.offsetOf(e.endLine, e.endCol), end))
        }
        var text = it.insertText
        var cursorBack = 0
        // A snippet keeps its tab stops: its placeholders are selected one after the other with Tab, as in Cursor.
        var stops: List<Lsp.SnippetStop> = emptyList()
        if (it.snippet) {
            val raw = it.raw.optJSONObject("textEdit")?.optString("newText") ?: it.raw.optString("insertText").ifEmpty { it.label }
            val parsed = Lsp.parseSnippet(raw)
            text = parsed.first; stops = parsed.second
        }
        val callable = it.kind == 2 || it.kind == 3 || it.kind == 4
        if (stops.isEmpty() && callable && !text.contains('(') && (b >= t.length || t[b] != '(') && (editor.lang.id in setOf("kotlin", "java", "python", "javascript", "typescript", "go", "rust", "c", "cpp", "dart", "swift"))) {
            text += "()"
            cursorBack = 1
        }
        applying = true
        try {
            editor.replaceRange(a, b, text)
            if (cursorBack > 0) editor.edit.setSelection((a + text.length - cursorBack).coerceIn(0, editor.edit.text.length))
            if (stops.isNotEmpty()) startSnippet(a, stops)
            if (it.additional.isNotEmpty()) editor.applyEdits(it.additional)
        } finally { applying = false }
        if (cursorBack > 0) requestSignature()
        // A resolved item may bring extra edits (an import) that the list did not have.
        val client = Lsp.clientFor(path, folder)
        if (it.additional.isEmpty() && client != null && client.capability("completionProvider")?.optBoolean("resolveProvider") == true) {
            val textNow = editor.text()
            client.resolveCompletion(it) { full -> if (full.additional.isNotEmpty() && editor.text() == textNow) { applying = true; try { editor.applyEdits(full.additional) } finally { applying = false } } }
        }
    }

    private inner class CompletionAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: newRow()
            val it = shown[position]
            val badge = row.getChildAt(0) as TextView
            val label = row.getChildAt(1) as TextView
            val detail = row.getChildAt(2) as TextView
            val (letter, color) = badgeFor(it.kind)
            badge.text = letter; badge.setTextColor(color)
            val word = if (completionStart >= 0) editor.edit.text.subSequence(completionStart, editor.cursorOffset().coerceAtMost(editor.edit.text.length).coerceAtLeast(completionStart)).toString() else ""
            label.text = highlight(it.label, word)
            detail.text = it.detail.replace('\n', ' ')
            row.background = if (position == selected) Ui.rounded(0x33ff8a3d, 4) else null
            return row
        }
        private fun newRow(): LinearLayout {
            val badge = Ui.text(ctx, "", 11f, Ui.fg3, mono = true, bold = true)
            badge.gravity = Gravity.CENTER
            val label = Ui.text(ctx, "", 12.5f, Ui.fg, mono = true)
            label.isSingleLine = true; label.ellipsize = TextUtils.TruncateAt.END
            val detail = Ui.text(ctx, "", 11f, Ui.fg3, mono = true)
            detail.isSingleLine = true; detail.ellipsize = TextUtils.TruncateAt.END
            detail.gravity = Gravity.END
            val row = Ui.row(ctx, badge, label, detail)
            badge.layoutParams = Ui.lp(dp(22), Ui.wrap)
            label.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            detail.layoutParams = Ui.lp(dp(150), Ui.wrap).also { it.leftMargin = dp(6) }
            row.setPadding(dp(4), 0, dp(8), 0)
            row.minimumHeight = dp(24)
            row.layoutParams = android.widget.AbsListView.LayoutParams(Ui.match, dp(24))
            return row
        }
    }

    /** The label with the typed characters in the accent color. */
    private fun highlight(label: String, word: String): CharSequence {
        if (word.isEmpty()) return label
        val sb = SpannableStringBuilder(label)
        var i = 0
        for (ch in word) {
            var k = i
            while (k < label.length && !label[k].equals(ch, ignoreCase = true)) k++
            if (k >= label.length) break
            sb.setSpan(ForegroundColorSpan(Ui.accent), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = k + 1
        }
        return sb
    }

    // ---------------------------------------------------------------- hover
    private fun requestHover(off: Int, x: Int, y: Int) {
        if (completionPopup != null || generating) return
        Api.main.removeCallbacks(hoverDismiss)
        val diags = editor.diagnosticsAt(off)
        val word = editor.wordRangeAt(off)
        val client = Lsp.clientFor(path, folder)
        val gen = ++hoverGen
        if (client == null || !client.ready || !client.has("hoverProvider") || word.isEmpty()) {
            if (diags.isNotEmpty()) showHover(diags, "", x, y, off) else { hoverPopup?.dismiss(); hoverPopup = null }
            return
        }
        val (line, col) = editor.lineColOf(off)
        client.hover(path, line, col) { h ->
            if (gen != hoverGen || detached) return@hover
            val md = h?.markdown?.trim() ?: ""
            if (md.isEmpty() && diags.isEmpty()) { hoverPopup?.dismiss(); hoverPopup = null; return@hover }
            showHover(diags, md, x, y, off)
        }
    }

    private fun showHover(diags: List<Lsp.Diagnostic>, md: String, x: Int, y: Int, off: Int) {
        hoverPopup?.dismiss()
        val box = Ui.column(ctx)
        box.background = Ui.rounded(Ui.bg2, 6, Ui.border2)
        box.setPadding(dp(10), dp(8), dp(10), dp(8))
        for (d in diags.take(4)) {
            val icon = if (d.severity <= 1) R.drawable.ic_error else if (d.severity == 2) R.drawable.ic_problems else R.drawable.ic_info
            val msg = Ui.text(ctx, d.describe(), 12f, Ui.fg)
            msg.setTextIsSelectable(true)
            val r = Ui.row(ctx, Ui.iconView(ctx, icon, CodeEditor.severityColor(d.severity), 14), msg)
            msg.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(6) }
            r.gravity = Gravity.TOP
            box.addView(r, Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = dp(4) })
        }
        if (diags.isNotEmpty()) {
            val acts = Ui.row(ctx,
                link(R.drawable.ic_bulb, "Quick fix") { hoverPopup?.dismiss(); editor.edit.setSelection(off); codeActions() },
                link(R.drawable.ic_sparkle, "Fix with AI") { hoverPopup?.dismiss(); fixWithAi(diags.first()) })
            box.addView(acts, Ui.lp(Ui.wrap, Ui.wrap).also { it.bottomMargin = if (md.isNotEmpty()) dp(6) else 0 })
            if (md.isNotEmpty()) box.addView(Ui.divider(ctx, false), Ui.lp(Ui.match, 1).also { it.bottomMargin = dp(6) })
        }
        if (md.isNotEmpty()) {
            val content = Ui.column(ctx)
            Markdown.render(ctx, md.take(12000), content) { s -> host.copyToClipboard(s) }
            val sc = ScrollView(ctx); sc.addView(content)
            box.addView(sc, Ui.lp(Ui.match, Ui.wrap))
        }
        box.measure(View.MeasureSpec.makeMeasureSpec(dp(560), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.AT_MOST))
        val w = box.measuredWidth.coerceIn(dp(200), dp(560)); val h = box.measuredHeight.coerceAtMost(dp(360))
        val pw = PopupWindow(box, w, h, false)
        pw.isTouchable = true; pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(8).toFloat()
        box.setOnHoverListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> Api.main.removeCallbacks(hoverDismiss)
                MotionEvent.ACTION_HOVER_EXIT -> { Api.main.removeCallbacks(hoverDismiss); Api.main.postDelayed(hoverDismiss, 300) }
            }
            false
        }
        val loc = IntArray(2); editor.edit.getLocationInWindow(loc)
        val lineH = editor.edit.lineHeight
        var py = loc[1] + y - h - lineH / 2
        if (py < dp(40)) py = loc[1] + y + lineH
        val px = (loc[0] + x - dp(20)).coerceIn(0, (editor.rootView.width - w).coerceAtLeast(0))
        hoverPopup = pw
        try { pw.showAtLocation(editor.edit, Gravity.NO_GRAVITY, px, py) } catch (e: Exception) { hoverPopup = null }
    }

    private fun link(icon: Int, label: String, onClick: () -> Unit): View {
        val t = Ui.text(ctx, label, 12f, Ui.accent2)
        val r = Ui.row(ctx, Ui.iconView(ctx, icon, Ui.accent2, 14), t)
        t.setPadding(dp(4), 0, dp(12), 0)
        r.setPadding(0, dp(2), 0, dp(2))
        r.setOnClickListener { onClick() }
        return r
    }

    private fun fixWithAi(d: Lsp.Diagnostic) = fixAllWithAi(listOf(d))

    /** Every problem given goes to the agent with the file, one per line of the request. */
    private fun fixAllWithAi(list: List<Lsp.Diagnostic>) {
        val text = if (list.size == 1) "Fix this problem in $path at line ${list[0].line + 1}: ${list[0].describe()}"
            else "Fix these problems in $path:\n" + list.joinToString("\n") { "- line ${it.line + 1}: ${it.describe()}" }
        host.quickAsk(text, "agent", JSONObject().put("kind", "file").put("path", path).put("name", path))
    }

    // ---------------------------------------------------------------- signature help
    private fun requestSignature() {
        val client = Lsp.clientFor(path, folder) ?: return
        if (!client.ready || !client.has("signatureHelpProvider")) return
        val (line, col) = editor.cursorLineCol()
        val gen = ++signatureGen
        client.signatureHelp(path, line, col) { sh ->
            if (gen != signatureGen || detached) return@signatureHelp
            if (sh == null || sh.signatures.isEmpty()) { signaturePopup?.dismiss(); signaturePopup = null; return@signatureHelp }
            showSignature(sh)
        }
    }

    private fun showSignature(sh: Lsp.SignatureHelp) {
        val sig = sh.signatures.getOrNull(sh.activeSignature) ?: sh.signatures[0]
        val sb = SpannableStringBuilder(sig.label)
        val param = sig.parameters.getOrNull(sh.activeParameter)
        if (param != null && param.first.isNotEmpty()) {
            val i = sig.label.indexOf(param.first)
            if (i >= 0) {
                sb.setSpan(ForegroundColorSpan(Ui.accent), i, i + param.first.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), i, i + param.first.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val box = Ui.column(ctx)
        box.background = Ui.rounded(Ui.bg2, 6, Ui.border2)
        box.setPadding(dp(10), dp(6), dp(10), dp(6))
        val head = Ui.text(ctx, sb, 12.5f, Ui.fg, mono = true)
        if (sh.signatures.size > 1) {
            val n = Ui.text(ctx, "${sh.activeSignature + 1} of ${sh.signatures.size}  ", 11f, Ui.fg3)
            box.addView(Ui.row(ctx, n, head))
        } else box.addView(head)
        val doc = param?.second?.ifEmpty { null } ?: sig.documentation
        if (doc.isNotEmpty()) box.addView(Ui.text(ctx, Markdown.inline(doc.take(600)), 11.5f, Ui.fg2).also { it.setPadding(0, dp(4), 0, 0) })
        box.measure(View.MeasureSpec.makeMeasureSpec(dp(640), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.AT_MOST))
        val w = box.measuredWidth.coerceAtLeast(dp(120)); val h = box.measuredHeight
        val rect = editor.cursorRect()
        val loc = IntArray(2); editor.edit.getLocationInWindow(loc)
        val px = (loc[0] + rect.left - dp(20)).coerceIn(0, (editor.rootView.width - w).coerceAtLeast(0))
        var py = loc[1] + rect.top - h - dp(4)
        if (py < dp(40)) py = loc[1] + rect.bottom + dp(4)
        val old = signaturePopup
        if (old != null && old.isShowing) { old.contentView = box; old.update(px, py, w, h); return }
        val pw = PopupWindow(box, w, h, false)
        pw.isTouchable = false
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(6).toFloat()
        signaturePopup = pw
        try { pw.showAtLocation(editor.edit, Gravity.NO_GRAVITY, px, py) } catch (e: Exception) { signaturePopup = null }
    }

    // ---------------------------------------------------------------- navigation
    private fun clientOrSay(): LspClient? {
        val c = Lsp.clientFor(path, folder)
        if (c == null) { Ui.toast(ctx, if (Lsp.serverFor(path) == null) "No language server knows this kind of file." else "The language server is not running (Settings, Language servers)."); return null }
        if (!c.ready) { Ui.toast(ctx, "The language server is still starting."); return null }
        return c
    }

    private fun goToLocation(method: String, cap: String, off: Int) {
        val client = clientOrSay() ?: return
        val (line, col) = editor.lineColOf(off)
        // The words match what is asked for: a definition or an implementation.
        val impl = method == "textDocument/implementation"
        client.locations(method, cap, path, line, col, false) { list, err ->
            if (detached) return@locations
            when {
                err != null && list.isEmpty() -> Ui.toast(ctx, err)
                list.isEmpty() -> Ui.toast(ctx, if (impl) "No implementation found for this symbol." else "No definition found for this symbol.")
                list.size == 1 -> panel.openAt(list[0].path, list[0].line, list[0].col)
                else -> pickLocation(if (impl) "Implementations" else "Definitions", list)
            }
        }
    }

    fun goToDefinition() = goToLocation("textDocument/definition", "definitionProvider", editor.cursorOffset())
    fun goToImplementation() = goToLocation("textDocument/implementation", "implementationProvider", editor.cursorOffset())

    /** The definition at the cursor (the first one when there are several), for Open Definition to the Side. */
    fun findDefinition(done: (Lsp.Location?) -> Unit) {
        val client = clientOrSay() ?: return
        val (line, col) = editor.cursorLineCol()
        client.locations("textDocument/definition", "definitionProvider", path, line, col, false) { list, err ->
            if (detached) return@locations
            if (list.isEmpty() && err != null) { Ui.toast(ctx, err); return@locations }
            done(list.firstOrNull())
        }
    }

    /** A small list of places (several definitions or implementations) to choose from. */
    private fun pickLocation(title: String, list: List<Lsp.Location>) {
        val labels = list.map { it.path + ":" + (it.line + 1) + ":" + (it.col + 1) }
        AlertDialog.Builder(ctx).setTitle(title).setItems(labels.toTypedArray()) { _, i -> panel.openAt(list[i].path, list[i].line, list[i].col) }
            .setNegativeButton("Close", null).show()
    }

    /** Alt F12: the definition's lines in a small window, without leaving this file. */
    fun peekDefinition() {
        val client = clientOrSay() ?: return
        val (line, col) = editor.cursorLineCol()
        client.locations("textDocument/definition", "definitionProvider", path, line, col, false) { list, err ->
            val loc = list.firstOrNull()
            if (loc == null) { Ui.toast(ctx, err ?: "No definition found for this symbol."); return@locations }
            Api.async("peek", { Api.get("/api/file", mapOf("path" to loc.path)) }) { r, e ->
                if (r == null) { Ui.toast(ctx, "Cannot read " + loc.path + ": " + (e?.message ?: "")); return@async }
                val lines = r.optString("content").split('\n')
                val from = (loc.line - 3).coerceAtLeast(0); val to = (loc.line + 18).coerceAtMost(lines.size)
                val sb = SpannableStringBuilder()
                for (i in from until to) {
                    val start = sb.length
                    sb.append(String.format("%5d  ", i + 1)).append(lines[i]).append('\n')
                    if (i == loc.line) sb.setSpan(android.text.style.BackgroundColorSpan(0x33ff8a3d), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val t = Ui.text(ctx, sb, 12f, Ui.fg, mono = true)
                t.setTextIsSelectable(true)
                t.setPadding(dp(10), dp(8), dp(10), dp(8))
                val hs = android.widget.HorizontalScrollView(ctx); hs.addView(t)
                val sc = ScrollView(ctx); sc.addView(hs)
                AlertDialog.Builder(ctx).setTitle(loc.path + ":" + (loc.line + 1)).setView(sc)
                    .setPositiveButton("Open") { _, _ -> panel.openAt(loc.path, loc.line, loc.col) }
                    .setNegativeButton("Close", null).show()
            }
        }
    }

    /** Shift F12: every reference, grouped by file, with the line's text; a click opens the place. */
    fun references() {
        val client = clientOrSay() ?: return
        val (line, col) = editor.cursorLineCol()
        val r = editor.wordRangeAt(editor.cursorOffset())
        val word = if (r.isEmpty()) "" else editor.edit.text.subSequence(r.first, r.last + 1).toString()
        client.locations("textDocument/references", "referencesProvider", path, line, col, true) { list, err ->
            if (detached) return@locations
            if (list.isEmpty()) { Ui.toast(ctx, err ?: "No references found."); return@locations }
            showReferences(word, list)
        }
    }

    private fun showReferences(word: String, list: List<Lsp.Location>) {
        val rows = ArrayList<Any>()   // a String is a file header, a Location is a hit
        val texts = HashMap<String, List<String>>()
        for ((p, hits) in list.groupBy { it.path }) { rows.add(p); rows.addAll(hits.sortedBy { it.line }) }
        val lv = ListView(ctx)
        lv.divider = null
        lv.selector = ColorDrawable(Color.TRANSPARENT)
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val x = rows[position]
                if (x is String) {
                    val t = (convertView as? TextView) ?: Ui.text(ctx, "", 12f, Ui.accent2, bold = true).also { it.setPadding(dp(12), dp(8), dp(12), dp(2)) }
                    t.text = x + "  (" + list.count { it.path == x } + ")"
                    return t
                }
                val loc = x as Lsp.Location
                val t = (convertView as? TextView) ?: Ui.text(ctx, "", 12f, Ui.fg2, mono = true).also { it.setPadding(dp(24), dp(2), dp(12), dp(2)); it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END }
                val src = texts[loc.path]?.getOrNull(loc.line)?.trim() ?: ""
                t.text = String.format("%5d  ", loc.line + 1) + src
                return t
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ -> val x = rows[pos]; if (x is Lsp.Location) panel.openAt(x.path, x.line, x.col) }
        val files = list.map { it.path }.distinct().take(40)
        for (p in files) {
            val open = panel.textOf(p)
            if (open != null) texts[p] = open.split('\n')
            else Api.async("ref-file", { Api.get("/api/file", mapOf("path" to p)) }) { r, _ -> if (r != null) { texts[p] = r.optString("content").split('\n'); adapter.notifyDataSetChanged() } }
        }
        val dialog = AlertDialog.Builder(ctx).setTitle("${list.size} references" + (if (word.isNotEmpty()) " to $word" else "")).setView(lv)
            .setNegativeButton("Close", null).create()
        dialog.show()
        dialog.window?.setLayout(dp(820), dp(520))
    }

    // ---------------------------------------------------------------- rename, format, quick fixes
    fun renameDialog() {
        val client = clientOrSay() ?: return
        val off = editor.cursorOffset()
        val r = editor.wordRangeAt(off)
        if (r.isEmpty()) { Ui.toast(ctx, "Put the cursor on a name to rename it."); return }
        val old = editor.edit.text.subSequence(r.first, r.last + 1).toString()
        val (line, col) = editor.lineColOf(r.first)
        val input = Ui.input(ctx, "New name", mono = true)
        input.setText(old); input.selectAll()
        input.isSingleLine = true
        val dialog = AlertDialog.Builder(ctx).setTitle("Rename $old").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == old) return@setPositiveButton
                client.rename(path, line, col, name) { edits, err ->
                    if (edits == null) { Ui.toast(ctx, "Not renamed: " + (err ?: "no answer")); return@rename }
                    if (edits.isEmpty()) { Ui.toast(ctx, "The language server found nothing to rename."); return@rename }
                    val n = panel.applyWorkspaceEdit(edits)
                    Ui.toast(ctx, "Renamed $old to $name in $n " + (if (n == 1) "file" else "files") + ". The changed files are not saved yet.")
                }
            }.setNegativeButton("Cancel", null).create()
        input.setOnEditorActionListener { _, _, _ -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick(); true }
        input.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) { dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick(); true } else false
        }
        dialog.show()
        input.requestFocus()
    }

    fun format() {
        val client = clientOrSay() ?: return
        val before = editor.text()
        client.formatting(path, editor.lang.tabSize, true) { edits, err ->
            if (edits == null) { Ui.toast(ctx, err ?: "Not formatted."); return@formatting }
            if (editor.text() != before) { Ui.toast(ctx, "The file changed while it was being formatted. Try again."); return@formatting }
            if (edits.isEmpty()) { Ui.toast(ctx, "Already formatted."); return@formatting }
            applying = true
            try { editor.applyEdits(edits) } finally { applying = false }
        }
    }

    /** Ctrl . : the server's quick fixes for the cursor's line, plus Fix with AI, which always works. */
    fun codeActions() {
        val off = editor.cursorOffset()
        val (line, col) = editor.lineColOf(off)
        val sStart = minOf(editor.edit.selectionStart, editor.edit.selectionEnd); val sEnd = maxOf(editor.edit.selectionStart, editor.edit.selectionEnd)
        val (l1, c1) = editor.lineColOf(sStart); val (l2, c2) = editor.lineColOf(sEnd)
        val diags = editor.diagnosticsOnLine(line)
        val client = Lsp.clientFor(path, folder)
        fun show(actions: List<Lsp.CodeAction>) {
            val titles = ArrayList<String>()
            for (a in actions) titles.add((if (a.preferred) "★ " else "") + a.title)
            val aiTitle = if (diags.size > 1) "Fix the " + diags.size + " problems on this line with AI" else if (diags.isNotEmpty()) "Fix with AI: " + diags.first().message.take(80) else "Improve this code with AI (Ctrl K)"
            titles.add(aiTitle)
            AlertDialog.Builder(ctx).setTitle(if (actions.isEmpty()) "No quick fixes from the language server" else "Quick fixes")
                .setItems(titles.toTypedArray()) { _, i ->
                    if (i >= actions.size) {
                        if (diags.isNotEmpty()) fixAllWithAi(diags)
                        else openInlineEdit(prefill = "Improve this code")
                        return@setItems
                    }
                    val chosen = actions[i]
                    fun run(a: Lsp.CodeAction) {
                        if (a.edits.isNotEmpty()) panel.applyWorkspaceEdit(a.edits)
                        a.command?.let { cmd -> client?.executeCommand(cmd) { err -> if (err != null) Ui.toast(ctx, "The fix failed: $err") } }
                    }
                    val raw = chosen.raw
                    if (raw != null && client != null) client.resolveCodeAction(raw) { full, err -> if (full == null) Ui.toast(ctx, "The fix failed: $err") else run(full) }
                    else run(chosen)
                }.setNegativeButton("Close", null).show()
        }
        if (client == null || !client.ready || !client.has("codeActionProvider")) { show(emptyList()); return }
        val range = if (sStart != sEnd) intArrayOf(l1, c1, l2, c2) else intArrayOf(line, col, line, col)
        client.codeActions(path, range[0], range[1], range[2], range[3], diags) { actions, _ -> if (!detached) show(actions) }
    }

    // ---------------------------------------------------------------- self-tests for the window's snapshot mode
    /** The content of the open popup (completion list, hover or signature help), for a snapshot. */
    fun popupView(): View? = completionPopup?.contentView ?: hoverPopup?.contentView ?: signaturePopup?.contentView ?: inlineBar?.contentView

    fun testCompletion(line: Int, col: Int) { editor.goTo(line, col); editor.focus(); Api.main.postDelayed({ requestCompletion(null) }, 300) }

    fun testHover(line: Int, col: Int) {
        editor.goTo(line, col)
        Api.main.postDelayed({ val r = editor.cursorRect(); requestHover(editor.offsetOf(line, col), r.left, r.top) }, 300)
    }

    fun testInline(line0: Int, line1: Int, instruction: String) {
        editor.edit.setSelection(editor.offsetOf(line0, 0), editor.offsetOf(line1, 1000))
        openInlineEdit(instruction, whole = false, auto = true)
    }

    fun testGhost(line: Int, col: Int) { editor.goTo(line, col); editor.focus(); Api.main.postDelayed({ requestGhost(true) }, 300) }

    // ---------------------------------------------------------------- the editor's right-click menu
    /** Clipboard actions, the AI actions (Add to chat, Edit with AI, Explain, Fix) and the language server's navigation. */
    private fun contextMenu(x: Int, y: Int) {
        val anchor = View(ctx)
        val root = editor.rootView as? ViewGroup ?: return
        val loc = IntArray(2); editor.edit.getLocationInWindow(loc)
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        // A one-pixel anchor at the pointer, so the menu opens where the mouse is.
        root.addView(anchor, ViewGroup.LayoutParams(1, 1))
        anchor.x = (loc[0] - rootLoc[0] + x).toFloat(); anchor.y = (loc[1] - rootLoc[1] + y).toFloat()
        val menu = android.widget.PopupMenu(ctx, anchor)
        val hasSel = editor.edit.selectionStart != editor.edit.selectionEnd
        val diags = editor.diagnosticsOnLine(editor.cursorLineCol().first)
        val lsp = Lsp.clientFor(path, folder)?.ready == true
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun add(label: String, enabled: Boolean = true, run: () -> Unit) { if (enabled) items.add(label to run) }
        add("Add the selection to the chat  (Ctrl Shift L)", hasSel) { host.editorSelection()?.let { host.addToChat(it) } }
        add("Edit with AI  (Ctrl K)") { openInlineEdit() }
        add("Explain this code", hasSel) { host.quickAsk("Explain this code.", "ask", host.editorSelection()) }
        add(if (diags.size > 1) "Fix the " + diags.size + " problems here with AI" else "Fix the problem here with AI", diags.isNotEmpty()) { fixAllWithAi(diags) }
        add("Go to the definition  (F12)", lsp) { goToDefinition() }
        add("Peek the definition  (Alt F12)", lsp) { peekDefinition() }
        add("Find all references  (Shift F12)", lsp) { references() }
        add("Rename the symbol  (F2)", lsp) { renameDialog() }
        add("Quick fix  (Ctrl .)") { codeActions() }
        add("Format the document  (Shift Alt F)", lsp) { format() }
        add("Cut", hasSel) { cutOrCopy(true) }
        add("Copy", hasSel) { cutOrCopy(false) }
        add("Paste") { editor.edit.onTextContextMenuItem(android.R.id.paste) }
        add("Select all") { editor.edit.selectAll() }
        for ((i, it) in items.withIndex()) menu.menu.add(0, i, i, it.first)
        menu.setOnMenuItemClickListener { item -> items.getOrNull(item.itemId)?.second?.invoke(); true }
        menu.setOnDismissListener { root.removeView(anchor) }
        menu.show()
    }

    private fun cutOrCopy(cut: Boolean) {
        val a = minOf(editor.edit.selectionStart, editor.edit.selectionEnd); val b = maxOf(editor.edit.selectionStart, editor.edit.selectionEnd)
        if (a >= b) return
        host.copyToClipboard(editor.edit.text.subSequence(a, b).toString())
        if (cut) editor.replaceRange(a, b, "")
    }

    // ---------------------------------------------------------------- AI Tab completions
    private fun scheduleGhost() {
        Api.main.removeCallbacks(ghostTask)
        if (!ForgeSettings.bool("tab_completions", false)) return
        Api.main.postDelayed(ghostTask, ForgeSettings.int("completion_delay_ms", 1200).toLong().coerceIn(150, 10000))
    }

    /** Asks the model for a short continuation at the cursor; the answer shows as ghost text that Tab inserts. */
    /** Ctrl Space: suggestions now; when no language server can give them, the reason is said instead of nothing. */
    fun requestCompletionPublic() { if (clientOrSay() != null) requestCompletion(null) }

    /** The inline edit box's input, while the box is open (Ctrl Shift K moves the focus between it and the code). */
    private var inlineInput: EditText? = null
    fun inlineOpen(): Boolean = inlineBar?.isShowing == true
    fun focusInline() {
        val pw = inlineBar ?: return
        pw.isFocusable = true; pw.update()
        inlineInput?.requestFocus()
    }
    fun requestSignaturePublic() = requestSignature()

    /** Ctrl K Ctrl I: the hover of the symbol at the cursor, as if the pointer rested on it. */
    fun hoverAtCursor() {
        val r = editor.cursorRect()
        requestHover(editor.cursorOffset(), r.left, r.bottom)
    }

    /** Ctrl K Ctrl F: the language server formats only the selected lines. */
    fun formatSelection() {
        val client = clientOrSay() ?: return
        val a = minOf(editor.edit.selectionStart, editor.edit.selectionEnd); val b = maxOf(editor.edit.selectionStart, editor.edit.selectionEnd)
        if (a == b) { format(); return }
        val (l1, c1) = editor.lineColOf(a); val (l2, c2) = editor.lineColOf(b)
        val before = editor.text()
        client.rangeFormatting(path, l1, c1, l2, c2, editor.lang.tabSize, true) { edits, err ->
            if (edits == null) { Ui.toast(ctx, err ?: "Not formatted."); return@rangeFormatting }
            if (editor.text() != before) { Ui.toast(ctx, "The file changed while it was being formatted. Try again."); return@rangeFormatting }
            if (edits.isEmpty()) { Ui.toast(ctx, "Already formatted."); return@rangeFormatting }
            applying = true
            try { editor.applyEdits(edits) } finally { applying = false }
        }
    }

    fun requestGhost(manual: Boolean) {
        if (detached || generating) return
        if (!manual && (!ForgeSettings.bool("tab_completions", false) || completionPopup != null)) return
        if (!editor.edit.hasFocus() && !manual) return
        val t = editor.edit.text
        val off = editor.cursorOffset()
        if (editor.edit.selectionStart != editor.edit.selectionEnd) return
        // Only at the end of a line or before closing characters, where an insertion cannot collide with code.
        var k = off
        while (k < t.length && t[k] != '\n') { if (!(t[k].isWhitespace() || t[k] in ")]}\"';,")) { if (!manual) return else break }; k++ }
        val prefix = t.subSequence(maxOf(0, off - 6000), off).toString()
        if (prefix.trim().length < 3) return
        val suffix = t.subSequence(off, minOf(t.length, off + 2000)).toString()
        val gen = ++ghostGen
        val version = editor.text().hashCode()
        host.setTabStatus("Tab: thinking")
        val body = JSONObject().put("path", path).put("language", editor.lang.id).put("prefix", prefix).put("suffix", suffix)
        Api.async("complete", { Api.post("/api/complete", body, 90000) }) { r, e ->
            host.setTabStatus(null)
            if (gen != ghostGen || detached) return@async
            if (r == null) {
                if (manual) Ui.toast(ctx, "No completion: " + (e?.message ?: "no answer"))
                // A refusal for lack of memory is said once in the status bar, so missing suggestions have a reason.
                else if ((e as? Api.ApiError)?.code == 503) host.flashStatus("Tab completions wait: " + (e.message ?: ""))
                return@async
            }
            val text = r.optString("text")
            if (text.isEmpty()) { if (manual) Ui.toast(ctx, "The model had nothing to add here."); return@async }
            if (editor.cursorOffset() != off || editor.text().hashCode() != version) return@async
            editor.showGhost(text)
        }
    }

    // ---------------------------------------------------------------- Ctrl K: inline edit
    /** Opens the instruction bar over the selection (or over the cursor's line when nothing is selected). With
     *  [whole] the whole file is the selection; [prefill] goes into the bar and [auto] runs it at once (Apply from the chat). */
    fun openInlineEdit(prefill: String = "", whole: Boolean = false, auto: Boolean = false) {
        if (generating) return
        inlineBar?.dismiss()
        dismissPopups(keepInline = true)
        val t = editor.edit.text
        if (whole) editor.edit.setSelection(0, t.length)
        var s = minOf(editor.edit.selectionStart, editor.edit.selectionEnd).coerceAtLeast(0)
        var e = maxOf(editor.edit.selectionStart, editor.edit.selectionEnd).coerceAtLeast(0)
        val hasSelection = s != e
        if (hasSelection) {
            // Whole lines, like the old page: from the start of the first line to the end of the last.
            s = TextUtils.lastIndexOf(t, '\n', s - 1) + 1
            if (e > s && t[e - 1] == '\n') e--
            val nl = TextUtils.indexOf(t, '\n', e); e = if (nl < 0) t.length else nl
            editor.edit.setSelection(s, e)
        }
        val input = Ui.input(ctx, if (hasSelection) "Edit the selected code: describe the change" else "Generate code here: describe what to write")
        input.isSingleLine = true
        input.imeOptions = EditorInfo.IME_ACTION_GO
        val status = Ui.text(ctx, if (hasSelection) "Lines " + (editor.lineColOf(s).first + 1) + " to " + (editor.lineColOf(e).first + 1) else "At line " + (editor.lineColOf(s).first + 1), 11.5f, Ui.fg3)
        status.isSingleLine = true
        val send = Ui.miniButton(ctx, R.drawable.ic_send, "Generate (Enter)") { }
        val close = Ui.miniButton(ctx, R.drawable.ic_close, "Close (Escape)") { }
        val spark = Ui.iconView(ctx, R.drawable.ic_sparkle, Ui.accent, 16)
        val row = Ui.row(ctx, spark, input, status, send, close)
        input.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(8) }
        status.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(8); it.rightMargin = dp(4) }
        send.layoutParams = Ui.lp(dp(28), dp(28)); close.layoutParams = Ui.lp(dp(28), dp(28))
        row.background = Ui.rounded(Ui.bg3, 8, Ui.accent)
        row.setPadding(dp(10), dp(4), dp(6), dp(4))
        val width = (editor.edit.width - dp(40)).coerceIn(dp(360), dp(900))
        val pw = PopupWindow(row, width, WindowManager.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(10).toFloat()
        pw.isOutsideTouchable = false
        pw.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        pw.setOnDismissListener { if (inlineBar === pw) inlineBar = null; if (!generating) editor.focus() }
        inlineBar = pw
        val l = editor.edit.layout
        val loc = IntArray(2); editor.edit.getLocationInWindow(loc)
        var y = loc[1] + dp(6)
        if (l != null) {
            val line = l.getLineForOffset(s)
            y = loc[1] + l.getLineTop(line) + editor.edit.extendedPaddingTop - editor.edit.scrollY - dp(46)
            if (y < loc[1]) y = loc[1] + l.getLineBottom(l.getLineForOffset(e)) + editor.edit.extendedPaddingTop - editor.edit.scrollY + dp(4)
            y = y.coerceIn(loc[1], loc[1] + editor.edit.height - dp(44))
        }
        val x = loc[0] + dp(20)
        val start = s; val end = e
        fun go() {
            val instruction = input.text.toString().trim()
            if (instruction.isEmpty() || generating) return
            input.isEnabled = false
            runInline(instruction, start, end, status, pw) { input.isEnabled = true; input.requestFocus() }
        }
        send.setOnClickListener { go() }
        close.setOnClickListener { if (generating) cancelInline() else pw.dismiss() }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        input.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                // Alt Enter: a quick question about the code instead of an edit; the answer appears in the AI panel.
                (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && ev.isAltPressed -> {
                    val q = input.text.toString().trim()
                    if (q.isNotEmpty()) {
                        val code0 = editor.text().substring(start.coerceIn(0, editor.text().length), end.coerceIn(start, editor.text().length))
                        val rec = if (code0.isNotEmpty()) JSONObject().put("kind", "text").put("title", path.substringAfterLast('/') + " " + (editor.lineColOf(start).first + 1) + "-" + (editor.lineColOf(end).first + 1)).put("name", path).put("text", code0) else null
                        pw.dismiss(); host.quickAsk(q, "ask", rec)
                    }
                    true
                }
                code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> { go(); true }
                code == KeyEvent.KEYCODE_ESCAPE -> { if (generating) cancelInline() else pw.dismiss(); true }
                // Ctrl Shift Backspace cancels, as in Cursor.
                code == KeyEvent.KEYCODE_DEL && ev.isCtrlPressed && ev.isShiftPressed -> { if (generating) cancelInline() else pw.dismiss(); true }
                // Ctrl Shift K: the focus goes back to the code while the box stays open (and Ctrl Shift K there returns).
                code == KeyEvent.KEYCODE_K && ev.isCtrlPressed && ev.isShiftPressed -> { pw.isFocusable = false; pw.update(); editor.focus(); true }
                else -> false
            }
        }
        inlineInput = input
        try { pw.showAtLocation(editor.edit, Gravity.NO_GRAVITY, x, y) } catch (ex: Exception) { android.util.Log.w("ForgeSmarts", "inline bar not shown", ex); inlineBar = null; return }
        if (prefill.isNotEmpty()) input.setText(prefill)
        input.requestFocus()
        if (auto && prefill.isNotEmpty()) go()
    }

    private fun runInline(instruction: String, start: Int, end: Int, status: TextView, bar: PopupWindow, retry: () -> Unit) {
        val full = editor.text()
        val selection = full.substring(start, end)
        val before = full.substring(0, start)
        val after = full.substring(end)
        generating = true
        status.setTextColor(Ui.fg3)
        status.text = "Generating…"
        val body = JSONObject().put("session_id", "").put("path", path).put("language", editor.lang.id).put("selection", selection)
            .put("instruction", instruction).put("before", before).put("after", after)
        var raw = ""
        var shownLen = selection.length
        var finalCode: String? = null
        android.util.Log.i("ForgeSmarts", "inline edit: " + instruction.take(60) + " on " + (end - start) + " characters")
        Api.async("inline-edit", { Api.post("/api/inline_edit", body, 30000) }) { r, e ->
            if (r == null) { android.util.Log.w("ForgeSmarts", "inline edit refused: " + e?.message); generating = false; status.setTextColor(Ui.red); status.text = "Failed: " + (e?.message ?: "no answer"); retry(); return@async }
            inlineRunId = r.optString("run_id")
            val stream = Api.EventStream(inlineRunId) { ev ->
                when (ev.optString("type")) {
                    "code_delta" -> {
                        raw += ev.optString("text")
                        val partial = partialCode(raw)
                        if (partial != null) {
                            // The code appears in the editor as it is written; the final version replaces it as one undo step.
                            editor.mirrorEdit(start, shownLen, partial)
                            shownLen = partial.length
                        }
                        status.text = "Generating, " + raw.length + " characters"
                    }
                    "code" -> finalCode = ev.optString("text")
                    "stats" -> { val tps = ev.optDouble("gen_tps", 0.0); if (tps > 0) status.text = "Done, " + String.format("%.1f", tps) + " tokens per second" }
                    "error" -> {
                        status.setTextColor(Ui.red); status.text = ev.optString("message").take(160)
                        // The connection to the server was lost for good: no "end" follows, so the edit ends here, the
                        // selection gets its original text back and the editor takes keys again.
                        if (ev.optBoolean("lost") && generating) {
                            generating = false
                            inlineStream = null
                            editor.mirrorEdit(start, shownLen, selection)
                            retry()
                        }
                    }
                    "end" -> {
                        android.util.Log.i("ForgeSmarts", "inline edit ended (" + ev.optString("status") + "): " + (finalCode?.length ?: -1) + " characters of code")
                        if (!generating) return@EventStream
                        generating = false
                        inlineStream = null
                        // Back to the original text first, so the final change is recorded (and mirrored) against it.
                        editor.mirrorEdit(start, shownLen, selection)
                        val code = finalCode
                        // A cancelled edit (Escape, the X) applies nothing, not even what was written so far.
                        if (code == null || ev.optString("status") == "cancelled") { if (status.currentTextColor != Ui.red) status.text = "Cancelled"; retry(); return@EventStream }
                        val base = editor.text()
                        editor.replaceRange(start, end, code)
                        bar.dismiss()
                        panel.startReview(path, base, "from Ctrl K", inline = true)
                        editor.focus()
                    }
                }
            }
            inlineStream = stream
            stream.start()
        }
    }

    /** The code inside the first fenced block of a partial answer, or the text itself when no fence has started. */
    private fun partialCode(raw: String): String? {
        val fence = raw.indexOf("```")
        if (fence < 0) return null
        val nl = raw.indexOf('\n', fence)
        if (nl < 0) return null
        val body = raw.substring(nl + 1)
        val close = body.indexOf("```")
        return (if (close >= 0) body.substring(0, close) else body).trimEnd('\n')
    }

    fun cancelInline() {
        if (inlineRunId.isNotEmpty() && generating) {
            val id = inlineRunId
            Api.async("cancel", { Api.post("/api/cancel", JSONObject().put("run_id", id)) }) { _, _ -> }
        }
    }
}
