package local.forge;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the vision model (Qwen3-VL in llama.cpp, on the Intel GPU) on demand for the Forge IDE.
 *
 * A background thread long-polls the IDE server (/api/engine/control). When the IDE needs eyes, it picks the best
 * model that fits in free memory (4B, else 2B), downloads its files from the IDE once, starts it, and stops it again
 * when the IDE says it has been idle. No port is opened for control; the vision server itself listens only on the
 * address the Linux VM uses, with the same key as the main engine.
 */
public final class VisionControl {
    private static final String TAG = "ForgeVision";
    private static final String FORGE = "http://localhost:8765";
    private static Thread control;
    private static volatile Process proc;
    private static volatile String vstate = "stopped";   // stopped | downloading | starting | running | error
    private static volatile String vtier = "";
    private static volatile String vdetail = "";
    private static volatile boolean launching = false;

    private VisionControl() { }

    public static synchronized void ensure(Context ctx) {
        if (control != null && control.isAlive()) return;
        final Context app = ctx.getApplicationContext();
        control = new Thread(() -> loop(app), "forge-vision-control");
        control.setDaemon(true);
        control.start();
    }

    public static String state() { return vstate; }
    public static String tier() { return vtier; }
    public static String detail() { return vdetail; }

    private static void loop(Context ctx) {
        while (true) {
            try {
                String q = "wait=20&vstate=" + enc(vstate) + "&vtier=" + enc(vtier) + "&vdetail=" + enc(vdetail)
                        + "&host_mem_mb=" + EngineService.memAvailableMb();
                JSONObject r = new JSONObject(get(FORGE + "/api/engine/control?" + q, 35000));
                boolean want = r.optBoolean("want", false);
                GpuBudget.heartbeat(ctx);
                if (r.optBoolean("engine_start", false) && !want) EngineService.startIfIdle(ctx);
                if (r.optBoolean("engine_stop", false) && !want && proc == null) { log("the IDE says the engine has been idle; unloading it"); EngineService.stop(ctx); }
                if (want && proc == null && !launching) {
                    launching = true;
                    new Thread(() -> { try { start(ctx, r); } finally { launching = false; } }, "forge-vision-start").start();
                } else if (!want && proc != null) {
                    stop("idle");
                }
            } catch (Exception e) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private static void start(Context ctx, JSONObject cfg) { startAttempt(ctx, cfg, 0, false); }

    /** One launch attempt: skipTiers tiers are passed over, plain leaves out the extra flags. A server that exits
     *  within 30 seconds of its launch is tried once more without the extra flags, then with the next tier. */
    private static void startAttempt(Context ctx, JSONObject cfg, int skipTiers, boolean plain) {
        boolean retrying = false, held = false;
        try {
            JSONArray tiers = cfg.optJSONArray("tiers");
            if (tiers == null || tiers.length() == 0) { fail("no vision model files on the IDE server"); return; }
            // One model on the GPU at a time: Llama's engine pauses while the vision model looks.
            if (EngineService.engineRunning()) {
                vstate = "starting"; vdetail = "pausing Llama on the GPU"; report();
                EngineService.pauseForVision();
            }
            // Without the engine (or its pause) the app has no foreground service; take one so Android cannot freeze
            // the app while the vision model works.
            held = EngineService.holdForeground(ctx, "The vision model is reading an image");
            JSONObject pick = null;
            String why = null;
            GpuBudget.poolCreditPercent = cfg.optLong("pool_credit_percent", 0);
            for (int i = skipTiers; i < tiers.length(); i++) {
                JSONObject t = tiers.getJSONObject(i);
                why = GpuBudget.check(ctx, t.optLong("need_mb", 3000));
                log("tier " + t.optString("name") + ": " + (why == null ? "fits" : why) + " (" + GpuBudget.describe(ctx) + ")");
                if (why == null) { pick = t; break; }
            }
            if (pick == null) {
                fail("not enough free memory for the vision model (" + why + ")");
                EngineService.resumeAfterVision();
                return;
            }
            vtier = pick.optString("name");
            File dir = new File(ctx.getFilesDir(), "models/vision");
            dir.mkdirs();
            File model = new File(dir, pick.getString("model"));
            File mmproj = new File(dir, pick.getString("mmproj"));
            String fileUrl = FORGE + cfg.optString("file_url", "/api/engine/vision_file?name=");
            if (model.length() != pick.getLong("model_size")) download(fileUrl + enc(model.getName()), model, pick.getLong("model_size"));
            if (mmproj.length() != pick.getLong("mmproj_size")) download(fileUrl + enc(mmproj.getName()), mmproj, pick.getLong("mmproj_size"));
            File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libllama-server.so");
            List<String> cmd = new ArrayList<>();
            cmd.add(bin.getAbsolutePath());
            cmd.add("-m"); cmd.add(model.getAbsolutePath());
            cmd.add("--mmproj"); cmd.add(mmproj.getAbsolutePath());
            cmd.add("--host"); cmd.add(cfg.optString("host_ip", "127.0.0.1"));
            cmd.add("--port"); cmd.add(String.valueOf(cfg.optInt("port", 8082)));
            cmd.add("-ngl"); cmd.add("99");
            cmd.add("-c"); cmd.add(String.valueOf(pick.optInt("ctx", 4096)));
            cmd.add("-np"); cmd.add("1");
            cmd.add("-t"); cmd.add("4");
            cmd.add("-ctk"); cmd.add("q8_0");
            cmd.add("-ctv"); cmd.add("q8_0");
            cmd.add("--image-max-tokens"); cmd.add(String.valueOf(pick.optInt("image_max_tokens", 1024)));
            cmd.add("--no-webui");
            // Flags from the IDE's "Vision model: extra llama-server flags" setting (flash attention off by default).
            boolean usedExtras = false;
            if (!plain) for (String extra : cfg.optString("vision_extra_args", "-fa off").trim().split("\\s+")) if (!extra.isEmpty()) { cmd.add(extra); usedExtras = true; }
            String key = cfg.optString("api_key", "");
            if (!key.isEmpty()) { cmd.add("--api-key"); cmd.add(key); }
            vstate = "starting"; vdetail = "loading " + vtier + " on the Intel GPU";
            report();
            log("launch " + vtier + ": " + String.join(" ", cmd).replace(key, "<key>"));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", ctx.getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
            GpuBudget.beforeStart(ctx, "vision");
            long launchedAt = System.currentTimeMillis();
            Process p = pb.start();
            proc = p;
            // The vision server is reclaimed before the main engine (900) and long before the Linux VM (0).
            int pid = pidOf(p);
            try (FileOutputStream adj = new FileOutputStream("/proc/" + pid + "/oom_score_adj")) { adj.write("950".getBytes()); } catch (Exception e) { log("oom_score_adj not set: " + e.getMessage()); }
            EngineService.memoryGuard(ctx, p, "vision server");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("server is listening") || line.contains("all slots are idle")) {
                        if (!"running".equals(vstate)) { vstate = "running"; vdetail = vtier + " on the Intel GPU"; report(); }
                    }
                    if (line.contains("error") || line.contains("ggml_vulkan: 0") || line.contains("listening")) log(line);
                }
            }
            int code = p.waitFor();
            GpuBudget.afterStop(ctx, "vision");
            if (proc == p) proc = null;
            boolean early = code != 0 && !"running".equals(vstate) && !"stopped".equals(vstate) && System.currentTimeMillis() - launchedAt < 30000;
            if (early && usedExtras) {
                log("the vision server exited with code " + code + " right after a launch with the extra flags; starting it again without them");
                retrying = true;
                startAttempt(ctx, cfg, skipTiers, true);
                return;
            }
            if (early && skipTiers + 1 < tiers.length()) {
                log("the vision server exited with code " + code + " right after its launch; trying the next tier");
                retrying = true;
                startAttempt(ctx, cfg, skipTiers + 1, false);
                return;
            }
            if (!"stopped".equals(vstate)) { vstate = code == 0 ? "stopped" : "error"; vdetail = "vision server exited with code " + code; report(); }
        } catch (Exception e) {
            // Reading the server's output is interrupted when we stop it on purpose: that is not an error.
            if (!"stopped".equals(vstate)) fail(e.getMessage() == null ? e.toString() : e.getMessage());
            else { GpuBudget.afterStop(ctx, "vision"); proc = null; }
        } finally {
            if (!retrying) {
                EngineService.resumeAfterVision();   // Llama gets the GPU back
                if (held) EngineService.releaseHold(ctx);
            }
        }
    }

    public static synchronized void stop(String why) {
        Process p = proc;
        vstate = "stopped"; vdetail = why;
        if (p != null) {
            log("stopping vision server: " + why);
            p.destroy();
            try { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) { }
        }
        proc = null;
        report();
    }

    /** The memory guard stopped the vision server: say so, instead of a bare "exited with code". */
    static void guardStopped(String why) { vstate = "error"; vdetail = why; log("[vision] " + why); report(); }

    private static void fail(String why) {
        vstate = "error"; vdetail = why;
        log("ERROR: " + why);
        report();
    }

    private static void log(String s) { Log.i(TAG, s); EngineService.log("[vision] " + s); }

    private static void report() {
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("vstate", vstate); o.put("vtier", vtier); o.put("vdetail", vdetail);
                o.put("host_mem_mb", EngineService.memAvailableMb());
                o.put("log", EngineService.logTail(60));   // the IDE keeps it for diagnosis (/api/engine/applog)
                HttpURLConnection c = (HttpURLConnection) new URL(FORGE + "/api/engine/vision_status").openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(3000); c.setReadTimeout(5000);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(o.toString().getBytes());
                c.getResponseCode(); c.disconnect();
            } catch (Exception ignored) { }
        }).start();
    }

    private static void download(String url, File dest, long size) throws Exception {
        vstate = "downloading"; vdetail = "downloading " + dest.getName() + " (" + (size >> 20) + " MB, once)";
        report();
        File tmp = new File(dest.getAbsolutePath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(120000);
        if (c.getResponseCode() != 200) throw new Exception("download of " + dest.getName() + " failed: HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream(); RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
            out.setLength(0);
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (tmp.length() != size) throw new Exception("download of " + dest.getName() + " incomplete");
        if (!tmp.renameTo(dest)) throw new Exception("could not move " + dest.getName() + " into place");
        log("downloaded " + dest.getName());
    }

    private static int pidOf(Process p) {
        try { return (int) (long) p.getClass().getMethod("pid").invoke(p); } catch (Exception e) {
            try { java.lang.reflect.Field f = p.getClass().getDeclaredField("pid"); f.setAccessible(true); return f.getInt(p); } catch (Exception e2) { return -1; }
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static String get(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(timeout);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes()); } finally { c.disconnect(); }
    }
}
