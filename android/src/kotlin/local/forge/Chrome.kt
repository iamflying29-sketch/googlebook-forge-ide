package local.forge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The window chrome of Forge, built in code to look like the web page it replaces: the top bar, the activity bar
 * on the left, the tab strip of the bottom panel, the status bar and the draggable splitters between the panels.
 * Every builder returns a small holder with the views the window updates later.
 */
object Chrome {

    // ---------------------------------------------------------------- top bar
    class TopBar(
        val view: LinearLayout,
        /** The label inside the folder button (the folder name). */
        val folderLabel: TextView,
        /** The window title in the middle left: "file · folder" or the folder name. */
        val title: TextView,
        /** The engine status chip on the right (text and dot). */
        val engineChip: LinearLayout,
        val terminalButton: LinearLayout,
        val chatButton: LinearLayout,
    )

    /** The 34 dp top bar: logo, folder button with a chevron, title, the search field and the icon buttons on the right. */
    fun topBar(c: Context, onFolder: (View) -> Unit, onSearch: (View) -> Unit, onTerminal: (View) -> Unit, onChat: (View) -> Unit,
               onNewWindow: (View) -> Unit, onSettings: (View) -> Unit): TopBar {
        val h = c.resources.getDimensionPixelSize(R.dimen.top_bar_height)
        val bar = LinearLayout(c)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Ui.bg2)
        bar.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0)
        bar.layoutParams = Ui.lp(Ui.match, h)

        // Logo and product name.
        val logo = Ui.iconView(c, R.drawable.ic_forge_logo, 0, 20)
        val name = Ui.text(c, "Forge", 13f, Ui.fg, bold = true)
        name.letterSpacing = 0.02f
        name.setPadding(Ui.dp(c, 7), 0, Ui.dp(c, 10), 0)
        bar.addView(logo); bar.addView(name)

        // The folder button: folder icon, folder name, chevron.
        val folderLabel = Ui.text(c, "workspace", 12.5f, Ui.fg2)
        folderLabel.maxLines = 1; folderLabel.ellipsize = TextUtils.TruncateAt.MIDDLE
        folderLabel.maxWidth = Ui.dp(c, 220)
        val folderButton = Ui.button(c, R.drawable.ic_folder, "", "Open folder (Ctrl O)", onClick = onFolder)
        folderButton.removeViewAt(1)
        folderLabel.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 4), 0)
        folderButton.addView(folderLabel, Ui.lp(Ui.wrap, Ui.wrap))
        folderButton.addView(Ui.iconView(c, R.drawable.ic_chevron_down, Ui.fg3, 14))
        bar.addView(folderButton)

        // The title: file and folder.
        val title = Ui.text(c, "Forge", 12.5f, Ui.fg3)
        title.maxLines = 1; title.ellipsize = TextUtils.TruncateAt.MIDDLE
        title.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0)
        bar.addView(title, Ui.lp(0, Ui.wrap, 1f))

        // The search field in the center: a rounded box that opens the quick open dialog.
        val search = LinearLayout(c)
        search.orientation = LinearLayout.HORIZONTAL
        search.gravity = Gravity.CENTER_VERTICAL
        search.background = Ui.states(Ui.rounded(Ui.bg3, 7, Ui.border), Ui.rounded(Ui.bg3, 7, Ui.border2), Ui.rounded(Ui.bg3, 7, Ui.accent))
        search.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 6), 0)
        search.isClickable = true; search.isFocusable = true
        search.tooltipText = "Quick open (Ctrl P)"
        search.setOnClickListener(onSearch)
        search.addView(Ui.iconView(c, R.drawable.ic_search, Ui.fg3, 14))
        val hint = Ui.text(c, "Search files and commands", 12.5f, Ui.fg3)
        hint.maxLines = 1; hint.ellipsize = TextUtils.TruncateAt.END
        hint.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0)
        search.addView(hint, Ui.lp(0, Ui.wrap, 1f))
        search.addView(Ui.kbd(c, "Ctrl P"))
        bar.addView(search, Ui.lp(Ui.dp(c, 420), Ui.dp(c, 26)))

        // The right side: engine chip and icon buttons.
        val spacer = View(c)
        bar.addView(spacer, Ui.lp(0, 1, 1f))
        val chip = Ui.chip(c, "Connecting", Ui.fg3)
        (chip.layoutParams as? LinearLayout.LayoutParams ?: Ui.lp(Ui.wrap, Ui.wrap)).also { it.rightMargin = Ui.dp(c, 6); chip.layoutParams = it }
        bar.addView(chip)
        val terminal = Ui.button(c, R.drawable.ic_terminal, null, "Terminal (Ctrl `)", active = true, onClick = onTerminal)
        val chat = Ui.button(c, R.drawable.ic_chat, null, "AI panel (Ctrl L)", active = true, onClick = onChat)
        bar.addView(terminal)
        bar.addView(chat)
        bar.addView(Ui.button(c, R.drawable.ic_new_window, null, "New window (Ctrl Shift N)", onClick = onNewWindow))
        bar.addView(Ui.button(c, R.drawable.ic_settings, null, "Settings (Ctrl ,)", onClick = onSettings))
        return TopBar(bar, folderLabel, title, chip, terminal, chat)
    }

    // ---------------------------------------------------------------- activity bar
    class ActivityItem(val id: String, val icon: Int, val tooltip: String, val onClick: () -> Unit, val bottom: Boolean = false)

    class ActivityBar(val view: LinearLayout, private val buttons: Map<String, ActivityButton>) {
        /** Highlights one item (accent icon plus a 2 dp accent bar on its left) and dims the others. */
        fun setActive(id: String?) { for ((k, b) in buttons) b.setActive(k == id) }
    }

    /** One 36 dp square of the activity bar; draws the accent bar on the left when active. */
    class ActivityButton(c: Context, private val icon: Int) : FrameLayout(c) {
        private var active = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Ui.accent }
        private val img = ImageView(c)
        init {
            val size = c.resources.getDimensionPixelSize(R.dimen.activity_item_size)
            layoutParams = Ui.lp(size, size)
            isClickable = true; isFocusable = true
            background = Ui.states(null, Ui.rounded(Ui.bg4, 8), Ui.rounded(Ui.border2, 8))
            img.setImageDrawable(Ui.icon(c, icon, 0))
            img.isDuplicateParentStateEnabled = true
            val iconSize = Ui.dp(c, 20)
            addView(img, LayoutParams(iconSize, iconSize, Gravity.CENTER))
            setWillNotDraw(false)
            setActive(false)
        }
        fun setActive(on: Boolean) {
            active = on
            img.imageTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_hovered), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(if (on) Ui.accent else Ui.fg, if (on) Ui.accent else Ui.fg, if (on) Ui.accent else Ui.fg3))
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!active) return
            val w = Ui.dp(context, 2).toFloat()
            canvas.drawRoundRect(0f, height * 0.22f, w, height * 0.78f, w, w, paint)
        }
    }

    /** The 44 dp activity bar on the left. Items with `bottom = true` sit at the bottom (Settings). */
    fun activityBar(c: Context, items: List<ActivityItem>): ActivityBar {
        val w = c.resources.getDimensionPixelSize(R.dimen.activity_bar_width)
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setBackgroundColor(Ui.bg2)
        col.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6))
        col.layoutParams = Ui.lp(w, Ui.match)
        val buttons = HashMap<String, ActivityButton>()
        fun add(it: ActivityItem) {
            val b = ActivityButton(c, it.icon)
            b.tooltipText = it.tooltip; b.contentDescription = it.tooltip
            b.setOnClickListener { _ -> it.onClick() }
            val lp = Ui.lp(b.layoutParams.width, b.layoutParams.height); lp.topMargin = Ui.dp(c, 2)
            col.addView(b, lp)
            buttons[it.id] = b
        }
        for (it in items) if (!it.bottom) add(it)
        col.addView(View(c), Ui.lp(1, 0, 1f))
        for (it in items) if (it.bottom) add(it)
        return ActivityBar(col, buttons)
    }

    // ---------------------------------------------------------------- panel tabs
    class PanelTabs(val view: LinearLayout, private val tabs: List<TextView>, private val underlines: List<View>, val actions: LinearLayout) {
        var selected = 0
            private set
        private val names = tabs.map { it.text.toString() }
        /** A count after a tab's name (the number of problems); an empty text removes it. */
        fun setBadge(i: Int, badge: String) {
            val t = tabs.getOrNull(i) ?: return
            if (badge.isEmpty()) { t.text = names[i]; return }
            val sb = android.text.SpannableStringBuilder(names[i] + "  " + badge)
            sb.setSpan(android.text.style.ForegroundColorSpan(Ui.accent2), names[i].length + 2, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            t.text = sb
        }
        fun select(i: Int) {
            selected = i
            for (k in tabs.indices) {
                tabs[k].setTextColor(if (k == i) Ui.fg else Ui.fg3)
                underlines[k].visibility = if (k == i) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    /** The 30 dp strip above the bottom panel: uppercase tabs with an accent underline, plus actions on the right. */
    fun panelTabs(c: Context, names: List<String>, onSelect: (Int) -> Unit): PanelTabs {
        val h = c.resources.getDimensionPixelSize(R.dimen.panel_tabs_height)
        val strip = LinearLayout(c)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setBackgroundColor(Ui.panel)
        strip.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0)
        strip.layoutParams = Ui.lp(Ui.match, h)
        val labels = ArrayList<TextView>(); val lines = ArrayList<View>()
        for ((i, n) in names.withIndex()) {
            val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL
            col.isClickable = true; col.isFocusable = true
            col.background = Ui.states(null, Ui.rounded(Ui.bg3, 4), Ui.rounded(Ui.bg4, 4))
            val t = Ui.text(c, n.uppercase(), 11.5f, Ui.fg3, bold = true)
            t.letterSpacing = 0.06f
            t.gravity = Gravity.CENTER
            t.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0)
            col.addView(t, Ui.lp(Ui.wrap, 0, 1f))
            val line = View(c); line.setBackgroundColor(Ui.accent); line.visibility = View.INVISIBLE
            col.addView(line, Ui.lp(Ui.match, Ui.dp(c, 2)))
            col.setOnClickListener { onSelect(i) }
            strip.addView(col, Ui.lp(Ui.wrap, Ui.match))
            labels.add(t); lines.add(line)
        }
        strip.addView(View(c), Ui.lp(0, 1, 1f))
        val actions = LinearLayout(c); actions.orientation = LinearLayout.HORIZONTAL; actions.gravity = Gravity.CENTER_VERTICAL
        strip.addView(actions, Ui.lp(Ui.wrap, Ui.wrap))
        val tabs = PanelTabs(strip, labels, lines, actions)
        tabs.select(0)
        return tabs
    }

    // ---------------------------------------------------------------- status bar
    class StatusBar(val view: LinearLayout, val ollamaDot: View, val ollama: TextView, val engine: TextView, val memory: TextView,
                    val index: TextView, val right: TextView, val rightBox: LinearLayout, val leftBox: LinearLayout) {
        /** A clickable status item (icon and text) in the right group, like VS Code's position and language items. */
        fun addItem(c: Context, icon: Int, tooltip: String, left: Boolean = false, onClick: (View) -> Unit): Pair<LinearLayout, TextView> {
            val t = Ui.text(c, "", 11.5f, Ui.fg2)
            t.maxLines = 1
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            if (icon != 0) row.addView(Ui.iconView(c, icon, Ui.fg2, 13), Ui.lp(Ui.dp(c, 13), Ui.dp(c, 13)).also { it.rightMargin = Ui.dp(c, 4) })
            row.addView(t)
            row.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2))
            row.background = Ui.states(null, Ui.rounded(Ui.bg4, 4), Ui.rounded(Ui.border2, 4))
            row.isClickable = true
            row.tooltipText = tooltip
            row.setOnClickListener(onClick)
            (if (left) leftBox else rightBox).addView(row, Ui.lp(Ui.wrap, Ui.wrap).also { it.rightMargin = Ui.dp(c, 4) })
            return row to t
        }
        fun setOllama(up: Boolean?) {
            ollama.text = when (up) { true -> "Ollama connected"; false -> "Ollama offline"; null -> "Server unreachable" }
            ollama.setTextColor(if (up == true) Ui.green else Ui.red)
            Ui.setDotColor(ollamaDot, if (up == true) Ui.green else Ui.red)
        }
        fun setEngine(text: String, color: Int = Ui.fg2) { engine.text = text; engine.setTextColor(color); engine.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }
        fun setMemory(text: String, color: Int = Ui.fg2) { memory.text = text; memory.setTextColor(color); memory.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }
        fun setIndex(text: String) { index.text = text; index.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }
    }

    /** The 34 dp status bar: Ollama with a dot, the engine, free memory and the codebase index on the left, a note on the right. */
    fun statusBar(c: Context, onIndexClick: (View) -> Unit): StatusBar {
        val h = c.resources.getDimensionPixelSize(R.dimen.status_bar_height)
        val bar = LinearLayout(c)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Ui.bg2)
        bar.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0)
        bar.layoutParams = Ui.lp(Ui.match, h)
        fun item(s: String): TextView {
            val t = Ui.text(c, s, 11.5f, Ui.fg2)
            t.maxLines = 1; t.ellipsize = TextUtils.TruncateAt.END
            t.setPadding(0, 0, Ui.dp(c, 14), 0)
            return t
        }
        val dot = Ui.dot(c, Ui.fg3)
        (dot.layoutParams as LinearLayout.LayoutParams).rightMargin = Ui.dp(c, 6)
        bar.addView(dot)
        val ollama = item("Ollama"); bar.addView(ollama)
        val engine = item(""); engine.visibility = View.GONE; bar.addView(engine)
        val memory = item(""); memory.visibility = View.GONE; bar.addView(memory)
        val index = item(""); index.visibility = View.GONE
        index.isClickable = true; index.background = Ui.states(null, Ui.rounded(Ui.bg4, 4), Ui.rounded(Ui.border2, 4))
        index.setOnClickListener(onIndexClick)
        index.tooltipText = "Codebase index: click for settings"
        bar.addView(index)
        val leftBox = LinearLayout(c); leftBox.orientation = LinearLayout.HORIZONTAL; leftBox.gravity = Gravity.CENTER_VERTICAL
        bar.addView(leftBox, Ui.lp(Ui.wrap, Ui.wrap))
        bar.addView(View(c), Ui.lp(0, 1, 1f))
        val rightBox = LinearLayout(c); rightBox.orientation = LinearLayout.HORIZONTAL; rightBox.gravity = Gravity.CENTER_VERTICAL
        bar.addView(rightBox, Ui.lp(Ui.wrap, Ui.wrap))
        val right = Ui.text(c, "", 11.5f, Ui.fg3); right.maxLines = 1
        right.setPadding(Ui.dp(c, 6), 0, 0, 0)
        bar.addView(right)
        return StatusBar(bar, dot, ollama, engine, memory, index, right, rightBox, leftBox)
    }

    // ---------------------------------------------------------------- splitters
    /**
     * A thin draggable handle between two panels. `vertical` is a vertical bar that moves left and right (it
     * resizes widths); otherwise it is a horizontal bar that moves up and down (it resizes heights). It is
     * transparent at rest and accent colored while hovered or dragged, like the web page's gutters. `onDrag`
     * receives the movement since the last event in pixels; `onEnd` runs when the pointer is released.
     */
    fun splitter(c: Context, vertical: Boolean, onDrag: (Int) -> Unit, onEnd: () -> Unit = {}): View {
        val size = c.resources.getDimensionPixelSize(R.dimen.splitter_size)
        val v = View(c)
        v.layoutParams = if (vertical) Ui.lp(size, Ui.match) else Ui.lp(Ui.match, size)
        v.setBackgroundColor(Ui.border)
        v.pointerIcon = PointerIcon.getSystemIcon(c, if (vertical) PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW else PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)
        var last = 0f
        var dragging = false
        v.setOnHoverListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> view.setBackgroundColor(Ui.accent)
                MotionEvent.ACTION_HOVER_EXIT -> if (!dragging) view.setBackgroundColor(Ui.border)
            }
            false
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { last = if (vertical) e.rawX else e.rawY; dragging = true; view.setBackgroundColor(Ui.accent); view.parent?.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_MOVE -> {
                    val now = if (vertical) e.rawX else e.rawY
                    val delta = (now - last).toInt()
                    if (delta != 0) { last = now; onDrag(delta) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; view.setBackgroundColor(Ui.border); onEnd(); true }
                else -> false
            }
        }
        return v
    }

    // ---------------------------------------------------------------- panel headers
    /** A side panel header: uppercase title on the left, small action buttons on the right. */
    fun panelHeader(c: Context, title: String, vararg actions: View): LinearLayout {
        val row = Ui.row(c, Ui.sectionLabel(c, title).also { it.layoutParams = Ui.lp(0, Ui.wrap, 1f) }, *actions)
        row.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8))
        row.layoutParams = Ui.lp(Ui.match, Ui.wrap)
        return row
    }

}
