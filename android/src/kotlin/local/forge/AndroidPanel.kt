package local.forge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * The Android view, ported from the old page's android.js: the devices (this Googlebook, virtual devices in the
 * Linux VM, and phones, tablets, watches and TVs paired over Wi-Fi) with one target device; the virtual devices
 * with their state, creation, image downloads, renaming, upgrades and the updates block; the device labs of Google
 * and Samsung; pairing; this Android project's build, install and run; Kotlin Multiplatform modules; the
 * toolchains; and Cargo for Rust. The live device screen opens in DeviceScreenWindow. The logcat lives in
 * LogcatPanel.
 *
 * The lists are read with GET /api/devices and GET /api/android/status, and read again every 2.5 seconds only
 * while something is in motion (a device starting, booting or stopping, a download or an image update running)
 * and only while the view is shown; the main window calls onShown and onHidden. A build watch reads
 * /api/android/status every 5 seconds for at most 30 minutes after "Build, install and run", as the page did.
 *
 * Some actions reach other parts of the window: commands that run in a terminal, the chat's attachments, the
 * debugger and the logcat. The main window wires the four hooks below; until it does, the terminal commands are
 * shown and copied to the clipboard, and the other actions say that they are not connected yet.
 */
class AndroidPanel(private val ctx: Context, private val host: MainActivity) {
    val view: LinearLayout = LinearLayout(ctx)

    /** Runs a shell command in a terminal tab (the page's ForgeTerm.run): the command and a label for the tab. */
    var runInTerminal: ((command: String, label: String) -> Unit)? = null
    /** Adds an attachment record (as /api/attachment returns it) to the chat composer (ChatPanel.addAttachment). */
    var attachToChat: ((record: JSONObject) -> Unit)? = null
    /** Starts the installed debug build on the target device and attaches the debugger (the page's ForgeDebug.attachAndroid). */
    var attachDebugger: ((appId: String) -> Unit)? = null
    /** Opens the logcat panel for one app id. */
    var openLogcat: ((appId: String) -> Unit)? = null

    /** The last /api/devices reply: devices, target, virtual, personas, behaviors and profiles (read by the device window). */
    var devices: JSONObject? = null
        private set
    /** The application id of this project's first application module, for the device window's behavior row. */
    var appId: String = ""
        private set

    private val content = LinearLayout(ctx)
    private var info: JSONObject? = null
    private var vdMode = "check"
    private var hasCargo = false
    private var shown = false
    private var generation = 0
    private val handler = Api.main
    private val pollRunnable = Runnable { render() }
    private var watch: Runnable? = null
    private var window: DeviceScreenWindow? = null
    private val prefs = ctx.getSharedPreferences("forge-android-view", Context.MODE_PRIVATE)

    // Inputs that keep their text across renders (the page rebuilt them, but the native view keeps what you typed).
    private val pairAddress = Ui.input(ctx, "Pairing address and port (shown under Pair device with pairing code)")
    private val pairCode = Ui.input(ctx, "Six-digit pairing code")
    private val connectAddress = Ui.input(ctx, "Device address and port (shown under Wireless debugging)")
    private val labProject = Ui.input(ctx, "Google Cloud project ID")
    private val labReservation = Ui.input(ctx, "Reservation ID (from My reservations)")
    private val samsungAddress = Ui.input(ctx, "localhost:PORT shown by Remote Debug Bridge")
    private val createLabel = Ui.input(ctx, "Label for the new device (optional)")

    // Google's device lab: its status is read on every render as on the page, but at most once a minute during
    // the 2.5 second polling, and the model list once (the server caches it for six hours).
    private var googleStatus: JSONObject? = null
    private var googleStatusAt = 0L
    private var googleModels: JSONObject? = null

