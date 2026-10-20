package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Jupyter notebooks (.ipynb) as native cells, ported from the old page's notebook.js: code cells with their own
 * editor and outputs (text, errors, images, rich data), Markdown cells rendered with Markdown.kt and edited in
 * place on a double tap, and a kernel that keeps variables between cells.
 *
 * Server API (forge/server.py, forge/notebook.py, forge/kernel_runner.py, forge/jupyter_runner.py):
 *  - GET /api/file?path=            -> {path, content, mtime, size}: the notebook's JSON text.
 *  - POST /api/file {path, content} -> {ok, path, mtime, check}: Save writes the JSON (indent 1, like the page).
 *  - GET /api/notebook/kernels      -> {kernels: [{name, display_name, language}]}.
 *  - WebSocket /ws/kernel?folder=&path= : the window sends {op: "execute", id, code}, {op: "interrupt"},
 *    {op: "restart"}, {op: "shutdown"}; the server sends JSON messages with a "type":
 *      attached {busy, info}, ready {python, pid} or {kernel, display_name, language, version}, restarted,
 *      stream {id, name, text}, result {id, count, data}, display {id, data}, error {id, ename, evalue, traceback},
 *      done {id, count, status}, exit {message, code?, id?}, completions {id, items}.
 *    The server restarts a dead kernel by itself when an execute arrives, and sends "restarted" before the
 *    cell's output; the page used to clear its running cells on that message and lost those outputs, so this
 *    version clears them only after a restart the user asked for.
 */
class NotebookPanel(private val ctx: Context, private val host: MainActivity) {
    companion object {
        private val ANSI = Regex("\u001b\\[[0-9;]*[A-Za-z]")
        private const val MAX_HIGHLIGHT_CHARS = 60000
        private const val MAX_IMAGE_PX = 2048
        private val HIGHLIGHT_LANG = mapOf("python" to "python", "kotlin" to "kotlin", "bash" to "shell", "sh" to "shell", "shell" to "shell",
            "rust" to "rust", "javascript" to "javascript", "typescript" to "typescript", "java" to "java", "c++" to "cpp", "cpp" to "cpp", "c" to "c")

        private fun uid(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return String(CharArray(8) { chars[Random.nextInt(chars.length)] })
        }

        /** A cell's source as text: the ipynb format stores it as a list of lines or as one string. */
        fun srcText(v: Any?): String = when (v) {
            null, JSONObject.NULL -> ""
            is JSONArray -> { val sb = StringBuilder(); for (i in 0 until v.length()) sb.append(v.optString(i)); sb.toString() }
            else -> v.toString()
        }

        /** Text as the ipynb list of lines, each keeping its line break except the last. */
        fun toLines(t: String): JSONArray {
            val a = JSONArray()
            val parts = t.split("\n")
            for ((i, p) in parts.withIndex()) {
                val last = i == parts.size - 1
                if (last && p.isEmpty()) continue
                a.put(if (last) p else p + "\n")
            }
            return a
        }

        private fun newCodeCell(): JSONObject = JSONObject().put("cell_type", "code").put("id", uid()).put("metadata", JSONObject())
            .put("execution_count", JSONObject.NULL).put("outputs", JSONArray()).put("source", JSONArray())

        private fun newMarkdownCell(): JSONObject = JSONObject().put("cell_type", "markdown").put("id", uid()).put("metadata", JSONObject()).put("source", JSONArray())

        fun emptyNotebook(): JSONObject = JSONObject()
            .put("cells", JSONArray().put(newCodeCell()))
            .put("metadata", JSONObject()
                .put("kernelspec", JSONObject().put("display_name", "Python 3").put("language", "python").put("name", "python3"))
                .put("language_info", JSONObject().put("name", "python")))
            .put("nbformat", 4).put("nbformat_minor", 5)

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** A rough plain-text form of an HTML output (tables become tab separated lines). */
        fun htmlToText(html: String): String {
            var s = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
            s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
            s = s.replace(Regex("(?i)</(p|div|tr|li|h[1-6]|pre|table|thead|tbody|ul|ol)>"), "\n")
            s = s.replace(Regex("(?i)</t[dh]>"), "\t")
            s = s.replace(Regex("<[^>]+>"), "")
            s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            s = s.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n")
            return s.trim()
        }
    }

    /** A color span of the cell highlighter, so only these spans are replaced on the next pass. */
    private class CellSpan(color: Int) : ForegroundColorSpan(color)

