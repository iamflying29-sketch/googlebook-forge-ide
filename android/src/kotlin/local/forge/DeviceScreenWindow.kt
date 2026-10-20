package local.forge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The floating device window of the Android view, ported from the page's buildWindow(): the device's live screen,
 * one picture at a time from GET /api/devices/screen (the next one is asked for when the last arrived, at least
 * 350 ms apart, so about two a second), taps, long presses, swipes and mouse wheel scrolling sent through
 * POST /api/devices/input, keys typed on the picture, a text box, Back, Home and Recents, and for virtual devices
 * the screen profile, dark theme, text size and rotation (POST /api/devices/look) and a maker's battery behavior
 * for one app (POST /api/devices/behave). "To chat" attaches the screen to the chat; "Look" also asks the vision
 * model to describe it. The Googlebook's own screen is view only, as on the page.
 *
 * The window is a view laid over the main window's content, draggable by its title bar, and it keeps running when
 * the Android view is hidden; it stops asking for pictures while it is closed or while the main window is not
 * visible, and starts again when it comes back.
 */
class DeviceScreenWindow(private val ctx: Context, private val host: MainActivity, private val panel: AndroidPanel) {
    private val root: FrameLayout = host.findViewById(android.R.id.content)
    private val box = Box(ctx)
    private val title = Ui.text(ctx, "", 12.5f, Ui.fg, bold = true)
    private val screen = ScreenView(ctx) { root.height - Ui.dp(ctx, 290) }
    private val prof = Dropdown(ctx, "Screen profile")
    private val dark = Ui.button(ctx, "Dark") { setLook(JSONObject().put("dark", !lookState.optBoolean("dark"))) }
    private val font = Dropdown(ctx, "Text size")
    private val bsel = Dropdown(ctx, "A maker's battery behavior, made with Android's own controls, for the app below")
    private val bpkg = Ui.input(ctx, "App package, for example com.example.app")
    private val text = Ui.input(ctx, "Type text for the device, then Enter")
    private val lookRow = FlowRow(ctx)
    private val behaveRow = FlowRow(ctx)
    private val nav = FlowRow(ctx)
    private val note = Ui.text(ctx, "", 11f, Ui.fg3)

    private var device: JSONObject? = null
    private var input = false
    private var vd: JSONObject? = null
    private var persona: JSONObject? = null
    private var lookState = JSONObject()
    private var gen = 0
    private var windowVisible = true
    private val handler = Api.main
    private val refreshRunnable = Runnable { refresh() }
    private var wheelBusy = false

