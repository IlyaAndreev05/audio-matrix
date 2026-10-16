#!/bin/sh
set -eu

sink_name=audiomatrix_system
pipewire_sink_library="${AUDIOMATRIX_PIPEWIRE_SINK_LIBRARY:-/opt/audiomatrix/lib/systemd/user/pipewire-sink.sh}"
state_directory="${XDG_STATE_HOME:-$HOME/.local/state}/audio-matrix"
previous_file="$state_directory/previous-system-sink"
previous_sink=
sleep_pid=

. "$pipewire_sink_library"

sink_exists() { audiomatrix_sink_exists "$1"; }

read_state() {
    if [ -r "$previous_file" ]; then
        IFS= read -r previous_sink < "$previous_file"
    else
        previous_sink=$(audiomatrix_default_sink)
    fi
    [ -n "$previous_sink" ] || { echo "Could not determine the saved system output" >&2; exit 1; }
}

cleanup() {
    trap - EXIT
    current_sink=$(audiomatrix_default_sink 2>/dev/null || true)
    if [ "$current_sink" = "$sink_name" ]; then
        if sink_exists "$previous_sink"; then
            audiomatrix_set_default_sink "$previous_sink" || return 0
        else
            echo "Saved output $previous_sink is unavailable; keeping the virtual sink to avoid a hardware fallback" >&2
            return 0
        fi
    fi
}

terminate() {
    if [ -n "$sleep_pid" ]; then kill "$sleep_pid" 2>/dev/null || true; fi
    exit 0
}

read_state
trap terminate HUP INT TERM
trap cleanup EXIT
while ! sink_exists "$sink_name"; do
    sleep 1 &
    sleep_pid=$!
    wait "$sleep_pid" || true
    sleep_pid=
done
audiomatrix_set_default_sink "$sink_name"
while :; do
    sleep 1 &
    sleep_pid=$!
    wait "$sleep_pid" || true
    sleep_pid=
    if sink_exists "$sink_name"; then
        current_sink=$(audiomatrix_default_sink)
        if [ "$current_sink" != "$sink_name" ]; then audiomatrix_set_default_sink "$sink_name"; fi
    fi
done
