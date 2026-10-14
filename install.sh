#!/bin/bash
# Installs Forge IDE for the current user: launcher in ~/.local/bin, a systemd user service that starts
# with the VM, and the tuned forge-coder model in Ollama. Safe to re-run. Nothing is deleted.
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
if [ ! -f "$HERE/static/vendor/monaco/vs/loader.js" ]; then
  echo "Downloading the editor libraries (Monaco, xterm.js, marked, DOMPurify)..."
  "$HERE/static/fetch-vendor.sh"
fi
mkdir -p ~/.local/bin ~/.config/systemd/user
ln -sf "$HERE/bin/forge-ide" ~/.local/bin/forge-ide
sed "s|%h/projects/forge-ide|$HERE|" "$HERE/systemd/forge-ide.service" > ~/.config/systemd/user/forge-ide.service
systemctl --user daemon-reload
systemctl --user enable forge-ide.service >/dev/null
if command -v ollama >/dev/null 2>&1 && ! ollama list 2>/dev/null | grep -q '^forge-coder'; then
  echo "Building the forge-coder model..."
  ( cd "$HERE/model" && ollama create forge-coder -f Modelfile )
fi
systemctl --user restart forge-ide.service
echo "Forge IDE installed. Open http://localhost:${FORGE_PORT:-8765} in Chrome."
echo "Commands: forge-ide status | stop | start | log | open FOLDER"
