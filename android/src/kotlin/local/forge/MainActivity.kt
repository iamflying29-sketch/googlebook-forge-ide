package local.forge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.TextView
import org.json.JSONObject

/**
 * Forge's native window: the Explorer and editor, the terminal and the AI panel, built with Android views and
 * talking to the Forge server in the Linux VM. No browser anywhere. The GPU engine and the vision model are run
 * by the services of this app (EngineService, VisionControl), as before.
 */
class MainActivity : Activity() {
    companion object {
        const val VERSION = "2.2"
        private const val TAG = "Forge"
        private const val TERMINAL_PACKAGE = "com.android.virtualization.terminal"
        private const val PROBE_MS = 3000L
        private const val PICK_IMAGE = 42
        private const val REQ_MIC = 43
    }

    private lateinit var root: FrameLayout
    private lateinit var ide: LinearLayout
    private lateinit var offline: LinearLayout
    private lateinit var offlineText: TextView
    private lateinit var folderButton: android.widget.Button
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var chat: ChatPanel
    private lateinit var editor: EditorPanel
    private lateinit var terminal: TerminalPanel
    private var terminalShown = true
    private var chatShown = true
    private var started = false
    private var checking = false
    private var failedProbes = 0
    private var offlineSince = 0L
    private var terminalOpened = false
    private var loaded = false
    private var voice: Voice? = null
    private var dictateInto: EditText? = null
    private var afterMic: Runnable? = null
    private val probeTask = Runnable { connect() }
    private val statusTask = object : Runnable { override fun run() { refreshStatus(); Api.main.postDelayed(this, 20000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = intent?.getStringExtra("path")
        if (p != null && p.startsWith("/")) Api.folder = p
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.bg)
        buildIde()
        buildOffline()
        root.addView(ide, FrameLayout.LayoutParams(Ui.match, Ui.match))
        root.addView(offline, FrameLayout.LayoutParams(Ui.match, Ui.match))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        VisionControl.ensure(this)
        if (intent?.getBooleanExtra("start_engine", false) == true) EngineService.start(this)
        if (intent?.getBooleanExtra("stop_engine", false) == true) EngineService.stop(this)
        connect()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("start_engine", false) == true) EngineService.start(this)
        if (intent?.getBooleanExtra("stop_engine", false) == true) EngineService.stop(this)
    }

