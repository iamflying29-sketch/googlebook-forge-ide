package local.forge

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Docs view, ported from the docs section of the old page's settings (extras.js): the documentation sites the
 * Forge server has indexed for @Docs and the agent's docs_search tool, a form that adds a site, "Index again" and
 * Remove per site, and a filter box for the list. While a site is being crawled the list is reloaded every four
 * seconds, as the page did, until the view leaves the window.
 *
 * The server of 2026-10-19 has no endpoint that searches the indexed text for the user (the search lives in the
 * agent's docs_search tool only), so this view has no content search; the filter box narrows the list by name
 * and address.
 */
class DocsPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val body = Ui.column(ctx)
    private val filter = Ui.input(ctx, "Filter the indexed docs by name or address")
    private val url = Ui.input(ctx, "https://docs.example.com/guide/")
    private val name = Ui.input(ctx, "Name (optional)")
    private val count = SideKit.section(ctx, "Indexed docs")
    private val list = Ui.column(ctx)
    private var docs = JSONArray()
    private var error: String? = null
    private var attached = false
    private val poll = object : Runnable { override fun run() { if (attached && crawling()) { load(); Api.main.postDelayed(this, 4000) } } }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(SideKit.header(ctx, "Docs", Ui.miniButton(ctx, R.drawable.ic_refresh, "Refresh") { load() }))
        view.addView(Ui.divider(ctx, false))
        view.addView(Ui.scroll(ctx, body), Ui.lp(Ui.match, 0, 1f))
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attached = true; load() }
            override fun onViewDetachedFromWindow(v: View) { attached = false; Api.main.removeCallbacks(poll) }
        })
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        }
        filter.addTextChangedListener(watcher)
        buildStatic()
        render()
        load()
    }

    private fun crawling(): Boolean {
        for (i in 0 until docs.length()) { val st = docs.optJSONObject(i)?.optString("status") ?: continue; if (st != "ready" && st != "error") return true }
        return false
    }

    /** GET /api/docs: {"docs": [{"name", "url", "pages", "chunks", "updated", "status", "error"?}]}. */
    fun load() {
        Api.async("docs", { Api.get("/api/docs") }) { r, e ->
            if (r == null) { error = e?.message ?: "Could not list the docs."; render(); return@async }
            error = null
            docs = r.optJSONArray("docs") ?: JSONArray()
            render()
            Api.main.removeCallbacks(poll)
            if (attached && crawling()) Api.main.postDelayed(poll, 4000)
        }
    }

    /** The hint, the add form and the filter are built once; only the list below them is rebuilt. */
    private fun buildStatic() {
        val hint = Ui.text(ctx, "Index a documentation site so you can mention it with @docs and the agent can search it. Forge reads up to 40 pages under the address you give, once, and keeps them on this computer.", 12f, Ui.fg3)
        hint.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 4))
        body.addView(hint, Ui.lp(Ui.match, Ui.wrap))
        val form = Ui.column(ctx)
        form.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        form.addView(url, Ui.lp(Ui.match, Ui.wrap))
        val nLp = Ui.lp(Ui.match, Ui.wrap); nLp.topMargin = Ui.dp(ctx, 6)
        form.addView(name, nLp)
        val add = Ui.button(ctx, "Add", primary = true) { add() }
        val aLp = Ui.lp(Ui.wrap, Ui.wrap); aLp.topMargin = Ui.dp(ctx, 6)
        form.addView(add, aLp)
        body.addView(form, Ui.lp(Ui.match, Ui.wrap))
        body.addView(Ui.divider(ctx, false))
        val fLp = Ui.lp(Ui.match, Ui.wrap); fLp.setMargins(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 4))
        filter.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6))
        body.addView(filter, fLp)
        body.addView(count)
        body.addView(list, Ui.lp(Ui.match, Ui.wrap))
    }

    private fun render() {
        list.removeAllViews()
        count.text = ("Indexed docs (" + docs.length() + ")").uppercase()
        val err = error
        if (err != null) { list.addView(SideKit.muted(ctx, err)); return }
        val q = filter.text.toString().trim().lowercase()
        var shown = 0
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            if (q.isNotEmpty() && !d.optString("name").lowercase().contains(q) && !d.optString("url").lowercase().contains(q)) continue
            shown++
            list.addView(docRow(d))
        }
        if (shown == 0) list.addView(SideKit.muted(ctx, if (docs.length() == 0) "No docs indexed yet." else "No indexed docs match \"$q\"."))
    }

    /** One indexed site: its name and page count or crawl status, with "Index again" (when ready) and Remove. */
    private fun docRow(d: JSONObject): View {
        val dn = d.optString("name"); val du = d.optString("url"); val status = d.optString("status")
        val pages = d.optInt("pages")
        val row = SideKit.listRow(ctx)
        row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6))
        val col = Ui.column(ctx)
        val t = Ui.text(ctx, dn, 12.5f, Ui.fg)
        t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t, Ui.lp(Ui.match, Ui.wrap))
        val state = when (status) {
            "ready" -> "$pages pages"
            else -> status + (if (pages > 0) " ($pages pages so far)" else "")
        } + (if (d.optString("error").isNotEmpty() && d.optString("error") != "null") "  ·  " + d.optString("error") else "")
        val stateColor = when (status) { "ready" -> Ui.fg3; "error" -> Ui.red; else -> Ui.yellow }
        val m = Ui.text(ctx, state, 11f, stateColor)
        m.maxLines = 2; m.ellipsize = TextUtils.TruncateAt.END
        col.addView(m, Ui.lp(Ui.match, Ui.wrap))
        if (du.isNotEmpty() && du != "null") {
            val u = Ui.text(ctx, du, 11f, Ui.fg3)
            u.maxLines = 1; u.ellipsize = TextUtils.TruncateAt.MIDDLE
            col.addView(u, Ui.lp(Ui.match, Ui.wrap))
        }
        row.addView(col, Ui.lp(0, Ui.wrap, 1f))
        if (status == "ready" && du.isNotEmpty() && du != "null") row.addView(Ui.miniButton(ctx, R.drawable.ic_refresh, "Index again") { post(JSONObject().put("action", "add").put("name", dn).put("url", du), "Indexing $dn again.") })
        row.addView(Ui.miniButton(ctx, R.drawable.ic_trash, "Remove (moved to Forge's trash folder)") {
            SideKit.confirm(ctx, "Remove docs?", "Remove the $dn docs? Forge moves the index to its trash folder.", "Remove") {
                post(JSONObject().put("action", "remove").put("name", dn), "Removed $dn.")
            }
        })
        row.tooltipText = du
        row.setOnClickListener { host.copyToClipboard("@docs $dn"); Ui.toast(ctx, "\"@docs $dn\" is on the clipboard. Paste it into the chat to use these docs.") }
        return row
    }

    /** Add: POST /api/docs {"action": "add", "url", "name"}; the name defaults to the site's host name. */
    private fun add() {
        val u = url.text.toString().trim()
        if (u.isEmpty()) { Ui.toast(ctx, "Enter the address of the documentation first."); return }
        if (!u.startsWith("http://") && !u.startsWith("https://")) { Ui.toast(ctx, "The documentation address must start with http:// or https://"); return }
        val n = name.text.toString().trim().ifEmpty { try { java.net.URL(u).host } catch (e: Exception) { "" } }
        post(JSONObject().put("action", "add").put("url", u).put("name", n), "Indexing started. The list updates while Forge reads the pages.") {
            url.setText(""); name.setText("")
        }
    }

    /** Every POST /api/docs answers with the new list: {"docs": [...]}. */
    private fun post(bodyJson: JSONObject, doneMessage: String, then: (() -> Unit)? = null) {
        Api.async("docs-post", { Api.post("/api/docs", bodyJson) }) { r, e ->
            if (r == null) { Ui.toast(ctx, e?.message ?: "The docs request failed."); return@async }
            error = null
            docs = r.optJSONArray("docs") ?: JSONArray()
            then?.invoke()
            Ui.toast(ctx, doneMessage)
            render()
            Api.main.removeCallbacks(poll)
            if (attached && crawling()) Api.main.postDelayed(poll, 4000)
        }
    }
}
