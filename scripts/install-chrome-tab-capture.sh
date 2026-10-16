#!/bin/sh
set -eu

data_home=${XDG_DATA_HOME:-$HOME/.local/share}
config_home=${XDG_CONFIG_HOME:-$HOME/.config}
state_home=${XDG_STATE_HOME:-$HOME/.local/state}
extension_id=hkhjefdofjmbfkdmhmjpdfkeijccnkgi
host_name=io.github.ilyaandreev05.audiomatrix.chrome_tab_capture
target_extension="$data_home/audio-matrix/chrome-tab-capture"
target_host="$config_home/google-chrome/NativeMessagingHosts/$host_name.json"
state_dir="$state_home/audio-matrix/chrome-tab-capture"
extension_backup="$target_extension.pre-audiomatrix"
host_backup="$target_host.pre-audiomatrix"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
package_extension=/opt/audiomatrix/lib/chrome/tab-capture
package_host=/opt/audiomatrix/lib/chrome/native-host/audio-matrix-tab-capture.py
source_extension=${AM_CHROME_EXTENSION_SOURCE:-$package_extension}
source_host=${AM_CHROME_NATIVE_HOST_SOURCE:-$package_host}

if [ ! -r "$source_extension/manifest.json" ] || [ ! -r "$source_extension/service-worker.js" ]; then
    repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
    source_extension="$repo_root/chrome/tab-capture"
fi
if [ ! -r "$source_host" ]; then
    repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
    source_host="$repo_root/chrome/native-host/audio-matrix-tab-capture.py"
fi
if [ ! -r "$source_extension/manifest.json" ] || [ ! -r "$source_host" ]; then
    echo "Audio Matrix Chrome tab capture files are missing." >&2
    exit 1
fi
if [ -e "$state_dir/extension-state" ] || [ -e "$state_dir/host-state" ]; then
    echo "A Chrome tab capture backup already exists; run uninstall-chrome-tab-capture.sh before replacing it." >&2
    exit 1
fi
if [ -e "$extension_backup" ] || [ -e "$host_backup" ]; then
    echo "A Chrome tab capture recovery path already exists; inspect it before continuing." >&2
    exit 1
fi

mkdir -p "$(dirname "$target_extension")" "$(dirname "$target_host")" "$state_dir"
if [ -e "$target_extension" ]; then mv "$target_extension" "$extension_backup"; extension_state=present; else extension_state=absent; fi
if [ -e "$target_host" ]; then mv "$target_host" "$host_backup"; host_state=present; else host_state=absent; fi
tmp_extension="$target_extension.install.$$"
tmp_host="$target_host.install.$$"
restore_on_error() {
    result=$?
    if [ "$result" -ne 0 ]; then
        rm -rf -- "$tmp_extension" "$tmp_host"
        rm -rf -- "$target_extension"
        rm -f -- "$target_host"
        if [ -e "$extension_backup" ]; then mv "$extension_backup" "$target_extension"; fi
        if [ -e "$host_backup" ]; then mv "$host_backup" "$target_host"; fi
        rm -f -- "$state_dir/extension-state" "$state_dir/host-state"
    fi
    exit "$result"
}
trap restore_on_error EXIT HUP INT TERM
mkdir "$tmp_extension"
cp -R "$source_extension/." "$tmp_extension/"
mkdir -p "$(dirname "$tmp_host")"
python3 - "$tmp_host" "$source_host" "$extension_id" "$host_name" <<'PY'
import json
import os
import sys

destination, host_path, extension_id, host_name = sys.argv[1:]
with open(destination, "w", encoding="utf-8") as output:
    json.dump({
        "name": host_name,
        "description": "Audio Matrix Chrome tab capture PCM bridge",
        "path": os.path.abspath(host_path),
        "type": "stdio",
        "allowed_origins": [f"chrome-extension://{extension_id}/"],
    }, output, indent=2)
    output.write("\n")
PY
mv "$tmp_extension" "$target_extension"
mv "$tmp_host" "$target_host"
printf '%s\n' "$extension_state" > "$state_dir/extension-state"
printf '%s\n' "$host_state" > "$state_dir/host-state"
trap - EXIT HUP INT TERM
printf 'Installed extension files at %s and its Chrome Native Messaging host.\n' "$target_extension"
printf 'Load the unpacked extension from chrome://extensions. It is not enabled or started by this script.\n'
