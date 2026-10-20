package local.forge

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The chat prompt's @ menu and / menu, the port of the old page's mention menu (chat.js).
 *
 * Typing @ offers context like Cursor: @codebase (the most relevant code), @web (a web search for the message),
 * @git (uncommitted changes), @terminal (the active terminal's output), @linter errors, every indexed @docs site
 * and "add new documentation", earlier chats, notepads, folders, the current file and the files whose names match.
 * Typing / at the start offers the slash commands of /api/commands (built-in ones like /new, /plan, /review and
 * your own). Up and Down move, Enter or Tab picks, Escape closes; the typed @word or /word is removed on a pick.
 */
class ChatMentions(private val ctx: Context, private val host: MainActivity, private val prompt: EditText, private val anchor: View,
                   private val onPick: (Item, Int) -> Unit) {
    class Item(val label: String, val hint: String, val icon: Int, val kind: String, val data: JSONObject = JSONObject())

    private fun dp(v: Int) = Ui.dp(ctx, v)
    private val items = ArrayList<Item>()
    private var selected = 0
    private var start = -1
    private var slash = false
    private var popup: PopupWindow? = null
    private val list = ListView(ctx)
    private var generation = 0
    private var cacheAt = 0L
    private var docs = JSONArray()
    private var chats = JSONArray()
    private var commands = JSONArray()
    private var skills = JSONArray()
    private var notepads = JSONArray()
    private val updateTask = Runnable { update() }

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: newRow()
            val it = items[position]
            val icon = row.getChildAt(0) as ImageView
            if (it.kind == "file") icon.setImageDrawable(FileIcons.file(ctx, it.label.substringAfterLast('/')))
            else icon.setImageDrawable(Ui.icon(ctx, it.icon, when (it.kind) { "command" -> Ui.accent2; "skill" -> 0xffd7b4ff.toInt(); else -> Ui.fg3 }))
            (row.getChildAt(1) as TextView).text = if (it.kind == "file") it.label.substringAfterLast('/') else it.label
            (row.getChildAt(2) as TextView).text = if (it.kind == "file") (if (it.label.contains('/')) it.label.substringBeforeLast('/') else "") + (if (it.hint.isNotEmpty()) "  " + it.hint else "") else it.hint
            row.background = if (position == selected) Ui.rounded(0x33ff8a3d, 4) else null
            return row
        }
        private fun newRow(): LinearLayout {
            val icon = ImageView(ctx)
            val label = Ui.text(ctx, "", 12.5f, Ui.fg)
            label.isSingleLine = true
            val hint = Ui.text(ctx, "", 11.5f, Ui.fg3)
            hint.isSingleLine = true; hint.ellipsize = TextUtils.TruncateAt.END
            val row = Ui.row(ctx, icon, label, hint)
            icon.layoutParams = Ui.lp(dp(15), dp(15)).also { it.rightMargin = dp(8) }
            hint.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(10) }
            row.setPadding(dp(10), dp(5), dp(10), dp(5))
            return row
        }
    }

    init {
        list.adapter = adapter
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setOnItemClickListener { _, _, pos, _ -> selected = pos; pick() }
        prompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { Api.main.removeCallbacks(updateTask); Api.main.postDelayed(updateTask, 60) }
        })
    }

    fun isShowing(): Boolean = popup?.isShowing == true

    /** The prompt's keys while the menu is open; true when the key was used. */
    fun handleKey(code: Int, ev: KeyEvent): Boolean {
        if (!isShowing() || ev.action != KeyEvent.ACTION_DOWN) return isShowing() && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_TAB || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
        when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { selected = (selected + 1) % items.size; adapter.notifyDataSetChanged(); list.smoothScrollToPosition(selected); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { selected = (selected - 1 + items.size) % items.size; adapter.notifyDataSetChanged(); list.smoothScrollToPosition(selected); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> { pick(); return true }
            KeyEvent.KEYCODE_ESCAPE -> { dismiss(); return true }
        }
        return false
    }

    fun dismiss() { popup?.dismiss(); popup = null; start = -1 }

    private fun refreshCache(then: () -> Unit) {
        if (System.currentTimeMillis() - cacheAt < 10000) { then(); return }
        Api.async("mention-cache", {
            listOf(try { Api.get("/api/docs") } catch (e: Exception) { JSONObject() },
                try { Api.get("/api/sessions") } catch (e: Exception) { JSONObject() },
                try { Api.get("/api/commands") } catch (e: Exception) { JSONObject() },
                try { Api.get("/api/notepads") } catch (e: Exception) { JSONObject() })
        }) { r, _ ->
            if (r != null) {
                docs = r[0].optJSONArray("docs") ?: JSONArray()
                chats = r[1].optJSONArray("sessions") ?: JSONArray()
                commands = r[2].optJSONArray("commands") ?: JSONArray()
                skills = r[2].optJSONArray("skills") ?: JSONArray()
                notepads = r[3].optJSONArray("notepads") ?: JSONArray()
                cacheAt = System.currentTimeMillis()
                commandsTried = true
            }
            then()
        }
    }

    /** Forget the cached lists (after a chat or a notepad was added). */
    fun invalidate() { cacheAt = 0 }

    private var commandsTried = false
    /** True once the slash commands were asked for (they may be empty when the server did not answer). */
    fun commandsReady(): Boolean = commandsTried || commands.length() > 0
    fun loadNow(then: () -> Unit) { cacheAt = 0; refreshCache { commandsTried = true; then() } }
    fun command(name: String): JSONObject? {
        for (i in 0 until commands.length()) { val c = commands.optJSONObject(i) ?: continue; if (c.optString("name") == name) return c }
        return null
    }
    fun skill(name: String): JSONObject? {
        for (i in 0 until skills.length()) { val c = skills.optJSONObject(i) ?: continue; if (c.optString("name") == name) return c }
        return null
    }

    private fun update() {
        val text = prompt.text.toString()
        val pos = prompt.selectionStart.coerceIn(0, text.length)
        val before = text.substring(0, pos)
        val g = ++generation
        // "/" commands and skills: at the start of a word (as in Cursor), until a space ends the word.
        val sl = before.lastIndexOf('/')
        if (sl >= 0 && (sl == 0 || before[sl - 1].isWhitespace()) && before.substring(sl + 1).none { it.isWhitespace() || it == '/' }) {
            val q = before.substring(sl + 1).lowercase()
            refreshCache {
                if (g != generation) return@refreshCache
                val out = ArrayList<Item>()
                for (i in 0 until commands.length()) {
                    val c = commands.optJSONObject(i) ?: continue
                    val name = c.optString("name")
                    if (q.isEmpty() || name.startsWith(q) || name.contains(q)) out.add(Item("/" + name, c.optString("description"), R.drawable.ic_command, "command", c))
                }
                for (i in 0 until skills.length()) {
                    val c = skills.optJSONObject(i) ?: continue
                    val name = c.optString("name")
                    if (q.isEmpty() || name.startsWith(q) || name.contains(q))
                        out.add(Item("/" + name, "skill" + (if (c.optBoolean("manual_only")) ", only when you ask" else "") + ": " + c.optString("description").replace('\n', ' '), R.drawable.ic_sparkle, "skill", c))
                }
                slash = true; start = sl
                show(out)
            }
            return
        }
        val at = before.lastIndexOf('@')
        if (at < 0 || (at > 0 && !before[at - 1].isWhitespace()) || before.substring(at + 1).any { it.isWhitespace() && it != ' ' } || before.substring(at + 1).length > 60) { dismiss(); return }
        val typed = before.substring(at + 1)
        // A space ends the mention unless it continues one of the multi-word labels ("linter errors", "docs: x").
        if (typed.contains(' ') && !typed.lowercase().let { it.startsWith("linter") || it.startsWith("docs") || it.startsWith("chat") || it.startsWith("notepad") || it.startsWith("folder") }) { dismiss(); return }
        val q = typed.lowercase()
        refreshCache {
            if (g != generation) return@refreshCache
            Api.async("mention-files", { try { Api.get("/api/files", mapOf("q" to q.substringAfter(':').trim())) } catch (e: Exception) { JSONObject() } }) { r, _ ->
                if (g != generation) return@async
                val files = ArrayList<String>()
                r?.optJSONArray("files")?.let { a -> for (i in 0 until minOf(a.length(), 40)) files.add(a.getString(i)) }
                val out = ArrayList<Item>()
                val current = host.activeFile()
                if (current.isNotEmpty() && q.isEmpty()) out.add(Item(current, "current file", R.drawable.ic_files, "file", JSONObject().put("path", current)))
                val fixed = listOf(
                    Item("codebase", "the most relevant code in this project", R.drawable.ic_braces, "codebase"),
                    Item("web", "web search results for your message", R.drawable.ic_globe, "web"),
                    Item("git", "your uncommitted changes", R.drawable.ic_source_control, "git"),
                    Item("terminal", "recent output of the active terminal", R.drawable.ic_terminal, "terminal"),
                    Item("linter errors", "errors and warnings from the language servers and syntax checks", R.drawable.ic_problems, "lints"))
                out.addAll(fixed.filter { q.isEmpty() || it.label.contains(q) })
                for (i in 0 until docs.length()) {
                    val d = docs.optJSONObject(i) ?: continue
                    if (d.optString("status") != "ready") continue
                    val label = "docs: " + d.optString("name")
                    if (q.isEmpty() || label.lowercase().contains(q)) out.add(Item(label, d.optString("url"), R.drawable.ic_docs, "docs", d))
                }
                if (q.isEmpty() || "docs: add new documentation".contains(q)) out.add(Item("docs: add new documentation…", "index a documentation site for @docs", R.drawable.ic_docs, "docs-add"))
                var nChats = 0
                for (i in 0 until chats.length()) {
                    val s = chats.optJSONObject(i) ?: continue
                    val title = s.optString("title")
                    if (title.isEmpty() || title == "New chat" || s.optString("id") == host.activeChatId()) continue
                    val label = "chat: $title"
                    if (q.isEmpty() || label.lowercase().contains(q)) { out.add(Item(label, "an earlier chat in this folder", R.drawable.ic_history, "chat", s)); if (++nChats >= 8) break }
                }
                for (i in 0 until notepads.length()) {
                    val n = notepads.optJSONObject(i) ?: continue
                    val label = "notepad: " + n.optString("name")
                    if (q.isEmpty() || label.lowercase().contains(q)) out.add(Item(label, n.optString("text").take(60).replace('\n', ' '), R.drawable.ic_notes, "notepad", n))
                }
                val folders = files.mapNotNull { if (it.contains('/')) it.substringBeforeLast('/') else null }.distinct().take(4)
                for (f in folders) out.add(Item("folder: $f", "files and relevant code in this folder", R.drawable.ic_folder, "folder", JSONObject().put("path", f)))
                for (f in files.take(14)) if (f != current || q.isNotEmpty()) out.add(Item(f, "", R.drawable.ic_files, "file", JSONObject().put("path", f)))
                slash = false; start = at
                show(out.take(40))
            }
        }
    }

    private fun show(newItems: List<Item>) {
        if (newItems.isEmpty()) { dismiss(); return }
        items.clear(); items.addAll(newItems); selected = 0
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        val rows = minOf(9, items.size)
        val h = rows * dp(27) + dp(8)
        val w = anchor.width.coerceAtLeast(dp(320))
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        val x = loc[0]; val y = (loc[1] - h - dp(4)).coerceAtLeast(dp(40))
        val p = popup
        if (p != null && p.isShowing) { p.update(x, y, w, h); return }
        val box = android.widget.FrameLayout(ctx)
        box.background = Ui.rounded(Ui.bg2, 8, Ui.border2)
        box.setPadding(dp(3), dp(4), dp(3), dp(4))
        (list.parent as? ViewGroup)?.removeView(list)
        box.addView(list, android.widget.FrameLayout.LayoutParams(Ui.match, Ui.match))
        val pw = PopupWindow(box, w, h, false)
        pw.isTouchable = true
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(8).toFloat()
        pw.setOnDismissListener { if (popup === pw) popup = null }
        popup = pw
        try { pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y) } catch (e: Exception) { popup = null }
    }

    private fun pick() {
        val it = items.getOrNull(selected) ?: return
        val text = prompt.text.toString()
        val pos = prompt.selectionStart.coerceIn(0, text.length)
        val from = start.coerceIn(0, pos)
        dismiss()
        // The typed @word or /word goes; the pick puts its token in that place (or runs at once, for actions).
        prompt.text.delete(from, pos)
        prompt.setSelection(from.coerceAtMost(prompt.text.length))
        onPick(it, from)
    }
}