    /** The window's panel: it reports whether it is visible, so pictures are only asked for while it can be seen. */
    private inner class Box(c: Context) : LinearLayout(c) {
        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            windowVisible = isVisible
            if (isVisible) refreshSoon(0) else { handler.removeCallbacks(refreshRunnable); gen++ }
        }
    }

    init {
        box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(Ui.bg2, 10, Ui.border2)
        box.elevation = dp(12).toFloat()
        box.isClickable = true   // touches that miss a control stay in the window
        box.visibility = View.GONE

        // The title bar: the device's name, To chat, Look and close. Dragging it moves the window.
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.background = Ui.rounded(Ui.bg3, 10)
        head.setPadding(dp(8), dp(6), dp(8), dp(6))
        title.maxLines = 1; title.ellipsize = TextUtils.TruncateAt.END
        head.addView(title, Ui.lp(0, Ui.wrap, 1f))
        val shot = Ui.button(ctx, "To chat") { panel.screenshotToChat(device) }
        shot.tooltipText = "Attach this screen to the chat so the vision model can look at it for Llama"
        head.addView(shot, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) })
        val look = Ui.button(ctx, "Look") { panel.lookAtScreen(device) }
        look.tooltipText = "Attach this screen to the chat and ask the vision model to describe it"
        head.addView(look, Ui.lp(Ui.wrap, Ui.wrap).also { it.leftMargin = dp(6) })
        head.addView(Ui.miniButton(ctx, R.drawable.ic_close, "Close (the device keeps running)") { close() }, Ui.lp(dp(24), dp(24)).also { it.leftMargin = dp(6) })
        head.setOnTouchListener(DragListener())
        box.addView(head, Ui.lp(Ui.match, Ui.wrap))

        // Look: profile, dark, text size, rotation (virtual devices only).
        font.setItems(listOf(Dropdown.Item("1", "Text 100%"), Dropdown.Item("1.15", "Text 115%"), Dropdown.Item("1.3", "Text 130%"),
            Dropdown.Item("1.5", "Text 150%"), Dropdown.Item("2", "Text 200%")), "1")
        prof.onChange = { v -> setLook(JSONObject().put("profile", v)) }
        font.onChange = { v -> setLook(JSONObject().put("font_scale", v.toDouble())) }
        val rot = Ui.button(ctx, "Rotate") { setLook(JSONObject().put("rotation", (lookState.optInt("rotation") + 1) % 4)) }
        lookRow.addView(prof.view, ViewGroup.LayoutParams(dp(150), Ui.wrap))
        lookRow.addView(dark); lookRow.addView(font.view, ViewGroup.LayoutParams(dp(110), Ui.wrap)); lookRow.addView(rot)
        lookRow.setPadding(dp(8), dp(6), dp(8), 0)
        box.addView(lookRow, Ui.lp(Ui.match, Ui.wrap))

        // Behavior: a maker's battery behavior for one app (virtual devices only).
        val bgo = Ui.button(ctx, "Apply") {
            val d = device ?: return@button
            val body = JSONObject().put("serial", d.optString("serial")).put("port", d.optInt("port")).put("preset", bsel.value).put("package", bpkg.text.toString().trim())
            Api.async("device-behave", { Api.post("/api/devices/behave", body, 60000) }) { r, e ->
                notice(r?.optString("message") ?: e?.message ?: "The behavior could not be applied.")
            }
        }
        bsel.onChange = { v -> bsel.view.tooltipText = panel.devices?.optJSONObject("behaviors")?.optJSONObject(v)?.optString("about") ?: "Undo every behavior for this app" }
        behaveRow.addView(bsel.view, ViewGroup.LayoutParams(Ui.match, Ui.wrap))
        behaveRow.addView(bpkg, ViewGroup.LayoutParams(dp(220), Ui.wrap)); behaveRow.addView(bgo)
        behaveRow.setPadding(dp(8), dp(6), dp(8), 0)
        box.addView(behaveRow, Ui.lp(Ui.match, Ui.wrap))

        // The picture, on black.
        val body = FrameLayout(ctx)
        body.background = Ui.rounded(Color.BLACK, 10)
        body.setPadding(dp(8), dp(8), dp(8), dp(8))
        body.addView(screen, FrameLayout.LayoutParams(Ui.wrap, Ui.wrap, Gravity.CENTER))
        box.addView(body, Ui.lp(Ui.match, Ui.wrap).also { it.setMargins(dp(8), dp(6), dp(8), 0) })
        screen.setOnTouchListener(ScreenTouch())
        screen.setOnGenericMotionListener { _, ev -> wheel(ev) }
        screen.setOnKeyListener { _, code, ev -> keyTyped(code, ev) }

        // Back, Home, Recents and the text box.
        for ((k, label) in listOf("back" to "Back", "home" to "Home", "recents" to "Recents")) nav.addView(Ui.button(ctx, label) { send("key", JSONObject().put("key", k)) })
        nav.addView(text, ViewGroup.LayoutParams(Ui.match, Ui.wrap))
        text.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) {
                val s = text.text.toString()
                if (s.isNotEmpty()) send("text", JSONObject().put("text", s))
                text.setText("")
                handler.postDelayed({ send("key", JSONObject().put("key", "enter")) }, 150)
                true
            } else false
        }
        nav.setPadding(dp(8), dp(6), dp(8), 0)
        box.addView(nav, Ui.lp(Ui.match, Ui.wrap))
        note.setPadding(dp(8), dp(4), dp(8), dp(8))
        box.addView(note, Ui.lp(Ui.match, Ui.wrap))
    }

    // ---------------------------------------------------------------- showing and closing
    fun show(dev: JSONObject) {
        val d = JSONObject(dev.toString())
        device = d
        input = d.optString("kind") != "googlebook"
        val data = panel.devices
        val vid = d.optString("virtual_id")
        vd = if (vid.isNotEmpty()) data?.optJSONObject("virtual")?.optJSONArray("devices").objs()?.firstOrNull { it.optString("id") == vid } else null
        lookState = vd?.optJSONObject("look")?.let { JSONObject(it.toString()) } ?: JSONObject()
        val pid = vd?.optString("persona") ?: ""
        persona = if (pid.isNotEmpty()) data?.optJSONArray("personas").objs()?.firstOrNull { it.optString("id") == pid } else null
        fillProfiles(if (persona != null) "persona:$pid" else "")
        title.text = d.optString("name").ifEmpty { d.optString("serial") }
        lookRow.visibility = if (vd != null) View.VISIBLE else View.GONE
        behaveRow.visibility = if (vd != null) View.VISIBLE else View.GONE
        val behaviors = data?.optJSONObject("behaviors") ?: JSONObject()
        val pb = persona?.optString("behavior") ?: ""
        val keys = behaviors.keys().asSequence().toList().sortedBy { if (pb.isNotEmpty() && it.startsWith(pb)) 0 else 1 }
        val items = keys.map { Dropdown.Item(it, behaviors.optJSONObject(it)?.optString("label") ?: it) } + Dropdown.Item("reset", "Reset the app to normal")
        bsel.setItems(items, bsel.value)
        bsel.onChange?.invoke(bsel.value)
        if (bpkg.text.isEmpty()) bpkg.setText(panel.appId)
        nav.visibility = if (input) View.VISIBLE else View.GONE
        note.text = if (input) "Click to tap, hold to long press, drag to swipe, scroll to scroll. Click the screen, then type to send keys."
            else "This is your Googlebook's own screen, so Forge only shows it."
        applyLook()
        if (box.parent == null) {
            val width = min(dp(420), max(dp(200), (if (root.width > 0) root.width else ctx.resources.displayMetrics.widthPixels) - dp(32)))
            val lp = FrameLayout.LayoutParams(width, Ui.wrap, Gravity.TOP or Gravity.END)
            lp.topMargin = dp(64); lp.rightMargin = dp(24)
            root.addView(box, lp)
        }
        box.visibility = View.VISIBLE
        box.bringToFront()
        screen.setPicture(null, 0, 0)
        refreshSoon(0)
    }

    fun close() {
        box.visibility = View.GONE
        handler.removeCallbacks(refreshRunnable)
        gen++
    }

    /** The Android view read /api/devices again; the behavior tooltips and profile labels come from there. */
    fun dataChanged() {
        if (box.visibility == View.VISIBLE && prof.items.isEmpty()) fillProfiles(persona?.let { "persona:" + it.optString("id") } ?: "")
    }

    /** The general profiles and this device's own persona screen (other personas' screens are left out). */
    private fun fillProfiles(own: String) {
        val profiles = panel.devices?.optJSONObject("profiles") ?: JSONObject()
        val items = ArrayList<Dropdown.Item>()
        for (k in profiles.keys()) {
            if (k.startsWith("persona:") && k != own) continue
            val p = profiles.optJSONObject(k) ?: continue
            items.add(Dropdown.Item(k, (if (k == own) "Persona: " else "") + p.optString("label") + " " + p.optInt("width") + "×" + p.optInt("height")))
        }
        prof.setItems(items, lookState.optString("profile"))
    }

    // ---------------------------------------------------------------- input, look and behavior
    private fun send(kind: String, extra: JSONObject) {
        val d = device ?: return
        val body = JSONObject(extra.toString()).put("serial", d.optString("serial")).put("port", d.optInt("port")).put("kind", kind)
        Api.async("device-input", { Api.post("/api/devices/input", body, 30000) }) { r, e ->
            if (r == null) notice(e?.message ?: "The input was not sent.")
            else if (!r.optBoolean("ok") && r.optString("message").isNotEmpty()) notice(r.optString("message"))
            refreshSoon(250)
        }
    }

    private fun setLook(change: JSONObject) {
        val d = device ?: return
        val body = JSONObject(change.toString()).put("serial", d.optString("serial")).put("port", d.optInt("port"))
        Api.async("device-look", { Api.post("/api/devices/look", body, 60000) }) { r, e ->
            if (r == null) { notice(e?.message ?: "The look was not changed."); return@async }
            if (!r.optBoolean("ok")) { notice(r.optString("message")); return@async }
            r.optJSONObject("look")?.let { lookState = it }
            applyLook()
            refreshSoon(900)
        }
    }

    private fun applyLook() {
        val profile = lookState.optString("profile")
        if (profile.isNotEmpty()) prof.select(profile)
        val on = lookState.optBoolean("dark")
        dark.setTextColor(if (on) Ui.accent else Ui.fg)
        dark.background = if (on) Ui.rounded(Ui.accentSoft, 6, Ui.accent) else Ui.states(Ui.rounded(Ui.bg3, 6, Ui.border), Ui.rounded(Ui.bg4, 6, Ui.border2), Ui.rounded(Ui.bg4, 6, Ui.accent))
        val fs = lookState.optDouble("font_scale", 1.0)
        font.select(fs.toString().trimEnd('0').trimEnd('.'))
        val p = persona
        val own = p != null && profile == "persona:" + p.optString("id") && lookState.optInt("rotation") % 4 == 0
        screen.round = profile == "watch-round" || (own && p?.optString("kind") == "watch-round")
        val corner = p?.optDouble("corner", 0.0) ?: 0.0
        val pw = p?.optDouble("width", 0.0) ?: 0.0
        val ph = p?.optDouble("height", 0.0) ?: 0.0
        screen.cornerX = if (own && corner > 0 && pw > 0) (corner / pw).toFloat() else 0f
        screen.cornerY = if (own && corner > 0 && ph > 0) (corner / ph).toFloat() else 0f
        val c = if (own) p?.optJSONObject("cutout") else null
        screen.cutout = if (c != null && pw > 0 && ph > 0) floatArrayOf((c.optDouble("x") / pw).toFloat(), (c.optDouble("y") / ph).toFloat(), (c.optDouble("r") / pw).toFloat()) else null
        screen.invalidate()
    }

    /** A short press is a tap, a long one a long press, a drag a swipe (the page's pointer handling). */
    private inner class ScreenTouch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var downAt = 0L; private var down = false
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            if (!input) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { screen.requestFocus(); downX = ev.x; downY = ev.y; downAt = System.currentTimeMillis(); down = true; return true }
                MotionEvent.ACTION_UP -> {
                    if (!down) return true
                    down = false
                    val ms = System.currentTimeMillis() - downAt
                    val moved = hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) > dp(8)
                    val a = screen.toDevice(downX, downY)
                    if (!moved) send(if (ms > 550) "long" else "tap", JSONObject().put("x", a[0]).put("y", a[1]))
                    else {
                        val b = screen.toDevice(ev.x, ev.y)
                        send("swipe", JSONObject().put("x", a[0]).put("y", a[1]).put("x2", b[0]).put("y2", b[1]).put("ms", min(1500L, max(80L, ms))))
                    }
                    v.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { down = false; return true }
            }
            return true
        }
    }

    /** The mouse wheel scrolls the device: a short swipe of up to 35 percent of the screen, at most four a second. */
    private fun wheel(ev: MotionEvent): Boolean {
        if (!input || ev.actionMasked != MotionEvent.ACTION_SCROLL) return false
        if (wheelBusy) return true
        wheelBusy = true
        handler.postDelayed({ wheelBusy = false }, 250)
        val p = screen.toDevice(ev.x, ev.y)
        val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val dy = max(-0.35f, min(0.35f, v / 4f)) * screen.deviceHeight
        send("swipe", JSONObject().put("x", p[0]).put("y", p[1]).put("x2", p[0]).put("y2", Math.round(p[1] + dy)).put("ms", 180))
        return true
    }

    /** Keys typed on the picture: Escape is Back, the editing and arrow keys map to Android keys, characters are sent as text. */
    private fun keyTyped(code: Int, ev: KeyEvent): Boolean {
        if (!input || ev.action != KeyEvent.ACTION_DOWN) return false
        val key = when (code) {
            KeyEvent.KEYCODE_ESCAPE -> "back"
            KeyEvent.KEYCODE_DEL -> "delete"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_TAB -> "tab"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            else -> null
        }
        if (key != null) { send("key", JSONObject().put("key", key)); return true }
        if (ev.isCtrlPressed || ev.isMetaPressed || ev.isAltPressed) return false
        val ch = ev.unicodeChar
        if (ch > 0 && !Character.isISOControl(ch)) { send("text", JSONObject().put("text", String(Character.toChars(ch)))); return true }
        return false
    }

    /** Dragging the title bar moves the window; its position becomes left and top margins in the content frame. */
    private inner class DragListener : View.OnTouchListener {
        private var startRawX = 0f; private var startRawY = 0f; private var startLeft = 0; private var startTop = 0
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val lp = box.layoutParams as? FrameLayout.LayoutParams ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY; startLeft = box.left; startTop = box.top
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.rightMargin = 0
                    lp.leftMargin = max(0, (startLeft + ev.rawX - startRawX).toInt())
                    lp.topMargin = max(0, (startTop + ev.rawY - startRawY).toInt())
                    box.layoutParams = lp
                    return true
                }
                MotionEvent.ACTION_UP -> { v.performClick(); return true }
            }
            return false
        }
    }

    // ---------------------------------------------------------------- the pictures
    private fun refreshSoon(ms: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, ms)
    }

    /** One picture at a time; the next is asked for when the last arrived, at least 350 ms later, or 2.5 s after a failure. */
    private fun refresh() {
        val d = device ?: return
        if (box.visibility != View.VISIBLE || !windowVisible) return
        val my = ++gen
        val t0 = System.currentTimeMillis()
        val serial = d.optString("serial"); val port = d.optInt("port")
        Api.async("device-screen", { fetchPicture(serial, port) }) { pic, _ ->
            if (my != gen) return@async
            if (pic == null) { handler.postDelayed(refreshRunnable, 2500); return@async }
            screen.setPicture(pic.bitmap, pic.width, pic.height)
            handler.postDelayed(refreshRunnable, max(350L, System.currentTimeMillis() - t0))
        }
    }

    private class Picture(val bitmap: Bitmap, val width: Int, val height: Int)

    /**
     * GET /api/devices/screen as PNG bytes, read while the server writes them, with a 15-second limit and a 40 MB
     * cap, and decoded off the main thread. A very large screen is decoded at half or a quarter size for drawing;
     * the real size is kept for the input coordinates.
     */
    private fun fetchPicture(serial: String, port: Int): Picture? {
        val q = "serial=" + URLEncoder.encode(serial, "UTF-8") + "&port=" + port + "&t=" + System.currentTimeMillis()
        val c = URL(Api.SERVER + "/api/devices/screen?" + q).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 15000
        c.useCaches = false
        if (Api.folder.isNotEmpty()) c.setRequestProperty("X-Forge-Folder", URLEncoder.encode(Api.folder, "UTF-8").replace("+", "%20"))
        try {
            if (c.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            c.inputStream.use { s ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    if (out.size() + n > 40 * 1024 * 1024) return null
                    out.write(buf, 0, n)
                }
            }
            val bytes = out.toByteArray()
            val bounds = BitmapFactory.Options(); bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options()
            opts.inSampleSize = if (max(bounds.outWidth, bounds.outHeight) > 2400) 4 else if (max(bounds.outWidth, bounds.outHeight) > 1300) 2 else 1
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            return Picture(bmp, bounds.outWidth, bounds.outHeight)
        } finally {
            try { c.disconnect() } catch (e: Exception) { }
        }
    }

    private fun dp(v: Int) = Ui.dp(ctx, v)
    private fun notice(s: String) { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }
}

