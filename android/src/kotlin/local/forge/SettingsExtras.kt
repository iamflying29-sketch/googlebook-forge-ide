package local.forge

import android.app.Dialog
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.DateFormat
import java.util.Date

/**
 * The sections the web page appended under its settings rows (extras.js, renderSettingsExtras), built from native
 * views: the codebase index card, the custom modes, the command allow and deny lists, the memories, the docs
 * sources, the hooks, the rules for the AI, the MCP servers and the vision status card. One instance is built per
 * rendering of the form; [stop] is called when the form is rebuilt so that late replies are dropped.
 *
 * Endpoints, as the server (forge/server.py) answers them:
 *  - GET /api/index/status {"available", "enabled", "status": {"state", "done", "total", "chunks", "error", "updated"} | null, "server_running"};
 *    POST /api/index {"action": "start" | "pause"} -> {"status"} (the server knows no other action).
 *  - custom_modes, command_allowlist and command_denylist are settings keys, saved through the activity.
 *  - GET /api/memories {"global": [{"id", "text", "created"}], "project": [...]}; POST /api/memories {"action": "add",
 *    "text", "scope"} | {"action": "update", "id", "text"} | {"action": "remove", "id"} -> the same list.
 *  - GET /api/docs {"docs": [{"name", "url", "pages", "chunks", "updated", "status", "error"}]}; POST /api/docs
 *    {"action": "add", "url", "name"} | {"action": "remove", "name"} -> {"docs"}.
 *  - GET /api/hooks {"global": text, "project": text, "events": [names]}; POST /api/hooks {"scope", "config": text}.
 *  - GET /api/rules {"global": text, "project": text, "project_path"}; POST /api/rules {"global"?: text, "project"?: text}.
 *  - GET /api/mcp {"servers": [{"name", "running", "tools": [names], "error", "command": [words]}], "config_path", "config": text};
 *    POST /api/mcp/config {"config": text} -> {"ok", "servers"}; POST /api/mcp/connect {} -> {"servers"}.
 *  - GET /api/vision/status {"app_connected", "app": {"vstate", "vtier", "vdetail", "host_mem_mb"}, "wanted",
 *    "tiers_available": [names], "ocr"}; POST /api/vision/stop {} -> {"ok"}.
 */
internal class SettingsExtras(private val a: SettingsActivity, private val scope: String) {
    private var alive = true
    fun stop() { alive = false }

    private fun dp(v: Int) = Ui.dp(a, v)
    private fun hint(s: String): TextView = Ui.text(a, s, 11f, Ui.fg3)

    /** Under Folder: where a card's list comes from, "(from User)" or this folder's own list with a Reset. */
    private fun folderNote(key: String, label: String): View? {
        if (scope != "folder") return null
        if (!a.hasFolderOverride(key)) return Ui.text(a, "(from User; a change here makes this folder's own list)", 11f, Ui.fg3)
        val reset = Ui.text(a, "This folder has its own list. Reset to the User list", 11f, Ui.accent2)
        reset.isClickable = true
        reset.setOnClickListener { a.resetFolderCard(key, label) }
        return reset
    }

    /** Folder trust (Folder tab): whether this folder's own settings and hooks may loosen the safety settings. */
    private fun trustCard(): View {
        val state = Ui.text(a, "Checking the folder.", 12.5f, Ui.fg)
        val held = Ui.text(a, "", 11.5f, Ui.fg2)
        val button = Ui.button(a, "Trust this folder") { }
        val card = Ui.card(a, hint("As in Cursor and VS Code: a folder's own settings may make Forge safer, but they may turn on Auto-run, allow commands, turn off a protection or run hooks only once you trust the folder."),
            state, held, Ui.row(a, button).also { it.setPadding(0, dp(6), 0, 0) })
        fun show(r: JSONObject?) {
            if (!alive) return
            val t = r?.optJSONObject("trust")
            if (t == null) { state.text = "Could not ask the Forge server."; return }
            val trusted = t.optBoolean("trusted")
            val wants = t.optJSONArray("held_back")
            state.text = if (trusted) "This folder is trusted: its own settings and hooks apply." else "This folder is not trusted."
            held.text = if (!trusted && wants != null && wants.length() > 0) "Held back until you trust it:\n" + (0 until wants.length()).joinToString("\n") { "• " + wants.optString(it) }
                else if (!trusted) "Its own settings ask for nothing that needs trust." else ""
            button.text = if (trusted) "Take the trust back" else "Trust this folder"
            button.setOnClickListener {
                Api.async("trust", { Api.post("/api/trust", JSONObject().put("trusted", !trusted)) }) { r2, e -> if (r2 == null) fail("Not changed", e) else { show(r2); ForgeSettings.refresh(Api.folder) } }
            }
        }
        Api.async("settings", { Api.get("/api/settings") }) { r, _ -> show(r) }
        return card
    }
    private fun label(s: String): TextView { val t = Ui.text(a, s, 12.5f, Ui.fg2); t.setPadding(0, dp(8), 0, dp(2)); return t }
    private fun fail(what: String, e: Exception?) = Ui.toast(a, what + ": " + (e?.message ?: "unknown error"))

