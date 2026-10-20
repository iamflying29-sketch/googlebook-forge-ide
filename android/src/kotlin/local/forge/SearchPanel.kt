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
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Search view (Ctrl Shift F), like Cursor's: search the whole folder as you type, with match case, whole word
 * and regular expression toggles, files to include and to exclude, results grouped by file with every match
 * highlighted, and replace: in one file or in all of them. Replace shows the count first and can be undone from
 * AI Changes, because the server keeps every file's earlier version as a checkpoint.
 */
class SearchPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = Ui.column(ctx)
    private fun dp(v: Int) = Ui.dp(ctx, v)

    private val query = Ui.input(ctx, "Search", mono = true)
    private val replace = Ui.input(ctx, "Replace", mono = true)
    private val include = Ui.input(ctx, "Files to include, for example *.kt, src/**", mono = true)
    private val exclude = Ui.input(ctx, "Files to exclude, for example build/**", mono = true)
    private var matchCase = false
    private var wholeWord = false
    private var regex = false
    private val status = Ui.text(ctx, "", 11.5f, Ui.fg3)
    private val list = ListView(ctx)
    private val adapter = Adapter()
    private val rows = ArrayList<Any>()   // String is a file header, Hit is a match line
    private val collapsed = HashSet<String>()
    private val dismissed = HashSet<String>()
    private val hitsByFile = LinkedHashMap<String, ArrayList<Hit>>()
    private var generation = 0
    private val searchTask = Runnable { search() }
    private val details: LinearLayout

    /** One matching line from the server: where the matches are ([spans]) and, with a replacement, the line as
     *  Replace would write it ([preview]), so the view shows exactly what Replace does. */
    class Hit(val path: String, val line: Int, val text: String, val spans: List<IntArray> = emptyList(), val reps: List<String>? = null)

    init {
        view.setBackgroundColor(Ui.panel)
        view.addView(Chrome.panelHeader(ctx, "Search",
            Ui.miniButton(ctx, R.drawable.ic_refresh, "Search again") { search() },
            Ui.miniButton(ctx, R.drawable.ic_collapse, "Collapse all") { collapsed.addAll(hitsByFile.keys); render() },
            Ui.miniButton(ctx, R.drawable.ic_expand, "Expand all") { collapsed.clear(); render() }))
        val caseT = toggle("Aa", "Match case") { matchCase = !matchCase; matchCase }
        val wordT = toggle("ab", "Match whole word") { wholeWord = !wholeWord; wholeWord }
        val regexT = toggle(".*", "Use regular expression") { regex = !regex; regex }
        val qRow = Ui.row(ctx, query, caseT, wordT, regexT)
        query.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        val replaceAll = Ui.miniButton(ctx, R.drawable.ic_replace_all, "Replace all (Ctrl Alt Enter)") { replaceAll(null) }
        val rRow = Ui.row(ctx, replace, replaceAll)
        replace.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        replaceAll.layoutParams = Ui.lp(dp(26), dp(26)).also { it.leftMargin = dp(4) }
        details = Ui.column(ctx, include, exclude)
        include.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(4) }
        exclude.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(4) }
        details.visibility = View.GONE
        val more = Ui.text(ctx, "Files to include or exclude", 11.5f, Ui.fg3)
        more.setOnClickListener { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        more.setPadding(0, dp(4), 0, 0)
        val box = Ui.column(ctx, qRow, rRow, more, details, status)
        rRow.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(4) }
        status.setPadding(0, dp(6), 0, dp(2))
        box.setPadding(dp(10), 0, dp(8), dp(6))
        view.addView(box, Ui.lp(Ui.match, Ui.wrap))
        view.addView(Ui.divider(ctx, false))
        list.adapter = adapter
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setOnItemClickListener { _, _, pos, _ ->
            when (val x = rows.getOrNull(pos)) {
                is String -> { if (!collapsed.add(x)) collapsed.remove(x); render() }
                is Hit -> host.openFileAt(x.path, x.line, x.spans.firstOrNull()?.get(0) ?: 0)
            }
        }
        view.addView(list, Ui.lp(Ui.match, 0, 1f))
        val live = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { Api.main.removeCallbacks(searchTask); Api.main.postDelayed(searchTask, 350) }
        }
        query.addTextChangedListener(live); include.addTextChangedListener(live); exclude.addTextChangedListener(live)
        replace.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            // What each match becomes comes from the server (the same engine as Replace), so the search runs again.
            override fun afterTextChanged(s: Editable?) { Api.main.removeCallbacks(searchTask); Api.main.postDelayed(searchTask, 350) }
        })
        val keys = View.OnKeyListener { v, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when {
                (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && ev.isCtrlPressed && ev.isAltPressed -> { replaceAll(null); true }
                code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (v === replace) replaceAll(null) else search(); true }
                else -> false
            }
        }
        for (e in listOf(query, replace, include, exclude)) { e.isSingleLine = true; e.setOnKeyListener(keys) }
        status.text = "Type to search every file of this folder."
    }

    /** Opens the view with a query (the editor's selection) or a folder to search in. */
    /** Test step: a search with files to include and a replacement typed in, as a person would type them. */
    fun testSearch(q: String, includeGlob: String, replacement: String) {
        replace.setText(replacement)
        prefill(q, includeGlob)
    }

    fun prefill(q: String, includeGlob: String) {
        if (includeGlob.isNotEmpty()) { include.setText(includeGlob); details.visibility = View.VISIBLE }
        if (q.isNotEmpty()) query.setText(q)
        query.requestFocus()
        query.selectAll()
        if (q.isNotEmpty() || includeGlob.isNotEmpty()) search()
    }

    private fun toggle(label: String, tip: String, flip: () -> Boolean): TextView {
        val t = Ui.text(ctx, label, 11.5f, Ui.fg3, mono = true, bold = true)
        t.gravity = Gravity.CENTER
        t.tooltipText = tip
        t.setPadding(dp(5), dp(3), dp(5), dp(3))
        t.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(3) }
        fun style(on: Boolean) { t.setTextColor(if (on) Ui.accent else Ui.fg3); t.background = if (on) Ui.rounded(Ui.accentSoft, 4, Ui.accent) else Ui.rounded(0, 4) }
        style(false)
        t.setOnClickListener { style(flip()); search() }
        return t
    }

    fun search() {
        val q = query.text.toString()
        val g = ++generation
        if (q.isEmpty()) { hitsByFile.clear(); render(); status.text = "Type to search every file of this folder."; return }
        status.setTextColor(Ui.fg3)
        status.text = "Searching…"
        val params = mapOf("q" to q, "path" to ".", "glob" to include.text.toString().trim(), "exclude" to exclude.text.toString().trim(),
            "regex" to if (regex) "1" else "0", "case" to if (matchCase) "1" else "0", "word" to if (wholeWord) "1" else "0", "max" to "2000",
            "replacement" to replace.text.toString())
        Api.async("search", { Api.get("/api/search", params, 40000) }) { r, e ->
            if (g != generation) return@async
            hitsByFile.clear()
            dismissed.clear()
            if (r == null) { status.setTextColor(Ui.red); status.text = "Search failed: " + (e?.message ?: "no answer"); render(); return@async }
            val arr = r.optJSONArray("hits") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sp = o.optJSONArray("spans")
                val spans = if (sp == null) emptyList() else (0 until sp.length()).mapNotNull { k -> sp.optJSONArray(k)?.let { intArrayOf(it.optInt(0), it.optInt(1)) } }
                val path = o.optString("path")
                val ra = o.optJSONArray("reps")
                val reps = if (ra == null) null else (0 until ra.length()).map { ra.optString(it) }
                hitsByFile.getOrPut(path) { ArrayList() }.add(Hit(path, o.optInt("line", 1), o.optString("text"), spans, reps))
            }
            render()
            val n = hitsByFile.values.sumOf { it.size }
            val total = r.optInt("total", n)
            status.text = when {
                n == 0 -> if (r.optBoolean("stopped_early")) "No results before the search ran out of time." else "No results."
                else -> "$total " + (if (total == 1) "result" else "results") + " in " + hitsByFile.size + " " + (if (hitsByFile.size == 1) "file" else "files") +
                    (if (total > n) ". The first $n are shown; narrow the search." else "") + (if (r.optBoolean("stopped_early")) ". The search ran out of time." else "")
            }
        }
    }

    private fun render() {
        rows.clear()
        for ((p, hits) in hitsByFile) {
            if (p in dismissed) continue
            rows.add(p)
            if (p !in collapsed) rows.addAll(hits)
        }
        adapter.notifyDataSetChanged()
    }

    /** Replaces in the saved files of the folder (all, or the files given) after showing how many matches change. */
    private fun replaceAll(only: List<String>?) {
        val q = query.text.toString()
        if (q.isEmpty()) { Ui.toast(ctx, "Type what to search for first."); return }
        val dirty = host.dirtyEditors()
        if (dirty > 0) {
            AlertDialog.Builder(ctx).setTitle("Unsaved files").setMessage("Replace works on the saved files. Save your $dirty unsaved " + (if (dirty == 1) "file" else "files") + " first?")
                .setPositiveButton("Save and continue") { _, _ -> host.saveAll { replaceAll(only) } }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val body = JSONObject().put("q", q).put("replacement", replace.text.toString()).put("regex", regex).put("case", matchCase).put("word", wholeWord)
            .put("glob", include.text.toString().trim()).put("exclude", exclude.text.toString().trim())
        if (only != null) body.put("paths", JSONArray(only))
        Api.async("replace-dry", { Api.post("/api/replace", JSONObject(body.toString()).put("dry_run", true), 120000) }) { r, e ->
            if (r == null) { Ui.toast(ctx, "Cannot replace: " + (e?.message ?: "")); return@async }
            val total = r.optInt("total")
            val files = r.optJSONArray("files") ?: JSONArray()
            if (total == 0) { Ui.toast(ctx, "No matches to replace."); return@async }
            val sb = StringBuilder()
            for (i in 0 until minOf(files.length(), 14)) { val f = files.getJSONObject(i); sb.append(f.optString("path")).append(" (").append(f.optInt("count")).append(")\n") }
            if (files.length() > 14) sb.append("and ").append(files.length() - 14).append(" more files\n")
            sb.append("\nYou can undo this from AI Changes.")
            AlertDialog.Builder(ctx).setTitle("Replace $total " + (if (total == 1) "match" else "matches") + " in " + files.length() + " " + (if (files.length() == 1) "file" else "files") + "?")
                .setMessage(sb.toString())
                .setPositiveButton("Replace") { _, _ ->
                    Api.async("replace", { Api.post("/api/replace", body, 120000) }) { r2, e2 ->
                        if (r2 == null) { Ui.toast(ctx, "Not replaced: " + (e2?.message ?: "")); return@async }
                        val done = r2.optJSONArray("files") ?: JSONArray()
                        for (i in 0 until done.length()) host.fileChanged(done.getJSONObject(i).optString("path"))
                        Ui.toast(ctx, "Replaced " + r2.optInt("total") + " in " + done.length() + " " + (if (done.length() == 1) "file" else "files") + ". Undo it from AI Changes.")
                        search()
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    /** A result line with its matches marked where the server found them. With text in Replace, each match is struck
     *  through in red and followed by what Replace will write there, in green, as in Cursor's search view. */
    private fun highlighted(h: Hit): CharSequence {
        val text = h.text
        val t = text.trimStart()
        val cut = text.length - t.length
        val sb = SpannableStringBuilder(t)
        val reps = if (replace.text.isNotEmpty()) h.reps else null
        // From the last match to the first, so the inserted replacements do not move the matches still to mark.
        for (i in h.spans.indices.reversed()) {
            val a = h.spans[i][0] - cut; val b = h.spans[i][1] - cut
            if (a < 0 || b > sb.length || b <= a) continue
            if (reps != null) {
                sb.setSpan(BackgroundColorSpan(0x55ff5c6c), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.StrikethroughSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val r = reps.getOrNull(i) ?: continue
                sb.insert(b, r)
                sb.setSpan(BackgroundColorSpan(0x4422c55e), b, b + r.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Ui.green), b, b + r.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.setSpan(BackgroundColorSpan(0x55ff8a3d), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Ui.fg), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
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
                val row = (convertView as? LinearLayout) ?: fileRow()
                (row.getChildAt(0) as ImageView).setImageDrawable(Ui.icon(ctx, if (x in collapsed) R.drawable.ic_chevron_right else R.drawable.ic_chevron_down, Ui.fg3))
                (row.getChildAt(1) as ImageView).setImageDrawable(FileIcons.file(ctx, x.substringAfterLast('/')))
                (row.getChildAt(2) as TextView).text = x.substringAfterLast('/')
                (row.getChildAt(3) as TextView).text = if (x.contains('/')) x.substringBeforeLast('/') else ""
                (row.getChildAt(4) as TextView).text = (hitsByFile[x]?.size ?: 0).toString()
                row.getChildAt(5).setOnClickListener { replaceAll(listOf(x)) }
                row.getChildAt(5).visibility = if (replace.text.isNotEmpty()) View.VISIBLE else View.GONE
                row.getChildAt(6).setOnClickListener { dismissed.add(x); render() }
                return row
            }
            val h = x as Hit
            val t = (convertView as? TextView) ?: Ui.text(ctx, "", 12f, Ui.fg2, mono = true).also {
                it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END; it.setPadding(dp(34), dp(2), dp(8), dp(2))
            }
            t.text = highlighted(h)
            t.tooltipText = h.path + ":" + h.line
            return t
        }
        private fun fileRow(): LinearLayout {
            val name = Ui.text(ctx, "", 12f, Ui.fg)
            val dir = Ui.text(ctx, "", 11f, Ui.fg3)
            dir.isSingleLine = true; dir.ellipsize = TextUtils.TruncateAt.START
            val count = Ui.text(ctx, "", 10.5f, Ui.fg2)
            count.background = Ui.rounded(Ui.bg4, 8)
            count.setPadding(dp(6), 0, dp(6), 0)
            val rep = Ui.miniButton(ctx, R.drawable.ic_replace_all, "Replace in this file") { }
            val dismiss = Ui.miniButton(ctx, R.drawable.ic_close, "Hide this file's results") { }
            val row = Ui.row(ctx, ImageView(ctx), ImageView(ctx), name, dir, count, rep, dismiss)
            row.getChildAt(0).layoutParams = Ui.lp(dp(14), dp(14))
            row.getChildAt(1).layoutParams = Ui.lp(dp(16), dp(16)).also { it.leftMargin = dp(2); it.rightMargin = dp(6) }
            dir.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = dp(6) }
            count.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) }
            rep.layoutParams = Ui.lp(dp(22), dp(22)).also { it.leftMargin = dp(4) }
            dismiss.layoutParams = Ui.lp(dp(22), dp(22))
            row.setPadding(dp(6), dp(3), dp(4), dp(3))
            return row
        }
    }
}
