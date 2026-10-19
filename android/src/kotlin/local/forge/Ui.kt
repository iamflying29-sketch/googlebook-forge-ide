package local.forge

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The dark look of Forge, built in code: the same colors as the old page, no browser. */
object Ui {
    val bg = Color.parseColor("#0f1117")
    val bg2 = Color.parseColor("#141720")
    val bg3 = Color.parseColor("#1b1f2a")
    val border = Color.parseColor("#262b38")
    val fg = Color.parseColor("#d7dbe7")
    val fg2 = Color.parseColor("#a7adbd")
    val fg3 = Color.parseColor("#6b7489")
    val accent = Color.parseColor("#ff8a3d")
    val green = Color.parseColor("#4cc38a")
    val red = Color.parseColor("#ff6b6b")
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

    fun button(c: Context, label: String, primary: Boolean = false, onClick: (View) -> Unit): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        b.setTextColor(if (primary) Color.BLACK else fg)
        b.background = rounded(if (primary) accent else bg3, 8, if (primary) accent else border)
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
        b.setOnClickListener(onClick)
        return b
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
        e.background = rounded(bg2, 8, border)
        e.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8))
        e.isSingleLine = !multiline
        if (multiline) { e.gravity = Gravity.TOP; e.setHorizontallyScrolling(false) } else e.ellipsize = TextUtils.TruncateAt.END
        e.imeOptions = e.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return e
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
}
