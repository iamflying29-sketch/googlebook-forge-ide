package local.forge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The dark look of Forge, built in code: the same palette as the web page it replaces, no browser.
 * The function names and signatures below are used by every panel, so they stay as they are; new helpers are added
 * after them (icons, icon buttons, chips, keyboard hints and state drawables).
 */
object Ui {
    // The colors of the current theme (Ctrl K Ctrl T). They are set before a window builds its views.
    var bg = Color.parseColor("#0f1117"); private set
    var bg2 = Color.parseColor("#141720"); private set
    var bg3 = Color.parseColor("#1b1f2a"); private set
    var bg4 = Color.parseColor("#222738"); private set
    var panel = Color.parseColor("#12151d"); private set
    var border = Color.parseColor("#262b38"); private set
    var border2 = Color.parseColor("#2f3548"); private set
    var fg = Color.parseColor("#d7dbe7"); private set
    var fg2 = Color.parseColor("#a7adbd"); private set
    var fg3 = Color.parseColor("#6b7489"); private set

    /** Forge's color themes, all dark: bg, bg2, bg3, bg4, panel, border, border2, fg, fg2, fg3. */
    private val themes = linkedMapOf(
        "Forge Dark" to listOf("#0f1117", "#141720", "#1b1f2a", "#222738", "#12151d", "#262b38", "#2f3548", "#d7dbe7", "#a7adbd", "#6b7489"),
        "Forge Midnight" to listOf("#07080c", "#0c0e14", "#13161f", "#1a1e2a", "#0a0c11", "#1c2030", "#262b3d", "#d3d8e6", "#9ea5b8", "#5f6880"),
        "Forge Dimmed" to listOf("#1e2229", "#23272f", "#2a2f38", "#323844", "#20242b", "#363c48", "#414857", "#d4d8e0", "#aab0bc", "#7c8494"),
        "Forge High Contrast" to listOf("#000000", "#050505", "#101010", "#1a1a1a", "#030303", "#6b7489", "#9aa3b8", "#ffffff", "#e0e4ee", "#b4bccc"))

    fun themeNames(): List<String> = themes.keys.toList()
    fun currentTheme(c: Context): String = c.getSharedPreferences("forge-window", Context.MODE_PRIVATE).getString("theme", "Forge Dark") ?: "Forge Dark"
    fun setTheme(c: Context, name: String) { c.getSharedPreferences("forge-window", Context.MODE_PRIVATE).edit().putString("theme", name).apply(); loadTheme(c) }

    /** Applies the saved theme; every window calls this before it builds its views. */
    fun loadTheme(c: Context) {
        val t = themes[currentTheme(c)] ?: themes.values.first()
        val v = t.map { Color.parseColor(it) }
        bg = v[0]; bg2 = v[1]; bg3 = v[2]; bg4 = v[3]; panel = v[4]; border = v[5]; border2 = v[6]; fg = v[7]; fg2 = v[8]; fg3 = v[9]
    }
    val accent = Color.parseColor("#ff8a3d")
    val accent2 = Color.parseColor("#ffb07a")
    val accentSoft = Color.parseColor("#24ff8a3d")
    val onAccent = Color.parseColor("#1a1000")
    val green = Color.parseColor("#4cc38a")
    val red = Color.parseColor("#ff6b6b")
    val yellow = Color.parseColor("#ffd166")
    val purple = Color.parseColor("#c792ea")
    val blue = Color.parseColor("#82aaff")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun text(c: Context, s: CharSequence, size: Float = 13f, color: Int = fg, mono: Boolean = false, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        if (mono) t.typeface = Typeface.MONOSPACE
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        t.setTextIsSelectable(false)
        return t
    }

