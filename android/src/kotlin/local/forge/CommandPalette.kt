package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * The palette at the top of the window, the port of the old page's palette (app.js) with Cursor's prefixes:
 *  - nothing: files of the folder (recently opened first), plus commands whose names match the words;
 *  - ">": every command (Ctrl Shift P);
 *  - ":": go to a line, ":120" or ":120:8";
 *  - "@": the symbols of the active file (Ctrl Shift O);
 *  - "#": symbols of the whole project from the language server (Ctrl T);
 *  - "/" or "~": a path anywhere on this computer, opened as a file, a folder or a new window;
 *  - "?": this list of prefixes.
 */
class CommandPalette(private val ctx: Context, private val host: MainActivity) {
    class Command(val label: String, val hint: String = "", val run: () -> Unit)
    private class Item(val label: String, val detail: String, val hint: String, val icon: Int, val file: String?, val badge: Pair<String, Int>?, val run: () -> Unit)

    companion object {
        /** Files opened in this window, most recent first. */
        val recent = ArrayList<String>()
        fun touch(path: String) { if (path.isEmpty()) return; recent.remove(path); recent.add(0, path); while (recent.size > 40) recent.removeAt(recent.size - 1) }
        private val recentCommands = ArrayList<String>()
    }

    private fun dp(v: Int) = Ui.dp(ctx, v)

    /** The open palette's window content (for the window's snapshot mode). */
    var lastView: View? = null; private set

