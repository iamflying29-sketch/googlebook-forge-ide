package local.forge

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.widget.EditText
import org.json.JSONObject
import java.util.UUID

/**
 * The chat input's tokens, like Cursor's: an @ mention (context), a / command or a / skill sits in the text as one
 * colored pill. Each token stands for something real that goes with the message: an @ token is tied to its
 * attachment (the file, folder, docs, terminal output and so on that the model receives), a command token sends the
 * command's instructions and a skill token sends the skill's SKILL.md. Backspace removes a token whole, and removing
 * a token removes what it stood for. Lines that begin with "> " are quotes and get a quote bar.
 */
object ComposerTokens {
    const val CONTEXT = "context"
    const val COMMAND = "command"
    const val SKILL = "skill"

    /** One token: [label] is its text in the input ("@main.py", "/review"); [id] ties it to its attachment. */
    class TokenSpan(val kind: String, val label: String, val data: JSONObject, val id: String = UUID.randomUUID().toString().take(12)) : ReplacementSpan() {
        private val padH = 5f * Ui.density
        private val rect = RectF()

        private fun colors(): Pair<Int, Int> = when (kind) {
            COMMAND -> 0x40ff8a3d to Ui.accent2
            SKILL -> 0x40c792ea to 0xffd7b4ff.toInt()
            else -> 0x403d7bf6 to 0xff9cc2ff.toInt()
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (paint.measureText(label) + padH * 2).toInt()

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val (bg, fg) = colors()
            val oldColor = paint.color
            val fmi = paint.fontMetrics
            rect.set(x, y + fmi.ascent - 1.5f * Ui.density, x + paint.measureText(label) + padH * 2, y + fmi.descent + 1.5f * Ui.density)
            paint.color = bg
            canvas.drawRoundRect(rect, 4 * Ui.density, 4 * Ui.density, paint)
            paint.color = fg
            canvas.drawText(label, x + padH, y.toFloat(), paint)
            paint.color = oldColor
        }
    }

    /** The quote styling: a bar on the left, the text dimmer and slanted. Tagged so a restyle can take it off again. */
    class QuoteMark : QuoteSpan(Ui.accent2, (3 * Ui.density).toInt(), (8 * Ui.density).toInt())
    class QuoteColor : ForegroundColorSpan(Ui.fg2)
    class QuoteSlant : StyleSpan(Typeface.ITALIC)

    fun tokens(s: Spanned): List<TokenSpan> = s.getSpans(0, s.length, TokenSpan::class.java).sortedBy { s.getSpanStart(it) }

    /** Puts a token at [at] (replacing [at] until [to]) with a space after it, and the cursor after the space. */
    fun insert(edit: EditText, span: TokenSpan, at: Int, to: Int = at) {
        val e = edit.text
        val a = at.coerceIn(0, e.length); val b = to.coerceIn(a, e.length)
        val after = if (b < e.length && e[b] == ' ') "" else " "
        e.replace(a, b, span.label + after)
        e.setSpan(span, a, a + span.label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        edit.setSelection((a + span.label.length + after.length).coerceAtMost(e.length))
    }

    /** Backspace (or Delete) next to a token removes the whole token; true when it did. */
    fun deleteWhole(edit: EditText, forward: Boolean): Boolean {
        val e = edit.text
        val a = edit.selectionStart; val b = edit.selectionEnd
        if (a != b || a < 0) return false
        for (t in tokens(e)) {
            val s = e.getSpanStart(t); val en = e.getSpanEnd(t)
            if ((!forward && en == a) || (forward && s == a)) {
                e.removeSpan(t)
                e.delete(s, en)
                edit.setSelection(s.coerceAtMost(e.length))
                return true
            }
        }
        return false
    }

    /** After an edit: a token whose text was cut into goes away whole, and quote lines are styled again. */
    fun afterEdit(e: Editable) {
        for (t in tokens(e)) {
            val s = e.getSpanStart(t); val en = e.getSpanEnd(t)
            if (s < 0 || en <= s || e.subSequence(s, en).toString() != t.label) {
                e.removeSpan(t)
                if (s in 0 until en) e.delete(s, en)
            }
        }
        styleQuotes(e)
    }

    /** Lines that begin with ">" get the quote bar, the dimmer color and slanted text. */
    fun styleQuotes(e: Spannable) {
        for (sp in e.getSpans(0, e.length, QuoteMark::class.java)) e.removeSpan(sp)
        for (sp in e.getSpans(0, e.length, QuoteColor::class.java)) e.removeSpan(sp)
        for (sp in e.getSpans(0, e.length, QuoteSlant::class.java)) e.removeSpan(sp)
        var i = 0
        val n = e.length
        while (i <= n) {
            var j = i
            while (j < n && e[j] != '\n') j++
            if (j > i && e[i] == '>') {
                val end = if (j < n) j + 1 else j
                e.setSpan(QuoteMark(), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                e.setSpan(QuoteColor(), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                e.setSpan(QuoteSlant(), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i = j + 1
        }
    }

    /** A sent message as it shows in the chat: its tokens (from the message's attachments) and quotes styled. */
    fun styled(text: String, attachments: List<JSONObject>): CharSequence {
        val sb = SpannableStringBuilder(text)
        var from = 0
        for (a in attachments) {
            val label = a.optString("label")
            if (label.isEmpty()) continue
            val kind = when (a.optString("kind")) { "command" -> COMMAND; "skill" -> SKILL; else -> CONTEXT }
            val at = text.indexOf(label, if (kind == CONTEXT) from else 0)
            if (at < 0) continue
            if (sb.getSpans(at, at + label.length, TokenSpan::class.java).isNotEmpty()) continue
            sb.setSpan(TokenSpan(kind, label, a), at, at + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (kind == CONTEXT) from = at + label.length
        }
        styleQuotes(sb)
        return sb
    }
}
