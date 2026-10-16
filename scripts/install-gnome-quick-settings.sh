#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
uuid="audio-matrix-quick-settings@ilyaandreev05"
target="${XDG_DATA_HOME:-$HOME/.local/share}/gnome-shell/extensions/$uuid"
mkdir -p "$target"
cp "$repo_root/gnome/audio-matrix-quick-settings/metadata.json" "$target/metadata.json"
cp "$repo_root/gnome/audio-matrix-quick-settings/extension.js" "$target/extension.js"
printf 'Installed %s. On Wayland, log out and back in so GNOME Shell discovers the extension.\n' "$uuid"
printf 'After signing in, enable it with: gnome-extensions enable %s\n' "$uuid"
