#!/usr/bin/python3 -I
"""Forge Android guard: protects the Linux VM from Android shutting itself down inside a container.

When Android powers off or restarts, its init can write to /proc/sysrq-trigger: "e" and "i" signal every process in
the whole VM, "u" remounts every file system read-only, and "c" (after a shutdown that takes too long) crashes the
VM's kernel, which closes the Terminal app. A container cannot hide that file, because Android mounts its own /proc,
and this kernel cannot run BPF security programs. Android's init logs to the kernel log, though, and its first
shutdown message comes before any of those steps.

This service (run as root by systemd) reads the kernel log as it is written. When an Android init in a container
announces a power off or restart, the guard ends that Android at once with SIGKILL to its init (which ends the whole
container), long before init gets to the dangerous steps. Forge stops its virtual devices from outside, so a normal
stop never involves Android's own shutdown. The guard holds nothing and never writes anything except its log and a
short status file for Forge."""
import errno
import json
import os
import re
import signal
import sys
import time

PATTERN = re.compile(rb"\binit: (Received sys\.powerctl=|Reboot start|InitFatalReboot|Got shutdown_command)")
STATUS_DIR = "/run/forge-android-guard"


def log(text: str) -> None:
    sys.stdout.write(time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n")
    sys.stdout.flush()


def container_inits():
    """PIDs of every process named init that is PID 1 of a nested PID namespace (Android in a container)."""
    for name in os.listdir("/proc"):
        if not name.isdigit():
            continue
        try:
            with open(f"/proc/{name}/comm", "rb") as f:
                if f.read().strip() != b"init":
                    continue
            with open(f"/proc/{name}/status", "rb") as f:
                for line in f:
                    if line.startswith(b"NSpid:"):
                        ids = line.split()[1:]
                        if len(ids) >= 2 and ids[-1] == b"1":
                            yield int(name)
                        break
        except OSError:
            continue


def act(message: bytes) -> None:
    killed = []
    for pid in container_inits():
        try:
            os.kill(pid, signal.SIGKILL)
            killed.append(pid)
        except OSError:
            pass
    text = message.decode("utf-8", "replace").strip()[:300]
    log(f"Android began to shut down ({text}); ended container init {killed}")
    try:
        os.makedirs(STATUS_DIR, exist_ok=True)
        with open(os.path.join(STATUS_DIR, "last.json.new"), "w") as f:
            json.dump({"time": time.time(), "message": text, "killed": killed}, f)
        os.chmod(os.path.join(STATUS_DIR, "last.json.new"), 0o644)
        os.replace(os.path.join(STATUS_DIR, "last.json.new"), os.path.join(STATUS_DIR, "last.json"))
    except OSError:
        pass


def main() -> None:
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


if __name__ == "__main__":
    main()
