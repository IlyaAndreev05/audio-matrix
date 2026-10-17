#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/audio-matrix-system-sink.XXXXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT

mkdir -p "$scratch/bin" "$scratch/state/audio-matrix"
cat > "$scratch/bin/wpctl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == status ]]; then
    printf 'status\n' >> "$WPCTL_LOG"
    cat <<'STATUS'
Audio
 ├─ Sinks:
 │  * 32. audiomatrix_system [vol: 1.00]
 │    71. physical_output [vol: 1.00]
 ├─ Sources:
STATUS
elif [[ "${1:-}" == set-default ]]; then
    printf 'set-default %s\n' "$2" >> "$WPCTL_LOG"
else
    exit 2
fi
EOF
chmod +x "$scratch/bin/wpctl"
printf '%s\n' physical_output > "$scratch/state/audio-matrix/previous-system-sink"

PATH="$scratch/bin:$PATH" \
XDG_STATE_HOME="$scratch/state" \
WPCTL_LOG="$scratch/wpctl.log" \
AUDIOMATRIX_PIPEWIRE_SINK_LIBRARY="$repo_root/src/main/systemd/user/pipewire-sink.sh" \
    /bin/sh "$repo_root/src/main/systemd/user/audiomatrix-system-sink.sh" &
service_pid=$!
sleep 3.4
kill -TERM "$service_pid"
wait "$service_pid"

status_calls="$(awk '$1 == "status" { count++ } END { print count + 0 }' "$scratch/wpctl.log")"
if (( status_calls > 3 )); then
    echo "System sink monitor polled too often: $status_calls status calls in 3.4 seconds" >&2
    exit 1
fi
if grep -q '^set-default ' "$scratch/wpctl.log"; then
    echo 'Stopping the system-sink monitor changed the selected output' >&2
    exit 1
fi
printf 'System sink polling verified: %s status calls in 3.4 seconds; stopping it left the selected virtual output unchanged.\n' "$status_calls"
