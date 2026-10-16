#!/bin/sh
set -eu

unit_source=/opt/audiomatrix/lib/systemd/user
sink_config_source=/opt/audiomatrix/lib/pipewire/pipewire.conf.d/90-audiomatrix-system-sink.conf
unit_directory="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
config_directory="${XDG_CONFIG_HOME:-$HOME/.config}/pipewire/pipewire.conf.d"
state_directory="${XDG_STATE_HOME:-$HOME/.local/state}/audio-matrix"
engine_target="$unit_directory/audiomatrix.service"
sink_target="$unit_directory/audiomatrix-system-sink.service"
config_target="$config_directory/90-audiomatrix-system-sink.conf"
previous_sink_file="$state_directory/previous-system-sink"

. /opt/audiomatrix/lib/systemd/user/pipewire-sink.sh

if [ ! -r "$unit_source/audiomatrix.service" ] || [ ! -r "$unit_source/audiomatrix-system-sink.service" ] || [ ! -r "$sink_config_source" ]; then
    echo "Audio Matrix engine service files are missing. Install the Audio Matrix package first." >&2
    exit 1
fi

mkdir -p "$unit_directory" "$config_directory" "$state_directory"
if [ ! -r "$previous_sink_file" ]; then
    previous_sink=$(audiomatrix_default_sink)
    if [ -z "$previous_sink" ] || [ "$previous_sink" = audiomatrix_system ]; then
        echo "Could not save the current physical PipeWire output." >&2
        exit 1
    fi
    umask 077
    printf '%s\n' "$previous_sink" > "$previous_sink_file"
fi

backup_unit() {
    source=$1 target=$2 state_key=$3
    state_file="$state_directory/$state_key"
    if [ ! -e "$state_file" ]; then
        if [ -e "$target" ]; then
            backup="$target.backup.$(date +%Y%m%d%H%M%S)"
            cp -p "$target" "$backup"
            printf '%s\n' "$backup" > "$state_file"
            echo "Saved previous user unit to $backup"
        else
            printf '%s\n' "-" > "$state_file"
        fi
    fi
    install -m 0644 "$source" "$target"
}

backup_unit "$unit_source/audiomatrix.service" "$engine_target" engine-unit-backup
backup_unit "$unit_source/audiomatrix-system-sink.service" "$sink_target" sink-unit-backup
if [ ! -e "$state_directory/pipewire-config-backup" ]; then
    if [ -e "$config_target" ]; then
        backup="$config_target.backup.$(date +%Y%m%d%H%M%S)"
        cp -p "$config_target" "$backup"
        printf '%s\n' "$backup" > "$state_directory/pipewire-config-backup"
        echo "Saved previous PipeWire drop-in to $backup"
    else
        printf '%s\n' "-" > "$state_directory/pipewire-config-backup"
    fi
fi
install -m 0644 "$sink_config_source" "$config_target"

# A manually launched instance must hand off its graph before systemd starts the engine.
/opt/audiomatrix/bin/AudioMatrix --quit
systemctl --user daemon-reload
systemctl --user restart pipewire.service
systemctl --user enable --now audiomatrix.service
