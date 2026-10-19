package local.forge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * The Explorer's icons, drawn with Canvas so they look the same on every device: folders in the web page's folder
 * color, files as a page with the language's short label in the language's color, images as a small picture, and
 * the four header actions (new file, new folder, refresh, collapse all).
 */
object FileIcons {
    val folderColor = Color.parseColor("#ffcb6b")
    private val pageColor = Color.parseColor("#717896")
    private val imageColor = Color.parseColor("#4fd18b")
    private val plainColor = Color.parseColor("#94a3b8")

    /** The short label and the color of a language, the same table as the web page's file icons. */
    private val badges = mapOf(
        "python" to ("PY" to "#3b82f6"), "javascript" to ("JS" to "#eab308"), "typescript" to ("TS" to "#3b82f6"),
        "json" to ("{}" to "#eab308"), "html" to ("<>" to "#f97316"), "css" to ("#" to "#8b5cf6"), "markdown" to ("M" to "#60a5fa"),
        "shell" to ("$" to "#22c55e"), "dockerfile" to ("D" to "#22c55e"), "yaml" to ("Y" to "#ef4444"), "ini" to ("=" to "#94a3b8"),
        "java" to ("J" to "#f97316"), "kotlin" to ("K" to "#a855f7"), "go" to ("GO" to "#06b6d4"), "rust" to ("RS" to "#f97316"),
        "c" to ("C" to "#60a5fa"), "cpp" to ("C+" to "#60a5fa"), "plaintext" to ("≡" to "#94a3b8"))

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "avif")

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in imageExtensions

    /** The text badge of a file (label and color), for lists that show a letter code instead of a drawn icon. */
    fun badge(name: String): Pair<String, Int> {
        if (isImage(name)) return "IMG" to imageColor
        val b = badges[Highlighter.languageId(name)] ?: ("·" to "#94a3b8")
        return b.first to Color.parseColor(b.second)
    }

    /** The color that stands for a file's language. */
    fun colorFor(name: String): Int = badge(name).second

    fun folder(c: Context, open: Boolean): Drawable = Glyph(Ui.dp(c, 16), if (open) Kind.FOLDER_OPEN else Kind.FOLDER, folderColor, "")
    fun file(c: Context, name: String): Drawable {
        if (isImage(name)) return Glyph(Ui.dp(c, 16), Kind.IMAGE, imageColor, "")
        if (Highlighter.languageId(name) == "plaintext") return Glyph(Ui.dp(c, 16), Kind.PAGE, plainColor, "")
        val b = badge(name)
        return Glyph(Ui.dp(c, 16), Kind.PAGE, b.second, b.first)
    }

    /** One of the Explorer header actions: "new-file", "new-folder", "refresh" or "collapse". */
    fun action(c: Context, kind: String): Drawable {
        val k = when (kind) { "new-file" -> Kind.NEW_FILE; "new-folder" -> Kind.NEW_FOLDER; "refresh" -> Kind.REFRESH; else -> Kind.COLLAPSE }
        return Glyph(Ui.dp(c, 16), k, Ui.fg2, "")
    }

    enum class Kind { FOLDER, FOLDER_OPEN, PAGE, IMAGE, NEW_FILE, NEW_FOLDER, REFRESH, COLLAPSE }

    /** A square icon of the given size in pixels. Every shape is drawn relative to that size. */
    class Glyph(private val size: Int, private val kind: Kind, private val color: Int, private val label: String) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND; it.strokeJoin = Paint.Join.ROUND }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); it.textAlign = Paint.Align.CENTER }
        private val path = Path()

        override fun getIntrinsicWidth(): Int = size
        override fun getIntrinsicHeight(): Int = size
        override fun setAlpha(alpha: Int) { fill.alpha = alpha; stroke.alpha = alpha; text.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { fill.colorFilter = filter; stroke.colorFilter = filter; text.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            if (s <= 0f) return
            val x = b.left + (b.width() - s) / 2f
            val y = b.top + (b.height() - s) / 2f
            val u = s / 16f // one design unit; every shape is drawn on a 16 by 16 grid
            stroke.strokeWidth = 1.4f * u
            stroke.color = color; fill.color = color; text.color = color
            when (kind) {
                Kind.FOLDER, Kind.FOLDER_OPEN, Kind.NEW_FOLDER -> drawFolder(canvas, x, y, u, kind == Kind.FOLDER_OPEN)
                Kind.PAGE, Kind.NEW_FILE -> drawPage(canvas, x, y, u)
                Kind.IMAGE -> drawImage(canvas, x, y, u)
                Kind.REFRESH -> drawRefresh(canvas, x, y, u)
                Kind.COLLAPSE -> drawCollapse(canvas, x, y, u)
            }
            if (kind == Kind.NEW_FILE || kind == Kind.NEW_FOLDER) drawPlus(canvas, x + 11f * u, y + 11.5f * u, u)
        }

        private fun drawFolder(canvas: Canvas, x: Float, y: Float, u: Float, open: Boolean) {
            // The back of the folder: a rounded body with a tab on the left of the top edge.
            path.reset()
            path.moveTo(x + 1.5f * u, y + 4f * u)
            path.lineTo(x + 6f * u, y + 4f * u)
            path.lineTo(x + 7.5f * u, y + 5.5f * u)
            path.lineTo(x + 14.5f * u, y + 5.5f * u)
            path.lineTo(x + 14.5f * u, y + 13f * u)
            path.lineTo(x + 1.5f * u, y + 13f * u)
            path.close()
            fill.alpha = if (open) 70 else 110
            canvas.drawPath(path, fill)
            fill.alpha = 255
            canvas.drawPath(path, stroke)
            if (open) {
                // The front flap, slightly lower, so the folder looks opened.
                path.reset()
                path.moveTo(x + 3f * u, y + 8f * u)
                path.lineTo(x + 15.5f * u, y + 8f * u)
                path.lineTo(x + 14f * u, y + 13f * u)
                path.lineTo(x + 1.5f * u, y + 13f * u)
                path.close()
                fill.alpha = 160
                canvas.drawPath(path, fill)
                fill.alpha = 255
                canvas.drawPath(path, stroke)
            }
        }

        private fun pagePath(x: Float, y: Float, u: Float) {
            path.reset()
            path.moveTo(x + 3f * u, y + 1.5f * u)
            path.lineTo(x + 9.5f * u, y + 1.5f * u)
            path.lineTo(x + 13f * u, y + 5f * u)
            path.lineTo(x + 13f * u, y + 14.5f * u)
            path.lineTo(x + 3f * u, y + 14.5f * u)
            path.close()
        }

        private fun drawPage(canvas: Canvas, x: Float, y: Float, u: Float) {
            pagePath(x, y, u)
            stroke.color = pageColor
            canvas.drawPath(path, stroke)
            // The folded corner.
            path.reset()
            path.moveTo(x + 9.5f * u, y + 1.5f * u)
            path.lineTo(x + 9.5f * u, y + 5f * u)
            path.lineTo(x + 13f * u, y + 5f * u)
            canvas.drawPath(path, stroke)
            stroke.color = color
            if (label.isNotEmpty()) {
                text.textSize = (if (label.length > 1) 5.2f else 7f) * u
                canvas.drawText(label, x + 8f * u, y + 12f * u, text)
            } else if (kind == Kind.PAGE) {
                // Plain text: three short lines.
                stroke.color = pageColor
                for (i in 0..2) canvas.drawLine(x + 5f * u, y + (7.5f + 2.5f * i) * u, x + (9f + (if (i == 2) -2f else 2f)) * u, y + (7.5f + 2.5f * i) * u, stroke)
                stroke.color = color
            }
        }

        private fun drawImage(canvas: Canvas, x: Float, y: Float, u: Float) {
            val r = RectF(x + 1.5f * u, y + 2.5f * u, x + 14.5f * u, y + 13.5f * u)
            canvas.drawRoundRect(r, 1.5f * u, 1.5f * u, stroke)
            // A sun and two mountains.
            canvas.drawCircle(x + 5.2f * u, y + 6f * u, 1.3f * u, fill)
            path.reset()
            path.moveTo(x + 2.5f * u, y + 12.5f * u)
            path.lineTo(x + 6.5f * u, y + 8f * u)
            path.lineTo(x + 9f * u, y + 10.5f * u)
            path.lineTo(x + 10.5f * u, y + 9f * u)
            path.lineTo(x + 13.5f * u, y + 12.5f * u)
            path.close()
            fill.alpha = 170
            canvas.drawPath(path, fill)
            fill.alpha = 255
        }

        private fun drawRefresh(canvas: Canvas, x: Float, y: Float, u: Float) {
            val r = RectF(x + 3f * u, y + 3f * u, x + 13f * u, y + 13f * u)
            canvas.drawArc(r, 30f, 270f, false, stroke)
            // The arrow head at the end of the arc (its end lies at 300 degrees, upper right).
            path.reset()
            path.moveTo(x + 10.5f * u, y + 1.5f * u)
            path.lineTo(x + 13.5f * u, y + 4.5f * u)
            path.lineTo(x + 9.5f * u, y + 5.5f * u)
            path.close()
            canvas.drawPath(path, fill)
        }

        private fun drawCollapse(canvas: Canvas, x: Float, y: Float, u: Float) {
            // Two chevrons pointing at each other: the "collapse all" sign.
            path.reset()
            path.moveTo(x + 5f * u, y + 2f * u); path.lineTo(x + 8f * u, y + 5.5f * u); path.lineTo(x + 11f * u, y + 2f * u)
            path.moveTo(x + 5f * u, y + 14f * u); path.lineTo(x + 8f * u, y + 10.5f * u); path.lineTo(x + 11f * u, y + 14f * u)
            canvas.drawPath(path, stroke)
            canvas.drawLine(x + 3f * u, y + 8f * u, x + 13f * u, y + 8f * u, stroke)
        }

        private fun drawPlus(canvas: Canvas, cx: Float, cy: Float, u: Float) {
            // A small plus sign on a dark disc, in the lower right corner.
            fill.color = Ui.bg2
            canvas.drawCircle(cx, cy, 4f * u, fill)
            fill.color = color
            stroke.color = Ui.green
            canvas.drawLine(cx - 2.2f * u, cy, cx + 2.2f * u, cy, stroke)
            canvas.drawLine(cx, cy - 2.2f * u, cx, cy + 2.2f * u, stroke)
            stroke.color = color
        }
    }
}
