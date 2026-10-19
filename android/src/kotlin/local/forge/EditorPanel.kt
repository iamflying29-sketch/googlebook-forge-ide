package local.forge

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import org.json.JSONObject

/**
 * The Explorer (a file tree with icons, a context menu and file management), the open-file tabs with a breadcrumb
 * line, one CodeEditor per open file, the welcome screen, the quick-open palette (Ctrl P) and find in files.
 * Everything is native; the server API is the same one the web page used.
 */
class EditorPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)

    private val accentSoft = 0x24ff8a3d
    private val accent2 = Color.parseColor("#ffb07a")
    private val bg4 = Color.parseColor("#222738")
    private val border2 = Color.parseColor("#2f3548")

    // ---- the Explorer
    private class Entry(val path: String, val name: String, val dir: Boolean, val size: Long, val depth: Int, val placeholder: Boolean = false) {
        var expanded = false
        var children: List<Entry>? = null
    }
    private val roots = ArrayList<Entry>()
    private val visible = ArrayList<Entry>()
    private val expandedPaths = HashSet<String>()
    private var selectedPath = ""
    private val treeList = ListView(ctx)
    private val treeAdapter = TreeAdapter()
    private val folderLabel = Ui.text(ctx, "", 11f, Ui.fg2)
    private val emptyLabel = Ui.text(ctx, "(empty folder)", 12f, Ui.fg3)

    // ---- the tabs and the editors
    private class Open(var path: String, val editor: CodeEditor) { var dirty = false; var mtime = 0.0; var tab: View? = null }
    private val open = LinkedHashMap<String, Open>()
    private var active: Open? = null
    private val tabs = LinearLayout(ctx)
    private val tabScroll = HorizontalScrollView(ctx)
    private val crumbs = Ui.text(ctx, "", 11.5f, Ui.fg3)
    private val editorFrame = FrameLayout(ctx)
    private val welcome: View

    init {
        view.orientation = LinearLayout.HORIZONTAL
        val pad = Ui.dp(ctx, 8)

        // ---------------------------------------------------------------- Explorer column
        val tree = Ui.column(ctx)
        tree.setBackgroundColor(Ui.bg2)
        val title = Ui.text(ctx, "EXPLORER", 11f, Ui.fg3, bold = true)
        title.letterSpacing = 0.06f
        folderLabel.ellipsize = TextUtils.TruncateAt.END; folderLabel.isSingleLine = true
        folderLabel.setPadding(pad, 0, 0, 0)
        val titles = Ui.row(ctx, title, folderLabel)
        folderLabel.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        val head = Ui.row(ctx, titles,
            iconButton("new-file", "New file") { newFileDialog(folderOfSelection()) },
            iconButton("new-folder", "New folder") { newFolderDialog(folderOfSelection()) },
            iconButton("refresh", "Refresh") { refresh() },
            iconButton("collapse", "Collapse all") { collapseAll() })
        titles.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        head.setPadding(pad, pad / 2, pad / 2, pad / 2)
        tree.addView(head, Ui.lp(Ui.match, Ui.wrap))
        tree.addView(Ui.divider(ctx, false))
        treeList.adapter = treeAdapter
        treeList.divider = null
        treeList.selector = ColorDrawable(Color.TRANSPARENT)
        treeList.isVerticalScrollBarEnabled = true
        treeList.setOnItemClickListener { _, _, pos, _ ->
            val e = visible.getOrNull(pos) ?: return@setOnItemClickListener
            if (e.placeholder) return@setOnItemClickListener
            if (e.dir) toggle(e) else openFile(e.path)
        }
        treeList.setOnItemLongClickListener { _, v, pos, _ ->
            val e = visible.getOrNull(pos) ?: return@setOnItemLongClickListener false
            if (!e.placeholder) showMenu(e, v)
            true
        }
        // A mouse right-click on a row opens the same menu as a long press.
        treeList.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                val pos = treeList.pointToPosition(ev.x.toInt(), ev.y.toInt())
                val e = visible.getOrNull(pos)
                if (e != null && !e.placeholder) showMenu(e, treeList.getChildAt(pos - treeList.firstVisiblePosition) ?: v)
                true
            } else false
        }
        tree.addView(treeList, Ui.lp(Ui.match, 0, 1f))
        emptyLabel.setPadding(pad * 2, pad, pad, pad)
        emptyLabel.visibility = View.GONE
        tree.addView(emptyLabel, Ui.lp(Ui.match, Ui.wrap))
        view.addView(tree, Ui.lp(Ui.dp(ctx, 240), Ui.match))
        view.addView(Ui.divider(ctx, true))

        // ---------------------------------------------------------------- editor column
        val col = Ui.column(ctx)
        col.setBackgroundColor(Ui.bg)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabScroll.addView(tabs, FrameLayout.LayoutParams(Ui.wrap, Ui.match))
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(Ui.bg2)
        col.addView(tabScroll, Ui.lp(Ui.match, Ui.dp(ctx, 34)))
        col.addView(Ui.divider(ctx, false))
        crumbs.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0)
        crumbs.gravity = Gravity.CENTER_VERTICAL
        crumbs.isSingleLine = true; crumbs.ellipsize = TextUtils.TruncateAt.START
        crumbs.visibility = View.GONE
        col.addView(crumbs, Ui.lp(Ui.match, Ui.dp(ctx, 22)))
        welcome = buildWelcome()
        editorFrame.addView(welcome, FrameLayout.LayoutParams(Ui.match, Ui.match))
        col.addView(editorFrame, Ui.lp(Ui.match, 0, 1f))
        view.addView(col, Ui.lp(0, Ui.match, 1f))
        renderTabs()
    }

    private fun iconButton(kind: String, tip: String, onClick: (View) -> Unit): View {
        val b = ImageButton(ctx)
        b.setImageDrawable(FileIcons.action(ctx, kind))
        b.background = null
        b.scaleType = ImageView.ScaleType.CENTER
        b.contentDescription = tip
        b.tooltipText = tip
        val s = Ui.dp(ctx, 26)
        b.layoutParams = Ui.lp(s, s)
        b.setOnClickListener(onClick)
        return b
    }

    // ---------------------------------------------------------------- the welcome screen
    private fun buildWelcome(): View {
        val outer = LinearLayout(ctx)
        outer.orientation = LinearLayout.VERTICAL
        outer.gravity = Gravity.CENTER
        outer.setBackgroundColor(Ui.bg)
        val inner = LinearLayout(ctx)
        inner.orientation = LinearLayout.VERTICAL
        inner.gravity = Gravity.CENTER_HORIZONTAL
        val pad = Ui.dp(ctx, 20)
        inner.setPadding(pad, pad, pad, pad)

        val logo = TextView(ctx)
        logo.text = "F"
        logo.setTextColor(Color.WHITE)
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        logo.gravity = Gravity.CENTER
        val grad = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.accent, Color.parseColor("#ff5e62")))
        grad.cornerRadius = Ui.dp(ctx, 14).toFloat()
        logo.background = grad
        inner.addView(logo, Ui.lp(Ui.dp(ctx, 56), Ui.dp(ctx, 56)))

        val title = Ui.text(ctx, "Forge IDE", 26f, Ui.fg, bold = true)
        title.setPadding(0, Ui.dp(ctx, 14), 0, Ui.dp(ctx, 6))
        inner.addView(title)
        val sub = Ui.text(ctx, "Your local AI coding IDE. Everything runs on this Googlebook and nothing leaves the machine.", 13f, Ui.fg3)
        sub.gravity = Gravity.CENTER
        inner.addView(sub, Ui.lp(Ui.dp(ctx, 480), Ui.wrap))

        val shortcuts = listOf("Open a file" to "Ctrl P", "Chat with the agent" to "Ctrl L", "Terminal" to "Ctrl `", "Save" to "Ctrl S",
            "Find in file" to "Ctrl F", "Find in files" to "Ctrl Shift F", "Go to line" to "Ctrl G", "Settings" to "toolbar")
        val grid = LinearLayout(ctx)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(0, Ui.dp(ctx, 22), 0, Ui.dp(ctx, 22))
        var i = 0
        while (i < shortcuts.size) {
            val row = LinearLayout(ctx); row.orientation = LinearLayout.HORIZONTAL
            for (j in 0..1) {
                val cell = LinearLayout(ctx); cell.orientation = LinearLayout.HORIZONTAL; cell.gravity = Gravity.CENTER_VERTICAL
                val s = shortcuts.getOrNull(i + j)
                if (s != null) {
                    val label = Ui.text(ctx, s.first, 13f, Ui.fg2)
                    cell.addView(label, Ui.lp(0, Ui.wrap, 1f))
                    cell.addView(kbd(s.second))
                }
                cell.setPadding(0, Ui.dp(ctx, 4), if (j == 0) Ui.dp(ctx, 24) else 0, Ui.dp(ctx, 4))
                row.addView(cell, Ui.lp(0, Ui.wrap, 1f))
            }
            grid.addView(row, Ui.lp(Ui.match, Ui.wrap))
            i += 2
        }
        inner.addView(grid, Ui.lp(Ui.dp(ctx, 480), Ui.wrap))

        val actions = Ui.row(ctx,
            Ui.button(ctx, "Open a file", primary = true) { quickOpen() },
            spacer(8), Ui.button(ctx, "New file") { newFileDialog("") },
            spacer(8), Ui.button(ctx, "Settings") { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) })
        actions.gravity = Gravity.CENTER
        inner.addView(actions)
        outer.addView(inner, Ui.lp(Ui.wrap, Ui.wrap))
        return outer
    }

    private fun spacer(dp: Int): View = View(ctx).also { it.layoutParams = Ui.lp(Ui.dp(ctx, dp), 1) }

    private fun kbd(s: String): TextView {
        val t = Ui.text(ctx, s, 11f, Ui.fg2, mono = true)
        t.background = Ui.rounded(bg4, 2, border2)
        t.setPadding(Ui.dp(ctx, 5), Ui.dp(ctx, 1), Ui.dp(ctx, 5), Ui.dp(ctx, 1))
        return t
    }

    // ---------------------------------------------------------------- the tree
    private fun folderName(): String = if (Api.folder.isEmpty()) "Forge" else Api.folder.substringAfterLast('/')

    fun refresh() {
        folderLabel.text = folderName()
        Api.async("tree", { Api.get("/api/tree", mapOf("path" to ".")) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Explorer: " + (e?.message ?: "no answer")); return@async }
            roots.clear(); roots.addAll(parse(r, 0))
            restoreExpanded(roots)
            rebuild()
        }
    }

    private fun parse(r: JSONObject, depth: Int): List<Entry> {
        val items = r.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            out.add(Entry(it.optString("path"), it.optString("name"), it.optBoolean("dir"), it.optLong("size", 0L), depth))
        }
        out.sortWith(compareBy({ !it.dir }, { it.name.lowercase() }))
        return out
    }

    /** Loads a folder's children; afterwards every child folder that was expanded before is loaded again too. */
    private fun load(e: Entry) {
        Api.async("tree", { Api.get("/api/tree", mapOf("path" to e.path)) }) { r, _ ->
            e.children = if (r != null) parse(r, e.depth + 1) else emptyList()
            restoreExpanded(e.children!!)
            rebuild()
        }
    }

    private fun restoreExpanded(list: List<Entry>) {
        for (e in list) if (e.dir && e.path in expandedPaths) { e.expanded = true; load(e) }
    }

    private fun toggle(e: Entry) {
        if (e.expanded) { e.expanded = false; expandedPaths.remove(e.path); rebuild(); return }
        e.expanded = true; expandedPaths.add(e.path)
        if (e.children == null) load(e) else rebuild()
    }

    private fun collapseAll() {
        expandedPaths.clear()
        fun walk(list: List<Entry>) { for (e in list) { e.expanded = false; e.children?.let { walk(it) } } }
        walk(roots)
        rebuild()
    }

    private fun rebuild() {
        visible.clear()
        fun walk(list: List<Entry>) {
            for (e in list) {
                visible.add(e)
                if (e.dir && e.expanded) {
                    val ch = e.children
                    if (ch != null && ch.isEmpty()) visible.add(Entry("", "(empty folder)", false, 0L, e.depth + 1, placeholder = true))
                    else if (ch != null) walk(ch)
                }
            }
        }
        walk(roots)
        emptyLabel.visibility = if (roots.isEmpty()) View.VISIBLE else View.GONE
        treeAdapter.notifyDataSetChanged()
    }

    /** Every folder on the way to a path gets expanded, so a new or renamed file is visible after the refresh. */
    private fun expandAncestors(path: String) {
        var p = parentOf(path)
        while (p.isNotEmpty()) { expandedPaths.add(p); p = parentOf(p) }
    }

    private fun parentOf(path: String): String = if (path.contains('/')) path.substringBeforeLast('/') else ""
    private fun join(dir: String, name: String): String = if (dir.isEmpty() || dir == ".") name else dir.trimEnd('/') + "/" + name

    /** The folder new files go into when the header buttons are used: the selected entry's folder. */
    private fun folderOfSelection(): String {
        val e = visible.firstOrNull { it.path == selectedPath && it.path.isNotEmpty() } ?: return ""
        return if (e.dir) e.path else parentOf(e.path)
    }

    private fun humanSize(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
        else -> String.format("%.1f MB", n / (1024.0 * 1024.0))
    }

    private inner class TreeAdapter : BaseAdapter() {
        private inner class Row(val root: LinearLayout, val chevron: TextView, val icon: ImageView, val name: TextView, val meta: TextView)

        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView?.tag as? Row) ?: newRow()
            val e = visible[position]
            val dp12 = Ui.dp(ctx, 12)
            row.root.setPadding(Ui.dp(ctx, 6) + e.depth * dp12, 0, Ui.dp(ctx, 8), 0)
            row.chevron.text = if (e.dir) (if (e.expanded) "▾" else "▸") else ""
            if (e.placeholder) {
                row.icon.setImageDrawable(null)
                row.name.text = e.name; row.name.setTextColor(Ui.fg3); row.name.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
                row.meta.text = ""
                row.root.background = null
                return row.root
            }
            row.name.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            row.icon.setImageDrawable(if (e.dir) FileIcons.folder(ctx, e.expanded) else FileIcons.file(ctx, e.name))
            row.name.text = e.name
            row.name.setTextColor(if (e.dir || e.path == selectedPath) Ui.fg else Ui.fg2)
            row.meta.text = if (e.dir) "" else humanSize(e.size)
            row.root.background = if (e.path == selectedPath) ColorDrawable(accentSoft) else null
            return row.root
        }

        private fun newRow(): Row {
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.minimumHeight = Ui.dp(ctx, 24)
            val chevron = Ui.text(ctx, "", 10f, Ui.fg3)
            chevron.gravity = Gravity.CENTER
            root.addView(chevron, Ui.lp(Ui.dp(ctx, 14), Ui.wrap))
            val icon = ImageView(ctx)
            root.addView(icon, Ui.lp(Ui.dp(ctx, 16), Ui.dp(ctx, 16)).also { it.setMargins(Ui.dp(ctx, 2), 0, Ui.dp(ctx, 6), 0) })
            val name = Ui.text(ctx, "", 12.5f, Ui.fg2)
            name.isSingleLine = true; name.ellipsize = TextUtils.TruncateAt.END
            root.addView(name, Ui.lp(0, Ui.wrap, 1f))
            val meta = Ui.text(ctx, "", 10f, Ui.fg3)
            meta.setPadding(Ui.dp(ctx, 6), 0, 0, 0)
            root.addView(meta, Ui.lp(Ui.wrap, Ui.wrap))
            val row = Row(root, chevron, icon, name, meta)
            root.tag = row
            return row
        }
    }

    // ---------------------------------------------------------------- the context menu and file management
    private fun showMenu(e: Entry, anchor: View) {
        val dir = if (e.dir) e.path else parentOf(e.path)
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, "Rename")
        menu.menu.add(0, 2, 1, "New file here")
        menu.menu.add(0, 3, 2, "New folder here")
        menu.menu.add(0, 4, 3, "Copy path")
        menu.menu.add(0, 5, 4, "Move to trash")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> renameDialog(e)
                2 -> newFileDialog(dir)
                3 -> newFolderDialog(dir)
                4 -> { host.copyToClipboard(e.path); Ui.toast(ctx, "Copied " + e.path) }
                5 -> trashDialog(e)
            }
            true
        }
        menu.show()
    }

    private fun newFileDialog(dir: String) {
        val input = Ui.input(ctx, "name, for example src/main.py", mono = true)
        if (dir.isNotEmpty() && dir != ".") { input.setText(dir.trimEnd('/') + "/"); input.setSelection(input.text.length) }
        val dialog = AlertDialog.Builder(ctx).setTitle("New file").setView(input).setPositiveButton("Create") { _, _ ->
            val p = input.text.toString().trim().trimStart('/')
            if (p.isEmpty() || p.endsWith("/")) return@setPositiveButton
            Api.async("newfile", { Api.post("/api/file", JSONObject().put("path", p).put("content", "")) }) { r, e ->
                if (r == null) { Ui.toast(ctx, "Not created: " + (e?.message ?: "")); return@async }
                expandAncestors(p); refresh(); openFile(p)
            }
        }.setNegativeButton("Cancel", null).create()
        enterSubmits(input, dialog)
        dialog.show(); input.requestFocus()
    }

    private fun newFolderDialog(dir: String) {
        val input = Ui.input(ctx, "folder name", mono = true)
        if (dir.isNotEmpty() && dir != ".") { input.setText(dir.trimEnd('/') + "/"); input.setSelection(input.text.length) }
        val dialog = AlertDialog.Builder(ctx).setTitle("New folder").setView(input).setPositiveButton("Create") { _, _ ->
            val p = input.text.toString().trim().trim('/')
            if (p.isEmpty()) return@setPositiveButton
            Api.async("newfolder", { Api.post("/api/file/new", JSONObject().put("path", p).put("dir", true)) }) { r, e ->
                if (r == null) { Ui.toast(ctx, "Not created: " + (e?.message ?: "")); return@async }
                expandAncestors(p); expandedPaths.add(p); refresh()
            }
        }.setNegativeButton("Cancel", null).create()
        enterSubmits(input, dialog)
        dialog.show(); input.requestFocus()
    }

    private fun renameDialog(e: Entry) {
        val input = Ui.input(ctx, "new name", mono = true)
        input.setText(e.name)
        val dot = if (e.dir) -1 else e.name.lastIndexOf('.')
        input.setSelection(0, if (dot > 0) dot else e.name.length)
        val dialog = AlertDialog.Builder(ctx).setTitle("Rename " + e.name).setView(input).setPositiveButton("Rename") { _, _ ->
            val typed = input.text.toString().trim().trim('/')
            if (typed.isEmpty() || typed == e.name) return@setPositiveButton
            val newPath = if (typed.contains('/')) typed else join(parentOf(e.path), typed)
            Api.async("rename", { Api.post("/api/file/rename", JSONObject().put("path", e.path).put("new_path", newPath)) }) { r, err ->
                if (r == null) { Ui.toast(ctx, "Not renamed: " + (err?.message ?: "")); return@async }
                val finalPath = r.optString("path", newPath)
                movePaths(e.path, finalPath, e.dir)
                if (e.dir && e.path in expandedPaths) { expandedPaths.remove(e.path); expandedPaths.add(finalPath) }
                expandAncestors(finalPath); refresh()
            }
        }.setNegativeButton("Cancel", null).create()
        enterSubmits(input, dialog)
        dialog.show(); input.requestFocus()
    }

    /** After a rename: open tabs keep following the file (or every file under a renamed folder). */
    private fun movePaths(oldPath: String, newPath: String, dir: Boolean) {
        val moved = open.values.filter { it.path == oldPath || (dir && it.path.startsWith("$oldPath/")) }
        if (moved.isEmpty()) return
        for (o in moved) { open.remove(o.path); o.path = newPath + o.path.substring(oldPath.length) }
        for (o in moved) open[o.path] = o
        if (selectedPath == oldPath) selectedPath = newPath
        renderTabs()
        active?.let { showCrumbs(it); host.setWindowTitle(it.path.substringAfterLast('/')) }
    }

    private fun trashDialog(e: Entry) {
        val what = if (e.dir) "the folder " + e.name + " and everything in it" else e.name
        AlertDialog.Builder(ctx).setTitle("Move to trash?").setMessage("Forge moves $what to its trash folder.")
            .setPositiveButton("Move to trash") { _, _ ->
                Api.async("trash", { Api.post("/api/file/trash", JSONObject().put("path", e.path)) }) { r, err ->
                    if (r == null) { Ui.toast(ctx, "Not moved: " + (err?.message ?: "")); return@async }
                    // Tabs of trashed files close, except edited ones: saving them again recreates the file.
                    for (o in open.values.toList()) if (!o.dirty && (o.path == e.path || o.path.startsWith(e.path + "/"))) closeNow(o)
                    if (selectedPath == e.path) selectedPath = ""
                    Ui.toast(ctx, r.optString("message", "Moved to the trash"))
                    refresh()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun enterSubmits(input: TextView, dialog: AlertDialog) {
        input.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick(); true
            } else false
        }
    }

    // ---------------------------------------------------------------- files, tabs and editors
    /** Opens a file in a tab (or brings its tab to the front); a line number above 0 moves the cursor there. */
    fun openFile(path: String, line: Int = 0) {
        val existing = open[path]
        if (existing != null) { activate(existing); if (line > 0) existing.editor.goToLine(line); return }
        Api.async("file", { Api.get("/api/file", mapOf("path" to path)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Cannot open " + path + ": " + (e?.message ?: "")); return@async }
            val editor = CodeEditor(ctx)
            val o = Open(path, editor)
            o.mtime = r.optDouble("mtime", 0.0)
            editor.setContent(r.optString("content"), path)
            editor.onChanged = { if (!o.dirty) { o.dirty = true; renderTabs() } }
            open[path] = o
            activate(o)
            if (line > 0) editor.goToLine(line)
        }
    }

    private fun activate(o: Open) {
        val prev = active
        active = o
        if (prev !== o) {
            if (prev != null) editorFrame.removeView(prev.editor)
            editorFrame.addView(o.editor, FrameLayout.LayoutParams(Ui.match, Ui.match))
        }
        welcome.visibility = View.GONE
        showCrumbs(o)
        renderTabs()
        selectedPath = o.path
        treeAdapter.notifyDataSetChanged()
        host.setWindowTitle(o.path.substringAfterLast('/'))
        o.editor.focus()
    }

    private fun showCrumbs(o: Open) {
        crumbs.text = o.path.split('/').filter { it.isNotEmpty() }.joinToString("  ›  ")
        crumbs.visibility = View.VISIBLE
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        for (o in open.values) {
            val t = tabView(o)
            o.tab = t
            tabs.addView(t, Ui.lp(Ui.wrap, Ui.match))
            tabs.addView(Ui.divider(ctx, true))
        }
        val a = active?.tab
        if (a != null) tabScroll.post { tabScroll.requestChildRectangleOnScreen(tabs, Rect(a.left, 0, a.right, a.height), false) }
    }

    private fun tabView(o: Open): View {
        val isActive = o === active
        val name = Ui.text(ctx, o.path.substringAfterLast('/'), 12.5f, if (isActive) Ui.fg else Ui.fg3)
        val close = Ui.text(ctx, if (o.dirty) "●" else "✕", 11f, if (o.dirty) Ui.accent else Ui.fg3)
        close.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
        close.tooltipText = if (o.dirty) "Unsaved changes. Click to close." else "Close"
        close.setOnClickListener { close(o) }
        val inner = Ui.row(ctx, name, close)
        inner.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 6), 0)
        val underline = View(ctx)
        underline.setBackgroundColor(if (isActive) Ui.accent else Color.TRANSPARENT)
        val col = Ui.column(ctx)
        col.addView(inner, Ui.lp(Ui.wrap, 0, 1f))
        col.addView(underline, Ui.lp(Ui.match, Ui.dp(ctx, 2)))
        col.setBackgroundColor(if (isActive) Ui.bg else Ui.bg2)
        col.setOnClickListener { activate(o) }
        col.setOnLongClickListener { close(o); true }
        // A mouse middle-click closes the tab, like in a browser.
        col.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_TERTIARY) != 0) { close(o); true } else false
        }
        col.tooltipText = o.path
        return col
    }

    private fun close(o: Open) {
        if (!o.dirty) { closeNow(o); return }
        AlertDialog.Builder(ctx).setTitle("Unsaved changes").setMessage(o.path + " has changes that are not saved.")
            .setPositiveButton("Save") { _, _ -> save(o) { closeNow(o) } }
            .setNegativeButton("Discard") { _, _ -> closeNow(o) }
            .setNeutralButton("Keep open", null).show()
    }

    private fun closeNow(o: Open) {
        open.remove(o.path)
        editorFrame.removeView(o.editor)
        if (active === o) {
            active = null
            val next = open.values.lastOrNull()
            if (next != null) activate(next)
            else { welcome.visibility = View.VISIBLE; crumbs.visibility = View.GONE; renderTabs(); host.setWindowTitle(""); selectedPath = ""; treeAdapter.notifyDataSetChanged() }
        } else renderTabs()
    }

    fun saveActive() { val o = active ?: return; save(o) { } }

    private fun save(o: Open, done: () -> Unit) {
        val text = o.editor.text()
        val path = o.path
        Api.async("save", { Api.post("/api/file", JSONObject().put("path", path).put("content", text)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Not saved: " + (e?.message ?: "")); return@async }
            if (o.dirty && o.editor.text() == text) { o.dirty = false; renderTabs() }
            Ui.toast(ctx, "Saved " + path)
            done()
        }
    }

    /** A file the agent changed: reload it if it is open and not edited here, and refresh the tree. */
    fun fileChanged(path: String) {
        val o = open[path]
        if (o != null && !o.dirty) {
            Api.async("file", { Api.get("/api/file", mapOf("path" to path)) }) { r, _ ->
                if (r == null || o.dirty) return@async
                o.mtime = r.optDouble("mtime", 0.0)
                o.editor.replaceContent(r.optString("content"))
            }
        }
        refresh()
    }

    fun activePath(): String = active?.path ?: ""
    /** The text selected in the active editor, or an empty string. */
    fun selectionText(): String = active?.editor?.selectionText() ?: ""
    fun focusEditor() { active?.editor?.focus() }

    // ---------------------------------------------------------------- Ctrl P: the quick-open palette
    fun quickOpen() {
        val input = Ui.input(ctx, "Type part of a file name")
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        input.background = null
        input.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
        val items = ArrayList<String>()
        var selected = 0
        var generation = 0
        val list = ListView(ctx)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: Ui.row(ctx, Ui.text(ctx, "", 10f, Ui.fg3, bold = true).also { it.gravity = Gravity.CENTER; it.layoutParams = Ui.lp(Ui.dp(ctx, 28), Ui.wrap) },
                    Ui.text(ctx, "", 13f, Ui.fg), Ui.text(ctx, "", 12f, Ui.fg3).also { it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.START; it.setPadding(Ui.dp(ctx, 8), 0, 0, 0) })
                    .also { it.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6)); (it.getChildAt(2) as TextView).layoutParams = Ui.lp(0, Ui.wrap, 1f) }
                val p = items[position]
                val badge = FileIcons.badge(p)
                (row.getChildAt(0) as TextView).also { it.text = badge.first; it.setTextColor(badge.second) }
                (row.getChildAt(1) as TextView).text = p.substringAfterLast('/')
                (row.getChildAt(2) as TextView).text = if (p.contains('/')) p.substringBeforeLast('/') else ""
                row.background = if (position == selected) Ui.rounded(accentSoft, 2) else null
                return row
            }
        }
        list.adapter = adapter
        val box = Ui.column(ctx, input, Ui.divider(ctx, false), list)
        list.layoutParams = Ui.lp(Ui.match, Ui.dp(ctx, 360))
        box.setBackgroundColor(Ui.bg2)
        val dialog = AlertDialog.Builder(ctx).setView(box).create()
        dialog.window?.setBackgroundDrawable(Ui.rounded(Ui.bg2, 4, border2))
        fun openSelected() { val p = items.getOrNull(selected) ?: return; dialog.dismiss(); openFile(p) }
        fun search(q: String) {
            val g = ++generation
            Api.async("files", { Api.get("/api/files", mapOf("q" to q)) }) { r, _ ->
                if (g != generation) return@async
                items.clear()
                val arr = r?.optJSONArray("files")
                if (arr != null) for (i in 0 until minOf(arr.length(), 200)) items.add(arr.getString(i))
                selected = 0
                adapter.notifyDataSetChanged()
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { search(s.toString()) }
        })
        input.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (selected < items.size - 1) selected++; adapter.notifyDataSetChanged(); list.smoothScrollToPosition(selected); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (selected > 0) selected--; adapter.notifyDataSetChanged(); list.smoothScrollToPosition(selected); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { openSelected(); true }
                KeyEvent.KEYCODE_ESCAPE -> { dialog.dismiss(); true }
                else -> false
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> selected = pos; openSelected() }
        dialog.show()
        dialog.window?.setLayout(Ui.dp(ctx, 640), WindowManager.LayoutParams.WRAP_CONTENT)
        search("")
        input.requestFocus()
    }

    // ---------------------------------------------------------------- Ctrl Shift F: find in files
    private class Hit(val path: String, val line: Int, val text: String, val header: Boolean)

    /** Searches the folder with the server's grep; results are grouped by file and a tap opens the file at the line. */
    fun findInFiles() {
        val query = Ui.input(ctx, "Search in files", mono = true)
        val glob = Ui.input(ctx, "*.kt, *.py", mono = true)
        val regex = CheckBox(ctx); regex.text = "Regex"; regex.setTextColor(Ui.fg2); regex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val status = Ui.text(ctx, "Enter searches. Matches come from grep, at most 400.", 11.5f, Ui.fg3)
        val hits = ArrayList<Hit>()
        val list = ListView(ctx)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = hits.size
            override fun getItem(position: Int): Any = hits[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getViewTypeCount(): Int = 2
            override fun getItemViewType(position: Int): Int = if (hits[position].header) 0 else 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val h = hits[position]
                if (h.header) {
                    val t = (convertView as? TextView) ?: Ui.text(ctx, "", 12f, accent2, bold = true).also { it.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 2)) }
                    t.text = h.path
                    return t
                }
                val row = (convertView as? LinearLayout) ?: Ui.row(ctx, Ui.text(ctx, "", 11.5f, Ui.fg3, mono = true).also { it.setPadding(0, 0, Ui.dp(ctx, 6), 0) },
                    Ui.text(ctx, "", 11.5f, Ui.fg2, mono = true).also { it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END })
                    .also { it.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 2), Ui.dp(ctx, 12), Ui.dp(ctx, 2)) }
                (row.getChildAt(0) as TextView).text = h.line.toString()
                (row.getChildAt(1) as TextView).text = h.text.trim()
                return row
            }
        }
        list.adapter = adapter
        val top = Ui.row(ctx, query, glob, regex)
        query.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        glob.layoutParams = Ui.lp(Ui.dp(ctx, 150), Ui.wrap).also { it.setMargins(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0) }
        top.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 4))
        status.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), Ui.dp(ctx, 4))
        val box = Ui.column(ctx, top, status, Ui.divider(ctx, false), list)
        list.layoutParams = Ui.lp(Ui.match, Ui.dp(ctx, 380))
        box.setBackgroundColor(Ui.bg2)
        val dialog = AlertDialog.Builder(ctx).setView(box).create()
        dialog.window?.setBackgroundDrawable(Ui.rounded(Ui.bg2, 4, border2))
        val lineRx = Regex("^(.+?):(\\d+):(.*)$")
        var generation = 0
        fun search() {
            val q = query.text.toString()
            if (q.isEmpty()) return
            val g = ++generation
            status.text = "Searching…"
            val params = mapOf("q" to q, "path" to ".", "glob" to glob.text.toString().trim(), "regex" to if (regex.isChecked) "1" else "0")
            Api.async("search", { Api.get("/api/search", params, 30000) }) { r, e ->
                if (g != generation) return@async
                hits.clear()
                if (r == null) { status.text = "Search failed: " + (e?.message ?: "no answer"); adapter.notifyDataSetChanged(); return@async }
                val result = r.optString("result")
                var last = ""
                var first = ""
                for (line in result.split('\n')) {
                    val m = lineRx.matchEntire(line)
                    if (m == null) { if (first.isEmpty() && line.isNotBlank()) first = line; continue }
                    val path = m.groupValues[1]
                    if (path != last) { hits.add(Hit(path, 0, "", true)); last = path }
                    hits.add(Hit(path, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3], false))
                }
                status.text = if (first.isNotEmpty()) first else if (hits.isEmpty()) "No matches" else ""
                adapter.notifyDataSetChanged()
            }
        }
        val keys = View.OnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { search(); true }
                KeyEvent.KEYCODE_ESCAPE -> { dialog.dismiss(); true }
                else -> false
            }
        }
        query.setOnKeyListener(keys); glob.setOnKeyListener(keys)
        regex.setOnCheckedChangeListener { _, _ -> search() }
        list.setOnItemClickListener { _, _, pos, _ ->
            val h = hits.getOrNull(pos) ?: return@setOnItemClickListener
            dialog.dismiss()
            openFile(h.path, if (h.header) 0 else h.line)
        }
        val initial = active?.editor?.selectionText() ?: ""
        if (initial.isNotEmpty() && !initial.contains('\n')) query.setText(initial)
        dialog.show()
        dialog.window?.setLayout(Ui.dp(ctx, 760), WindowManager.LayoutParams.WRAP_CONTENT)
        query.requestFocus(); query.selectAll()
        if (initial.isNotEmpty()) search()
    }
}
