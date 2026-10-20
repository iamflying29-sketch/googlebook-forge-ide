package local.forge

import android.app.AlertDialog
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Source control view, ported from the old page's extras.js: the repository summary, the commit message with
 * "Write it for me", Commit, Stage all, Unstage all, Pull and Push, the staged and unstaged file lists with stage,
 * unstage and discard per file, a diff of a file against HEAD, the recent commits, and "Initialize repository" when
 * the folder has no repository. Every call goes to the Forge server, which runs git in the window's folder.
 */
class GitPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val body = Ui.column(ctx)
    private var state: JSONObject? = null
    /** The commit message survives a refresh of the view, like the page's copy in sessionStorage. */
    private var commitMessage = ""
    private var messageBox: EditText? = null
    private var loading = false

    private companion object {
        const val MESSAGE_HINT = "Commit message (Ctrl Enter to commit)"
    }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(SideKit.header(ctx, "Source control", Ui.miniButton(ctx, R.drawable.ic_refresh, "Refresh") { load() }))
        view.addView(Ui.divider(ctx, false))
        view.addView(Ui.scroll(ctx, body), Ui.lp(Ui.match, 0, 1f))
        body.addView(SideKit.muted(ctx, "Loading the repository status."))
        load()
    }

    /** Reads the status (GET /api/git) and, for a repository, the recent commits (GET /api/git/log). */
    fun load() {
        if (loading) return
        loading = true
        Api.async("git", {
            val g = Api.get("/api/git")
            val log = if (g.optBoolean("repo")) (try { Api.get("/api/git/log") } catch (e: Exception) { null }) else null
            Pair(g, log)
        }) { r, e ->
            loading = false
            if (r == null) showMessage(e?.message ?: "Could not read the repository status.") else render(r.first, r.second)
        }
    }

    private fun showMessage(s: String) {
        state = null; messageBox = null
        body.removeAllViews()
        body.addView(SideKit.muted(ctx, s))
    }

    private fun render(g: JSONObject, log: JSONObject?) {
        state = g
        messageBox = null
        body.removeAllViews()
        if (!g.optBoolean("repo")) {
            body.addView(SideKit.muted(ctx, "This folder is not a git repository."))
            val init = Ui.button(ctx, "Initialize repository", primary = true) { initRepository() }
            val box = Ui.row(ctx, init)
            box.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), Ui.dp(ctx, 10))
            body.addView(box, Ui.lp(Ui.match, Ui.wrap))
            return
        }
        if (g.has("error")) { body.addView(SideKit.muted(ctx, "Git did not answer: " + g.optString("error"))); return }

        // The summary line: "## main...origin/main [ahead 1]" from git status, or the branch name.
        val summary = g.optString("summary").ifEmpty { "On " + g.optString("branch") }
        val sum = Ui.text(ctx, summary, 11.5f, Ui.fg2, mono = true)
        sum.maxLines = 1; sum.ellipsize = TextUtils.TruncateAt.END
        sum.tooltipText = summary
        sum.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
        body.addView(sum, Ui.lp(Ui.match, Ui.wrap))
        body.addView(Ui.divider(ctx, false))

        // The commit message box.
        val msg = Ui.input(ctx, MESSAGE_HINT, multiline = true)
        msg.minLines = 3
        msg.setText(commitMessage)
        msg.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { commitMessage = s?.toString() ?: "" }
        })
        msg.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN && ev.isCtrlPressed) { commit(); true } else false
        }
        messageBox = msg
        val msgLp = Ui.lp(Ui.match, Ui.wrap); msgLp.setMargins(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), 0)
        body.addView(msg, msgLp)

        // The actions, two per row so they fit the 260 dp side panel.
        actionRow(
            action("Commit", primary = true, tooltip = "Commit the staged changes (stages everything if nothing is staged)") { commit() },
            action("Write it for me", tooltip = "Let the model write the commit message from the diff") { generateMessage() })
        actionRow(
            action("Stage all") { simple("/api/git/stage", "Stage all") },
            action("Unstage all") { simple("/api/git/unstage", "Unstage all") })
        actionRow(
            action("Pull", tooltip = "git pull (fast-forward only)") { remote("/api/git/pull", "Pull") },
            action("Push", tooltip = "git push") { remote("/api/git/push", "Push") })
        val gap = View(ctx); body.addView(gap, Ui.lp(Ui.match, Ui.dp(ctx, 6)))
        body.addView(Ui.divider(ctx, false))

        // The file lists.
        val files = g.optJSONArray("files") ?: JSONArray()
        val staged = ArrayList<JSONObject>(); val unstaged = ArrayList<JSONObject>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            if (f.optBoolean("staged")) staged.add(f)
            if (f.optBoolean("unstaged")) unstaged.add(f)
        }
        fileSection("Staged changes", staged, true)
        fileSection("Changes", unstaged, false)

        // The recent commits (git log --oneline): the short hash and the subject of each entry.
        if (log != null) {
            body.addView(SideKit.section(ctx, "Recent commits"))
            val lines = log.optString("log").trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) body.addView(SideKit.muted(ctx, "No commits yet."))
            for (line in lines) {
                val hash = line.substringBefore(' '); val subject = line.substringAfter(' ', "")
                val sb = SpannableStringBuilder()
                sb.append(hash)
                sb.setSpan(ForegroundColorSpan(Ui.blue), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("  ").append(subject)
                val t = Ui.text(ctx, sb, 11f, Ui.fg3, mono = true)
                t.maxLines = 2; t.ellipsize = TextUtils.TruncateAt.END
                t.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 2), Ui.dp(ctx, 12), Ui.dp(ctx, 2))
                t.tooltipText = line
                body.addView(t, Ui.lp(Ui.match, Ui.wrap))
            }
            val tail = View(ctx); body.addView(tail, Ui.lp(Ui.match, Ui.dp(ctx, 8)))
        }
    }

    private fun action(label: String, primary: Boolean = false, tooltip: String? = null, onClick: () -> Unit): Button {
        val b = Ui.button(ctx, label, primary) { onClick() }
        if (tooltip != null) b.tooltipText = tooltip
        return b
    }

    private fun actionRow(vararg buttons: Button) {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(ctx, 9), Ui.dp(ctx, 6), Ui.dp(ctx, 9), 0)
        for (b in buttons) {
            val lp = Ui.lp(0, Ui.wrap, 1f); lp.leftMargin = Ui.dp(ctx, 3); lp.rightMargin = Ui.dp(ctx, 3)
            row.addView(b, lp)
        }
        body.addView(row, Ui.lp(Ui.match, Ui.wrap))
    }

    private fun fileSection(title: String, list: List<JSONObject>, stagedList: Boolean) {
        body.addView(SideKit.section(ctx, "$title (${list.size})"))
        if (list.isEmpty()) {
            val none = Ui.text(ctx, "none", 12f, Ui.fg3)
            none.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 2), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
            body.addView(none, Ui.lp(Ui.match, Ui.wrap))
        }
        for (f in list) body.addView(fileRow(f, stagedList))
    }

    /** One changed file: the status code in color, the path, and the actions open, stage or unstage, discard. */
    private fun fileRow(f: JSONObject, stagedList: Boolean): View {
        val path = f.optString("path"); val status = f.optString("status").ifEmpty { "M" }
        val row = SideKit.listRow(ctx)
        row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 2), Ui.dp(ctx, 6), Ui.dp(ctx, 2))
        val color = when (status[0]) { 'A', '?' -> Ui.green; 'D' -> Ui.red; else -> Ui.yellow }
        val code = Ui.text(ctx, status, 11f, color, mono = true)
        code.gravity = Gravity.CENTER
        row.addView(code, Ui.lp(Ui.dp(ctx, 22), Ui.wrap))
        val name = Ui.text(ctx, path, 12.5f, Ui.fg)
        name.maxLines = 1; name.ellipsize = TextUtils.TruncateAt.MIDDLE
        name.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0)
        row.addView(name, Ui.lp(0, Ui.wrap, 1f))
        row.addView(Ui.miniButton(ctx, R.drawable.ic_chevron_right, "Open file") { host.openFileAt(path, 0) })
        if (stagedList) {
            row.addView(SideKit.textMini(ctx, "−", "Unstage") { paths("/api/git/unstage", path, "Unstage") })
        } else {
            row.addView(SideKit.textMini(ctx, "+", "Stage") { paths("/api/git/stage", path, "Stage") })
            if (status != "??") row.addView(SideKit.textMini(ctx, "↶", "Discard changes (a copy is kept in AI Changes)") { discard(path) })
        }
        row.tooltipText = path
        row.setOnClickListener { showDiff(path, stagedList) }
        return row
    }

    // ---------------------------------------------------------------- actions
    private fun initRepository() {
        Api.async("git-init", { Api.post("/api/git/init", JSONObject()) }) { r, e ->
            if (r == null) Ui.toast(ctx, e?.message ?: "Could not initialize the repository.")
            else if (!r.optBoolean("ok")) SideKit.showText(ctx, "Initialize repository failed", r.optString("output"))
            load()
        }
    }

    /** Stage all or unstage all: POST with an empty body. */
    private fun simple(path: String, label: String) {
        Api.async("git-$label", { Api.post(path, JSONObject()) }) { r, e ->
            if (r == null) Ui.toast(ctx, e?.message ?: "$label failed.")
            else if (!r.optBoolean("ok")) SideKit.showText(ctx, "$label failed", r.optString("output"))
            load()
        }
    }

    /** Stage or unstage one file: POST with {"paths": [path]}. */
    private fun paths(api: String, path: String, label: String) {
        Api.async("git-$label", { Api.post(api, JSONObject().put("paths", JSONArray().put(path))) }) { r, e ->
            if (r == null) Ui.toast(ctx, e?.message ?: "$label failed.")
            else if (!r.optBoolean("ok")) SideKit.showText(ctx, "$label failed: $path", r.optString("output"))
            load()
        }
    }

    private fun discard(path: String) {
        SideKit.confirm(ctx, "Discard changes?", "Discard your changes to $path? A copy stays in the AI Changes view.", "Discard") {
            Api.async("git-discard", { Api.post("/api/git/discard", JSONObject().put("path", path)) }) { r, e ->
                if (r == null) Ui.toast(ctx, e?.message ?: "Discard failed.")
                else if (!r.optBoolean("ok")) SideKit.showText(ctx, "Discard failed: $path", r.optString("output"))
                else host.fileChanged(path)
                load()
            }
        }
    }

    /** Pull and Push take a while and answer with git's output, which is shown in a dialog. */
    private fun remote(path: String, label: String) {
        Ui.toast(ctx, "$label started.")
        Api.async("git-$label", { Api.post(path, JSONObject(), 130000) }) { r, e ->
            if (r == null) { Ui.toast(ctx, e?.message ?: "$label failed."); load(); return@async }
            val ok = r.optBoolean("ok")
            SideKit.showText(ctx, if (ok) "$label: done" else "$label: failed", r.optString("output").trim())
            load()
        }
    }

    private fun commit() {
        val text = commitMessage.trim()
        if (text.isEmpty()) { Ui.toast(ctx, "Write a commit message first, or click Write it for me."); return }
        val files = state?.optJSONArray("files") ?: JSONArray()
        var anyStaged = false
        for (i in 0 until files.length()) if (files.optJSONObject(i)?.optBoolean("staged") == true) anyStaged = true
        val body = JSONObject().put("message", text).put("stage_all", !anyStaged)
        Api.async("git-commit", { Api.post("/api/git/commit", body) }) { r, e ->
            if (r == null) { Ui.toast(ctx, e?.message ?: "Commit failed."); return@async }
            if (r.optBoolean("ok")) { Ui.toast(ctx, "Committed."); commitMessage = ""; messageBox?.setText("") }
            else SideKit.showText(ctx, "Commit failed", r.optString("output").trim())
            load()
        }
    }

    /** "Write it for me": the server asks the model for a message from the diff (POST /api/git/commit_message). */
    private fun generateMessage() {
        val box = messageBox ?: return
        box.hint = "Writing a commit message with the model. This can take a while."
        box.isEnabled = false
        Api.async("git-message", { Api.post("/api/git/commit_message", JSONObject(), 620000) }) { r, e ->
            box.isEnabled = true
            box.hint = MESSAGE_HINT
            if (r == null) { Ui.toast(ctx, e?.message ?: "The model did not answer."); return@async }
            val text = r.optString("message")
            if (text.isNotEmpty()) { commitMessage = text; box.setText(text); box.setSelection(text.length) }
            else Ui.toast(ctx, r.optString("note").ifEmpty { "No changes to describe." })
        }
    }

    // ---------------------------------------------------------------- diff
    /**
     * The file's diff against HEAD (GET /api/git/diff): added lines green, removed lines red, three lines of
     * context around each change, in a dialog with Open file and Close. The "staged" parameter is sent with the
     * request; the server's handler of 2026-10-19 reads only "path" and always compares HEAD with the working tree.
     */
    private fun showDiff(path: String, staged: Boolean) {
        Api.async("git-diff", { Api.get("/api/git/diff", mapOf("path" to path, "staged" to (if (staged) "1" else "0"))) }) { r, e ->
            if (r == null) { Ui.toast(ctx, e?.message ?: "Could not read the diff."); return@async }
            val lines = SideKit.lineDiff(r.optString("original"), r.optString("modified"))
            val text = renderDiff(lines)
            val t = Ui.text(ctx, text, 11.5f, Ui.fg2, mono = true)
            t.setTextIsSelectable(true)
            t.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
            val d = AlertDialog.Builder(ctx).setTitle("$path: HEAD to working tree").setView(Ui.scroll(ctx, t))
                .setPositiveButton("Close", null)
                .setNeutralButton("Open file") { _, _ -> host.openFileAt(path, 0) }
                .create()
            d.show()
            val dm = ctx.resources.displayMetrics
            d.window?.setLayout((dm.widthPixels * 0.82).toInt(), (dm.heightPixels * 0.85).toInt())
        }
    }

    private fun renderDiff(lines: List<SideKit.DiffLine>): CharSequence {
        val changed = lines.any { it.kind != ' ' }
        if (!changed) return "No differences between HEAD and the working tree."
        val context = 3
        val show = BooleanArray(lines.size)
        for (i in lines.indices) if (lines[i].kind != ' ') for (k in maxOf(0, i - context)..minOf(lines.size - 1, i + context)) show[k] = true
        val sb = SpannableStringBuilder()
        var hidden = 0
        fun flushHidden() {
            if (hidden == 0) return
            val start = sb.length
            sb.append("    ($hidden unchanged lines)\n")
            sb.setSpan(ForegroundColorSpan(Ui.fg3), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            hidden = 0
        }
        for (i in lines.indices) {
            if (!show[i]) { hidden++; continue }
            flushHidden()
            val l = lines[i]
            val start = sb.length
            sb.append(l.kind).append(' ').append(l.text).append('\n')
            val color = when (l.kind) { '+' -> Ui.green; '-' -> Ui.red; else -> Ui.fg2 }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        flushHidden()
        return sb
    }
}