    /** A text button. Primary buttons are filled with the accent color, the others are dark with a thin border. */
    fun button(c: Context, label: String, primary: Boolean = false, onClick: (View) -> Unit): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        b.setTextColor(if (primary) onAccent else fg)
        if (primary) b.setTypeface(b.typeface, Typeface.BOLD)
        b.background = if (primary) states(rounded(accent, 6), rounded(accent2, 6), rounded(accent2, 6))
            else states(rounded(bg3, 6, border), rounded(bg4, 6, border2), rounded(bg4, 6, accent))
        b.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5))
        b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
        b.stateListAnimator = null
        b.setOnClickListener(onClick)
        return b
    }

    /**
     * The icon variant of a button: a 28 dp tall control with a vector icon and an optional label, transparent
     * at rest, lighter on hover and pressed. `active` draws the icon in the accent color (a panel that is shown).
     */
    fun button(c: Context, icon: Int, label: String? = null, tooltip: String? = null, active: Boolean = false, onClick: (View) -> Unit): LinearLayout {
        val h = dp(c, 28)
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.minimumHeight = h
        row.isClickable = true; row.isFocusable = true
        val padX = if (label == null) dp(c, 6) else dp(c, 8)
        row.setPadding(padX, 0, padX, 0)
        row.background = states(null, rounded(bg4, 6), rounded(border2, 6))
        val tint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_hovered), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(fg, fg, if (active) accent else fg2))
        val img = ImageView(c)
        img.setImageDrawable(icon(c, icon, 0))
        img.imageTintList = tint
        img.isDuplicateParentStateEnabled = true
        row.addView(img, lp(dp(c, 16), dp(c, 16)))
        if (label != null) {
            val t = text(c, label, 12.5f, fg2)
            t.setTextColor(tint)
            t.isDuplicateParentStateEnabled = true
            t.setPadding(dp(c, 6), 0, 0, 0)
            t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
            row.addView(t, lp(wrap, wrap))
        }
        row.layoutParams = lp(wrap, h)
        if (tooltip != null) { row.tooltipText = tooltip; row.contentDescription = tooltip } else if (label != null) row.contentDescription = label
        row.setOnClickListener(onClick)
        return row
    }

    /** Marks an icon button built by the function above as active (accent icon) or not. */
    fun setActive(iconButton: LinearLayout, active: Boolean) {
        val tint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_hovered), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(fg, fg, if (active) accent else fg2))
        for (i in 0 until iconButton.childCount) {
            val v = iconButton.getChildAt(i)
            if (v is ImageView) v.imageTintList = tint
            if (v is TextView) v.setTextColor(tint)
        }
    }

    /** A vector drawable from res/drawable, tinted with one color (0 keeps the theme's tint). */
    fun icon(c: Context, res: Int, color: Int = fg2): Drawable {
        val d = c.getDrawable(res)!!.mutate()
        if (color != 0) d.setTintList(ColorStateList.valueOf(color))
        return d
    }

    /** An ImageView showing a tinted icon at the given size. */
    fun iconView(c: Context, res: Int, color: Int = fg2, sizeDp: Int = 16): ImageView {
        val v = ImageView(c)
        v.setImageDrawable(icon(c, res, color))
        v.layoutParams = lp(dp(c, sizeDp), dp(c, sizeDp))
        return v
    }

    /** Rest, hover and pressed backgrounds in one StateListDrawable. A null rest state is transparent. */
    fun states(normal: Drawable?, hover: Drawable, pressed: Drawable): StateListDrawable {
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        s.addState(intArrayOf(android.R.attr.state_hovered), hover)
        s.addState(intArrayOf(android.R.attr.state_focused), hover)
        if (normal != null) s.addState(intArrayOf(), normal)
        return s
    }

    fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = radiusDp * 3f
        if (stroke != 0) d.setStroke(2, stroke)
        return d
    }

    fun column(c: Context, vararg children: View): LinearLayout {
        val l = LinearLayout(c); l.orientation = LinearLayout.VERTICAL
        for (v in children) l.addView(v)
        return l
    }

    fun row(c: Context, vararg children: View): LinearLayout {
        val l = LinearLayout(c); l.orientation = LinearLayout.HORIZONTAL; l.gravity = Gravity.CENTER_VERTICAL
        for (v in children) l.addView(v)
        return l
    }

    fun lp(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight)
    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    val match = ViewGroup.LayoutParams.MATCH_PARENT

    fun input(c: Context, hint: String, mono: Boolean = false, multiline: Boolean = false): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setHintTextColor(fg3)
        e.setTextColor(fg)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        if (mono) e.typeface = Typeface.MONOSPACE
        e.background = focusStates(rounded(bg3, 6, border), rounded(bg3, 6, accent))
        e.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8))
        e.isSingleLine = !multiline
        if (multiline) { e.gravity = Gravity.TOP; e.setHorizontallyScrolling(false) } else e.ellipsize = TextUtils.TruncateAt.END
        e.imeOptions = e.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        e.highlightColor = Color.parseColor("#66ff8a3d")
        return e
    }

    /** Rest and focused backgrounds (inputs get an accent border while focused, like the web page). */
    fun focusStates(normal: Drawable, focused: Drawable): StateListDrawable {
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_focused), focused)
        s.addState(intArrayOf(), normal)
        return s
    }

    fun scroll(c: Context, child: View): ScrollView {
        val s = ScrollView(c); s.isFillViewport = true; s.addView(child, FrameLayout.LayoutParams(match, wrap)); return s
    }

    fun divider(c: Context, vertical: Boolean): View {
        val v = View(c); v.setBackgroundColor(border)
        v.layoutParams = if (vertical) LinearLayout.LayoutParams(dp(c, 1), match) else LinearLayout.LayoutParams(match, dp(c, 1))
        return v
    }

    fun card(c: Context, vararg children: View): LinearLayout {
        val l = column(c, *children)
        l.background = rounded(bg2, 8, border)
        l.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8))
        return l
    }

    /** A short notice at the bottom of the window. */
    fun toast(c: Context, s: String) { android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show() }

    // ---------------------------------------------------------------- newer helpers

    /** A keyboard hint such as "Ctrl P": small monospace text in a bordered box, like the web page's kbd element. */
    fun kbd(c: Context, keys: String): TextView {
        val t = text(c, keys, 10.5f, fg2, mono = true)
        t.background = rounded(bg4, 4, border2)
        t.setPadding(dp(c, 5), dp(c, 1), dp(c, 5), dp(c, 2))
        t.maxLines = 1
        return t
    }

    /** A round status dot, 8 dp, in the given color. */
    fun dot(c: Context, color: Int): View {
        val v = View(c)
        val d = GradientDrawable(); d.shape = GradientDrawable.OVAL; d.setColor(color)
        v.background = d
        v.layoutParams = lp(dp(c, 8), dp(c, 8))
        return v
    }

    fun setDotColor(dot: View, color: Int) { (dot.background as? GradientDrawable)?.setColor(color) }

    /** A small rounded pill with a dot and a label, like the web page's model pill and chips. */
    fun chip(c: Context, label: String, dotColor: Int = 0, onClick: ((View) -> Unit)? = null): LinearLayout {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = if (onClick != null) states(rounded(bg3, 99, border), rounded(bg4, 99, border2), rounded(bg4, 99, accent)) else rounded(bg3, 99, border)
        row.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3))
        if (dotColor != 0) { val d = dot(c, dotColor); row.addView(d); (d.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(c, 6) }
        val t = text(c, label, 11.5f, fg2)
        t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
        row.addView(t, lp(wrap, wrap))
        if (onClick != null) { row.isClickable = true; row.isFocusable = true; row.setOnClickListener(onClick) }
        return row
    }

    /** Sets the label (and the dot color, when the chip has a dot) of a chip built by `chip`. */
    fun setChip(chip: LinearLayout, label: String, dotColor: Int = 0, textColor: Int = fg2) {
        for (i in 0 until chip.childCount) {
            val v = chip.getChildAt(i)
            if (v is TextView) { v.text = label; v.setTextColor(textColor) } else if (dotColor != 0) setDotColor(v, dotColor)
        }
    }

    /** An uppercase section label such as "EXPLORER" (11 sp, letter spaced, secondary color). */
    fun sectionLabel(c: Context, s: String): TextView {
        val t = text(c, s.uppercase(), 11f, fg2, bold = true)
        t.letterSpacing = 0.08f
        return t
    }

    /** A small square icon-only button (24 dp) for panel headers, like the web page's .mini buttons. */
    fun miniButton(c: Context, icon: Int, tooltip: String, onClick: (View) -> Unit): ImageButton {
        val b = ImageButton(c)
        b.setImageDrawable(icon(c, icon, 0))
        b.imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_hovered), intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(fg, fg, fg3))
        b.background = states(null, rounded(bg4, 5), rounded(border2, 5))
        b.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = dp(c, 5); b.setPadding(p, p, p, p)
        b.layoutParams = lp(dp(c, 24), dp(c, 24))
        b.tooltipText = tooltip; b.contentDescription = tooltip
        b.setOnClickListener(onClick)
        return b
    }
}
