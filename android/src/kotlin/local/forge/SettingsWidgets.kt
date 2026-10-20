package local.forge

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView

/**
 * The controls and dialogs the settings page is built from, shared by SettingsActivity and SettingsExtras: the
 * switch, the drop-down picker, the text dialog, the confirmation dialog and the list rows of the cards. They are
 * drawn in code in the web page's dark style and use only the helpers in Ui, Chrome and FolderDialog.
 */

/** A 34 by 18 dp switch like the web page's .switch: a pill with a knob that slides to the right when on. */
internal class Toggle(c: Context, initial: Boolean) : FrameLayout(c) {
    var on = initial
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private val knob = View(c)
    private val h = Ui.dp(c, 18)
    private val travel = Ui.dp(c, 16)
    init {
        layoutParams = LinearLayout.LayoutParams(Ui.dp(c, 34), h)
        val k = Ui.dp(c, 12)
        addView(knob, LayoutParams(k, k, Gravity.CENTER_VERTICAL).also { it.leftMargin = Ui.dp(c, 2) })
        isClickable = true; isFocusable = true
        contentDescription = "switch"
        paint(false)
        setOnClickListener { set(!on, true) }
        setOnKeyListener { _, code, ev -> if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE) && ev.action == KeyEvent.ACTION_DOWN) { set(!on, true); true } else false }
    }
    fun set(v: Boolean, fire: Boolean) {
        val changed = v != on
        on = v
        paint(changed)
        if (fire) onChange?.invoke(on)
    }
    private fun paint(animate: Boolean) {
        background = pill(if (on) Ui.accentSoft else Ui.bg4, h, if (on) Ui.accent else dialogBorder)
        knob.background = circle(if (on) Ui.accent else Ui.fg3)
        val x = if (on) travel.toFloat() else 0f
        if (animate) knob.animate().translationX(x).setDuration(150).start() else knob.translationX = x
    }
}

/** A field-like control that shows the chosen value and a small arrow; tapping it opens the list. */
internal class Picker(c: Context) : TextView(c) {
    init {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Ui.fg)
        background = Ui.rounded(Ui.bg2, 8, Ui.border)
        setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7))
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true; isFocusable = true
        layoutParams = Ui.lp(Ui.match, Ui.wrap)
        setOnKeyListener { v, code, ev -> if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE) && ev.action == KeyEvent.ACTION_DOWN) { v.performClick(); true } else false }
    }
    fun show(value: String) { text = value + "   ▾" }
}

/** A dark drop-down list under the anchor; the current value is drawn in the accent color. */
internal fun showPicker(c: Context, anchor: TextView, options: List<String>, current: String, onPick: (String) -> Unit) {
    val popup = ListPopupWindow(c)
    popup.anchorView = anchor
    popup.width = anchor.width.coerceAtLeast(Ui.dp(c, 200))
    popup.isModal = true
    popup.setBackgroundDrawable(Ui.rounded(Ui.bg3, 8, dialogBorder))
    popup.verticalOffset = Ui.dp(c, 2)
    popup.setAdapter(object : BaseAdapter() {
        override fun getCount(): Int = options.size
        override fun getItem(position: Int): Any = options[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = (convertView as? TextView) ?: Ui.text(c, "", 13f, Ui.fg).also { it.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8)); it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END }
            t.text = options[position]
            t.setTextColor(if (options[position] == current) Ui.accent else Ui.fg)
            return t
        }
    })
    popup.setOnItemClickListener { _, _, pos, _ -> popup.dismiss(); onPick(options[pos]) }
    popup.show()
    popup.listView?.let { lv ->
        lv.divider = null
        lv.selector = Ui.rounded(Ui.accentSoft, 6)
        val idx = options.indexOf(current); if (idx >= 0) lv.setSelection(idx)
    }
}

/** A picker whose choices are fixed; the control shows the current one and saves a change through [onPick]. */
internal fun fixedPicker(c: Context, options: List<String>, current: String, onPick: (String) -> Unit): Picker {
    val p = Picker(c)
    p.show(current)
    p.setOnClickListener { showPicker(c, p, options, p.text.toString().removeSuffix("   ▾")) { chosen -> p.show(chosen); onPick(chosen) } }
    return p
}

/** An empty view of the given width, for spacing between buttons. */
internal fun gapView(c: Context, dp: Int): View { val v = View(c); v.layoutParams = Ui.lp(Ui.dp(c, dp), 1); return v }

/** A dialog heading: the title on the left and the close glyph on the right, like the web page's .dm-head. */
internal fun dialogHead(c: Context, title: String, onClose: () -> Unit): LinearLayout {
    val heading = Ui.text(c, title, 14f, Ui.fg, bold = true)
    heading.layoutParams = Ui.lp(0, Ui.wrap, 1f)
    val head = Ui.row(c, heading, glyphButton(c, "✕", "Close") { onClose() })
    head.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8))
    return head
}

/**
 * A dialog with one text field: a heading, an optional note, the field and the buttons Cancel and [saveLabel].
 * A single-line field saves on Enter. The dialog closes only after [onSave] was called, so the caller decides
 * what to do with the text; an empty text is handed over as well.
 */
