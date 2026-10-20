package local.forge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The AI changes view: every file the agent changed in the last run (or in all runs), each expandable into a native
 * diff with Keep and Undo for the whole file and for every hunk, plus Keep all and Undo all. It replaces the old
 * page's review.js (inline review in the editor) and the file chips of chat.js.
 *
 * Server API (forge/server.py):
 *  - GET /api/changes            -> {changes: [{run_id, path, kind, ts, revertable, workspace, title, run_ts, session_id}], folder}
 *    with ?all=1 for every folder's runs, or ?run_id=X for one run; the run_id form adds "before" and "after"
 *    (the file's content before and after the run; null when the file did not or does not exist).
 *  - GET /api/file?path=         -> {path, content, mtime, size}: the file as it is now (the user may have edited it).
 *  - POST /api/file {path, content} -> {ok, path, mtime, check}: used for undoing one hunk.
 *  - POST /api/revert {run_id, path} -> {ok, message}: restores the file to its state before the run.
 *  - POST /api/revert_all {run_id}   -> {ok, messages: [..]}: restores every file of the run.
 * "Keep" is local, as on the old page: a kept file is remembered per folder in SharedPreferences and no longer
 * counted as waiting for review.
 */
class ReviewPanel(private val ctx: Context, private val host: MainActivity) {
    companion object {
        /**
         * Set by the window: shows the AI changes view for one run. The banner's Review button calls it with the
         * run id; when nothing is set the button asks the user to open the view from the activity bar.
         */
        var openReview: ((String) -> Unit)? = null

        /** The run the view shows first when it is opened next (the banner's Review button sets it). */
        @Volatile var focusRunId: String = ""

        private const val PREFS = "forge.review"
        private const val MAX_KEPT = 600
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        private fun keptKey(folder: String) = "kept:" + folder

        /** The kept (reviewed) changes of a folder, as "runId\u0000path" strings. */
        fun keptSet(c: Context, folder: String): MutableSet<String> {
            val out = LinkedHashSet<String>()
            try {
                val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(keptKey(folder), "") ?: ""
                if (raw.isNotEmpty()) { val a = JSONArray(raw); for (i in 0 until a.length()) out.add(a.getString(i)) }
            } catch (e: Exception) { }
            return out
        }

        fun saveKept(c: Context, folder: String, kept: Set<String>) {
            val list = kept.toList().takeLast(MAX_KEPT)
            val a = JSONArray(); for (k in list) a.put(k)
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(keptKey(folder), a.toString()).apply()
        }

        fun keptId(runId: String, path: String) = runId + "\u0000" + path
    }

    private class Change(val runId: String, val path: String, var kind: String, val ts: Double, val revertable: Boolean,
                         val workspace: String, val title: String, val runTs: Double, val sessionId: String)

    private class Run(val id: String, val title: String, val ts: Double, val workspace: String, val sessionId: String) {
        val files = ArrayList<Change>()
    }

    /** One expanded file: the original lines, the file's current lines and the hunks between them. */
    private inner class Detail(val change: Change, val box: LinearLayout) {
        var base: List<String> = emptyList()
        var now: List<String> = emptyList()
        var hunks: List<Diff.Hunk> = emptyList()
        var busy = false
    }

    val view: LinearLayout = LinearLayout(ctx)
    private val list = LinearLayout(ctx)
    private val notice = Ui.text(ctx, "", 12f, Ui.fg3)
    private val lastChip: LinearLayout
    private val allChip: LinearLayout
    private var mode = "last"
    private var runs: List<Run> = emptyList()
    private var kept: MutableSet<String> = keptSet(ctx, Api.folder)
    private val details = HashMap<String, Detail>()
    private val rows = HashMap<String, LinearLayout>()
    private val expanded = HashSet<String>()

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(Chrome.panelHeader(ctx, "AI changes", Ui.miniButton(ctx, R.drawable.ic_refresh, "Reload the list") { load() }))
        lastChip = Ui.chip(ctx, "Last run") { setMode("last") }
        allChip = Ui.chip(ctx, "All runs") { setMode("all") }
        val modes = Ui.row(ctx, lastChip, allChip)
        (allChip.layoutParams as? LinearLayout.LayoutParams ?: Ui.lp(Ui.wrap, Ui.wrap)).let { it.leftMargin = Ui.dp(ctx, 6); allChip.layoutParams = it }
        modes.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        view.addView(modes, Ui.lp(Ui.match, Ui.wrap))
        notice.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        view.addView(notice, Ui.lp(Ui.match, Ui.wrap))
        list.orientation = LinearLayout.VERTICAL
        view.addView(Ui.scroll(ctx, list), Ui.lp(Ui.match, 0, 1f))
        if (focusRunId.isNotEmpty()) mode = "last"
        styleChips()
        load()
    }

    private fun styleChips() {
        Ui.setChip(lastChip, "Last run", 0, if (mode == "last") Ui.accent else Ui.fg2)
        Ui.setChip(allChip, "All runs", 0, if (mode == "all") Ui.accent else Ui.fg2)
    }

    private fun setMode(m: String) {
        if (mode == m) return
        mode = m
        if (m == "all") focusRunId = ""
        styleChips()
        load()
    }

    // ---------------------------------------------------------------- loading
    fun load() {
        notice.text = "Loading the agent's changes."
        notice.visibility = View.VISIBLE
        val focus = focusRunId
        val params = if (mode == "all" || focus.isNotEmpty()) mapOf("all" to "1") else emptyMap()
        Api.async("changes", { Api.get("/api/changes", params) }) { r, e ->
            if (r == null) { notice.text = "Cannot load the changes: " + (e?.message ?: "unknown error"); return@async }
            runs = group(r.optJSONArray("changes") ?: JSONArray())
            kept = keptSet(ctx, Api.folder)
            render()
        }
    }

    /** Groups the flat change list by run; the server lists runs newest first and files in change order. */
    private fun group(arr: JSONArray): List<Run> {
        val byId = LinkedHashMap<String, Run>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optString("run_id")
            val run = byId.getOrPut(rid) { Run(rid, o.optString("title"), o.optDouble("run_ts", 0.0), o.optString("workspace"), o.optString("session_id")) }
            run.files.add(Change(rid, o.optString("path"), o.optString("kind"), o.optDouble("ts", 0.0), o.optBoolean("revertable", true),
                o.optString("workspace"), o.optString("title"), o.optDouble("run_ts", 0.0), o.optString("session_id")))
        }
        return byId.values.toList()
    }

    private fun isKept(ch: Change) = kept.contains(keptId(ch.runId, ch.path))

    private fun setKept(ch: Change, on: Boolean) {
        val id = keptId(ch.runId, ch.path)
        if (on) kept.add(id) else kept.remove(id)
        saveKept(ctx, Api.folder, kept)
    }

    /** Files of a run that still wait for a decision: not reverted and not kept. */
    private fun pending(run: Run): List<Change> = run.files.filter { it.kind != "reverted" && !isKept(it) }

    private fun shownRuns(): List<Run> {
        if (runs.isEmpty()) return emptyList()
        if (mode == "all") return runs
        val focus = focusRunId
        if (focus.isNotEmpty()) { val r = runs.firstOrNull { it.id == focus }; if (r != null) return listOf(r) }
        // The newest run of this folder; with an empty folder setting every run belongs to the server's default folder.
        val mine = runs.firstOrNull { Api.folder.isEmpty() || it.workspace.isEmpty() || it.workspace.trimEnd('/') == Api.folder.trimEnd('/') }
        return listOf(mine ?: runs.first())
    }

    // ---------------------------------------------------------------- rendering
    private fun render() {
        list.removeAllViews()
        rows.clear()
        val shown = shownRuns()
        if (shown.isEmpty()) {
            notice.text = if (mode == "all") "No agent run has changed a file yet." else "The agent has not changed any file in this folder yet."
            notice.visibility = View.VISIBLE
            return
        }
        val waiting = shown.sumOf { pending(it).size }
        notice.text = when {
            waiting == 0 -> "Every change here has been kept or undone."
            waiting == 1 -> "1 file waits for your decision."
            else -> "$waiting files wait for your decision."
        }
        for (run in shown) {
            list.addView(runHeader(run), Ui.lp(Ui.match, Ui.wrap))
            for (ch in run.files) {
                val row = fileRow(ch)
                rows[keptId(ch.runId, ch.path)] = row
                list.addView(row, Ui.lp(Ui.match, Ui.wrap))
            }
        }
    }

    private fun runHeader(run: Run): View {
        val col = Ui.column(ctx)
        col.setBackgroundColor(Ui.bg2)
        col.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        val title = Ui.text(ctx, if (run.title.isNotEmpty()) run.title else "Agent run " + run.id.take(8), 12.5f, Ui.fg, bold = true)
        title.maxLines = 2; title.ellipsize = TextUtils.TruncateAt.END
        col.addView(title, Ui.lp(Ui.match, Ui.wrap))
        val when_ = if (run.ts > 0) timeFormat.format(Date((run.ts * 1000).toLong())) else ""
        val folder = run.workspace.trimEnd('/').substringAfterLast('/')
        val metaText = listOf(when_, if (mode == "all" && folder.isNotEmpty()) "folder " + folder else "",
            run.files.size.toString() + (if (run.files.size == 1) " file" else " files")).filter { it.isNotEmpty() }.joinToString("  ·  ")
        col.addView(Ui.text(ctx, metaText, 11f, Ui.fg3), Ui.lp(Ui.match, Ui.wrap))
        val actions = Ui.row(ctx)
        val keepAll = Ui.button(ctx, "Keep all") { keepAll(run) }
        val undoAll = Ui.button(ctx, "Undo all") { confirmUndoAll(run) }
        actions.addView(keepAll, Ui.lp(Ui.wrap, Ui.wrap))
        actions.addView(undoAll, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 6) })
        if (run.sessionId.isNotEmpty()) {
            val chat = Ui.button(ctx, "Open chat") { host.openChatSession(run.sessionId) }
            actions.addView(chat, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 6) })
        }
        col.addView(actions, Ui.lp(Ui.wrap, Ui.wrap).also { it.topMargin = Ui.dp(ctx, 6) })
        return col
    }

    private fun kindColor(kind: String): Int = when (kind) {
        "created" -> Ui.green
        "deleted" -> Ui.red
        "reverted" -> Ui.fg3
        else -> Ui.yellow
    }

    private fun fileRow(ch: Change): LinearLayout {
        val col = Ui.column(ctx)
        val id = keptId(ch.runId, ch.path)
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
        head.background = Ui.states(null, Ui.rounded(Ui.bg3, 4), Ui.rounded(Ui.bg4, 4))
        head.isClickable = true; head.isFocusable = true
        val chevron = Ui.iconView(ctx, if (expanded.contains(id)) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, Ui.fg3, 14)
        head.addView(chevron)
        val icon = ImageView(ctx)
        icon.setImageDrawable(FileIcons.file(ctx, ch.path.substringAfterLast('/')))
        head.addView(icon, Ui.lp(Ui.dp(ctx, 16), Ui.dp(ctx, 16)).also { it.leftMargin = Ui.dp(ctx, 4); it.rightMargin = Ui.dp(ctx, 6) })
        val name = Ui.text(ctx, ch.path, 12.5f, if (ch.kind == "reverted" || isKept(ch)) Ui.fg2 else Ui.fg)
        name.maxLines = 1; name.ellipsize = TextUtils.TruncateAt.MIDDLE
        head.addView(name, Ui.lp(0, Ui.wrap, 1f))
        val badgeText = if (ch.kind != "reverted" && isKept(ch)) "kept" else ch.kind
        val badge = Ui.text(ctx, badgeText, 10.5f, if (badgeText == "kept") Ui.fg3 else kindColor(ch.kind), bold = true)
        badge.setPadding(Ui.dp(ctx, 6), 0, 0, 0)
        head.addView(badge, Ui.lp(Ui.wrap, Ui.wrap))
        head.setOnClickListener { toggle(ch, col) }
        col.addView(head, Ui.lp(Ui.match, Ui.wrap))

        val actions = Ui.row(ctx)
        actions.setPadding(Ui.dp(ctx, 30), 0, Ui.dp(ctx, 8), Ui.dp(ctx, 6))
        val open = Ui.button(ctx, "Open in editor") { openInEditor(ch) }
        actions.addView(open, Ui.lp(Ui.wrap, Ui.wrap))
        if (ch.kind != "reverted") {
            if (!isKept(ch)) {
                val keep = Ui.button(ctx, "Keep") { setKept(ch, true); render() }
                actions.addView(keep, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 6) })
            } else {
                val again = Ui.button(ctx, "Review again") { setKept(ch, false); render() }
                actions.addView(again, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 6) })
            }
            if (ch.revertable) {
                val undo = Ui.button(ctx, "Undo") { revertFile(ch) }
                actions.addView(undo, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(ctx, 6) })
            } else {
                val t = Ui.text(ctx, "Too large for a snapshot: cannot be undone here", 11f, Ui.fg3)
                t.setPadding(Ui.dp(ctx, 8), 0, 0, 0)
                actions.addView(t, Ui.lp(0, Ui.wrap, 1f))
            }
        }
        if (ch.kind == "deleted") open.visibility = View.GONE
        col.addView(actions, Ui.lp(Ui.match, Ui.wrap))

        val box = Ui.column(ctx)
        box.visibility = if (expanded.contains(id)) View.VISIBLE else View.GONE
        col.addView(box, Ui.lp(Ui.match, Ui.wrap))
        if (expanded.contains(id)) fillDetail(ch, box)
        col.addView(Ui.divider(ctx, false))
        return col
    }

    private fun toggle(ch: Change, row: LinearLayout) {
        val id = keptId(ch.runId, ch.path)
        if (expanded.contains(id)) expanded.remove(id) else expanded.add(id)
        val fresh = fileRow(ch)
        val idx = list.indexOfChild(row)
        if (idx >= 0) { list.removeViewAt(idx); list.addView(fresh, idx, Ui.lp(Ui.match, Ui.wrap)) }
        rows[id] = fresh
    }

    private fun openInEditor(ch: Change) {
        val d = details[keptId(ch.runId, ch.path)]
        val line = d?.hunks?.firstOrNull()?.let { it.b0 + 1 } ?: 0
        host.openFileAt(ch.path, line)
    }

    // ---------------------------------------------------------------- the diff of one file
    private fun fillDetail(ch: Change, box: LinearLayout) {
        box.removeAllViews()
        val loading = Ui.text(ctx, "Loading the diff.", 11.5f, Ui.fg3)
        loading.setPadding(Ui.dp(ctx, 30), 0, Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        box.addView(loading, Ui.lp(Ui.match, Ui.wrap))
        val d = Detail(ch, box)
        details[keptId(ch.runId, ch.path)] = d
        Api.async("change-detail", {
            val r = Api.get("/api/changes", mapOf("run_id" to ch.runId))
            val arr = r.optJSONArray("changes") ?: JSONArray()
            var item: JSONObject? = null
            for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; if (o.optString("path") == ch.path) item = o }
            val found = item ?: throw Api.ApiError("The server no longer has this change.")
            val before = if (found.isNull("before")) "" else found.optString("before")
            val after = if (found.isNull("after")) "" else found.optString("after")
            // The file as it is now (the user may have edited it since the run); the snapshot is the fallback.
            val now = if (ch.kind == "deleted" || ch.kind == "reverted") after else try {
                Api.get("/api/file", mapOf("path" to ch.path)).optString("content")
            } catch (e: Exception) { after }
            Triple(before, now, found.optString("kind"))
        }) { r, e ->
            if (r == null) { loading.text = "Cannot load the diff: " + (e?.message ?: "unknown error"); return@async }
            ch.kind = r.third
            d.base = Diff.split(r.first)
            d.now = Diff.split(r.second)
            d.hunks = if (ch.kind == "deleted") listOf(Diff.Hunk(0, d.base.size, 0, 0)) else Diff.lines(d.base, d.now)
            renderDetail(d)
        }
    }

    private fun renderDetail(d: Detail) {
        val box = d.box
        box.removeAllViews()
        val ch = d.change
        if (d.hunks.isEmpty()) {
            val t = Ui.text(ctx, "No differences remain between this file and its state before the run.", 11.5f, Ui.fg3)
            t.setPadding(Ui.dp(ctx, 30), 0, Ui.dp(ctx, 8), Ui.dp(ctx, 8))
            box.addView(t, Ui.lp(Ui.match, Ui.wrap))
            return
        }
        val (added, removed) = Diff.stats(d.hunks)
        val stat = Ui.text(ctx, d.hunks.size.toString() + (if (d.hunks.size == 1) " change: " else " changes: ") + added + " added, " + removed + " removed", 11.5f, Ui.fg2)
        stat.setPadding(Ui.dp(ctx, 30), 0, Ui.dp(ctx, 8), Ui.dp(ctx, 2))
        box.addView(stat, Ui.lp(Ui.match, Ui.wrap))
        val perHunk = ch.kind != "deleted" && ch.kind != "reverted" && !isKept(ch)
        val diffView = Diff.render(ctx, d.base, d.now, d.hunks, if (!perHunk) null else { i ->
            val keep = Ui.button(ctx, "Keep") { keepHunk(d, i) }
            val undo = Ui.button(ctx, "Undo") { undoHunk(d, i) }
            val row = Ui.row(ctx, keep, undo)
            (undo.layoutParams as? LinearLayout.LayoutParams ?: Ui.lp(Ui.wrap, Ui.wrap)).let { it.leftMargin = Ui.dp(ctx, 4); undo.layoutParams = it }
            row
        })
        diffView.setPadding(Ui.dp(ctx, 22), 0, 0, Ui.dp(ctx, 6))
        box.addView(diffView, Ui.lp(Ui.match, Ui.wrap))
    }

    /** Accepts one hunk: the original moves forward over it, so the remaining hunks stay reviewable. */
    private fun keepHunk(d: Detail, i: Int) {
        val h = d.hunks.getOrNull(i) ?: return
        d.base = Diff.keepHunk(d.base, d.now, h)
        d.hunks = Diff.lines(d.base, d.now)
        if (d.hunks.isEmpty()) { setKept(d.change, true); render() } else renderDetail(d)
    }

    /** Undoes one hunk by writing the file with the original lines put back; the editor reloads it. */
    private fun undoHunk(d: Detail, i: Int) {
        val h = d.hunks.getOrNull(i) ?: return
        if (d.busy) return
        d.busy = true
        val next = Diff.undoHunk(d.base, d.now, h)
        val content = next.joinToString("\n")
        val path = d.change.path
        Api.async("undo-hunk", { Api.post("/api/file", JSONObject().put("path", path).put("content", content)) }) { r, e ->
            d.busy = false
            if (r == null) { Ui.toast(ctx, "Could not write " + path + ": " + (e?.message ?: "")); return@async }
            d.now = next
            d.hunks = Diff.lines(d.base, d.now)
            host.fileChanged(path)
            if (d.hunks.isEmpty()) { Ui.toast(ctx, "Every change in " + path + " was undone."); setKept(d.change, true); render() }
            else renderDetail(d)
        }
    }

    // ---------------------------------------------------------------- whole files and whole runs
    private fun revertFile(ch: Change) {
        Api.async("revert", { Api.post("/api/revert", JSONObject().put("run_id", ch.runId).put("path", ch.path)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Could not undo " + ch.path + ": " + (e?.message ?: "")); return@async }
            Ui.toast(ctx, r.optString("message", "Restored " + ch.path))
            ch.kind = "reverted"
            setKept(ch, false)
            host.fileChanged(ch.path)
            host.refreshTree()
            load()
        }
    }

    private fun keepAll(run: Run) {
        for (ch in run.files) if (ch.kind != "reverted") kept.add(keptId(ch.runId, ch.path))
        saveKept(ctx, Api.folder, kept)
        Ui.toast(ctx, "Kept every change of this run.")
        render()
    }

    private fun confirmUndoAll(run: Run) {
        val n = run.files.count { it.kind != "reverted" }
        if (n == 0) { Ui.toast(ctx, "Every change of this run is already undone."); return }
        AlertDialog.Builder(ctx).setTitle("Undo every change of this run?")
            .setMessage("Forge restores $n file" + (if (n == 1) "" else "s") + " to the state before the run. Files the run created go to the trash folder.")
            .setPositiveButton("Undo all") { _, _ -> undoAll(run) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun undoAll(run: Run) {
        Api.async("revert-all", { Api.post("/api/revert_all", JSONObject().put("run_id", run.id)) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Could not undo the run: " + (e?.message ?: "")); return@async }
            val msgs = r.optJSONArray("messages") ?: JSONArray()
            Ui.toast(ctx, msgs.length().toString() + (if (msgs.length() == 1) " file restored." else " files restored."))
            for (ch in run.files) { host.fileChanged(ch.path); kept.remove(keptId(ch.runId, ch.path)) }
            saveKept(ctx, Api.folder, kept)
            host.refreshTree()
            load()
        }
    }

    // ---------------------------------------------------------------- the banner above the editor
    /**
     * A one-line banner for a finished run: "N files changed by the agent" with Review, Keep all and a close button.
     * It is returned right away and fills itself in; it hides itself when the run changed nothing (or everything
     * was kept already). Returns null for an empty run id.
     */
    fun bannerFor(runId: String): View? {
        if (runId.isBlank()) return null
        val c = ctx
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.rounded(Ui.accentSoft, 6, Ui.border2)
        row.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4))
        val text = Ui.text(c, "Checking what the agent changed.", 12f, Ui.fg)
        text.maxLines = 1; text.ellipsize = TextUtils.TruncateAt.END
        row.addView(text, Ui.lp(0, Ui.wrap, 1f))
        val review = Ui.button(c, "Review", primary = true) {
            focusRunId = runId
            val open = openReview
            if (open != null) open(runId) else Ui.toast(c, "Open AI changes from the activity bar to review them.")
        }
        row.addView(review, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(c, 8) })
        var files: List<Change> = emptyList()
        val keepAll = Ui.button(c, "Keep all") {
            val k = keptSet(c, Api.folder)
            for (ch in files) k.add(keptId(ch.runId, ch.path))
            saveKept(c, Api.folder, k)
            kept = k
            row.visibility = View.GONE
            Ui.toast(c, "Kept every change of this reply.")
        }
        row.addView(keepAll, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = Ui.dp(c, 6) })
        val close = Ui.miniButton(c, R.drawable.ic_close, "Hide") { row.visibility = View.GONE }
        row.addView(close, Ui.lp(Ui.dp(c, 24), Ui.dp(c, 24)).also { it.leftMargin = Ui.dp(c, 4) })
        Api.async("banner-changes", { Api.get("/api/changes", mapOf("run_id" to runId)) }) { r, e ->
            if (r == null) { row.visibility = View.GONE; return@async }
            val k = keptSet(c, Api.folder)
            val all = group(r.optJSONArray("changes") ?: JSONArray()).flatMap { it.files }
            files = all.filter { it.kind != "reverted" && !k.contains(keptId(it.runId, it.path)) }
            if (files.isEmpty()) { row.visibility = View.GONE; return@async }
            text.text = files.size.toString() + (if (files.size == 1) " file changed by the agent" else " files changed by the agent")
            text.setTypeface(text.typeface, Typeface.BOLD)
        }
        return row
    }
}
