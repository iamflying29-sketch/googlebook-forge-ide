package local.forge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The breakpoints of this window, keyed by the file's path the way the editor names it (relative to the window's
 * folder for files inside it, absolute for files outside it). They are saved per folder in the app's preferences,
 * as the old page saved them in the browser's storage, so they survive a restart.
 *
 * The editor draws them through [lines] and toggles them through [toggle]; [listener] tells the editor which file to
 * redraw. The debug view registers [sessionListener] to update its own list and to send the file's breakpoints to
 * the running adapter. A breakpoint can be disabled without being removed: it stays in the list and in the gutter,
 * but it is not sent to the adapter.
 *
 * Every function is safe to call from any thread, but callers are expected to be on the main thread (the listeners
 * are called on the caller's thread).
 */
object Breakpoints {
    /** The editor's redraw hook: called with the path of the file whose breakpoints changed. */
    @Volatile var listener: ((String) -> Unit)? = null
    /** The debug view's hook, called after [listener] with the same path. */
    @Volatile internal var sessionListener: ((String) -> Unit)? = null

    private val lines = LinkedHashMap<String, java.util.TreeSet<Int>>()
    private val disabled = HashMap<String, HashSet<Int>>()
    private var prefs: SharedPreferences? = null
    private var loadedFolder: String? = null

    /** Opens the saved breakpoints. Cheap to call more than once; the debug view calls it when it is built. */
    fun init(ctx: Context) {
        synchronized(this) {
            if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("forge-breakpoints", Context.MODE_PRIVATE)
            ensureLoaded()
        }
    }

    /** The path the way the editor names files: relative inside the window's folder, absolute elsewhere. */
    fun normalize(path: String): String {
        val root = Api.folder.trimEnd('/')
        if (root.isNotEmpty() && path.startsWith("$root/")) return path.substring(root.length + 1)
        return path
    }

    /** The absolute path of a file the editor names relatively. */
    fun absolute(path: String): String = if (path.startsWith("/")) path else Api.folder.trimEnd('/') + "/" + path

    fun toggle(path: String, line: Int) {
        if (line < 1) return
        val p = normalize(path)
        synchronized(this) {
            ensureLoaded()
            val set = lines.getOrPut(p) { java.util.TreeSet() }
            if (!set.remove(line)) set.add(line) else disabled[p]?.remove(line)
            if (set.isEmpty()) lines.remove(p)
            persist()
        }
        notify(p)
    }

    /** Every breakpoint line of the file, enabled or not, in ascending order. */
    fun lines(path: String): Set<Int> {
        synchronized(this) { ensureLoaded(); return lines[normalize(path)]?.let { java.util.TreeSet(it) } ?: emptySet() }
    }

    /** The lines the adapter should stop at: the enabled breakpoints of the file, in ascending order. */
    fun enabledLines(path: String): List<Int> {
        synchronized(this) {
            ensureLoaded()
            val p = normalize(path)
            val off = disabled[p] ?: emptySet<Int>()
            return (lines[p] ?: emptySet<Int>()).filter { it !in off }
        }
    }

    fun isEnabled(path: String, line: Int): Boolean = synchronized(this) { disabled[normalize(path)]?.contains(line) != true }

    fun setEnabled(path: String, line: Int, enabled: Boolean) {
        val p = normalize(path)
        synchronized(this) {
            ensureLoaded()
            if (enabled) disabled[p]?.remove(line) else disabled.getOrPut(p) { HashSet() }.add(line)
            persist()
        }
        notify(p)
    }

    fun remove(path: String, line: Int) {
        val p = normalize(path)
        synchronized(this) {
            ensureLoaded()
            val set = lines[p] ?: return
            set.remove(line); disabled[p]?.remove(line)
            if (set.isEmpty()) lines.remove(p)
            persist()
        }
        notify(p)
    }

    /** Removes every breakpoint and returns the files that had one, so the caller can tell the adapter. */
    fun clear(): List<String> {
        val had: List<String>
        synchronized(this) {
            ensureLoaded()
            had = ArrayList(lines.keys)
            lines.clear(); disabled.clear()
            persist()
        }
        for (p in had) notify(p)
        return had
    }

    /** The files that have breakpoints, in the order they were first added. */
    fun files(): List<String> = synchronized(this) { ensureLoaded(); ArrayList(lines.keys) }

    private fun notify(path: String) {
        listener?.invoke(path)
        sessionListener?.invoke(path)
    }

    // ---------------------------------------------------------------- storage (one JSON object per folder)
    private fun key(): String = "bp:" + Api.folder

    private fun ensureLoaded() {
        val p = prefs ?: return
        if (loadedFolder == Api.folder) return
        lines.clear(); disabled.clear()
        loadedFolder = Api.folder
        try {
            val o = JSONObject(p.getString(key(), "{}") ?: "{}")
            for (file in o.keys()) {
                val entry = o.optJSONObject(file) ?: continue
                val ls = entry.optJSONArray("lines") ?: JSONArray()
                val set = java.util.TreeSet<Int>()
                for (i in 0 until ls.length()) set.add(ls.optInt(i))
                if (set.isEmpty()) continue
                lines[file] = set
                val off = entry.optJSONArray("disabled") ?: JSONArray()
                if (off.length() > 0) disabled[file] = HashSet<Int>().also { for (i in 0 until off.length()) it.add(off.optInt(i)) }
            }
        } catch (e: Exception) { }
    }

    private fun persist() {
        val p = prefs ?: return
        val o = JSONObject()
        for ((file, set) in lines) {
            if (set.isEmpty()) continue
            val entry = JSONObject().put("lines", JSONArray(set.toList()))
            val off = disabled[file]?.filter { it in set }
            if (!off.isNullOrEmpty()) entry.put("disabled", JSONArray(off))
            o.put(file, entry)
        }
        p.edit().putString(key(), o.toString()).apply()
    }
}