internal fun textDialog(a: Activity, title: String, initial: String, hint: String = "", note: String = "", mono: Boolean = false,
                        multiline: Boolean = true, saveLabel: String = "Save", widthDp: Int = 640, onSave: (String) -> Unit) {
    val c = a
    val box = Ui.column(c)
    lateinit var dialog: Dialog
    box.addView(dialogHead(c, title) { dialog.dismiss() })
    box.addView(Ui.divider(c, false))
    val body = Ui.column(c)
    body.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 4))
    if (note.isNotEmpty()) body.addView(Ui.text(c, note, 11.5f, Ui.fg3).also { it.setPadding(0, 0, 0, Ui.dp(c, 8)) })
    val input = Ui.input(c, hint, mono = mono, multiline = multiline)
    input.layoutParams = Ui.lp(Ui.match, Ui.wrap)
    input.setText(initial)
    if (multiline) {
        input.minLines = 6
        input.maxHeight = (c.resources.displayMetrics.heightPixels * 0.55).toInt()
        input.isVerticalScrollBarEnabled = true
    } else {
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setOnEditorActionListener { _, _, _ -> onSave(input.text.toString()); dialog.dismiss(); true }
        input.setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { onSave(input.text.toString()); dialog.dismiss(); true } else false }
    }
    body.addView(input)
    box.addView(body)
    val spacer = View(c); spacer.layoutParams = Ui.lp(0, 1, 1f)
    val foot = Ui.row(c, spacer, Ui.button(c, "Cancel") { dialog.dismiss() }, gapView(c, 8),
        Ui.button(c, saveLabel, primary = true) { onSave(input.text.toString()); dialog.dismiss() })
    foot.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 10))
    box.addView(foot)
    dialog = darkDialog(a, box, widthDp)
    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
    input.requestFocus()
    if (!multiline) input.setSelection(input.text.length)
}

/** A yes-or-no dialog: a title, a message and the buttons Cancel and [actionLabel]; [onConfirm] runs on the latter. */
internal fun confirmDialog(a: Activity, title: String, message: String, actionLabel: String, onConfirm: () -> Unit) {
    val c = a
    val box = Ui.column(c)
    box.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 12))
    box.addView(Ui.text(c, title, 14f, Ui.fg, bold = true))
    box.addView(Ui.text(c, message, 12.5f, Ui.fg2).also { it.setPadding(0, Ui.dp(c, 6), 0, 0) })
    lateinit var ask: Dialog
    val spacer = View(c); spacer.layoutParams = Ui.lp(0, 1, 1f)
    val buttons = Ui.row(c, spacer, Ui.button(c, "Cancel") { ask.dismiss() }, gapView(c, 8),
        Ui.button(c, actionLabel, primary = true) { ask.dismiss(); onConfirm() })
    buttons.setPadding(0, Ui.dp(c, 12), 0, 0)
    box.addView(buttons)
    ask = darkDialog(a, box, 440)
    ask.setCanceledOnTouchOutside(true)
    ask.show()
}

/**
 * One row of a list card: the text on the left (tapping it runs [onEdit] when there is one) and small controls on
 * the right, such as a switch or a trash button. The row lightens under the pointer like the web page's rows.
 */
internal fun itemRow(c: Context, text: CharSequence, mono: Boolean, onEdit: (() -> Unit)?, vararg controls: View): LinearLayout {
    val label = Ui.text(c, text, 12.5f, Ui.fg, mono = mono)
    label.layoutParams = Ui.lp(0, Ui.wrap, 1f)
    label.setPadding(Ui.dp(c, 6), Ui.dp(c, 5), Ui.dp(c, 6), Ui.dp(c, 5))
    if (onEdit != null) {
        label.isClickable = true; label.isFocusable = true
        label.tooltipText = "Edit"
        label.background = Ui.states(null, Ui.rounded(Ui.bg4, 6), Ui.rounded(Ui.border2, 6))
        label.setOnClickListener { onEdit() }
        label.setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { onEdit(); true } else false }
    }
    val row = Ui.row(c, label)
    for (v in controls) {
        row.addView(v)
        (v.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = Ui.dp(c, 6)
    }
    row.setPadding(0, Ui.dp(c, 1), 0, Ui.dp(c, 1))
    return row
}

/** A trash button for a list row. */
internal fun trashButton(c: Context, tooltip: String, onClick: () -> Unit) = Ui.miniButton(c, R.drawable.ic_trash, tooltip) { onClick() }

/** A gray "none yet" line for an empty list. */
internal fun emptyNote(c: Context, s: String): TextView {
    val t = Ui.text(c, s, 11.5f, Ui.fg3)
    t.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4))
    return t
}

/** A row of buttons under a card's content, like the web page's .gactions. */
internal fun actionRow(c: Context, vararg buttons: View): LinearLayout {
    val row = LinearLayout(c)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    for ((i, b) in buttons.withIndex()) {
        if (i > 0) row.addView(gapView(c, 8))
        row.addView(b)
    }
    row.setPadding(0, Ui.dp(c, 8), 0, 0)
    return row
}

/** Runs [commit] when the field loses focus, and on Enter for a single-line field. */
internal fun commitOn(input: EditText, singleLine: Boolean, commit: () -> Unit) {
    input.setOnFocusChangeListener { _, has -> if (!has) commit() }
    if (singleLine) {
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setOnEditorActionListener { _, _, _ -> commit(); true }
        input.setOnKeyListener { _, code, ev -> if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN) { commit(); true } else false }
    }
}
