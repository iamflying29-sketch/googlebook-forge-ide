package local.forge

import org.json.JSONObject

/**
 * The effective settings of the window's folder (GET /api/settings, the "effective" object), cached for the parts
 * of the window that read them often: Tab completions, their delay, the language servers, the font size. The cache
 * is refreshed when the window loads, after the Settings window closes and after every change made from here.
 */
object ForgeSettings {
    @Volatile var effective: JSONObject = JSONObject(); private set
    private val listeners = ArrayList<() -> Unit>()

    fun bool(key: String, default: Boolean): Boolean = if (effective.has(key)) effective.optBoolean(key, default) else default
    fun int(key: String, default: Int): Int = if (effective.has(key)) effective.optInt(key, default) else default
    fun string(key: String, default: String): String = if (effective.has(key)) effective.optString(key, default) else default

    /** Called on the main thread after every refresh. */
    fun onChange(l: () -> Unit) { listeners.add(l) }

    fun refresh(done: (() -> Unit)? = null) {
        Api.async("settings", { Api.get("/api/settings") }) { r, _ ->
            if (r != null) {
                effective = r.optJSONObject("effective") ?: JSONObject()
                Lsp.enabled = bool("lsp", true)
                for (l in listeners.toList()) l()
            }
            done?.invoke()
        }
    }

    /** Saves one user setting and refreshes the cache. */
    fun set(key: String, value: Any, done: ((Boolean, String) -> Unit)? = null) {
        Api.async("setting", { Api.post("/api/settings", JSONObject().put(key, value).put("_scope", "user")) }) { r, e ->
            if (r != null) { effective.put(key, value); refresh() }
            done?.invoke(r != null, e?.message ?: "")
        }
    }
}