    fun open(prefix: String, commands: List<Command>) {
        val input = Ui.input(ctx, "Search files by name. Type > for commands, : for a line, @ for symbols, ? for help")
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        input.background = null
        input.isSingleLine = true
        input.setPadding(dp(14), dp(12), dp(14), dp(12))
        val items = ArrayList<Item>()
        var selected = 0
        var generation = 0
        val list = ListView(ctx)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        lateinit var dialog: AlertDialog
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: newRow()
                val it = items[position]
                val icon = row.getChildAt(0) as ImageView
                val badge = row.getChildAt(1) as TextView
                if (it.file != null) { icon.visibility = View.VISIBLE; badge.visibility = View.GONE; icon.setImageDrawable(FileIcons.file(ctx, it.file.substringAfterLast('/'))) }
                else if (it.badge != null) { icon.visibility = View.GONE; badge.visibility = View.VISIBLE; badge.text = it.badge.first; badge.setTextColor(it.badge.second) }
                else { icon.visibility = View.VISIBLE; badge.visibility = View.GONE; icon.setImageDrawable(Ui.icon(ctx, it.icon, Ui.fg3)) }
                (row.getChildAt(2) as TextView).text = it.label
                (row.getChildAt(3) as TextView).text = it.detail
                (row.getChildAt(4) as TextView).text = it.hint
                row.getChildAt(4).visibility = if (it.hint.isEmpty()) View.GONE else View.VISIBLE
                row.background = if (position == selected) Ui.rounded(0x33ff8a3d, 4) else null
                return row
            }
            private fun newRow(): LinearLayout {
                val icon = ImageView(ctx)
                val badge = Ui.text(ctx, "", 11f, Ui.fg3, mono = true, bold = true)
                badge.gravity = Gravity.CENTER
                val label = Ui.text(ctx, "", 13f, Ui.fg)
                label.isSingleLine = true; label.ellipsize = TextUtils.TruncateAt.END
                val detail = Ui.text(ctx, "", 12f, Ui.fg3)
                detail.isSingleLine = true; detail.ellipsize = TextUtils.TruncateAt.START
                val hint = Ui.kbd(ctx, "")
                val row = Ui.row(ctx, icon, badge, label, detail, hint)
                icon.layoutParams = Ui.lp(dp(16), dp(16)).also { it.rightMargin = dp(10) }
                badge.layoutParams = Ui.lp(dp(16), Ui.wrap).also { it.rightMargin = dp(10) }
                label.layoutParams = Ui.lp(Ui.wrap, Ui.wrap)
                detail.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(10) }
                hint.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(10) }
                row.setPadding(dp(12), dp(6), dp(12), dp(6))
                return row
            }
        }
        list.adapter = adapter

        fun show(newItems: List<Item>) {
            items.clear(); items.addAll(newItems.take(200)); selected = 0
            adapter.notifyDataSetChanged()
            list.setSelection(0)
        }

        fun commandItems(needle: String): List<Item> {
            val n = needle.trim().lowercase()
            val matched = commands.filter { n.isEmpty() || matches(n, it.label.lowercase()) }
            val ordered = if (n.isEmpty()) matched.sortedBy { c -> recentCommands.indexOf(c.label).let { if (it < 0) Int.MAX_VALUE else it } } else matched
            return ordered.map { c -> Item(c.label, "", c.hint, R.drawable.ic_command, null, null) { recentCommands.remove(c.label); recentCommands.add(0, c.label); c.run() } }
        }

        fun update(q: String) {
            val g = ++generation
            when {
                q.startsWith("?") -> show(listOf(
                    Item("Go to a file", "type a name", "", R.drawable.ic_files, null, null) { open("", commands) },
                    Item("> Run a command", "Ctrl Shift P", "", R.drawable.ic_command, null, null) { open(">", commands) },
                    Item(": Go to a line", "Ctrl G", "", R.drawable.ic_goto, null, null) { open(":", commands) },
                    Item("@ Go to a symbol in this file", "Ctrl Shift O", "", R.drawable.ic_outline, null, null) { open("@", commands) },
                    Item("# Go to a symbol in the project", "Ctrl T", "", R.drawable.ic_braces, null, null) { open("#", commands) },
                    Item("/ Open a path anywhere on this computer", "", "", R.drawable.ic_folder, null, null) { open("/", commands) }))
                q.startsWith(">") -> show(commandItems(q.substring(1)))
                q.startsWith(":") -> {
                    val parts = q.substring(1).split(':', ',')
                    val line = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val col = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
                    val ed = host.activeEditor()
                    show(if (line == null || ed == null) listOf(Item(if (ed == null) "Open a file first" else "Type a line number" + " (1 to " + ed.lineCount() + ")", "", "", R.drawable.ic_goto, null, null) { })
                        else listOf(Item("Go to line $line" + (if (col > 1) ", column $col" else ""), "", "", R.drawable.ic_goto, null, null) { ed.goTo(line - 1, col - 1); ed.focus() }))
                }
                q.startsWith("@") -> {
                    val needle = q.substring(1).trim().lowercase()
                    val path = host.activeFile()
                    fun fill(symbols: List<Lsp.Symbol>) {
                        val flat = ArrayList<Pair<Lsp.Symbol, String>>()
                        fun walk(l: List<Lsp.Symbol>, parent: String) { for (s in l) { flat.add(s to parent); walk(s.children, if (parent.isEmpty()) s.name else parent + "." + s.name) } }
                        walk(symbols, "")
                        show(flat.filter { needle.isEmpty() || matches(needle, it.first.name.lowercase()) }.map { (s, parent) ->
                            Item(s.name, if (parent.isNotEmpty()) parent else s.detail, Lsp.symbolKindNames.getOrElse(s.kind) { "" }, 0, null, symbolBadge(s.kind)) { host.openFileAt(path, s.line + 1, s.col) }
                        }.ifEmpty { listOf(Item(if (path.isEmpty()) "Open a file first" else "No symbols", if (path.isEmpty()) "" else "the language server sent no outline for this file", "", R.drawable.ic_outline, null, null) { }) })
                    }
                    val known = host.currentSymbols()
                    if (known.isNotEmpty() || path.isEmpty()) fill(known)
                    else {
                        val client = Lsp.clientFor(path)
                        if (client == null) fill(emptyList()) else client.documentSymbols(path) { l, _ -> if (g == generation) fill(l) }
                    }
                }
                q.startsWith("#") -> {
                    val needle = q.substring(1).trim()
                    val path = host.activeFile()
                    val client = if (path.isNotEmpty()) Lsp.clientFor(path) else null
                    if (client == null || needle.isEmpty()) { show(listOf(Item(if (client == null) "Open a file whose language has a language server" else "Type part of a name", "", "", R.drawable.ic_braces, null, null) { })); return }
                    client.workspaceSymbols(needle) { l, err ->
                        if (g != generation) return@workspaceSymbols
                        show(l.map { s -> Item(s.name, s.path + ":" + (s.line + 1), Lsp.symbolKindNames.getOrElse(s.kind) { "" }, 0, null, symbolBadge(s.kind)) { host.openFileAt(s.path, s.line + 1, s.col) } }
                            .ifEmpty { listOf(Item(err ?: "No symbols found", "", "", R.drawable.ic_braces, null, null) { }) })
                    }
                }
                q.startsWith("/") || q.startsWith("~") -> show(listOf(
                    Item("Open the file $q", "anywhere on this computer", "", R.drawable.ic_file_add, null, null) { host.openFileAt(q, 0, 0) },
                    Item("Open the folder $q in this window", "", "", R.drawable.ic_folder, null, null) { host.goToFolderPath(q) },
                    Item("Open the folder $q in a new window", "", "", R.drawable.ic_new_window, null, null) { host.newWindowAt(q) }))
                else -> {
                    Api.async("files", { Api.get("/api/files", mapOf("q" to q)) }) { r, _ ->
                        if (g != generation) return@async
                        val out = ArrayList<Item>()
                        val arr = r?.optJSONArray("files")
                        val files = ArrayList<String>()
                        if (arr != null) for (i in 0 until minOf(arr.length(), 200)) files.add(arr.getString(i))
                        val ordered = if (q.isEmpty()) (recent.filter { it != host.activeFile() } + files.filter { it !in recent }) else files
                        for (f in ordered) out.add(Item(f.substringAfterLast('/'), if (f.contains('/')) f.substringBeforeLast('/') else "", if (q.isEmpty() && f in recent) "recently opened" else "", 0, f, null) { host.openFileAt(f, 0, 0) })
                        if (q.isNotBlank()) out.addAll(commandItems(q).take(12).map { Item(it.label, "command", it.hint, it.icon, null, null, it.run) })
                        if (out.isEmpty()) out.add(Item(if (q.isEmpty()) "This folder has no files yet" else "No file matches", "Type > to see the commands", "", R.drawable.ic_command, null, null) { open(">", commands) })
                        show(out)
                    }
                }
            }
        }

        val box = Ui.column(ctx, input, Ui.divider(ctx, false), list)
        list.layoutParams = Ui.lp(Ui.match, dp(420))
        box.setBackgroundColor(Ui.bg2)
        dialog = AlertDialog.Builder(ctx).setView(box).create()
        dialog.window?.setBackgroundDrawable(Ui.rounded(Ui.bg2, 8, Ui.border2))
        fun runSelected() { val it = items.getOrNull(selected) ?: return; dialog.dismiss(); Api.main.post { it.run() } }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { update(s.toString()) }
        })
        input.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (selected < items.size - 1) selected++; adapter.notifyDataSetChanged(); list.smoothScrollToPosition(selected); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (selected > 0) selected--; adapter.notifyDataSetChanged(); list.smoothScrollToPosition(selected); true }
                KeyEvent.KEYCODE_PAGE_DOWN -> { selected = minOf(items.size - 1, selected + 10); adapter.notifyDataSetChanged(); list.setSelection(selected); true }
                KeyEvent.KEYCODE_PAGE_UP -> { selected = maxOf(0, selected - 10); adapter.notifyDataSetChanged(); list.setSelection(selected); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { runSelected(); true }
                KeyEvent.KEYCODE_ESCAPE -> { dialog.dismiss(); true }
                else -> false
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> selected = pos; runSelected() }
        dialog.show()
        lastView = dialog.window?.decorView
        dialog.setOnDismissListener { if (lastView === dialog.window?.decorView) lastView = null }
        dialog.window?.let { w ->
            w.setLayout(dp(680), WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            val a = w.attributes; a.y = dp(56); w.attributes = a
        }
        input.setText(prefix)
        input.setSelection(prefix.length)
        if (prefix.isEmpty()) update("")
        input.requestFocus()
    }

    /** Every word of the needle appears in the text (in any order), or the needle's letters appear in order. */
    private fun matches(needle: String, text: String): Boolean {
        val words = needle.split(' ').filter { it.isNotEmpty() }
        if (words.all { text.contains(it) }) return true
        var i = 0
        for (ch in needle.replace(" ", "")) { i = text.indexOf(ch, i); if (i < 0) return false; i++ }
        return needle.length >= 3
    }

    private fun symbolBadge(kind: Int): Pair<String, Int> = when (kind) {
        5, 11, 23 -> "C" to 0xffffcb6b.toInt()
        6, 9, 12 -> "ƒ" to 0xffc792ea.toInt()
        7, 8 -> "p" to 0xff82aaff.toInt()
        10, 22 -> "E" to 0xffffcb6b.toInt()
        13, 14 -> "v" to 0xff82aaff.toInt()
        2, 3, 4 -> "M" to 0xfff78c6c.toInt()
        else -> "·" to Ui.fg3
    }

}
