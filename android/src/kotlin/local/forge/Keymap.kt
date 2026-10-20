package local.forge

import android.view.KeyEvent

/**
 * Forge's key bindings, like Cursor's: every binding has its keys, the part of the window where it applies and the
 * command it runs. Two-key chords work the way they do in Cursor and VS Code: Ctrl K (VS Code's chords) or Ctrl R
 * (Cursor moved VS Code's Ctrl K chords there), then a second key. When no second key follows, Ctrl K alone opens the
 * editor's inline edit and Ctrl R alone reloads the window.
 *
 * Keys are written like "Ctrl+Shift+K" and chords like "Ctrl+K Ctrl+C". The palette shows them with spaces
 * ("Ctrl Shift K") and the Keyboard Shortcuts page lists them all.
 */
object Keymap {
    /** Where a binding applies: the code editor, the chat's input, the terminal, or anywhere but the terminal. */
    enum class Where { EDITOR, CHAT, TERMINAL, WINDOW, ANYWHERE }

    /** [enabled] lets a key do its usual thing when the binding does not fit (Tab in a chat input with text, say). */
    class Binding(val keys: String, val where: Where, val title: String, val run: () -> Unit, val enabled: () -> Boolean = { true })

    /** The first keys of the two-key chords: Ctrl K (VS Code) and Ctrl R (Cursor). */
    val chordPrefixes = setOf("Ctrl+K", "Ctrl+R")

    /** The name of one key press, with its modifiers in a fixed order: Ctrl, Shift, Alt. Empty for a modifier alone. */
    fun stroke(ev: KeyEvent): String {
        val key = keyName(ev.keyCode) ?: return ""
        val sb = StringBuilder()
        if (ev.isCtrlPressed) sb.append("Ctrl+")
        if (ev.isShiftPressed) sb.append("Shift+")
        if (ev.isAltPressed) sb.append("Alt+")
        sb.append(key)
        return sb.toString()
    }

    fun keyName(code: Int): String? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (code - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (code - KeyEvent.KEYCODE_0)).toString()
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F" + (code - KeyEvent.KEYCODE_F1 + 1)
        KeyEvent.KEYCODE_NUMPAD_0 -> "Numpad0"
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "-"
        KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> "="
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_SEMICOLON -> ";"
        KeyEvent.KEYCODE_APOSTROPHE -> "'"
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_SLASH -> "/"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_INSERT -> "Insert"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        else -> null
    }

    /** "Ctrl+Shift+K" as the palette shows it: "Ctrl Shift K". */
    fun pretty(keys: String): String = keys.split(' ').joinToString(", ") { it.replace("+", " ").replace("PageUp", "Page Up").replace("PageDown", "Page Down") }

    /** A binding applies here: [inEditor], [inChat] and [inTerminal] describe the focused view. */
    fun applies(b: Binding, inEditor: Boolean, inChat: Boolean, inTerminal: Boolean): Boolean = when (b.where) {
        Where.EDITOR -> inEditor
        Where.CHAT -> inChat
        Where.TERMINAL -> inTerminal
        Where.WINDOW -> !inTerminal
        Where.ANYWHERE -> true
    }
}
