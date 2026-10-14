#!/bin/bash
# Downloads the open-source browser libraries Forge uses into static/vendor so the IDE works offline:
# Monaco editor (MIT), xterm.js and its addons (MIT), marked (MIT) and DOMPurify (Apache 2.0 or MPL 2.0).
# Nothing is deleted; re-running simply overwrites the files.
set -euo pipefail
cd "$(dirname "$0")"
V=vendor
T=$(mktemp -d -p "${TMPDIR:-/tmp}" forge-vendor.XXXXXX)
mkdir -p "$V/monaco" "$V/xterm" "$V/marked" "$V/dompurify"
get() { curl -sfL -o "$T/$2" "https://registry.npmjs.org/$1"; mkdir -p "$T/${2%.tgz}"; tar -xzf "$T/$2" -C "$T/${2%.tgz}"; }
get monaco-editor/-/monaco-editor-0.52.2.tgz monaco.tgz
get @xterm/xterm/-/xterm-5.5.0.tgz xterm.tgz
get @xterm/addon-fit/-/addon-fit-0.10.0.tgz fit.tgz
get @xterm/addon-web-links/-/addon-web-links-0.11.0.tgz links.tgz
get marked/-/marked-15.0.7.tgz marked.tgz
get dompurify/-/dompurify-3.2.4.tgz purify.tgz
cp -r "$T/monaco/package/min/vs" "$V/monaco/"
cp "$T/xterm/package/lib/xterm.js" "$T/xterm/package/css/xterm.css" "$V/xterm/"
cp "$T/fit/package/lib/addon-fit.js" "$T/links/package/lib/addon-web-links.js" "$V/xterm/"
cp "$T/marked/package/marked.min.js" "$V/marked/"
cp "$T/purify/package/dist/purify.min.js" "$V/dompurify/"
echo "Vendor libraries are in $(pwd)/$V"
