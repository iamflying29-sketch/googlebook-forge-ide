package local.forge

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.io.BufferedReader
import java.io.FileReader

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
 * 5.4 GB in the pool: a vision model that started took 1.4 GB straight out of it. The check estimates the pool as the
 * unaccounted memory now minus the unaccounted memory measured in this boot before Forge's first GPU process (the
 * baseline, kept as the lowest value ever seen in this boot), and credits the share the user chose in the IDE's
 * settings (0 by default) only after a Forge GPU process has run in this boot. A memory guard in each GPU process
 * stops the process the moment visible free memory gets scarce; after such a stop no pool credit is given for
 * 15 minutes.
 */
object GpuBudget {
    private const val PREFS = "gpu_budget"
    private const val SESSION_WINDOW_MS = 15L * 60 * 1000
    private const val MARGIN_MB = 500L
    private const val FLOOR_MB = 1500L
    /** The memory guard stops a Forge GPU process when visible free memory falls under this. */
    const val GUARD_FLOOR_MB = 700L
    /** Share of the measured driver pool the check may count; 0 unless the IDE's setting says otherwise. */
    @JvmStatic @Volatile var poolCreditPercent: Long = 0
    private var staleChecked = false

    private fun prefs(c: Context): SharedPreferences {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            if (!staleChecked) {
                // A new app process: any process marked running died with the old one. Its memory is in the pool,
                // and the session window still counts from the last heartbeat.
                staleChecked = true
                p.edit().putBoolean("running_engine", false).putBoolean("running_vision", false).apply()
            }
        }
        return p
    }

    private fun anyRunning(p: SharedPreferences): Boolean = p.getBoolean("running_engine", false) || p.getBoolean("running_vision", false)

    @JvmStatic @Synchronized fun beforeStart(c: Context, who: String) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val session = p.getLong("baseline_mb", -1) > 0 && (anyRunning(p) || now - p.getLong("last_active", 0) < SESSION_WINDOW_MS)
        val e = p.edit()
        if (!session) e.putLong("baseline_mb", EngineService.memAvailableMb())
        poolMb(c)   // records this boot's baseline before the first GPU process touches the pool
        e.putBoolean("running_$who", true).putLong("last_active", now).putBoolean("gpu_used_this_boot", true).apply()
    }

    @JvmStatic @Synchronized fun heartbeat(c: Context) {
        val p = prefs(c)
        if (anyRunning(p)) p.edit().putLong("last_active", System.currentTimeMillis()).apply()
    }

    @JvmStatic @Synchronized fun afterStop(c: Context, who: String) {
        prefs(c).edit().putBoolean("running_$who", false).putLong("last_active", System.currentTimeMillis()).apply()
    }

    /** Free memory the next GPU process can count on, in MB: visible free memory, or the session baseline, or
     *  visible free memory plus the chosen share of the driver's pool, whichever is largest. */
    @JvmStatic @Synchronized fun effectiveMb(c: Context): Long {
        val p = prefs(c)
        val now = EngineService.memAvailableMb()
        var best = now
        val base = p.getLong("baseline_mb", -1)
        val recent = System.currentTimeMillis() - p.getLong("last_active", 0) < SESSION_WINDOW_MS
        val pool = poolMb(c)
        // The session baseline assumes the gap since the session began is the driver's pool. Credit only the part of
        // the gap that the pool measurement can see (checked 2026-10-20: without this cap, memory taken by other apps
        // after the GPU process stopped was counted as free too).
        if (base > 0 && recent && !anyRunning(p) && now >= 0) best = maxOf(best, minOf(base - MARGIN_MB, now + pool))
        if (pool > 0 && now >= 0 && poolCreditPercent > 0) best = maxOf(best, now + pool * minOf(100L, poolCreditPercent) / 100)
        return best
    }

    /** A stamp that identifies this boot (wall clock minus uptime; stable within ten seconds). */
    private fun bootStamp(): Long = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 10000

    /** Memory that no counter in /proc/meminfo explains, in MB (the graphics driver's pages live here). The
     *  compressed swap's physical pages are not readable without root; they are estimated at 45 percent of the
     *  swapped amount (measured 38 to 41 percent on this machine), which errs on the safe side. */
    @JvmStatic fun unaccountedMb(): Long {
        val m = HashMap<String, Long>()
        try {
            BufferedReader(FileReader("/proc/meminfo")).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val digits = line.substring(colon + 1).replace(Regex("[^0-9]"), "")
                    val v = digits.toLongOrNull() ?: continue
                    m[line.substring(0, colon)] = v / 1024
                }
            }
        } catch (e: Exception) {
            return -1
        }
        val total = m["MemTotal"] ?: return -1
        if (!m.containsKey("MemAvailable")) return -1
        var used = 0L
        for (k in arrayOf("MemFree", "Buffers", "Cached", "AnonPages", "Slab", "KernelStack", "PageTables", "SecPageTables", "VmallocUsed")) used += m[k] ?: 0L
        val swapped = maxOf(0L, (m["SwapTotal"] ?: 0L) - (m["SwapFree"] ?: 0L))
        used += swapped * 45 / 100
        return maxOf(0L, total - used)
    }

    /** The driver pool's size as far as it can be measured, in MB, or 0 when it cannot be credited. */
    @JvmStatic @Synchronized fun poolMb(c: Context): Long {
        val p = prefs(c)
        val now = unaccountedMb()
        if (now < 0) return 0
        val stamp = bootStamp()
        val e = p.edit()
        if (p.getLong("boot_stamp", -1) != stamp) {
            // A new boot: the pool is empty, so what is unaccounted now is the baseline of this boot.
            e.putLong("boot_stamp", stamp).putLong("boot_base_mb", now).putBoolean("gpu_used_this_boot", false).putLong("guard_stop_ts", 0).apply()
            return 0
        }
        var base = p.getLong("boot_base_mb", now)
        if (now < base) { base = now; e.putLong("boot_base_mb", base) }
        e.apply()
        if (!p.getBoolean("gpu_used_this_boot", false)) return 0
        if (System.currentTimeMillis() - p.getLong("guard_stop_ts", 0) < SESSION_WINDOW_MS) return 0
        return maxOf(0L, now - base)
    }

    /** Called by the memory guard after it stopped a GPU process: no pool credit for a while. */
    @JvmStatic @Synchronized fun guardStopped(c: Context) {
        prefs(c).edit().putLong("guard_stop_ts", System.currentTimeMillis()).apply()
    }

    /** One line for the engine log: what the check sees. */
    @JvmStatic fun describe(c: Context): String = "visible " + EngineService.memAvailableMb() + " MB, driver pool " + poolMb(c) + " MB, counted " + effectiveMb(c) + " MB"

    @JvmStatic fun lowMemory(c: Context): Boolean {
        return try {
            val am = c.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.lowMemory
        } catch (e: Exception) {
            true   // unknown means unsafe
        }
    }

    /** Null when a process needing needMb may start now, otherwise the reason it may not. */
    @JvmStatic fun check(c: Context, needMb: Long): String? {
        if (lowMemory(c)) return "Android reports low memory"
        val avail = EngineService.memAvailableMb()
        if (avail >= 0 && avail < FLOOR_MB) return "only $avail MB free"
        val eff = effectiveMb(c)
        if (eff >= 0 && eff < needMb) return "$eff MB free, $needMb MB needed"
        return null
    }
}
