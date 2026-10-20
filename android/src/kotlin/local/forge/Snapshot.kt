package local.forge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import android.view.View
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Pictures of the native window, rendered by the app itself and sent to the Forge server (POST /api/snapshot, kept in
 * ~/.forge-ide/snapshots in the VM). This is how the look of every view can be checked while the Googlebook's screen
 * is off or the window is covered: no screenshot of the user's screen and no input on it is needed.
 *
 * A window that is not on screen gets no layout passes from Android, so the view is measured and laid out here at
 * the size of the display before it is drawn.
 */
object Snapshot {
    private const val TAG = "ForgeSnapshot"

    fun render(view: View, name: String, done: ((Boolean) -> Unit)? = null) {
        try {
            val dm = view.resources.displayMetrics
            val w = if (view.width > 0 && view.isLaidOut && view.isShown) view.width else dm.widthPixels
            val h = if (view.height > 0 && view.isLaidOut && view.isShown) view.height else dm.heightPixels
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(view.left, view.top, view.left + w, view.top + h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            val data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            Api.async("snapshot", { Api.post("/api/snapshot", JSONObject().put("name", name).put("data", data), 60000) }) { r, e ->
                if (r == null) Log.w(TAG, "snapshot $name not sent: " + (e?.message ?: ""))
                done?.invoke(r != null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "snapshot $name failed", e)
            done?.invoke(false)
        }
    }

    /** Runs the steps one after another: each step's action, then a picture after [delayMs] for the data to arrive. */
    fun sequence(view: View, steps: List<String>, delayMs: Long, target: () -> View = { view }, action: (String) -> Unit, finished: (() -> Unit)? = null) {
        fun next(i: Int) {
            if (i >= steps.size) { finished?.invoke(); return }
            val step = steps[i]
            try { action(step) } catch (e: Exception) { Log.w(TAG, "step $step failed", e) }
            Api.main.postDelayed({ render(target(), step.replace(Regex("[^A-Za-z0-9_.-]+"), "-")) { next(i + 1) } }, delayMs)
        }
        next(0)
    }
}