    fun build(host: LinearLayout) {
        if (scope == "folder") {
            host.addView(a.groupHeader("Folder trust"))
            host.addView(trustCard())
        }
        host.addView(a.groupHeader("Codebase index"))
        host.addView(indexCard())
        host.addView(a.groupHeader("Custom modes"))
        host.addView(customModesCard())
        host.addView(a.groupHeader("Commands the agent may run"))
        host.addView(StringList("command_allowlist", "Allowlist (run without asking)",
            "A command runs at once, even with Auto-run off, when every part of it (the commands joined with &&, ;, | and the like) starts with one of these. A command with $( ) or backticks never matches. Destructive and denylisted commands still ask." +
                (if (scope == "folder") " A folder's own allowlist applies only once you trust the folder." else ""),
            "None. Commands wait for your approval unless Auto-run is on.").card)
        host.addView(StringList("command_denylist", "Denylist (never run without asking)",
            "As in Cursor: a command that contains one of these always asks for your approval, even with Auto-run on or an allowlist match." +
                (if (scope == "folder") " A folder's denylist adds to yours; it never removes entries." else ""),
            "None.").card)
        if (scope != "user") return
        host.addView(a.groupHeader("Memories"))
        host.addView(memoriesCard())
        host.addView(a.groupHeader("Docs (@docs)"))
        host.addView(docsCard())
        host.addView(a.groupHeader("Hooks"))
        host.addView(hooksCard())
        host.addView(a.groupHeader("Rules for the AI"))
        host.addView(rulesCard())
        host.addView(a.groupHeader("MCP servers"))
        host.addView(mcpCard())
        host.addView(a.groupHeader("Vision model"))
        host.addView(visionCard())
    }

    /** Called every 5 seconds while the page is shown: the cards whose state moves on their own are refreshed. */
    fun tick(n: Int) {
        if (!alive) return
        if (indexState == "indexing" || indexState.startsWith("waiting")) refreshIndex()
        if (visionInfo != null) refreshVision()
        if (docsList != null && (docsPending || n % 4 == 0)) refreshDocs()
    }

    // ------------------------------------------------------------------ codebase index
    private var indexInfo: TextView? = null
    private var indexState = ""
    private var indexBusy = false

    private fun indexCard(): View {
        val c = a
        val info = Ui.text(c, "Checking the index...", 11.5f, Ui.fg2)
        info.setPadding(0, dp(4), 0, 0)
        indexInfo = info
        val buttons = actionRow(c,
            Ui.button(c, "Index now", primary = true) { indexAction("start") },
            Ui.button(c, "Pause") { indexAction("pause") },
            Ui.button(c, "Refresh") { refreshIndex() })
        val card = Ui.card(c, Ui.text(c, "Status for this folder", 12.5f, Ui.fg2), info, buttons,
            hint("The index lives on the Forge server, one per folder, and is used by codebase search and @codebase. Index now adds new and changed files; Pause stops the worker until the next Index now. The server offers no action that throws the index away and starts over.").also { it.setPadding(0, dp(8), 0, 0) })
        refreshIndex()
        return card
    }

    private fun indexAction(action: String) {
        Api.async("index-$action", { Api.post("/api/index", JSONObject().put("action", action)) }) { r, e ->
            if (!alive) return@async
            if (r == null) { fail("Index", e); return@async }
            Ui.toast(a, if (action == "pause") "Indexing paused" else "Indexing started")
            refreshIndex()
        }
    }

