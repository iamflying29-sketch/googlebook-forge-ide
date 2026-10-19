package local.forge;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * The free-memory check for everything Forge loads onto the Intel GPU. It is never bypassed.
 *
 * Why it is more than "MemAvailable >= need": when a GPU process stops, the graphics driver keeps the memory it used
 * for reuse, and Android does not count that pool as free (measured 2026-10-14: after stopping the 8B engine only
 * 0.6 GB of 2.2 GB came back within a minute). The next GPU process reuses that pool first. So within a short session
 * the check may count the free memory measured before Forge's GPU session began, minus a margin, but only while no
 * Forge GPU process is running and only within 15 minutes of Forge's last GPU activity. It always refuses when
 * Android reports low memory or when visible free memory is below a hard floor.
 *
 * The driver's pool, measured (added 2026-10-19): the pages the graphics driver keeps show up in no counter of
 * /proc/meminfo, so "memory that nothing accounts for" grows by exactly the pool. Measured on the Googlebook with
 * 5.4 GB in the pool: a vision model that started took 1.4 GB straight out of it (dumpsys "Lost RAM" fell from
 * 5.59 to 4.14 GB while MemAvailable rose). The check therefore estimates the pool as the unaccounted memory now
 * minus the unaccounted memory measured in this boot before Forge's first GPU process (the baseline, kept as the
 * lowest value ever seen in this boot, so it can only shrink the estimate), counts 80 percent of it, and credits it
 * only after a Forge GPU process has run in this boot (before that, the memory belongs to something else).
 * A memory guard in each GPU process stops the process the moment visible free memory gets scarce, so the VM
 * never pays for a wrong estimate; after such a stop no pool credit is given for 15 minutes.
 */
final class GpuBudget {
    private static final String PREFS = "gpu_budget";
    private static final long SESSION_WINDOW_MS = 15L * 60 * 1000;
    private static final long MARGIN_MB = 500;
    private static final long FLOOR_MB = 1500;
    /** The memory guard stops a Forge GPU process when visible free memory falls under this. */
    static final long GUARD_FLOOR_MB = 700;
    /** Share of the measured driver pool the check may count; 0 unless the IDE's setting "GPU engine: count the
     *  driver's held memory" says otherwise (the app reads it from the engine configuration at every start). */
    static volatile long poolCreditPercent = 0;
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
        poolMb(c);   // records this boot's baseline before the first GPU process touches the pool
        e.putBoolean("running_" + who, true).putLong("last_active", now).putBoolean("gpu_used_this_boot", true).apply();
    }

    static synchronized void heartbeat(Context c) {
        SharedPreferences p = prefs(c);
        if (anyRunning(p)) p.edit().putLong("last_active", System.currentTimeMillis()).apply();
    }

    static synchronized void afterStop(Context c, String who) {
        prefs(c).edit().putBoolean("running_" + who, false).putLong("last_active", System.currentTimeMillis()).apply();
    }

    /** Free memory the next GPU process can count on, in MB: visible free memory, or the session baseline, or
     *  visible free memory plus the measured share of the driver's pool, whichever is largest. */
    static synchronized long effectiveMb(Context c) {
        SharedPreferences p = prefs(c);
        long now = EngineService.memAvailableMb();
        long best = now;
        long base = p.getLong("baseline_mb", -1);
        boolean recent = System.currentTimeMillis() - p.getLong("last_active", 0) < SESSION_WINDOW_MS;
        if (base > 0 && recent && !anyRunning(p)) best = Math.max(best, base - MARGIN_MB);
        long pool = poolMb(c);
        if (pool > 0 && now >= 0 && poolCreditPercent > 0) best = Math.max(best, now + pool * Math.min(100, poolCreditPercent) / 100);
        return best;
    }

    /** A stamp that identifies this boot (wall clock minus uptime; stable within a second). */
    private static long bootStamp() { return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 10000; }

    /** Memory that no counter in /proc/meminfo explains, in MB (the graphics driver's pages live here). The
     *  compressed swap's physical pages are not readable without root; they are estimated at 45 percent of the
     *  swapped amount (measured 38 to 41 percent on this machine), which errs on the safe side. */
    static long unaccountedMb() {
        Map<String, Long> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                try { m.put(line.substring(0, colon), Long.parseLong(line.substring(colon + 1).replaceAll("[^0-9]", "")) / 1024); } catch (NumberFormatException ignored) { }
            }
        } catch (Exception e) {
            return -1;
        }
        if (!m.containsKey("MemTotal") || !m.containsKey("MemAvailable")) return -1;
        long total = m.get("MemTotal");
        long used = 0;
        for (String k : new String[]{"MemFree", "Buffers", "Cached", "AnonPages", "Slab", "KernelStack", "PageTables", "SecPageTables", "VmallocUsed"}) {
            Long v = m.get(k);
            if (v != null) used += v;
        }
        long swapped = Math.max(0, m.getOrDefault("SwapTotal", 0L) - m.getOrDefault("SwapFree", 0L));
        used += swapped * 45 / 100;
        return Math.max(0, total - used);
    }

    /** The driver pool's size as far as it can be measured, in MB, or 0 when it cannot be credited. */
    static synchronized long poolMb(Context c) {
        SharedPreferences p = prefs(c);
        long now = unaccountedMb();
        if (now < 0) return 0;
        long stamp = bootStamp();
        SharedPreferences.Editor e = p.edit();
        if (p.getLong("boot_stamp", -1) != stamp) {
            // A new boot: the pool is empty, so what is unaccounted now is the baseline of this boot.
            e.putLong("boot_stamp", stamp).putLong("boot_base_mb", now).putBoolean("gpu_used_this_boot", false).putLong("guard_stop_ts", 0).apply();
            return 0;
        }
        long base = p.getLong("boot_base_mb", now);
        if (now < base) { base = now; e.putLong("boot_base_mb", base); }
        e.apply();
        if (!p.getBoolean("gpu_used_this_boot", false)) return 0;
        if (System.currentTimeMillis() - p.getLong("guard_stop_ts", 0) < SESSION_WINDOW_MS) return 0;
        return Math.max(0, now - base);
    }

    /** Called by the memory guard after it stopped a GPU process: no pool credit for a while. */
    static synchronized void guardStopped(Context c) {
        prefs(c).edit().putLong("guard_stop_ts", System.currentTimeMillis()).apply();
    }

    /** One line for the engine log: what the check sees. */
    static String describe(Context c) {
        return "visible " + EngineService.memAvailableMb() + " MB, driver pool " + poolMb(c) + " MB, counted " + effectiveMb(c) + " MB";
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
