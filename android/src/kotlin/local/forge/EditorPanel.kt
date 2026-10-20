package local.forge

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
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
 * The Explorer and the editor area, like Cursor's.
 *
 * The Explorer: a file tree with icons, problem colors, a context menu and file management, and the OUTLINE of the
 * active file under it (the language server's symbols; a click jumps there).
 *
 * The editor area holds one or two editor groups side by side (Ctrl backslash splits; both groups can show the same
 * file, and their editors share the text, the undo history and the unsaved state). A group has its own tabs, a
 * breadcrumb line (folders, file, and the symbols around the cursor) and its content: a CodeEditor with its
 * EditorSmarts (language servers and AI), the image viewer for pictures, a native notebook for .ipynb files, and an
 * optional live Markdown preview next to a Markdown file (Ctrl Shift V).
 *
 * Reviews: a file the agent changed, or code written by Ctrl K, stays under review until it is kept or undone. The
 * editor shows the inline diff (green new lines, red removed lines, Keep and Undo on each hunk) and the review bar.
 * Agent reviews survive a restart of the window (the base text comes back from the server's checkpoints).
 */
class EditorPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)

    private val accentSoft = 0x24ff8a3d
    private val accent2 = Color.parseColor("#ffb07a")
    private val bg4 = Color.parseColor("#222738")
    private val border2 = Color.parseColor("#2f3548")
    private fun dp(v: Int) = Ui.dp(ctx, v)

    // ---------------------------------------------------------------- the Explorer
    private class Entry(val path: String, val name: String, val dir: Boolean, val size: Long, val depth: Int, val placeholder: Boolean = false,
                        val root: Int = 0) {
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
    private val explorer = Ui.column(ctx)

    // ---- the outline
    private val outlineHead: LinearLayout
    private val outlineChevron = ImageView(ctx)
    private val outlineList = ListView(ctx)
    private val outlineRows = ArrayList<Pair<Lsp.Symbol, Int>>()
    private val outlineAdapter = OutlineAdapter()
    private var outlineOpen = true
    private var outlineFor = ""
    private var symbols: List<Lsp.Symbol> = emptyList()
    private var symbolsFor = ""
    private val symbolsTask = Runnable { loadSymbols() }

    // ---------------------------------------------------------------- documents, tabs and groups
    /** One file's text state, shared by every editor that shows it. */
    inner class Doc(var path: String) {
        var dirty = false
        var mtime = 0.0
        val history = CodeEditor.History()
        val tabs = ArrayList<TextTab>()
        fun text(): String = tabs.firstOrNull()?.editor?.text() ?: ""
    }
    private val docs = HashMap<String, Doc>()

    abstract inner class Tab(var group: Group, var path: String) {
        abstract val content: View
        var strip: View? = null
        open val dirty: Boolean get() = false
        open fun focus() {}
        open fun dispose() {}
        open fun renamed(newPath: String) { path = newPath }
    }

    inner class TextTab(group: Group, path: String, val doc: Doc) : Tab(group, path) {
        val editor = CodeEditor(ctx)
        lateinit var smarts: EditorSmarts
        var preview: PreviewPanel.MarkdownPreview? = null
        val box = LinearLayout(ctx)
        override val content: View get() = box
        override val dirty: Boolean get() = doc.dirty
        override fun focus() { editor.focus() }
        override fun dispose() { smarts.detach() }
        override fun renamed(newPath: String) { path = newPath; editor.path = newPath; preview?.renameTo(newPath) }
    }

    inner class ImageTab(group: Group, path: String) : Tab(group, path) {
        val viewer = PreviewPanel.ImageViewer(ctx, path)
        override val content: View get() = viewer.view
        override fun renamed(newPath: String) { path = newPath; viewer.path = newPath; viewer.load() }
    }

    inner class NotebookTab(group: Group, path: String) : Tab(group, path) {
        val nb = NotebookPanel(ctx, host)
        override val content: View get() = nb.view
        override val dirty: Boolean get() = nb.isDirty()
        override fun dispose() { nb.close() }
    }

    inner class Group {
        val view = Ui.column(ctx)
        val strip = LinearLayout(ctx)
        val stripScroll = HorizontalScrollView(ctx)
        val crumbs = LinearLayout(ctx)
        val crumbScroll = HorizontalScrollView(ctx)
        val frame = FrameLayout(ctx)
        val tabs = ArrayList<Tab>()
        var active: Tab? = null
        val welcome: View? = if (groups.isEmpty()) buildWelcome() else null

        init {
            view.setBackgroundColor(Ui.bg)
            strip.orientation = LinearLayout.HORIZONTAL
            stripScroll.addView(strip, FrameLayout.LayoutParams(Ui.wrap, Ui.match))
            stripScroll.isHorizontalScrollBarEnabled = false
            val split = Ui.miniButton(ctx, R.drawable.ic_split, "Split the editor (Ctrl \\)") { focusGroup(this); toggleSplit() }
            val more = Ui.miniButton(ctx, R.drawable.ic_chevron_down, "More: close tabs, preview, reveal") { v -> groupMenu(this, v) }
            val head = Ui.row(ctx, stripScroll, split, more)
            stripScroll.layoutParams = Ui.lp(0, Ui.match, 1f)
            for (b in listOf(split, more)) b.layoutParams = Ui.lp(dp(26), dp(26)).also { it.rightMargin = dp(3) }
            head.setBackgroundColor(Ui.bg2)
            view.addView(head, Ui.lp(Ui.match, dp(34)))
            view.addView(Ui.divider(ctx, false))
            crumbs.orientation = LinearLayout.HORIZONTAL
            crumbs.gravity = Gravity.CENTER_VERTICAL
            crumbs.setPadding(dp(10), 0, dp(10), 0)
            crumbScroll.addView(crumbs, FrameLayout.LayoutParams(Ui.wrap, Ui.match))
            crumbScroll.isHorizontalScrollBarEnabled = false
            crumbScroll.visibility = View.GONE
            view.addView(crumbScroll, Ui.lp(Ui.match, dp(24)))
            if (welcome != null) frame.addView(welcome, FrameLayout.LayoutParams(Ui.match, Ui.match))
            view.addView(frame, Ui.lp(Ui.match, 0, 1f))
            // A click anywhere in the group makes it the one new files open in.
            view.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) focusGroup(this); false }
        }
    }

    private val groups = ArrayList<Group>()
    private val groupsRow = LinearLayout(ctx)
    private var current: Group
    private var groupSplitter: View? = null

    // ---------------------------------------------------------------- git (Explorer colors, gutter bars, branch)
    private var gitRepo = false
    private val gitStatus = HashMap<String, String>()
    private val gitHead = HashMap<String, String>()
    private val gitTask = Runnable { loadGit() }
    private val gitMarkTasks = HashMap<String, Runnable>()

    // ---------------------------------------------------------------- reviews (inline diffs under review)
    private class Review(var base: String, val runId: String, val label: String, val inline: Boolean)
    private val reviews = LinkedHashMap<String, Review>()
    private val reviewTasks = HashMap<String, Runnable>()

    init {
        view.orientation = LinearLayout.HORIZONTAL
        val pad = dp(8)

        // ---------------------------------------------------------------- Explorer column
        explorer.setBackgroundColor(Ui.bg2)
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
        explorer.addView(head, Ui.lp(Ui.match, Ui.wrap))
        explorer.addView(Ui.divider(ctx, false))
        treeList.adapter = treeAdapter
        treeList.divider = null
        treeList.selector = ColorDrawable(Color.TRANSPARENT)
        treeList.isVerticalScrollBarEnabled = true
        treeList.setOnItemClickListener { _, _, pos, _ ->
            val e = visible.getOrNull(pos) ?: return@setOnItemClickListener
            if (e.placeholder) return@setOnItemClickListener
            if (e.dir) { selectedPath = e.path; toggle(e) } else openFile(e.path)
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
        explorer.addView(treeList, Ui.lp(Ui.match, 0, 1f))
        emptyLabel.setPadding(pad * 2, pad, pad, pad)
        emptyLabel.visibility = View.GONE
        explorer.addView(emptyLabel, Ui.lp(Ui.match, Ui.wrap))

        // The outline, collapsible, under the tree.
        outlineChevron.setImageDrawable(Ui.icon(ctx, R.drawable.ic_chevron_down, Ui.fg3))
        val outlineTitle = Ui.text(ctx, "OUTLINE", 11f, Ui.fg3, bold = true)
        outlineTitle.letterSpacing = 0.06f
        outlineHead = Ui.row(ctx, outlineChevron, outlineTitle)
        outlineChevron.layoutParams = Ui.lp(dp(14), dp(14)).also { it.rightMargin = dp(4) }
        outlineHead.setPadding(pad, dp(5), pad, dp(5))
        outlineHead.setOnClickListener { outlineOpen = !outlineOpen; showOutline() }
        explorer.addView(Ui.divider(ctx, false))
        explorer.addView(outlineHead, Ui.lp(Ui.match, Ui.wrap))
        outlineList.adapter = outlineAdapter
        outlineList.divider = null
        outlineList.selector = ColorDrawable(Color.TRANSPARENT)
        outlineList.setOnItemClickListener { _, _, pos, _ ->
            val s = outlineRows.getOrNull(pos)?.first ?: return@setOnItemClickListener
            openAt(outlineFor, s.line, s.col)
        }
        explorer.addView(outlineList, Ui.lp(Ui.match, 0, 0.7f))
        view.addView(explorer, Ui.lp(dp(240), Ui.match))
        view.addView(Ui.divider(ctx, true))

        // ---------------------------------------------------------------- the editor groups
        groupsRow.orientation = LinearLayout.HORIZONTAL
        current = Group()
        groups.add(current)
        groupsRow.addView(current.view, Ui.lp(0, Ui.match, 1f))
        view.addView(groupsRow, Ui.lp(0, Ui.match, 1f))
        renderTabs(current)
        showOutline()

        Lsp.applyEdit = { edits -> applyWorkspaceEdit(edits) > 0 }
        Lsp.notify = { msg -> Ui.toast(ctx, msg) }
        Lsp.listeners.add { _, _ -> treeAdapter.notifyDataSetChanged() }
    }

    private fun iconButton(kind: String, tip: String, onClick: (View) -> Unit): View {
        val b = ImageButton(ctx)
        b.setImageDrawable(FileIcons.action(ctx, kind))
        b.background = null
        b.scaleType = ImageView.ScaleType.CENTER
        b.contentDescription = tip
        b.tooltipText = tip
        val s = dp(26)
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
        val pad = dp(20)
        inner.setPadding(pad, pad, pad, pad)

        val logo = TextView(ctx)
        logo.text = "F"
        logo.setTextColor(Color.WHITE)
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        logo.gravity = Gravity.CENTER
        val grad = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.accent, Color.parseColor("#ff5e62")))
        grad.cornerRadius = dp(14).toFloat()
        logo.background = grad
        inner.addView(logo, Ui.lp(dp(56), dp(56)))

        val title = Ui.text(ctx, "Forge IDE", 26f, Ui.fg, bold = true)
        title.setPadding(0, dp(14), 0, dp(6))
        inner.addView(title)
        val sub = Ui.text(ctx, "Your local AI coding IDE. Everything runs on this Googlebook and nothing leaves the machine.", 13f, Ui.fg3)
        sub.gravity = Gravity.CENTER
        inner.addView(sub, Ui.lp(dp(480), Ui.wrap))

        val shortcuts = listOf("Go to file" to "Ctrl P", "Command palette" to "Ctrl Shift P", "Chat with the agent" to "Ctrl L",
            "Edit code with AI" to "Ctrl K", "Terminal" to "Ctrl `", "Search in files" to "Ctrl Shift F", "Split the editor" to "Ctrl \\",
            "Go to definition" to "F12", "Quick fix" to "Ctrl .", "Problems" to "Ctrl Shift M")
        val grid = LinearLayout(ctx)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(0, dp(22), 0, dp(22))
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
                cell.setPadding(0, dp(4), if (j == 0) dp(24) else 0, dp(4))
                row.addView(cell, Ui.lp(0, Ui.wrap, 1f))
            }
            grid.addView(row, Ui.lp(Ui.match, Ui.wrap))
            i += 2
        }
        inner.addView(grid, Ui.lp(dp(520), Ui.wrap))

        val actions = Ui.row(ctx,
            Ui.button(ctx, "Open a file", primary = true) { quickOpen() },
            spacer(8), Ui.button(ctx, "New file") { newFileDialog("") },
            spacer(8), Ui.button(ctx, "Commands") { host.openPalette(">") },
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
        t.setPadding(dp(5), dp(1), dp(5), dp(1))
        return t
    }

    // ---------------------------------------------------------------- the tree
    private fun folderName(): String = if (Api.folder.isEmpty()) "Forge" else Api.folder.trimEnd('/').substringAfterLast('/')

    fun refresh() {
        folderLabel.text = folderName()
        scheduleGit(300)
        Api.async("tree", { Pair(Api.get("/api/tree", mapOf("path" to ".")), try { Api.get("/api/roots") } catch (e: Exception) { null }) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Explorer: " + (e?.message ?: "no answer")); return@async }
            roots.clear()
            val extra = r.second?.optJSONArray("extra")
            if (extra == null || extra.length() == 0) roots.addAll(parse(r.first, 0))
            else {
                // Several folders in this window: one section per folder, like Cursor's multi-root workspaces.
                val primary = Entry(".", folderName(), true, 0L, 0, root = 1)
                primary.expanded = "!collapsed:." !in expandedPaths
                primary.children = parse(r.first, 1)
                roots.add(primary)
                for (i in 0 until extra.length()) {
                    val x = extra.getString(i)
                    val e2 = Entry(x, x.trimEnd('/').substringAfterLast('/'), true, 0L, 0, root = 2)
                    roots.add(e2)
                    if ("!collapsed:$x" !in expandedPaths) { e2.expanded = true; load(e2) }
                }
                restoreExpanded(primary.children!!)
            }
            restoreExpanded(roots.filter { it.root == 0 })
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
        if (e.root > 0) {
            // A folder section of a multi-root window remembers being closed rather than being open.
            if (e.expanded) { e.expanded = false; expandedPaths.add("!collapsed:" + e.path); rebuild() }
            else { e.expanded = true; expandedPaths.remove("!collapsed:" + e.path); if (e.children == null) load(e) else rebuild() }
            return
        }
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

    /** Shows a file in the tree: its folders open and its row selected and scrolled into view. */
    fun revealInTree(path: String) {
        host.showExplorer()
        expandAncestors(path)
        selectedPath = path
        refresh()
        treeList.postDelayed({ val i = visible.indexOfFirst { it.path == path }; if (i >= 0) treeList.setSelection(maxOf(0, i - 5)) }, 600)
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

    /** Errors and warnings under a path (a file, or every file in a folder), from the language servers. */
    private fun problemCounts(path: String, dir: Boolean): Pair<Int, Int> {
        var e = 0; var w = 0
        for ((p, list) in Lsp.diagnostics) {
            if (p == path || (dir && p.startsWith("$path/"))) for (d in list) { if (d.severity <= 1) e++ else if (d.severity == 2) w++ }
        }
        return e to w
    }

    private inner class TreeAdapter : BaseAdapter() {
        private inner class Row(val root: LinearLayout, val chevron: ImageView, val icon: ImageView, val name: TextView, val meta: TextView)

        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView?.tag as? Row) ?: newRow()
            val e = visible[position]
            row.root.setPadding(dp(6) + e.depth * dp(12), 0, dp(8), 0)
            row.chevron.setImageDrawable(if (e.dir) Ui.icon(ctx, if (e.expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, Ui.fg3) else null)
            if (e.placeholder) {
                row.icon.setImageDrawable(null)
                row.name.text = e.name; row.name.setTextColor(Ui.fg3); row.name.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
                row.meta.text = ""
                row.root.background = null
                return row.root
            }
            row.name.setTypeface(if (e.root > 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT, Typeface.NORMAL)
            row.icon.setImageDrawable(if (e.dir) FileIcons.folder(ctx, e.expanded) else FileIcons.file(ctx, e.name))
            row.name.text = e.name
            val (errors, warnings) = problemCounts(e.path, e.dir)
            val reviewing = !e.dir && reviews.containsKey(e.path)
            val git = gitColor(e.path, e.dir)
            val gitLetter = if (!e.dir) gitStatus[e.path]?.let { if (it == "??") "U" else it.take(1) } else null
            row.name.setTextColor(when { errors > 0 -> Ui.red; warnings > 0 -> Ui.yellow; reviewing -> accent2; git != 0 -> git; e.dir || e.path == selectedPath -> Ui.fg; else -> Ui.fg2 })
            row.meta.text = when {
                errors + warnings > 0 && !e.dir -> (errors + warnings).toString()
                errors + warnings > 0 -> "●"
                reviewing -> "R"
                gitLetter != null -> gitLetter
                e.dir && git != 0 -> "●"
                e.dir -> ""
                else -> humanSize(e.size)
            }
            row.meta.setTextColor(if (errors > 0) Ui.red else if (warnings > 0) Ui.yellow else if (reviewing) accent2 else if (git != 0) git else Ui.fg3)
            row.root.background = if (e.path == selectedPath) ColorDrawable(accentSoft) else null
            return row.root
        }

        private fun newRow(): Row {
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.minimumHeight = dp(24)
            val chevron = ImageView(ctx)
            root.addView(chevron, Ui.lp(dp(14), dp(14)))
            val icon = ImageView(ctx)
            root.addView(icon, Ui.lp(dp(16), dp(16)).also { it.setMargins(dp(2), 0, dp(6), 0) })
            val name = Ui.text(ctx, "", 12.5f, Ui.fg2)
            name.isSingleLine = true; name.ellipsize = TextUtils.TruncateAt.END
            root.addView(name, Ui.lp(0, Ui.wrap, 1f))
            val meta = Ui.text(ctx, "", 10f, Ui.fg3)
            meta.setPadding(dp(6), 0, 0, 0)
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
        if (e.root == 2) {
            menu.menu.add(0, 20, 0, "Remove this folder from the window (the folder itself stays)")
            menu.setOnMenuItemClickListener { item ->
                if (item.itemId == 20) Api.async("roots", { Api.post("/api/roots", JSONObject().put("action", "remove").put("path", e.path)) }) { r, err ->
                    if (r == null) Ui.toast(ctx, "Not removed: " + (err?.message ?: "")) else refresh()
                }
                true
            }
            menu.show()
            return
        }
        if (!e.dir) {
            menu.menu.add(0, 10, 0, "Open to the side")
            menu.menu.add(0, 11, 1, "Add to the chat")
        } else {
            menu.menu.add(0, 12, 0, "Add the folder to the chat")
            menu.menu.add(0, 13, 1, "Find in this folder")
        }
        menu.menu.add(0, 14, 2, "Open a terminal here")
        menu.menu.add(0, 2, 3, "New file here")
        menu.menu.add(0, 3, 4, "New folder here")
        menu.menu.add(0, 1, 5, "Rename")
        menu.menu.add(0, 4, 6, "Copy the relative path")
        menu.menu.add(0, 6, 7, "Copy the full path")
        menu.menu.add(0, 5, 8, "Move to trash")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> renameDialog(e)
                2 -> newFileDialog(dir)
                3 -> newFolderDialog(dir)
                4 -> { host.copyToClipboard(e.path); Ui.toast(ctx, "Copied " + e.path) }
                6 -> { val full = Api.folder.trimEnd('/') + "/" + e.path; host.copyToClipboard(full); Ui.toast(ctx, "Copied $full") }
                5 -> trashDialog(e)
                10 -> openToSide(e.path)
                11 -> host.addToChat(JSONObject().put("kind", "file").put("path", e.path).put("name", e.path))
                12 -> host.addToChat(JSONObject().put("kind", "folder").put("path", e.path).put("name", e.path))
                13 -> host.showSearch(include = e.path.trimEnd('/') + "/**")
                14 -> host.terminalIn(dir)
            }
            true
        }
        menu.show()
    }

    /** The New file dialog with a name filled in (the palette's New file and New Jupyter notebook). */
    fun newFileAt(name: String) = newFileDialog(folderOfSelection(), name)

    private fun newFileDialog(dir: String, name: String = "") {
        val input = Ui.input(ctx, "name, for example src/main.py", mono = true)
        val prefix = if (dir.isNotEmpty() && dir != ".") dir.trimEnd('/') + "/" else ""
        if (prefix.isNotEmpty() || name.isNotEmpty()) {
            input.setText(prefix + name)
            val dot = name.lastIndexOf('.')
            if (name.isNotEmpty() && dot > 0) input.setSelection(prefix.length, prefix.length + dot) else input.setSelection(input.text.length)
        }
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
        fun moved(p: String) = p == oldPath || (dir && p.startsWith("$oldPath/"))
        fun target(p: String) = newPath + p.substring(oldPath.length)
        for (d in docs.values.filter { moved(it.path) }) {
            val old = d.path
            docs.remove(old)
            d.path = target(old)
            docs[d.path] = d
            for (t in d.tabs) t.renamed(d.path)
            Lsp.moved(old, d.path, d.text())
            reviews.remove(old)?.let { reviews[d.path] = it; persistReviews() }
        }
        for (g in groups) for (t in g.tabs) if (t !is TextTab && moved(t.path)) t.renamed(target(t.path))
        if (moved(selectedPath)) selectedPath = target(selectedPath)
        for (g in groups) renderTabs(g)
        activeTab()?.let { showCrumbs(it.group); host.setWindowTitle(it.path.substringAfterLast('/')) }
    }

    private fun trashDialog(e: Entry) {
        val what = if (e.dir) "the folder " + e.name + " and everything in it" else e.name
        AlertDialog.Builder(ctx).setTitle("Move to trash?").setMessage("Forge moves $what to its trash folder.")
            .setPositiveButton("Move to trash") { _, _ ->
                Api.async("trash", { Api.post("/api/file/trash", JSONObject().put("path", e.path)) }) { r, err ->
                    if (r == null) { Ui.toast(ctx, "Not moved: " + (err?.message ?: "")); return@async }
                    // Tabs of trashed files close, except edited ones: saving them again recreates the file.
                    for (g in groups) for (t in g.tabs.toList()) if (!t.dirty && (t.path == e.path || t.path.startsWith(e.path + "/"))) closeNow(t)
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

    // ---------------------------------------------------------------- groups
    private fun focusGroup(g: Group) {
        if (current === g) return
        current = g
        for (x in groups) renderTabs(x)
        g.active?.let { onActiveChanged(it) }
    }

    /** Ctrl backslash: opens the active file in a second group on the right, or closes the second group. */
    fun toggleSplit() {
        if (groups.size > 1) { closeGroup(groups[1]); return }
        val src = current.active
        val g = Group()
        groups.add(g)
        val sp = Chrome.splitter(ctx, vertical = true, onDrag = { d -> resizeGroups(d) })
        groupSplitter = sp
        groupsRow.addView(sp)
        groupsRow.addView(g.view, Ui.lp(0, Ui.match, 1f))
        current = g
        renderTabs(g)
        if (src != null) open(src.path, g, activate = true) { } else renderTabs(groups[0])
    }

    fun openToSide(path: String) {
        if (groups.size < 2) {
            val g = Group()
            groups.add(g)
            val sp = Chrome.splitter(ctx, vertical = true, onDrag = { d -> resizeGroups(d) })
            groupSplitter = sp
            groupsRow.addView(sp)
            groupsRow.addView(g.view, Ui.lp(0, Ui.match, 1f))
        }
        current = groups[1]
        open(path, groups[1], activate = true) { }
        renderTabs(groups[0])
    }

    private fun resizeGroups(delta: Int) {
        if (groups.size < 2) return
        val a = groups[0].view.layoutParams as LinearLayout.LayoutParams
        val b = groups[1].view.layoutParams as LinearLayout.LayoutParams
        val total = groups[0].view.width + groups[1].view.width
        if (total <= 0) return
        val wa = (groups[0].view.width + delta).coerceIn(dp(200), total - dp(200))
        a.weight = wa.toFloat() / total; b.weight = 1f - a.weight
        groups[0].view.layoutParams = a; groups[1].view.layoutParams = b
    }

    private fun closeGroup(g: Group) {
        if (groups.size < 2) return
        val dirty = g.tabs.filter { it.dirty && !(it is TextTab && it.doc.tabs.size > 1) }
        if (dirty.isNotEmpty()) {
            AlertDialog.Builder(ctx).setTitle("Unsaved changes").setMessage(dirty.joinToString("\n") { it.path } + "\n\nhave changes that are not saved.")
                .setPositiveButton("Save all") { _, _ -> var left = dirty.size; for (t in dirty) saveTab(t) { left--; if (left == 0) closeGroupNow(g) } }
                .setNegativeButton("Discard") { _, _ -> closeGroupNow(g) }
                .setNeutralButton("Cancel", null).show()
            return
        }
        closeGroupNow(g)
    }

    /** Closes a group's tabs. The left group always stays (it holds the welcome screen): when it is the one closed,
     *  the right group's tabs move into it. */
    private fun closeGroupNow(g: Group) {
        for (t in g.tabs.toList()) { disposeTab(t); g.frame.removeView(t.content) }
        g.tabs.clear(); g.active = null
        val left = groups[0]
        val right = groups.getOrNull(1) ?: return
        if (g === left) {
            for (t in right.tabs.toList()) { right.frame.removeView(t.content); t.group = left; left.tabs.add(t) }
            left.active = null
            right.tabs.clear()
        }
        groups.remove(right)
        groupsRow.removeView(right.view)
        groupSplitter?.let { groupsRow.removeView(it) }
        groupSplitter = null
        (left.view.layoutParams as LinearLayout.LayoutParams).weight = 1f
        current = left
        val next = left.tabs.firstOrNull()
        if (next != null && left.active == null) activate(next) else { renderTabs(left); left.active?.let { onActiveChanged(it); it.focus() } }
    }

    private fun groupMenu(g: Group, anchor: View) {
        val menu = PopupMenu(ctx, anchor)
        val t = g.active
        if (t is TextTab && PreviewPanel.isMarkdown(t.path)) menu.menu.add(0, 1, 0, if (t.preview == null) "Open the Markdown preview (Ctrl Shift V)" else "Close the Markdown preview")
        if (t != null) {
            menu.menu.add(0, 2, 1, "Reveal in the Explorer")
            menu.menu.add(0, 3, 2, "Add this file to the chat")
        }
        menu.menu.add(0, 4, 3, "Close saved tabs")
        menu.menu.add(0, 5, 4, "Close all tabs")
        if (groups.size > 1) menu.menu.add(0, 6, 5, "Close this group")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> togglePreview()
                2 -> t?.let { revealInTree(it.path) }
                3 -> t?.let { host.addToChat(JSONObject().put("kind", "file").put("path", it.path).put("name", it.path)) }
                4 -> for (x in g.tabs.toList()) if (!x.dirty) closeNow(x)
                5 -> for (x in g.tabs.toList()) close(x)
                6 -> closeGroup(g)
            }
            true
        }
        menu.show()
    }

    // ---------------------------------------------------------------- opening files
    /** Opens a file in the focused group (or brings its tab to the front); a line above 0 (1-based) moves the cursor there. */
    fun openFile(path: String, line: Int = 0) {
        open(path, current, activate = true) { t -> if (line > 0 && t is TextTab) t.editor.goToLine(line) }
    }

    /** Opens a file at a 0-based line and column (go to definition, problems, search results, the outline). */
    fun openAt(path: String, line: Int, col: Int) {
        val existing = current.tabs.firstOrNull { it.path == path } ?: groups.flatMap { it.tabs }.firstOrNull { it.path == path }
        val g = existing?.group ?: current
        open(path, g, activate = true) { t -> if (t is TextTab) t.editor.goTo(line, col) }
    }

    private fun open(path: String, g: Group, activate: Boolean, then: (Tab) -> Unit) {
        val existing = g.tabs.firstOrNull { it.path == path }
        if (existing != null) { if (activate) activate(existing); then(existing); return }
        when {
            PreviewPanel.isImage(path) -> { val t = ImageTab(g, path); addTab(t, activate); then(t) }
            path.endsWith(".ipynb", ignoreCase = true) -> { val t = NotebookTab(g, path); t.nb.open(path); addTab(t, activate); then(t) }
            else -> {
                val doc = docs[path]
                if (doc != null && doc.tabs.isNotEmpty()) {
                    val t = makeTextTab(g, doc, doc.text())
                    addTab(t, activate); then(t)
                    return
                }
                Api.async("file", { Api.get("/api/file", mapOf("path" to path)) }) { r, e ->
                    if (r == null) { Ui.toast(ctx, "Cannot open " + path + ": " + (e?.message ?: "")); return@async }
                    val again = g.tabs.firstOrNull { it.path == path }
                    if (again != null) { if (activate) activate(again); then(again); return@async }
                    val d = docs[path] ?: Doc(path).also { docs[path] = it }
                    d.mtime = r.optDouble("mtime", 0.0)
                    val t = makeTextTab(g, d, r.optString("content"))
                    if (d.tabs.size == 1) Lsp.open(path, t.editor.text())
                    addTab(t, activate); then(t)
                    renderReview(path)
                }
            }
        }
    }

    private fun makeTextTab(g: Group, doc: Doc, text: String): TextTab {
        val t = TextTab(g, doc.path, doc)
        val ed = t.editor
        ed.setContent(text, doc.path)
        ed.history = doc.history
        doc.tabs.add(t)
        ed.onChanged = { if (!doc.dirty) { doc.dirty = true; for (x in groups) renderTabs(x) }; scheduleReview(doc.path); scheduleSymbols(); scheduleGitMarks(doc.path) }
        // Changes made in one editor of the file appear in its other editor (the split view).
        ed.changeHook = { start, removed, added -> for (o in doc.tabs) if (o !== t) o.editor.mirrorEdit(start, removed, added); if (doc.tabs.size > 1) scheduleReview(doc.path) }
        ed.focusHook = { if (current !== t.group) focusGroup(t.group); if (t.group.active === t) onActiveChanged(t) }
        ed.onReviewHunk = { i, keep -> reviewHunk(doc.path, ed, i, keep) }
        ed.onReviewAll = { keep -> reviewAll(doc.path, keep) }
        scheduleGitMarks(doc.path, 300)
        ed.onTextChanged = { t.preview?.update(ed.text()) }
        applyEditorSettings(ed)
        t.smarts = EditorSmarts(ctx, host, this, ed)
        if (doc.path == execPath && execLine > 0) ed.showExecutionLine(execLine)
        t.box.orientation = LinearLayout.HORIZONTAL
        t.box.addView(ed, Ui.lp(0, Ui.match, 1f))
        return t
    }

    private fun addTab(t: Tab, activate: Boolean) {
        val g = t.group
        val idx = g.active?.let { g.tabs.indexOf(it) + 1 } ?: g.tabs.size
        g.tabs.add(idx.coerceIn(0, g.tabs.size), t)
        if (activate || g.active == null) activate(t) else renderTabs(g)
    }

    private fun activate(t: Tab) {
        val g = t.group
        val prev = g.active
        g.active = t
        if (prev !== t) {
            if (prev != null) g.frame.removeView(prev.content)
            (t.content.parent as? ViewGroup)?.removeView(t.content)
            g.frame.addView(t.content, FrameLayout.LayoutParams(Ui.match, Ui.match))
        }
        g.welcome?.visibility = View.GONE
        current = g
        for (x in groups) renderTabs(x)
        onActiveChanged(t)
        t.focus()
    }

    private fun activeTab(): Tab? = current.active

    private fun onActiveChanged(t: Tab) {
        selectedPath = t.path
        treeAdapter.notifyDataSetChanged()
        host.setWindowTitle(t.path.substringAfterLast('/'))
        showCrumbs(t.group)
        if (t is TextTab) { host.onEditorState(t.editor, t.path); scheduleSymbols(0) } else { host.onEditorState(null, t.path); symbols = emptyList(); symbolsFor = ""; showOutline() }
    }

    // ---------------------------------------------------------------- tabs
    private fun renderTabs(g: Group) {
        g.strip.removeAllViews()
        for (t in g.tabs) {
            val v = tabView(t)
            t.strip = v
            g.strip.addView(v, Ui.lp(Ui.wrap, Ui.match))
        }
        g.welcome?.visibility = if (g.tabs.isEmpty()) View.VISIBLE else View.GONE
        if (g.tabs.isEmpty()) g.crumbScroll.visibility = View.GONE
        val a = g.active?.strip
        if (a != null) g.stripScroll.post { g.stripScroll.requestChildRectangleOnScreen(g.strip, Rect(a.left, 0, a.right, a.height), false) }
    }

    private fun tabView(t: Tab): View {
        val g = t.group
        val isActive = t === g.active
        val focused = isActive && g === current
        val name = Ui.text(ctx, t.path.substringAfterLast('/'), 12.5f, if (isActive) Ui.fg else Ui.fg3)
        if (reviews.containsKey(t.path)) name.setTextColor(accent2)
        val icon = ImageView(ctx)
        icon.setImageDrawable(FileIcons.file(ctx, t.path.substringAfterLast('/')))
        val close = ImageView(ctx)
        close.setImageDrawable(if (t.dirty) dotDrawable() else Ui.icon(ctx, R.drawable.ic_close, if (isActive) Ui.fg2 else Ui.fg3))
        close.tooltipText = if (t.dirty) "Unsaved changes. Click to close." else "Close (Ctrl W)"
        close.setPadding(dp(3), dp(3), dp(3), dp(3))
        close.setOnClickListener { close(t) }
        val inner = Ui.row(ctx, icon, name, close)
        icon.layoutParams = Ui.lp(dp(16), dp(16)).also { it.rightMargin = dp(6) }
        close.layoutParams = Ui.lp(dp(18), dp(18)).also { it.leftMargin = dp(6) }
        inner.setPadding(dp(10), 0, dp(6), 0)
        val line = View(ctx)
        line.setBackgroundColor(if (focused) Ui.accent else if (isActive) Ui.fg3 else Color.TRANSPARENT)
        val col = Ui.column(ctx)
        col.addView(line, Ui.lp(Ui.match, dp(2)))
        col.addView(inner, Ui.lp(Ui.wrap, 0, 1f))
        col.setBackgroundColor(if (isActive) Ui.bg else Ui.bg2)
        col.setOnClickListener { activate(t) }
        col.setOnLongClickListener { tabMenu(t, col); true }
        col.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_TERTIARY) != 0) { close(t); true }
            else if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) { tabMenu(t, v); true }
            else false
        }
        col.tooltipText = t.path
        val wrap = LinearLayout(ctx)
        wrap.addView(col, Ui.lp(Ui.wrap, Ui.match))
        wrap.addView(Ui.divider(ctx, true))
        return wrap
    }

    /** The unsaved dot of a tab: a small accent circle with room around it. */
    private fun dotDrawable(): android.graphics.drawable.Drawable {
        val d = GradientDrawable(); d.shape = GradientDrawable.OVAL; d.setColor(Ui.accent); d.setSize(dp(8), dp(8))
        return android.graphics.drawable.InsetDrawable(d, dp(3))
    }

    private fun tabMenu(t: Tab, anchor: View) {
        val g = t.group
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, "Close")
        menu.menu.add(0, 2, 1, "Close others")
        menu.menu.add(0, 3, 2, "Close to the right")
        menu.menu.add(0, 4, 3, "Close saved")
        menu.menu.add(0, 5, 4, "Close all")
        menu.menu.add(0, 6, 5, if (g === groups[0]) "Open to the side" else "Move to the left group")
        menu.menu.add(0, 7, 6, "Reveal in the Explorer")
        menu.menu.add(0, 8, 7, "Add to the chat")
        menu.menu.add(0, 9, 8, "Copy the relative path")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> close(t)
                2 -> for (x in g.tabs.toList()) if (x !== t) close(x)
                3 -> { val i = g.tabs.indexOf(t); for (x in g.tabs.toList().drop(i + 1)) close(x) }
                4 -> for (x in g.tabs.toList()) if (!x.dirty) closeNow(x)
                5 -> for (x in g.tabs.toList()) close(x)
                6 -> if (g === groups[0]) openToSide(t.path) else { open(t.path, groups[0], true) { }; closeNow(t) }
                7 -> revealInTree(t.path)
                8 -> host.addToChat(JSONObject().put("kind", "file").put("path", t.path).put("name", t.path))
                9 -> { host.copyToClipboard(t.path); Ui.toast(ctx, "Copied " + t.path) }
            }
            true
        }
        menu.show()
    }

    /** Ctrl Tab and Ctrl Shift Tab: the next or previous tab of the focused group. */
    fun cycleTab(dir: Int) {
        val g = current
        if (g.tabs.size < 2) return
        val i = g.tabs.indexOf(g.active)
        activate(g.tabs[((i + dir) % g.tabs.size + g.tabs.size) % g.tabs.size])
    }

    fun closeActive() { current.active?.let { close(it) } }

    private fun close(t: Tab) {
        val lastOfDoc = t !is TextTab || t.doc.tabs.size <= 1
        if (!t.dirty || !lastOfDoc) { closeNow(t); return }
        AlertDialog.Builder(ctx).setTitle("Unsaved changes").setMessage(t.path + " has changes that are not saved.")
            .setPositiveButton("Save") { _, _ -> saveTab(t) { closeNow(t) } }
            .setNegativeButton("Discard") { _, _ -> closeNow(t) }
            .setNeutralButton("Keep open", null).show()
    }

    private fun disposeTab(t: Tab) {
        t.dispose()
        if (t is TextTab) {
            t.doc.tabs.remove(t)
            if (t.doc.tabs.isEmpty()) {
                docs.remove(t.doc.path)
                Lsp.close(t.doc.path)
                // An inline (Ctrl K) review cannot outlive the file's last editor; an agent review comes back on reopening.
                if (reviews[t.doc.path]?.inline == true) reviews.remove(t.doc.path)
            }
        }
    }

    private fun closeNow(t: Tab) {
        val g = t.group
        val idx = g.tabs.indexOf(t)
        disposeTab(t)
        g.tabs.remove(t)
        g.frame.removeView(t.content)
        if (g.active === t) {
            g.active = null
            val next = g.tabs.getOrNull(idx.coerceAtMost(g.tabs.size - 1)) ?: g.tabs.lastOrNull()
            if (next != null) activate(next)
            else if (groups.size > 1 && g !== groups[0]) closeGroupNow(g)
            else if (groups.size > 1) {
                // The left group emptied: the right group becomes the only one.
                val right = groups[1]
                groups.remove(right); groupsRow.removeView(right.view); groupSplitter?.let { groupsRow.removeView(it) }; groupSplitter = null
                for (x in right.tabs.toList()) { right.frame.removeView(x.content); x.group = g; g.tabs.add(x) }
                right.tabs.clear()
                current = g
                g.tabs.firstOrNull()?.let { activate(it) } ?: renderTabs(g)
            } else {
                renderTabs(g)
                host.setWindowTitle(""); selectedPath = ""; treeAdapter.notifyDataSetChanged()
                host.onEditorState(null, ""); symbols = emptyList(); symbolsFor = ""; showOutline()
            }
        } else renderTabs(g)
    }

    // ---------------------------------------------------------------- saving
    fun saveActive() { current.active?.let { saveTab(it) { } } }

    fun saveAll(done: (() -> Unit)? = null) {
        val dirty = groups.flatMap { it.tabs }.filter { it.dirty }.distinctBy { it.path }
        if (dirty.isEmpty()) { done?.invoke(); return }
        var left = dirty.size
        for (t in dirty) saveTab(t) { left--; if (left == 0) done?.invoke() }
    }

    fun dirtyCount(): Int = groups.flatMap { it.tabs }.filter { it.dirty }.distinctBy { it.path }.size

    private fun saveTab(t: Tab, done: () -> Unit) {
        when (t) {
            is NotebookTab -> t.nb.save { done() }
            is TextTab -> saveDoc(t.doc, done)
            else -> done()
        }
    }

    private fun saveDoc(d: Doc, done: () -> Unit) {
        val text = d.text()
        val path = d.path
        Api.async("save", { Api.post("/api/file", JSONObject().put("path", path).put("content", text)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Not saved: " + (e?.message ?: "")); return@async }
            d.mtime = r.optDouble("mtime", d.mtime)
            if (d.dirty && d.text() == text) { d.dirty = false; for (g in groups) renderTabs(g) }
            d.tabs.firstOrNull()?.smarts?.saved()
            host.reportCheck(path, r.optString("check"))
            host.flashStatus("Saved $path")
            scheduleGit()
            done()
        }
    }

    /** A file changed on disk (the agent wrote it): reload it where it is open and not edited here, and refresh the tree. */
    fun fileChanged(path: String) {
        val d = docs[path]
        if (d != null && !d.dirty) {
            Api.async("file", { Api.get("/api/file", mapOf("path" to path)) }) { r, _ ->
                if (r == null || d.dirty) return@async
                d.mtime = r.optDouble("mtime", 0.0)
                val text = r.optString("content")
                if (text != d.text()) {
                    for (t in d.tabs) t.editor.replaceContent(text)
                    Lsp.change(path, text)
                }
                renderReview(path)
            }
        }
        for (g in groups) for (t in g.tabs) if (t is ImageTab && t.path == path) t.viewer.load()
        refresh()
    }

    fun activePath(): String = current.active?.path ?: ""
    fun activeEditor(): CodeEditor? = (current.active as? TextTab)?.editor
    fun activeSmarts(): EditorSmarts? = (current.active as? TextTab)?.smarts
    /** The text selected in the active editor, or an empty string. */
    fun selectionText(): String = activeEditor()?.selectionText() ?: ""
    fun focusEditor() { current.active?.focus() }
    /** The current text of an open file, or null when it is not open. */
    fun textOf(path: String): String? = docs[path]?.let { if (it.tabs.isEmpty()) null else it.text() }
    fun openPaths(): List<String> = groups.flatMap { g -> g.tabs.map { it.path } }.distinct()

    /** Font size and minimap from the settings (13 points is the default, 12.5 sp on this screen). */
    private fun applyEditorSettings(ed: CodeEditor) {
        ed.setFontSize(ForgeSettings.int("font_size", 13).coerceIn(8, 32) * 12.5f / 13f)
        ed.setMinimapVisible(ForgeSettings.bool("minimap", true))
    }

    /** The settings changed: every open editor follows. */
    fun settingsChanged() { for (d in docs.values) for (t in d.tabs) applyEditorSettings(t.editor) }

    // ---------------------------------------------------------------- git
    fun scheduleGit(ms: Long = 800) { Api.main.removeCallbacks(gitTask); Api.main.postDelayed(gitTask, ms) }

    /** git status of the folder: Explorer colors and letters, the status bar's branch, the gutter bars of open files. */
    private fun loadGit() {
        Api.async("git-status", { Api.get("/api/git") }) { r, _ ->
            if (r == null) return@async
            gitRepo = r.optBoolean("repo")
            gitStatus.clear()
            val files = r.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                var p = f.optString("path")
                if (p.contains(" -> ")) p = p.substringAfter(" -> ")
                gitStatus[p.trim('"')] = f.optString("status")
            }
            host.onGit(if (gitRepo) r.optString("branch") else "", r.optString("summary"), gitStatus.size)
            treeAdapter.notifyDataSetChanged()
            // A commit or a checkout changes what HEAD holds; the gutter bars are worked out again.
            gitHead.clear()
            for (d in docs.values) scheduleGitMarks(d.path, 0)
        }
    }

    private fun scheduleGitMarks(path: String, ms: Long = 600) {
        if (!gitRepo) return
        val task = gitMarkTasks.getOrPut(path) { Runnable { gitMarks(path) } }
        Api.main.removeCallbacks(task); Api.main.postDelayed(task, ms)
    }

    /** The lines that differ from the last commit: added (green), modified (blue), deleted (a red mark). */
    private fun gitMarks(path: String) {
        val d = docs[path] ?: return
        val st = gitStatus[path]
        if (!gitRepo || st == "??" || st == "A" || st == "D") { for (t in d.tabs) t.editor.setGitMarks(emptyMap()); return }
        val head = gitHead[path]
        if (head == null) {
            Api.async("git-head", { Api.get("/api/git/diff", mapOf("path" to path)) }) { r, _ ->
                if (r == null) return@async
                gitHead[path] = if (r.isNull("original")) "" else r.optString("original")
                if (gitHead[path]!!.isNotEmpty()) gitMarks(path)
            }
            return
        }
        if (head.isEmpty()) return
        val marks = HashMap<Int, Int>()
        val a = Diff.split(head); val b = Diff.split(d.text())
        if (a.size + b.size > 40000) return
        for (h in Diff.lines(a, b)) {
            when {
                h.added == 0 -> marks[h.b0] = 3
                h.removed == 0 -> for (i in h.b0 until h.b1) marks[i] = 1
                else -> for (i in h.b0 until h.b1) marks[i] = 2
            }
        }
        for (t in d.tabs) t.editor.setGitMarks(marks)
    }

    private fun gitColor(path: String, dir: Boolean): Int {
        if (!gitRepo || gitStatus.isEmpty()) return 0
        val st = if (dir) gitStatus.entries.firstOrNull { it.key.startsWith("$path/") }?.value else gitStatus[path]
        return when {
            st == null -> 0
            st == "??" || st.contains('A') -> 0xff73c991.toInt()
            st.contains('D') -> Ui.red
            else -> 0xffe2c08d.toInt()
        }
    }

    // ---------------------------------------------------------------- the debugger's paused line
    private var execPath = ""
    private var execLine = 0

    /** The line the debugger is paused on (a yellow arrow and band in every editor of that file); null clears it. */
    fun showExecution(path: String?, line: Int) {
        execPath = path ?: ""; execLine = line
        for (d in docs.values) for (t in d.tabs) t.editor.showExecutionLine(if (d.path == execPath && line > 0) line else null)
    }

    // ---------------------------------------------------------------- edits from language servers
    /** Applies edits to several files (rename, quick fixes): open files change in their editor, others are opened first. */
    fun applyWorkspaceEdit(edits: Map<String, List<Lsp.TextEdit>>): Int {
        var n = 0
        for ((p, list) in edits) {
            if (list.isEmpty()) continue
            n++
            val d = docs[p]
            if (d != null && d.tabs.isNotEmpty()) { d.tabs[0].editor.applyEdits(list); continue }
            open(p, current, activate = false) { t -> if (t is TextTab) t.editor.applyEdits(list) }
        }
        return n
    }

    // ---------------------------------------------------------------- the Markdown preview
    /** Ctrl Shift V: the live preview next to the active Markdown file, or closes it. */
    fun togglePreview() {
        val t = current.active as? TextTab
        if (t == null || !PreviewPanel.isMarkdown(t.path)) { Ui.toast(ctx, "Open a Markdown file first."); return }
        val p = t.preview
        if (p != null) { t.box.removeView(p.view); t.box.removeViewAt(t.box.childCount - 1); t.preview = null; return }
        val prev = PreviewPanel.MarkdownPreview(ctx, host, t.path) { togglePreview() }
        t.preview = prev
        t.box.addView(Ui.divider(ctx, true))
        t.box.addView(prev.view, Ui.lp(0, Ui.match, 1f))
        prev.update(t.editor.text())
    }

    // ---------------------------------------------------------------- breadcrumbs and the outline
    private fun showCrumbs(g: Group) {
        val t = g.active ?: run { g.crumbScroll.visibility = View.GONE; return }
        g.crumbs.removeAllViews()
        val parts = t.path.split('/').filter { it.isNotEmpty() }
        for ((i, part) in parts.withIndex()) {
            val isFile = i == parts.size - 1
            if (isFile) g.crumbs.addView(ImageView(ctx).also { it.setImageDrawable(FileIcons.file(ctx, part)) }, Ui.lp(dp(14), dp(14)).also { it.rightMargin = dp(4) })
            val c = Ui.text(ctx, part, 11.5f, if (isFile) Ui.fg2 else Ui.fg3)
            val prefix = parts.take(i + 1).joinToString("/")
            c.setOnClickListener { revealInTree(prefix) }
            g.crumbs.addView(c)
            if (!isFile) g.crumbs.addView(Ui.iconView(ctx, R.drawable.ic_chevron_right, Ui.fg3, 12), Ui.lp(dp(12), dp(12)).also { it.leftMargin = dp(3); it.rightMargin = dp(3) })
        }
        val tt = t as? TextTab
        if (tt != null && symbolsFor == t.path && symbols.isNotEmpty()) {
            val (line, _) = tt.editor.cursorLineCol()
            var level = symbols
            while (true) {
                val s = level.lastOrNull { line >= it.line && line <= it.endLine } ?: break
                g.crumbs.addView(Ui.iconView(ctx, R.drawable.ic_chevron_right, Ui.fg3, 12), Ui.lp(dp(12), dp(12)).also { it.leftMargin = dp(3); it.rightMargin = dp(3) })
                val (letter, color) = symbolBadge(s.kind)
                val badge = Ui.text(ctx, letter, 10f, color, mono = true, bold = true)
                val name = Ui.text(ctx, s.name, 11.5f, Ui.fg2)
                val r = Ui.row(ctx, badge, name)
                name.setPadding(dp(3), 0, 0, 0)
                r.setOnClickListener { tt.editor.goTo(s.line, s.col) }
                g.crumbs.addView(r)
                level = s.children
            }
        }
        g.crumbScroll.visibility = View.VISIBLE
    }

    /** The cursor moved in an editor: the breadcrumbs and the outline follow it (called by EditorSmarts). */
    fun cursorMoved(editor: CodeEditor) {
        val t = current.active as? TextTab ?: return
        if (t.editor !== editor) return
        Api.main.removeCallbacks(crumbTask); Api.main.postDelayed(crumbTask, 200)
        host.onEditorState(editor, t.path)
    }
    private val crumbTask = Runnable { showCrumbs(current); outlineAdapter.notifyDataSetChanged() }

    private fun symbolBadge(kind: Int): Pair<String, Int> = when (kind) {
        5, 11, 23 -> "C" to 0xffffcb6b.toInt()
        6, 9, 12 -> "ƒ" to 0xffc792ea.toInt()
        7, 8 -> "p" to 0xff82aaff.toInt()
        10, 22 -> "E" to 0xffffcb6b.toInt()
        13, 14 -> "v" to 0xff82aaff.toInt()
        2, 3, 4 -> "M" to 0xfff78c6c.toInt()
        else -> "·" to Ui.fg3
    }

    private fun scheduleSymbols(delay: Long = 1500) { Api.main.removeCallbacks(symbolsTask); Api.main.postDelayed(symbolsTask, delay) }

    private fun loadSymbols() {
        val t = current.active as? TextTab ?: return
        val client = Lsp.clientFor(t.path)
        if (client == null || !client.has("documentSymbolProvider")) {
            if (client != null && !client.ready) { scheduleSymbols(2000); return }
            symbols = emptyList(); symbolsFor = t.path; showOutline(); return
        }
        val p = t.path
        client.documentSymbols(p) { list, _ ->
            if ((current.active as? TextTab)?.path != p) return@documentSymbols
            symbols = list; symbolsFor = p
            showOutline(); showCrumbs(current)
        }
    }

    /** The active file's symbols for the Ctrl Shift O picker. */
    fun currentSymbols(): List<Lsp.Symbol> = if (symbolsFor == activePath()) symbols else emptyList()

    /** Ctrl Shift O: a filterable list of the active file's symbols. */
    fun symbolPicker() { host.openPalette("@") }

    private fun showOutline() {
        outlineChevron.setImageDrawable(Ui.icon(ctx, if (outlineOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, Ui.fg3))
        outlineList.visibility = if (outlineOpen) View.VISIBLE else View.GONE
        outlineRows.clear()
        outlineFor = symbolsFor
        fun walk(list: List<Lsp.Symbol>, depth: Int) { for (s in list) { outlineRows.add(s to depth); if (depth < 6) walk(s.children, depth + 1) } }
        walk(symbols, 0)
        outlineAdapter.notifyDataSetChanged()
    }

    private inner class OutlineAdapter : BaseAdapter() {
        override fun getCount(): Int = if (outlineRows.isEmpty()) 1 else outlineRows.size
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()
        override fun isEnabled(position: Int): Boolean = outlineRows.isNotEmpty()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (outlineRows.isEmpty()) {
                val t = Ui.text(ctx, if (activePath().isEmpty()) "Open a file to see its outline." else "No symbols from a language server for this file.", 11.5f, Ui.fg3)
                t.setPadding(dp(16), dp(6), dp(8), dp(6))
                return t
            }
            val (s, depth) = outlineRows[position]
            val row = (convertView as? LinearLayout)?.takeIf { it.childCount == 3 } ?: Ui.row(ctx, Ui.text(ctx, "", 10f, Ui.fg3, mono = true, bold = true),
                Ui.text(ctx, "", 12f, Ui.fg2).also { it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END },
                Ui.text(ctx, "", 10.5f, Ui.fg3).also { it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END })
            val (letter, color) = symbolBadge(s.kind)
            (row.getChildAt(0) as TextView).also { it.text = letter; it.setTextColor(color); it.layoutParams = Ui.lp(dp(14), Ui.wrap) }
            (row.getChildAt(1) as TextView).also { it.text = s.name; it.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { lp -> lp.leftMargin = dp(4) } }
            (row.getChildAt(2) as TextView).also { it.text = s.detail; it.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { lp -> lp.leftMargin = dp(6) } }
            row.setPadding(dp(14) + depth * dp(12), dp(2), dp(8), dp(2))
            val ed = activeEditor()
            val line = ed?.cursorLineCol()?.first ?: -1
            val inside = line >= s.line && line <= s.endLine && (position + 1 >= outlineRows.size || !(line >= outlineRows[position + 1].first.line && line <= outlineRows[position + 1].first.endLine && outlineRows[position + 1].second > depth))
            row.background = if (inside) ColorDrawable(accentSoft) else null
            return row
        }
    }

    // ---------------------------------------------------------------- reviews
    private val prefs get() = ctx.getSharedPreferences("forge-review", Context.MODE_PRIVATE)
    private fun prefKey() = "review:" + Api.folder

    private fun persistReviews() {
        val o = JSONObject()
        for ((p, r) in reviews) if (!r.inline && r.runId.isNotEmpty()) o.put(p, r.runId)
        prefs.edit().putString(prefKey(), o.toString()).apply()
    }

    /** Files to review: the agent reviews of this folder (Ctrl K reviews end with their editor). */
    fun pendingReviews(): List<String> = reviews.filterValues { !it.inline }.keys.toList()

    /** Starts (or restarts) a review of a file against [base]; the editor shows the inline diff. */
    fun startReview(path: String, base: String, label: String, inline: Boolean, runId: String = "") {
        val existing = reviews[path]
        // An agent review keeps its first base, so several edits in a row show as one review.
        if (existing != null && !existing.inline && !inline) { renderReview(path); return }
        reviews[path] = Review(base, runId, label, inline)
        if (!inline) persistReviews()
        renderReview(path)
        treeAdapter.notifyDataSetChanged()
        for (g in groups) renderTabs(g)
        host.onReviewsChanged(pendingReviews().size)
    }

    /** The agent changed a file (the chat's file_changed event): review it against the version before the run. */
    fun agentChanged(runId: String, path: String) {
        if (reviews[path]?.inline == false) { fileChanged(path); return }
        Api.async("change-base", { Api.get("/api/changes", mapOf("run_id" to runId)) }) { r, _ ->
            val arr = r?.optJSONArray("changes")
            var base: String? = null; var kind = ""
            if (arr != null) for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); if (c.optString("path") == path) { base = if (c.isNull("before")) "" else c.optString("before"); kind = c.optString("kind") } }
            fileChanged(path)
            if (base == null || kind == "deleted" || kind == "reverted") return@async
            startReview(path, base!!, "from the agent", inline = false, runId = runId)
        }
    }

    /** Opens the files of a run with their reviews (the chat's Review button). */
    fun reviewRun(runId: String) {
        Api.async("changes", { Api.get("/api/changes", mapOf("run_id" to runId)) }) { r, e ->
            val arr = r?.optJSONArray("changes")
            if (arr == null) { Ui.toast(ctx, "Cannot load the changes: " + (e?.message ?: "")); return@async }
            var first: String? = null
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val kind = c.optString("kind")
                if (kind == "deleted" || kind == "reverted") continue
                val p = c.optString("path")
                if (!reviews.containsKey(p)) reviews[p] = Review(if (c.isNull("before")) "" else c.optString("before"), runId, "from the agent", false)
                if (first == null) first = p
            }
            persistReviews()
            host.onReviewsChanged(pendingReviews().size)
            if (first != null) openFile(first) else Ui.toast(ctx, "This reply changed no files that can be reviewed.")
        }
    }

    /** Opens the next file that waits for review (the status bar item). */
    fun nextReview() {
        val list = pendingReviews()
        if (list.isEmpty()) { Ui.toast(ctx, "No changes wait for review."); return }
        openFile(list.firstOrNull { it != activePath() } ?: list[0])
    }

    /** Keeps every pending agent change in every file. */
    fun keepAllReviews() {
        for (p in pendingReviews()) finishReview(p)
        Ui.toast(ctx, "Kept every pending change.")
    }

    /** After the window opens: the reviews of this folder come back with their base text from the server. */
    fun restoreReviews() {
        val saved = try { JSONObject(prefs.getString(prefKey(), "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val keys = saved.keys().asSequence().toList()
        for (p in keys) {
            val runId = saved.optString(p)
            Api.async("review-base", { Api.get("/api/changes", mapOf("run_id" to runId)) }) { r, _ ->
                val arr = r?.optJSONArray("changes") ?: return@async
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    if (c.optString("path") != p || c.optString("kind") == "deleted" || c.optString("kind") == "reverted") continue
                    if (!reviews.containsKey(p)) reviews[p] = Review(if (c.isNull("before")) "" else c.optString("before"), runId, "from the agent", false)
                    renderReview(p)
                    host.onReviewsChanged(pendingReviews().size)
                    treeAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun scheduleReview(path: String) {
        if (!reviews.containsKey(path)) return
        val task = reviewTasks.getOrPut(path) { Runnable { renderReview(path) } }
        Api.main.removeCallbacks(task); Api.main.postDelayed(task, 250)
    }

    private fun hunksFor(base: List<String>, now: List<String>): List<Pair<Diff.Hunk, CodeEditor.ReviewHunk>> =
        Diff.lines(base, now).map { h -> h to CodeEditor.ReviewHunk(h.b0, h.b1, base.subList(h.a0, h.a1)) }

    private fun renderReview(path: String) {
        val d = docs[path] ?: return
        val r = reviews[path]
        if (r == null) { for (t in d.tabs) t.editor.clearReview(); return }
        val base = Diff.split(r.base)
        val now = Diff.split(d.text())
        val hunks = hunksFor(base, now)
        if (hunks.isEmpty()) { finishReview(path); return }
        for (t in d.tabs) t.editor.setReview(hunks.map { it.second }, r.label)
    }

    /** Ends a file's review without changing its text (its changes were undone elsewhere). */
    fun endReview(path: String) = finishReview(path)

    private fun finishReview(path: String) {
        val r = reviews.remove(path) ?: return
        if (!r.inline) persistReviews()
        docs[path]?.let { d -> for (t in d.tabs) t.editor.clearReview() }
        treeAdapter.notifyDataSetChanged()
        for (g in groups) renderTabs(g)
        host.onReviewsChanged(pendingReviews().size)
    }

    private fun reviewHunk(path: String, ed: CodeEditor, i: Int, keep: Boolean) {
        val r = reviews[path] ?: return
        val d = docs[path] ?: return
        val base = Diff.split(r.base)
        val now = Diff.split(d.text())
        val hunks = Diff.lines(base, now)
        val h = hunks.getOrNull(i) ?: return
        if (keep) {
            r.base = Diff.keepHunk(base, now, h).joinToString("\n")
            renderReview(path)
            return
        }
        val target = Diff.undoHunk(base, now, h).joinToString("\n")
        replaceMinimal(ed, target)
        renderReview(path)
        // An agent change is on disk: undoing part of it saves the file, like the old page did.
        if (!r.inline) saveDoc(d) { }
    }

    private fun reviewAll(path: String, keep: Boolean) {
        val r = reviews[path] ?: return
        val d = docs[path] ?: return
        if (keep) { finishReview(path); host.flashStatus("Kept the changes in $path"); return }
        replaceMinimal(d.tabs[0].editor, r.base)
        finishReview(path)
        if (!r.inline) saveDoc(d) { }
        host.flashStatus("Undid the changes in $path. Ctrl Z brings them back.")
    }

    /** Replaces the editor's text with [target] as one undo step that touches only the part that differs. */
    private fun replaceMinimal(ed: CodeEditor, target: String) {
        val cur = ed.text()
        var a = 0
        while (a < cur.length && a < target.length && cur[a] == target[a]) a++
        var b = 0
        while (b < cur.length - a && b < target.length - a && cur[cur.length - 1 - b] == target[target.length - 1 - b]) b++
        ed.replaceRange(a, cur.length - b, target.substring(a, target.length - b))
    }

    // ---------------------------------------------------------------- quick open and search (now in the palette and the Search view)
    fun quickOpen() { host.openPalette("") }
    fun findInFiles() { host.showSearch(query = selectionText().takeIf { !it.contains('\n') } ?: "") }

    /** Files the language servers report problems in, with their counts, for the status bar. */
    fun problemTotals(): Pair<Int, Int> {
        var e = 0; var w = 0
        for (list in Lsp.diagnostics.values) for (d in list) { if (d.severity <= 1) e++ else if (d.severity == 2) w++ }
        return e to w
    }

}