    private fun refreshIndex() {
        if (indexBusy) return
        indexBusy = true
        Api.async("index-status", { Api.get("/api/index/status") }) { r, e ->
            indexBusy = false
            if (!alive) return@async
            val info = indexInfo ?: return@async
            if (r == null) { info.text = "Could not read the index status: " + (e?.message ?: "unknown error"); info.setTextColor(Ui.red); return@async }
            info.setTextColor(Ui.fg2)
            val st = r.optJSONObject("status")
            indexState = st?.optString("state") ?: ""
            val sb = StringBuilder()
            when {
                !r.optBoolean("available") -> sb.append("The embedding model is not installed on the Forge server.")
                !r.optBoolean("enabled") -> sb.append("Turned off (see Codebase index under Agent behavior above).")
                st == null -> sb.append("Not started.")
                else -> {
                    sb.append("State: ").append(indexState)
                    if (indexState == "indexing") sb.append(" (").append(st.optInt("done")).append(" of ").append(st.optInt("total")).append(" new chunks)")
                    sb.append("\nChunks indexed: ").append(st.optInt("chunks"))
                    val updated = st.optDouble("updated", 0.0)
                    if (updated > 0) sb.append("\nLast update: ").append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date((updated * 1000).toLong())))
                    if (!st.isNull("error") && st.optString("error").isNotEmpty()) sb.append("\nNote: ").append(st.optString("error"))
                }
            }
            sb.append("\nEmbedding server: ").append(if (r.optBoolean("server_running")) "running" else "stopped (starts when needed)")
            info.text = sb
        }
    }

    // ------------------------------------------------------------------ custom modes
    private val modes = ArrayList<JSONObject>()
    private var modesBox: LinearLayout? = null

    private fun customModesCard(): View {
        val c = a
        modes.clear()
        (a.valueOf("custom_modes") as? JSONArray)?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { modes.add(JSONObject(it.toString())) } }
        val box = Ui.column(c)
        modesBox = box
        val card = Ui.card(c, hint("Your own chat modes next to Agent, Ask and Plan, like in Cursor. Each mode adds its instructions to every message you send in it, and can be limited to read-only tools or run commands without asking. The chat's mode picker lists them by name." +
            (if (scope == "folder") " Under Folder the list is saved for this folder only." else "")), box)
        folderNote("custom_modes", "Custom modes")?.let { card.addView(it, 1) }
        drawModes()
        return card
    }

    private fun drawModes() {
        val c = a
        val box = modesBox ?: return
        box.removeAllViews()
        if (modes.isEmpty()) box.addView(emptyNote(c, "No custom modes yet."))
        for ((i, m) in modes.withIndex()) {
            val name = Ui.input(c, "Name, for example Reviewer")
            name.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            name.setText(m.optString("name"))
            commitOn(name, true) { val v = name.text.toString().trim(); if (v != m.optString("name")) { m.put("name", v); saveModes() } }
            val tools = fixedPicker(c, listOf("All tools", "Read-only tools"), if (m.optString("tools") == "read-only") "Read-only tools" else "All tools") { chosen ->
                m.put("tools", if (chosen == "Read-only tools") "read-only" else "all"); saveModes()
            }
            tools.layoutParams = Ui.lp(dp(170), Ui.wrap).also { it.leftMargin = dp(6) }
            val del = trashButton(c, "Delete this mode") {
                confirmDialog(a, "Delete this mode", "Delete the mode \"" + m.optString("name").ifEmpty { "unnamed" } + "\"?", "Delete") { modes.removeAt(i); saveModes(); drawModes() }
            }
            (del.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
            val top = Ui.row(c, name, tools, del)
            val instr = Ui.input(c, "Instructions for this mode", multiline = true)
            instr.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) }
            instr.minLines = 3
            instr.setText(m.optString("instructions"))
            commitOn(instr, false) { val v = instr.text.toString(); if (v != m.optString("instructions")) { m.put("instructions", v); saveModes() } }
            val auto = Toggle(c, m.optBoolean("auto_run"))
            auto.onChange = { on -> m.put("auto_run", on); saveModes() }
            val autoLabel = Ui.text(c, "Run commands without asking", 12f, Ui.fg2)
            autoLabel.setPadding(dp(8), 0, 0, 0)
            val autoRow = Ui.row(c, auto, autoLabel)
            autoRow.setPadding(0, dp(6), 0, 0)
            val card = Ui.column(c, top, instr, autoRow)
            card.background = Ui.rounded(Ui.bg3, 8, Ui.border)
            card.setPadding(dp(8), dp(8), dp(8), dp(8))
            card.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) }
            box.addView(card)
        }
        box.addView(actionRow(c, Ui.button(c, "Add a mode") {
            modes.add(JSONObject().put("name", "Mode " + (modes.size + 1)).put("instructions", "").put("tools", "all").put("auto_run", false))
            saveModes(); drawModes()
        }))
    }

    /** Saves the modes that have a name, as the web page did; a nameless mode stays on the page until it gets one. */
    private fun saveModes() {
        val arr = JSONArray()
        for (m in modes) if (m.optString("name").trim().isNotEmpty()) arr.put(m)
        a.saveSetting("custom_modes", arr, "Custom modes") { }
    }

    // ------------------------------------------------------------------ command allow and deny lists
    /** A list setting shown as rows (tap one to edit it, the trash button removes it) with a field to add one. */
    private inner class StringList(val key: String, val title: String, val note: String, val emptyText: String) {
        val card: LinearLayout
        private val list = Ui.column(a)
        private val items = ArrayList(a.stringListOf(key))

        init {
            val c = a
            val add = Ui.input(c, "One command prefix, for example: npm test", mono = true)
            add.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            val addBtn = Ui.button(c, "Add") { addFrom(add) }
            onEnter(add) { addFrom(add) }
            addBtn.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { p -> p.leftMargin = dp(6) }
            val addRow = Ui.row(c, add, addBtn)
            addRow.setPadding(0, dp(6), 0, 0)
            card = Ui.card(c, Ui.text(c, title, 12.5f, Ui.fg2), hint(note).also { it.setPadding(0, 0, 0, dp(4)) }, list, addRow)
            folderNote(key, title)?.let { card.addView(it, 1) }
            card.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) }
            draw()
        }

        private fun addFrom(field: EditText) {
            val v = field.text.toString().trim()
            if (v.isEmpty()) return
            if (items.contains(v)) { Ui.toast(a, "\"$v\" is already in the list"); return }
            items.add(v); field.setText(""); save(); draw()
        }

        private fun draw() {
            val c = a
            list.removeAllViews()
            if (items.isEmpty()) list.addView(emptyNote(c, emptyText))
            for ((i, s) in items.withIndex()) {
                list.addView(itemRow(c, s, true, {
                    textDialog(a, "Edit the command prefix", s, hint = "npm test", multiline = false) { v ->
                        val t = v.trim()
                        if (t.isEmpty()) items.removeAt(i) else items[i] = t
                        save(); draw()
                    }
                }, trashButton(c, "Remove this prefix") { items.removeAt(i); save(); draw() }))
            }
        }

        private fun save() {
            a.saveSetting(key, JSONArray(items), title) { ok -> if (!ok) { items.clear(); items.addAll(a.stringListOf(key)); draw() } }
        }
    }

    // ------------------------------------------------------------------ memories
    private var memBox: LinearLayout? = null

    private fun memoriesCard(): View {
        val c = a
        val box = Ui.column(c)
        memBox = box
        box.addView(emptyNote(c, "Loading the memories..."))
        val card = Ui.card(c, hint("Facts and preferences Forge remembers between chats. The agent offers to save one when you tell it something lasting, and you approve each one. They are added to every chat (global) or every chat in this folder (project). Tap a memory to edit it."), box)
        Api.async("memories", { Api.get("/api/memories") }) { r, e ->
            if (!alive) return@async
            if (r == null) { box.removeAllViews(); box.addView(emptyNote(c, "Could not load the memories: " + (e?.message ?: "unknown error"))) } else drawMemories(r)
        }
        return card
    }

    private fun drawMemories(m: JSONObject) {
        val c = a
        val box = memBox ?: return
        box.removeAllViews()
        for (sc in listOf("global", "project")) {
            box.addView(label(if (sc == "global") "Global (every folder)" else "This project"))
            val items = m.optJSONArray(sc)
            if (items == null || items.length() == 0) { box.addView(emptyNote(c, "None yet.")); continue }
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("id"); val text = item.optString("text")
                box.addView(itemRow(c, text, false, {
                    textDialog(a, "Edit the memory", text, note = "Up to 1,000 characters are kept.") { v ->
                        if (v.trim().isEmpty()) { Ui.toast(a, "A memory cannot be empty; use the trash button to forget it"); return@textDialog }
                        memoryPost(JSONObject().put("action", "update").put("id", id).put("text", v))
                    }
                }, trashButton(c, "Forget this memory") {
                    confirmDialog(a, "Forget this memory?", text, "Forget") { memoryPost(JSONObject().put("action", "remove").put("id", id)) }
                }))
            }
        }
        val add = Ui.input(c, "Add a memory, for example: I prefer tabs over spaces")
        add.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        val scopePick = fixedPicker(c, listOf("This project", "Global"), "This project") { }
        scopePick.layoutParams = Ui.lp(dp(140), Ui.wrap).also { it.leftMargin = dp(6) }
        val addIt = {
            val v = add.text.toString().trim()
            if (v.isNotEmpty()) {
                val sc = if (scopePick.text.toString().startsWith("Global")) "global" else "project"
                memoryPost(JSONObject().put("action", "add").put("text", v).put("scope", sc))
            }
        }
        onEnter(add) { addIt() }
        val addBtn = Ui.button(c, "Add") { addIt() }
        addBtn.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) }
        val row = Ui.row(c, add, scopePick, addBtn)
        row.setPadding(0, dp(8), 0, 0)
        box.addView(row)
    }

    private fun memoryPost(body: JSONObject) {
        Api.async("memories-" + body.optString("action"), { Api.post("/api/memories", body) }) { r, e ->
            if (!alive) return@async
            if (r == null) fail("Memories", e) else drawMemories(r)
        }
    }

    // ------------------------------------------------------------------ docs sources
    private var docsList: LinearLayout? = null
    private var docsPending = false
    private var docsBusy = false

    private fun docsCard(): View {
        val c = a
        val list = Ui.column(c)
        docsList = list
        list.addView(emptyNote(c, "Loading the docs..."))
        val url = Ui.input(c, "https://docs.example.com/guide/", mono = true)
        url.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        val name = Ui.input(c, "Name")
        name.layoutParams = Ui.lp(dp(130), Ui.wrap).also { it.leftMargin = dp(6) }
        val addIt = {
            val u = url.text.toString().trim()
            if (u.isEmpty()) Ui.toast(a, "Type the address of the documentation first")
            else {
                val n = name.text.toString().trim().ifEmpty { try { URI(u).host ?: u } catch (e: Exception) { u } }
                docsPost(JSONObject().put("action", "add").put("url", u).put("name", n)) { url.setText(""); name.setText("") }
            }
        }
        onEnter(url) { addIt() }
        onEnter(name) { addIt() }
        val addBtn = Ui.button(c, "Add") { addIt() }
        addBtn.layoutParams = Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) }
        val row = Ui.row(c, url, name, addBtn)
        row.setPadding(0, dp(8), 0, 0)
        val card = Ui.card(c, hint("Index a documentation site so you can mention it with @docs and the agent can search it. Forge reads up to 40 pages under the address you give, once, and keeps them on this computer. A removed index goes to Forge's trash folder, not to deletion."), list, row)
        refreshDocs()
        return card
    }

    private fun refreshDocs() {
        if (docsBusy) return
        docsBusy = true
        Api.async("docs", { Api.get("/api/docs") }) { r, e ->
            docsBusy = false
            if (!alive) return@async
            if (r == null) { val l = docsList ?: return@async; l.removeAllViews(); l.addView(emptyNote(a, "Could not load the docs: " + (e?.message ?: "unknown error"))); return@async }
            drawDocs(r.optJSONArray("docs"))
        }
    }

    private fun drawDocs(docs: JSONArray?) {
        val c = a
        val list = docsList ?: return
        list.removeAllViews()
        docsPending = false
        if (docs == null || docs.length() == 0) { list.addView(emptyNote(c, "No docs indexed yet.")); return }
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val name = d.optString("name"); val status = d.optString("status"); val pages = d.optInt("pages"); val u = d.optString("url")
            val err = if (d.isNull("error")) "" else d.optString("error")
            if (status != "ready") docsPending = true
            val text = name + " · " + (if (status == "ready") "$pages pages" else status + (if (pages > 0) " ($pages pages so far)" else "")) + (if (err.isNotEmpty()) " · $err" else "")
            val controls = ArrayList<View>()
            if (status == "ready") controls.add(Ui.miniButton(c, R.drawable.ic_refresh, "Index again") { docsPost(JSONObject().put("action", "add").put("name", name).put("url", u)) { } })
            controls.add(trashButton(c, "Remove (moved to Forge's trash folder)") {
                confirmDialog(a, "Remove the $name docs?", "The index is moved to Forge's trash folder on the server, so it can be brought back by hand.", "Remove") {
                    docsPost(JSONObject().put("action", "remove").put("name", name)) { }
                }
            })
            val row = itemRow(c, text, false, null, *controls.toTypedArray())
            row.tooltipText = u
            list.addView(row)
        }
    }

    private fun docsPost(body: JSONObject, then: () -> Unit) {
        Api.async("docs-" + body.optString("action"), { Api.post("/api/docs", body) }) { r, e ->
            if (!alive) return@async
            if (r == null) { fail("Docs", e); return@async }
            then()
            drawDocs(r.optJSONArray("docs"))
        }
    }

    // ------------------------------------------------------------------ hooks
    private var hooksBox: LinearLayout? = null
    private var hookEvents: List<String> = listOf("beforeSubmitPrompt", "beforeShellExecution", "afterFileEdit", "stop")
    private val hookTexts = HashMap<String, String>()

    private fun hooksCard(): View {
        val c = a
        val box = Ui.column(c)
        hooksBox = box
        box.addView(emptyNote(c, "Loading the hooks..."))
        val card = Ui.card(c, hint("Hooks run your own scripts at points in the agent loop (beforeSubmitPrompt, beforeShellExecution, afterFileEdit, stop), with 10 seconds each. Each one gets the event as JSON on its input and may answer with {\"permission\": \"allow\" | \"ask\" | \"deny\", \"message\": \"...\"}; exit code 2 means deny whatever it printed, and the strictest answer of all hooks wins. Ask before a message shows you a question first. A hook that fails or runs out of time before a command makes Forge ask you; output that is not JSON counts as allow. A folder's own hooks run only once you trust the folder. A hook switched off keeps its command in the file under \"disabled_command\", which the server skips, so it can be switched on again."), box)
        loadHooks()
        return card
    }

    private fun loadHooks() {
        Api.async("hooks", { Api.get("/api/hooks") }) { r, e ->
            if (!alive) return@async
            val box = hooksBox ?: return@async
            if (r == null) { box.removeAllViews(); box.addView(emptyNote(a, "Could not load the hooks: " + (e?.message ?: "unknown error"))); return@async }
            val ev = ArrayList<String>()
            r.optJSONArray("events")?.let { arr -> for (i in 0 until arr.length()) ev.add(arr.optString(i)) }
            if (ev.isNotEmpty()) hookEvents = ev
            hookTexts["global"] = r.optString("global", "{}")
            hookTexts["project"] = r.optString("project", "{}")
            drawHooks()
        }
    }

    private fun drawHooks() {
        val c = a
        val box = hooksBox ?: return
        box.removeAllViews()
        for (sc in listOf("global", "project")) {
            box.addView(label(if (sc == "global") "Global hooks (~/.forge-ide/hooks.json)" else "Project hooks (.forge/hooks.json)"))
            val text = hookTexts[sc] ?: "{}"
            val cfg = try { JSONObject(text) } catch (e: Exception) { null }
            if (cfg == null) {
                box.addView(emptyNote(c, "This file is not valid JSON. Fix it with Edit as JSON."))
            } else {
                var any = false
                for (ev in hookEvents) {
                    val arr = cfg.optJSONArray(ev) ?: continue
                    for (i in 0 until arr.length()) {
                        val h = arr.optJSONObject(i) ?: continue
                        val enabled = h.optString("command").isNotEmpty()
                        val cmd = if (enabled) h.optString("command") else h.optString("disabled_command")
                        any = true
                        val toggle = Toggle(c, enabled)
                        toggle.tooltipText = "Enabled"
                        toggle.onChange = { on ->
                            if (on) { h.put("command", cmd); h.remove("disabled_command") } else { h.put("disabled_command", cmd); h.remove("command") }
                            saveHooks(sc, cfg)
                        }
                        val del = trashButton(c, "Delete this hook") {
                            confirmDialog(a, "Delete this hook?", "$ev: $cmd", "Delete") { arr.remove(i); if (arr.length() == 0) cfg.remove(ev); saveHooks(sc, cfg) }
                        }
                        box.addView(hookRow(ev, cmd, enabled, {
                            hookDialog(ev, cmd) { newEv, newCmd ->
                                if (newEv != ev) {
                                    arr.remove(i); if (arr.length() == 0) cfg.remove(ev)
                                    (cfg.optJSONArray(newEv) ?: JSONArray().also { cfg.put(newEv, it) }).put(h)
                                }
                                h.put(if (enabled) "command" else "disabled_command", newCmd)
                                saveHooks(sc, cfg)
                            }
                        }, toggle, del))
                    }
                }
                if (!any) box.addView(emptyNote(c, "No hooks in this file."))
            }
            box.addView(actionRow(c,
                Ui.button(c, "Add a hook") {
                    hookDialog(hookEvents.first(), "") { ev, cmd ->
                        val target = cfg ?: JSONObject()
                        (target.optJSONArray(ev) ?: JSONArray().also { target.put(ev, it) }).put(JSONObject().put("command", cmd))
                        saveHooks(sc, target)
                    }
                },
                Ui.button(c, "Edit as JSON") {
                    textDialog(a, if (sc == "global") "Global hooks" else "Project hooks", text, mono = true,
                        note = "Example: {\"beforeShellExecution\": [{\"command\": \"~/bin/check-command.sh\"}], \"afterFileEdit\": [{\"command\": \"black -q \\\"\$FORGE_FILE\\\"\"}]}. Events: " + hookEvents.joinToString(", ") + ".") { v -> saveHooksText(sc, v) }
                }))
        }
    }

    /** A hook's row: the event above the command on the left, the enable switch and the trash button on the right. */
    private fun hookRow(event: String, command: String, enabled: Boolean, onEdit: () -> Unit, vararg controls: View): LinearLayout {
        val c = a
        val evText = Ui.text(c, event, 10.5f, Ui.fg3)
        val cmdText = Ui.text(c, command.ifEmpty { "(no command)" }, 12.5f, if (enabled) Ui.fg else Ui.fg3, mono = true)
        val col = Ui.column(c, evText, cmdText)
        col.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        col.setPadding(dp(6), dp(4), dp(6), dp(4))
        col.isClickable = true; col.isFocusable = true
        col.tooltipText = "Edit"
        col.background = Ui.states(null, Ui.rounded(Ui.bg4, 6), Ui.rounded(Ui.border2, 6))
        col.setOnClickListener { onEdit() }
        col.setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { onEdit(); true } else false }
        val row = Ui.row(c, col)
        for (v in controls) { row.addView(v); (v.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6) }
        return row
    }

    /** The add-or-edit dialog of a hook: an event picker and the command, run by bash on the Forge server. */
    private fun hookDialog(event: String, command: String, onSave: (String, String) -> Unit) {
        val c = a
        val box = Ui.column(c)
        lateinit var dialog: Dialog
        box.addView(dialogHead(c, if (command.isEmpty()) "Add a hook" else "Edit the hook") { dialog.dismiss() })
        box.addView(Ui.divider(c, false))
        val body = Ui.column(c)
        body.setPadding(dp(12), dp(10), dp(12), dp(4))
        body.addView(Ui.text(c, "Event", 12.5f, Ui.fg2))
        val picker = fixedPicker(c, hookEvents, if (hookEvents.contains(event)) event else hookEvents.first()) { }
        picker.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(3); it.bottomMargin = dp(8) }
        body.addView(picker)
        body.addView(Ui.text(c, "Command (run by bash with the event as JSON on its input; FORGE_FILE and FORGE_COMMAND are set)", 12.5f, Ui.fg2))
        val input = Ui.input(c, "~/bin/check-command.sh", mono = true)
        input.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(3) }
        input.setText(command)
        body.addView(input)
        box.addView(body)
        val save = {
            val cmd = input.text.toString().trim()
            if (cmd.isEmpty()) Ui.toast(a, "A hook needs a command")
            else { dialog.dismiss(); onSave(picker.text.toString().removeSuffix("   ▾"), cmd) }
        }
        onEnter(input) { save() }
        val spacer = View(c); spacer.layoutParams = Ui.lp(0, 1, 1f)
        val foot = Ui.row(c, spacer, Ui.button(c, "Cancel") { dialog.dismiss() }, gapView(c, 8), Ui.button(c, "Save", primary = true) { save() })
        foot.setPadding(dp(12), dp(8), dp(12), dp(10))
        box.addView(foot)
        dialog = darkDialog(a, box, 640)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        input.requestFocus()
    }

    private fun saveHooks(sc: String, cfg: JSONObject) = saveHooksText(sc, cfg.toString(2))

    private fun saveHooksText(sc: String, text: String) {
        Api.async("hooks-save", { Api.post("/api/hooks", JSONObject().put("scope", sc).put("config", text)) }) { r, e ->
            if (!alive) return@async
            if (r == null) { fail("Hooks not saved", e); loadHooks(); return@async }
            hookTexts[sc] = text
            Ui.toast(a, "Hooks saved")
            drawHooks()
        }
    }

    // ------------------------------------------------------------------ rules for the AI
    private var rulesBox: LinearLayout? = null
    private val ruleTexts = HashMap<String, String>()

    private fun rulesCard(): View {
        val c = a
        val box = Ui.column(c)
        rulesBox = box
        box.addView(emptyNote(c, "Loading the rules..."))
        val card = Ui.card(c, hint("Rules are written in plain English, one per line, and are added to every chat. Global rules apply in every folder; project rules in this folder only. The agent also reads a .cursorrules, AGENTS.md or CLAUDE.md file in the folder, the way Cursor does; those files are edited in the editor. Tap a rule to edit it."), box)
        Api.async("rules", { Api.get("/api/rules") }) { r, e ->
            if (!alive) return@async
            if (r == null) { box.removeAllViews(); box.addView(emptyNote(c, "Could not load the rules: " + (e?.message ?: "unknown error"))); return@async }
            ruleTexts["global"] = r.optString("global")
            ruleTexts["project"] = r.optString("project")
            drawRules()
        }
        return card
    }

    private fun drawRules() {
        val c = a
        val box = rulesBox ?: return
        box.removeAllViews()
        for (key in listOf("global", "project")) {
            box.addView(label(if (key == "global") "Global rules (~/.forge-ide/rules.md)" else "Project rules (.forge/rules.md)"))
            val text = ruleTexts[key] ?: ""
            val lines = ArrayList(text.split("\n"))
            val shown = lines.indices.filter { lines[it].isNotBlank() }
            if (shown.isEmpty()) box.addView(emptyNote(c, "No rules yet."))
            for (idx in shown) {
                val line = lines[idx]
                box.addView(itemRow(c, line.trim(), false, {
                    textDialog(a, "Edit the rule", line.trim(), multiline = false) { v ->
                        val t = v.trim()
                        if (t.isEmpty()) lines.removeAt(idx) else lines[idx] = t
                        saveRules(key, lines.joinToString("\n"))
                    }
                }, trashButton(c, "Delete this rule") {
                    confirmDialog(a, "Delete this rule?", line.trim(), "Delete") { lines.removeAt(idx); saveRules(key, lines.joinToString("\n")) }
                }))
            }
            box.addView(actionRow(c,
                Ui.button(c, "Add a rule") {
                    textDialog(a, "Add a rule", "", hint = "For example: Always write a test for new code", multiline = false, saveLabel = "Add") { v ->
                        val t = v.trim()
                        if (t.isEmpty()) return@textDialog
                        saveRules(key, if (text.isBlank()) t else text.trimEnd('\n') + "\n" + t)
                    }
                },
                Ui.button(c, "Edit as text") {
                    textDialog(a, if (key == "global") "Global rules" else "Project rules", text, hint = "Write rules in plain English, one per line") { v -> saveRules(key, v) }
                }))
        }
    }

    private fun saveRules(key: String, text: String) {
        Api.async("rules-save", { Api.post("/api/rules", JSONObject().put(key, text)) }) { r, e ->
            if (!alive) return@async
            if (r == null) { fail("Rules not saved", e); return@async }
            ruleTexts[key] = text
            Ui.toast(a, "Rules saved")
            drawRules()
        }
    }

    // ------------------------------------------------------------------ MCP servers
    private var mcpList: LinearLayout? = null
    private var mcpConfig = ""
    private var mcpPath = "~/.forge-ide/mcp.json"
    private var mcpPathText: TextView? = null

    private fun mcpCard(): View {
        val c = a
        val list = Ui.column(c)
        mcpList = list
        list.addView(emptyNote(c, "Loading the MCP servers..."))
        val pathText = hint("Configuration: $mcpPath")
        mcpPathText = pathText
        val card = Ui.card(c, hint("Model Context Protocol servers give the agent more tools. Each server is a command the Forge server starts over stdio; its tools appear to the agent as mcp_<server>_<tool>. They ask before they run, even with Auto-run on, while MCP tools protection is on (Agent behavior); Always allow on a tool's card allows that tool in one chat. A server that fails to start is tried again after ten minutes, or at once with Connect."),
            pathText, list, actionRow(c,
                Ui.button(c, "Add a server", primary = true) { addServerDialog() },
                Ui.button(c, "Connect servers") { connectServers() },
                Ui.button(c, "Edit configuration") {
                    textDialog(a, "MCP configuration ($mcpPath)", mcpConfig, mono = true,
                        note = "Example: {\"servers\": {\"fs\": {\"command\": [\"npx\", \"-y\", \"@modelcontextprotocol/server-filesystem\", \"/home/you/projects\"]}}}. Saving reloads the servers.",
                        saveLabel = "Save and reload") { v -> saveMcpConfig(v) }
                }))
        Api.async("mcp", { Api.get("/api/mcp") }) { r, e ->
            if (!alive) return@async
            if (r == null) { list.removeAllViews(); list.addView(emptyNote(c, "Could not load the MCP servers: " + (e?.message ?: "unknown error"))); return@async }
            mcpConfig = r.optString("config")
            mcpPath = r.optString("config_path", mcpPath)
            pathText.text = "Configuration: $mcpPath"
            drawServers(r.optJSONArray("servers"))
        }
        return card
    }

    private fun drawServers(servers: JSONArray?) {
        val c = a
        val list = mcpList ?: return
        list.removeAllViews()
        if (servers == null || servers.length() == 0) { list.addView(emptyNote(c, "No MCP servers configured.")); return }
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val name = s.optString("name")
            val running = s.optBoolean("running")
            val err = if (s.isNull("error")) "" else s.optString("error")
            val tools = s.optJSONArray("tools")?.length() ?: 0
            val cmd = ArrayList<String>()
            s.optJSONArray("command")?.let { arr -> for (j in 0 until arr.length()) cmd.add(arr.optString(j)) }
            val dot = Ui.dot(c, if (err.isNotEmpty()) Ui.red else if (running) Ui.green else Ui.fg3)
            (dot.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6)
            val title = Ui.text(c, name + " · " + (if (tools > 0) "$tools tools" else "no tools yet"), 12.5f, Ui.fg)
            val state = Ui.text(c, if (err.isNotEmpty()) "error: $err" else if (running) "running" else "stopped", 11.5f,
                if (err.isNotEmpty()) Ui.red else if (running) Ui.green else Ui.fg3)
            state.setPadding(dp(14), 0, 0, 0)
            val command = Ui.text(c, cmd.joinToString(" "), 11f, Ui.fg3, mono = true)
            command.setPadding(dp(14), 0, 0, 0)
            command.maxLines = 2; command.ellipsize = TextUtils.TruncateAt.END
            val col = Ui.column(c, Ui.row(c, dot, title), state, command)
            col.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            col.setPadding(dp(6), dp(5), dp(6), dp(5))
            val del = trashButton(c, "Remove this server from the configuration") {
                confirmDialog(a, "Remove the server \"$name\"?", "It is taken out of $mcpPath and the servers are reloaded.", "Remove") { removeServer(name) }
            }
            (del.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
            list.addView(Ui.row(c, col, del))
        }
    }

    private fun parsedMcpConfig(): JSONObject? {
        val cfg = try { JSONObject(mcpConfig.ifBlank { "{}" }) } catch (e: Exception) { null }
        if (cfg == null) Ui.toast(a, "The MCP configuration is not valid JSON; fix it with Edit configuration")
        return cfg
    }

    private fun removeServer(name: String) {
        val cfg = parsedMcpConfig() ?: return
        cfg.optJSONObject("servers")?.remove(name)
        saveMcpConfig(cfg.toString(2))
    }

    /** Adds one server: its name and its JSON description, for example {"command": ["npx", "-y", "..."]}. */
    private fun addServerDialog() {
        val c = a
        val box = Ui.column(c)
        lateinit var dialog: Dialog
        box.addView(dialogHead(c, "Add an MCP server") { dialog.dismiss() })
        box.addView(Ui.divider(c, false))
        val body = Ui.column(c)
        body.setPadding(dp(12), dp(10), dp(12), dp(4))
        body.addView(Ui.text(c, "Name (the tools appear as mcp_<name>_<tool>)", 12.5f, Ui.fg2))
        val name = Ui.input(c, "fs")
        name.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(3); it.bottomMargin = dp(8) }
        body.addView(name)
        body.addView(Ui.text(c, "Server description as JSON: \"command\" (a list of words), and if needed \"args\", \"env\" and \"cwd\"", 12.5f, Ui.fg2))
        val spec = Ui.input(c, "{\"command\": [\"npx\", \"-y\", \"@modelcontextprotocol/server-filesystem\", \"/home/you/projects\"]}", mono = true, multiline = true)
        spec.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(3) }
        spec.minLines = 4
        body.addView(spec)
        box.addView(body)
        val save = {
            val n = name.text.toString().trim()
            val parsed = try { JSONObject(spec.text.toString()) } catch (e: Exception) { null }
            when {
                n.isEmpty() -> Ui.toast(a, "The server needs a name")
                parsed == null -> Ui.toast(a, "The description is not valid JSON")
                parsed.optJSONArray("command") == null || parsed.optJSONArray("command")!!.length() == 0 -> Ui.toast(a, "The description needs a \"command\" list")
                else -> {
                    val cfg = parsedMcpConfig()
                    if (cfg != null) {
                        (cfg.optJSONObject("servers") ?: JSONObject().also { cfg.put("servers", it) }).put(n, parsed)
                        dialog.dismiss()
                        saveMcpConfig(cfg.toString(2))
                    }
                }
            }
        }
        val spacer = View(c); spacer.layoutParams = Ui.lp(0, 1, 1f)
        val foot = Ui.row(c, spacer, Ui.button(c, "Cancel") { dialog.dismiss() }, gapView(c, 8), Ui.button(c, "Add and reload", primary = true) { save() })
        foot.setPadding(dp(12), dp(8), dp(12), dp(10))
        box.addView(foot)
        dialog = darkDialog(a, box, 640)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        name.requestFocus()
    }

    private fun saveMcpConfig(text: String) {
        Api.async("mcp-config", { Api.post("/api/mcp/config", JSONObject().put("config", text)) }) { r, e ->
            if (!alive) return@async
            if (r == null) { fail("MCP configuration not saved", e); return@async }
            mcpConfig = text
            Ui.toast(a, "MCP configuration saved")
            drawServers(r.optJSONArray("servers"))
        }
    }

    private fun connectServers() {
        Api.async("mcp-connect", { Api.post("/api/mcp/connect", JSONObject(), 120000) }) { r, e ->
            if (!alive) return@async
            if (r == null) { fail("MCP servers", e); return@async }
            Ui.toast(a, "MCP servers connected")
            drawServers(r.optJSONArray("servers"))
        }
    }

    // ------------------------------------------------------------------ vision status
    private var visionInfo: TextView? = null
    private var visionBusy = false

    private fun visionCard(): View {
        val c = a
        val info = Ui.text(c, "Checking the vision model...", 11.5f, Ui.fg2)
        info.setPadding(0, dp(4), 0, 0)
        visionInfo = info
        val buttons = actionRow(c,
            Ui.button(c, "Refresh") { refreshVision() },
            Ui.button(c, "Stop the vision model") {
                Api.async("vision-stop", { Api.post("/api/vision/stop", JSONObject()) }) { r, e ->
                    if (!alive) return@async
                    if (r == null) fail("Vision", e) else Ui.toast(a, "The Forge server released the vision model")
                    refreshVision()
                }
            })
        val card = Ui.card(c, Ui.text(c, "Qwen3-VL on the Intel GPU, run by this app when a chat looks at an image", 12.5f, Ui.fg2), info, buttons,
            hint("The Forge server asks this app for the model when a screenshot needs more than text recognition, and this app reports its state back every few seconds. The tier (4B or 2B) is chosen by the free memory at each start.").also { it.setPadding(0, dp(8), 0, 0) })
        refreshVision()
        return card
    }

    private fun refreshVision() {
        if (visionBusy) return
        visionBusy = true
        val state = VisionControl.state().ifEmpty { "off" }
        val tier = VisionControl.tier()
        val detail = VisionControl.detail()
        Api.async("vision-status", { Api.get("/api/vision/status") }) { r, e ->
            visionBusy = false
            if (!alive) return@async
            val info = visionInfo ?: return@async
            val sb = StringBuilder()
            sb.append("In this app: ").append(state).append(if (tier.isNotEmpty()) " ($tier)" else "")
            if (detail.isNotEmpty() && state != "error") sb.append(", ").append(detail)
            if (state == "error") sb.append("\nLast error: ").append(detail.ifEmpty { "not recorded" })
            if (r == null) {
                sb.append("\nForge server: could not read the vision status (").append(e?.message ?: "unknown error").append(")")
            } else {
                val app = r.optJSONObject("app") ?: JSONObject()
                val vstate = app.optString("vstate").ifEmpty { "stopped" }
                val vtier = app.optString("vtier"); val vdetail = app.optString("vdetail")
                sb.append("\nSeen by the Forge server: ")
                if (r.optBoolean("app_connected")) {
                    sb.append(vstate).append(if (vtier.isNotEmpty()) " ($vtier)" else "")
                    if (vdetail.isNotEmpty() && vstate != "running") sb.append(", ").append(vdetail)
                } else sb.append("this app has not reported for a minute, so the server cannot use the GPU for images")
                val tiers = ArrayList<String>()
                r.optJSONArray("tiers_available")?.let { arr -> for (i in 0 until arr.length()) tiers.add(arr.optString(i)) }
                sb.append("\nModel tiers on the server: ").append(if (tiers.isEmpty()) "none installed" else tiers.joinToString(" and "))
                sb.append("\nText recognition (tesseract): ").append(if (r.optBoolean("ocr")) "ready" else "not installed")
                sb.append("\nWanted by a chat right now: ").append(if (r.optBoolean("wanted")) "yes" else "no")
            }
            info.text = sb
            info.setTextColor(if (state == "error") Ui.red else Ui.fg2)
        }
    }
}

/** Runs [action] when Enter is pressed in a single-line field (a focus loss does nothing). */
private fun onEnter(input: EditText, action: () -> Unit) {
    input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
    input.setOnEditorActionListener { _, _, _ -> action(); true }
    input.setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { action(); true } else false }
}
