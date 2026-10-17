#!/bin/sh
set -eu

unit_directory="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
config_directory="${XDG_CONFIG_HOME:-$HOME/.config}/pipewire/pipewire.conf.d"
state_directory="${XDG_STATE_HOME:-$HOME/.local/state}/audio-matrix"
previous_sink_file="$state_directory/previous-system-sink"
previous_sink=

. /opt/audiomatrix/lib/systemd/user/pipewire-sink.sh

if [ -e "$config_directory/90-audiomatrix-system-sink.conf" ] && [ ! -r "$previous_sink_file" ]; then
    echo "Saved pre-Audio Matrix output is missing. Keeping the virtual input active to avoid an unsafe fallback." >&2
    exit 1
fi
if [ -r "$previous_sink_file" ]; then
    IFS= read -r previous_sink < "$previous_sink_file"
    if [ -z "$previous_sink" ] || [ "$previous_sink" = audiomatrix_system ]; then
        echo "Saved pre-Audio Matrix output is invalid. Keeping the virtual input active." >&2
        exit 1
    fi
    if ! audiomatrix_sink_exists "$previous_sink"; then
        echo "Saved output $previous_sink is unavailable. Reconnect it before disabling the virtual input." >&2
        exit 1
    fi
fi

systemctl --user disable --now audiomatrix.service || true
systemctl --user stop audiomatrix-system-sink.service || true
if [ -n "$previous_sink" ]; then audiomatrix_set_default_sink "$previous_sink"; fi

restore_or_remove() {
    target=$1 state_key=$2
    state_file="$state_directory/$state_key"
    if [ -r "$state_file" ]; then
        IFS= read -r saved < "$state_file"
        if [ "$saved" = "-" ]; then rm -f "$target"
        elif [ -e "$saved" ]; then mv -f "$saved" "$target"
        else echo "Saved backup $saved is missing; refusing to remove $target" >&2; exit 1
        fi
        rm -f "$state_file"
    fi
}

restore_or_remove "$unit_directory/audiomatrix.service" engine-unit-backup
restore_or_remove "$unit_directory/audiomatrix-system-sink.service" sink-unit-backup
restore_or_remove "$config_directory/90-audiomatrix-system-sink.conf" pipewire-config-backup
systemctl --user daemon-reload
systemctl --user restart pipewire.service
if [ -n "$previous_sink" ]; then audiomatrix_set_default_sink "$previous_sink"; fi
systemctl --user reset-failed audiomatrix.service || true
systemctl --user reset-failed audiomatrix-system-sink.service || true
rm -f "$previous_sink_file"
