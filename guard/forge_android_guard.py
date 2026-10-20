#!/usr/bin/python3 -I
"""Forge Android guard: protects the Linux VM from Android running in Forge's virtual devices.

It looks after Forge's own containers only (the ones podman runs for Forge's virtual devices, found by their
control group) and never touches any other container. It runs only while Forge runs: the Forge server starts it and
Quit Forge stops it.

Android in a container shares the VM's kernel, and a privileged container cannot hide the kernel's global controls,
because Android mounts its own /proc and /sys. This service (run as root by systemd) protects the VM in four ways.

1. Shutdowns. When Android powers off or restarts, its init writes to /proc/sysrq-trigger: "e" and "i" signal every
   process in the whole VM, "u" remounts every file system read-only, and "c" crashes the VM's kernel, which closes the
   Terminal app. Android's init logs to the kernel log first, so the guard reads that log as it is written and ends
   that Android at once (SIGKILL to its init ends the whole container), long before init reaches those steps. The
   kernel log does not name the container that wrote a line, so the guard traces it (the process that asked for the
   shutdown, or the Android that has just started) and ends only its own containers, never another Android. The same
   happens if an Android init ever starts to load an SELinux policy, which would put the whole VM under Android's
   policy. (Forge's images never do; this is the backstop for any other image.)

2. Suspend and hibernation. Android's suspend service can put the whole VM to sleep, and the VM might not wake up.
   The guard holds a kernel wake lock, so Android's suspend handshake (it reads /sys/power/wakeup_count first) waits
   forever instead of suspending, and it holds a secret memory handle, which makes the kernel refuse hibernation.

3. Global settings. Android's startup scripts change kernel settings for the whole VM (memory overcommit, memory
   watermarks, panic on hung tasks, SysRq, performance counters) and hand kernel files to Android's users, whose
   numbers belong to other users in the VM (Android's "system" is your own uid 1000). While one of this guard's
   Androids runs, and for a minute after, the guard compares those settings and the owners and modes of the files under /proc, /sys and the
   kernel's tracing, security and debug file systems with a reference taken while no Android was running, and puts
   back anything that changed. When nothing has run for a minute it refreshes the reference, so your own changes
   are kept.

4. Hazards. In the same time, it clears any SafeSetID policy (one for uid 1000 would kill sudo), switches off the
   handlers for ARM programs that Android registers with the kernel, and ends its Androids' livelock daemon llkd,
   which can crash the kernel on purpose.

Every step has a time limit and nothing here waits on another process. The guard writes its log to the journal and a
short status for Forge in /run/forge-android-guard."""
import ctypes
import errno
import json
import os
import re
import signal
import stat
import sys
import threading
import time

PATTERN = re.compile(rb"\binit: (Received sys\.powerctl=|Reboot start|InitFatalReboot|Got shutdown_command|"
                     rb"Opening SELinux policy|Loading SELinux policy)")
# Android's init changes /proc/cmdline (which the Terminal's display setup reads) in its first second, before it logs
# this line, so the shield checks at once instead of waiting for its next round.
STARTING = re.compile(rb"\binit: init (first|second) stage started")
STATUS_DIR = "/run/forge-android-guard"
WAKE_LOCK = "forge_guard_no_suspend"
# The containers this guard looks after, by a mark in their control group path (podman runs Forge's devices).
SCOPE_MARKS = ("libpod-",)
SCOPE_NAME = "Forge's virtual devices"

# Settings the kernel changes by itself, or that are counters and not settings.
VOLATILE = re.compile(r"^(kernel/random/|kernel/ns_last_pid$|kernel/tainted$|kernel/perf_event_max_sample_rate$|"
                      r"kernel/hostname$|kernel/domainname$|kernel/pty/nr$|fs/binfmt_misc/|fs/(dentry-state|"
                      r"inode-nr|inode-state|file-nr|aio-nr)$|fs/quota/|vm/drop_caches$|vm/compact_memory$|"
                      r"vm/stat_refresh$)")
