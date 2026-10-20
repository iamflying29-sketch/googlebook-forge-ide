package local.forge

import android.content.Context
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * The Extensions view (Ctrl Shift X), Forge's counterpart of Cursor's. Forge has no extension marketplace: what extends
 * its editor are the language servers (code intelligence for each language) and the toolchains. This view lists every
 * language server with its state in this window's folder, starts, restarts and stops each one, turns them all on or off
 * for the folder, and leads to the toolchains in the Android view.
 */
class ExtensionsPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)
    private val body = Ui.column(ctx)
    private fun dp(v: Int) = Ui.dp(ctx, v)

    /** Server key (as Lsp and the server name them), the language's name, and an extension that starts it. */
    private val langs = listOf(
        Triple("python", "Python", "py"), Triple("javascript", "JavaScript", "js"), Triple("typescript", "TypeScript", "ts"),
        Triple("shell", "Shell scripts", "sh"), Triple("json", "JSON", "json"), Triple("css", "CSS", "css"),
        Triple("html", "HTML", "html"), Triple("xml", "XML (Android layouts and manifests)", "xml"), Triple("c", "C", "c"),
        Triple("cpp", "C++", "cpp"), Triple("go", "Go", "go"), Triple("rust", "Rust", "rs"), Triple("java", "Java", "java"),
        Triple("kotlin", "Kotlin", "kt"))

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(SideKit.header(ctx, "Extensions", Ui.miniButton(ctx, R.drawable.ic_refresh, "Reload") { load() }))
        view.addView(Ui.divider(ctx, false))
        view.addView(Ui.scroll(ctx, body), Ui.lp(Ui.match, 0, 1f))
        body.addView(SideKit.muted(ctx, "Looking at the language servers."))
        load()
    }

    /** GET /api/lsp/status: which language servers are installed in the Linux VM. */
    fun load() {
        Api.async("lsp-status", { Api.get("/api/lsp/status") }) { r, e ->
            if (r == null) { body.removeAllViews(); body.addView(SideKit.muted(ctx, "Could not ask the Forge server: " + (e?.message ?: ""))); return@async }
            render(r.optJSONObject("available") ?: JSONObject())
        }
    }

    private fun render(installed: JSONObject) {
        body.removeAllViews()
        val folder = host.windowFolder()
        body.addView(SideKit.muted(ctx, "Forge has no extension marketplace. What extends its editor are these language servers (suggestions, hover, go to definition, references, rename and live errors) and the toolchains in the Android view. A server starts by itself when you open a file of its language, when memory allows."))
        val on = Lsp.enabled(folder)
        val toggle = Ui.button(ctx, if (on) "Turn them off for this folder" else "Turn them on for this folder") {
            ForgeSettings.set("lsp", !on, folder) { ok, err ->
                if (!ok) Ui.toast(ctx, "Not changed: $err")
                else if (!on) for (p in host.editor.openPaths()) host.editor.textOf(p)?.let { Lsp.open(p, it, folder) }
                load()
            }
        }
        body.addView(SideKit.section(ctx, "LANGUAGE SERVERS " + (if (on) "(ON IN THIS FOLDER)" else "(OFF IN THIS FOLDER)")))
        body.addView(Ui.row(ctx, toggle).also { it.setPadding(dp(10), dp(2), dp(10), dp(6)) })
        val running = Lsp.status(folder)
        for ((key, name, ext) in langs) {
            val has = installed.optBoolean(key, false)
            val st = running.firstOrNull { it.startsWith("$key:") }
            val state = when {
                !has -> "Not installed. " + installHint(key)
                st == null -> if (on) "Installed, not running." else "Installed, turned off for this folder."
                st.contains("ready") -> "Running" + (st.substringAfter("ready", "").trim().let { if (it.isNotEmpty()) " $it" else "" }) + "."
                else -> "Starting."
            }
            val title = Ui.text(ctx, name, 12.5f, Ui.fg, bold = true)
            val info = Ui.text(ctx, state, 11.5f, if (has) Ui.fg2 else Ui.fg3)
            val col = Ui.column(ctx, title, info)
            val row = Ui.row(ctx, col)
            col.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            if (has && on) {
                if (st == null) row.addView(Ui.button(ctx, "Start") { Lsp.startServer(key, ext, folder); host.flashStatus("Starting the $name language server."); Api.main.postDelayed({ load() }, 1500) })
                else {
                    row.addView(Ui.button(ctx, "Restart") { Lsp.stopServer(key, folder); Lsp.startServer(key, ext, folder); reopen(ext, folder); Api.main.postDelayed({ load() }, 1500) })
                    row.addView(Ui.button(ctx, "Stop") { Lsp.stopServer(key, folder); host.flashStatus("Stopped the $name language server. It starts again when you open a $name file."); load() },
                        Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) })
                }
            }
            row.setPadding(dp(12), dp(6), dp(10), dp(6))
            row.background = Ui.rounded(Ui.bg2, 6)
            body.addView(row, Ui.lp(Ui.match, Ui.wrap).also { it.setMargins(dp(8), dp(2), dp(8), dp(2)) })
        }
        body.addView(SideKit.section(ctx, "TOOLCHAINS"))
        body.addView(SideKit.muted(ctx, "Android SDK, Kotlin, Gradle, Rust and the virtual devices are in the Android view."))
        body.addView(Ui.row(ctx, Ui.button(ctx, "Open the Android and toolchains view") { host.showAndroidView() }).also { it.setPadding(dp(10), dp(2), dp(10), dp(8)) })
    }

    /** After a restart, the open files of that language are opened on the new server again. */
    private fun reopen(ext: String, folder: String) {
        for (p in host.editor.openPaths()) if (p.substringAfterLast('.', "").equals(ext, true)) host.editor.textOf(p)?.let { Lsp.open(p, it, folder) }
    }

    private fun installHint(key: String): String = when (key) {
        "kotlin", "java", "xml" -> "It comes with the android part: ~/projects/forge-ide/install-extras.sh android"
        "rust" -> "It comes with the rust part: ~/projects/forge-ide/install-extras.sh rust"
        else -> "It comes with the lsp part: ~/projects/forge-ide/install-extras.sh lsp"
    }
}
