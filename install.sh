#!/bin/bash
# Installs Forge IDE for the current user: launcher in ~/.local/bin, a systemd user socket that starts the Forge
# server when the Forge app connects (Quit Forge stops it completely), and the tuned forge-coder model in Ollama. Safe to re-run. Nothing is deleted.
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
mkdir -p ~/.local/bin ~/.config/systemd/user
ln -sf "$HERE/bin/forge-ide" ~/.local/bin/forge-ide
sed "s|%h/projects/forge-ide|$HERE|" "$HERE/systemd/forge-ide.service" > ~/.config/systemd/user/forge-ide.service
cp "$HERE/systemd/forge-ide.socket" ~/.config/systemd/user/forge-ide.socket
systemctl --user daemon-reload
systemctl --user disable forge-ide.service >/dev/null 2>&1 || true
systemctl --user enable forge-ide.socket >/dev/null
if command -v ollama >/dev/null 2>&1 && ! ollama list 2>/dev/null | grep -q '^forge-coder'; then
  echo "Building the forge-coder model..."
  ( cd "$HERE/model" && ollama create forge-coder -f Modelfile )
fi
systemctl --user stop forge-ide.service >/dev/null 2>&1 || true
systemctl --user restart forge-ide.socket
echo "Forge IDE installed. Build and install the Forge app (android/build.sh, then adb install -r android/Forge.apk) and open it on the Googlebook."
echo "Commands: forge-ide status | stop | start | log | open FOLDER"