    // ---------------------------------------------------------------- layout
    private fun buildIde() {
        ide = LinearLayout(this); ide.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 8)
        folderButton = Ui.button(this, "Open folder") { openFolderDialog() }
        titleText = Ui.text(this, "Forge", 13f, Ui.fg, bold = true)
        statusText = Ui.text(this, "", 11.5f, Ui.fg2)
        val bar = Ui.row(this, Ui.text(this, "F", 15f, Ui.accent, bold = true).also { it.setPadding(pad, 0, pad, 0) }, folderButton, titleText, statusText,
            Ui.button(this, "Find file") { editor.quickOpen() },
            Ui.button(this, "Terminal") { toggleTerminal() },
            Ui.button(this, "Chat") { toggleChat() },
            Ui.button(this, "New window") { newWindow() },
            Ui.button(this, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        titleText.setPadding(pad, 0, pad, 0)
        statusText.layoutParams = Ui.lp(0, Ui.wrap, 1f)
        bar.setPadding(pad / 2, pad / 2, pad / 2, pad / 2); bar.setBackgroundColor(Ui.bg2)
        ide.addView(bar, Ui.lp(Ui.match, Ui.wrap))
        ide.addView(Ui.divider(this, false))
        editor = EditorPanel(this, this)
        terminal = TerminalPanel(this, this)
        chat = ChatPanel(this, this)
        val left = Ui.column(this)
        left.addView(editor.view, Ui.lp(Ui.match, 0, 1f))
        left.addView(Ui.divider(this, false))
        left.addView(terminal.view, Ui.lp(Ui.match, Ui.dp(this, 230)))
        val body = Ui.row(this, left, Ui.divider(this, true), chat.view)
        body.gravity = Gravity.TOP
        left.layoutParams = Ui.lp(0, Ui.match, 1f)
        chat.view.layoutParams = Ui.lp(Ui.dp(this, 430), Ui.match)
        ide.addView(body, Ui.lp(Ui.match, 0, 1f))
    }

    private fun buildOffline() {
        offline = LinearLayout(this); offline.orientation = LinearLayout.VERTICAL; offline.gravity = Gravity.CENTER
        offline.setBackgroundColor(Ui.bg)
        offlineText = Ui.text(this, "Connecting to Forge…", 14f, Ui.fg2); offlineText.gravity = Gravity.CENTER
        val pad = Ui.dp(this, 24)
        offlineText.setPadding(pad, pad, pad, pad)
        offline.addView(Ui.text(this, "Forge", 26f, Ui.accent, bold = true).also { it.gravity = Gravity.CENTER })
        offline.addView(offlineText)
        offline.addView(Ui.row(this, Ui.button(this, "Check now", primary = true) { connect() }, Ui.button(this, "Open the Terminal") { openTerminalApp() }).also { it.gravity = Gravity.CENTER })
        offline.visibility = View.GONE
    }

    fun toggleTerminal() { terminalShown = !terminalShown; terminal.view.visibility = if (terminalShown) View.VISIBLE else View.GONE }
    fun toggleChat() { chatShown = !chatShown; chat.view.visibility = if (chatShown) View.VISIBLE else View.GONE }
    fun setWindowTitle(name: String) { titleText.text = if (name.isEmpty()) folderName() else name + "  ·  " + folderName() }
    private fun folderName(): String = if (Api.folder.isEmpty()) "Forge" else Api.folder.substringAfterLast('/')
    fun refreshTree() { editor.refresh() }
    fun fileChanged(path: String) { editor.fileChanged(path) }
    fun copyToClipboard(s: String) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Forge", s)) }

    // ---------------------------------------------------------------- connecting
    /** Load the IDE if the server answers, otherwise show the waiting screen and keep probing every few seconds
     *  while this window is open. Two misses in a row open the Terminal app once per outage (its VM boots in about
     *  half a minute and starts Forge with it). */
    private fun connect() {
        Api.main.removeCallbacks(probeTask)
        if (checking) return
        checking = true
        Api.async("probe", { Api.serverUp() }) { up, _ ->
            checking = false
            if (up == true) {
                failedProbes = 0; terminalOpened = false; offlineSince = 0
                offline.visibility = View.GONE
                if (!loaded) loadIde()
            } else {
                failedProbes++
                showOffline()
                if (started) Api.main.postDelayed(probeTask, PROBE_MS)
            }
        }
    }

    private fun showOffline() {
        if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
        val secs = (System.currentTimeMillis() - offlineSince) / 1000
        offlineText.text = "The Forge server in the Linux Terminal is not answering yet (checked $failedProbes times, $secs s).\n" +
            (if (terminalOpened) "The Terminal app was opened; its VM takes about half a minute to start Forge." else "Checking again every ${PROBE_MS / 1000} seconds.")
        offline.visibility = View.VISIBLE
        if (!terminalOpened && failedProbes >= 2) { terminalOpened = true; openTerminalApp() }
    }

    private fun openTerminalApp() {
        val i = packageManager.getLaunchIntentForPackage(TERMINAL_PACKAGE)
        if (i == null) { Log.w(TAG, "the Terminal app is not installed"); return }
        try { startActivity(i) } catch (e: Exception) { Log.w(TAG, "cannot open the Terminal", e) }
    }

    private fun loadIde() {
        loaded = true
        Api.async("status", { Api.get("/api/status") }) { r, _ ->
            if (r != null && Api.folder.isEmpty()) Api.folder = r.optString("workspace")
            setWindowTitle("")
            editor.refresh(); chat.load(); terminal.connect()
            Api.main.removeCallbacks(statusTask); Api.main.post(statusTask)
        }
    }

    private fun refreshStatus() {
        Api.async("status", { Api.get("/api/status") }) { r, e ->
            if (r == null) { statusText.text = "server not answering"; if (e != null) { loaded = false; connect() }; return@async }
            val g = r.optJSONObject("gpu_engine")
            val health = g?.optJSONObject("health")?.optString("status") ?: "down"
            val mem = r.optJSONObject("mem")
            val host = r.opt("host_mem_mb")
            val engine = if (r.optString("engine") == "gpu") {
                if (health == "ok") "GPU engine: ready" else if (g != null && g.optString("note").isNotEmpty()) "GPU engine: waiting for memory" else "GPU engine: $health"
            } else "Model: " + r.optString("model")
            statusText.text = engine + "   VM " + String.format("%.1f", (mem?.optInt("available_mb") ?: 0) / 1024.0) + " GB free" +
                (if (host is Number && host.toInt() >= 0) "   Googlebook " + String.format("%.1f", host.toInt() / 1024.0) + " GB free" else "")
            statusText.setTextColor(if (health == "ok" || r.optString("engine") != "gpu") Ui.fg2 else Ui.accent)
        }
    }

    // ---------------------------------------------------------------- folders and windows
    private fun openFolderDialog() {
        Api.async("dirs", { Api.get("/api/dirs", mapOf("path" to (if (Api.folder.isEmpty()) "~" else Api.folder))) }) { r, e ->
            if (r == null) { Ui.toast(this, "Cannot list folders: " + (e?.message ?: "")); return@async }
            showDirs(r)
        }
    }

    private fun showDirs(r: JSONObject) {
        val here = r.optString("path")
        val items = ArrayList<String>(); val targets = ArrayList<String>()
        val recent = r.optJSONArray("recent")
        if (recent != null) for (i in 0 until recent.length()) { items.add("Recent: " + recent.getString(i)); targets.add(recent.getString(i)) }
        if (r.optString("parent").isNotEmpty()) { items.add(".. (up)"); targets.add(r.optString("parent")) }
        val dirs = r.optJSONArray("dirs")
        if (dirs != null) for (i in 0 until dirs.length()) { items.add(dirs.getString(i) + "/"); targets.add(here.trimEnd('/') + "/" + dirs.getString(i)) }
        val list = ListView(this); list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        val dialog = AlertDialog.Builder(this).setTitle(here).setView(list)
            .setPositiveButton("Open this folder") { _, _ -> goToFolder(here) }
            .setNeutralButton("New window here") { _, _ -> newWindow(here) }
            .setNegativeButton("Cancel", null).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            if (items[pos].startsWith("Recent: ")) goToFolder(targets[pos])
            else Api.async("dirs", { Api.get("/api/dirs", mapOf("path" to targets[pos])) }) { rr, _ -> if (rr != null) showDirs(rr) }
        }
        dialog.show()
    }