    private val kindNames = mapOf("googlebook" to "Googlebook", "virtual" to "Virtual device", "waydroid" to "Waydroid", "phone" to "Phone",
        "tablet" to "Tablet", "foldable" to "Foldable", "watch" to "Watch", "tv" to "TV")

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Ui.panel)
        view.addView(SideKit.header(ctx, "Android", Ui.miniButton(ctx, R.drawable.ic_refresh, "Refresh") { googleStatusAt = 0; googleModels = null; render() }))
        view.addView(Ui.divider(ctx, false))
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(10), dp(4), dp(10), dp(16))
        view.addView(Ui.scroll(ctx, content), Ui.lp(Ui.match, 0, 1f))
        pairCode.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        pairCode.filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        labProject.setText(prefs.getString("labProject", "") ?: "")
        content.addView(hint("Open the view to read the devices and toolchains."))
    }

    /** The main window calls this when the view becomes visible: the lists are read, and polling may start. */
    fun onShown() { shown = true; render() }

    /** The main window calls this when the view is hidden: polling stops (the device window stays open). */
    fun onHidden() { shown = false; handler.removeCallbacks(pollRunnable); generation++ }

    // ---------------------------------------------------------------- loading and polling
    private class Loaded(val dev: JSONObject, val info: JSONObject, val mode: String, val cargo: Boolean)

    /** Reads everything the view shows and rebuilds it (the page's render()). */
    fun render() {
        handler.removeCallbacks(pollRunnable)
        val my = ++generation
        if (content.childCount == 0) content.addView(hint("Checking the devices and toolchains…"))
        Api.async("android-view", {
            val dev = Api.get("/api/devices", timeoutMs = 60000)
            val st = Api.get("/api/android/status", timeoutMs = 60000)
            val mode = try { Api.get("/api/settings").optJSONObject("effective")?.optString("vd_updates", "check") ?: "check" } catch (e: Exception) { "check" }
            val cargo = try {
                Api.get("/api/files", mapOf("q" to "Cargo.toml")).optJSONArray("files")?.let { a -> (0 until a.length()).any { Regex("(^|/)Cargo\\.toml$").containsMatchIn(a.optString(it)) } } ?: false
            } catch (e: Exception) { false }
            Loaded(dev, st, mode, cargo)
        }) { r, e ->
            if (my != generation) return@async
            if (r == null) {
                content.removeAllViews()
                content.addView(hint(e?.message ?: "The devices could not be read."))
                return@async
            }
            devices = r.dev; info = r.info; vdMode = r.mode; hasCargo = r.cargo
            rebuild()
            keepPolling()
        }
    }

    private fun rebuild() {
        val dev = devices ?: return
        content.removeAllViews()
        content.addView(devicesSection(dev))
        content.addView(virtualSection(dev))
        content.addView(labSection())
        content.addView(pairSection())
        projectSection(dev)?.let { content.addView(it) }
        multiplatformSection()?.let { content.addView(it) }
        toolchainSection()?.let { content.addView(it) }
        rustSection()?.let { content.addView(it) }
        window?.dataChanged()
    }

    /** The page polled every 2.5 seconds while a device, a download or an image update was in motion, and only while the view was shown. */
    private fun keepPolling() {
        handler.removeCallbacks(pollRunnable)
        val v = devices?.optJSONObject("virtual") ?: return
        val u = v.optJSONObject("updates")
        val active = (u != null && (u.optBoolean("checking") || u.optString("applying").isNotEmpty()))
            || v.optJSONArray("devices").objs().any { it.optString("phase") in setOf("starting", "booting", "stopping") }
            || v.optJSONArray("versions").objs().any { it.optJSONObject("job")?.optBoolean("running") == true }
        if (active && shown) handler.postDelayed(pollRunnable, 2500)
    }

    // ---------------------------------------------------------------- devices
    private fun devicesSection(dev: JSONObject): View {
        val box = Ui.column(ctx)
        val list = dev.optJSONArray("devices").objs()
        val target = dev.optJSONObject("target")
        var online = 0
        for (d in list) {
            val isTarget = same(d, target)
            val state = d.optString("state")
            if (state == "device") online++
            val kind = d.optString("kind")
            val facts = listOfNotNull(
                d.optString("android").ifEmpty { null }?.let { "Android $it" },
                d.optString("one_ui").ifEmpty { null },
                if (d.optInt("width") > 0) d.optInt("width").toString() + "×" + d.optInt("height") else null,
                kindNames[kind] ?: kind.ifEmpty { null }).joinToString(", ")
            val acts = FlowRow(ctx)
            if (state == "device" && !isTarget) acts.addView(btn("Use", primary = true, tooltip = "Builds, installs, logcat and adb use this device") { useDevice(d) })
            if (state == "device") {
                acts.addView(btn("Screen") { openScreen(d) })
                acts.addView(btn("Screenshot to chat") { screenshotToChat(d) })
            }
            if (kind != "googlebook" && kind != "virtual") acts.addView(btn("Disconnect") {
                postAction("/api/devices/disconnect", JSONObject().put("serial", d.optString("serial")).put("port", d.optInt("port")))
            })
            val badges = if (isTarget) listOf(badge("target", false)) else emptyList()
            box.addView(deviceRow(isTarget, if (state == "device") Ui.green else Ui.red, d.optString("name").ifEmpty { d.optString("serial") }, badges,
                if (state == "device") facts else state + " (" + d.optString("serial") + ")", acts))
        }
        if (list.none { it.optString("kind") == "googlebook" }) {
            box.addView(actions(btn("Connect this Googlebook") { postAction("/api/android/connect", JSONObject()) }))
        }
        if (online == 0) box.addView(muted("No device is connected. Connect the Googlebook, start a virtual device or pair a device below."))
        return section("Devices", box, hint("The target device is where Build, install and run, Logcat and adb in the terminal and in the agent's commands go. The Googlebook's own screen is view only."))
    }

    private fun useDevice(d: JSONObject) {
        postAction("/api/devices/target", JSONObject().put("serial", d.optString("serial")).put("port", d.optInt("port")))
    }

    /** POSTs an action whose reply is {ok, message}, shows the message and reads the lists again. */
    private fun postAction(path: String, body: JSONObject, long: Boolean = false, rerender: Boolean = true, then: ((JSONObject) -> Unit)? = null) {
        Api.async("android-action", { Api.post(path, body, 120000) }) { r, e ->
            if (r == null) { notice(e?.message ?: "The action failed.", true); return@async }
            notice(r.optString("message").ifEmpty { if (r.optBoolean("ok")) "Done." else "The action failed." }, long || !r.optBoolean("ok"))
            then?.invoke(r)
            if (rerender) render()
        }
    }

    // ---------------------------------------------------------------- virtual devices
    private fun virtualSection(dev: JSONObject): View {
        val v = dev.optJSONObject("virtual") ?: JSONObject()
        val box = Ui.column(ctx)
        if (!v.optBoolean("available")) {
            return section("Virtual devices", hint("Virtual devices need Podman and sudo without a password in the Linux VM. See the manual, section \"Virtual devices\"."))
        }
        val guard = v.optBoolean("guard")
        if (!guard) box.addView(warn("The Forge Android guard service is not running. It protects the Linux VM while Android runs, so virtual devices will not start. Install it with guard/install.sh in the Forge folder."))
        for (n in v.optJSONArray("notices").objs()) box.addView(warn(n.optString("message")))
        val sh = v.optJSONObject("shield") ?: JSONObject()
        if (guard && sh.optString("warning").isNotEmpty()) box.addView(warn(sh.optString("warning")))
        else if (guard && sh.has("fresh") && !sh.optBoolean("fresh")) box.addView(warn("The guard's VM shield has not reported for over a minute. Check it with: systemctl status forge-android-guard"))
        else if (guard && sh.optBoolean("fresh")) {
            val fixed = sh.optInt("fixed")
            box.addView(hint("VM shield on: Android cannot suspend, hibernate, restart or crash the Linux VM, and Android's changes to the VM's kernel settings are put back." +
                (if (fixed > 0) " In the last 15 minutes it put back $fixed " + (if (fixed == 1) "change." else "changes.") else "")))
        }
        val profiles = dev.optJSONObject("profiles") ?: JSONObject()
        val target = dev.optJSONObject("target")
        val liveDevices = dev.optJSONArray("devices").objs()
        for (d in v.optJSONArray("devices").objs()) {
            val phase = d.optString("phase")
            val busy = phase in setOf("starting", "booting", "stopping")
            val running = d.optBoolean("running")
            val live = liveDevices.firstOrNull { it.optString("serial") == d.optString("serial") && it.optInt("port") == 5041 && it.optString("state") == "device" }
            val badges = ArrayList<View>()
            if (d.optString("persona").isNotEmpty()) badges.add(badge("persona", false, "Android with this device's screen, identity, features and battery behavior. It is not the maker's own interface; for that, use a real device or a device lab."))
            badges.add(badge(phase, true))
            val profileLabel = profiles.optJSONObject(d.optJSONObject("look")?.optString("profile") ?: "")?.optString("label") ?: ""
            val facts = listOf(d.optString("version_label"), profileLabel, if (d.optInt("memory_mb") > 0) d.optInt("memory_mb").toString() + " MB in use" else "", d.optString("message"))
                .filter { it.isNotEmpty() }.joinToString(", ")
            val acts = FlowRow(ctx)
            if (running) acts.addView(btn("Stop", enabled = !busy) { vdAction("/api/vd/stop", d) })
            else acts.addView(btn("Start", primary = true, enabled = !busy, tooltip = "Starts the device in the Linux VM; it becomes the target device when it is ready") { vdAction("/api/vd/start", d) })
            if (live != null) acts.addView(btn("Screen") { openScreen(live) })
            if (live != null && !same(live, target)) acts.addView(btn("Use") { useDevice(live) })
            acts.addView(btn("Rename") {
                SideKit.askText(ctx, "Rename " + d.optString("name"), d.optString("name"), "New name", "Rename") { name ->
                    postAction("/api/vd/rename", JSONObject().put("id", d.optString("id")).put("name", name))
                }
            })
            val upgrades = d.optJSONArray("upgrades").objs()
            if (!running && upgrades.isNotEmpty()) {
                val up = upgrades.last()
                acts.addView(btn("Upgrade to " + up.optString("label"),
                    tooltip = "Moves this device to the newer installed version of its family and keeps its apps and data; Android migrates them on the next start, as a phone does after a system upgrade") {
                    postAction("/api/vd/move", JSONObject().put("id", d.optString("id")).put("version", up.optString("version")), long = true)
                })
            }
            box.addView(deviceRow(live != null && same(live, target), if (running) Ui.green else Ui.red, d.optString("name"), badges, facts, acts))
        }
        box.addView(updatesBlock(v))
        box.addView(createBlock(dev, v))
        return section("Virtual devices", box, hint("Real Android running in the Linux VM, without hardware virtualization. One device needs its memory limit (2.5 to 3 GB) plus 2.3 GB free on the Googlebook, so stop it, or unload the Llama model, when memory is short. Screen profiles, dark theme and text size change live in the device window. Watches are watch-sized, round screens of phone Android, because Wear OS itself cannot run here. Versions marked (ARM, emulated) are the makers' own Android under ARM emulation: real but slow, 15 to 30 minutes to the first screen."))
    }

    /** The create form: version, device type, persona and label, with the download hint of the chosen version. */
    private fun createBlock(dev: JSONObject, v: JSONObject): View {
        val col = Ui.column(ctx)
        val versions = v.optJSONArray("versions").objs()
        val types = v.optJSONObject("types") ?: JSONObject()
        val profiles = dev.optJSONObject("profiles") ?: JSONObject()
        val personas = dev.optJSONArray("personas").objs()
        val ver = Dropdown(ctx, "Android version")
        ver.setItems(versions.map { Dropdown.Item(it.optString("version"), it.optString("label") + (if (it.optBoolean("installed")) "" else " (download first)")) },
            prefs.getString("createVersion", "") ?: "")
        val typ = Dropdown(ctx, "Device type and screen")
        val typeKeys = types.keys().asSequence().toList()
        val pers = Dropdown(ctx, "Persona: Android dressed as a specific device (its screen, cutout, identity, features and the maker's battery behavior)")
        val persItems = ArrayList<Dropdown.Item>()
        persItems.add(Dropdown.Item("", "No persona"))
        for (x in personas) persItems.add(Dropdown.Item(x.optString("id"), x.optString("name") + " persona" + (if (x.optString("certainty") == "exact") "" else " (approximate)"),
            group = x.optString("maker") + " (" + x.optString("skin") + ")"))
        pers.setItems(persItems, "")
        val persHint = hint("")
        persHint.visibility = View.GONE
        val dl = Ui.column(ctx)
        fun showDownload() {
            dl.removeAllViews()
            val x = versions.firstOrNull { it.optString("version") == ver.value } ?: return
            val job = x.optJSONObject("job")
            if (job != null && job.optBoolean("running")) {
                val lines = job.optJSONArray("lines")
                val last = if (lines != null && lines.length() > 0) lines.optString(lines.length() - 1) else "starting"
                dl.addView(hint("Downloading " + x.optString("label") + ": " + last))
            } else if (!x.optBoolean("installed")) {
                val backend = x.optString("backend")
                dl.addView(hint(x.optString("label") + " (" + x.optString("detail") + ") is not downloaded yet: " + x.optString("size") + "." +
                    (if (backend == "waydroid") " It is LineageOS from the WayDroid-ATV project, a community build made for containers; Forge checks the published checksums." +
                        (if (x.optString("version").contains("google")) " Its Google apps are the ones that project includes. Signing in to Google Play may first need the device registered at google.com/android/uncertified." else "")
                    else " Android 11 to 16 come from the open-source Redroid project.")))
                dl.addView(actions(btn("Download " + x.optString("label")) {
                    postAction("/api/vd/download", JSONObject().put("version", x.optString("version")), long = true)
                }))
            } else if (job != null && job.has("ok") && !job.isNull("ok") && !job.optBoolean("ok")) {
                val lines = job.optJSONArray("lines")
                val tail = if (lines != null) (maxOf(0, lines.length() - 2) until lines.length()).joinToString(" ") { lines.optString(it) } else ""
                dl.addView(warn("The last download failed: $tail"))
            }
        }
        fun versionChanged() {
            prefs.edit().putString("createVersion", ver.value).apply()
            val x = versions.firstOrNull { it.optString("version") == ver.value }
            val allowed = x?.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            typ.setItems(typeKeys.map { k -> Dropdown.Item(k, profiles.optJSONObject(types.optString(k))?.optString("label") ?: k, enabled = allowed == null || k in allowed) }, typ.value)
            showDownload()
        }
        pers.onChange = { id ->
            val x = personas.firstOrNull { it.optString("id") == id }
            typ.enabled = x == null
            if (x == null) { persHint.visibility = View.GONE; persHint.text = "" } else {
                val behavior = x.optString("behavior")
                persHint.visibility = View.VISIBLE
                persHint.text = x.optString("name") + ": " + x.optInt("width") + "×" + x.optInt("height") + " at " + x.optInt("density") + " dpi" +
                    (if (x.optJSONObject("cutout") != null) ", camera hole" else "") + ", " + x.optString("maker") + " identity and features" +
                    (if (behavior.isNotEmpty() && behavior != "stock" && behavior != "pixel") ", " + x.optString("maker") + " battery behavior available" else "") + ". " +
                    (if (x.optString("certainty") == "exact") "Screen values from the maker's published data or the device's own source tree. " else "Some screen values are approximate. ") + x.optString("note")
            }
        }
        ver.onChange = { versionChanged() }
        versionChanged()
        val create = btn("Create") {
            val x = versions.firstOrNull { it.optString("version") == ver.value }
            if (x == null) { notice("Choose an Android version.", false); return@btn }
            if (!x.optBoolean("installed")) { notice("Download " + x.optString("label") + " first.", false); return@btn }
            val body = JSONObject().put("version", ver.value).put("type", typ.value).put("persona", pers.value).put("name", createLabel.text.toString().trim())
            postAction("/api/vd/create", body) { createLabel.setText("") }
        }
        val form = FlowRow(ctx)
        form.addView(ver.view, full()); form.addView(typ.view, full()); form.addView(pers.view, full()); form.addView(own(createLabel), full()); form.addView(create)
        form.setPadding(0, dp(6), 0, dp(6))
        col.addView(form); col.addView(persHint); col.addView(dl)
        return col
    }

    /** Updates and upgrades of the images: what the daily check found, buttons to install, and the daily mode. */
    private fun updatesBlock(v: JSONObject): View {
        val u = v.optJSONObject("updates") ?: JSONObject()
        val applying = u.optString("applying")
        val checking = u.optBoolean("checking")
        val available = u.optInt("available")
        val col = rowCard(false)
        val state = if (checking) "checking" else if (applying.isNotEmpty()) "installing $applying" else if (available > 0) "$available available" else "current"
        col.addView(nameLine(0, "Updates and upgrades", listOf(badge(state, true))))
        val when_ = if (u.optDouble("checked", 0.0) > 0) formatTime(u.optDouble("checked")) else "never"
        col.addView(facts("Last check: " + when_ + (if (u.optString("error").isNotEmpty()) ". " + u.optString("error") else "") +
            ". Every image comes from its project's own release feed: a newer build of the same version is an update, and the same family for a newer Android is an upgrade."))
        val errors = u.optJSONArray("errors")
        if (errors != null) for (i in 0 until errors.length()) col.addView(warn("The check could not reach " + errors.optString(i)))
        for (it in u.optJSONArray("items").objs()) {
            val update = it.optJSONObject("update"); val upgrade = it.optJSONObject("upgrade")
            val err = it.optString("error"); val blocked = it.optString("upgrade_blocked")
            if (update == null && upgrade == null && err.isEmpty() && blocked.isEmpty()) continue
            val sb = StringBuilder(it.optString("label") + ": ")
            if (err.isNotEmpty()) sb.append("check failed: ").append(err)
            if (update != null) sb.append("update available, ").append(update.optString("label")).append(" (").append(update.optString("size")).append("). ")
            if (upgrade != null) sb.append(if (update != null) " " else "").append("upgrade available: ").append(upgrade.optString("label")).append(" (").append(upgrade.optString("size")).append(")")
                .append(if (upgrade.optBoolean("installed")) ", installed; devices can move to it with their Upgrade button. " else ". ")
            if (blocked.isNotEmpty()) sb.append(" ").append(blocked)
            col.addView(facts(sb.toString()))
            val acts = FlowRow(ctx)
            if (update != null) acts.addView(btn("Update", primary = true, enabled = applying.isEmpty()) {
                postAction("/api/vd/updates/apply", JSONObject().put("version", it.optString("version")).put("kind", "update"), long = true)
            })
            if (upgrade != null && !upgrade.optBoolean("installed")) acts.addView(btn("Upgrade", primary = true, enabled = applying.isEmpty()) {
                postAction("/api/vd/updates/apply", JSONObject().put("version", it.optString("version")).put("kind", "upgrade"), long = true)
            })
            if (acts.childCount > 0) col.addView(actions(acts))
        }
        val mode = Dropdown(ctx, "What the daily run does by itself")
        mode.setItems(listOf(
            Dropdown.Item("check", "Daily: check and show what is available"),
            Dropdown.Item("install", "Daily: check, download and install updates and upgrades"),
            Dropdown.Item("install-move", "Daily: install, and move stopped devices to the newest version"),
            Dropdown.Item("off", "Never check by itself")), vdMode)
        mode.onChange = { m ->
            Api.async("vd-mode", { Api.post("/api/settings", JSONObject().put("vd_updates", m).put("_scope", "user")) }) { r, e ->
                if (r == null) notice(e?.message ?: "Not saved.", true) else { vdMode = m; notice("Saved.", false) }
            }
        }
        val acts = FlowRow(ctx)
        acts.addView(btn("Check now", enabled = !checking) {
            postAction("/api/vd/updates/check", JSONObject(), long = true, rerender = false) { handler.postDelayed({ render() }, 4000) }
        })
        acts.addView(btn("Update and upgrade everything", primary = true, enabled = applying.isEmpty() && available > 0,
            tooltip = "Installs every available update and upgrade, one after another; devices then move with their Upgrade button, or by themselves in the third daily mode") {
            postAction("/api/vd/updates/apply_all", JSONObject(), long = true)
        })
        acts.addView(mode.view, full())
        col.addView(actions(acts))
        for (l in u.optJSONArray("log").objs()) col.addView(muted(formatTime(l.optDouble("time")) + " " + l.optString("message")))
        return col
    }

    private fun vdAction(path: String, d: JSONObject) {
        postAction(path, JSONObject().put("id", d.optString("id")), long = true)
    }

    // ---------------------------------------------------------------- device labs (the makers' real software)
    private fun labSection(): View {
        val box = Ui.column(ctx)
        box.addView(hint("Samsung's One UI, Motorola's My UX, OnePlus's OxygenOS and Google's Pixel UI are built for the makers' ARM phones, so besides the emulated ARM devices Forge connects real devices: yours over Wi-Fi (below), or a device in Google's or Samsung's lab. A lab device appears under Devices and works in the device window, with installs, logcat and the debugger."))
        // Google's device lab
        val g = labCard("Google's device lab", "Real Pixel, Samsung Galaxy, Motorola, OnePlus, OPPO, realme, Xiaomi, Sony and Nothing phones, a Pixel Tablet and a Pixel Watch, reserved through Google's Android command line tool.")
        val gBody = Ui.column(ctx)
        gBody.addView(hint("Checking…"))
        g.addView(gBody)
        box.addView(g)
        val my = generation
        val cached = googleStatus
        if (cached != null && System.currentTimeMillis() - googleStatusAt < 60000) fillGoogle(gBody, cached)
        else Api.async("cloud-google", { Api.get("/api/cloud/google", timeoutMs = 60000) }) { st, e ->
            if (my != generation) return@async
            gBody.removeAllViews()
            if (st == null) { gBody.addView(hint(e?.message ?: "Google's lab could not be read.")); return@async }
            googleStatus = st; googleStatusAt = System.currentTimeMillis()
            fillGoogle(gBody, st)
        }
        // Samsung's Remote Test Lab
        val s = labCard("Samsung Remote Test Lab (free)",
            "Real Galaxy phones, foldables, tablets and Galaxy Watches with One UI, including One UI 9 on Android 17. Sign in on Samsung's site for 20 free credits a day (one credit is 15 minutes) and reserve a device there:")
        s.addView(link("https://developer.samsung.com/remotetestlab/"))
        s.addView(hint("To use it in Forge: in the lab's device screen, open Remote Debug Bridge and download Samsung's rdb program for Linux, save it in Documents, unzip it and run it in Forge's terminal (cd /mnt/shared/Documents && unzip rdb.zip && ./rdb), click Connect on Samsung's page, then enter the localhost port it shows here. Accept the \"Allow USB debugging\" prompt in the lab's screen the first time."))
        val sBody = Ui.column(ctx)
        val sForm = FlowRow(ctx)
        sForm.addView(own(samsungAddress), full())
        sForm.addView(btn("Connect") { postAction("/api/devices/connect", JSONObject().put("address", samsungAddress.text.toString().trim()), long = true) })
        sForm.addView(btn("What is in the lab") {
            sBody.removeAllViews(); sBody.addView(hint("Reading Samsung's device list…"))
            Api.async("cloud-samsung", { Api.get("/api/cloud/samsung", timeoutMs = 90000) }) { r, e ->
                sBody.removeAllViews()
                if (r == null) { sBody.addView(hint(e?.message ?: "Samsung's device list could not be read.")); return@async }
                if (!r.optBoolean("ok")) { sBody.addView(hint(r.optString("message"))); return@async }
                sBody.addView(hint(r.optInt("units").toString() + " Android devices in Samsung's labs (and " + r.optInt("tizen_units") + " Samsung TVs, which run Tizen, not Android). The newest:"))
                for (p in r.optJSONArray("products").objs().take(25)) {
                    val vs = p.optJSONArray("versions")
                    val two = if (vs != null) (0 until minOf(2, vs.length())).joinToString(", ") { vs.optString(it) } else ""
                    sBody.addView(factRow(null, p.optString("name"), two + " (" + p.optInt("units") + ")"))
                }
            }
        })
        s.addView(sForm); s.addView(sBody)
        box.addView(s)
        // Amazon
        val a = labCard("Amazon Fire TV", "Amazon has no emulator for Fire OS. Its free Appstore Quality Central gives every developer cloud Fire TV devices in the browser:")
        a.addView(link("https://developer.amazon.com/docs/app-testing/live-device-interaction-virtual.html"))
        a.addView(hint("In Forge, the Fire TV Stick 4K Max persona gives Android TV Fire TV's screen, identity and features."))
        box.addView(a)
        return section("Device labs: the makers' real software", box)
    }

    private fun fillGoogle(gBody: LinearLayout, st: JSONObject) {
        gBody.removeAllViews()
        if (!st.optBoolean("installed")) { gBody.addView(hint("Google's Android command line tool is not installed in the Linux VM.")); return }
        val signedIn = st.optBoolean("signed_in")
        if (!signedIn) {
            gBody.addView(hint("Sign in once with your Google account: run \"android auth login\" in a terminal and follow the link it shows. Forge never sees your password."))
            gBody.addView(actions(btn("Open a terminal for sign-in") { terminal("android auth login", "Google sign-in") }))
        }
        val price = st.optString("price")
        gBody.addView(hint(price))
        val pick = Dropdown(ctx, "Device and Android version")
        pick.setItems(listOf(Dropdown.Item("", "Loading the lab's devices…")), "")
        val out = Ui.text(ctx, "", 11f, Ui.fg2, mono = true)
        out.setTextIsSelectable(true)
        out.visibility = View.GONE
        val reserve = btn("Reserve", primary = true, enabled = signedIn) {
            val target = pick.value; val pj = labProject.text.toString().trim()
            if (target.isEmpty() || pj.isEmpty()) { notice("Choose a device and enter your project ID.", false); return@btn }
            prefs.edit().putString("labProject", pj).apply()
            SideKit.confirm(ctx, "Reserve " + pick.label() + " in Google's device lab?", price + "\n\nReturn the device when you are done, so it stops costing money.", "Reserve") {
                postAction("/api/cloud/google/reserve", JSONObject().put("target", target).put("project", pj).put("confirmed", true), long = true, rerender = false)
            }
        }
        val list = btn("My reservations", enabled = signedIn) {
            val pj = labProject.text.toString().trim()
            Api.async("cloud-list", { Api.get("/api/cloud/google/list", mapOf("project" to pj), 90000) }) { r, e ->
                out.visibility = View.VISIBLE
                out.text = r?.optString("text")?.ifEmpty { "No reservations." } ?: (e?.message ?: "The list could not be read.")
            }
        }
        val giveBack = btn("Return device", enabled = signedIn) {
            postAction("/api/cloud/google/release", JSONObject().put("reservation", labReservation.text.toString().trim()).put("project", labProject.text.toString().trim()), long = true)
        }
        val extend = btn("Extend 15 minutes", enabled = signedIn) {
            SideKit.confirm(ctx, "Extend this reservation by 15 minutes?", price, "Extend") {
                postAction("/api/cloud/google/extend", JSONObject().put("reservation", labReservation.text.toString().trim()).put("project", labProject.text.toString().trim())
                    .put("minutes", 15).put("confirmed", true), long = true, rerender = false)
            }
        }
        val f1 = FlowRow(ctx); f1.addView(pick.view, full()); f1.addView(own(labProject), full()); f1.addView(reserve)
        val f2 = FlowRow(ctx); f2.addView(list); f2.addView(own(labReservation), full()); f2.addView(giveBack); f2.addView(extend)
        gBody.addView(f1); gBody.addView(f2); gBody.addView(out)
        val jobs = st.optJSONObject("jobs")
        if (jobs != null) for (k in jobs.keys()) {
            val j = jobs.optJSONObject(k) ?: continue
            val lines = j.optJSONArray("lines")
            val tail = if (lines != null && lines.length() > 0) ". " + (maxOf(0, lines.length() - 3) until lines.length()).joinToString(" ") { lines.optString(it) } else ""
            val text = j.optString("what") + (if (j.optBoolean("running")) ": running" else if (j.optBoolean("ok")) ": done" else ": failed") + tail
            gBody.addView(if (!j.optBoolean("running") && !j.optBoolean("ok")) warn(text) else hint(text))
        }
        fun fillModels(m: JSONObject) {
            val items = ArrayList<Dropdown.Item>()
            items.add(Dropdown.Item("", if (m.optBoolean("ok")) "Choose a device" else "The device list could not be read"))
            for (mk in m.optJSONArray("makers").objs()) {
                val models = mk.optJSONArray("models").objs()
                val group = mk.optString("maker") + " (" + models.size + ")"
                for (md in models) {
                    val ts = md.optJSONArray("targets") ?: continue
                    for (i in 0 until ts.length()) {
                        val t = ts.optString(i)
                        items.add(Dropdown.Item(t, md.optString("name") + ", Android API " + t.substringAfter('/'), group = group))
                    }
                }
            }
            pick.setItems(items, "")
        }
        val models = googleModels
        if (models != null) fillModels(models)
        else Api.async("cloud-models", { Api.get("/api/cloud/google/models", timeoutMs = 120000) }) { m, e ->
            if (m == null) { pick.setItems(listOf(Dropdown.Item("", e?.message ?: "The device list could not be read")), ""); return@async }
            googleModels = m
            if (pick.view.isAttachedToWindow) fillModels(m)
        }
    }

    private fun labCard(title: String, about: String): LinearLayout {
        val c = Ui.column(ctx, Ui.text(ctx, title, 12.5f, Ui.fg, bold = true), hint(about))
        c.background = Ui.rounded(Ui.bg2, 6, Ui.border)
        c.setPadding(dp(10), dp(8), dp(10), dp(8))
        c.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = dp(6) }
        return c
    }

    /** A web address, shown in the accent color; a click opens it in the system's browser, as the page's link did. */
    private fun link(url: String): TextView {
        val t = Ui.text(ctx, url, 11.5f, Ui.accent)
        t.setPadding(0, dp(2), 0, dp(4))
        t.setOnClickListener {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { host.copyToClipboard(url); notice("No browser could open it; the address is on the clipboard.", true) }
        }
        return t
    }

    // ---------------------------------------------------------------- pairing
    private fun pairSection(): View {
        val found = Ui.column(ctx)
        val f1 = FlowRow(ctx)
        f1.addView(own(pairAddress), full()); f1.addView(own(pairCode), full())
        f1.addView(btn("Pair", primary = true) {
            val body = JSONObject().put("address", pairAddress.text.toString()).put("code", pairCode.text.toString())
            postAction("/api/devices/pair", body, long = true, rerender = false) { r ->
                if (r.optBoolean("ok")) { pairCode.setText(""); notice("Paired. Now enter the address shown under Wireless debugging (not the pairing one) and click Connect.", true) }
            }
        })
        val f2 = FlowRow(ctx)
        f2.addView(own(connectAddress), full())
        f2.addView(btn("Connect") {
            postAction("/api/devices/connect", JSONObject().put("address", connectAddress.text.toString()), long = true, rerender = false) { r -> if (r.optBoolean("ok")) render() }
        })
        f2.addView(btn("Look for devices", tooltip = "Devices that announce Wireless debugging on the network (the Linux VM often cannot see these announcements)") {
            found.removeAllViews()
            Api.async("devices-scan", { Api.get("/api/devices/scan", timeoutMs = 30000) }) { r, e ->
                found.removeAllViews()
                if (r == null) { found.addView(muted(e?.message ?: "The scan failed.")); return@async }
                val list = r.optJSONArray("found").objs()
                if (list.isEmpty()) found.addView(muted("No announcements reached the Linux VM. Type the address shown on the device instead."))
                for (x in list) found.addView(factRow(Ui.green, x.optString("service"), x.optString("name") + " " + x.optString("address")))
            }
        })
        return section("Pair a phone, tablet, watch or TV",
            hint("On the device: Developer options, Wireless debugging, Pair device with pairing code. Galaxy Watch and Wear OS watches have the same option under Developer options (on Galaxy Watch, tap Software version five times first). The device and this Googlebook must be on the same Wi-Fi."),
            f1, f2, found)
    }

    // ---------------------------------------------------------------- this project, Kotlin Multiplatform, toolchains, Rust
    private fun targetName(): String {
        val t = devices?.optJSONObject("target") ?: return "no device"
        return t.optString("name").ifEmpty { t.optString("serial") }
    }

    private fun projectSection(dev: JSONObject): View? {
        val p = info?.optJSONObject("project") ?: return null
        if (!p.optBoolean("android")) return null
        val t = dev.optJSONObject("target")
        val box = Ui.column(ctx)
        val modules = p.optJSONArray("modules").objs()
        appId = modules.firstOrNull { it.optBoolean("application") && it.optString("application_id").isNotEmpty() }?.optString("application_id") ?: ""
        for (m in modules.filter { it.optBoolean("application") }) {
            val apk = m.optString("apk")
            val id = m.optString("application_id")
            box.addView(factRow(if (apk.isNotEmpty()) Ui.green else Ui.red, m.optString("module") + " (" + id.ifEmpty { "unknown id" } + ")",
                if (apk.isNotEmpty()) "APK built " + formatTime(m.optDouble("apk_time")) else "not built yet"))
            val acts = FlowRow(ctx)
            acts.addView(btn("Build, install and run", primary = true, tooltip = "Builds the debug APK in a terminal, then installs it on " + targetName() + " and starts it") { buildInstallRun(m, true) })
            acts.addView(btn("Build debug APK") { buildInstallRun(m, false) })
            acts.addView(btn("Install", enabled = apk.isNotEmpty() && t != null) { installApk(m) { } })
            acts.addView(btn("Run", enabled = id.isNotEmpty() && t != null) { launchApp(m) })
            acts.addView(btn("Debug on device", enabled = id.isNotEmpty() && t != null, tooltip = "Starts the installed debug build on " + targetName() + " and attaches the debugger (breakpoints in the gutter, F9)") {
                val f = attachDebugger
                if (f == null) notice("The debugger is not connected to the Android view yet.", true) else f(id)
            })
            acts.addView(btn("App logcat", enabled = id.isNotEmpty()) {
                val f = openLogcat
                if (f == null) notice("Open the LOGCAT tab in the bottom panel and enter the app id $id.", true) else f(id)
            })
            acts.addView(btn("Compose previews", tooltip = "Renders every @Preview of this module as pictures on phone, foldable, tablet, round and square watch and TV screens, without a device (forge-preview)") {
                val mod = m.optString("module")
                terminal("forge-preview " + (if (mod == ".") ":" else ":" + mod.split("/").joinToString(":")), "Compose previews")
            })
            box.addView(actions(acts))
        }
        val more = FlowRow(ctx)
        more.addView(btn("Lint") { terminal("forge-gradle lint", "Android lint") })
        more.addView(btn("Unit tests") { terminal("forge-gradle test", "Gradle tests") })
        more.addView(btn("Release bundle (AAB)") { terminal("forge-gradle bundleRelease", "Gradle bundle") })
        box.addView(actions(more))
        if (!p.optBoolean("gradlew")) box.addView(hint("This project has no gradlew; forge-gradle uses Gradle " + (info?.optJSONObject("toolchains")?.optString("gradle") ?: "") + " instead."))
        box.addView(hint("Target device: " + targetName() + "."))
        return section("This Android project", box)
    }

    /** Kotlin Multiplatform modules: build, test and run each target with forge-gradle. iOS needs macOS and Xcode. */
    private fun multiplatformSection(): View? {
        val mods = info?.optJSONArray("multiplatform").objs() ?: emptyList()
        if (mods.isEmpty()) return null
        val names = mapOf("jvm" to "JVM", "desktop" to "Desktop", "android" to "Android", "js" to "JavaScript", "wasmJs" to "Wasm", "ios" to "iOS", "iosArm64" to "iOS",
            "iosX64" to "iOS", "iosSimulatorArm64" to "iOS", "native" to "Native", "apple" to "Apple", "web" to "Web", "linuxX64" to "Linux", "macos" to "macOS")
        val box = Ui.column(ctx)
        for (m in mods) {
            val path = m.optString("gradle_path")
            val targets = m.optJSONArray("targets")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val shown = targets.map { names[it] ?: it }.distinct()
            box.addView(factRow(Ui.green, m.optString("module"), "targets: " + (shown.joinToString(", ").ifEmpty { "none found" })))
            fun task(text: String, task: String, title: String? = null) = btn(text, tooltip = title ?: task) { terminal("forge-gradle $path:$task", text) }
            val acts = FlowRow(ctx)
            acts.addView(task("Check common code", "compileCommonMainKotlinMetadata"))
            if ("jvm" in targets) { acts.addView(task("Build JVM", "compileKotlinJvm")); acts.addView(task("JVM tests", "jvmTest")) }
            if ("desktop" in targets) acts.addView(task("Build desktop", "compileKotlinDesktop"))
            if (m.optBoolean("compose_desktop")) acts.addView(task("Run desktop app", "run", "Compose Multiplatform desktop app (it opens in the Linux VM's display)"))
            if ("android" in targets && m.optBoolean("android_app")) acts.addView(task("Build Android", "assembleDebug"))
            if ("js" in targets) acts.addView(task("Run in browser (JS)", "jsBrowserDevelopmentRun"))
            if ("wasmJs" in targets) acts.addView(task("Run in browser (Wasm)", "wasmJsBrowserDevelopmentRun"))
            acts.addView(task("All tests", "allTests"))
            box.addView(actions(acts))
            if (targets.any { it.startsWith("ios") || it == "apple" || it.startsWith("macos") })
                box.addView(hint("iOS and macOS targets can only be compiled on a Mac with Xcode. Forge still checks their shared Kotlin code, but not their Apple APIs."))
        }
        box.addView(hint("Code intelligence treats each source set as its own module (commonMain, jvmMain, androidMain and so on), so expect and actual declarations link up and Android APIs only resolve in Android code. JavaScript, Wasm and iOS sets are checked as shared Kotlin; their platform APIs are not known to the Kotlin server."))
        return section("Kotlin Multiplatform", box)
    }

    private fun toolchainSection(): View? {
        val t = info?.optJSONObject("toolchains") ?: return null
        fun strs(key: String): List<String> = t.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        fun s(key: String): String = if (t.isNull(key)) "" else t.optString(key)
        fun b(key: String): Boolean = !t.isNull(key) && (t.optBoolean(key) || s(key).isNotEmpty())
        val tc = Ui.column(ctx)
        val sdk = s("android_sdk")
        tc.addView(factRow(okColor(sdk.isNotEmpty()), "Android SDK", if (sdk.isNotEmpty()) "platforms " + strs("platforms").joinToString(", ") else "not found"))
        val bt = strs("build_tools")
        tc.addView(factRow(okColor(bt.isNotEmpty()), "Build tools", bt.joinToString(", ").ifEmpty { "none" }))
        val ndk = s("ndk"); val cmake = s("cmake")
        tc.addView(factRow(okColor(ndk.isNotEmpty()), "NDK and CMake", listOf(ndk, if (cmake.isNotEmpty()) "cmake $cmake" else "").filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "not installed" }))
        tc.addView(factRow(okColor(b("sdkmanager")), "SDK tools", listOf("sdkmanager", "lint", "apkanalyzer", "aapt2", "d8", "apksigner", "bundletool").filter { b(it) }.joinToString(", ")))
        tc.addView(factRow(okColor(s("adb").isNotEmpty()), "adb", s("adb").ifEmpty { "not found" }))
        tc.addView(factRow(okColor(s("java").isNotEmpty()), "Java", s("java").ifEmpty { "not found" }))
        tc.addView(factRow(okColor(s("kotlinc").isNotEmpty()), "Kotlin", s("kotlinc").ifEmpty { "not installed" }))
        tc.addView(factRow(okColor(s("gradle").isNotEmpty()), "Gradle", s("gradle").ifEmpty { "not installed" }))
        tc.addView(factRow(okColor(s("rust").isNotEmpty()), "Rust", s("rust").ifEmpty { "not installed" }))
        val mark = { on: Boolean -> if (on) "yes" else "no" }
        tc.addView(factRow(okColor(b("kotlin_lsp") && b("jdtls")), "Code intelligence",
            "Kotlin " + mark(b("kotlin_lsp")) + ", Java " + mark(b("jdtls")) + ", XML " + mark(b("lemminx")) + ", Rust " + mark(b("rust_analyzer"))))
        tc.addView(hint("Everything is on the PATH in Forge's terminal and for the agent, together with forge-device. Gradle and Cargo builds started with forge-gradle and forge-cargo (and by the agent) check free memory first and run inside a memory cap."))
        return section("Toolchains", tc)
    }

    private fun rustSection(): View? {
        if (!hasCargo || info?.optJSONObject("toolchains")?.optBoolean("cargo") != true) return null
        val acts = FlowRow(ctx)
        for (c in listOf("check", "build", "run", "test", "clippy")) acts.addView(btn("cargo $c") { terminal("forge-cargo $c", "cargo $c") })
        return section("This Rust project", actions(acts))
    }

    // ---------------------------------------------------------------- install, run, build watch, screenshots
    /** Attaches the device's screen (POST /api/android/screenshot, which saves it on the server) to the chat as an image. */
    fun screenshotToChat(d: JSONObject?, then: ((JSONObject) -> Unit)? = null) {
        val t = d ?: devices?.optJSONObject("target")
        val body = JSONObject()
        if (t != null) body.put("serial", t.optString("serial")).put("port", t.optInt("port"))
        Api.async("screenshot", { Api.post("/api/android/screenshot", body, 40000) }) { meta, e ->
            if (meta == null) { notice(e?.message ?: "The screenshot failed.", true); return@async }
            val record = JSONObject(meta.toString()).put("kind", "image").put("name", (t?.optString("name")?.ifEmpty { null } ?: "Device") + " screen")
            val f = attachToChat
            if (f == null) { notice("The chat is not connected to the Android view yet, so the screenshot stays on the server as image " + meta.optString("id") + ".", true); return@async }
            f(record)
            notice("Screenshot attached to the chat.", false)
            then?.invoke(record)
        }
    }

    /** Look: the screen goes to the chat and the vision model is asked to describe it. */
    fun lookAtScreen(d: JSONObject?) {
        screenshotToChat(d) { host.quickAsk("Look at the attached device screen and describe it: which app and screen it shows, the visible texts, buttons and fields, and anything that looks wrong.") }
    }

    private fun installApk(m: JSONObject, then: (Boolean) -> Unit) {
        notice("Installing " + m.optString("apk") + " on " + targetName() + "…", false)
        Api.async("install", { Api.post("/api/android/install", JSONObject().put("apk", m.optString("apk")), 300000) }) { r, e ->
            val ok = r?.optBoolean("ok") == true
            notice(if (ok) "Installed on " + targetName() + "." else "Install failed: " + (r?.optString("message") ?: e?.message ?: ""), true)
            then(ok)
        }
    }

    private fun launchApp(m: JSONObject) {
        val id = m.optString("application_id")
        Api.async("launch", { Api.post("/api/android/launch", JSONObject().put("app_id", id), 60000) }) { r, e ->
            val ok = r?.optBoolean("ok") == true
            notice(if (ok) "Started $id." else "Could not start it: " + (r?.optString("message") ?: e?.message ?: ""), true)
        }
    }

    /** Builds in a terminal (visible, stoppable with Ctrl C); when a newer APK appears, installs and runs it. */
    private fun buildInstallRun(m: JSONObject, thenRun: Boolean) {
        val started = System.currentTimeMillis() / 1000.0
        val module = m.optString("module")
        val cdir = if (module.isNotEmpty() && module != ".") ":" + module.replace("/", ":") + ":" else ""
        terminal("forge-gradle " + cdir + "assembleDebug", "Gradle build")
        if (!thenRun) return
        watch?.let { handler.removeCallbacks(it) }
        var waited = 0
        lateinit var tick: Runnable
        tick = Runnable {
            waited += 5
            if (waited > 1800) { watch = null; return@Runnable }
            Api.async("apk-watch", { Api.get("/api/android/status", timeoutMs = 60000) }) { s, _ ->
                if (watch !== tick) return@async
                val mm = s?.optJSONObject("project")?.optJSONArray("modules").objs()?.firstOrNull { it.optString("module") == module }
                if (mm != null && mm.optString("apk").isNotEmpty() && mm.optDouble("apk_time", 0.0) > started) {
                    watch = null
                    installApk(mm) { ok -> if (ok) launchApp(mm); render() }
                } else handler.postDelayed(tick, 5000)
            }
        }
        watch = tick
        handler.postDelayed(tick, 5000)
    }

    /** A command for a terminal tab; without the terminal hook, the command is shown and put on the clipboard. */
    private fun terminal(command: String, label: String) {
        val f = runInTerminal
        if (f != null) f(command, label)
        else { host.copyToClipboard(command); notice("The terminal is not connected to the Android view yet. The command is on the clipboard: $command", true) }
    }

    // ---------------------------------------------------------------- the device window
    fun openScreen(d: JSONObject) {
        val w = window ?: DeviceScreenWindow(ctx, host, this).also { window = it }
        w.show(d)
    }

    // ---------------------------------------------------------------- small pieces of the look
    private fun dp(v: Int) = Ui.dp(ctx, v)
    private fun okColor(ok: Boolean) = if (ok) Ui.green else Ui.red
    private fun full(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(Ui.match, Ui.wrap)
    private fun same(a: JSONObject?, b: JSONObject?): Boolean = a != null && b != null && a.optString("serial") == b.optString("serial") && a.optInt("port") == b.optInt("port")
    private fun formatTime(seconds: Double): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((seconds * 1000).toLong()))
    private fun notice(s: String, long: Boolean) { Toast.makeText(ctx, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    /** Takes a persistent input out of the row of the previous render before it is added again. */
    private fun own(v: View): View { (v.parent as? ViewGroup)?.removeView(v); return v }

    private fun section(title: String, vararg kids: View): LinearLayout {
        val col = Ui.column(ctx)
        val head = Ui.sectionLabel(ctx, title)
        head.setPadding(0, dp(12), 0, dp(4))
        col.addView(head, Ui.lp(Ui.match, Ui.wrap))
        for (k in kids) col.addView(k, k.layoutParams as? LinearLayout.LayoutParams ?: Ui.lp(Ui.match, Ui.wrap))
        return col
    }

    private fun hint(s: String): TextView {
        val t = Ui.text(ctx, s, 11.5f, Ui.fg3)
        t.setPadding(0, dp(2), 0, dp(4))
        return t
    }

    private fun muted(s: String): TextView = Ui.text(ctx, s, 11.5f, Ui.fg3).also { it.setPadding(0, dp(2), 0, dp(2)) }

    private fun facts(s: String): TextView {
        val t = Ui.text(ctx, s, 12f, Ui.fg2)
        t.setPadding(dp(13), dp(2), 0, 0)
        return t
    }

    private fun warn(s: String): TextView {
        val t = Ui.text(ctx, s, 12f, Ui.fg)
        t.background = Ui.rounded(0x14ffc850, 6, Ui.yellow)
        t.setPadding(dp(8), dp(6), dp(8), dp(6))
        t.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = dp(6) }
        return t
    }

    private fun badge(text: String, dim: Boolean, tooltip: String? = null): TextView {
        val t = Ui.text(ctx, text.uppercase(), 9.5f, if (dim) Ui.fg3 else Ui.accent)
        t.letterSpacing = 0.05f
        t.background = Ui.rounded(0, 99, if (dim) Ui.border2 else Ui.accent)
        t.setPadding(dp(6), 0, dp(6), dp(1))
        t.maxLines = 1
        if (tooltip != null) { t.tooltipText = tooltip; t.contentDescription = tooltip }
        return t
    }

    private fun rowCard(target: Boolean): LinearLayout {
        val col = Ui.column(ctx)
        col.background = Ui.rounded(Ui.bg2, 8, if (target) Ui.accent else Ui.border)
        col.setPadding(dp(8), dp(6), dp(8), dp(6))
        col.layoutParams = Ui.lp(Ui.match, Ui.wrap).also { it.bottomMargin = dp(6) }
        return col
    }

    /** The name line of a device row: a status dot, the bold name and its badges. */
    private fun nameLine(dotColor: Int, name: String, badges: List<View>): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        if (dotColor != 0) { val d = Ui.dot(ctx, dotColor); row.addView(d); (d.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(6) }
        val n = Ui.text(ctx, name, 12.5f, Ui.fg, bold = true)
        n.maxLines = 1; n.ellipsize = TextUtils.TruncateAt.END
        row.addView(n, Ui.lp(0, Ui.wrap, 1f))
        for (b in badges) row.addView(b, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) })
        return row
    }

    private fun deviceRow(target: Boolean, dotColor: Int, name: String, badges: List<View>, factsText: String, acts: FlowRow): LinearLayout {
        val col = rowCard(target)
        col.addView(nameLine(dotColor, name, badges))
        if (factsText.isNotEmpty()) col.addView(facts(factsText))
        if (acts.childCount > 0) col.addView(actions(acts))
        return col
    }

    private fun actions(vararg kids: View): View {
        val f = if (kids.size == 1 && kids[0] is FlowRow) kids[0] as FlowRow else FlowRow(ctx).also { r -> for (k in kids) r.addView(k) }
        f.setPadding(0, dp(4), 0, dp(4))
        return f
    }

    /** One fact line: a dot (or none), a label and a value that wraps (the page's tc-row). */
    private fun factRow(dotColor: Int?, label: String, value: String): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.TOP
        row.setPadding(0, dp(2), 0, dp(2))
        if (dotColor != null) { val d = Ui.dot(ctx, dotColor); row.addView(d); (d.layoutParams as LinearLayout.LayoutParams).let { it.rightMargin = dp(6); it.topMargin = dp(5) } }
        val l = Ui.text(ctx, label, 12f, Ui.fg2)
        l.maxWidth = dp(110)
        row.addView(l, Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = dp(6) })
        row.addView(Ui.text(ctx, value, 12f, Ui.fg), Ui.lp(0, Ui.wrap, 1f))
        return row
    }

    private fun btn(label: String, primary: Boolean = false, enabled: Boolean = true, tooltip: String? = null, onClick: () -> Unit): Button {
        val b = Ui.button(ctx, label, primary) { onClick() }
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.45f
        if (tooltip != null) { b.tooltipText = tooltip; b.contentDescription = tooltip }
        return b
    }
}

