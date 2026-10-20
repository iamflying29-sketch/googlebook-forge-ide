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
import org.json.JSONArray
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
        /** The file's encoding (Forge's id, see /api/encodings) and line ending, kept when it is saved. */
        var encoding = "utf8"
        var encodingName = "UTF-8"
        var eol = "LF"
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
        override fun renamed(newPath: String) { path = newPath; nb.renameTo(newPath) }
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

    // The language servers of this window's folder send their edits and notes here.
    private var lspFolder = ""
    private val lspApply: (Map<String, List<Lsp.TextEdit>>) -> Boolean = { edits -> applyWorkspaceEdit(edits) > 0 }
    private val lspNotify: (String) -> Unit = { msg -> Ui.toast(ctx, msg) }
    private val lspListener: (String, String, List<Lsp.Diagnostic>) -> Unit = { f, _, _ -> if (f == host.windowFolder()) treeAdapter.notifyDataSetChanged() }

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
            if (e.dir) { selectedPath = e.path; toggle(e) } else openFilePreview(e.path)
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
        layoutGroups()
        view.addView(groupsRow, Ui.lp(0, Ui.match, 1f))
        renderTabs(current)
        showOutline()

        bindLsp()
    }

    /** Registers this window's editor with the language servers of its folder (again whenever the folder changes). */
    fun bindLsp() {
        unbindLsp()
        lspFolder = host.windowFolder()
        Lsp.applyEdit[lspFolder] = lspApply
        Lsp.notify[lspFolder] = lspNotify
        if (!Lsp.listeners.contains(lspListener)) Lsp.listeners.add(lspListener)
    }

    /** The window closes or leaves the folder: its handlers stop receiving that folder's edits and notes. */
    fun unbindLsp(closing: Boolean = false) {
        if (Lsp.applyEdit[lspFolder] === lspApply) Lsp.applyEdit.remove(lspFolder)
        if (Lsp.notify[lspFolder] === lspNotify) Lsp.notify.remove(lspFolder)
        if (closing) Lsp.listeners.remove(lspListener)
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
        for ((p, list) in Lsp.diagnosticsOf(host.windowFolder())) {
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
                4 -> { val rel = relativeOf(e.path); host.copyToClipboard(rel); Ui.toast(ctx, "Copied $rel") }
                6 -> { val full = if (e.path.startsWith("/")) e.path else host.windowFolder().trimEnd('/') + "/" + e.path; host.copyToClipboard(full); Ui.toast(ctx, "Copied $full") }
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

    /**
     * A path typed in the New file or New folder dialog: an absolute path inside a folder added to this window stays
     * absolute (the file goes into that folder), an absolute path inside this window's folder becomes relative, and a
     * leading slash on anything else means this window's folder.
     */
    private fun newPathOf(typed: String): String {
        val t = typed.trim()
        if (!t.startsWith("/")) return t
        val primary = host.windowFolder().trimEnd('/')
        if (primary.isNotEmpty() && t.startsWith("$primary/")) return t.removePrefix("$primary/")
        if (addedRoots().any { t == it || t.startsWith("$it/") }) return t
        return t.trimStart('/')
    }

    /** The folders added to this window (absolute paths). */
    private fun addedRoots(): List<String> = roots.filter { it.root == 2 }.map { it.path.trimEnd('/') }

    /** A path relative to the folder that holds it: this window's folder, or the added folder it is in. */
    private fun relativeOf(path: String): String {
        if (!path.startsWith("/")) return path
        val r = addedRoots().firstOrNull { path.startsWith("$it/") } ?: return path
        return path.removePrefix("$r/")
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
            val p = newPathOf(input.text.toString())
            if (p.isEmpty() || p.endsWith("/")) return@setPositiveButton
            // /api/file/new creates the file only when it does not exist yet; an existing file is never emptied.
            Api.async("newfile", { Api.post("/api/file/new", JSONObject().put("path", p)) }) { r, e ->
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
            val p = newPathOf(input.text.toString()).trimEnd('/')
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
            Lsp.moved(old, d.path, d.text(), host.windowFolder())
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

    /** Up to eight editor groups, side by side or stacked (Shift Alt 0), like Cursor's. */
    private val MAX_GROUPS = 8
    private var stacked = false

    /** Lays the groups out again with a splitter between every two neighbours. */
    private fun layoutGroups() {
        groupsRow.removeAllViews()
        groupsRow.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for ((i, g) in groups.withIndex()) {
            if (i > 0) {
                val left = i - 1
                groupsRow.addView(Chrome.splitter(ctx, vertical = !stacked, onDrag = { d -> resizeGroups(left, d) }))
            }
            (g.view.parent as? ViewGroup)?.removeView(g.view)
            groupsRow.addView(g.view, if (stacked) Ui.lp(Ui.match, 0, 1f) else Ui.lp(0, Ui.match, 1f))
        }
    }

    /** A new, empty group right after [after] (or at the end). */
    private fun addGroup(after: Group? = null): Group? {
        if (groups.size >= MAX_GROUPS) { host.flashStatus("Forge has at most $MAX_GROUPS editor groups."); return null }
        val g = Group()
        val at = if (after == null) groups.size else groups.indexOf(after) + 1
        groups.add(at.coerceIn(0, groups.size), g)
        for (x in groups) (x.view.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
        layoutGroups()
        renderTabs(g)
        return g
    }

    /** Ctrl backslash: the active file opens again in a new group to the right of the focused one (Split Editor). */
    fun toggleSplit() {
        val src = current.active
        if (src is NotebookTab) {
            // A notebook shows in one group at a time (two views would save over each other's cells), so it moves.
            val g = addGroup(current) ?: return
            moveTab(src, g)
            host.flashStatus("A notebook shows in one group at a time, so it moved to the new group.")
            return
        }
        val g = addGroup(current) ?: return
        current = g
        if (src != null) open(src.path, g, activate = true) { t -> if (t is TextTab && src is TextTab) { val (l, c) = src.editor.lineColOf(src.editor.cursorOffset()); t.editor.goTo(l, c) } } else for (x in groups) renderTabs(x)
    }

    /** Shift Alt 0: the groups side by side or one above the other. */
    fun toggleGroupLayout() {
        if (groups.size < 2) { host.flashStatus("Split the editor first (Ctrl \\)."); return }
        stacked = !stacked
        for (x in groups) (x.view.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
        layoutGroups()
    }

    /** Opens a file in the group to the right of the focused one, making that group if there is none. */
    /** Ctrl K V: the live preview next to the active Markdown file; it stays open when it already is. */
    fun openPreview() { val t = current.active as? TextTab; if (t?.preview != null) return; togglePreview(t) }

    /** Opens a file at a place in the group to the right, made when there is none (Open Definition to the Side). */
    fun openToSideAt(path: String, line: Int, col: Int) {
        val i = groups.indexOf(current)
        val target = groups.getOrNull(i + 1) ?: addGroup(current) ?: return
        current = target
        open(path, target, activate = true) { t -> if (t is TextTab) t.editor.goTo(line, col) }
        for (x in groups) renderTabs(x)
    }

    fun openToSide(path: String) {
        val i = groups.indexOf(current)
        val target = groups.getOrNull(i + 1) ?: addGroup(current) ?: return
        current = target
        open(path, target, activate = true) { }
        for (x in groups) renderTabs(x)
    }

    /** A splitter between group [left] and the next one moved by [delta] pixels. */
    private fun resizeGroups(left: Int, delta: Int) {
        val a = groups.getOrNull(left) ?: return
        val b = groups.getOrNull(left + 1) ?: return
        val la = a.view.layoutParams as LinearLayout.LayoutParams
        val lb = b.view.layoutParams as LinearLayout.LayoutParams
        val sa = if (stacked) a.view.height else a.view.width
        val sb = if (stacked) b.view.height else b.view.width
        val total = sa + sb
        if (total <= 0) return
        val na = (sa + delta).coerceIn(dp(120), total - dp(120))
        val pair = la.weight + lb.weight
        la.weight = pair * na / total; lb.weight = pair - la.weight
        a.view.layoutParams = la; b.view.layoutParams = lb
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

    /** Closes a group and its tabs. The group with the welcome screen always stays: when it is the one closed, the
     *  tabs of its neighbour move into it and the neighbour goes instead. */
    private fun closeGroupNow(g: Group) {
        for (t in g.tabs.toList()) { disposeTab(t); g.frame.removeView(t.content); previewTabs.remove(t); mru.remove(t) }
        g.tabs.clear(); g.active = null
        if (groups.size < 2) { renderTabs(g); return }
        val idx = groups.indexOf(g)
        var keep: Group
        if (g.welcome != null) {
            val other = groups.getOrNull(idx + 1) ?: groups[idx - 1]
            for (t in other.tabs.toList()) { other.frame.removeView(t.content); t.group = g; g.tabs.add(t) }
            other.tabs.clear()
            g.active = null
            groups.remove(other)
            keep = g
        } else {
            groups.remove(g)
            keep = groups[(idx - 1).coerceAtLeast(0)]
        }
        for (x in groups) (x.view.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
        layoutGroups()
        current = keep
        val next = keep.active ?: keep.tabs.firstOrNull()
        if (next != null && keep.active == null) activate(next) else { for (x in groups) renderTabs(x); keep.active?.let { onActiveChanged(it); it.focus() } }
    }

    private fun groupMenu(g: Group, anchor: View) {
        val menu = PopupMenu(ctx, anchor)
        val t = g.active
        if (t is TextTab && PreviewPanel.isMarkdown(t.path)) menu.menu.add(0, 1, 0, if (t.preview == null) "Open the Markdown preview (Ctrl Shift V)" else "Close the Markdown preview")
        // An Untitled file is in no folder yet, so it can be neither revealed nor attached by its path.
        if (t != null && !isUntitled(t.path)) {
            menu.menu.add(0, 2, 1, "Reveal in the Explorer")
            menu.menu.add(0, 3, 2, "Add this file to the chat")
        }
        menu.menu.add(0, 4, 3, "Close saved tabs")
        menu.menu.add(0, 5, 4, "Close all tabs")
        if (groups.size > 1) menu.menu.add(0, 6, 5, "Close this group")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> togglePreview(t)
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


    // ---------------------------------------------------------------- Cursor's editor commands: groups, tabs and files
    /** Paths of closed tabs, newest last, for Ctrl Shift T. */
    private val closedPaths = ArrayList<String>()
    private var untitledCounter = 0
    /** Tabs in the order they were last active, newest last (Ctrl Tab goes back through them). */
    private val mru = ArrayList<Tab>()
    private var mruStep = 0
    private var mruTime = 0L

    fun isUntitled(path: String): Boolean = path.startsWith("untitled:")
    fun displayName(path: String): String = if (isUntitled(path)) "Untitled-" + path.removePrefix("untitled:") else path.substringAfterLast('/')

    fun groupCount(): Int = groups.size

    /** Ctrl 1 to Ctrl 8: that editor group; a group that does not exist yet is made by splitting the last one (as in
     *  Cursor, the active file opens in it). */
    fun focusGroupAt(i: Int) {
        val n = i.coerceIn(0, MAX_GROUPS - 1)
        if (n >= groups.size) {
            current = groups.last()
            while (groups.size <= n) { val before = groups.size; toggleSplit(); if (groups.size == before) break }
            return
        }
        val g = groups[n]
        focusGroup(g); g.active?.focus()
    }

    /** Ctrl K Ctrl Left and Right: the group to the left or right. */
    fun focusNextGroup(dir: Int) {
        if (groups.size < 2) return
        val i = groups.indexOf(current)
        focusGroupAt(((i + dir) % groups.size + groups.size) % groups.size)
    }

    /** Ctrl Shift Page Up and Down: the active tab moves left or right in its group. */
    fun moveTab(dir: Int) {
        val g = current; val t = g.active ?: return
        val i = g.tabs.indexOf(t); val j = (i + dir).coerceIn(0, g.tabs.size - 1)
        if (i == j) return
        g.tabs.removeAt(i); g.tabs.add(j, t); renderTabs(g)
    }

    /** Ctrl Alt Right and Left: the active tab moves to the next or previous group (a new one on the right if needed). */
    fun moveTabToGroup(dir: Int) {
        val t = current.active ?: return
        val i = groups.indexOf(current) + dir
        if (i < 0) return
        val target = if (i >= groups.size) (addGroup(current) ?: return) else groups[i]
        moveTab(t, target)
    }

    private fun isNotebook(path: String) = path.endsWith(".ipynb", ignoreCase = true)

    /**
     * Moves a tab itself into another group: the same editor, notebook or picture, so unsaved changes, the cursor and
     * a notebook's outputs go with it. An emptied group closes, as in Cursor.
     */
    private fun moveTab(t: Tab, target: Group) {
        val g = t.group
        if (g === target) return
        val idx = g.tabs.indexOf(t)
        g.tabs.remove(t)
        g.frame.removeView(t.content)
        if (g.active === t) g.active = null
        t.group = target
        target.tabs.add(t)
        activate(t)
        if (g.tabs.isEmpty() && groups.size > 1) closeGroupNow(g)
        else if (g.active == null) g.tabs.getOrNull(idx.coerceAtMost(g.tabs.size - 1))?.let { n -> val keep = current; activate(n); keep.active?.let { activate(it) } }
        for (x in groups) renderTabs(x)
        scheduleStateSave()
    }

    /** Ctrl K Left and Right: the focused group changes places with its neighbour on that side. */
    fun swapGroups(dir: Int = 1) {
        val i = groups.indexOf(current); val j = i + dir
        if (i < 0 || j < 0 || j >= groups.size) return
        val g = groups[i]; groups[i] = groups[j]; groups[j] = g
        layoutGroups()
        for (x in groups) renderTabs(x)
    }

    /** Ctrl K W: closes the tabs of the focused group (and the group itself when there are two). */
    fun closeGroupTabs() { if (groups.size > 1) closeGroup(current) else for (x in current.tabs.toList()) close(x) }
    /** Ctrl K Ctrl W: closes every tab of every group. */
    fun closeAllTabs() { for (g in groups.toList()) for (x in g.tabs.toList()) close(x) }
    /** Ctrl K U: closes the saved tabs of the focused group. */
    fun closeSavedTabs() { for (x in current.tabs.toList()) if (!x.dirty) closeNow(x) }
    fun closeOtherTabs() { val t = current.active ?: return; for (x in current.tabs.toList()) if (x !== t) close(x) }
    fun closeTabsToTheRight() { val t = current.active ?: return; val i = current.tabs.indexOf(t); for (x in current.tabs.toList().drop(i + 1)) close(x) }
    fun hasOpenTabs(): Boolean = groups.any { it.tabs.isNotEmpty() }

    /** Ctrl Shift T: opens the tab closed last again. */
    fun reopenClosed() {
        val p = closedPaths.removeLastOrNull() ?: run { host.flashStatus("No closed tab to open again."); return }
        openFile(p)
    }

    /** Alt 1 to 9: the tab at that place in the focused group (Alt 0 is the last one). */
    fun openTabAt(n: Int) {
        val tabs = current.tabs
        if (tabs.isEmpty()) return
        val t = if (n <= 0) tabs.last() else tabs.getOrNull(n - 1) ?: return
        activate(t)
    }

    /** Ctrl Tab and Ctrl Shift Tab: the tabs in the order they were last used, like Cursor's editor history. */
    fun cycleRecent(dir: Int) {
        val now = System.currentTimeMillis()
        val live = mru.filter { x -> groups.any { g -> x in g.tabs } }
        if (live.size < 2) { cycleTab(dir); return }
        mruStep = if (now - mruTime < 1500) mruStep + dir else dir
        mruTime = now
        val order = live.reversed()
        val idx = ((mruStep % order.size) + order.size) % order.size
        val t = order[if (idx == 0 && dir > 0) 1 else idx]
        holdMru = true
        try { activate(t) } finally { holdMru = false }
    }
    private var holdMru = false

    private fun noteActive(t: Tab) {
        if (holdMru) return
        mru.remove(t); mru.add(t)
        if (mru.size > 60) mru.removeAt(0)
    }

    /** Ctrl K P: the active file's full path; Ctrl K Ctrl Shift C: its path inside the folder. */
    fun copyActivePath(relative: Boolean) {
        val p = activePath()
        if (p.isEmpty() || isUntitled(p)) { Ui.toast(ctx, "Open a saved file first."); return }
        val abs = if (p.startsWith("/")) p else host.windowFolder().trimEnd('/') + "/" + p
        val out = if (relative) relativeOf(abs.removePrefix(host.windowFolder().trimEnd('/') + "/")) else abs
        host.copyToClipboard(out)
        host.flashStatus("Copied $out")
    }

    /** Ctrl K R: shows the active file in the Explorer. */
    fun revealActive() {
        val p = activePath()
        if (p.isEmpty() || isUntitled(p)) return
        host.showExplorer()
        revealInTree(p)
    }

    /** Ctrl N: a new Untitled file, saved under a name the first time it is saved. */
    fun newUntitled() {
        untitledCounter++
        val path = "untitled:$untitledCounter"
        val d = Doc(path)
        docs[path] = d
        val t = makeTextTab(current, d, "")
        addTab(t, true)
    }

    /** Ctrl Shift S: saves the active file under a new name and continues in that file. */
    fun saveAsActive() {
        val t = current.active as? TextTab ?: run { Ui.toast(ctx, "Open a text file first."); return }
        saveAsDoc(t.doc) { }
    }

    private fun saveAsDoc(d: Doc, done: () -> Unit) {
        val old = d.path
        val input = Ui.input(ctx, "Path in this folder, for example src/notes.txt", mono = true)
        input.setText(if (isUntitled(old)) "" else old)
        input.setSelection(input.text.length)
        val dialog = AlertDialog.Builder(ctx).setTitle("Save as").setView(input)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target = newPathOf(input.text.toString())
                if (target.isEmpty()) return@setOnClickListener
                val text = d.text()
                fun save(replace: Boolean) {
                Api.async("save-as", { Api.post("/api/file", JSONObject().put("path", target).put("content", text).put("encoding", d.encoding).put("eol", d.eol).put("if_absent", !replace && target != old)) }) { r, e ->
                    if (r == null && (e as? Api.ApiError)?.code == 409) {
                        // The name is taken: replace it only after a yes, as Cursor asks.
                        AlertDialog.Builder(ctx).setTitle("Replace $target?").setMessage("$target already exists. Replacing it overwrites its contents.")
                            .setPositiveButton("Replace") { _, _ -> save(true) }.setNegativeButton("Cancel", null).show()
                        return@async
                    }
                    if (r == null) { Ui.toast(ctx, "Not saved: " + (e?.message ?: "")); return@async }
                    dialog.dismiss()
                    host.flashStatus("Saved $target")
                    if (isUntitled(old)) {
                        // The Untitled file becomes the saved one, in the same tab.
                        docs.remove(old); d.path = target; docs[target] = d
                        d.dirty = false; d.mtime = r.optDouble("mtime", 0.0)
                        for (x in d.tabs) { x.renamed(target); x.editor.lang = Highlighter.langFor(target) }
                        Lsp.open(target, text, host.windowFolder())
                        for (g in groups) renderTabs(g)
                        current.active?.let { onActiveChanged(it) }
                    } else {
                        // Save As of a saved file: its tab is replaced by the new file, and the old file stays as it is on disk.
                        val tabsOf = d.tabs.toList()
                        val g = tabsOf.firstOrNull()?.group ?: current
                        d.dirty = false
                        open(target, g, activate = true) { }
                        for (x in tabsOf) closeNow(x)
                    }
                    refresh()
                    done()
                }
                }
                save(false)
            }
        }
        dialog.show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- preview tabs (a single click opens a file for a look)
    private val previewTabs = HashSet<Tab>()

    /** A single click in the Explorer opens a preview tab (its name in italics) that the next preview replaces, as in
     *  Cursor. Editing the file, a double click on the tab or Ctrl K Enter keeps it open. */
    fun openFilePreview(path: String) {
        if (!ForgeSettings.bool("preview_tabs", true)) { openFile(path); return }
        val g = current
        val existing = g.tabs.firstOrNull { it.path == path }
        if (existing != null) { activate(existing); return }
        val old = g.tabs.firstOrNull { it in previewTabs && !it.dirty }
        open(path, g, activate = true) { t ->
            if (old != null && old !== t) closeNow(old)
            previewTabs.add(t); renderTabs(g)
        }
    }

    fun keepOpen(t: Tab? = current.active) { if (t != null && previewTabs.remove(t)) renderTabs(t.group) }
    fun isPreviewTab(t: Tab): Boolean = t in previewTabs

    // ---------------------------------------------------------------- back and forward (Ctrl Alt minus, Ctrl Shift minus)
    private class Spot(val path: String, val offset: Int)
    private val backStack = ArrayList<Spot>()
    private val forwardStack = ArrayList<Spot>()
    private var lastSpot: Spot? = null
    private var navigating = false
    private var lastEdit: Spot? = null

    /** Called when the cursor moves: a jump of more than 10 lines or to another file is a place to come back to. */
    private fun notePosition(path: String, ed: CodeEditor) {
        if (navigating) { lastSpot = Spot(path, ed.cursorOffset()); return }
        val off = ed.cursorOffset()
        val prev = lastSpot
        if (prev != null && (prev.path != path || Math.abs(ed.logicalLineAt(off) - lineOfSpot(prev, ed)) > 10)) {
            backStack.add(prev); if (backStack.size > 100) backStack.removeAt(0)
            forwardStack.clear()
        }
        lastSpot = Spot(path, off)
    }

    private fun lineOfSpot(s: Spot, ed: CodeEditor): Int = if (s.path == ed.path) ed.logicalLineAt(s.offset.coerceIn(0, ed.text().length)) else -1000

    fun goBack() = travel(backStack, forwardStack)
    fun goForward() = travel(forwardStack, backStack)

    private fun travel(from: ArrayList<Spot>, to: ArrayList<Spot>) {
        val s = from.removeLastOrNull() ?: return
        lastSpot?.let { to.add(it) }
        goToSpot(s)
    }

    private fun goToSpot(s: Spot) {
        navigating = true
        open(s.path, current, activate = true) { t ->
            if (t is TextTab) { val off = s.offset.coerceIn(0, t.editor.text().length); val (l, c) = t.editor.lineColOf(off); t.editor.goTo(l, c) }
            Api.main.postDelayed({ navigating = false }, 300)
        }
    }

    /** Ctrl K Ctrl Q: the place of the last edit. */
    fun goToLastEdit() { val s = lastEdit ?: return; goToSpot(s) }

    fun noteEdit(path: String, ed: CodeEditor) { lastEdit = Spot(path, ed.cursorOffset()) }

    // ---------------------------------------------------------------- open editors (kept per folder, as Cursor does)
    private val statePrefs get() = ctx.getSharedPreferences("forge-editors", Context.MODE_PRIVATE)
    private fun stateKey() = "open:" + host.windowFolder()
    private val stateTask by lazy { Runnable { saveOpenState() } }
    private var restoringState = false

    /** A tab opened, closed or came to the front: the open editors are written down a moment later. */
    private fun scheduleStateSave() { Api.main.removeCallbacks(stateTask); Api.main.postDelayed(stateTask, 1000) }

    /**
     * Writes down the open editors of this window's folder: the groups, their tabs in order, each group's active tab,
     * the cursor of every text tab and the focused group. Reload Window and opening the folder again bring them back.
     * Untitled files are not kept here; hot exit keeps unsaved text.
     */
    fun saveOpenState() {
        Api.main.removeCallbacks(stateTask)
        if (restoringState || host.testWindow || host.windowFolder().isEmpty()) return
        val gs = JSONArray()
        for (g in groups) {
            val tabs = JSONArray()
            for (t in g.tabs) {
                if (isUntitled(t.path)) continue
                val o = JSONObject().put("path", t.path)
                if (t is TextTab) { val (l, c) = t.editor.lineColOf(t.editor.cursorOffset()); o.put("line", l).put("col", c) }
                if (t in previewTabs) o.put("preview", true)
                tabs.put(o)
            }
            gs.put(JSONObject().put("tabs", tabs).put("active", g.active?.path?.takeIf { !isUntitled(it) } ?: ""))
        }
        val o = JSONObject().put("groups", gs).put("focus", groups.indexOf(current)).put("stacked", stacked)
        statePrefs.edit().putString(stateKey(), o.toString()).apply()
    }

    /** Opens the editors written down for this folder, one after the other so the tabs keep their order; a file that
     *  is gone is skipped. [done] runs when all are open. */
    fun restoreOpenState(done: () -> Unit) {
        val o = try { JSONObject(statePrefs.getString(stateKey(), null) ?: "") } catch (e: Exception) { null }
        val gs = o?.optJSONArray("groups")
        if (o == null || gs == null || gs.length() == 0) { done(); return }
        restoringState = true
        if (o.optBoolean("stacked") != stacked && gs.length() > 1) { stacked = o.optBoolean("stacked"); layoutGroups() }
        val steps = ArrayList<Pair<Int, JSONObject>>()
        for (gi in 0 until gs.length()) {
            val g = gs.optJSONObject(gi) ?: continue
            val tabs = g.optJSONArray("tabs") ?: continue
            for (ti in 0 until tabs.length()) tabs.optJSONObject(ti)?.let { steps.add(gi to it) }
        }
        var missing = 0
        fun groupAt(i: Int): Group {
            while (groups.size <= i && groups.size < MAX_GROUPS) { if (addGroup(groups.last()) == null) break }
            return groups.getOrElse(i) { groups.last() }
        }
        fun finish() {
            // Each group shows its active tab again, and the group that had the focus gets it back.
            for (gi in 0 until gs.length()) {
                val want = gs.optJSONObject(gi)?.optString("active").orEmpty()
                val g = groups.getOrNull(gi) ?: continue
                g.tabs.firstOrNull { it.path == want }?.let { activate(it) }
            }
            groups.getOrNull(o.optInt("focus", 0))?.let { g -> g.active?.let { activate(it) } ?: focusGroup(g) }
            // Groups that stayed empty (all their files were gone) close again.
            for (g in groups.toList()) if (g.tabs.isEmpty() && groups.size > 1) closeGroupNow(g)
            restoringState = false
            if (missing > 0) host.flashStatus(if (missing == 1) "One file that was open is gone." else "$missing files that were open are gone.")
            done()
        }
        fun next(i: Int) {
            if (i >= steps.size) { finish(); return }
            val (gi, t) = steps[i]
            val path = t.optString("path")
            if (path.isEmpty()) { next(i + 1); return }
            open(path, groupAt(gi), activate = true, failed = { missing++; next(i + 1) }) { tab ->
                if (tab is TextTab && t.has("line")) tab.editor.goTo(t.optInt("line"), t.optInt("col"))
                if (t.optBoolean("preview")) previewTabs.add(tab)
                next(i + 1)
            }
        }
        next(0)
    }

    // ---------------------------------------------------------------- hot exit (unsaved files survive closing Forge)
    /** Every unsaved text file: path, text, encoding and line ending. */
    fun unsavedDocs(): JSONArray {
        val out = JSONArray()
        for (d in docs.values) if (d.dirty && d.tabs.isNotEmpty()) out.put(JSONObject().put("path", d.path).put("text", d.text()).put("encoding", d.encoding).put("encoding_name", d.encodingName).put("eol", d.eol))
        return out
    }

    /** Opens the files kept by hot exit with their unsaved text (Untitled files come back as Untitled files). */
    fun restoreUnsaved(list: JSONArray) {
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val path = o.optString("path"); val text = o.optString("text")
            if (path.isEmpty()) continue
            val apply = { t: Tab ->
                if (t is TextTab) {
                    t.doc.encoding = o.optString("encoding", t.doc.encoding); t.doc.encodingName = o.optString("encoding_name", t.doc.encodingName); t.doc.eol = o.optString("eol", t.doc.eol)
                    for (x in t.doc.tabs) x.editor.replaceContent(text)
                    t.doc.dirty = true
                    for (g in groups) renderTabs(g)
                }
            }
            if (isUntitled(path)) {
                untitledCounter++
                val p = "untitled:$untitledCounter"
                val d = Doc(p); docs[p] = d
                val t = makeTextTab(current, d, "")
                addTab(t, true); apply(t)
            } else open(path, current, activate = true) { t -> apply(t) }
        }
    }

    // ---------------------------------------------------------------- encodings and line endings
    /** Takes the encoding and line ending the server found in a file; a file that is not valid in its encoding gets
     *  a warning, because saving it would change the bytes shown as replacement characters. */
    private fun readCodec(d: Doc, r: JSONObject, warn: Boolean = true) {
        d.encoding = r.optString("encoding", "utf8").ifEmpty { "utf8" }
        d.encodingName = r.optString("encoding_name", "UTF-8").ifEmpty { "UTF-8" }
        d.eol = r.optString("eol", "LF").ifEmpty { "LF" }
        if (warn && !r.optBoolean("lossless", true)) Ui.toast(ctx, d.path.substringAfterLast('/') + " has bytes that are not valid " + d.encodingName + ". Use Reopen with Encoding to pick the right one before you save.")
        if (current.active.let { it is TextTab && it.doc === d }) (current.active as? TextTab)?.let { host.onEditorState(it.editor, it.path) }
    }

    /** The active text file's encoding name, encoding id and line ending, or null. */
    fun activeCodec(): Triple<String, String, String>? { val t = current.active as? TextTab ?: return null; return Triple(t.doc.encodingName, t.doc.encoding, t.doc.eol) }

    /** Reopen with Encoding: the file is read again from disk in another encoding (unsaved changes are asked about). */
    fun reopenWithEncoding(enc: String, name: String) {
        val t = current.active as? TextTab ?: return
        val d = t.doc
        if (isUntitled(d.path)) { d.encoding = enc; d.encodingName = name; host.onEditorState(t.editor, t.path); return }
        fun go() {
            Api.async("file", { Api.get("/api/file", mapOf("path" to d.path, "encoding" to enc)) }) { r, e ->
                if (r == null) { Ui.toast(ctx, "Not reopened: " + (e?.message ?: "")); return@async }
                readCodec(d, r, warn = false)
                val text = r.optString("content")
                for (x in d.tabs) x.editor.replaceContent(text)
                d.dirty = false; for (g in groups) renderTabs(g)
                Lsp.change(d.path, text, host.windowFolder())
                host.flashStatus("Reopened with " + d.encodingName + (if (!r.optBoolean("lossless", true)) ". Some bytes do not fit this encoding." else "."))
                host.onEditorState(t.editor, t.path)
            }
        }
        if (d.dirty) AlertDialog.Builder(ctx).setTitle("Reopen with " + name + "?").setMessage("The unsaved changes of " + d.path + " are lost when it is read again.")
            .setPositiveButton("Reopen") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
        else go()
    }

    /** Save with Encoding: the file is written in another encoding from now on. */
    fun saveWithEncoding(enc: String, name: String) {
        val t = current.active as? TextTab ?: return
        val old = t.doc.encoding to t.doc.encodingName
        t.doc.encoding = enc; t.doc.encodingName = name
        if (isUntitled(t.doc.path)) { host.onEditorState(t.editor, t.path); return }
        val text = t.doc.text()
        Api.async("save", { Api.post("/api/file", JSONObject().put("path", t.doc.path).put("content", text).put("encoding", enc).put("eol", t.doc.eol)) }) { r, e ->
            if (r == null) {
                t.doc.encoding = old.first; t.doc.encodingName = old.second
                AlertDialog.Builder(ctx).setTitle("Not saved with " + name).setMessage(e?.message ?: "").setPositiveButton("OK", null).show()
            } else {
                t.doc.mtime = r.optDouble("mtime", t.doc.mtime)
                if (t.doc.text() == text) { t.doc.dirty = false; for (g in groups) renderTabs(g) }
                host.flashStatus("Saved " + t.doc.path + " with " + name + ".")
            }
            host.onEditorState(t.editor, t.path)
        }
    }

    /** Change End of Line Sequence: LF or CRLF for the active file; the file counts as changed until it is saved. */
    fun setEol(eol: String) {
        val t = current.active as? TextTab ?: return
        if (t.doc.eol == eol) return
        t.doc.eol = eol
        if (!t.doc.dirty) { t.doc.dirty = true; for (g in groups) renderTabs(g) }
        host.onEditorState(t.editor, t.path)
    }

    // ---------------------------------------------------------------- editor options for every open editor
    fun forEachEditor(f: (CodeEditor) -> Unit) { for (d in docs.values) for (t in d.tabs) f(t.editor) }

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

    /** "{ } JSON" and "Open as JSON" of a notebook: the file as plain text in a text tab. */
    fun openAsText(path: String) = open(path, current, activate = true, asText = true) { }

    private fun open(path: String, g: Group, activate: Boolean, failed: ((String) -> Unit)? = null, asText: Boolean = false, then: (Tab) -> Unit) {
        // A notebook can be open as a notebook and as JSON text at the same time; each open finds its own kind of tab.
        val existing = g.tabs.firstOrNull { it.path == path && (if (asText) it is TextTab else !(it is TextTab && isNotebook(path))) }
        if (existing != null) { if (activate) activate(existing); then(existing); return }
        when {
            isUntitled(path) -> { val d = docs[path] ?: return; val t = makeTextTab(g, d, d.text()); addTab(t, activate); then(t) }
            !asText && PreviewPanel.isImage(path) -> { val t = ImageTab(g, path); addTab(t, activate); then(t) }
            !asText && isNotebook(path) -> { val t = NotebookTab(g, path); t.nb.open(path); addTab(t, activate); then(t) }
            else -> {
                val doc = docs[path]
                if (doc != null && doc.tabs.isNotEmpty()) {
                    val t = makeTextTab(g, doc, doc.text())
                    addTab(t, activate); then(t)
                    return
                }
                Api.async("file", { Api.get("/api/file", mapOf("path" to path)) }) { r, e ->
                    if (r == null) { if (failed != null) failed(e?.message ?: "") else Ui.toast(ctx, "Cannot open " + path + ": " + (e?.message ?: "")); return@async }
                    // The same kind of tab only: a notebook's JSON opens next to its notebook view, not instead of it.
                    val again = g.tabs.firstOrNull { it.path == path && (if (asText) it is TextTab else !(it is TextTab && isNotebook(path))) }
                    if (again != null) { if (activate) activate(again); then(again); return@async }
                    val d = docs[path] ?: Doc(path).also { docs[path] = it }
                    d.mtime = r.optDouble("mtime", 0.0)
                    readCodec(d, r)
                    val t = makeTextTab(g, d, r.optString("content"))
                    if (d.tabs.size == 1) Lsp.open(path, t.editor.text(), host.windowFolder())
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
        ed.onChanged = {
            if (t in previewTabs) previewTabs.remove(t)
            if (!doc.dirty) { doc.dirty = true; for (x in groups) renderTabs(x) }
            noteEdit(doc.path, ed)
            if (!isUntitled(doc.path)) { scheduleReview(doc.path); scheduleSymbols(); scheduleGitMarks(doc.path) }
        }
        // Changes made in one editor of the file appear in its other editor (the split view).
        ed.changeHook = { start, removed, added -> for (o in doc.tabs) if (o !== t) o.editor.mirrorEdit(start, removed, added); if (doc.tabs.size > 1) scheduleReview(doc.path) }
        ed.focusHook = { if (current !== t.group) focusGroup(t.group); if (t.group.active === t) onActiveChanged(t) }
        ed.onReviewHunk = { i, keep -> reviewHunk(doc.path, ed, i, keep) }
        ed.onReviewAll = { keep -> reviewAll(doc.path, keep) }
        if (!isUntitled(doc.path)) scheduleGitMarks(doc.path, 300)
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
        scheduleStateSave()
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
        noteActive(t)
        scheduleStateSave()
        for (x in groups) renderTabs(x)
        onActiveChanged(t)
        t.focus()
    }

    private fun activeTab(): Tab? = current.active

    private fun onActiveChanged(t: Tab) {
        selectedPath = t.path
        treeAdapter.notifyDataSetChanged()
        host.setWindowTitle(displayName(t.path))
        showCrumbs(t.group)
        if (t is TextTab) { host.onEditorState(t.editor, t.path); notePosition(t.path, t.editor); if (!isUntitled(t.path)) scheduleSymbols(0) } else { host.onEditorState(null, t.path); symbols = emptyList(); symbolsFor = ""; showOutline() }
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
        val name = Ui.text(ctx, displayName(t.path), 12.5f, if (isActive) Ui.fg else Ui.fg3)
        if (t in previewTabs) name.setTypeface(name.typeface, android.graphics.Typeface.ITALIC)
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
        var lastClick = 0L
        col.setOnClickListener { val now = System.currentTimeMillis(); if (now - lastClick < 400) keepOpen(t); lastClick = now; activate(t) }
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
        menu.menu.add(0, 6, 5, if (g === groups.last()) (if (t is NotebookTab) "Move to the side" else "Open to the side") else "Move to the next group")
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
                // A notebook moves (one view of it at a time keeps its unsaved cells safe); other files open again there.
                6 -> {
                    val next = if (g === groups.last()) addGroup(g) else groups[groups.indexOf(g) + 1]
                    if (next != null) { if (t is NotebookTab || g !== groups.last()) moveTab(t, next) else { current = next; open(t.path, next, true) { } } }
                }
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
                Lsp.close(t.doc.path, host.windowFolder())
                // An inline (Ctrl K) review cannot outlive the file's last editor; an agent review comes back on reopening.
                if (reviews[t.doc.path]?.inline == true) reviews.remove(t.doc.path)
            }
        }
    }

    private fun closeNow(t: Tab) {
        scheduleStateSave()
        val g = t.group
        val idx = g.tabs.indexOf(t)
        previewTabs.remove(t); mru.remove(t)
        if (!isUntitled(t.path)) { closedPaths.remove(t.path); closedPaths.add(t.path); if (closedPaths.size > 50) closedPaths.removeAt(0) }
        disposeTab(t)
        g.tabs.remove(t)
        g.frame.removeView(t.content)
        if (g.active === t) {
            g.active = null
            val next = g.tabs.getOrNull(idx.coerceAtMost(g.tabs.size - 1)) ?: g.tabs.lastOrNull()
            if (next != null) activate(next)
            // An empty group closes, as in Cursor (the group with the welcome screen takes its neighbour's tabs instead).
            else if (groups.size > 1) closeGroupNow(g)
            else {
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
        if (isUntitled(d.path)) { saveAsDoc(d, done); return }
        val text = d.text()
        val path = d.path
        Api.async("save", { Api.post("/api/file", JSONObject().put("path", path).put("content", text).put("encoding", d.encoding).put("eol", d.eol)) }) { r, e ->
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
                readCodec(d, r, warn = false)
                val text = r.optString("content")
                if (text != d.text()) {
                    for (t in d.tabs) t.editor.replaceContent(text)
                    Lsp.change(path, text, host.windowFolder())
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
        val f = host.windowFolder()
        ed.setFontSize(ForgeSettings.int("font_size", 13, f).coerceIn(8, 32) * 12.5f / 13f)
        ed.setMinimapVisible(ForgeSettings.bool("minimap", true, f))
        val ws = ForgeSettings.bool("render_whitespace", false, f)
        if (ed.renderWhitespace != ws) { ed.renderWhitespace = ws; ed.edit.invalidate() }
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
        // File operations (a rename that moves a class's file, a new file) run first, one after the other, through the
        // server; the text edits follow, so edits to a renamed or created file land in it.
        val ops = (edits as? Lsp.EditMap)?.ops.orEmpty()
        if (ops.isNotEmpty()) {
            runFileOps(ops, 0) { applyTextEdits(edits) }
            return edits.values.count { it.isNotEmpty() } + ops.size
        }
        return applyTextEdits(edits)
    }

    private fun runFileOps(ops: List<Lsp.FileOp>, i: Int, done: () -> Unit) {
        if (i >= ops.size) { refresh(); done(); return }
        val op = ops[i]
        val body = when (op.kind) {
            "create" -> JSONObject().put("path", op.path)
            "rename" -> JSONObject().put("path", op.path).put("new_path", op.newPath)
            else -> JSONObject().put("path", op.path)
        }
        val route = when (op.kind) { "create" -> "/api/file/new"; "rename" -> "/api/file/rename"; else -> "/api/file/trash" }
        Api.async("lsp-file-op", { Api.post(route, body) }) { _, e ->
            if (e != null) Ui.toast(ctx, "The language server asked to " + op.kind + " " + op.path + ", which failed: " + e.message)
            else if (op.kind == "rename") movePaths(op.path, op.newPath, dir = false)
            runFileOps(ops, i + 1, done)
        }
    }

    private fun applyTextEdits(edits: Map<String, List<Lsp.TextEdit>>): Int {
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
    /** Ctrl Shift V: the live preview next to a Markdown file's editor (the active one by default), or closes it. The
     *  preview's own X and the group menus pass their tab, so they act on the group they belong to. */
    fun togglePreview(tab: Tab? = current.active) {
        val t = tab as? TextTab
        if (t == null || !PreviewPanel.isMarkdown(t.path)) { Ui.toast(ctx, "Open a Markdown file first."); return }
        val p = t.preview
        if (p != null) { t.box.removeView(p.view); t.box.removeViewAt(t.box.childCount - 1); t.preview = null; return }
        val prev = PreviewPanel.MarkdownPreview(ctx, host, t.path) { togglePreview(t) }
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
        notePosition(t.path, editor)
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
        val client = Lsp.clientFor(t.path, host.windowFolder())
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
        // The file after the one shown, round the list, so every file with changes is reached in turn.
        val i = list.indexOf(activePath())
        openFile(list[(i + 1) % list.size])
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
            // An agent change: the server keeps the new original, so the kept part stays kept after a reload and in a
            // later Undo or Restore checkpoint.
            if (!r.inline && r.runId.isNotEmpty()) {
                val body = JSONObject().put("run_id", r.runId).put("path", path).put("before", r.base)
                Api.async("keep-hunk", { Api.post("/api/changes/keep", body) }) { _, e -> if (e != null) Ui.toast(ctx, "The kept part is not saved: " + e.message) }
            }
            return
        }
        val target = Diff.undoHunk(base, now, h).joinToString("\n")
        replaceMinimal(ed, target)
        renderReview(path)
        // An agent change is on disk: undoing part of it saves the file, like the old page did.
        if (!r.inline) saveDoc(d) { }
    }

    /** The AI changes view kept part of a change: the editor's review of that file starts from the same original. */
    fun setReviewBase(path: String, base: String) { val r = reviews[path] ?: return; r.base = base; renderReview(path) }

    private fun reviewAll(path: String, keep: Boolean) {
        val r = reviews[path] ?: return
        val d = docs[path] ?: return
        if (keep) { finishReview(path); host.flashStatus("Kept the changes in $path"); return }
        if (!r.inline && r.runId.isNotEmpty()) {
            // An agent change is undone from its checkpoint, as the AI changes view does: a file the agent created goes
            // to the trash (an empty file would stay otherwise), and the checkpoint knows it was undone.
            Api.async("revert", { Api.post("/api/revert", JSONObject().put("run_id", r.runId).put("path", path)) }) { res, e ->
                if (res == null) { Ui.toast(ctx, "Not undone: " + (e?.message ?: "")); return@async }
                finishReview(path)
                Api.async("file", { Api.get("/api/file", mapOf("path" to path)) }) { f, _ ->
                    if (f == null) {
                        // The file is gone (the agent had created it): its tabs close.
                        for (t in d.tabs.toList()) closeNow(t)
                        docs.remove(path)
                        refresh()
                    } else {
                        replaceMinimal(d.tabs[0].editor, f.optString("content"))
                        d.dirty = false; d.mtime = f.optDouble("mtime", d.mtime)
                        for (g in groups) renderTabs(g)
                    }
                    host.flashStatus(res.optString("message").ifEmpty { "Undid the changes in $path." })
                }
            }
            return
        }
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
        for (list in Lsp.diagnosticsOf(host.windowFolder()).values) for (d in list) { if (d.severity <= 1) e++ else if (d.severity == 2) w++ }
        return e to w
    }

}
