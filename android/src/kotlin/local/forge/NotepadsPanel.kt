package local.forge

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Notepads view, ported from the old page's extras.js: reusable notes (conventions, API notes, a plan) kept by
 * the Forge server in one list. The list shows every notepad; the selected one opens in an editor below the list
 * with its name and text. Edits are saved when the name or the text box loses focus, when another notepad is
 * selected, and one second after the last keystroke as a safety net (the page saved half a second after each edit).
 * "Add to chat" attaches the notepad to the chat as context, the same as picking it with @ in the chat input.
 */
class NotepadsPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val body = Ui.column(ctx)
    private var pads = JSONArray()
    private var active = -1
    private var nameBox: EditText? = null
    private var textBox: EditText? = null
    /** The name shown in the selected notepad's list row, updated while the user types. */
    private var activeLabel: TextView? = null
    private var dirty = false
    private val saveLater = Runnable { saveIfDirty() }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(SideKit.header(ctx, "Notepads",
            Ui.miniButton(ctx, R.drawable.ic_plus, "New notepad") { newPad() },
            Ui.miniButton(ctx, R.drawable.ic_refresh, "Reload") { load() }))
        view.addView(Ui.divider(ctx, false))
        view.addView(Ui.scroll(ctx, body), Ui.lp(Ui.match, 0, 1f))
        body.addView(SideKit.muted(ctx, "Loading the notepads."))
        // A pending edit is saved when the view leaves the window (the side view was closed).
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { saveIfDirty() }
        })
        load()
    }

    /** GET /api/notepads: {"notepads": [{"name": ..., "text": ...}, ...]}. */
    fun load() {
        Api.async("notepads", { Api.get("/api/notepads") }) { r, e ->
            if (r == null) { body.removeAllViews(); body.addView(SideKit.muted(ctx, e?.message ?: "Could not load the notepads.")); return@async }
            pads = r.optJSONArray("notepads") ?: JSONArray()
            if (active >= pads.length()) active = -1
            render()
        }
    }

    /** POST /api/notepads with the whole list: {"notepads": [...]}; the server keeps the first 200. */
    private fun save(then: (() -> Unit)? = null) {
        Api.main.removeCallbacks(saveLater)
        dirty = false
        val copy = JSONArray(pads.toString())
        Api.async("notepads-save", { Api.post("/api/notepads", JSONObject().put("notepads", copy)) }) { r, e ->
            if (r == null) Ui.toast(ctx, "The notepads were not saved: " + (e?.message ?: ""))
            then?.invoke()
        }
    }

    private fun saveIfDirty() { if (dirty) save() }

    /** Takes the editor's current name and text into the active notepad; true when something changed. */
    private fun pullEditor(): Boolean {
        val p = if (active in 0 until pads.length()) pads.optJSONObject(active) else null
        val n = nameBox; val t = textBox
        if (p == null || n == null || t == null) return false
        val name = n.text.toString().trim().ifEmpty { "Untitled" }
        val text = t.text.toString()
        if (name == p.optString("name") && text == p.optString("text")) return false
        p.put("name", name); p.put("text", text)
        return true
    }

    private fun edited() {
        if (!pullEditor()) return
        dirty = true
        // The list row shows the new name at once; the list is not rebuilt, so the text box keeps the focus.
        activeLabel?.text = pads.optJSONObject(active)?.optString("name")?.ifEmpty { "Untitled" } ?: "Untitled"
        Api.main.removeCallbacks(saveLater)
        Api.main.postDelayed(saveLater, 1000)
    }

    private fun render() {
        nameBox = null; textBox = null; activeLabel = null
        body.removeAllViews()
        if (pads.length() == 0) body.addView(SideKit.muted(ctx, "No notepads yet. A notepad is reusable context (conventions, API notes, a plan) you attach to a chat with @notepad."))
        for (i in 0 until pads.length()) {
            val p = pads.optJSONObject(i) ?: continue
            val row = SideKit.listRow(ctx, selected = i == active)
            row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 5), Ui.dp(ctx, 6), Ui.dp(ctx, 5))
            val name = Ui.text(ctx, p.optString("name").ifEmpty { "Untitled" }, 12.5f, Ui.fg)
            name.maxLines = 1; name.ellipsize = TextUtils.TruncateAt.END
            row.addView(name, Ui.lp(0, Ui.wrap, 1f))
            if (i == active) activeLabel = name
            row.addView(Ui.miniButton(ctx, R.drawable.ic_trash, "Delete notepad") { deletePad(i) })
            row.setOnClickListener { selectPad(i) }
            body.addView(row)
        }
        if (active in 0 until pads.length()) body.addView(buildEditor(pads.getJSONObject(active)))
    }

    private fun buildEditor(p: JSONObject): View {
        val col = Ui.column(ctx)
        col.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 12))
        col.layoutParams = Ui.lp(Ui.match, Ui.wrap)
        val name = Ui.input(ctx, "Name")
        name.setText(p.optString("name"))
        val text = Ui.input(ctx, "Notes the model should know when you attach this notepad", mono = true, multiline = true)
        text.setText(p.optString("text"))
        text.minHeight = Ui.dp(ctx, 180)
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { edited() }
        }
        name.addTextChangedListener(watcher); text.addTextChangedListener(watcher)
        val onFocus = View.OnFocusChangeListener { _, has -> if (!has) { pullEditor().let { if (it) dirty = true }; saveIfDirty() } }
        name.onFocusChangeListener = onFocus; text.onFocusChangeListener = onFocus
        nameBox = name; textBox = text
        col.addView(name, Ui.lp(Ui.match, Ui.wrap))
        val tLp = Ui.lp(Ui.match, Ui.wrap); tLp.topMargin = Ui.dp(ctx, 6)
        col.addView(text, tLp)
        val add = Ui.button(ctx, "Add to chat") { addToChat() }
        add.tooltipText = "Attaches this notepad to the chat as context, the same as picking it with @"
        val aLp = Ui.lp(Ui.wrap, Ui.wrap); aLp.topMargin = Ui.dp(ctx, 6)
        col.addView(add, aLp)
        return col
    }

    private fun selectPad(i: Int) {
        if (i == active) return
        if (pullEditor()) dirty = true
        saveIfDirty()
        active = i
        render()
    }

    private fun newPad() {
        if (pullEditor()) dirty = true
        pads.put(JSONObject().put("name", "Notepad " + (pads.length() + 1)).put("text", ""))
        active = pads.length() - 1
        save()
        render()
        nameBox?.requestFocus(); nameBox?.selectAll()
    }

    private fun deletePad(i: Int) {
        val p = pads.optJSONObject(i) ?: return
        SideKit.confirm(ctx, "Delete notepad?", "Delete the notepad \"" + p.optString("name") + "\"?", "Delete") {
            val keep = JSONArray()
            for (k in 0 until pads.length()) if (k != i) keep.put(pads.get(k))
            pads = keep
            active = -1
            nameBox = null; textBox = null
            save()
            render()
        }
    }

    /** Attaches the notepad to the chat as context (the model receives its text with the next message). */
    private fun addToChat() {
        val p = (if (active in 0 until pads.length()) pads.optJSONObject(active) else null) ?: return
        if (pullEditor()) dirty = true
        saveIfDirty()
        val name = p.optString("name").ifEmpty { "Untitled" }
        host.addToChat(JSONObject().put("kind", "text").put("title", "Notepad: $name").put("text", p.optString("text")).put("name", "notepad: $name"))
        host.flashStatus("The notepad \"$name\" goes with your next message.")
    }
}