/** The objects of a JSON array (missing arrays and non-object items give an empty list or are skipped). */
internal fun JSONArray?.objs(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

/**
 * Children laid out left to right and wrapped into new lines when a line is full, with 6 dp gaps, like the page's
 * flex rows of buttons and inputs. A child whose layout width is MATCH_PARENT takes a whole line.
 */
internal class FlowRow(ctx: Context) : ViewGroup(ctx) {
    private val gap = Ui.dp(ctx, 6)

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p != null
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = LayoutParams(p ?: generateDefaultLayoutParams())

    private fun measureKid(child: View, maxW: Int) {
        val lp = child.layoutParams
        val ws = when {
            lp.width == LayoutParams.MATCH_PARENT -> MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY)
            lp.width > 0 -> MeasureSpec.makeMeasureSpec(minOf(lp.width, maxW), MeasureSpec.EXACTLY)
            else -> MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
        }
        val hs = if (lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        child.measure(ws, hs)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = maxOf(1, MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureKid(c, maxW)
            val cw = c.measuredWidth; val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            x += cw + gap
            lineH = maxOf(lineH, ch)
        }
        val h = (if (childCount == 0) 0 else y + lineH) + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(maxW + paddingLeft + paddingRight, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = maxOf(1, r - l - paddingLeft - paddingRight)
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val cw = c.measuredWidth; val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + cw, paddingTop + y + ch)
            x += cw + gap
            lineH = maxOf(lineH, ch)
        }
    }
}

