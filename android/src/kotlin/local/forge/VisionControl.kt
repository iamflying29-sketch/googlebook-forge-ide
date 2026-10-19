package local.forge

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Runs the vision model (Qwen3-VL in llama.cpp, on the Intel GPU) on demand for the Forge IDE.
 *
 * A background thread long-polls the IDE server (/api/engine/control). When the IDE needs eyes, it picks the best
 * model that fits in free memory, downloads its files from the IDE once, starts it, and stops it again when the IDE
 * says it has been idle. No port is opened for control; the vision server itself listens only on the address the
 * Linux VM uses, with the same key as the main engine.
 */
object VisionControl {
    private const val TAG = "ForgeVision"
    private const val FORGE = "http://localhost:8765"
    private var control: Thread? = null
    @Volatile private var proc: Process? = null
    @Volatile private var vstate = "stopped"   // stopped | downloading | starting | running | error
    @Volatile private var vtier = ""
    @Volatile private var vdetail = ""
    @Volatile private var launching = false

    @Synchronized fun ensure(ctx: Context) {
        val c = control
        if (c != null && c.isAlive) return
        val app = ctx.applicationContext
        control = Thread({ loop(app) }, "forge-vision-control").also { it.isDaemon = true; it.start() }
    }

    fun state(): String = vstate
    fun tier(): String = vtier
    fun detail(): String = vdetail

    private fun loop(ctx: Context) {
        while (true) {
            try {
                val q = "wait=20&vstate=" + enc(vstate) + "&vtier=" + enc(vtier) + "&vdetail=" + enc(vdetail) + "&host_mem_mb=" + EngineService.memAvailableMb()
                val r = JSONObject(get("$FORGE/api/engine/control?$q", 35000))
                val want = r.optBoolean("want", false)
                GpuBudget.heartbeat(ctx)
                if (r.optBoolean("engine_start", false) && !want) EngineService.startIfIdle(ctx)
                if (r.optBoolean("engine_stop", false) && !want && proc == null) { log("the IDE says the engine has been idle; unloading it"); EngineService.stop(ctx) }
                if (want && proc == null && !launching) {
                    launching = true
                    Thread({ try { start(ctx, r) } finally { launching = false } }, "forge-vision-start").start()
                } else if (!want && proc != null) {
                    stop("idle")
                }
            } catch (e: Exception) {
                try { Thread.sleep(5000) } catch (ie: InterruptedException) { }
            }
        }
    }

    private fun start(ctx: Context, cfg: JSONObject) { startAttempt(ctx, cfg, 0, false) }

