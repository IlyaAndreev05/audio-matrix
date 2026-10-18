#!/bin/sh
set -eu

capture_props='{"node.name":"audiomatrix_events","node.description":"Audio Matrix event input","media.class":"Audio/Sink","audio.position":["FL","FR"],"node.virtual":true,"node.autoconnect":false}'
playback_props='{"node.name":"audiomatrix_events_discard","node.description":"Audio Matrix event sink isolation","node.autoconnect":false,"node.passive":true,"stream.dont-remix":true,"audio.position":["FL","FR"]}'

exec /usr/bin/pw-loopback -n audiomatrix_events -l 10 -c 2 -m '[ FL FR ]' -i "$capture_props" -o "$playback_props"
