#!/bin/sh
set -eu

data_home=${XDG_DATA_HOME:-$HOME/.local/share}
config_home=${XDG_CONFIG_HOME:-$HOME/.config}
state_home=${XDG_STATE_HOME:-$HOME/.local/state}
host_name=io.github.ilyaandreev05.audiomatrix.chrome_tab_capture
target_extension="$data_home/audio-matrix/chrome-tab-capture"
target_host="$config_home/google-chrome/NativeMessagingHosts/$host_name.json"
state_dir="$state_home/audio-matrix/chrome-tab-capture"
extension_backup="$target_extension.pre-audiomatrix"
host_backup="$target_host.pre-audiomatrix"
extension_removed="$target_extension.audiomatrix-removed"
host_removed="$target_host.audiomatrix-removed"

if [ ! -r "$state_dir/extension-state" ] || [ ! -r "$state_dir/host-state" ]; then
    echo "No saved Chrome tab capture installation state was found." >&2
    exit 1
fi
IFS= read -r extension_state < "$state_dir/extension-state"
IFS= read -r host_state < "$state_dir/host-state"
if [ "$extension_state" = present ] && [ ! -d "$extension_backup" ]; then echo "The previous extension backup is missing: $extension_backup" >&2; exit 1; fi
if [ "$host_state" = present ] && [ ! -r "$host_backup" ]; then echo "The previous Native Messaging host backup is missing: $host_backup" >&2; exit 1; fi
if [ -e "$extension_removed" ] || [ -e "$host_removed" ]; then echo "A Chrome tab capture recovery path already exists." >&2; exit 1; fi

if [ -e "$target_extension" ]; then mv "$target_extension" "$extension_removed"; fi
if [ -e "$target_host" ]; then mv "$target_host" "$host_removed"; fi
if [ "$extension_state" = present ]; then mv "$extension_backup" "$target_extension"; fi
if [ "$host_state" = present ]; then mv "$host_backup" "$target_host"; fi
rm -f -- "$state_dir/extension-state" "$state_dir/host-state"
rmdir "$state_dir" 2>/dev/null || true
printf 'Restored prior extension and Native Messaging host files.\n'
printf 'Remove the manually loaded extension from chrome://extensions; the replaced files are preserved at %s and %s.\n' "$extension_removed" "$host_removed"
