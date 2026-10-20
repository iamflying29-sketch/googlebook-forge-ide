package local.forge

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject

/**
 * The PROBLEMS tab of the bottom panel, the port of the old page's problems view (extras.js): the language servers'
 * errors and warnings of every open file, and the syntax checks Forge runs after every save and agent edit
 * ("SYNTAX CHECK FAILED"). Rows are grouped by file; a click opens the place. Each problem has Quick fix (the
 * language server's code actions) and Fix with AI; Fix all with AI sends every problem to the agent at once.
 * A filter box narrows the list, and the counts show on the tab and in the status bar.
 */
class ProblemsPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private fun dp(v: Int) = Ui.dp(ctx, v)

    /** Syntax check failures: path to the checker's text. */
    private val checks = LinkedHashMap<String, String>()
    private val rows = ArrayList<Any>()   // a String is a file header, a Problem is a row
    private val list = ListView(ctx)
    private val adapter = Adapter()
    private val filter = Ui.input(ctx, "Filter, for example: unused or main.py", mono = false)
    private val summary = Ui.text(ctx, "", 11.5f, Ui.fg3)
    private val empty = Ui.text(ctx, "", 12f, Ui.fg3)
    private val collapsed = HashSet<String>()
    private var showWarnings = true
    private val renderTask = Runnable { render() }
    /** Called with the error and warning counts after every change (the tab badge and the status bar). */
    var onCounts: ((Int, Int) -> Unit)? = null

    class Problem(val path: String, val line: Int, val col: Int, val severity: Int, val message: String, val source: String, val diag: Lsp.Diagnostic?)

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.bg)
        filter.isSingleLine = true
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { schedule(120) }
        })
        val warnToggle = Ui.miniButton(ctx, R.drawable.ic_problems, "Show or hide warnings") { v -> showWarnings = !showWarnings; (v as? ImageView)?.setColorFilter(if (showWarnings) Ui.yellow else Ui.fg3); render() }
        warnToggle.setColorFilter(Ui.yellow)
        val fixAll = Ui.button(ctx, R.drawable.ic_sparkle, "Fix all with AI", "Send every error and warning to the agent") { fixAll() }
        val collapse = Ui.miniButton(ctx, R.drawable.ic_collapse, "Collapse all files") { for (r in rows) if (r is String) collapsed.add(r); render() }
        val bar = Ui.row(ctx, filter, warnToggle, collapse, fixAll, summary)
        filter.layoutParams = Ui.lp(dp(320), Ui.wrap)
        warnToggle.layoutParams = Ui.lp(dp(26), dp(26)).also { it.leftMargin = dp(6) }
        collapse.layoutParams = Ui.lp(dp(26), dp(26)).also { it.leftMargin = dp(2) }
        (fixAll.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        summary.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(12) }
        summary.gravity = Gravity.END
        bar.setPadding(dp(10), dp(4), dp(12), dp(4))
        bar.setBackgroundColor(Ui.bg2)
        view.addView(bar, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.divider(ctx, false))
        list.adapter = adapter
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setOnItemClickListener { _, _, pos, _ ->
            when (val x = rows.getOrNull(pos)) {
                is String -> { if (!collapsed.add(x)) collapsed.remove(x); render() }
                is Problem -> host.openFileAt(x.path, x.line + 1, x.col)
            }
        }
        view.addView(list, Ui.lp(Ui.match, 0, 1f))
        empty.setPadding(dp(14), dp(10), dp(14), dp(10))
        view.addView(empty, Ui.lp(Ui.match, Ui.wrap))
        Lsp.listeners.add { _, _ -> schedule(250) }
        render()
    }

    private fun schedule(ms: Long) { Api.main.removeCallbacks(renderTask); Api.main.postDelayed(renderTask, ms) }

    /** A save or an agent edit reported its syntax check: a failure is listed until the next clean check. */
    fun reportCheck(path: String, text: String) {
        if (path.isEmpty()) return
        val failed = text.contains("SYNTAX CHECK FAILED")
        if (failed) checks[path] = text.trim() else if (checks.remove(path) == null) return
        render()
        if (failed) host.showProblems()
    }

    /** Every problem as one line of text (the chat's linter errors context and Fix all with AI). */
    fun allLines(): List<String> {
        val out = ArrayList<String>()
        for ((p, list) in Lsp.diagnostics) for (d in list) if (d.severity <= 2) out.add(p + ":" + (d.line + 1) + ": " + (if (d.severity <= 1) "error" else "warning") + ": " + d.describe())
        for ((p, t) in checks) out.add(p + ": " + t.replace(Regex("\\s+"), " ").take(400))
        return out
    }

    fun counts(): Pair<Int, Int> {
        var e = checks.size; var w = 0
        for (list in Lsp.diagnostics.values) for (d in list) { if (d.severity <= 1) e++ else if (d.severity == 2) w++ }
        return e to w
    }

    private fun render() {
        rows.clear()
        val q = filter.text.toString().trim().lowercase()
        var errors = 0; var warnings = 0; var shownCount = 0
        val byFile = LinkedHashMap<String, ArrayList<Problem>>()
        for ((p, t) in checks) {
            val m = Regex("line (\\d+)", RegexOption.IGNORE_CASE).find(t)
            val line = (m?.groupValues?.get(1)?.toIntOrNull() ?: 1) - 1
            errors++
            byFile.getOrPut(p) { ArrayList() }.add(Problem(p, line, 0, 1, t.lines().filter { it.isNotBlank() }.drop(1).joinToString(" ").ifEmpty { t }.take(500), "syntax check", null))
        }
        for ((p, list) in Lsp.diagnostics) for (d in list) {
            if (d.severity > 2) continue
            if (d.severity <= 1) errors++ else warnings++
            byFile.getOrPut(p) { ArrayList() }.add(Problem(p, d.line, d.col, d.severity, d.message, d.source + (if (d.code.isNotEmpty()) " " + d.code else ""), d))
        }
        for ((p, probs) in byFile) {
            val kept = probs.filter { (showWarnings || it.severity <= 1) && (q.isEmpty() || it.message.lowercase().contains(q) || p.lowercase().contains(q) || it.source.lowercase().contains(q)) }
                .sortedWith(compareBy({ it.severity }, { it.line }))
            if (kept.isEmpty()) continue
            rows.add(p)
            shownCount += kept.size
            if (p !in collapsed) rows.addAll(kept.take(300))
        }
        summary.text = when {
            errors + warnings == 0 -> ""
            else -> "$errors " + (if (errors == 1) "error" else "errors") + ", $warnings " + (if (warnings == 1) "warning" else "warnings")
        }
        empty.text = if (errors + warnings == 0) "No problems. Language servers check open files as you type, and Forge checks the syntax after every save and agent edit."
            else if (shownCount == 0) "No problem matches the filter." else ""
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        onCounts?.invoke(errors, warnings)
    }

    private var stepIndex = -1

    /** F8 and Shift F8 when the file has no more problems: the next or previous one in this list. */
    fun step(dir: Int) {
        val ps = rows.filterIsInstance<Problem>()
        if (ps.isEmpty()) { Ui.toast(ctx, "There are no problems."); return }
        stepIndex = ((stepIndex + dir) % ps.size + ps.size) % ps.size
        val p = ps[stepIndex]
        host.openFileAt(p.path, p.line + 1, p.col)
    }

    private fun fixAll() {
        val lines = allLines()
        if (lines.isEmpty()) { Ui.toast(ctx, "There are no problems to fix."); return }
        host.quickAsk("Fix these errors and warnings. Edit the files directly and keep the behavior the same.", "agent",
            JSONObject().put("kind", "text").put("title", "Problems").put("name", "Problems (" + lines.size + ")").put("text", lines.joinToString("\n")))
    }

    private fun fixOne(p: Problem) {
        host.quickAsk("Fix this problem in ${p.path} at line ${p.line + 1}: ${p.message}" + (if (p.source.isNotEmpty()) " (${p.source})" else ""), "agent",
            JSONObject().put("kind", "file").put("path", p.path).put("name", p.path))
    }

    private fun quickFix(p: Problem) {
        host.openFileAt(p.path, p.line + 1, p.col)
        Api.main.postDelayed({ host.editorSmarts()?.codeActions() }, 500)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position] is String) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val x = rows[position]
            if (x is String) {
                val row = (convertView as? LinearLayout) ?: Ui.row(ctx, ImageView(ctx), ImageView(ctx), Ui.text(ctx, "", 12f, Ui.fg, bold = true), Ui.text(ctx, "", 11f, Ui.fg3))
                    .also { r -> r.setPadding(dp(8), dp(4), dp(8), dp(2)); (r.getChildAt(0) as ImageView).layoutParams = Ui.lp(dp(14), dp(14)); (r.getChildAt(1) as ImageView).layoutParams = Ui.lp(dp(16), dp(16)).also { it.leftMargin = dp(2); it.rightMargin = dp(6) }; (r.getChildAt(3) as TextView).setPadding(dp(8), 0, 0, 0) }
                (row.getChildAt(0) as ImageView).setImageDrawable(Ui.icon(ctx, if (x in collapsed) R.drawable.ic_chevron_right else R.drawable.ic_chevron_down, Ui.fg3))
                (row.getChildAt(1) as ImageView).setImageDrawable(FileIcons.file(ctx, x.substringAfterLast('/')))
                (row.getChildAt(2) as TextView).text = x
                val n = (Lsp.diagnostics[x]?.count { it.severity <= 2 } ?: 0) + (if (checks.containsKey(x)) 1 else 0)
                (row.getChildAt(3) as TextView).text = n.toString()
                return row
            }
            val p = x as Problem
            val row = (convertView as? LinearLayout) ?: newRow()
            val icon = row.getChildAt(0) as ImageView
            val msg = row.getChildAt(1) as TextView
            val where = row.getChildAt(2) as TextView
            icon.setImageDrawable(Ui.icon(ctx, if (p.severity <= 1) R.drawable.ic_error else R.drawable.ic_problems, if (p.severity <= 1) Ui.red else Ui.yellow))
            msg.text = p.message.replace('\n', ' ')
            where.text = (if (p.source.isNotEmpty()) p.source + "  " else "") + "[" + (p.line + 1) + ":" + (p.col + 1) + "]"
            row.getChildAt(3).setOnClickListener { quickFix(p) }
            row.getChildAt(4).setOnClickListener { fixOne(p) }
            row.getChildAt(3).visibility = if (p.diag != null) View.VISIBLE else View.GONE
            return row
        }

        private fun newRow(): LinearLayout {
            val icon = ImageView(ctx)
            val msg = Ui.text(ctx, "", 12f, Ui.fg2)
            msg.isSingleLine = true; msg.ellipsize = TextUtils.TruncateAt.END
            val where = Ui.text(ctx, "", 11f, Ui.fg3)
            where.isSingleLine = true
            val quick = Ui.miniButton(ctx, R.drawable.ic_bulb, "Quick fix (the language server's fixes)") { }
            val ai = Ui.miniButton(ctx, R.drawable.ic_sparkle, "Fix with AI") { }
            val row = Ui.row(ctx, icon, msg, where, quick, ai)
            icon.layoutParams = Ui.lp(dp(14), dp(14)).also { it.rightMargin = dp(8) }
            msg.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            where.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(10); it.rightMargin = dp(6) }
            quick.layoutParams = Ui.lp(dp(24), dp(24))
            ai.layoutParams = Ui.lp(dp(24), dp(24)).also { it.leftMargin = dp(2) }
            row.setPadding(dp(38), dp(1), dp(8), dp(1))
            row.minimumHeight = dp(24)
            return row
        }
    }
}
