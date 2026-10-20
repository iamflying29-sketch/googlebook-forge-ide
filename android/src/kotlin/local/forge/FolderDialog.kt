package local.forge

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject

/** The web page's second border color (--border-2), used for dialog frames; Ui has no name for it. */
internal val dialogBorder: Int = Color.parseColor("#2f3548")

/**
 * A dialog drawn in Forge's dark style whatever the system theme: no title bar, a rounded dark panel with a border,
 * dimmed background, at most [widthDp] wide and never wider than 94 percent of the screen (the web page's
 * folder dialog uses the same rule: min(720px, 94vw)).
 */
internal fun darkDialog(activity: Activity, content: View, widthDp: Int): Dialog {
    val d = Dialog(activity)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    content.background = Ui.rounded(Ui.bg2, 12, dialogBorder)
    d.setContentView(content)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    val screenW = activity.resources.configuration.screenWidthDp
    d.window?.setLayout(Ui.dp(activity, minOf(widthDp, (screenW * 0.94).toInt())), WindowManager.LayoutParams.WRAP_CONTENT)
    d.window?.setDimAmount(0.6f)
    return d
}

/** A small button with a glyph and no frame, like the web page's ".mini" buttons. */
internal fun glyphButton(ctx: Context, glyph: String, description: String, onClick: () -> Unit): TextView {
    val t = Ui.text(ctx, glyph, 14f, Ui.fg3)
    t.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
    t.contentDescription = description
    t.tooltipText = description
    t.isClickable = true; t.isFocusable = true
    t.background = Ui.rounded(Color.TRANSPARENT, 6)
    t.setOnClickListener { onClick() }
    return t
}

/**
 * The Open folder dialog, the native twin of the web page's folder modal: the current path as a breadcrumb, a path
 * field you can type into, the subfolders with folder icons and ".." to go up, the recent folders on the right (each
 * with a small arrow that opens it in a new window), and the buttons Open this folder, New window here and Cancel.
 * Enter in the path field opens the typed path; Enter on a selected folder in the list opens that folder.
 */
object FolderDialog {
    /** [onAdd], when given, adds an "Add to this window" button (several folders in one window, like Cursor). */
    /** [addMode]: the dialog is "Add a folder to this window": its main button (and Enter) adds the folder ([onOpen] is
     *  the add then), and there is no button that would replace this window's folder. */
    fun show(activity: Activity, onOpen: (String) -> Unit, onNewWindow: (String) -> Unit, onAdd: ((String) -> Unit)? = null, addMode: Boolean = false) {
        Browser(activity, onOpen, onNewWindow, if (addMode) null else onAdd, addMode).show()
    }

    private class Browser(val activity: Activity, val onOpen: (String) -> Unit, val onNewWindow: (String) -> Unit, val onAdd: ((String) -> Unit)?, val addMode: Boolean = false) {
        private val dialog: Dialog
        private val pathField: EditText = Ui.input(activity, "/path/to/folder", mono = true)
        private val crumbs = LinearLayout(activity)
        private val list = ListView(activity)
        private val recentBox = LinearLayout(activity)
        private val adapter = FolderAdapter()
        private var current = ""
        private var parent = ""
        private var dirs: List<String> = emptyList()
        /** The path the field showed after the last listing, so a focus loss knows whether the text changed. */
        private var shownPath = ""
        private var busy = false

        init {
            val c = activity
            val root = Ui.column(c)
            root.layoutParams = ViewGroup.LayoutParams(Ui.match, Ui.wrap)

            // Header: title and the close glyph, like .dm-head.
            val title = Ui.text(c, if (addMode) "Add a folder to this window" else "Open folder", 14f, Ui.fg, bold = true)
            title.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            val head = Ui.row(c, title, glyphButton(c, "✕", "Close") { dialog.dismiss() })
            head.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8))
            root.addView(head)
            root.addView(Ui.divider(c, false))