    /** A frame that reports a double tap on anything inside it (the selectable text views eat ordinary touches). */
    private class DoubleTapFrame(c: Context, onDouble: () -> Unit) : FrameLayout(c) {
        private val detector = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { onDouble(); return true }
            override fun onDown(e: MotionEvent): Boolean = true
        })
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean { detector.onTouchEvent(ev); return false }
        override fun onTouchEvent(event: MotionEvent): Boolean { detector.onTouchEvent(event); return true }
    }

    val view: LinearLayout = LinearLayout(ctx)
    var path: String = ""
        private set
    private var nb: JSONObject? = null
    private val cells = ArrayList<Cell>()
    private val cellsCol = LinearLayout(ctx)
    private val scroll: ScrollView
    private val addRow: LinearLayout
    private val fileLabel: TextView
    private val unsaved: TextView
    private val statusDot: View
    private val status: TextView
    private val kernelChip: LinearLayout
    private var dirty = false
    private var kernelList: List<JSONObject>? = null

    // ---- the kernel connection
    private var ws: Api.WebSocketClient? = null
    private var attached = false
    private var kernelState = "not started"
    private var kernelInfo: JSONObject? = null
    private val waiting = LinkedHashMap<String, Cell>()
    private val afterAttach = ArrayDeque<() -> Unit>()
    private val runQueue = ArrayDeque<Cell>()
    private var restartRequested = false

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)
        fileLabel = Ui.text(ctx, "Notebook", 12.5f, Ui.fg, bold = true)
        fileLabel.maxLines = 1; fileLabel.ellipsize = TextUtils.TruncateAt.MIDDLE
        unsaved = Ui.text(ctx, "Unsaved", 11f, Ui.accent2, bold = true)
        unsaved.visibility = View.GONE
        unsaved.setPadding(Ui.dp(ctx, 8), 0, 0, 0)
        val runAll = Ui.button(ctx, R.drawable.ic_run, "Run all", "Run every code cell in order") { runAll() }
        val addCode = Ui.button(ctx, R.drawable.ic_plus, "Code", "Add a code cell at the end") { insert(null, "code", true) }
        val addMd = Ui.button(ctx, R.drawable.ic_plus, "Markdown", "Add a Markdown cell at the end") { insert(null, "markdown", true) }
        val interrupt = Ui.button(ctx, R.drawable.ic_stop, "Interrupt", "Stop the running cell") { interrupt() }
        val restart = Ui.button(ctx, R.drawable.ic_refresh, "Restart", "Restart the kernel (variables are cleared)") { restart() }
        val clear = Ui.button(ctx, "Clear outputs") { clearOutputs() }
        val json = Ui.button(ctx, "{ } JSON") { if (path.isNotEmpty()) host.openFileAsText(path) }
        val save = Ui.button(ctx, "Save", primary = true) { save() }
        kernelChip = Ui.chip(ctx, "Kernel", Ui.fg3) { v -> showKernelMenu(v) }
        statusDot = Ui.dot(ctx, Ui.fg3)
        status = Ui.text(ctx, "", 11.5f, Ui.fg2)
        status.maxLines = 1; status.ellipsize = TextUtils.TruncateAt.END
        val bar = Ui.row(ctx, runAll, addCode, addMd, interrupt, restart, clear, json, save, kernelChip, statusDot, status)
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i)
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: Ui.lp(Ui.wrap, Ui.wrap)
            lp.rightMargin = Ui.dp(ctx, 6)
            if (v === statusDot) { lp.width = Ui.dp(ctx, 8); lp.height = Ui.dp(ctx, 8) }
            v.layoutParams = lp
        }
        bar.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        val barScroll = HorizontalScrollView(ctx)
        barScroll.isHorizontalScrollBarEnabled = false
        barScroll.addView(bar, FrameLayout.LayoutParams(Ui.wrap, Ui.wrap))
        val titleRow = Ui.row(ctx, fileLabel, unsaved)
        fileLabel.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        titleRow.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), 0)
        view.addView(titleRow, Ui.lp(Ui.match, Ui.wrap))
        view.addView(barScroll, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.divider(ctx, false))
        cellsCol.orientation = LinearLayout.VERTICAL
        cellsCol.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 40))
        addRow = Ui.row(ctx, Ui.button(ctx, "+ Code") { insert(null, "code", true) }, Ui.button(ctx, "+ Markdown") { insert(null, "markdown", true) })
        (addRow.getChildAt(1).layoutParams as LinearLayout.LayoutParams).leftMargin = Ui.dp(ctx, 6)
        addRow.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), 0, 0)
        scroll = Ui.scroll(ctx, cellsCol)
        view.addView(scroll, Ui.lp(Ui.match, 0, 1f))
        renderStatus()
    }

    // ---------------------------------------------------------------- opening, saving, closing
    /** Opens a notebook file: its JSON comes from the server, and a previous notebook's kernel connection is closed. */
    fun open(p: String) {
        close()
        path = p
        fileLabel.text = p
        cells.clear()
        cellsCol.removeAllViews()
        val loading = Ui.text(ctx, "Loading " + p, 12f, Ui.fg3)
        loading.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), 0, 0)
        cellsCol.addView(loading)
        Api.async("nb-load", { Api.get("/api/file", mapOf("path" to p)) }) { r, e ->
            if (path != p) return@async
            if (r == null) { loading.text = "Cannot open " + p + ": " + (e?.message ?: "unknown error"); return@async }
            load(r.optString("content"))
        }
    }

    private fun load(text: String) {
        cells.clear()
        cellsCol.removeAllViews()
        val parsed = try { if (text.isBlank()) emptyNotebook() else JSONObject(text) } catch (e: Exception) { null }
        val arr = parsed?.optJSONArray("cells")
        if (parsed == null || arr == null) {
            nb = null
            val t = Ui.text(ctx, "This file is not a valid notebook. Open it as JSON to see and fix it.", 12.5f, Ui.fg2)
            t.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
            cellsCol.addView(t, Ui.lp(Ui.match, Ui.wrap))
            val b = Ui.button(ctx, "Open as JSON") { host.openFileAsText(path) }
            cellsCol.addView(b, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 8) })
            return
        }
        nb = parsed
        if (arr.length() == 0) arr.put(newCodeCell())
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val c = Cell(d)
            cells.add(c)
            cellsCol.addView(c.root, cellMargins())
        }
        cellsCol.addView(addRow, Ui.lp(Ui.wrap, Ui.wrap))
        setDirty(text.isBlank())
        renderStatus()
        fillKernels()
    }

    private fun cellMargins(): LinearLayout.LayoutParams { val lp = Ui.lp(Ui.match, Ui.wrap); lp.bottomMargin = Ui.dp(ctx, 8); return lp }

    fun isDirty(): Boolean = dirty

    private fun setDirty(on: Boolean) { dirty = on; unsaved.visibility = if (on) View.VISIBLE else View.GONE }

    private fun markDirty() { if (!dirty) setDirty(true) }

    /** The notebook's JSON as it would be saved: the cells' current data inside the notebook object. */
    private fun serialize(): String? {
        val n = nb ?: return null
        val arr = JSONArray()
        for (c in cells) { c.flushSource(); arr.put(c.data) }
        n.put("cells", arr)
        return n.toString(1) + "\n"
    }

    /** Writes the .ipynb JSON (Ctrl S or the Save button). */
    fun save(done: (() -> Unit)? = null) {
        if (path.isEmpty()) return
        val text = serialize()
        if (text == null) { Ui.toast(ctx, "This file is not a valid notebook, so it cannot be saved as one."); return }
        val p = path
        Api.async("nb-save", { Api.post("/api/file", JSONObject().put("path", p).put("content", text)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Not saved: " + (e?.message ?: "unknown error")); return@async }
            if (path == p) setDirty(false)
            Ui.toast(ctx, "Saved " + p)
            done?.invoke()
        }
    }

    /** The file was renamed (Explorer Rename): saves go to the new name, and the kernel connects again under it. */
    fun renameTo(p: String) {
        close()
        path = p
        fileLabel.text = p
    }

    /** Closes the kernel connection (the server stops a kernel nobody is attached to after ten minutes). */
    fun close() {
        val c = ws
        ws = null
        attached = false
        afterAttach.clear()
        runQueue.clear()
        for (cell in waiting.values) cell.setRunning(false, null)
        waiting.clear()
        c?.close()
        kernelState = "not started"
        kernelInfo = null
        renderStatus()
    }

    // ---------------------------------------------------------------- kernels and the status line
    private fun spec(): JSONObject = nb?.optJSONObject("metadata")?.optJSONObject("kernelspec")
        ?: JSONObject().put("name", "python3").put("language", "python").put("display_name", "Python 3")

    private fun highlightLangId(): String = HIGHLIGHT_LANG[spec().optString("language", "python").lowercase()] ?: "plaintext"

    private fun fillKernels() {
        val cur = spec().optString("name", "python3")
        Ui.setChip(kernelChip, spec().optString("display_name", cur), Ui.fg3)
        if (kernelList != null) return
        Api.async("kernels", { Api.get("/api/notebook/kernels") }) { r, _ ->
            val list = ArrayList<JSONObject>()
            val arr = r?.optJSONArray("kernels")
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
            if (list.isEmpty()) list.add(JSONObject().put("name", "python3").put("display_name", "Python 3 (Forge)").put("language", "python"))
            kernelList = list
            val name = spec().optString("name", "python3")
            val installed = list.any { it.optString("name") == name } || name == "python"
            Ui.setChip(kernelChip, spec().optString("display_name", name) + (if (installed) "" else " (not installed)"), Ui.fg3)
        }
    }

    private fun showKernelMenu(anchor: View) {
        val list = kernelList
        if (list == null) { Ui.toast(ctx, "The kernel list is still loading."); return }
        val menu = PopupMenu(ctx, anchor)
        for ((i, k) in list.withIndex()) menu.menu.add(0, i, i, k.optString("display_name", k.optString("name")))
        menu.setOnMenuItemClickListener { item -> setKernel(list[item.itemId]); true }
        menu.show()
    }

    /** Switches the notebook to another kernel: the file remembers it, cells get its language and the kernel restarts. */
    private fun setKernel(k: JSONObject) {
        val n = nb ?: return
        val name = k.optString("name")
        val meta = n.optJSONObject("metadata") ?: JSONObject().also { n.put("metadata", it) }
        meta.put("kernelspec", JSONObject().put("name", name).put("display_name", k.optString("display_name").replace(" (Forge)", "")).put("language", k.optString("language")))
        meta.put("language_info", JSONObject().put("name", k.optString("language")))
        markDirty()
        for (c in cells) c.rehighlight()
        Ui.setChip(kernelChip, spec().optString("display_name", name), Ui.fg3)
        // The server reads the kernel name from the file when it starts a kernel, so the file is saved first.
        save {
            if (kernelState != "not started" && kernelState != "stopped" && kernelState != "disconnected") restart()
        }
        Ui.toast(ctx, "This notebook now runs " + k.optString("display_name") + ". The kernel starts when you run a cell.")
        renderStatus()
    }

    private fun renderStatus() {
        val info = kernelInfo
        val sp = spec()
        val python = info?.optString("python", "") ?: ""
        val display = info?.optString("display_name", "") ?: ""
        val name = when {
            python.isNotEmpty() -> "Python " + python
            display.isNotEmpty() -> display
            else -> sp.optString("display_name", "Python")
        }
        status.text = name + "  ·  " + (if (kernelState == "not started") "kernel starts when you run a cell" else kernelState)
        Ui.setDotColor(statusDot, when (kernelState) {
            "idle" -> Ui.green
            "busy", "starting" -> Ui.yellow
            "stopped", "disconnected" -> Ui.red
            else -> Ui.fg3
        })
    }

    // ---------------------------------------------------------------- the kernel connection
    private fun ensureOpen(then: () -> Unit) {
        if (ws != null && attached) { then(); return }
        afterAttach.addLast(then)
        if (ws == null) connect()
    }

    private fun connect() {
        if (path.isEmpty()) return
        kernelState = "starting"
        renderStatus()
        attached = false
        val client = Api.WebSocketClient("/ws/kernel?folder=" + enc(host.windowFolder()) + "&path=" + enc(path), { onText(it) }, { why -> onClosed(why) })
        ws = client
        client.connect()
    }

    private fun send(obj: JSONObject) { ws?.sendText(obj.toString()) }

    private fun flushAfterAttach() {
        attached = true
        while (afterAttach.isNotEmpty()) afterAttach.removeFirst().invoke()
    }

    private fun onText(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        val id = msg.optString("id", "")
        val cell = if (id.isNotEmpty()) waiting[id] else null
        when (msg.optString("type")) {
            "ready", "attached" -> {
                val info = msg.optJSONObject("info")
                kernelInfo = if (info != null && info.length() > 0) info else if (msg.has("python") || msg.has("display_name")) msg else kernelInfo
                kernelState = if (msg.optBoolean("busy", false) || waiting.isNotEmpty()) "busy" else "idle"
                flushAfterAttach()
            }
            "restarted" -> {
                kernelState = if (waiting.isNotEmpty() && !restartRequested) "busy" else "idle"
                if (restartRequested) {
                    restartRequested = false
                    for (c in waiting.values) c.setRunning(false, null)
                    waiting.clear()
                    runQueue.clear()
                    Ui.toast(ctx, "Kernel restarted. Variables were cleared.")
                }
            }
            "stream" -> cell?.addOutput(JSONObject().put("output_type", "stream").put("name", msg.optString("name", "stdout")).put("text", msg.optString("text")))
            "result" -> cell?.addOutput(JSONObject().put("output_type", "execute_result").put("execution_count", msg.opt("count") ?: JSONObject.NULL)
                .put("data", msg.optJSONObject("data") ?: JSONObject()).put("metadata", JSONObject()))
            "display" -> cell?.addOutput(JSONObject().put("output_type", "display_data").put("data", msg.optJSONObject("data") ?: JSONObject()).put("metadata", JSONObject()))
            "error" -> cell?.addOutput(JSONObject().put("output_type", "error").put("ename", msg.optString("ename")).put("evalue", msg.optString("evalue"))
                .put("traceback", msg.optJSONArray("traceback") ?: JSONArray()))
            "done" -> {
                if (cell != null) { cell.finish(msg.opt("count")); waiting.remove(id) }
                if (waiting.isEmpty()) kernelState = "idle"
                runNextQueued()
            }
            "exit" -> {
                kernelState = "stopped"
                for (c in waiting.values) c.setRunning(false, null)
                waiting.clear()
                runQueue.clear()
                afterAttach.clear()
                attached = true   // the socket itself is open; the next execute makes the server start a kernel again
                val m = msg.optString("message", "")
                if (m.isNotEmpty()) Ui.toast(ctx, m)
            }
            else -> { }
        }
        renderStatus()
    }

    private fun onClosed(why: String) {
        val hadKernel = ws != null
        ws = null
        attached = false
        afterAttach.clear()
        runQueue.clear()
        for (c in waiting.values) c.setRunning(false, null)
        waiting.clear()
        kernelState = "disconnected"
        renderStatus()
        if (hadKernel) Ui.toast(ctx, "The kernel connection closed: " + why)
    }

    private fun execute(cell: Cell) {
        if (cell.type != "code") return
        cell.clearOutputs()
        val id = uid()
        waiting[id] = cell
        cell.setRunning(true, id)
        kernelState = "busy"
        renderStatus()
        val code = cell.code()
        ensureOpen { send(JSONObject().put("op", "execute").put("id", id).put("code", code)) }
    }

    private fun runAll() {
        runQueue.clear()
        for (c in cells) if (c.type == "code") runQueue.addLast(c)
        runNextQueued()
    }

    private fun runNextQueued() {
        if (waiting.isNotEmpty()) return
        val next = runQueue.removeFirstOrNull() ?: return
        execute(next)
    }

    private fun interrupt() {
        if (ws == null) { Ui.toast(ctx, "No kernel is running."); return }
        send(JSONObject().put("op", "interrupt"))
    }

    private fun restart() {
        restartRequested = true
        ensureOpen { send(JSONObject().put("op", "restart")) }
    }

    private fun clearOutputs() {
        for (c in cells) if (c.type == "code") { c.data.put("outputs", JSONArray()); c.data.put("execution_count", JSONObject.NULL); c.renderCount(); c.renderOutputs() }
        markDirty()
    }

    // ---------------------------------------------------------------- cell list operations
    private fun insert(after: Cell?, type: String, focus: Boolean): Cell {
        val d = if (type == "code") newCodeCell() else newMarkdownCell()
        val c = Cell(d)
        val i = if (after != null) cells.indexOf(after) + 1 else cells.size
        cells.add(i, c)
        cellsCol.addView(c.root, i, cellMargins())
        markDirty()
        if (type == "markdown") c.editMarkdown() else if (focus) c.focusEditor()
        scrollTo(c)
        return c
    }

    private fun confirmRemove(c: Cell) {
        AlertDialog.Builder(ctx).setTitle("Delete this cell?")
            .setMessage(if (c.type == "code") "The cell's code and outputs are removed from the notebook. Save afterwards to write the change." else "The Markdown cell is removed from the notebook. Save afterwards to write the change.")
            .setPositiveButton("Delete") { _, _ -> remove(c) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun remove(c: Cell) {
        val i = cells.indexOf(c)
        if (i < 0) return
        cells.removeAt(i)
        cellsCol.removeView(c.root)
        if (cells.isEmpty()) insert(null, "code", false)
        markDirty()
    }

    private fun move(c: Cell, dir: Int) {
        val i = cells.indexOf(c)
        val j = i + dir
        if (i < 0 || j < 0 || j >= cells.size) return
        cells.removeAt(i)
        cells.add(j, c)
        cellsCol.removeView(c.root)
        cellsCol.addView(c.root, j, cellMargins())
        markDirty()
        scrollTo(c)
    }

    private fun convert(c: Cell) {
        val i = cells.indexOf(c)
        if (i < 0) return
        val src = toLines(c.code())
        val d = if (c.type == "code")
            JSONObject().put("cell_type", "markdown").put("id", c.data.optString("id", uid())).put("metadata", c.data.optJSONObject("metadata") ?: JSONObject()).put("source", src)
        else
            JSONObject().put("cell_type", "code").put("id", c.data.optString("id", uid())).put("metadata", c.data.optJSONObject("metadata") ?: JSONObject())
                .put("execution_count", JSONObject.NULL).put("outputs", JSONArray()).put("source", src)
        val n = Cell(d)
        cells[i] = n
        cellsCol.removeView(c.root)
        cellsCol.addView(n.root, i, cellMargins())
        markDirty()
    }

    private fun focusNext(c: Cell, create: Boolean) {
        val i = cells.indexOf(c)
        val next = cells.getOrNull(i + 1) ?: (if (create) insert(c, "code", false) else null) ?: return
        scrollTo(next)
        if (next.type == "code") next.focusEditor() else next.editMarkdown()
    }

    private fun scrollTo(c: Cell) {
        scroll.post {
            val top = c.root.top
            val bottom = c.root.bottom
            if (top < scroll.scrollY) scroll.smoothScrollTo(0, top)
            else if (bottom > scroll.scrollY + scroll.height) scroll.smoothScrollTo(0, Math.max(0, bottom - scroll.height))
        }
    }

    // ---------------------------------------------------------------- one cell
    private inner class Cell(val data: JSONObject) {
        val root = LinearLayout(ctx)
        val type: String get() = data.optString("cell_type", "code")
        private val gutter = LinearLayout(ctx)
        private val body = LinearLayout(ctx)
        private var editor: EditText? = null
        private var rendered: LinearLayout? = null
        private var renderedFrame: FrameLayout? = null
        private var outputs: LinearLayout? = null
        private var countView: TextView? = null
        private var running = false
        var runId: String? = null
        private val highlightTask = Runnable { highlightNow() }

        init {
            if (data.optString("id", "").isEmpty()) data.put("id", uid())
            if (data.optJSONObject("metadata") == null) data.put("metadata", JSONObject())
            if (type == "code") {
                if (data.optJSONArray("outputs") == null) data.put("outputs", JSONArray())
                if (!data.has("execution_count")) data.put("execution_count", JSONObject.NULL)
            }
            root.orientation = LinearLayout.HORIZONTAL
            root.background = Ui.rounded(Ui.bg2, 6, Ui.border)
            root.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
            gutter.orientation = LinearLayout.VERTICAL
            gutter.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            gutter.minimumWidth = Ui.dp(ctx, 44)
            body.orientation = LinearLayout.VERTICAL
            build()
        }

        private fun build() {
            root.removeAllViews()
            gutter.removeAllViews()
            body.removeAllViews()
            if (type == "code") {
                val run = Ui.miniButton(ctx, R.drawable.ic_run, "Run cell (Shift Enter)") { execute(this) }
                gutter.addView(run)
                val count = Ui.text(ctx, "", 10.5f, Ui.fg3, mono = true)
                count.gravity = Gravity.CENTER_HORIZONTAL
                countView = count
                gutter.addView(count, Ui.lp(Ui.wrap, Ui.wrap).also { it.topMargin = Ui.dp(ctx, 2) })
                renderCount()
                val ed = cellEditor(srcText(data.opt("source")))
                editor = ed
                body.addView(ed, Ui.lp(Ui.match, Ui.wrap))
                val outs = LinearLayout(ctx)
                outs.orientation = LinearLayout.VERTICAL
                outputs = outs
                body.addView(outs, Ui.lp(Ui.match, Ui.wrap))
                renderOutputs()
            } else {
                val marker = Ui.text(ctx, "M", 11f, Ui.fg3, bold = true)
                marker.setPadding(0, Ui.dp(ctx, 6), 0, 0)
                gutter.addView(marker)
                val r = LinearLayout(ctx)
                r.orientation = LinearLayout.VERTICAL
                r.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
                rendered = r
                val frame = DoubleTapFrame(ctx) { editMarkdown() }
                frame.addView(r, FrameLayout.LayoutParams(Ui.match, Ui.wrap))
                renderedFrame = frame
                body.addView(frame, Ui.lp(Ui.match, Ui.wrap))
                renderMarkdown()
            }
            val tools = LinearLayout(ctx)
            tools.orientation = LinearLayout.VERTICAL
            tools.gravity = Gravity.TOP
            tools.addView(toolButton("▲", "Move up") { move(this, -1) })
            tools.addView(toolButton("▼", "Move down") { move(this, 1) })
            tools.addView(toolButton(if (type == "code") "M" else "{}", if (type == "code") "Change to Markdown" else "Change to code") { convert(this) })
            if (type == "markdown") tools.addView(toolButton("Edit", "Edit this cell") { editMarkdown() })
            tools.addView(Ui.miniButton(ctx, R.drawable.ic_trash, "Delete cell") { confirmRemove(this) })
            root.addView(gutter, Ui.lp(Ui.wrap, Ui.wrap))
            root.addView(body, Ui.lp(0, Ui.wrap, 1f))
            root.addView(tools, Ui.lp(Ui.wrap, Ui.wrap))
        }

        private fun toolButton(label: String, tooltip: String, onClick: (View) -> Unit): TextView {
            val t = Ui.text(ctx, label, 11f, Ui.fg3, bold = true)
            t.gravity = Gravity.CENTER
            t.minWidth = Ui.dp(ctx, 24); t.minHeight = Ui.dp(ctx, 24)
            t.background = Ui.states(null, Ui.rounded(Ui.bg4, 5), Ui.rounded(Ui.border2, 5))
            t.isClickable = true; t.isFocusable = true
            t.tooltipText = tooltip; t.contentDescription = tooltip
            t.setOnClickListener(onClick)
            return t
        }

        /** A growing monospace editor for a cell, with syntax colors from the Highlighter and the notebook keys. */
        private fun cellEditor(text: String): EditText {
            val e = EditText(ctx)
            e.setText(text)
            e.typeface = Typeface.MONOSPACE
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            e.setTextColor(Ui.fg)
            e.setHintTextColor(Ui.fg3)
            e.hint = if (type == "code") "Code" else "Markdown"
            e.background = Ui.focusStates(Ui.rounded(Markdown.codeBg, 6, Ui.border), Ui.rounded(Markdown.codeBg, 6, Ui.accent))
            e.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
            e.gravity = Gravity.TOP or Gravity.START
            e.isSingleLine = false
            e.minLines = 1
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            e.imeOptions = e.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            e.setHorizontallyScrolling(false)
            e.isSaveEnabled = false
            e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            e.highlightColor = CodeEditor.selection
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    data.put("source", toLines(s?.toString() ?: ""))
                    markDirty()
                    if (type == "code") { e.removeCallbacks(highlightTask); e.postDelayed(highlightTask, 150) }
                }
            })
            e.setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                        ev.isShiftPressed -> { if (type == "code") execute(this) else finishMarkdown(); focusNext(this, true); true }
                        ev.isCtrlPressed -> { if (type == "code") execute(this) else finishMarkdown(); true }
                        ev.isAltPressed -> { if (type == "code") execute(this) else finishMarkdown(); insert(this, "code", true); true }
                        else -> false
                    }
                    KeyEvent.KEYCODE_S -> if (ev.isCtrlPressed) { save(); true } else false
                    KeyEvent.KEYCODE_ESCAPE -> if (type == "markdown") { finishMarkdown(); true } else false
                    else -> false
                }
            }
            if (type == "code") e.post(highlightTask)
            return e
        }

        private fun highlightNow() {
            val e = editor ?: return
            if (type != "code") return
            val text: Editable = e.text ?: return
            for (old in text.getSpans(0, text.length, CellSpan::class.java)) text.removeSpan(old)
            if (text.length > MAX_HIGHLIGHT_CHARS) return
            val tokens = ArrayList<Highlighter.Token>()
            Highlighter.lang(highlightLangId()).tokenize(text.toString(), 0, tokens)
            for (t in tokens) if (t.end <= text.length && t.start < t.end) text.setSpan(CellSpan(t.color), t.start, t.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun rehighlight() { editor?.let { it.removeCallbacks(highlightTask); it.post(highlightTask) } }

        fun code(): String = editor?.text?.toString() ?: srcText(data.opt("source"))

        /** Puts the editor's text into the cell data (the watcher does it on every change; this is the safety net). */
        fun flushSource() { val e = editor ?: return; data.put("source", toLines(e.text.toString())) }

        fun focusEditor() { editor?.let { it.requestFocus(); it.setSelection(it.text.length) } }

        // ---- Markdown cells
        fun renderMarkdown() {
            val r = rendered ?: return
            val text = srcText(data.opt("source"))
            r.removeAllViews()
            if (text.isBlank()) r.addView(Ui.text(ctx, "Empty Markdown cell. Double tap to edit.", 12.5f, Ui.fg3), Ui.lp(Ui.match, Ui.wrap))
            else Markdown.render(ctx, text, r) { host.copyToClipboard(it) }
        }

        fun editMarkdown() {
            if (type != "markdown") return
            if (editor != null) { editor?.requestFocus(); return }
            renderedFrame?.visibility = View.GONE
            val ed = cellEditor(srcText(data.opt("source")))
            editor = ed
            ed.setOnFocusChangeListener { _, has -> if (!has) finishMarkdown() }
            body.addView(ed, Ui.lp(Ui.match, Ui.wrap))
            ed.requestFocus()
            ed.setSelection(ed.text.length)
        }

        fun finishMarkdown() {
            val ed = editor ?: return
            if (type != "markdown") return
            data.put("source", toLines(ed.text.toString()))
            ed.onFocusChangeListener = null
            body.removeView(ed)
            editor = null
            renderMarkdown()
            renderedFrame?.visibility = View.VISIBLE
        }

        // ---- running and outputs
        fun renderCount() {
            val c = countView ?: return
            val ec = data.opt("execution_count")
            c.text = if (running) "[*]" else if (ec == null || ec == JSONObject.NULL) "[ ]" else "[" + ec + "]"
        }

        fun setRunning(on: Boolean, id: String?) {
            running = on
            runId = if (on) id else null
            root.background = Ui.rounded(Ui.bg2, 6, if (on) Ui.accent else Ui.border)
            renderCount()
        }

        fun finish(count: Any?) {
            data.put("execution_count", if (count == null || count == JSONObject.NULL) JSONObject.NULL else count)
            setRunning(false, null)
            markDirty()
        }

        fun clearOutputs() {
            data.put("outputs", JSONArray())
            renderOutputs()
        }

        fun addOutput(o: JSONObject) {
            val outs = data.optJSONArray("outputs") ?: JSONArray().also { data.put("outputs", it) }
            val last = if (outs.length() > 0) outs.optJSONObject(outs.length() - 1) else null
            if (o.optString("output_type") == "stream" && last != null && last.optString("output_type") == "stream" && last.optString("name") == o.optString("name")) {
                last.put("text", srcText(last.opt("text")) + o.optString("text"))
                // Only the last output view changes: it is replaced instead of rebuilding every output.
                val host = outputs
                if (host != null && host.childCount > 0) { host.removeViewAt(host.childCount - 1); host.addView(outputView(last, this), outputMargins()) }
                else renderOutputs()
            } else {
                outs.put(o)
                outputs?.addView(outputView(o, this), outputMargins())
            }
            markDirty()
        }

        fun renderOutputs() {
            val host = outputs ?: return
            host.removeAllViews()
            val outs = data.optJSONArray("outputs") ?: return
            for (i in 0 until outs.length()) { val o = outs.optJSONObject(i) ?: continue; host.addView(outputView(o, this), outputMargins()) }
        }

        private fun outputMargins(): LinearLayout.LayoutParams { val lp = Ui.lp(Ui.match, Ui.wrap); lp.topMargin = Ui.dp(ctx, 4); return lp }
    }

    // ---------------------------------------------------------------- output views
    private fun outputText(text: String, color: Int): TextView {
        val t = Markdown.textView(ctx, text, 12f, color, mono = true)
        t.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        return t
    }

    private fun outputView(o: JSONObject, cell: Cell): View {
        val type = o.optString("output_type")
        if (type == "stream") {
            val err = o.optString("name") == "stderr"
            return outputText(srcText(o.opt("text")).replace(ANSI, ""), if (err) Ui.accent2 else Ui.fg)
        }
        if (type == "error") {
            val tb = o.optJSONArray("traceback")
            val sb = StringBuilder()
            if (tb != null) for (i in 0 until tb.length()) sb.append(srcText(tb.opt(i)))
            var text = sb.toString().replace(ANSI, "")
            if (text.isBlank()) text = o.optString("ename") + ": " + o.optString("evalue")
            val box = Ui.column(ctx)
            box.background = Ui.rounded(0x1aff6b6b, 6, Ui.red)
            box.addView(outputText(text, Ui.red), Ui.lp(Ui.match, Ui.wrap))
            val errorText = text
            val fix = Ui.button(ctx, "Fix with AI") {
                host.quickAsk("This notebook cell in " + path + " fails. Explain why and fix the cell (edit the notebook file).\n\nCell code:\n" + cell.code() + "\n\nError:\n" + errorText, "agent")
            }
            box.addView(fix, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 8); it.bottomMargin = Ui.dp(ctx, 6) })
            return box
        }
        val data = o.optJSONObject("data") ?: JSONObject()
        val png = data.opt("image/png")
        val jpeg = data.opt("image/jpeg")
        if (png != null || jpeg != null) return imageOutput(srcText(png ?: jpeg))
        if (data.has("text/html")) return outputText(htmlToText(srcText(data.opt("text/html"))), Ui.fg)
        if (data.has("image/svg+xml")) return Markdown.codeBlock(ctx, "svg", srcText(data.opt("image/svg+xml")).trim()) { host.copyToClipboard(it) }
        if (data.has("text/markdown")) {
            val col = Ui.column(ctx)
            col.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0)
            Markdown.render(ctx, srcText(data.opt("text/markdown")), col) { host.copyToClipboard(it) }
            return col
        }
        val plain = outputText(srcText(data.opt("text/plain")).replace(ANSI, ""), Ui.fg)
        if (type == "execute_result") plain.background = Ui.rounded(Ui.bg, 6, Ui.border)
        return plain
    }

    /** A base64 PNG or JPEG decoded off the main thread and shown at its own size, never wider than the cell. */
    private fun imageOutput(b64: String): View {
        val iv = ImageView(ctx)
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_START
        iv.contentDescription = "figure"
        val holder = Ui.column(ctx, iv)
        holder.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        Api.async("nb-image", {
            val bytes = Base64.decode(b64.replace(Regex("\\s"), ""), Base64.DEFAULT)
            val bounds = BitmapFactory.Options(); bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_IMAGE_PX || bounds.outHeight / sample > MAX_IMAGE_PX) sample *= 2
            val opts = BitmapFactory.Options(); opts.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }) { bmp: Bitmap?, _ ->
            if (bmp == null) { holder.removeAllViews(); holder.addView(Ui.text(ctx, "The image output could not be decoded.", 12f, Ui.fg3)); return@async }
            iv.maxWidth = bmp.width
            iv.setImageBitmap(bmp)
        }
        return holder
    }
}