/**
 * The picture of the device's screen, scaled to fit the window's width and the height left under it, masked round
 * for watches, with a persona's rounded corners and camera hole drawn over it in proportion (Android leaves its own
 * cutout out of screenshots). Touch positions map to the device's real pixels.
 */
internal class ScreenView(ctx: Context, private val maxHeight: () -> Int) : View(ctx) {
    private var bitmap: Bitmap? = null
    var deviceWidth = 0
        private set
    var deviceHeight = 0
        private set
    var round = false
    var cornerX = 0f
    var cornerY = 0f
    /** The camera hole as fractions: center x of the width, center y of the height, radius of the width. */
    var cutout: FloatArray? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val black = Paint().also { it.color = Color.BLACK }
    private val fill = Paint().also { it.color = Color.parseColor("#111111") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Ui.fg3; it.textSize = Ui.dp(ctx, 12).toFloat(); it.textAlign = Paint.Align.CENTER }
    private val minSide = Ui.dp(ctx, 80)
    private val placeholderWidth = Ui.dp(ctx, 180)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setPicture(b: Bitmap?, width: Int, height: Int) {
        val old = bitmap
        val sizeChanged = old == null || b == null || old.width != b.width || old.height != b.height
        bitmap = b
        deviceWidth = if (b != null) width else 0
        deviceHeight = if (b != null) height else 0
        if (sizeChanged) requestLayout()
        invalidate()
        if (old != null && old !== b) old.recycle()
    }

