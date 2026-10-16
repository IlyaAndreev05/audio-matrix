#!/bin/sh
set -eu

unit_directory="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
target_unit="$unit_directory/audiomatrix-events.service"

systemctl --user disable --now audiomatrix-events.service || true
rm -f "$target_unit"
systemctl --user daemon-reload
systemctl --user reset-failed audiomatrix-events.service || true
