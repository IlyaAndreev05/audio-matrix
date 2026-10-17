#!/bin/sh
set -eu

sink_name=audiomatrix_system
pipewire_sink_library="${AUDIOMATRIX_PIPEWIRE_SINK_LIBRARY:-/opt/audiomatrix/lib/systemd/user/pipewire-sink.sh}"
sleep_pid=

. "$pipewire_sink_library"

sink_exists() { audiomatrix_sink_exists "$1"; }

terminate() {
    if [ -n "$sleep_pid" ]; then kill "$sleep_pid" 2>/dev/null || true; fi
    exit 0
}

trap terminate HUP INT TERM
while :; do
    sink_rows=$(audiomatrix_sink_rows)
    current_sink=$(printf '%s\n' "$sink_rows" | awk -F '\t' '$3 == "1" { print $2; exit }')
    available_sink_id=$(printf '%s\n' "$sink_rows" | awk -F '\t' -v wanted="$sink_name" '$2 == wanted { print $1; exit }')
    if [ -n "$available_sink_id" ] && [ "$current_sink" != "$sink_name" ]; then
        wpctl set-default "$available_sink_id"
    fi
    sleep 3 &
    sleep_pid=$!
    wait "$sleep_pid" || true
    sleep_pid=
done
