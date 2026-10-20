package local.forge

import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Memories view: what the agent remembers, in two places, like Cursor's memories and rules. Global memories apply
 * in every project (~/.forge-ide/memories.json); project memories only in this folder (.forge/memories.json in it).
 * The agent reads all of them at the start of every reply, and its remember tool offers new ones for approval, where
 * you choose global or project. Here you see, add, edit, move and delete them.
 */
class MemoriesPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val body = Ui.column(ctx)
    private fun dp(v: Int) = Ui.dp(ctx, v)

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(SideKit.header(ctx, "Memories",
            Ui.miniButton(ctx, R.drawable.ic_refresh, "Reload") { load() }))
        view.addView(Ui.divider(ctx, false))
        view.addView(Ui.scroll(ctx, body), Ui.lp(Ui.match, 0, 1f))
        body.addView(SideKit.muted(ctx, "Loading the memories."))
        load()
    }

    /** GET /api/memories: {"global": [{id, text, created}], "project": [...]}. */
    fun load() {
        Api.async("memories", { Api.get("/api/memories") }) { r, e ->
            if (r == null) { body.removeAllViews(); body.addView(SideKit.muted(ctx, e?.message ?: "Could not load the memories.")); return@async }
            render(r)
        }
    }

    private fun post(body: JSONObject, done: String) {
        Api.async("memories-change", { Api.post("/api/memories", body) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Not changed: " + (e?.message ?: "")); return@async }
            host.flashStatus(done)
            render(r)
        }
    }

    private fun render(r: JSONObject) {
        body.removeAllViews()
        body.addView(SideKit.muted(ctx, "The agent reads these at the start of every reply. Global memories apply in every project; project memories only in this folder. When the agent offers to remember something, you approve it and choose where it goes."))
        section("GLOBAL (every project)", "global", r.optJSONArray("global") ?: JSONArray())
        section("THIS PROJECT (" + Api.folder.trimEnd('/').substringAfterLast('/') + ")", "project", r.optJSONArray("project") ?: JSONArray())
    }

    private fun section(title: String, scope: String, items: JSONArray) {
        body.addView(SideKit.section(ctx, title + "  " + items.length()))
        val input = Ui.input(ctx, if (scope == "global") "Add a global memory, for example: I prefer Kotlin" else "Add a project memory, for example: Use tabs here", mono = false)
        input.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && !ev.isShiftPressed) {
                add(scope, input.text.toString()); true
            } else false
        }
        val addBtn = Ui.miniButton(ctx, R.drawable.ic_plus, "Add (Enter)") { add(scope, input.text.toString()) }
        val addRow = Ui.row(ctx, input, addBtn)
        input.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        addRow.setPadding(dp(10), dp(2), dp(8), dp(6))
        body.addView(addRow, Ui.lp(Ui.match, Ui.wrap))
        if (items.length() == 0) body.addView(SideKit.muted(ctx, "None yet."))
        for (i in items.length() - 1 downTo 0) {
            val m = items.optJSONObject(i) ?: continue
            val id = m.optString("id"); val text = m.optString("text")
            val label = Ui.text(ctx, text, 12.5f, Ui.fg)
            label.setPadding(0, dp(2), 0, dp(2))
            val edit = Ui.miniButton(ctx, R.drawable.ic_rename, "Edit") { editDialog(id, text) }
            val move = Ui.miniButton(ctx, if (scope == "global") R.drawable.ic_folder else R.drawable.ic_globe,
                if (scope == "global") "Move to this project" else "Make it global (every project)") { move(id, text, if (scope == "global") "project" else "global") }
            val del = Ui.miniButton(ctx, R.drawable.ic_trash, "Delete this memory") { confirmDelete(id, text) }
            val row = Ui.row(ctx, label, edit, move, del)
            label.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            row.setPadding(dp(12), dp(4), dp(6), dp(4))
            row.background = Ui.rounded(Ui.bg2, 6)
            body.addView(row, Ui.lp(Ui.match, Ui.wrap).also { it.setMargins(dp(8), dp(2), dp(8), dp(2)) })
        }
    }

    private fun add(scope: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        post(JSONObject().put("action", "add").put("scope", scope).put("text", t), if (scope == "global") "Saved a global memory." else "Saved a project memory.")
    }

    private fun editDialog(id: String, text: String) {
        val input = Ui.input(ctx, "", mono = false, multiline = true)
        input.setText(text); input.setSelection(text.length)
        AlertDialog.Builder(ctx).setTitle("Edit the memory").setView(input)
            .setPositiveButton("Save") { _, _ -> val t = input.text.toString().trim(); if (t.isNotEmpty()) post(JSONObject().put("action", "update").put("id", id).put("text", t), "Memory saved.") }
            .setNegativeButton("Cancel", null).show()
        input.requestFocus()
    }

    /** Moving is adding it to the other place and taking it out of this one. */
    private fun move(id: String, text: String, to: String) {
        Api.async("memories-move", {
            Api.post("/api/memories", JSONObject().put("action", "add").put("scope", to).put("text", text))
            Api.post("/api/memories", JSONObject().put("action", "remove").put("id", id))
        }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Not moved: " + (e?.message ?: "")); return@async }
            host.flashStatus(if (to == "global") "The memory is global now." else "The memory belongs to this project now.")
            render(r)
        }
    }

    private fun confirmDelete(id: String, text: String) {
        AlertDialog.Builder(ctx).setTitle("Delete this memory?").setMessage(text)
            .setPositiveButton("Delete") { _, _ -> post(JSONObject().put("action", "remove").put("id", id), "Memory deleted.") }
            .setNegativeButton("Cancel", null).show()
    }
}
