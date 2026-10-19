package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject

/** The Explorer, the open-file tabs and the editor, all native. */
class EditorPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val treeList = ListView(ctx)
    private val treeTitle = Ui.text(ctx, "EXPLORER", 11f, Ui.fg3, bold = true)
    private val tabs = LinearLayout(ctx)
    private val editor = EditText(ctx)
    private val gutter = TextView(ctx)
    private val empty = Ui.text(ctx, "Open a file from the Explorer, or press Ctrl P to find one.", 13f, Ui.fg3)

    private class Entry(val path: String, val name: String, val dir: Boolean, val depth: Int) { var expanded = false; var children: List<Entry>? = null }
    private val roots = ArrayList<Entry>()
    private val visible = ArrayList<Entry>()
    private val adapter = ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1)

    private class Open(val path: String) { var text: String = ""; var dirty = false; var mtime: Double = 0.0 }
    private val open = LinkedHashMap<String, Open>()
    private var active: Open? = null
    private var loading = false

    init {
        view.orientation = LinearLayout.HORIZONTAL
        val pad = Ui.dp(ctx, 8)
        // Explorer
        val tree = Ui.column(ctx)
        tree.setBackgroundColor(Ui.bg2)
        val head = Ui.row(ctx, treeTitle, Ui.button(ctx, "New") { newFile() }, Ui.button(ctx, "Refresh") { refresh() })
        treeTitle.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        head.setPadding(pad, pad / 2, pad / 2, pad / 2)
        tree.addView(head, Ui.lp(Ui.match, Ui.wrap))
        treeList.adapter = adapter
        treeList.divider = null
        treeList.setOnItemClickListener { _, _, pos, _ -> val e = visible.getOrNull(pos) ?: return@setOnItemClickListener; if (e.dir) toggle(e) else openFile(e.path) }
        tree.addView(treeList, Ui.lp(Ui.match, 0, 1f))
        view.addView(tree, Ui.lp(Ui.dp(ctx, 230), Ui.match))
        view.addView(Ui.divider(ctx, true))
        // Editor column
        val col = Ui.column(ctx)
        col.setBackgroundColor(Ui.bg)
        tabs.orientation = LinearLayout.HORIZONTAL
        val tabScroll = HorizontalScrollView(ctx); tabScroll.addView(tabs); tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(Ui.bg2)
        col.addView(tabScroll, Ui.lp(Ui.match, Ui.wrap))
        col.addView(Ui.divider(ctx, false))
        gutter.typeface = Typeface.MONOSPACE; gutter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); gutter.setTextColor(Ui.fg3)
        gutter.gravity = Gravity.END; gutter.setPadding(pad, pad, pad / 2, pad)
        editor.typeface = Typeface.MONOSPACE; editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); editor.setTextColor(Ui.fg)
        editor.background = null; editor.gravity = Gravity.TOP; editor.setPadding(pad / 2, pad, pad, pad)
        editor.setHorizontallyScrolling(true); editor.isVerticalScrollBarEnabled = true
        editor.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.imeOptions = editor.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val a = active ?: return
                if (loading) return
                a.text = s.toString(); if (!a.dirty) { a.dirty = true; renderTabs() }
                updateGutter()
            }
        })
        val body = Ui.row(ctx, gutter, editor)
        body.gravity = Gravity.TOP
        editor.layoutParams = Ui.lp(0, Ui.match, 1f)
        val editorFrame = android.widget.FrameLayout(ctx)
        editorFrame.addView(body, android.widget.FrameLayout.LayoutParams(Ui.match, Ui.match))
        empty.gravity = Gravity.CENTER
        editorFrame.addView(empty, android.widget.FrameLayout.LayoutParams(Ui.match, Ui.match))
        col.addView(editorFrame, Ui.lp(Ui.match, 0, 1f))
        view.addView(col, Ui.lp(0, Ui.match, 1f))
        showEditor(false)
    }

    private fun showEditor(on: Boolean) { empty.visibility = if (on) View.GONE else View.VISIBLE; editor.visibility = if (on) View.VISIBLE else View.INVISIBLE; gutter.visibility = editor.visibility }

    private fun updateGutter() {
        val n = editor.text?.count { it == '\n' }?.plus(1) ?: 1
        val sb = StringBuilder(); for (i in 1..n) sb.append(i).append('\n')
        gutter.text = sb
    }

    // ---------------------------------------------------------------- the tree
    fun refresh() {
        Api.async("tree", { Api.get("/api/tree", mapOf("path" to ".")) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Explorer: " + (e?.message ?: "no answer")); return@async }
            roots.clear(); roots.addAll(parse(r, 0)); rebuild()
            treeTitle.text = "EXPLORER" + if (roots.isEmpty()) "  (empty folder)" else ""
        }
    }

    private fun parse(r: JSONObject, depth: Int): List<Entry> {
        val items = r.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until items.length()) { val it = items.getJSONObject(i); out.add(Entry(it.optString("path"), it.optString("name"), it.optBoolean("dir"), depth)) }
        return out
    }

    private fun toggle(e: Entry) {
        if (e.expanded) { e.expanded = false; rebuild(); return }
        if (e.children != null) { e.expanded = true; rebuild(); return }
        Api.async("tree", { Api.get("/api/tree", mapOf("path" to e.path)) }) { r, _ ->
            e.children = if (r != null) parse(r, e.depth + 1) else emptyList(); e.expanded = true; rebuild()
        }
    }

    private fun rebuild() {
        visible.clear()
        fun walk(list: List<Entry>) { for (e in list) { visible.add(e); if (e.dir && e.expanded) walk(e.children ?: emptyList()) } }
        walk(roots)
        adapter.clear()
        for (e in visible) adapter.add("  ".repeat(e.depth) + (if (e.dir) (if (e.expanded) "▾ " else "▸ ") else "   ") + e.name)
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- files
    fun openFile(path: String) {
        val existing = open[path]
        if (existing != null) { activate(existing); return }
        Api.async("file", { Api.get("/api/file", mapOf("path" to path)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Cannot open " + path + ": " + (e?.message ?: "")); return@async }
            val o = Open(path); o.text = r.optString("content"); o.mtime = r.optDouble("mtime", 0.0)
            open[path] = o; activate(o)
        }
    }

    private fun activate(o: Open) {
        active = o
        loading = true
        editor.setText(o.text); editor.setSelection(0)
        loading = false
        updateGutter(); showEditor(true); renderTabs()
        host.setWindowTitle(o.path.substringAfterLast('/'))
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        for (o in open.values) {
            val b = Ui.button(ctx, o.path.substringAfterLast('/') + (if (o.dirty) " •" else "")) { activate(o) }
            if (o === active) b.background = Ui.rounded(Ui.bg, 6, Ui.accent)
            b.setOnLongClickListener { close(o); true }
            tabs.addView(b, Ui.lp(Ui.wrap, Ui.wrap).also { it.setMargins(4, 4, 0, 4) })
        }
        if (open.isNotEmpty()) tabs.addView(Ui.text(ctx, "   long press closes a tab", 10.5f, Ui.fg3).also { it.gravity = Gravity.CENTER_VERTICAL })
    }

    private fun close(o: Open) {
        if (o.dirty) { AlertDialog.Builder(ctx).setTitle("Unsaved changes in " + o.path).setPositiveButton("Save") { _, _ -> save(o) { closeNow(o) } }.setNegativeButton("Discard") { _, _ -> closeNow(o) }.setNeutralButton("Keep open", null).show(); return }
        closeNow(o)
    }

    private fun closeNow(o: Open) {
        open.remove(o.path)
        if (active === o) { active = null; val next = open.values.lastOrNull(); if (next != null) activate(next) else { loading = true; editor.setText(""); loading = false; showEditor(false); renderTabs(); host.setWindowTitle("") } }
        else renderTabs()
    }

    fun saveActive() { val o = active ?: return; save(o) { } }

    private fun save(o: Open, done: () -> Unit) {
        val text = if (o === active) editor.text.toString() else o.text
        Api.async("save", { Api.post("/api/file", JSONObject().put("path", o.path).put("content", text)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Not saved: " + (e?.message ?: "")); return@async }
            o.text = text; o.dirty = false; renderTabs(); Ui.toast(ctx, "Saved " + o.path); done()
        }
    }

    /** A file the agent changed: reload it if it is open and not edited here. */
    fun fileChanged(path: String) {
        val o = open[path] ?: run { refresh(); return }
        if (o.dirty) return
        Api.async("file", { Api.get("/api/file", mapOf("path" to path)) }) { r, _ ->
            if (r == null) return@async
            o.text = r.optString("content")
            if (o === active) { loading = true; editor.setText(o.text); loading = false; updateGutter() }
        }
        refresh()
    }

    private fun newFile() {
        val input = Ui.input(ctx, "name, for example src/main.py")
        AlertDialog.Builder(ctx).setTitle("New file").setView(input).setPositiveButton("Create") { _, _ ->
            val p = input.text.toString().trim(); if (p.isEmpty()) return@setPositiveButton
            Api.async("newfile", { Api.post("/api/file", JSONObject().put("path", p).put("content", "")) }) { r, e ->
                if (r == null) { Ui.toast(ctx, "Not created: " + (e?.message ?: "")); return@async }
                refresh(); openFile(p)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    /** Ctrl P: find a file of this folder by name. */
    fun quickOpen() {
        val input = Ui.input(ctx, "Type part of a file name")
        val list = ListView(ctx)
        val items = ArrayList<String>()
        val ad = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, items)
        list.adapter = ad
        val box = Ui.column(ctx, input, list); list.layoutParams = Ui.lp(Ui.match, Ui.dp(ctx, 320))
        val dialog = AlertDialog.Builder(ctx).setTitle("Open a file").setView(box).setNegativeButton("Close", null).create()
        fun search(q: String) {
            Api.async("files", { Api.get("/api/files", mapOf("q" to q)) }) { r, _ ->
                items.clear()
                val arr = r?.optJSONArray("files")
                if (arr != null) for (i in 0 until minOf(arr.length(), 200)) items.add(arr.getString(i))
                ad.notifyDataSetChanged()
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { search(s.toString()) }
        })
        list.setOnItemClickListener { _, _, pos, _ -> openFile(items[pos]); dialog.dismiss() }
        dialog.show(); search(""); input.requestFocus()
    }

    fun activePath(): String = active?.path ?: ""
    fun focusEditor() { editor.requestFocus() }
}
