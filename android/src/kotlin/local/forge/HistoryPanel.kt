package local.forge

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Chats view (chat history), ported from the old page's chat.js: every saved chat of every folder
 * (GET /api/sessions with all=1), grouped by folder with this window's folder first, with a search field that
 * filters by title and folder. A row opens the chat in this window's AI panel; each row also offers Rename
 * (POST /api/session/rename), Export to a Markdown file (GET /api/session, then POST /api/session/export) and
 * Delete after a confirmation (POST /api/session/delete).
 */
class HistoryPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val body = Ui.column(ctx)
    private val search = Ui.input(ctx, "Search chats")
    private var sessions = ArrayList<JSONObject>()
    private var error: String? = null

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(SideKit.header(ctx, "Chats", Ui.miniButton(ctx, R.drawable.ic_refresh, "Refresh") { load() }))
        view.addView(Ui.divider(ctx, false))
        val sLp = Ui.lp(Ui.match, Ui.wrap); sLp.setMargins(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
        search.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6))
        view.addView(search, sLp)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        })
        view.addView(Ui.scroll(ctx, body), Ui.lp(Ui.match, 0, 1f))
        body.addView(SideKit.muted(ctx, "Loading the chats."))
        load()
    }

    /** GET /api/sessions?all=1: {"folder": ..., "sessions": [{"id", "title", "updated", "workspace", "messages"}]}. */
    fun load() {
        Api.async("sessions", { Api.get("/api/sessions", mapOf("all" to "1")) }) { r, e ->
            if (r == null) { error = e?.message ?: "Could not list the chats."; sessions.clear(); render(); return@async }
            error = null
            val list = r.optJSONArray("sessions") ?: JSONArray()
            sessions = ArrayList()
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { sessions.add(it) }
            render()
        }
    }

    private fun render() {
        body.removeAllViews()
        val err = error
        if (err != null) { body.addView(SideKit.muted(ctx, err)); return }
        val q = search.text.toString().trim().lowercase()
        val shown = sessions.filter { s ->
            q.isEmpty() || s.optString("title").lowercase().contains(q) || s.optString("workspace").lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            body.addView(SideKit.muted(ctx, if (sessions.isEmpty()) "No chats yet." else "No chat matches \"$q\"."))
            return
        }
        // Group by folder, this window's folder first; the server already sorts by last update.
        val here = Api.folder
        val groups = LinkedHashMap<String, ArrayList<JSONObject>>()
        for (s in shown) groups.getOrPut(s.optString("workspace")) { ArrayList() }.add(s)
        val keys = groups.keys.sortedWith(compareBy { if (it == here) 0 else 1 })
        for (folder in keys) {
            val head = Ui.row(ctx)
            head.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 3))
            val name = Ui.text(ctx, SideKit.folderName(folder).uppercase() + (if (folder == here) "  (THIS WINDOW)" else ""), 11f, Ui.accent2, bold = true)
            name.letterSpacing = 0.07f; name.maxLines = 1; name.ellipsize = TextUtils.TruncateAt.END
            head.addView(name, Ui.lp(Ui.wrap, Ui.wrap))
            val path = Ui.text(ctx, folder, 11f, Ui.fg3)
            path.maxLines = 1; path.ellipsize = TextUtils.TruncateAt.START
            path.setPadding(Ui.dp(ctx, 8), 0, 0, 0)
            head.addView(path, Ui.lp(0, Ui.wrap, 1f))
            head.tooltipText = folder
            body.addView(head, Ui.lp(Ui.match, Ui.wrap))
            for (s in groups[folder]!!) body.addView(sessionRow(s))
        }
    }

    private fun sessionRow(s: JSONObject): View {
        val id = s.optString("id"); val title = s.optString("title").ifEmpty { "Chat" }
        val row = SideKit.listRow(ctx)
        row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6))
        val col = Ui.column(ctx)
        val t = Ui.text(ctx, title, 12.5f, Ui.fg)
        t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t, Ui.lp(Ui.match, Ui.wrap))
        val meta = SideKit.timeAgo(s.optDouble("updated", 0.0)) + "  ·  " + s.optInt("messages") + " messages"
        col.addView(Ui.text(ctx, meta, 11f, Ui.fg3), Ui.lp(Ui.match, Ui.wrap))
        row.addView(col, Ui.lp(0, Ui.wrap, 1f))
        row.addView(Ui.miniButton(ctx, R.drawable.ic_chevron_down, "Rename, export or delete") { v -> menu(v, s) })
        row.addView(Ui.miniButton(ctx, R.drawable.ic_trash, "Delete chat") { delete(id, title) })
        row.tooltipText = title + "\n" + s.optString("workspace")
        row.setOnClickListener { host.openChatSession(id) }
        row.setOnLongClickListener { v -> menu(v, s); true }
        return row
    }

    private fun menu(anchor: View, s: JSONObject) {
        val id = s.optString("id"); val title = s.optString("title").ifEmpty { "Chat" }
        val m = PopupMenu(ctx, anchor)
        m.menu.add("Open")
        m.menu.add("Rename")
        m.menu.add("Export as Markdown")
        m.menu.add("Delete")
        m.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Open" -> host.openChatSession(id)
                "Rename" -> rename(id, title)
                "Export as Markdown" -> export(id)
                "Delete" -> delete(id, title)
            }
            true
        }
        m.show()
    }

    /** POST /api/session/rename {"id", "title"}: {"ok": true}; the server keeps at most 120 characters. */
    private fun rename(id: String, current: String) {
        SideKit.askText(ctx, "Rename chat", current, "Title", "Rename") { name ->
            Api.async("session-rename", { Api.post("/api/session/rename", JSONObject().put("id", id).put("title", name)) }) { r, e ->
                if (r == null) Ui.toast(ctx, "Not renamed: " + (e?.message ?: ""))
                load()
            }
        }
    }

    /** POST /api/session/delete {"id"}: {"ok": true}; the server moves the chat's file to its trash folder. */
    private fun delete(id: String, title: String) {
        SideKit.confirm(ctx, "Delete chat?", "Delete the chat \"$title\"? Forge moves it to its trash folder.", "Delete") {
            Api.async("session-delete", { Api.post("/api/session/delete", JSONObject().put("id", id)) }) { r, e ->
                if (r == null) Ui.toast(ctx, "Not deleted: " + (e?.message ?: ""))
                load()
            }
        }
    }

    /**
     * Export: the server writes the Markdown the client gives it (POST /api/session/export {"id", "markdown"}
     * answers {"ok": true, "path": ...}), so the transcript is read first (GET /api/session?id=...) and turned into
     * Markdown the way the old page and the native chat panel do.
     */
    private fun export(id: String) {
        Api.async("session-export", {
            val s = Api.get("/api/session", mapOf("id" to id))
            Api.post("/api/session/export", JSONObject().put("id", id).put("markdown", transcriptMarkdown(s)))
        }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Export failed: " + (e?.message ?: "")); return@async }
            val path = r.optString("path")
            SideKit.showText(ctx, "Chat exported", "Saved to\n$path", "Copy path") { host.copyToClipboard(path) }
        }
    }

    private fun transcriptMarkdown(s: JSONObject): String {
        val sb = StringBuilder("# " + s.optString("title").ifEmpty { "Chat" } + "\n\nFolder: " + s.optString("workspace").ifEmpty { Api.folder } + "\n\n")
        val tr = s.optJSONArray("transcript") ?: JSONArray()
        for (i in 0 until tr.length()) {
            val turn = tr.optJSONObject(i) ?: continue
            if (turn.optString("role") == "user") {
                sb.append("## You\n\n").append(turn.optString("text")).append("\n")
                val att = turn.optJSONArray("attachments") ?: JSONArray()
                val labels = ArrayList<String>()
                for (k in 0 until att.length()) {
                    val a = att.optJSONObject(k) ?: continue
                    if (a.optString("kind") == "editor") continue
                    labels.add(attachmentLabel(a))
                }
                if (labels.isNotEmpty()) sb.append("\nAttached: ").append(labels.joinToString(", ")).append("\n")
                sb.append("\n")
                continue
            }
            sb.append("## Forge\n\n")
            val blocks = turn.optJSONArray("blocks") ?: JSONArray()
            for (k in 0 until blocks.length()) {
                val b = blocks.optJSONObject(k) ?: continue
                when (b.optString("type")) {
                    "text" -> sb.append(b.optString("text")).append("\n\n")
                    "thinking" -> sb.append("> Thought process: ").append(b.optString("text").replace("\n", " ")).append("\n\n")
                    "tool" -> {
                        val name = b.optString("name")
                        sb.append("Tool: ").append(TOOL_LABELS[name] ?: name).append(' ').append(argSummary(name, b.optJSONObject("arguments")))
                        val result = b.optString("result")
                        if (result.isNotEmpty()) sb.append("\n```\n").append(result.take(1500)).append("\n```")
                        sb.append("\n\n")
                    }
                    "todos" -> {
                        sb.append("To-do list:\n")
                        val todos = b.optJSONArray("todos") ?: JSONArray()
                        for (n in 0 until todos.length()) {
                            val x = todos.optJSONObject(n) ?: continue
                            sb.append("- [").append(if (x.optString("status") == "completed") "x" else " ").append("] ").append(x.optString("content")).append("\n")
                        }
                        sb.append("\n")
                    }
                    "status" -> sb.append("_").append(b.optString("text")).append("_\n\n")
                }
            }
        }
        return sb.toString()
    }

    private fun attachmentLabel(a: JSONObject): String = when (a.optString("kind")) {
        "selection" -> a.optString("path") + ":" + a.optInt("start") + "-" + a.optInt("end")
        "terminal" -> "Terminal output"
        "upload" -> a.optString("name")
        "docs" -> "Docs: " + a.optString("name")
        "chat" -> "Chat: " + a.optString("title")
        "folder" -> "Folder: " + a.optString("path")
        "image" -> "Image: " + a.optString("name").ifEmpty { "image" }
        else -> a.optString("path").ifEmpty { a.optString("title").ifEmpty { a.optString("name").ifEmpty { a.optString("kind") } } }
    }

    private fun argSummary(name: String, a: JSONObject?): String {
        val x = a ?: JSONObject()
        return when (name) {
            "run_command" -> x.optString("command")
            "search" -> x.optString("pattern") + (if (x.optString("path").let { it.isNotEmpty() && it != "." }) " in " + x.optString("path") else "")
            "find_files" -> x.optString("pattern")
            "web_fetch" -> x.optString("url")
            "web_search", "codebase_search" -> x.optString("query")
            "docs_search" -> x.optString("name") + ": " + x.optString("query")
            "read_file" -> x.optString("path") + (if (x.optInt("start_line") > 0) ":" + x.optInt("start_line") + "-" + x.optString("end_line") else "")
            "look_at_image" -> x.optString("question") + (if (x.optString("image").isNotEmpty()) "  [" + x.optString("image") + "]" else "")
            "remember" -> x.optString("memory")
            "todo_write" -> (x.optJSONArray("todos")?.length() ?: 0).toString() + " items"
            else -> x.optString("path").ifEmpty { x.toString() }
        }
    }

    private companion object {
        val TOOL_LABELS = mapOf(
            "read_file" to "Read file", "write_file" to "Write file", "edit_file" to "Edit file", "list_dir" to "List folder",
            "find_files" to "Find files", "search" to "Search code", "run_command" to "Run command", "web_fetch" to "Fetch URL",
            "web_search" to "Web search", "codebase_search" to "Search codebase", "delete_path" to "Move to trash",
            "ask_user" to "Question", "look_at_image" to "Look at image", "todo_write" to "Update to-do list",
            "remember" to "Save memory", "docs_search" to "Search docs")
    }
}