/**
 * A native stand-in for the page's select element: a bordered box with the chosen label and a small arrow that
 * opens a menu of the items. Items may be grouped (each group becomes a submenu) and disabled.
 */
internal class Dropdown(private val ctx: Context, tooltip: String = "") {
    class Item(val value: String, val label: String, val group: String? = null, val enabled: Boolean = true)

    val view: TextView = TextView(ctx)
    var items: List<Item> = emptyList()
        private set
    var value: String = ""
        private set
    var onChange: ((String) -> Unit)? = null
    var enabled: Boolean = true
        set(v) { field = v; view.isEnabled = v; view.alpha = if (v) 1f else 0.45f }

    init {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        view.setTextColor(Ui.fg)
        view.background = Ui.states(Ui.rounded(Ui.bg3, 6, Ui.border), Ui.rounded(Ui.bg4, 6, Ui.border2), Ui.rounded(Ui.bg4, 6, Ui.accent))
        view.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 7), Ui.dp(ctx, 10), Ui.dp(ctx, 7))
        view.maxLines = 1; view.ellipsize = TextUtils.TruncateAt.END
        view.isClickable = true; view.isFocusable = true
        if (tooltip.isNotEmpty()) { view.tooltipText = tooltip; view.contentDescription = tooltip }
        view.setOnClickListener { open() }
        render()
    }

    /** The label of the chosen item. */
    fun label(): String = items.firstOrNull { it.value == value }?.label ?: ""

    /** Replaces the items; the wanted value stays chosen when it is still an enabled item, otherwise the first enabled one is chosen. */
    fun setItems(list: List<Item>, wanted: String) {
        items = list
        val keep = list.firstOrNull { it.value == wanted && it.enabled } ?: list.firstOrNull { it.enabled } ?: list.firstOrNull()
        value = keep?.value ?: ""
        render()
    }

    /** Chooses a value without calling onChange (the device window applies the server's look this way). */
    fun select(v: String) { if (items.any { it.value == v }) { value = v; render() } }

    private fun render() { view.text = label().ifEmpty { "…" } + "  ▾" }

    private fun open() {
        if (items.isEmpty()) return
        val m = PopupMenu(ctx, view)
        val groups = LinkedHashMap<String, android.view.Menu>()
        for ((i, it) in items.withIndex()) {
            val menu = if (it.group == null) m.menu else groups.getOrPut(it.group) { m.menu.addSubMenu(it.group) }
            val mi = menu.add(0, i, i, it.label)
            mi.isEnabled = it.enabled
            mi.isCheckable = true
            mi.isChecked = it.value == value
        }
        m.setOnMenuItemClickListener { mi ->
            if (mi.hasSubMenu()) return@setOnMenuItemClickListener false
            val it = items.getOrNull(mi.itemId) ?: return@setOnMenuItemClickListener false
            if (it.value != value) { value = it.value; render(); onChange?.invoke(value) }
            true
        }
        m.show()
    }
}
