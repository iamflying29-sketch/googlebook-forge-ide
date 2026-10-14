package local.forge;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * The free-memory check for everything Forge loads onto the Intel GPU. It is never bypassed.
 *
 * Why it is more than "MemAvailable >= need": when a GPU process stops, the graphics driver keeps the memory it used
 * for reuse, and Android does not count that pool as free (measured 2026-10-14: after stopping the 8B engine only
 * 0.6 GB of 2.2 GB came back within a minute). The next GPU process reuses that pool first. So within a short session
 * the check may count the free memory measured before Forge's GPU session began, minus a margin, but only while no
 * Forge GPU process is running and only within 15 minutes of Forge's last GPU activity. It always refuses when
 * Android reports low memory or when visible free memory is below a hard floor.
 */
final class GpuBudget {
    private static final String PREFS = "gpu_budget";
    private static final long SESSION_WINDOW_MS = 15L * 60 * 1000;
    private static final long MARGIN_MB = 500;
    private static final long FLOOR_MB = 1500;
    private static boolean staleChecked = false;

    private GpuBudget() { }

    private static SharedPreferences prefs(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        synchronized (GpuBudget.class) {
            if (!staleChecked) {
                // A new app process: any process marked running died with the old one. Its memory is in the pool,
                // and the session window still counts from the last heartbeat.
                staleChecked = true;
                p.edit().putBoolean("running_engine", false).putBoolean("running_vision", false).apply();
            }
        }
        return p;
    }

    private static boolean anyRunning(SharedPreferences p) {
        return p.getBoolean("running_engine", false) || p.getBoolean("running_vision", false);
    }

    static synchronized void beforeStart(Context c, String who) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        boolean session = p.getLong("baseline_mb", -1) > 0 && (anyRunning(p) || now - p.getLong("last_active", 0) < SESSION_WINDOW_MS);
        SharedPreferences.Editor e = p.edit();
        if (!session) e.putLong("baseline_mb", EngineService.memAvailableMb());
        e.putBoolean("running_" + who, true).putLong("last_active", now).apply();
    }

    static synchronized void heartbeat(Context c) {
        SharedPreferences p = prefs(c);
        if (anyRunning(p)) p.edit().putLong("last_active", System.currentTimeMillis()).apply();
    }

    static synchronized void afterStop(Context c, String who) {
        prefs(c).edit().putBoolean("running_" + who, false).putLong("last_active", System.currentTimeMillis()).apply();
    }

    /** Free memory the next GPU process can count on, in MB. */
    static synchronized long effectiveMb(Context c) {
        SharedPreferences p = prefs(c);
        long now = EngineService.memAvailableMb();
        long base = p.getLong("baseline_mb", -1);
        boolean recent = System.currentTimeMillis() - p.getLong("last_active", 0) < SESSION_WINDOW_MS;
        if (base > 0 && recent && !anyRunning(p)) return Math.max(now, base - MARGIN_MB);
        return now;
    }

    static boolean lowMemory(Context c) {
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.lowMemory;
        } catch (Exception e) {
            return true;   // unknown means unsafe
        }
    }

    /** Null when a process needing needMb may start now, otherwise the reason it may not. */
    static String check(Context c, long needMb) {
        if (lowMemory(c)) return "Android reports low memory";
        long avail = EngineService.memAvailableMb();
        if (avail >= 0 && avail < FLOOR_MB) return "only " + avail + " MB free";
        long eff = effectiveMb(c);
        if (eff >= 0 && eff < needMb) return eff + " MB free, " + needMb + " MB needed";
        return null;
    }
}