    private fun goToFolder(path: String) {
        Api.folder = path
        Api.async("workspace", { Api.post("/api/workspace", JSONObject().put("path", path)) }) { _, _ ->
            setWindowTitle(""); editor.refresh(); chat.load(); terminal.connect()
        }
    }

    private fun newWindow(path: String = Api.folder) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("path", path)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivity(i)
    }

    // ---------------------------------------------------------------- images and voice
    fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT); i.type = "image/*"; i.addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(Intent.createChooser(i, "Attach an image"), PICK_IMAGE) } catch (e: Exception) { Ui.toast(this, "No image picker available") }
    }

    @Deprecated("Android's classic result callback is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image.png"
        Api.async("upload", {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("cannot read the image")
            if (bytes.size > 15 * 1024 * 1024) throw Exception("the image is larger than 15 MB")
            Api.upload(name, bytes)
        }) { r, e -> if (r == null) Ui.toast(this, "Not attached: " + (e?.message ?: "")) else chat.addAttachment(r) }
    }

    fun dictate(into: EditText) {
        dictateInto = into
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            afterMic = Runnable { startVoice() }
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startVoice()
    }

    private fun startVoice() {
        if (!Voice.available(this)) { Ui.toast(this, "No speech recognition service is installed"); return }
        val v = voice ?: Voice(this) { ev -> Api.main.post { onVoice(ev) } }.also { voice = it }
        Ui.toast(this, "Listening… speak, then press Mic again to stop")
        v.start("")
        afterMic = null
    }

    private var voiceBase = ""
    private fun onVoice(ev: JSONObject) {
        val kind = ev.optString("type", ev.optString("kind"))
        val text = ev.optString("text", ev.optString("value", ev.optString("message")))
        val into = dictateInto ?: return
        when (kind) {
            "state" -> if (text == "listening") voiceBase = into.text.toString()
            "partial" -> into.setText((voiceBase + " " + text).trim())
            "final" -> { voiceBase = (voiceBase + " " + text).trim(); into.setText(voiceBase); into.setSelection(into.text.length) }
            "error" -> Ui.toast(this, text)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_MIC && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) afterMic?.run()
    }

    // ---------------------------------------------------------------- keys and lifecycle
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_S -> { editor.saveActive(); return true }
                KeyEvent.KEYCODE_P -> { editor.quickOpen(); return true }
                KeyEvent.KEYCODE_L -> { if (!chatShown) toggleChat(); chat.focusPrompt(); return true }
                KeyEvent.KEYCODE_GRAVE -> { if (!terminalShown) toggleTerminal(); terminal.focusInput(); return true }
                KeyEvent.KEYCODE_O -> { openFolderDialog(); return true }
                KeyEvent.KEYCODE_R -> { if (!loaded) connect() else { editor.refresh(); refreshStatus() }; return true }
            }
            if (keyCode == KeyEvent.KEYCODE_N && event.isShiftPressed) { newWindow(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() { super.onStart(); started = true; if (!loaded) connect() else Api.main.post(statusTask) }
    override fun onStop() { super.onStop(); started = false; Api.main.removeCallbacks(probeTask); Api.main.removeCallbacks(statusTask); voice?.stop() }
    override fun onDestroy() { super.onDestroy(); terminal.close(); chat.stop() }
}
