package local.forge

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject

/** Settings as a native form, saved to the server one value at a time; plus the GPU engine card. */
class SettingsActivity : Activity() {
    private class Entry(val key: String, val label: String, val type: String, val hint: String, val options: List<String> = emptyList())

    private val entries = listOf(
        Entry("engine", "Engine", "select", "gpu runs the model on the Intel graphics through the Forge app; ollama runs it on the VM's processor.", listOf("gpu", "ollama")),
        Entry("gpu_model", "GPU engine: model", "model", "The Ollama model whose weights the Forge app runs on the Intel graphics. forge-coder is the tuned Llama 3.1 8B build; qwen3.5:4b needs 3.8 GB free instead of 5.9 GB."),
        Entry("model", "Model in the VM (Ollama)", "model", "Used when the engine is ollama, and as the fallback."),
        Entry("gpu_autostart", "Start the GPU engine by itself", "bool", "When the engine is chosen and not running, the app starts it: when you open Forge, when a message needs it, and after the app was updated. The free-memory check always applies."),
        Entry("gpu_idle_unload_min", "GPU engine: unload after idle minutes", "number", "After this many minutes without a chat the app unloads the engine; the next message starts it again and waits for it. 0 keeps it loaded."),
        Entry("gpu_pool_credit", "GPU engine: count the driver's held memory (percent)", "select", "0 trusts only what Android reports as free. 50 or 80 also count that share of the memory the graphics driver keeps from Forge's earlier GPU processes, with a guard that stops the engine when free memory gets scarce.", listOf("0", "50", "80")),
        Entry("gpu_extra_args", "GPU engine: extra llama-server flags", "text", "Added to the engine's command line. If the engine refuses a flag, the app starts it again without the extras."),
        Entry("vision", "Vision model", "select", "auto describes screenshots with the vision model; off uses text recognition only.", listOf("auto", "off")),
        Entry("vision_extra_args", "Vision model: extra llama-server flags", "text", "Added to the vision server's command line (flash attention off by default)."),
        Entry("thinking", "Thought process", "bool", "The model writes a short thought process before it acts (at most three sentences)."),
        Entry("tool_mode", "Tool calling", "select", "prompt is Forge's own tagged format; native is Ollama's JSON tool API.", listOf("prompt", "native")),
        Entry("auto_approve_commands", "Run commands without asking", "bool", "Commands the agent runs are approved automatically (dangerous ones still ask)."),
        Entry("max_iterations", "Tool steps per message", "number", "How many tool calls one message may make before Forge pauses."),
        Entry("num_ctx", "Context (tokens)", "number", "The model's context window in the VM."),
        Entry("gpu_ctx", "GPU engine context (tokens)", "number", "The context window the GPU engine starts with."),
        Entry("temperature", "Temperature", "number", "0.2 is precise; higher is more varied."),
        Entry("vd_updates", "Virtual device images: updates", "select", "check shows updates daily; install also installs them; off never checks.", listOf("check", "install", "install-move", "off")),
    )