SYSCTL_TREES = ("kernel", "vm", "fs", "abi", "debug", "dev", "user")
# Other single values Android's scripts write. A value in brackets ("always [madvise] never") is the one chosen.
VALUES = ["/sys/kernel/mm/lru_gen/enabled", "/sys/kernel/mm/transparent_hugepage/enabled",
          "/sys/kernel/mm/transparent_hugepage/defrag", "/sys/kernel/mm/transparent_hugepage/shmem_enabled",
          "/sys/power/sync_on_suspend", "/sys/power/pm_async", "/sys/power/mem_sleep", "/sys/power/pm_freeze_timeout",
          "/sys/power/pm_test", "/sys/power/disk", "/sys/module/dm_bufio/parameters/max_age_seconds",
          "/sys/module/dm_verity/parameters/prefetch_cluster", "/sys/kernel/tracing/tracing_on",
          "/sys/kernel/tracing/current_tracer"]
for _disk in ("vda", "vdb", "zram0"):
    VALUES += [f"/sys/block/{_disk}/queue/{q}" for q in ("read_ahead_kb", "scheduler", "nr_requests", "iostats",
                                                        "add_random", "rq_affinity")]
# Trees whose owners and modes are kept: (root, recurse). /proc and /sys stay on their own file system, so the
# other file systems mounted inside them are listed on their own.
PROC_SKIP = {"/proc/self", "/proc/thread-self", "/proc/net", "/proc/sys/net", "/proc/mounts"}
TREES_FAST = [("/proc", True), ("/sys/kernel/security", True), ("/sys/fs/pstore", True), ("/sys/fs/selinux", True),
              ("/sys/kernel/debug", False), ("/sys/power", True)]
TREES_FULL = [("/sys", True)]
# The tracing file system re-creates many of its files on demand, so it is held to a rule instead of a snapshot:
# everything belongs to root and nothing is open to other users (which is how the kernel creates it).
TRACEFS = "/sys/kernel/tracing"

_log_lock = threading.Lock()
wake = threading.Event()   # set by the kernel log reader when an Android init starts
recent = []          # the latest fixes, for the status file