    /** The device pixel under a point of this view. */
    fun toDevice(x: Float, y: Float): IntArray {
        val w = max(1, width); val h = max(1, height)
        val dw = if (deviceWidth > 0) deviceWidth else w; val dh = if (deviceHeight > 0) deviceHeight else h
        return intArrayOf(Math.round(x * dw / w).coerceIn(0, dw), Math.round(y * dh / h).coerceIn(0, dh))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = max(minSide, MeasureSpec.getSize(widthMeasureSpec))
        val maxH = max(minSide, maxHeight())
        val b = bitmap
        val bw = b?.width ?: 9
        val bh = b?.height ?: 16
        var scale = min(availW.toFloat() / bw, maxH.toFloat() / bh)
        if (b == null) scale = min(scale, placeholderWidth.toFloat() / bw)
        setMeasuredDimension(max(minSide, (bw * scale).toInt()), max(minSide, (bh * scale).toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val path = Path()
        if (round) path.addOval(RectF(0f, 0f, w, h), Path.Direction.CW)
        else if (cornerX > 0f || cornerY > 0f) path.addRoundRect(RectF(0f, 0f, w, h), cornerX * w, cornerY * h, Path.Direction.CW)
        else path.addRoundRect(RectF(0f, 0f, w, h), Ui.dp(context, 6).toFloat(), Ui.dp(context, 6).toFloat(), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        val b = bitmap
        if (b != null && !b.isRecycled) canvas.drawBitmap(b, null, RectF(0f, 0f, w, h), paint)
        else {
            canvas.drawRect(0f, 0f, w, h, fill)
            canvas.drawText("Waiting for the first picture…", w / 2f, h / 2f, textPaint)
        }
        cutout?.let { c -> canvas.drawCircle(c[0] * w, c[1] * h, c[2] * w, black) }
        canvas.restore()
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
