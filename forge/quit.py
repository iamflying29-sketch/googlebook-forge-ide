"""Quit Forge: stop everything Forge runs in the Linux VM, so nothing of Forge is left running afterwards.

The order matters. Chat replies are cancelled first. Virtual devices are stopped while the Forge Android guard still
protects the VM (an Android that shuts down is the moment the guard is for). Then every process Forge started in its
own systemd scope (language servers, debuggers, notebook kernels, builds, device displays, ARM devices) is stopped,
Ollama drops its models and stops, the guard stops, and last the Forge server stops itself. Its socket stays with
systemd (not a Forge process), so opening the Forge app starts the server again.

Every command has a time limit, and a step that fails is reported and does not stop the steps after it.
"""
import os
import subprocess
import threading
import time
from typing import Callable, List

from . import vdevice


def _run(argv: List[str], timeout: float = 30) -> subprocess.CompletedProcess:
    return subprocess.run(argv, capture_output=True, text=True, timeout=timeout)


def _forge_user_units() -> List[str]:
    """Running user units Forge started (all named forge-...), except the server and its socket."""
    try:
        r = _run(["systemctl", "--user", "list-units", "--plain", "--no-legend", "--state=active,activating,deactivating",
                  "forge-*"], timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        return []
    units = []
    for line in r.stdout.splitlines():
        name = line.split()[0] if line.split() else ""
        if name.startswith("forge-") and name not in ("forge-ide.service", "forge-ide.socket"):
            units.append(name)
    return units


def stop_everything(cancel_runs: Callable[[], int], unload_models: Callable[[], None]) -> List[str]:
    """Stops all of Forge in the VM except the server itself. Returns one line per step, for the app to show."""
    done: List[str] = []

    def step(label: str, fn: Callable[[], str]) -> None:
        try:
            done.append(fn() or label)
        except Exception as e:  # noqa: BLE001 (one failed step must not keep the others from running)
            done.append(f"{label}: failed ({e})")

    step("Chat replies", lambda: f"Stopped {cancel_runs()} running chat replies.")

    def devices() -> str:
        vdevice.stop_all()
        return "Stopped the virtual devices."
    step("Virtual devices", devices)

    def containers() -> str:
        # Anything left of Forge's devices (a container from a device whose definition was removed, for example).
        r = _run(["sudo", "-n", "podman", "ps", "--filter", "label=forge.vd", "--format", "{{.Names}}"], timeout=30)
        names = [n for n in r.stdout.split() if n]
        for n in names:
            _run(["sudo", "-n", "podman", "stop", "-t", "20", n], timeout=60)
        return f"Stopped {len(names)} leftover device containers." if names else "No device containers were left."
    step("Device containers", containers)

    def units() -> str:
        names = _forge_user_units()
        if names:
            _run(["systemctl", "--user", "stop"] + names, timeout=60)
        return f"Stopped {len(names)} Forge helpers (language servers, kernels, builds and displays)." if names else "No Forge helpers were running."
    step("Helpers", units)

    def ollama() -> str:
        unload_models()
        r = _run(["sudo", "-n", "systemctl", "stop", "ollama.service"], timeout=60)
        return "Ollama dropped its models and stopped." if r.returncode == 0 else "Ollama could not be stopped: " + r.stderr.strip()[-200:]
    step("Ollama", ollama)

    def guard() -> str:
        r = _run(["sudo", "-n", "systemctl", "stop", "forge-android-guard.service"], timeout=30)
        return "The Forge Android guard stopped." if r.returncode == 0 else "The Forge Android guard could not be stopped: " + r.stderr.strip()[-200:]
    step("Guard", guard)
    return done


def stop_server_soon(delay: float = 1.0) -> None:
    """The server stops itself a moment after it has answered (systemd ends every process in its group)."""
    def later() -> None:
        time.sleep(delay)
        try:
            subprocess.Popen(["systemctl", "--user", "stop", "--no-block", "forge-ide.service"],
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True)
        except OSError:
            os._exit(0)
    threading.Thread(target=later, name="quit", daemon=True).start()


def start_companions() -> List[str]:
    """When the server starts: Ollama and the Forge Android guard, which Quit Forge stopped, come back."""
    out = []
    for unit in ("ollama.service", "forge-android-guard.service"):
        try:
            if _run(["systemctl", "is-active", "--quiet", unit], timeout=10).returncode != 0:
                r = _run(["sudo", "-n", "systemctl", "start", unit], timeout=60)
                out.append(f"started {unit}" if r.returncode == 0 else f"could not start {unit}: {r.stderr.strip()[-200:]}")
        except (OSError, subprocess.TimeoutExpired) as e:
            out.append(f"could not start {unit}: {e}")
    return out