    private lateinit var form: LinearLayout
    private var settings = JSONObject()
    private var models: List<String> = emptyList()
    private val engineStatus = TextView(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 12)
        form = Ui.column(this)
        form.setPadding(pad, pad, pad, pad)
        val root = Ui.scroll(this, form)
        root.setBackgroundColor(Ui.bg)
        setContentView(root)
        title = "Forge settings"
        form.addView(Ui.row(this, Ui.text(this, "Settings", 18f, Ui.fg, bold = true).also { it.layoutParams = Ui.lp(0, Ui.wrap, 1f) }, Ui.button(this, "Close") { finish() }))
        form.addView(Ui.text(this, "Saved on the Forge server as you change them. Forge app " + MainActivity.VERSION + ", native.", 11.5f, Ui.fg3))
        form.addView(engineCard())
        Api.async("settings", { Pair(Api.get("/api/settings"), Api.get("/api/models")) }) { r, e ->
            if (r == null) { form.addView(Ui.text(this, "Could not load the settings: " + (e?.message ?: ""), 12f, Ui.red)); return@async }
            settings = r.first.optJSONObject("effective") ?: JSONObject()
            val arr = r.second.optJSONArray("models")
            val names = ArrayList<String>(); if (arr != null) for (i in 0 until arr.length()) names.add(arr.getJSONObject(i).optString("name"))
            models = names
            for (en in entries) form.addView(render(en))
        }
    }

    private fun save(key: String, value: Any) {
        Api.async("settings-save", { Api.post("/api/settings", JSONObject().put(key, value).put("_scope", "user")) }) { r, e ->
            if (r == null) Ui.toast(this, "Not saved: " + (e?.message ?: ""))
        }
    }

    private fun render(en: Entry): View {
        val box = Ui.column(this)
        box.setPadding(0, Ui.dp(this, 10), 0, 0)
        box.addView(Ui.text(this, en.label, 13.5f, Ui.fg, bold = true))
        val current = settings.opt(en.key)
        when (en.type) {
            "bool" -> {
                val cb = CheckBox(this); cb.text = if (settings.optBoolean(en.key)) "on" else "off"; cb.isChecked = settings.optBoolean(en.key); cb.setTextColor(Ui.fg2)
                cb.setOnCheckedChangeListener { _, on -> cb.text = if (on) "on" else "off"; save(en.key, on) }
                box.addView(cb)
            }
            "select", "model" -> {
                val options = if (en.type == "model") models else en.options
                val sp = Spinner(this)
                sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
                val cur = current?.toString() ?: ""
                val idx = options.indexOf(cur); if (idx >= 0) sp.setSelection(idx)
                var first = true
                sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (first) { first = false; return }
                        val chosen = options.getOrNull(pos) ?: return
                        val value: Any = if (en.key == "gpu_pool_credit") chosen.toInt() else chosen
                        save(en.key, value)
                    }
                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
                box.addView(sp)
            }
            else -> {
                val input = Ui.input(this, "")
                input.setText(current?.toString() ?: "")
                if (en.type == "number") input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                input.setOnFocusChangeListener { _, has ->
                    if (!has) {
                        val raw = input.text.toString().trim()
                        if (raw == (current?.toString() ?: "")) return@setOnFocusChangeListener
                        val value: Any = if (en.type == "number") (raw.toDoubleOrNull()?.let { if (it == Math.floor(it)) it.toInt() else it } ?: raw) else raw
                        save(en.key, value)
                    }
                }
                box.addView(input)
            }
        }
        box.addView(Ui.text(this, en.hint, 11.5f, Ui.fg3))
        return box
    }

    private fun engineCard(): View {
        engineStatus.setTextColor(Ui.fg2); engineStatus.textSize = 12f
        val card = Ui.card(this, Ui.text(this, "GPU engine", 13.5f, Ui.fg, bold = true), engineStatus,
            Ui.row(this, Ui.button(this, "Start engine", primary = true) { EngineService.start(this); refreshEngine() },
                Ui.button(this, "Stop engine") { EngineService.stop(this); refreshEngine() },
                Ui.button(this, "Engine log") { AlertDialog.Builder(this).setTitle("Engine log").setMessage(EngineService.logTail()).setPositiveButton("Close", null).show() },
                Ui.button(this, "Refresh") { refreshEngine() }))
        refreshEngine()
        return card
    }

    private fun refreshEngine() {
        val st = EngineService.status(this)
        engineStatus.text = "State: " + st.optString("state") + (if (st.optString("detail").isNotEmpty()) " (" + st.optString("detail") + ")" else "") +
            "\nModel files on this Googlebook: " + st.optString("models") +
            "\nFree memory reported by Android: " + st.optLong("hostMemAvailableMb") + " MB"
    }
}
