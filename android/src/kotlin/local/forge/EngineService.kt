package local.forge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Forge GPU engine: runs llama.cpp's llama-server (Vulkan backend, Intel graphics) on the Android side.
 *
 * Lifecycle: start, read the configuration from the Forge server in the VM (localhost:8765), download the model
 * weights from it once (kept in this app's private storage), check free host memory, launch the server bound to the
 * VM-facing address with an API key, watch it. Stop kills it. Everything is logged in memory and shown in the
 * settings screen's engine card.
 */
class EngineService : Service() {
    companion object {
        private const val TAG = "ForgeEngine"
        private const val CHANNEL = "forge_engine"
        private const val NOTIF_ID = 1001
        private const val FORGE = "http://localhost:8765"
        const val ACTION_START = "local.forge.ENGINE_START"
        const val ACTION_STOP = "local.forge.ENGINE_STOP"
        /** Keeps the app a foreground process (a notification, no engine) while the vision model runs without the
         *  engine: Android froze the app ten seconds after a pause once (2026-10-19), which stopped the control loop
         *  and left the engine dead until the window was opened again. */
        const val ACTION_HOLD = "local.forge.HOLD"
        @Volatile var holding = false

        @Volatile private var instance: EngineService? = null
        private val logLines = ArrayDeque<String>()
        @Volatile private var state = "stopped"      // stopped | downloading | starting | running | paused | error
        @Volatile private var detail = ""
        @Volatile private var downloaded = 0L
        @Volatile private var downloadTotal = 0L
        /** True while the engine is paused so the vision model can use the GPU (one model on the GPU at a time). */
        @Volatile var pausing = false
        @Volatile var pausedForVision = false

        fun stateName(): String = state

        // ------------------------------------------------------------ public status
        fun status(ctx: Context): JSONObject {
            val o = JSONObject()
            try {
                o.put("state", state)
                o.put("detail", detail)
                o.put("downloaded", downloaded)
                o.put("downloadTotal", downloadTotal)
                val names = ArrayList<String>()
                File(ctx.filesDir, "models").listFiles()?.forEach { f -> names.add(f.name + " (" + (f.length() / (1024 * 1024)) + " MB)") }
                o.put("models", names.toString())
                o.put("hostMemAvailableMb", memAvailableMb())
                o.put("binary", File(ctx.applicationInfo.nativeLibraryDir, "libllama-server.so").exists())
                o.put("vision", VisionControl.state())
                o.put("visionTier", VisionControl.tier())
                o.put("visionDetail", VisionControl.detail())
            } catch (e: Exception) { }
            return o
        }

        /** The last n log lines (all of them when n is 0). */
        @JvmOverloads fun logTail(n: Int = 0): String {
            synchronized(logLines) {
                val sb = StringBuilder()
                val skip = if (n > 0) maxOf(0, logLines.size - n) else 0
                var i = 0
                for (l in logLines) { if (i++ >= skip) sb.append(l).append('\n') }
                return sb.toString()
            }
        }

        fun log(s: String) {
            Log.i(TAG, s)
            synchronized(logLines) {
                logLines.addLast(s)
                while (logLines.size > 400) logLines.removeFirst()
            }
        }

        fun memAvailableMb(): Long {
            try {
                BufferedReader(FileReader("/proc/meminfo")).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("MemAvailable:")) return line.replace(Regex("[^0-9]"), "").toLong() / 1024
                    }
                }
            } catch (e: Exception) { }
            return -1
        }

        /** The memory guard: while a Forge GPU process runs, visible free memory is read every two seconds; when it
         *  falls under GpuBudget.GUARD_FLOOR_MB or Android reports low memory, the process is stopped at once. The
         *  Linux VM (the Terminal app) must never be the one that Android reclaims. A stop here also withdraws the
         *  driver pool credit for 15 minutes. */
        fun memoryGuard(ctx: Context, p: Process, who: String) {
            val t = Thread({
                var low = 0
                while (p.isAlive) {
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { return@Thread }
                    val avail = memAvailableMb()
                    val lowFlag = GpuBudget.lowMemory(ctx)
                    if ((avail >= 0 && avail < GpuBudget.GUARD_FLOOR_MB) || lowFlag) low++ else low = 0
                    if (low >= 2) {
                        log("memory guard: stopping the $who (visible free memory $avail MB" + (if (lowFlag) ", Android reports low memory" else "") + ")")
                        GpuBudget.guardStopped(ctx)
                        if (who != "engine") VisionControl.guardStopped("stopped by the memory guard: only $avail MB free on the Googlebook" + (if (lowFlag) " and Android reports low memory" else ""))
                        if (who == "engine" && instance != null) detail = "Stopped by the memory guard: only $avail MB free on the Googlebook. Close other apps; Forge starts it again when there is room."
                        p.destroy()
                        try { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() } catch (e: InterruptedException) { }
                        return@Thread
                    }
                }
            }, "forge-memory-guard-$who")
            t.isDaemon = true
            t.start()
        }

        /** Android's Process keeps its pid in a method or a private field, depending on the version. */
        fun pidOf(p: Process): Int {
            try { return (p.javaClass.getMethod("pid").invoke(p) as Long).toInt() } catch (e: Exception) {
                try { val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; return f.getInt(p) } catch (e2: Exception) { return -1 }
            }
        }

        private fun httpPost(url: String, body: String, timeout: Int): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeout; c.readTimeout = timeout
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.write(body.toByteArray())
            c.responseCode
            try { c.inputStream.use { return String(it.readBytes()) } } finally { c.disconnect() }
        }

        private fun httpGet(url: String, timeout: Int): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeout; c.readTimeout = timeout
            val code = c.responseCode
            if (code != 200) throw Exception("GET $url -> HTTP $code")
            try { c.inputStream.use { return String(it.readBytes()) } } finally { c.disconnect() }
        }

        // ------------------------------------------------------------ taking turns with the vision model
        fun engineRunning(): Boolean { val s = instance; return s != null && s.proc != null }

        /** Stops the engine process (keeping the service) so the vision model can use the GPU. True if it was running. */
        fun pauseForVision(): Boolean {
            val s = instance ?: return false
            val p = s.proc ?: return false
            pausing = true
            log("pausing the engine for the vision model")
            p.destroy()
            try { if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) } catch (e: InterruptedException) { }
            var i = 0
            while (i < 50 && !pausedForVision) { try { Thread.sleep(100) } catch (e: InterruptedException) { }; i++ }
            return true
        }

        /** A foreground hold for the vision model when the engine is not running or paused; true when one was taken. */
        fun holdForeground(ctx: Context, text: String): Boolean {
            val s = instance
            val st = state
            if (s != null && (s.proc != null || st == "paused" || st == "starting" || st == "downloading" || st == "running")) return false
            holding = true
            try {
                ctx.startForegroundService(Intent(ctx, EngineService::class.java).setAction(ACTION_HOLD).putExtra("text", text))
            } catch (e: Exception) {
                holding = false
                log("foreground hold not taken: $e")
                return false
            }
            return true
        }

        /** Ends the hold; the service goes away unless the engine runs, starts or is paused meanwhile. */
        fun releaseHold(ctx: Context) {
            holding = false
            val s = instance ?: return
            val st = state
            val w = s.worker
            if (s.proc == null && st != "paused" && st != "starting" && st != "downloading" && (w == null || !w.isAlive)) {
                s.stopForeground(STOP_FOREGROUND_DETACH)
                s.stopSelf()
            }
        }

        /** Starts the engine again after the vision model is done (through the normal free-memory check). */
        fun resumeAfterVision() {
            val s = instance ?: return
            if (!pausedForVision) return
            pausedForVision = false
            log("resuming the engine after the vision model")
            s.stopping = false
            s.worker = Thread({ s.run() }, "forge-engine").also { it.start() }
        }

        /** The IDE server asked for the engine (it is the chosen engine and not running): start it unless it already
         *  runs, starts, downloads or is paused for the vision model. The usual free-memory check applies. */
        fun startIfIdle(ctx: Context) {
            val st = state
            if (st == "running" || st == "starting" || st == "downloading" || st == "paused") return
            log("the IDE asked for the engine; starting it")
            try { start(ctx) } catch (e: Exception) { log("could not start the engine by itself: $e") }
        }

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, EngineService::class.java).setAction(ACTION_START)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, EngineService::class.java).setAction(ACTION_STOP)) }
    }

    @Volatile internal var proc: Process? = null
    @Volatile internal var worker: Thread? = null
    @Volatile internal var stopping = false
    /** Extra llama-server flags from the IDE's settings were used for the current launch; if the engine exits within
     *  30 seconds with them, it is started once more without them (a refused flag must never leave the engine dead). */
    @Volatile private var extraUsed = false
    @Volatile private var plainRetry = false
    @Volatile private var relaunching = false
    @Volatile private var launchedAt = 0L

    // ------------------------------------------------------------ service plumbing
    override fun onCreate() {
        super.onCreate()
        instance = this
        VisionControl.ensure(this)
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Forge GPU engine", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Runs the AI model on the Intel graphics while Forge is in use."
        nm.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (ACTION_STOP == action) {
            stopEngine("stopped by user")
            stopSelf()
            return START_NOT_STICKY
        }
        if (ACTION_HOLD == action) {
            val text = intent?.getStringExtra("text")
            startForeground(NOTIF_ID, buildNotification(text ?: "The vision model is reading an image"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            return START_NOT_STICKY
        }
        holding = false
        startForeground(NOTIF_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val w = worker
        if (w == null || !w.isAlive) {
            stopping = false
            worker = Thread({ run() }, "forge-engine").also { it.start() }
        }
        return START_NOT_STICKY   // never auto-restart a 5 GB process behind the user's back
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEngine("service destroyed")
        instance = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val stop = Intent(this, EngineService::class.java).setAction(ACTION_STOP)
        val pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = Intent(this, MainActivity::class.java)
        val pOpen = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Forge GPU engine")
            .setContentText(text)
            .setContentIntent(pOpen)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop engine", pStop).build())
            .build()
    }

    private fun notifyText(text: String) {
        detail = text
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text)) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------ the work
    internal fun run() {
        try {
            state = "starting"
            notifyText("Reading configuration from Forge…")
            val cfg = JSONObject(httpGet("$FORGE/api/engine/config", 15000))
            val model = cfg.optJSONObject("model") ?: throw Exception("Forge could not locate the model weights in Ollama's store.")
            val modelsDir = File(filesDir, "models")
            modelsDir.mkdirs()
            val modelFile = File(modelsDir, model.getString("filename"))
            val size = model.getLong("size")
            if (!modelFile.exists() || modelFile.length() != size) {
                state = "downloading"
                download(FORGE + model.getString("download"), modelFile, size)
            }
            val bin = File(applicationInfo.nativeLibraryDir, "libllama-server.so")
            if (!bin.exists()) throw Exception("Engine binary missing from this build of the app.")
            val needMb = size / (1024 * 1024) + 1200   // weights + KV cache + compute buffers + margin
            GpuBudget.poolCreditPercent = cfg.optLong("pool_credit_percent", 0)
            // The free-memory check (GpuBudget), never bypassed. Ask the VM to hand memory back while waiting.
            var why = GpuBudget.check(this, needMb)
            var attempt = 0
            while (why != null && attempt < 120 && !stopping) {
                if (attempt % 12 == 0) { try { httpPost("$FORGE/api/engine/prepare", "{}", 90000) } catch (e: Exception) { log("prepare: " + e.message) } }
                state = "starting"
                notifyText("Waiting for free memory ($why). Closing other apps helps.")
                Thread.sleep(5000)
                why = GpuBudget.check(this, needMb)
                attempt++
            }
            if (why != null) throw Exception("Not enough free memory on the Googlebook for the engine ($why). Close other apps and try again.")
            log("memory check passed: " + GpuBudget.describe(this) + ", $needMb MB needed")
            state = "starting"
            notifyText("Loading the model on the GPU…")
            val ctx = cfg.optInt("ctx", 8192)
            val ngl = cfg.optInt("gpu_layers", 99)
            val host = cfg.optString("host_ip", "127.0.0.1")
            val port = cfg.optInt("port", 8081)
            val key = cfg.optString("api_key", "")
            val cmd = ArrayList<String>()
            cmd.add(bin.absolutePath)
            cmd.add("-m"); cmd.add(modelFile.absolutePath)
            cmd.add("--host"); cmd.add(host)
            cmd.add("--port"); cmd.add(port.toString())
            cmd.add("-ngl"); cmd.add(ngl.toString())
            cmd.add("-c"); cmd.add(ctx.toString())
            cmd.add("-np"); cmd.add("1")
            cmd.add("-t"); cmd.add("4")
            cmd.add("-fa"); cmd.add("on")
            cmd.add("-ctk"); cmd.add("q8_0")
            cmd.add("-ctv"); cmd.add("q8_0")
            cmd.add("--no-webui")
            cmd.add("--metrics")
            // The prompt cache survives a changed byte in the middle of the prompt (chunks after it are shifted into
            // place) and the whole cached prompt can be saved to disk before a pause and restored after it.
            cmd.add("--cache-reuse"); cmd.add("256")
            val slots = File(cacheDir, "slots")
            slots.mkdirs()
            cmd.add("--slot-save-path"); cmd.add(slots.absolutePath)
            extraUsed = false
            if (!plainRetry) for (extra in cfg.optString("extra_args", "").trim().split(Regex("\\s+"))) if (extra.isNotEmpty()) { cmd.add(extra); extraUsed = true }
            if (key.isNotEmpty()) { cmd.add("--api-key"); cmd.add(key) }
            log("launch: " + cmd.joinToString(" ").let { if (key.isNotEmpty()) it.replace(key, "<key>") else it })
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = filesDir.absolutePath
            pb.environment()["TMPDIR"] = cacheDir.absolutePath
            pb.environment()["LLAMA_CACHE"] = cacheDir.absolutePath
            GpuBudget.beforeStart(this, "engine")
            relaunching = false
            launchedAt = System.currentTimeMillis()
            val p = pb.start()
            proc = p
            // Make the engine the first thing the system reclaims under memory pressure, never the VM.
            val epid = pidOf(p)
            try { FileOutputStream("/proc/$epid/oom_score_adj").use { it.write("900".toByteArray()) }; log("engine pid $epid marked oom_score_adj 900") } catch (e: Exception) { log("oom_score_adj not set for pid $epid: " + e.message) }
            memoryGuard(this, p, "engine")
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    log(line)
                    if (line.contains("server is listening") || line.contains("all slots are idle") || line.contains("starting the main loop")) {
                        if (state != "running") { state = "running"; notifyText("Running on the GPU · $host:$port"); postStats("running") }
                    }
                }
            }
            val code = p.waitFor()
            GpuBudget.afterStop(this, "engine")
            if (pausing) {
                pausing = false
                pausedForVision = true
                state = "paused"
                detail = "Paused while the vision model reads an image"
                notifyText(detail)
                postStats("paused")
            } else if (!stopping && code != 0 && extraUsed && !plainRetry && System.currentTimeMillis() - launchedAt < 30000) {
                plainRetry = true
                relaunching = true
                log("the engine exited with code $code within 30 s of a launch with the extra flags; starting it again without them")
                state = "starting"
                worker = Thread({ run() }, "forge-engine").also { it.start() }
            } else if (!stopping) {
                state = "error"
                detail = "Engine exited with code $code. See the engine log."
                log(detail)
                notifyText(detail)
                postStats("exited")
            } else {
                state = "stopped"
            }
        } catch (e: Exception) {
            if (pausing) {
                // Stopping the engine for the vision model closes its output while this thread reads it; that is the
                // pause, not an error. The engine process is gone, so the budget must know it stopped: otherwise the
                // vision model's check sees "engine running" and gives no credit for the memory the pause just freed.
                GpuBudget.afterStop(this, "engine")
                pausing = false
                pausedForVision = true
                state = "paused"
                detail = "Paused while the vision model reads an image"
                notifyText(detail)
                postStats("paused")
            } else if (stopping) {
                state = "stopped"   // the user or the IDE stopped it: closing its output interrupts this reader, no error
            } else {
                state = "error"
                detail = e.message ?: e.toString()
                log("ERROR: $detail")
                notifyText(detail)
            }
        } finally {
            if (!relaunching) proc = null
            if (state != "paused" && !relaunching && !holding) {
                stopForeground(STOP_FOREGROUND_DETACH)
                if (state != "error") stopSelf()
            }
        }
    }

    private fun postStats(what: String) {
        Thread({
            try {
                val c = URL("$FORGE/api/engine/stats").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 3000; c.readTimeout = 3000
                c.setRequestProperty("Content-Type", "application/json")
                val o = JSONObject()
                o.put("event", what); o.put("state", state); o.put("detail", detail); o.put("log", logTail(40))
                o.put("host_mem_mb", memAvailableMb()); o.put("budget", GpuBudget.describe(this))
                c.outputStream.write(o.toString().toByteArray())
                c.responseCode; c.disconnect()
            } catch (e: Exception) { }
        }, "forge-engine-stats").start()
    }

    private fun download(url: String, dest: File, size: Long) {
        val tmp = File(dest.absolutePath + ".part")
        var have = if (tmp.exists()) tmp.length() else 0L
        if (have > size) { tmp.delete(); have = 0 }   // a corrupt partial file from an interrupted download
        downloadTotal = size; downloaded = have
        notifyText("Downloading the model (" + (size / (1024 * 1024)) + " MB)…")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 120000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code != 200 && code != 206) throw Exception("model download failed: HTTP $code")
        val append = code == 206
        if (!append) { downloaded = 0; have = 0 }
        c.inputStream.use { inp ->
            RandomAccessFile(tmp, "rw").use { out ->
                if (append) out.seek(have) else out.setLength(0)
                val buf = ByteArray(1 shl 20)
                var last = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    if (stopping) throw Exception("download cancelled")
                    out.write(buf, 0, n)
                    downloaded += n
                    if (downloaded - last > 200L * 1024 * 1024) { last = downloaded; notifyText(String.format(Locale.US, "Downloading the model… %d / %d MB", downloaded shr 20, size shr 20)) }
                }
            }
        }
        if (tmp.length() != size) throw Exception("model download incomplete: " + tmp.length() + " of $size bytes")
        if (!tmp.renameTo(dest)) throw Exception("could not move the downloaded model into place")
        log("model downloaded: " + dest.name + " $size bytes")
    }

    private fun stopEngine(why: String) {
        stopping = true
        val p = proc
        if (p != null) {
            log("stopping engine: $why")
            p.destroy()
            try { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() } catch (e: InterruptedException) { }
        }
        state = "stopped"
        detail = why
        postStats("stopped")
    }
}
