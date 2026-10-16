#!/bin/bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)
extension="$repo_root/chrome/tab-capture"
for file in popup.js service-worker.js offscreen.js pcm-worklet.js; do node --check "$extension/$file"; done
python3 "$repo_root/chrome/native-host/test_host.py"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/audio-matrix-chrome-capture.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT HUP INT TERM
export XDG_DATA_HOME="$tmp/data"
export XDG_CONFIG_HOME="$tmp/config"
export XDG_STATE_HOME="$tmp/state"
export AM_CHROME_EXTENSION_SOURCE="$extension"
export AM_CHROME_NATIVE_HOST_SOURCE="$repo_root/chrome/native-host/audio-matrix-tab-capture.py"
mkdir -p "$XDG_DATA_HOME/audio-matrix/chrome-tab-capture" "$XDG_CONFIG_HOME/google-chrome/NativeMessagingHosts"
printf 'old extension\n' > "$XDG_DATA_HOME/audio-matrix/chrome-tab-capture/previous.txt"
printf '{"prior":true}\n' > "$XDG_CONFIG_HOME/google-chrome/NativeMessagingHosts/io.github.ilyaandreev05.audiomatrix.chrome_tab_capture.json"
"$script_dir/install-chrome-tab-capture.sh"
test -r "$XDG_DATA_HOME/audio-matrix/chrome-tab-capture/manifest.json"
test -r "$XDG_DATA_HOME/audio-matrix/chrome-tab-capture.pre-audiomatrix/previous.txt"
python3 - "$XDG_CONFIG_HOME/google-chrome/NativeMessagingHosts/io.github.ilyaandreev05.audiomatrix.chrome_tab_capture.json" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
assert manifest["allowed_origins"] == ["chrome-extension://hkhjefdofjmbfkdmhmjpdfkeijccnkgi/"]
assert manifest["path"].endswith("audio-matrix-tab-capture.py")
PY
"$script_dir/uninstall-chrome-tab-capture.sh"
test -r "$XDG_DATA_HOME/audio-matrix/chrome-tab-capture/previous.txt"
test "$(cat "$XDG_CONFIG_HOME/google-chrome/NativeMessagingHosts/io.github.ilyaandreev05.audiomatrix.chrome_tab_capture.json")" = '{"prior":true}'
test -r "$XDG_DATA_HOME/audio-matrix/chrome-tab-capture.audiomatrix-removed/manifest.json"
echo "Chrome tab capture bridge package/install/rollback tests passed (fake PipeWire process; no audio output)"
