#!/bin/sh

# Resolve transient PipeWire node IDs from wpctl at the time of each operation.
audiomatrix_sink_rows() {
    wpctl status -n 2>/dev/null | awk '
        /Sinks:/ { in_sinks = 1; next }
        in_sinks && /Sources:/ { exit }
        in_sinks {
            line = $0
            sub(/^[^*0-9]*/, "", line)
            is_default = 0
            if (substr(line, 1, 1) == "*") {
                is_default = 1
                sub(/^\*[[:space:]]*/, "", line)
            }
            if (match(line, /^[0-9]+\./)) {
                id = substr(line, 1, RLENGTH - 1)
                name = substr(line, RLENGTH + 1)
                sub(/^[[:space:]]*/, "", name)
                sub(/[[:space:]]+\[vol:.*$/, "", name)
                printf "%s\t%s\t%s\n", id, name, is_default
            }
        }
    '
}

audiomatrix_sink_id() {
    wanted=$1
    audiomatrix_sink_rows | awk -F '\t' -v wanted="$wanted" '$2 == wanted { print $1; exit }'
}

audiomatrix_sink_exists() {
    [ -n "$(audiomatrix_sink_id "$1")" ]
}

audiomatrix_default_sink() {
    audiomatrix_sink_rows | awk -F '\t' '$3 == "1" { print $2; exit }'
}

audiomatrix_set_default_sink() {
    sink_id=$(audiomatrix_sink_id "$1")
    if [ -z "$sink_id" ]; then
        echo "PipeWire output $1 is unavailable" >&2
        return 1
    fi
    wpctl set-default "$sink_id"
}