    /** One launch attempt: skipTiers tiers are passed over, plain leaves out the extra flags. A server that exits
     *  within 30 seconds of its launch is tried once more without the extra flags, then with the next tier. */
    private fun startAttempt(ctx: Context, cfg: JSONObject, skipTiers: Int, plain: Boolean) {
        var retrying = false
        var held = false
        try {
            val tiers = cfg.optJSONArray("tiers")
            if (tiers == null || tiers.length() == 0) { fail("no vision model files on the IDE server"); return }
            // One model on the GPU at a time: the engine pauses while the vision model looks.
            if (EngineService.engineRunning()) {
                vstate = "starting"; vdetail = "pausing the engine on the GPU"; report()
                EngineService.pauseForVision()
            }
            // Without the engine (or its pause) the app has no foreground service; take one so Android cannot freeze
            // the app while the vision model works.
            held = EngineService.holdForeground(ctx, "The vision model is reading an image")
            var pick: JSONObject? = null
            var why: String? = null
            GpuBudget.poolCreditPercent = cfg.optLong("pool_credit_percent", 0)
            for (i in skipTiers until tiers.length()) {
                val t = tiers.getJSONObject(i)
                why = GpuBudget.check(ctx, t.optLong("need_mb", 3000))
                log("tier " + t.optString("name") + ": " + (why ?: "fits") + " (" + GpuBudget.describe(ctx) + ")")
                if (why == null) { pick = t; break }
            }
            if (pick == null) {
                fail("not enough free memory for the vision model ($why)")
                EngineService.resumeAfterVision()
                return
            }
            vtier = pick.optString("name")
            val dir = File(ctx.filesDir, "models/vision")
            dir.mkdirs()
            val model = File(dir, pick.getString("model"))
            val mmproj = File(dir, pick.getString("mmproj"))
            val fileUrl = FORGE + cfg.optString("file_url", "/api/engine/vision_file?name=")
            if (model.length() != pick.getLong("model_size")) download(fileUrl + enc(model.name), model, pick.getLong("model_size"))
            if (mmproj.length() != pick.getLong("mmproj_size")) download(fileUrl + enc(mmproj.name), mmproj, pick.getLong("mmproj_size"))
            val bin = File(ctx.applicationInfo.nativeLibraryDir, "libllama-server.so")
            val cmd = ArrayList<String>()
            cmd.add(bin.absolutePath)
            cmd.add("-m"); cmd.add(model.absolutePath)
            cmd.add("--mmproj"); cmd.add(mmproj.absolutePath)
            cmd.add("--host"); cmd.add(cfg.optString("host_ip", "127.0.0.1"))
            cmd.add("--port"); cmd.add(cfg.optInt("port", 8082).toString())
            cmd.add("-ngl"); cmd.add("99")
            cmd.add("-c"); cmd.add(pick.optInt("ctx", 4096).toString())
            cmd.add("-np"); cmd.add("1")
            cmd.add("-t"); cmd.add("4")
            cmd.add("-ctk"); cmd.add("q8_0")
            cmd.add("-ctv"); cmd.add("q8_0")
            cmd.add("--image-max-tokens"); cmd.add(pick.optInt("image_max_tokens", 1024).toString())
            cmd.add("--no-webui")
            // Flags from the IDE's "Vision model: extra llama-server flags" setting (flash attention off by default).
            var usedExtras = false
            if (!plain) for (extra in cfg.optString("vision_extra_args", "-fa off").trim().split(Regex("\\s+"))) if (extra.isNotEmpty()) { cmd.add(extra); usedExtras = true }
            val key = cfg.optString("api_key", "")
            if (key.isNotEmpty()) { cmd.add("--api-key"); cmd.add(key) }
            vstate = "starting"; vdetail = "loading $vtier on the Intel GPU"
            report()
            log("launch $vtier: " + cmd.joinToString(" ").let { if (key.isNotEmpty()) it.replace(key, "<key>") else it })
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = ctx.filesDir.absolutePath
            pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
            GpuBudget.beforeStart(ctx, "vision")
            val launchedAt = System.currentTimeMillis()
            val p = pb.start()
            proc = p
            // The vision server is reclaimed before the main engine (900) and long before the Linux VM (0).
            val pid = EngineService.pidOf(p)
            try { FileOutputStream("/proc/$pid/oom_score_adj").use { it.write("950".toByteArray()) } } catch (e: Exception) { log("oom_score_adj not set: " + e.message) }
            EngineService.memoryGuard(ctx, p, "vision server")
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.contains("server is listening") || line.contains("all slots are idle")) {
                        if (vstate != "running") { vstate = "running"; vdetail = "$vtier on the Intel GPU"; report() }
                    }
                    if (line.contains("error") || line.contains("ggml_vulkan: 0") || line.contains("listening")) log(line)
                }
            }
            val code = p.waitFor()
            GpuBudget.afterStop(ctx, "vision")
            if (proc === p) proc = null
            val early = code != 0 && vstate != "running" && vstate != "stopped" && System.currentTimeMillis() - launchedAt < 30000
            if (early && usedExtras) {
                log("the vision server exited with code $code right after a launch with the extra flags; starting it again without them")
                retrying = true
                startAttempt(ctx, cfg, skipTiers, true)
                return
            }
            if (early && skipTiers + 1 < tiers.length()) {
                log("the vision server exited with code $code right after its launch; trying the next tier")
                retrying = true
                startAttempt(ctx, cfg, skipTiers + 1, false)
                return
            }
            if (vstate != "stopped") { vstate = if (code == 0) "stopped" else "error"; vdetail = "vision server exited with code $code"; report() }
        } catch (e: Exception) {
            // Reading the server's output is interrupted when we stop it on purpose: that is not an error.
            if (vstate != "stopped") fail(e.message ?: e.toString())
            else { GpuBudget.afterStop(ctx, "vision"); proc = null }
        } finally {
            if (!retrying) {
                EngineService.resumeAfterVision()   // the engine gets the GPU back
                if (held) EngineService.releaseHold(ctx)
            }
        }
    }

    @Synchronized fun stop(why: String) {
        val p = proc
        vstate = "stopped"; vdetail = why
        if (p != null) {
            log("stopping vision server: $why")
            p.destroy()
            try { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() } catch (e: InterruptedException) { }
        }
        proc = null
        report()
    }

    /** The memory guard stopped the vision server: say so, instead of a bare "exited with code". */
    fun guardStopped(why: String) { vstate = "error"; vdetail = why; log(why); report() }

    private fun fail(why: String) {
        vstate = "error"; vdetail = why
        log("ERROR: $why")
        report()
    }

    private fun log(s: String) { Log.i(TAG, s); EngineService.log("[vision] $s") }

    private fun report() {
        Thread({
            try {
                val o = JSONObject()
                o.put("vstate", vstate); o.put("vtier", vtier); o.put("vdetail", vdetail)
                o.put("host_mem_mb", EngineService.memAvailableMb())
                o.put("log", EngineService.logTail(60))   // the IDE keeps it for diagnosis (/api/engine/applog)
                val c = URL("$FORGE/api/engine/vision_status").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 3000; c.readTimeout = 5000
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.write(o.toString().toByteArray())
                c.responseCode; c.disconnect()
            } catch (e: Exception) { }
        }, "forge-vision-report").start()
    }

    private fun download(url: String, dest: File, size: Long) {
        vstate = "downloading"; vdetail = "downloading " + dest.name + " (" + (size shr 20) + " MB, once)"
        report()
        val tmp = File(dest.absolutePath + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 120000
        if (c.responseCode != 200) throw Exception("download of " + dest.name + " failed: HTTP " + c.responseCode)
        c.inputStream.use { inp ->
            RandomAccessFile(tmp, "rw").use { out ->
                out.setLength(0)
                val buf = ByteArray(1 shl 20)
                while (true) { val n = inp.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
            }
        }
        if (tmp.length() != size) throw Exception("download of " + dest.name + " incomplete")
        if (!tmp.renameTo(dest)) throw Exception("could not move " + dest.name + " into place")
        log("downloaded " + dest.name)
    }

    private fun enc(s: String?): String = try { URLEncoder.encode(s ?: "", "UTF-8") } catch (e: Exception) { "" }

    private fun get(url: String, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = timeout
        val code = c.responseCode
        if (code != 200) throw Exception("HTTP $code")
        try { c.inputStream.use { return String(it.readBytes()) } } finally { c.disconnect() }
    }
}