def log(text: str) -> None:
    with _log_lock:
        sys.stdout.write(time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n")
        sys.stdout.flush()


def write_status(name: str, data: dict) -> None:
    try:
        os.makedirs(STATUS_DIR, exist_ok=True)
        tmp = os.path.join(STATUS_DIR, name + ".new")
        with open(tmp, "w") as f:
            json.dump(data, f)
        os.chmod(tmp, 0o644)
        os.replace(tmp, os.path.join(STATUS_DIR, name))
    except OSError:
        pass


# ------------------------------------------------------------------ Android processes
def in_scope(pid) -> bool:
    """True when the process belongs to one of this guard's containers."""
    try:
        with open(f"/proc/{pid}/cgroup", "rb") as f:
            text = f.read(4096).decode("utf-8", "replace")
    except OSError:
        return False
    return any(mark in text for mark in SCOPE_MARKS)


def namespace_inits(scoped: bool = True):
    """(pid, comm) of every process that is PID 1 of a nested PID namespace (a container's first process), only this
    guard's containers unless scoped is False."""
    for name in os.listdir("/proc"):
        if not name.isdigit():
            continue
        try:
            with open(f"/proc/{name}/status", "rb") as f:
                comm = b""
                for line in f:
                    if line.startswith(b"Name:"):
                        comm = line.split(None, 1)[1].strip() if len(line.split()) > 1 else b""
                    elif line.startswith(b"NSpid:"):
                        ids = line.split()[1:]
                        if len(ids) >= 2 and ids[-1] == b"1" and (not scoped or in_scope(name)):
                            yield int(name), comm
                        break
        except OSError:
            continue


def nested_named(names):
    """PIDs of processes with one of these names that live in a nested PID namespace."""
    for name in os.listdir("/proc"):
        if not name.isdigit():
            continue
        try:
            with open(f"/proc/{name}/status", "rb") as f:
                comm, nested = b"", False
                for line in f:
                    if line.startswith(b"Name:"):
                        parts = line.split()
                        comm = parts[1] if len(parts) > 1 else b""
                    elif line.startswith(b"NSpid:"):
                        nested = len(line.split()) > 2
                        break
            if nested and comm in names and in_scope(name):
                yield int(name)
        except OSError:
            continue


# init logs who asked for a shutdown: "Received sys.powerctl='...' from pid: N (program)", N being the PID inside
# that Android's container. The lines that follow it in the same shutdown name nobody.
POWERCTL_FROM = re.compile(rb"Received sys\.powerctl='[^']*' from pid: (\d+)(?: \(([^)]*)\))?")
FOLLOW_UP = re.compile(rb"\binit: (Reboot start|Got shutdown_command)")
SELINUX_LINE = re.compile(rb"\binit: (Opening|Loading) SELinux policy")
_traced = {"time": -1e9, "inits": []}   # the latest shutdown request whose Android was found


def android_inits():
    """(pid, mine) for the init of every Android container in the VM, this guard's or not. Only read: another
    guard's containers are never touched."""
    return [(pid, in_scope(pid)) for pid, comm in namespace_inits(scoped=False) if comm == b"init"]


def pid_namespace(pid) -> str:
    try:
        return os.readlink(f"/proc/{pid}/ns/pid")
    except OSError:
        return ""


def requester_inits(ns_pid: bytes, program: bytes) -> list:
    """(pid, mine) for the init of each container that holds a process with this PID inside its container and this
    program name: the Android whose process asked for the shutdown."""
    spaces = set()
    for name in os.listdir("/proc"):
        if not name.isdigit():
            continue
        try:
            with open(f"/proc/{name}/status", "rb") as f:
                ids = []
                for line in f:
                    if line.startswith(b"NSpid:"):
                        ids = line.split()[1:]
                        break
            if len(ids) < 2 or ids[-1] != ns_pid:
                continue
            if program:
                with open(f"/proc/{name}/cmdline", "rb") as f:
                    if f.read(4096).split(b"\0", 1)[0] != program:
                        continue
            space = pid_namespace(name)
            if space:
                spaces.add(space)
        except OSError:
            continue
    return [(pid, mine) for pid, mine in android_inits() if pid_namespace(pid) in spaces]


def started_inits(seconds: float = 30.0) -> list:
    """(pid, mine) for the Android inits that started in the last seconds (init loads SELinux in its first second)."""
    try:
        with open("/proc/uptime") as f:
            up = float(f.read().split()[0])
        tick = os.sysconf("SC_CLK_TCK")
    except (OSError, ValueError):
        return []
    out = []
    for pid, mine in android_inits():
        try:
            with open(f"/proc/{pid}/stat", "rb") as f:
                fields = f.read().rsplit(b")", 1)[1].split()
            if up - int(fields[19]) / tick <= seconds:
                out.append((pid, mine))
        except (OSError, ValueError, IndexError):
            continue
    return out


def act(message: bytes) -> None:
    """Ends the Android that began to shut down or to load SELinux, but only when it runs in one of this guard's
    containers. The kernel log does not say which container wrote a line, so the guard traces it: the process that
    asked for the shutdown, the shutdown request that a follow-up line belongs to, or the Android that started in
    the last seconds. A line it cannot trace ends this guard's own containers, and never another Android."""
    text = message.decode("utf-8", "replace").strip()[:300]
    now = time.monotonic()
    found = None
    m = POWERCTL_FROM.search(message)
    if m:
        found = requester_inits(m.group(1), m.group(2) or b"") or None
        if found is not None:
            _traced.update(time=now, inits=found)
    elif FOLLOW_UP.search(message) and now - _traced["time"] < 5:
        found = _traced["inits"]
    elif SELINUX_LINE.search(message):
        found = started_inits() or None
    others = False
    if found is None:
        inits = android_inits()
        targets = [pid for pid, mine in inits if mine]
        others = any(not mine for _pid, mine in inits)
    else:
        targets = [pid for pid, mine in found if mine]
        if not targets:
            return   # another Android's line: its own guard looks after it
    killed = []
    for pid in targets:
        try:
            os.kill(pid, signal.SIGKILL)
            killed.append(pid)
        except OSError:
            pass
    if not killed:
        return
    how = "traced to it" if found is not None else ("not traceable, and another Android runs too, so only this guard's "
                                                    "containers were ended to keep the VM alive" if others else "not traceable")
    log(f"Android began to shut down or to load SELinux ({text}; {how}); ended container init {killed}")
    write_status("last.json", {"time": time.time(), "message": text, "killed": killed, "traced": found is not None})


def watch_kmsg() -> None:
    fd = os.open("/dev/kmsg", os.O_RDONLY)
    os.lseek(fd, 0, os.SEEK_END)   # only messages from now on
    log("watching the kernel log for Android shutdowns in containers")
    while True:
        try:
            record = os.read(fd, 8192)
        except OSError as e:
            if e.errno == errno.EPIPE:   # older records were overwritten while we read; keep going
                continue
            raise
        message = record.split(b";", 1)[-1]
        if PATTERN.search(message):
            act(message)
        elif STARTING.search(message):
            wake.set()


# ------------------------------------------------------------------ suspend and hibernation
_secret_fd = -1


def hold_locks() -> None:
    """Hold the wake lock (written again if anything released it) and the secret memory handle."""
    global _secret_fd
    try:
        with open("/sys/power/wake_lock", "r") as f:
            held = f.read().split()
        if WAKE_LOCK not in held:
            with open("/sys/power/wake_lock", "w") as f:
                f.write(WAKE_LOCK)
    except OSError as e:
        log(f"could not hold the wake lock: {e}")
    if _secret_fd < 0:
        libc = ctypes.CDLL(None, use_errno=True)
        fd = libc.syscall(447, 0)   # memfd_secret: while it exists, the kernel refuses to hibernate
        if fd >= 0:
            _secret_fd = fd
        else:
            log(f"could not create the secret memory handle (errno {ctypes.get_errno()}); hibernation stays possible")


# ------------------------------------------------------------------ the reference state
def read_text(path: str):
    try:
        with open(path, "r") as f:
            return f.read(4096).strip()
    except (OSError, UnicodeDecodeError):
        return None


def sysctls() -> dict:
    out = {}
    for tree in SYSCTL_TREES:
        top = "/proc/sys/" + tree
        for dirpath, dirnames, filenames in os.walk(top):
            rel_dir = dirpath[len("/proc/sys/"):]
            dirnames[:] = [d for d in dirnames if not VOLATILE.match(rel_dir + "/" + d + "/")]
            for fn in filenames:
                key = rel_dir + "/" + fn
                if VOLATILE.match(key):
                    continue
                path = dirpath + "/" + fn
                try:
                    if not os.stat(path).st_mode & 0o200 or not os.stat(path).st_mode & 0o400:
                        continue
                except OSError:
                    continue
                value = read_text(path)
                if value is not None:
                    out[key] = value
    return out


def values() -> dict:
    out = {}
    for path in VALUES:
        value = read_text(path)
        if value is not None:
            out[path] = value
    events = read_text("/sys/kernel/tracing/set_event")
    if events is not None:
        out["set_event"] = events
    return out


def perms(trees) -> dict:
    """Owner, group and mode of everything in these trees, staying on each tree's own file system."""
    out = {}
    for root, recurse in trees:
        try:
            dev = os.lstat(root).st_dev
        except OSError:
            continue
        stack = [root]
        while stack:
            path = stack.pop()
            try:
                st = os.lstat(path)
            except OSError:
                continue
            if st.st_dev != dev:
                continue
            out[path] = (st.st_uid, st.st_gid, stat.S_IMODE(st.st_mode))
            if recurse and stat.S_ISDIR(st.st_mode):
                try:
                    with os.scandir(path) as it:
                        for entry in it:
                            child = entry.path
                            if child in PROC_SKIP or (root == "/proc" and path == "/proc" and entry.name.isdigit()):
                                continue
                            stack.append(child)
                except OSError:
                    continue
    return out


def capture() -> dict:
    t0 = time.monotonic()
    ref = {"time": time.time(), "sysctl": sysctls(), "values": values(),
           "fast": perms(TREES_FAST), "full": perms(TREES_FULL)}
    log(f"reference taken: {len(ref['sysctl'])} settings, {len(ref['values'])} values, "
        f"{len(ref['fast']) + len(ref['full'])} files, in {time.monotonic() - t0:.2f} s")
    return ref


# ------------------------------------------------------------------ putting things back
def chosen(value: str) -> str:
    m = re.search(r"\[([^\]]+)\]", value)
    return m.group(1) if m else value.split(" (")[0]


def restore_sysctls(ref: dict, fixes: list) -> None:
    now = sysctls()
    for key, value in ref["sysctl"].items():
        if now.get(key, value) == value:
            continue
        try:
            with open("/proc/sys/" + key, "w") as f:
                f.write(value.replace("\t", " "))
            fixes.append(f"setting {key.replace('/', '.')}: {now[key]!r} back to {value!r}")
        except OSError as e:
            fixes.append(f"setting {key.replace('/', '.')} could not be put back ({e.strerror})")


def restore_values(ref: dict, fixes: list) -> None:
    now = values()
    for path, value in ref["values"].items():
        if now.get(path, value) == value:
            continue
        try:
            if path == "set_event":
                with open("/sys/kernel/tracing/set_event", "w") as f:
                    f.write("")
                for line in value.splitlines():
                    with open("/sys/kernel/tracing/set_event", "a") as f:
                        f.write(line)
            else:
                with open(path, "w") as f:
                    f.write(chosen(value))
            fixes.append(f"{path}: {now[path]!r} back to {value!r}")
        except OSError as e:
            fixes.append(f"{path} could not be put back ({e.strerror})")


def restore_perms(reference: dict, trees, fixes: list) -> None:
    for path, (uid, gid, mode) in perms(trees).items():
        want = reference.get(path)
        if want is None:
            # Something new (a device that appeared). Only hand it back to root if Android's users own it.
            if uid == 0 and gid == 0:
                continue
            want = (0, 0, mode)
        if (uid, gid, mode) == tuple(want):
            continue
        try:
            os.chown(path, want[0], want[1], follow_symlinks=False)
            os.chmod(path, want[2])
            fixes.append(f"{path}: {uid}:{gid} {mode:o} back to {want[0]}:{want[1]} {want[2]:o}")
        except OSError as e:
            fixes.append(f"{path} could not be put back ({e.strerror})")


def tracefs_rule(fixes: list) -> None:
    changed = 0
    try:
        dev = os.lstat(TRACEFS).st_dev
    except OSError:
        return
    stack = [TRACEFS]
    while stack:
        path = stack.pop()
        try:
            st = os.lstat(path)
        except OSError:
            continue
        if st.st_dev != dev:
            continue
        mode = stat.S_IMODE(st.st_mode)
        if st.st_uid or st.st_gid or mode & 0o007 or (path == TRACEFS and mode != 0o700):
            try:
                os.chown(path, 0, 0, follow_symlinks=False)
                os.chmod(path, 0o700 if path == TRACEFS else mode & ~0o007)
                changed += 1
            except OSError:
                pass
        if stat.S_ISDIR(st.st_mode):
            try:
                with os.scandir(path) as it:
                    stack.extend(entry.path for entry in it)
            except OSError:
                continue
    if changed:
        fixes.append(f"tracing file system: {changed} files given back to root and closed to other users")


# /proc is special: every /proc mount keeps its own cached copy of each entry's owner and mode, while the real
# values live in one shared record. When Android's init changes /proc/cmdline in its own /proc, the VM's cached copy
# still looks right, and the change only shows up later (seconds or hours), whenever the VM's copy is dropped. So the
# guard sets these entries through the VM's /proc on every round, which also rewrites the shared record, using values
# pinned from the first reference of this boot (kept in /run, so a restarted guard reuses them).
PROC_REF = os.path.join(STATUS_DIR, "proc-reference.json")


def proc_reference() -> dict:
    boot = read_text("/proc/sys/kernel/random/boot_id")
    try:
        with open(PROC_REF) as f:
            saved = json.load(f)
        if saved.get("boot_id") == boot and saved.get("entries"):
            return {k: tuple(v) for k, v in saved["entries"].items()}
    except (OSError, ValueError):
        pass
    entries = {}
    paths = [os.path.join("/proc", n) for n in os.listdir("/proc") if not n.isdigit()]
    try:
        paths += [os.path.join("/proc/pressure", n) for n in os.listdir("/proc/pressure")]
    except OSError:
        pass
    for path in paths:
        try:
            st = os.lstat(path)
        except OSError:
            continue
        if stat.S_ISREG(st.st_mode):
            entries[path] = (st.st_uid, st.st_gid, stat.S_IMODE(st.st_mode))
    write_status("proc-reference.json", {"boot_id": boot, "entries": entries})
    return entries


def reassert_proc(entries: dict, fixes: list) -> None:
    for path, (uid, gid, mode) in entries.items():
        try:
            st = os.lstat(path)
            if (st.st_uid, st.st_gid, stat.S_IMODE(st.st_mode)) != (uid, gid, mode):
                fixes.append(f"{path}: {st.st_uid}:{st.st_gid} {stat.S_IMODE(st.st_mode):o} back to {uid}:{gid} {mode:o}")
            os.chown(path, uid, gid)
            os.chmod(path, mode)
        except OSError:
            continue


def hazards(fixes: list) -> None:
    for kind in ("uid", "gid"):
        path = f"/sys/kernel/security/safesetid/{kind}_allowlist_policy"
        try:
            with open(path, "rb") as f:
                policy = f.read(65536)
            if policy.strip():
                fd = os.open(path, os.O_WRONLY)
                try:
                    os.write(fd, b"")   # an empty write installs an empty policy
                finally:
                    os.close(fd)
                fixes.append(f"cleared a SafeSetID {kind} policy someone wrote: {policy[:200]!r}")
        except OSError:
            pass
    # Android registers handlers for ARM programs with the kernel, and those registrations are global: every ARM
    # program run anywhere in the VM would be handed to an Android path. They are switched off (not removed).
    try:
        names = os.listdir("/proc/sys/fs/binfmt_misc")
    except OSError:
        names = []
    for name in names:
        if name in ("register", "status"):
            continue
        entry = read_text(f"/proc/sys/fs/binfmt_misc/{name}") or ""
        m = re.search(r"^interpreter (\S+)", entry, re.M)
        if entry.startswith("enabled") and m and m.group(1).startswith(("/system/", "/vendor/", "/apex/")):
            try:
                with open(f"/proc/sys/fs/binfmt_misc/{name}", "w") as f:
                    f.write("0")
                fixes.append(f"switched off Android's handler for ARM programs, {name} ({m.group(1)})")
            except OSError:
                pass
    for pid in nested_named({b"llkd"}):
        try:
            os.kill(pid, signal.SIGKILL)
            fixes.append(f"ended Android's livelock daemon llkd (pid {pid}), which can crash the kernel")
        except OSError:
            pass
    if os.path.isdir("/sys/fs/selinux/class") and os.listdir("/sys/fs/selinux/class"):
        fixes.append("WARNING: an SELinux policy is loaded in the VM's kernel; restart the Terminal app to clear it")
    # Device-mapper entries live in the VM's shared kernel. Android's package manager names them after its packages;
    # Forge's images never let it make them, so any that exist were left by an Android that got past that, and they
    # make the next Android's packages fail to mount. They hold no data; they are reported, not removed.
    left = []
    try:
        for dm in os.listdir("/sys/block"):
            if dm.startswith("dm-") and (read_text(f"/sys/block/{dm}/dm/name") or "").strip().startswith("com.android."):
                left.append(dm)
    except OSError:
        pass
    if left:
        fixes.append(f"WARNING: {len(left)} device-mapper entries named after Android's packages are in the VM's kernel; "
                     "they hold no data and are gone after the Terminal app restarts")


def android_running() -> bool:
    return any(True for _ in namespace_inits())


def any_container_android() -> bool:
    """Any Android container at all, this guard's or not. Only read, so the reference of the VM's settings is taken
    while no Android anywhere has changed them."""
    return any(True for _ in namespace_inits(scoped=False))


def shield() -> None:
    hold_locks()
    proc_ref = proc_reference()
    ref = capture()
    last_android = -1e9
    last_full = 0.0
    last_ref = time.monotonic()
    logged = {}
    while True:
        try:
            now = time.monotonic()
            running = android_running()
            if running:
                last_android = now
            active = running or now - last_android < 60
            fixes = []
            if active:
                # Only while one of this guard's Androids runs (or ran in the last minute): the shared /proc files,
                # settings and hazards an Android elsewhere changes are left to that Android's own guard.
                reassert_proc(proc_ref, fixes)
                hold_locks()
                restore_sysctls(ref, fixes)
                restore_values(ref, fixes)
                restore_perms(ref["fast"], TREES_FAST, fixes)
                if now - last_full >= 10:
                    restore_perms(ref["full"], TREES_FULL, fixes)
                    tracefs_rule(fixes)
                    last_full = now
                hazards(fixes)
            else:
                if now - last_ref >= 300:
                    # Refresh the reference so your own changes are kept, but only if no Android started while it
                    # was being taken (Android changes things within its first second).
                    if not any_container_android():
                        hold_locks()
                        new_ref = capture()
                        if not any_container_android():
                            ref = new_ref
                    last_ref = now
            unlogged = [f for f in fixes if logged.get(f, -1e9) < now - 600]   # the same fix is logged at most every ten minutes
            for fix in unlogged[:15]:
                log(fix)
            if len(unlogged) > 15:
                log(f"and {len(unlogged) - 15} more like these")
            for fix in unlogged:
                logged[fix] = now
            if unlogged:   # the status file follows the log, so a standing warning does not crowd out the fixes
                recent.extend({"time": time.time(), "fix": f} for f in unlogged)
                del recent[:-50]
            write_status("shield.json", {"time": time.time(), "android_running": bool(running), "active": active,
                                         "reference_time": ref["time"], "recent": recent[-20:],
                                         "wake_lock": WAKE_LOCK, "hibernation_blocked": _secret_fd >= 0})
            if wake.wait(2 if active else 5):
                wake.clear()   # an Android init just started: the next round checks at once whether it is this guard's
        except Exception as e:  # noqa: BLE001 (the shield must keep running)
            log(f"shield check failed: {e!r}")
            time.sleep(5)


def main() -> None:
    if sys.argv[1:] == ["--check"]:   # a bounded test: take the reference once, report, change nothing
        t0 = time.monotonic()
        ref = capture()
        fixes = []
        print(json.dumps({"seconds": round(time.monotonic() - t0, 2), "settings": len(ref["sysctl"]),
                          "values": ref["values"], "files": len(ref["fast"]) + len(ref["full"]),
                          "rss_kb": int(open("/proc/self/statm").read().split()[1]) * 4,
                          "android_running": android_running()}, indent=1))
        return
    threading.Thread(target=shield, name="shield", daemon=True).start()
    watch_kmsg()


if __name__ == "__main__":
    main()