            // Breadcrumb of the current path; each segment lists that folder.
            crumbs.orientation = LinearLayout.HORIZONTAL
            crumbs.gravity = Gravity.CENTER_VERTICAL
            val crumbScroll = HorizontalScrollView(c)
            crumbScroll.isHorizontalScrollBarEnabled = false
            crumbScroll.addView(crumbs)
            crumbScroll.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), 0)
            root.addView(crumbScroll)

            // The path field with the two main buttons, like .fm-path.
            pathField.layoutParams = Ui.lp(0, Ui.wrap, 1f)
            pathField.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            pathField.setOnEditorActionListener { _, _, _ -> openTyped(false); true }
            pathField.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { openTyped(false); true } else false
            }
            pathField.setOnFocusChangeListener { _, has ->
                val typed = pathField.text.toString().trim()
                if (!has && typed.isNotEmpty() && typed != shownPath) load(typed)
            }
            val openBtn = Ui.button(c, if (addMode) "Add to this window" else "Open this folder", primary = true) { openTyped(false) }
            val newBtn = Ui.button(c, "New window here") { openTyped(true) }
            newBtn.tooltipText = "Open this folder in a new window"
            val pathRow = Ui.row(c, pathField, gap(c, 8), openBtn, gap(c, 8), newBtn)
            pathRow.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10))
            root.addView(pathRow)
            root.addView(Ui.divider(c, false))

            // Body: the folder list on the left, the recent folders on the right (240dp, like .fm-body).
            val bodyHeight = (c.resources.displayMetrics.heightPixels * 0.45).toInt()
            list.adapter = adapter
            list.divider = null
            list.dividerHeight = 0
            list.selector = Ui.rounded(Color.parseColor("#24ff8a3d"), 6)
            list.setBackgroundColor(Color.TRANSPARENT)
            list.isVerticalScrollBarEnabled = true
            list.layoutParams = Ui.lp(0, bodyHeight, 1f)
            list.setOnItemClickListener { _, _, pos, _ -> load(targetAt(pos)) }
            list.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN && list.selectedItemPosition >= 0) {
                    openPath(targetAt(list.selectedItemPosition), false); true
                } else false
            }
            val recentScroll = Ui.scroll(c, recentBox)
            recentScroll.isFillViewport = false
            recentScroll.layoutParams = Ui.lp(Ui.dp(c, 240), bodyHeight)
            recentBox.orientation = LinearLayout.VERTICAL
            recentBox.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8))
            val body = Ui.row(c, list, Ui.divider(c, true).also { it.layoutParams = LinearLayout.LayoutParams(Ui.dp(c, 1), bodyHeight) }, recentScroll)
            body.gravity = Gravity.TOP
            root.addView(body)
            root.addView(Ui.divider(c, false))

            // Footer: Cancel on the left, the two actions on the right.
            val spacer = View(c); spacer.layoutParams = Ui.lp(0, 1, 1f)
            val foot = Ui.row(c, Ui.button(c, "Cancel") { dialog.dismiss() }, spacer)
            if (onAdd != null) {
                val add = Ui.button(c, "Add to this window") { val typed = pathField.text.toString().trim(); dialog.dismiss(); onAdd.invoke(if (typed.isEmpty()) current else typed) }
                add.tooltipText = "Show this folder in this window next to the current one"
                foot.addView(add); foot.addView(gap(c, 8))
            }
            foot.addView(Ui.button(c, "New window here") { openTyped(true) }); foot.addView(gap(c, 8))
            foot.addView(Ui.button(c, if (addMode) "Add to this window" else "Open this folder", primary = true) { openTyped(false) })
            foot.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 10))
            root.addView(foot)

            dialog = darkDialog(c, root, 720)
            dialog.setCanceledOnTouchOutside(true)
        }

        private fun gap(c: Context, dp: Int): View { val v = View(c); v.layoutParams = Ui.lp(Ui.dp(c, dp), 1); return v }

        fun show() {
            dialog.show()
            load(if (Api.folder.isEmpty()) "~" else Api.folder)
        }

        /** The folder a list row stands for: row 0 is "..", the rest are the subfolders. */
        private fun targetAt(pos: Int): String =
            if (pos == 0) (if (parent.isEmpty()) current else parent) else current.trimEnd('/') + "/" + dirs[pos - 1]

        private fun openTyped(newWindow: Boolean) {
            val typed = pathField.text.toString().trim()
            openPath(if (typed.isEmpty()) current else typed, newWindow)
        }

        /** Lists [path]; on an error the dialog stays where it is and says why. */
        private fun load(path: String) {
            if (busy) return
            busy = true
            Api.async("dirs", { Api.get("/api/dirs", mapOf("path" to path)) }) { r, e ->
                busy = false
                if (!dialog.isShowing) return@async
                if (r == null) { Ui.toast(activity, "Cannot list " + path + ": " + (e?.message ?: "unknown error")); return@async }
                render(r)
            }
        }

        private fun render(r: JSONObject) {
            current = r.optString("path")
            parent = r.optString("parent")
            val names = ArrayList<String>()
            r.optJSONArray("dirs")?.let { a -> for (i in 0 until a.length()) names.add(a.getString(i)) }
            dirs = names
            shownPath = current
            if (pathField.text.toString() != current) pathField.setText(current)
            pathField.setSelection(pathField.text.length)
            renderCrumbs()
            adapter.notifyDataSetChanged()
            list.setSelection(0)
            renderRecent(r)
        }

        private fun renderCrumbs() {
            val c = activity
            crumbs.removeAllViews()
            val parts = current.split('/').filter { it.isNotEmpty() }
            var prefix = ""
            crumbs.addView(crumb("/", "/", parts.isEmpty()))
            for ((i, p) in parts.withIndex()) {
                prefix += "/$p"
                if (i > 0) crumbs.addView(Ui.text(c, "/", 12f, Ui.fg3).also { it.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0) })
                crumbs.addView(crumb(p, prefix, i == parts.size - 1))
            }
        }

        private fun crumb(label: String, target: String, last: Boolean): TextView {
            val c = activity
            val t = Ui.text(c, label, 12.5f, if (last) Ui.fg else Ui.fg2, mono = true)
            t.setPadding(Ui.dp(c, 5), Ui.dp(c, 3), Ui.dp(c, 5), Ui.dp(c, 3))
            t.background = Ui.rounded(if (last) Ui.bg3 else Color.TRANSPARENT, 6)
            t.isClickable = true
            t.setOnClickListener { if (target != current) load(target) }
            return t
        }

        private fun renderRecent(r: JSONObject) {
            val c = activity
            recentBox.removeAllViews()
            recentBox.addView(Ui.text(c, "Recent", 11f, Ui.fg3).also { it.setPadding(Ui.dp(c, 4), 0, 0, Ui.dp(c, 4)) })
            val recent = r.optJSONArray("recent")
            if (recent == null || recent.length() == 0) {
                recentBox.addView(Ui.text(c, "No recent folders yet.", 11.5f, Ui.fg3).also { it.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), 0, 0) })
                return
            }
            for (i in 0 until recent.length()) {
                val p = recent.getString(i)
                val name = Ui.text(c, p, 12f, Ui.fg2)
                name.isSingleLine = true
                name.ellipsize = TextUtils.TruncateAt.MIDDLE
                name.layoutParams = Ui.lp(0, Ui.wrap, 1f)
                name.tooltipText = p
                name.isClickable = true
                name.setOnClickListener { openPath(p, false) }
                val arrow = glyphButton(c, "↗", "Open in a new window") { openPath(p, true) }
                val row = Ui.row(c, name, arrow)
                row.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), 0, Ui.dp(c, 2))
                row.background = Ui.rounded(Color.TRANSPARENT, 6)
                recentBox.addView(row)
            }
        }

        /**
         * Opens [path] in this window or a new one. The server is asked to list it first, so a typing mistake is
         * reported instead of opened; a folder that does not exist yet can be created, as on the web page.
         */
        private fun openPath(path: String, newWindow: Boolean) {
            if (path.isEmpty()) return
            Api.async("dirs", { Api.get("/api/dirs", mapOf("path" to path)) }) { r, e ->
                if (!dialog.isShowing) return@async
                if (r != null) { finish(r.optString("path", path), newWindow); return@async }
                val err = e as? Api.ApiError
                if (err != null && err.code == 400) askToCreate(path, newWindow)
                else Ui.toast(activity, "Cannot open " + path + ": " + (e?.message ?: "unknown error"))
            }
        }

        private fun askToCreate(path: String, newWindow: Boolean) {
            val c = activity
            val box = Ui.column(c)
            box.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 12))
            box.addView(Ui.text(c, "This folder does not exist", 14f, Ui.fg, bold = true))
            box.addView(Ui.text(c, path, 12.5f, Ui.fg2, mono = true).also { it.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6)) })
            box.addView(Ui.text(c, "Create it and open it?", 12.5f, Ui.fg2))
            lateinit var ask: Dialog
            val spacer = View(c); spacer.layoutParams = Ui.lp(0, 1, 1f)
            val buttons = Ui.row(c, spacer, Ui.button(c, "Cancel") { ask.dismiss() }, gap(c, 8),
                Ui.button(c, "Create and open", primary = true) {
                    ask.dismiss()
                    Api.async("workspace-create", { Api.post("/api/workspace", JSONObject().put("path", path).put("create", true)) }) { r, e ->
                        if (r == null) Ui.toast(activity, "Could not create the folder: " + (e?.message ?: "unknown error"))
                        else finish(r.optString("workspace", path), newWindow)
                    }
                })
            buttons.setPadding(0, Ui.dp(c, 12), 0, 0)
            box.addView(buttons)
            ask = darkDialog(c, box, 440)
            ask.show()
        }

        private fun finish(path: String, newWindow: Boolean) {
            dialog.dismiss()
            if (newWindow) onNewWindow(path) else onOpen(path)
        }

        /** The ".." row and one row per subfolder, each with a folder icon. */
        private inner class FolderAdapter : BaseAdapter() {
            override fun getCount(): Int = dirs.size + 1
            override fun getItem(position: Int): Any = if (position == 0) ".." else dirs[position - 1]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parentView: ViewGroup?): View {
                val c = activity
                val row = (convertView as? LinearLayout) ?: Ui.row(c, FolderIcon(c), Ui.text(c, "", 13f, Ui.fg)).also {
                    it.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6))
                    it.getChildAt(0).layoutParams = Ui.lp(Ui.dp(c, 16), Ui.dp(c, 14)).also { p -> p.rightMargin = Ui.dp(c, 8) }
                    (it.getChildAt(1) as TextView).isSingleLine = true
                    (it.getChildAt(1) as TextView).ellipsize = TextUtils.TruncateAt.END
                }
                val icon = row.getChildAt(0) as FolderIcon
                val label = row.getChildAt(1) as TextView
                if (position == 0) { icon.up = true; label.text = "..  (up)"; label.setTextColor(Ui.fg2) }
                else { icon.up = false; label.text = dirs[position - 1]; label.setTextColor(Ui.fg) }
                return row
            }
        }
    }

    /** A small folder glyph drawn with a Path, amber for a folder and gray for the ".." row. */
    private class FolderIcon(ctx: Context) : View(ctx) {
        var up = false
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.color = if (up) Ui.fg3 else Color.parseColor("#d9a441")
            paint.style = Paint.Style.FILL
            path.reset()
            val tab = h * 0.22f
            val r = h * 0.12f
            path.moveTo(0f, tab)
            path.lineTo(w * 0.38f, tab)
            path.lineTo(w * 0.46f, 0f)
            path.lineTo(w - r, 0f)
            path.quadTo(w, 0f, w, r)
            path.lineTo(w, h - r)
            path.quadTo(w, h, w - r, h)
            path.lineTo(r, h)
            path.quadTo(0f, h, 0f, h - r)
            path.close()
            canvas.drawPath(path, paint)
            // The front flap, a shade darker, so the shape reads as a folder at 16 by 14 dp.
            paint.color = if (up) Color.parseColor("#4b5366") else Color.parseColor("#b8862f")
            canvas.drawRoundRect(0f, h * 0.42f, w, h, r, r, paint)
        }
    }
}

/** A fully rounded pill of the given height in pixels (Ui.rounded takes a radius; a pill needs half its height). */
internal fun pill(fill: Int, heightPx: Int, stroke: Int = 0): GradientDrawable {
    val d = GradientDrawable(); d.setColor(fill); d.cornerRadius = heightPx / 2f; if (stroke != 0) d.setStroke(2, stroke); return d
}

/** A filled circle, for the knob of a toggle switch. */
internal fun circle(fill: Int): GradientDrawable {
    val d = GradientDrawable(); d.shape = GradientDrawable.OVAL; d.setColor(fill); return d
}
