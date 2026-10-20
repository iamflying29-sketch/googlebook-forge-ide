package local.forge

import org.json.JSONObject

/**
 * The effective settings of each folder a window shows (GET /api/settings, the "effective" object), cached for the
 * parts of a window that read them often: Tab completions, their delay, the language servers, the font size, the
 * minimap. Every folder has its own copy, because a folder's .forge/settings.json may override the user settings, and
 * two windows on two folders must each follow their own. The cache of a folder is refreshed when its window loads or
 * comes back to the front, after the Settings window closes and after every change made from here.
 */
object ForgeSettings {
    private val effectiveByFolder = HashMap<String, JSONObject>()
    private val folderByFolder = HashMap<String, JSONObject>()
    private val trustByFolder = HashMap<String, JSONObject>()

    /** What a folder's own settings and hooks ask for that waits until you trust the folder (empty when trusted). */
    fun heldBack(folder: String = Api.currentFolder()): List<String> {
        val a = trustByFolder[folder]?.optJSONArray("held_back") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }
    private val listeners = ArrayList<(String) -> Unit>()

    /** The effective settings of a folder (the folder of the window that asks, by default). */
    fun effective(folder: String = Api.currentFolder()): JSONObject = effectiveByFolder[folder] ?: JSONObject()

    fun bool(key: String, default: Boolean, folder: String = Api.currentFolder()): Boolean { val e = effective(folder); return if (e.has(key)) e.optBoolean(key, default) else default }
    fun int(key: String, default: Int, folder: String = Api.currentFolder()): Int { val e = effective(folder); return if (e.has(key)) e.optInt(key, default) else default }
    fun string(key: String, default: String, folder: String = Api.currentFolder()): String { val e = effective(folder); return if (e.has(key)) e.optString(key, default) else default }

    /** True when the folder's .forge/settings.json sets this key (then a change made here is saved there). */
    fun overriddenInFolder(key: String, folder: String = Api.currentFolder()): Boolean = folderByFolder[folder]?.has(key) == true

    /** Called on the main thread after every refresh, with the folder whose settings changed. Returns the listener, so
     *  a window that closes can take it out again with removeListener. */
    fun onChange(l: (String) -> Unit): (String) -> Unit { listeners.add(l); return l }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    /** Takes in a settings payload of the server (GET or POST /api/settings) for a folder. */
    private fun apply(folder: String, r: JSONObject) {
        effectiveByFolder[folder] = r.optJSONObject("effective") ?: JSONObject()
        folderByFolder[folder] = r.optJSONObject("folder") ?: JSONObject()
        trustByFolder[folder] = r.optJSONObject("trust") ?: JSONObject()
        // Language servers turned off for a folder stop at once, so they no longer hold memory in the VM.
        if (!bool("lsp", true, folder)) Lsp.shutdownFolder(folder)
        for (l in listeners.toList()) try { l(folder) } catch (e: Exception) { android.util.Log.w("ForgeSettings", "listener", e) }
    }

    /** Trusts a folder (or takes the trust back): its own settings and hooks may then loosen the safety settings. */
    fun trust(folder: String, trusted: Boolean, done: ((Boolean, String) -> Unit)? = null) {
        Api.withFolder(folder) {
            Api.async("trust", { Api.post("/api/trust", JSONObject().put("trusted", trusted)) }) { r, e ->
                if (r != null) apply(folder, r)
                done?.invoke(r != null, e?.message ?: "")
            }
        }
    }

    fun refresh(folder: String = Api.currentFolder(), done: (() -> Unit)? = null) {
        Api.withFolder(folder) {
            Api.async("settings", { Api.get("/api/settings") }) { r, _ ->
                if (r != null) apply(folder, r)
                done?.invoke()
            }
        }
    }

    /**
     * Saves one setting and refreshes the cache. It is saved where it takes effect: in the folder's settings when the
     * folder overrides this key (a user setting would change nothing there), otherwise in the user settings.
     */
    fun set(key: String, value: Any, folder: String = Api.currentFolder(), done: ((Boolean, String) -> Unit)? = null) {
        val scope = if (overriddenInFolder(key, folder)) "folder" else "user"
        Api.withFolder(folder) {
            Api.async("setting", { Api.post("/api/settings", JSONObject().put(key, value).put("_scope", scope)) }) { r, e ->
                if (r != null) apply(folder, r)
                done?.invoke(r != null, e?.message ?: "")
            }
        }
    }
}
