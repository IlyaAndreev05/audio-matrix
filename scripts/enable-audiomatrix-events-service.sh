#!/bin/sh
set -eu

source_unit=/opt/audiomatrix/lib/systemd/user/audiomatrix-events.service
unit_directory="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
target_unit="$unit_directory/audiomatrix-events.service"

if [ ! -r "$source_unit" ]; then
    printf '%s\n' "Audio Matrix package unit is missing: $source_unit" >&2
    exit 1
fi
if ! command -v pw-loopback >/dev/null 2>&1; then
    printf '%s\n' 'pw-loopback is not installed; no service was enabled.' >&2
    exit 1
fi

mkdir -p "$unit_directory"
install -m 0644 "$source_unit" "$target_unit"
systemctl --user daemon-reload
systemctl --user enable --now audiomatrix-events.service
