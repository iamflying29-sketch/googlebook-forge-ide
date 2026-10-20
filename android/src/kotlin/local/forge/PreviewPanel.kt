package local.forge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The previews of the editor, the port of the old page's preview.js: the live Markdown preview that sits next to a
 * Markdown file (Ctrl Shift V) and the image viewer that opens instead of an editor for png, jpg, gif, webp, bmp,
 * ico and avif files.
 *
 * The Markdown preview renders with Markdown.kt 250 ms after the last change and keeps its scroll position. Images
 * in the Markdown that point into the project are loaded through /api/raw; images on the internet are shown as
 * their description only, because Forge keeps everything on this machine.
 */
object PreviewPanel {
    private val imageRe = Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")

    /** The Markdown preview of one file. [update] takes the file's current text. */
    class MarkdownPreview(private val ctx: Context, private val host: MainActivity, var path: String, private val onClose: () -> Unit) {
        val view: LinearLayout = LinearLayout(ctx)
        private val body = Ui.column(ctx)
        private val scroll = ScrollView(ctx)
        private val title = Ui.text(ctx, "", 11f, Ui.fg3, bold = true)
        private var pending = ""
        private var rendered = ""
        private val task = Runnable { render() }

        init {
            view.orientation = LinearLayout.VERTICAL
            view.setBackgroundColor(Ui.bg)
            title.letterSpacing = 0.06f
            val head = Ui.row(ctx, Ui.iconView(ctx, R.drawable.ic_preview, Ui.fg3, 14), title,
                Ui.miniButton(ctx, R.drawable.ic_close, "Close the preview (Ctrl Shift V)") { onClose() })
            title.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = Ui.dp(ctx, 6) }
            head.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4))
            head.setBackgroundColor(Ui.bg2)
            view.addView(head, Ui.lp(Ui.match, Ui.dp(ctx, 30)))
            view.addView(Ui.divider(ctx, false))
            body.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 16), Ui.dp(ctx, 24), Ui.dp(ctx, 40))
            scroll.addView(body)
            scroll.isFillViewport = true
            view.addView(scroll, Ui.lp(Ui.match, 0, 1f))
            title.text = "PREVIEW  " + path.substringAfterLast('/')
        }

        fun update(text: String) {
            pending = text
            Api.main.removeCallbacks(task)
            Api.main.postDelayed(task, 250)
        }

        fun renameTo(p: String) { path = p; title.text = "PREVIEW  " + p.substringAfterLast('/') }

        private fun render() {
            if (pending == rendered) return
            rendered = pending
            val y = scroll.scrollY
            body.removeAllViews()
            // Image paragraphs become image views; the rest goes through the chat's Markdown renderer in pieces.
            val chunk = StringBuilder()
            fun flush() {
                if (chunk.isEmpty()) return
                val part = Ui.column(ctx)
                Markdown.render(ctx, chunk.toString(), part) { s -> host.copyToClipboard(s) }
                body.addView(part, Ui.lp(Ui.match, Ui.wrap))
                chunk.setLength(0)
            }
            var inFence = false
            for (line in rendered.split('\n')) {
                if (line.trimStart().startsWith("```")) inFence = !inFence
                val m = if (inFence) null else imageRe.matchEntire(line.trim())
                if (m == null) { chunk.append(line).append('\n'); continue }
                flush()
                body.addView(imageView(m.groupValues[1], m.groupValues[2]), Ui.lp(Ui.match, Ui.wrap).also { it.topMargin = Ui.dp(ctx, 6); it.bottomMargin = Ui.dp(ctx, 6) })
            }
            flush()
            scroll.post { scroll.scrollTo(0, y) }
        }

        private fun imageView(alt: String, src: String): View {
            if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:")) {
                val t = Ui.text(ctx, "Image: " + alt.ifEmpty { src } + "  (on the internet, not loaded)", 12f, Ui.fg3)
                t.background = Ui.rounded(Ui.bg2, 6, Ui.border)
                t.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
                return t
            }
            val dir = if (path.contains('/')) path.substringBeforeLast('/') + "/" else ""
            // "/x.png" means the project's root, as on GitHub; anything else is next to the Markdown file.
            val rel = normalize(if (src.startsWith("/")) src.trimStart('/') else dir + src)
            val iv = ImageView(ctx)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_START
            iv.contentDescription = alt
            Api.async("md-image", { decode(Api.bytes("/api/raw", mapOf("path" to rel), 20 * 1024 * 1024), 1600) }) { bmp, _ ->
                if (bmp != null) iv.setImageBitmap(bmp)
                else { iv.setImageDrawable(null); iv.contentDescription = "Cannot show $rel" }
            }
            return iv
        }

        private fun normalize(p: String): String {
            val out = ArrayList<String>()
            for (seg in p.split('/')) when (seg) { "", "." -> {}; ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1); else -> out.add(seg) }
            // A file in a folder added to the window has an absolute path; its images keep the leading slash.
            return (if (p.startsWith("/")) "/" else "") + out.joinToString("/")
        }
    }

    /** Decodes image bytes with a cap on the longest side, so a huge photo cannot exhaust the app's memory. */
    fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options(); bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) sample *= 2
        val o = BitmapFactory.Options(); o.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }

    fun isImage(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif")
    fun isMarkdown(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdx")

    /** The image viewer: the picture fitted to the view, zoom with the wheel, pinch, the buttons or a click (actual size). */
    class ImageViewer(private val ctx: Context, var path: String) {
        val view: LinearLayout = LinearLayout(ctx)
        private val image = ImageView(ctx)
        private val info = Ui.text(ctx, "", 11.5f, Ui.fg3)
        private val zoomLabel = Ui.text(ctx, "", 11.5f, Ui.fg2, mono = true)
        private var bmp: Bitmap? = null
        private var natW = 0
        private var natH = 0
        private var scale = 1f
        private var fit = true
        private var tx = 0f
        private var ty = 0f
        private var lastX = 0f
        private var lastY = 0f

        init {
            view.orientation = LinearLayout.VERTICAL
            view.setBackgroundColor(Ui.bg)
            val bar = Ui.row(ctx, Ui.iconView(ctx, R.drawable.ic_photo, Ui.fg3, 14), info, zoomLabel,
                Ui.miniButton(ctx, R.drawable.ic_zoom_out, "Zoom out") { zoom(1 / 1.25f) },
                Ui.miniButton(ctx, R.drawable.ic_zoom_in, "Zoom in") { zoom(1.25f) },
                Ui.miniButton(ctx, R.drawable.ic_maximize, "Fit to the window") { fit = true; layoutImage() },
                Ui.miniButton(ctx, R.drawable.ic_refresh, "Reload from disk") { load() })
            info.layoutParams = Ui.lp(0, Ui.wrap, 1f).also { it.leftMargin = Ui.dp(ctx, 6) }
            zoomLabel.setPadding(0, 0, Ui.dp(ctx, 8), 0)
            bar.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 3), Ui.dp(ctx, 6), Ui.dp(ctx, 3))
            bar.setBackgroundColor(Ui.bg2)
            view.addView(bar, Ui.lp(Ui.match, Ui.dp(ctx, 30)))
            view.addView(Ui.divider(ctx, false))
            val frame = object : FrameLayout(ctx) {
                override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); layoutImage() }
            }
            // A checkerboard-like dark backdrop so transparent images stay visible.
            frame.setBackgroundColor(0xff0b0d12.toInt())
            image.scaleType = ImageView.ScaleType.MATRIX
            frame.addView(image, FrameLayout.LayoutParams(Ui.match, Ui.match))
            view.addView(frame, Ui.lp(Ui.match, 0, 1f))
            val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean { zoom(d.scaleFactor, d.focusX, d.focusY); return true }
            })
            image.setOnTouchListener { _, ev ->
                scaler.onTouchEvent(ev)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX = ev.x; lastY = ev.y }
                    MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && ev.pointerCount == 1) { tx += ev.x - lastX; ty += ev.y - lastY; lastX = ev.x; lastY = ev.y; fit = false; apply() }
                }
                true
            }
            image.setOnGenericMotionListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_SCROLL) {
                    val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) zoom(if (v > 0) 1.15f else 1 / 1.15f, ev.x, ev.y)
                    true
                } else false
            }
            load()
        }

        fun load() {
            info.text = "Loading " + path
            val p = path
            Api.async("image", {
                val bytes = Api.bytes("/api/raw", mapOf("path" to p))
                val bounds = BitmapFactory.Options(); bounds.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                Triple(decode(bytes, 4096), bounds.outWidth to bounds.outHeight, bytes.size)
            }) { r, e ->
                if (p != path) return@async
                if (r?.first == null) { info.text = "Cannot show $p: " + (e?.message ?: "not an image Android can decode"); return@async }
                bmp = r.first
                natW = r.second.first; natH = r.second.second
                image.setImageBitmap(bmp)
                info.text = p + "   ·   " + natW + " × " + natH + " pixels   ·   " + human(r.third)
                fit = true
                layoutImage()
            }
        }

        private fun human(n: Int): String = if (n < 1024) "$n B" else if (n < 1024 * 1024) String.format("%.1f KB", n / 1024.0) else String.format("%.1f MB", n / 1048576.0)

        private fun layoutImage() {
            val b = bmp ?: return
            val w = image.width; val h = image.height
            if (w <= 0 || h <= 0) { image.post { layoutImage() }; return }
            if (fit) {
                // Small pictures show at their real size; larger ones shrink to fit.
                scale = minOf(1f * w / b.width, 1f * h / b.height, 1f * natW.coerceAtLeast(1) / b.width)
                tx = (w - b.width * scale) / 2f; ty = (h - b.height * scale) / 2f
            }
            apply()
        }

        private fun zoom(f: Float, fx: Float = image.width / 2f, fy: Float = image.height / 2f) {
            if (bmp == null) return
            val ns = (scale * f).coerceIn(0.02f, 40f)
            val k = ns / scale
            tx = fx - (fx - tx) * k; ty = fy - (fy - ty) * k
            scale = ns; fit = false
            apply()
        }

        private fun apply() {
            val b = bmp ?: return
            val m = Matrix(); m.postScale(scale, scale); m.postTranslate(tx, ty)
            image.imageMatrix = m
            val shown = scale * b.width / natW.coerceAtLeast(1)
            zoomLabel.text = String.format("%d%%", Math.round(shown * 100))
        }
    }
}
