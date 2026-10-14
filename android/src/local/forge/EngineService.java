package local.forge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Forge GPU engine: runs llama.cpp's llama-server (Vulkan backend, Intel graphics) on the Android side.
 *
 * Lifecycle: start -> read config from the Forge server in the VM (localhost:8765) -> download the model
 * weights from it once (kept in this app's private storage) -> check free host memory -> launch the server
 * bound to the VM-facing address with an API key -> watch it. Stop kills it. Everything is logged to
 * files/engine.log and exposed to the IDE through the JS bridge.
 */
public class EngineService extends Service {
    private static final String TAG = "ForgeEngine";
    private static final String CHANNEL = "forge_engine";
    private static final int NOTIF_ID = 1001;
    private static final String FORGE = "http://localhost:8765";
    public static final String ACTION_START = "local.forge.ENGINE_START";
    public static final String ACTION_STOP = "local.forge.ENGINE_STOP";

    private static volatile EngineService instance;
    private static final ArrayDeque<String> logLines = new ArrayDeque<>();
    private static volatile String state = "stopped";      // stopped | downloading | starting | running | error
    private static volatile String detail = "";
    private static volatile long downloaded = 0, downloadTotal = 0;
    /** True while the engine is paused so the vision model can use the GPU (one model on the GPU at a time). */
    static volatile boolean pausing = false;
    static volatile boolean pausedForVision = false;

    private Process proc;
    private Thread worker;
    private volatile boolean stopping = false;

    // ------------------------------------------------------------ public status for the bridge
    public static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("detail", detail);
            o.put("downloaded", downloaded);
            o.put("downloadTotal", downloadTotal);
            File models = new File(ctx.getFilesDir(), "models");
            List<String> names = new ArrayList<>();
            File[] fs = models.listFiles();
            if (fs != null) for (File f : fs) names.add(f.getName() + " (" + (f.length() / (1024 * 1024)) + " MB)");
            o.put("models", names.toString());
            o.put("hostMemAvailableMb", memAvailableMb());
            o.put("binary", new File(ctx.getApplicationInfo().nativeLibraryDir, "libllama-server.so").exists());
            o.put("vision", VisionControl.state());
            o.put("visionTier", VisionControl.tier());
            o.put("visionDetail", VisionControl.detail());
        } catch (Exception ignored) { }
        return o;
    }

    public static String logTail() {
        synchronized (logLines) {
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    static void log(String s) {
        Log.i(TAG, s);
        synchronized (logLines) {
            logLines.addLast(s);
            while (logLines.size() > 400) logLines.removeFirst();
        }
    }

    static long memAvailableMb() {
        try (BufferedReader r = new BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    // ------------------------------------------------------------ service plumbing
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        VisionControl.ensure(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Forge GPU engine", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Runs the AI model on the Intel graphics while Forge is in use.");
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEngine("stopped by user");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (worker == null || !worker.isAlive()) {
            stopping = false;
            worker = new Thread(this::run, "forge-engine");
            worker.start();
        }
        return START_NOT_STICKY;   // never auto-restart a 5 GB process behind the user's back
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopEngine("service destroyed");
        instance = null;
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, EngineService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Forge GPU engine")
                .setContentText(text)
                .setContentIntent(pOpen)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop engine", pStop).build())
                .build();
    }

    private void notifyText(String text) {
        detail = text;
        try { getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text)); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ the work
    private void run() {
        try {
            state = "starting";
            notifyText("Reading configuration from Forge…");
            JSONObject cfg = new JSONObject(httpGet(FORGE + "/api/engine/config", 15000));
            JSONObject model = cfg.optJSONObject("model");
            if (model == null) throw new Exception("Forge could not locate the model weights in Ollama's store.");
            File modelsDir = new File(getFilesDir(), "models");
            modelsDir.mkdirs();
            File modelFile = new File(modelsDir, model.getString("filename"));
            long size = model.getLong("size");
            if (!modelFile.exists() || modelFile.length() != size) {
                state = "downloading";
                download(FORGE + model.getString("download"), modelFile, size);
            }
            File bin = new File(getApplicationInfo().nativeLibraryDir, "libllama-server.so");
            if (!bin.exists()) throw new Exception("Engine binary missing from this build of the app.");
            long needMb = size / (1024 * 1024) + 1200;   // weights + KV cache + compute buffers + margin
            // The free-memory check (GpuBudget), never bypassed. Ask the VM to hand memory back while waiting.
            String why = GpuBudget.check(this, needMb);
            for (int attempt = 0; why != null && attempt < 120 && !stopping; attempt++) {
                if (attempt % 12 == 0) { try { httpPost(FORGE + "/api/engine/prepare", "{}", 90000); } catch (Exception e) { log("prepare: " + e.getMessage()); } }
                state = "starting";
                notifyText("Waiting for free memory (" + why + "). Closing other apps helps.");
                Thread.sleep(5000);
                why = GpuBudget.check(this, needMb);
            }
            if (why != null) {
                throw new Exception("Not enough free memory on the Googlebook for the engine (" + why + "). Close other apps and try again.");
            }
            log("memory check passed: " + GpuBudget.effectiveMb(this) + " MB usable, " + needMb + " MB needed");
            state = "starting";
            notifyText("Loading the model on the GPU…");
            int ctx = cfg.optInt("ctx", 8192);
            int ngl = cfg.optInt("gpu_layers", 99);
            String host = cfg.optString("host_ip", "127.0.0.1");
            int port = cfg.optInt("port", 8081);
            String key = cfg.optString("api_key", "");
            List<String> cmd = new ArrayList<>();
            cmd.add(bin.getAbsolutePath());
            cmd.add("-m"); cmd.add(modelFile.getAbsolutePath());
            cmd.add("--host"); cmd.add(host);
            cmd.add("--port"); cmd.add(String.valueOf(port));
            cmd.add("-ngl"); cmd.add(String.valueOf(ngl));
            cmd.add("-c"); cmd.add(String.valueOf(ctx));
            cmd.add("-np"); cmd.add("1");
            cmd.add("-t"); cmd.add("4");
            cmd.add("-fa"); cmd.add("on");
            cmd.add("-ctk"); cmd.add("q8_0");
            cmd.add("-ctv"); cmd.add("q8_0");
            cmd.add("--no-webui");
            cmd.add("--no-warmup");
            cmd.add("--metrics");
            if (!key.isEmpty()) { cmd.add("--api-key"); cmd.add(key); }
            log("launch: " + String.join(" ", cmd).replace(key, "<key>"));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            pb.environment().put("LLAMA_CACHE", getCacheDir().getAbsolutePath());
            GpuBudget.beforeStart(this, "engine");
            proc = pb.start();
            // Make the engine the first thing the system reclaims under memory pressure, never the VM.
            int epid = pidOf(proc);
            try (FileOutputStream adj = new FileOutputStream("/proc/" + epid + "/oom_score_adj")) { adj.write("900".getBytes()); log("engine pid " + epid + " marked oom_score_adj 900"); } catch (Exception e) { log("oom_score_adj not set for pid " + epid + ": " + e.getMessage()); }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log(line);
                    if (line.contains("server is listening") || line.contains("all slots are idle") || line.contains("starting the main loop")) {
                        if (!"running".equals(state)) { state = "running"; notifyText("Running on the GPU · " + host + ":" + port); postStats("running"); }
                    }
                }
            }
            int code = proc.waitFor();
            GpuBudget.afterStop(this, "engine");
            if (pausing) {
                pausing = false;
                pausedForVision = true;
                state = "paused";
                detail = "Paused while the vision model reads an image";
                notifyText(detail);
                postStats("paused");
            } else if (!stopping) {
                state = "error";
                detail = "Engine exited with code " + code + ". See the engine log.";
                log(detail);
                notifyText(detail);
                postStats("exited");
            } else {
                state = "stopped";
            }
        } catch (Exception e) {
            state = "error";
            detail = e.getMessage() == null ? e.toString() : e.getMessage();
            log("ERROR: " + detail);
            notifyText(detail);
        } finally {
            proc = null;
            if (!"paused".equals(state)) {
                stopForeground(STOP_FOREGROUND_DETACH);
                if (!"error".equals(state)) stopSelf();
            }
        }
    }

    /** Android's Process has no pid() method; its implementation keeps the pid in a private field. */
    static int pidOf(Process p) {
        try { return (int) (long) p.getClass().getMethod("pid").invoke(p); } catch (Exception e) {
            try { java.lang.reflect.Field f = p.getClass().getDeclaredField("pid"); f.setAccessible(true); return f.getInt(p); } catch (Exception e2) { return -1; }
        }
    }

    private void postStats(String what) {
        new Thread(() -> {
            try {
                URL u = new URL(FORGE + "/api/engine/stats");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(3000); c.setReadTimeout(3000);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(("{\"event\":\"" + what + "\",\"state\":\"" + state + "\"}").getBytes());
                c.getResponseCode(); c.disconnect();
            } catch (Exception ignored) { }
        }).start();
    }

    private void download(String url, File dest, long size) throws Exception {
        File tmp = new File(dest.getAbsolutePath() + ".part");
        long have = tmp.exists() ? tmp.length() : 0;
        if (have > size) { tmp.delete(); have = 0; }   // a corrupt partial file from an interrupted download
        downloadTotal = size; downloaded = have;
        notifyText("Downloading the model (" + (size / (1024 * 1024)) + " MB)…");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(120000);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        int code = c.getResponseCode();
        if (code != 200 && code != 206) throw new Exception("model download failed: HTTP " + code);
        boolean append = code == 206;
        if (!append) { downloaded = 0; have = 0; }
        try (InputStream in = c.getInputStream(); RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
            if (append) out.seek(have); else out.setLength(0);
            byte[] buf = new byte[1 << 20];
            int n; long last = 0;
            while ((n = in.read(buf)) > 0) {
                if (stopping) throw new Exception("download cancelled");
                out.write(buf, 0, n);
                downloaded += n;
                if (downloaded - last > 200L * 1024 * 1024) { last = downloaded; notifyText(String.format(Locale.US, "Downloading the model… %d / %d MB", downloaded >> 20, size >> 20)); }
            }
        }
        if (tmp.length() != size) throw new Exception("model download incomplete: " + tmp.length() + " of " + size + " bytes");
        if (!tmp.renameTo(dest)) throw new Exception("could not move the downloaded model into place");
        log("model downloaded: " + dest.getName() + " " + size + " bytes");
    }

    private void stopEngine(String why) {
        stopping = true;
        Process p = proc;
        if (p != null) {
            log("stopping engine: " + why);
            p.destroy();
            try { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) { }
        }
        state = "stopped";
        detail = why;
        postStats("stopped");
    }

    private static String httpPost(String url, String body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(body.getBytes());
        int code = c.getResponseCode();
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes()); } finally { c.disconnect(); }
    }

    private static String httpGet(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("GET " + url + " -> HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = in.readAllBytes();
            return new String(buf);
        } finally { c.disconnect(); }
    }

    // ------------------------------------------------------------ taking turns with the vision model
    static boolean engineRunning() { EngineService s = instance; return s != null && s.proc != null; }

    /** Stops the engine process (keeping the service) so the vision model can use the GPU. True if it was running. */
    static boolean pauseForVision() {
        EngineService s = instance;
        if (s == null || s.proc == null) return false;
        pausing = true;
        Process p = s.proc;
        log("pausing the engine for the vision model");
        p.destroy();
        try { if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        for (int i = 0; i < 50 && !pausedForVision; i++) { try { Thread.sleep(100); } catch (InterruptedException ignored) { } }
        return true;
    }

    /** Starts the engine again after the vision model is done (through the normal free-memory check). */
    static void resumeAfterVision() {
        EngineService s = instance;
        if (s == null || !pausedForVision) return;
        pausedForVision = false;
        log("resuming the engine after the vision model");
        s.stopping = false;
        s.worker = new Thread(s::run, "forge-engine");
        s.worker.start();
    }

    // ------------------------------------------------------------ control from the activity bridge
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, EngineService.class).setAction(ACTION_START);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, EngineService.class).setAction(ACTION_STOP);
        ctx.startService(i);
    }
}
