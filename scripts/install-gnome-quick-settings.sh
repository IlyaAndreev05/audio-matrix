#!/bin/sh
set -eu

uuid=audio-matrix-quick-settings@ilyaandreev05
data_home=${XDG_DATA_HOME:-$HOME/.local/share}
state_home=${XDG_STATE_HOME:-$HOME/.local/state}
target="$data_home/gnome-shell/extensions/$uuid"
state_dir="$state_home/audio-matrix/gnome-extension"
backup="$target.pre-audiomatrix"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
package_source="/opt/audiomatrix/lib/gnome-shell/extensions/$uuid"
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
source_dir=${AUDIO_MATRIX_GNOME_EXTENSION_SOURCE:-$package_source}
if [ -z "${AUDIO_MATRIX_GNOME_EXTENSION_SOURCE:-}" ] && [ ! -r "$source_dir/extension.js" ]; then
    source_dir="$repo_root/gnome/audio-matrix-quick-settings"
fi

if [ ! -r "$source_dir/extension.js" ] || [ ! -r "$source_dir/metadata.json" ]; then
    echo "Audio Matrix GNOME extension files are missing." >&2
    exit 1
fi
mkdir -p "$(dirname "$target")" "$state_dir"
upgrade_backup=
if [ -r "$state_dir/previous-state" ]; then
    IFS= read -r previous_state < "$state_dir/previous-state"
    IFS= read -r enabled_state < "$state_dir/previous-enabled" 2>/dev/null || enabled_state=unknown
    if [ "$previous_state" = present ] && [ ! -d "$backup" ]; then
        echo "The original GNOME extension backup is missing: $backup" >&2
        exit 1
    fi
    if [ -e "$target" ]; then
        installed_uuid=$(sed -n 's/.*"uuid"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$target/metadata.json" 2>/dev/null || true)
        if [ "$installed_uuid" != "$uuid" ]; then
            echo "Refusing to replace a directory that is not the saved Audio Matrix extension: $target" >&2
            exit 1
        fi
        old_version=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$target/metadata.json")
        upgrade_backup="$target.audiomatrix-replaced-${old_version:-unknown}-$$"
        if [ -e "$upgrade_backup" ]; then
            echo "Recovery path already exists: $upgrade_backup" >&2
            exit 1
        fi
    fi
else
    if [ -e "$backup" ]; then
        echo "Backup path already exists: $backup" >&2
        exit 1
    fi
    enabled_state=unknown
    if command -v gnome-extensions >/dev/null 2>&1; then
        if gnome-extensions list --enabled | grep -Fxq "$uuid"; then enabled_state=enabled; else enabled_state=disabled; fi
    fi
    if [ -e "$target" ]; then
        mv "$target" "$backup"
        previous_state=present
    else
        previous_state=absent
    fi
fi
tmp="$target.install.$$"
restore_on_error() {
    result=$?
    if [ "$result" -ne 0 ]; then
        rm -rf -- "$tmp"
        if [ -n "$upgrade_backup" ] && [ -e "$upgrade_backup" ]; then
            rm -rf -- "$target"
            mv "$upgrade_backup" "$target"
        elif [ -e "$backup" ]; then
            rm -rf -- "$target"
            mv "$backup" "$target"
        fi
    fi
    exit "$result"
}
trap restore_on_error EXIT HUP INT TERM
mkdir "$tmp"
install -m 0644 "$source_dir/metadata.json" "$tmp/metadata.json"
for source_file in "$source_dir"/*.js; do
    install -m 0644 "$source_file" "$tmp/$(basename "$source_file")"
done
if [ -n "$upgrade_backup" ]; then mv "$target" "$upgrade_backup"; fi
mv "$tmp" "$target"
if [ ! -r "$state_dir/previous-state" ]; then
    printf '%s\n' "$previous_state" > "$state_dir/previous-state"
    printf '%s\n' "$enabled_state" > "$state_dir/previous-enabled"
fi
trap - EXIT HUP INT TERM
recovery_path=${upgrade_backup:-$backup}
printf 'Installed %s. The previous extension is backed up at %s.\n' "$uuid" "$recovery_path"
printf 'This script does not enable the extension. Enable it with: gnome-extensions enable %s\n' "$uuid"
