#!/bin/sh
set -eu

uuid=audio-matrix-quick-settings@ilyaandreev05
data_home=${XDG_DATA_HOME:-$HOME/.local/share}
state_home=${XDG_STATE_HOME:-$HOME/.local/state}
target="$data_home/gnome-shell/extensions/$uuid"
backup="$target.pre-audiomatrix"
removed="$target.audiomatrix-removed"
state_dir="$state_home/audio-matrix/gnome-extension"
state_file="$state_dir/previous-state"
enabled_file="$state_dir/previous-enabled"

if [ ! -r "$state_file" ]; then
    echo "No saved Audio Matrix extension installation state was found." >&2
    exit 1
fi
IFS= read -r previous_state < "$state_file"
IFS= read -r previous_enabled < "$enabled_file" 2>/dev/null || previous_enabled=unknown
if [ "$previous_state" = present ] && [ ! -d "$backup" ]; then
    echo "The previous extension backup is missing: $backup" >&2
    exit 1
fi
if [ -e "$removed" ]; then
    echo "Recovery path already exists: $removed" >&2
    exit 1
fi

if [ -e "$target" ]; then mv "$target" "$removed"; fi
if [ "$previous_state" = present ]; then mv "$backup" "$target"; fi

if command -v gnome-extensions >/dev/null 2>&1; then
    currently_enabled=false
    if gnome-extensions list --enabled | grep -Fxq "$uuid"; then currently_enabled=true; fi
    if [ "$previous_enabled" = enabled ] && [ "$currently_enabled" = false ]; then gnome-extensions enable "$uuid" || true; fi
    if [ "$previous_enabled" = disabled ] && [ "$currently_enabled" = true ]; then gnome-extensions disable "$uuid" || true; fi
fi

rm -f -- "$state_file" "$enabled_file"
rmdir "$state_dir" 2>/dev/null || true
printf 'Restored the prior %s extension state. The replaced version is preserved at %s.\n' "$uuid" "$removed"
printf 'On Wayland, sign out and back in if GNOME Shell does not reload the restored files.\n'
