#!/bin/sh
set -eu

state_directory="${XDG_STATE_HOME:-$HOME/.local/state}/audio-matrix"
previous_file="$state_directory/previous-system-sink"
sink_name=audiomatrix_system
backup_file=

. /opt/audiomatrix/lib/systemd/user/pipewire-sink.sh

if [ ! -r "$previous_file" ]; then
    echo "Saved physical output is missing; refusing to restart Audio Matrix services." >&2
    exit 1
fi
current_sink=$(audiomatrix_default_sink)
if [ "$current_sink" != "$sink_name" ]; then
    echo "The selected output is $current_sink, not $sink_name; refusing to change it." >&2
    exit 1
fi
IFS= read -r previous_sink < "$previous_file"
if [ -z "$previous_sink" ] || [ "$previous_sink" = "$sink_name" ]; then
    echo "The saved physical output is invalid; refusing to restart Audio Matrix services." >&2
    exit 1
fi

backup_file="$previous_file.safe-restart.$$"
cp -p "$previous_file" "$backup_file"
restore_saved_output() {
    result=$?
    if [ -e "$backup_file" ]; then mv -f "$backup_file" "$previous_file"; fi
    exit "$result"
}
trap restore_saved_output EXIT HUP INT TERM

# The currently running legacy service restores its saved physical output on exit.
# Temporarily point that cleanup back to the active virtual sink during the restart.
printf '%s\n' "$sink_name" > "$previous_file"
systemctl --user restart audiomatrix-system-sink.service
mv -f "$backup_file" "$previous_file"
backup_file=
systemctl --user restart audiomatrix.service
trap - EXIT HUP INT TERM
printf 'Audio Matrix services restarted. The selected output remains %s; saved output settings were restored.\n' "$sink_name"
