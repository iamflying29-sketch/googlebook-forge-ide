#!/bin/bash
# Installs the Forge Android guard as a root systemd service (root-owned copies, so the service never runs a file
# that a normal user could change). It is not started at boot: the Forge server starts it while Forge runs.
set -e
cd "$(dirname "$0")"
sudo install -d -o root -g root -m 755 /usr/local/lib/forge-guard
sudo install -o root -g root -m 644 forge_android_guard.py /usr/local/lib/forge-guard/forge_android_guard.py
sudo install -o root -g root -m 644 forge-android-guard.service /etc/systemd/system/forge-android-guard.service
sudo systemctl daemon-reload
sudo systemctl disable forge-android-guard.service 2>/dev/null || true
sudo systemctl restart forge-android-guard.service
systemctl is-active forge-android-guard.service
