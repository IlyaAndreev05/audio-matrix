#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/audio-matrix-gnome-extension.XXXXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT

mkdir -p "$scratch/data/applications"
cat > "$scratch/data/applications/audiomatrix-AudioMatrix.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Audio Matrix smoke
Exec=/bin/true
Terminal=false
Categories=AudioVideo;
EOF
cat > "$scratch/check-desktop-app-info.mjs" <<'EOF'
import GioUnix from 'gi://GioUnix';

const app = GioUnix.DesktopAppInfo.new('audiomatrix-AudioMatrix.desktop');
if (app === null || app.get_id() !== 'audiomatrix-AudioMatrix.desktop')
    throw new Error('Could not resolve the Audio Matrix desktop entry');
print(`Resolved app ID: ${app.get_id()}`);
EOF

XDG_DATA_HOME="$scratch/data" gjs -m "$scratch/check-desktop-app-info.mjs"
extension_source="$repo_root/gnome/audio-matrix-quick-settings/extension.js"
rg -q "import GioUnix from 'gi://GioUnix';" "$extension_source"
rg -q 'GioUnix\.DesktopAppInfo\.new' "$extension_source"
node --check "$extension_source"
gnome-extensions pack "$repo_root/gnome/audio-matrix-quick-settings" --force --out-dir "$scratch"
unzip -t "$scratch/audio-matrix-quick-settings@ilyaandreev05.shell-extension.zip"
unzip -p "$scratch/audio-matrix-quick-settings@ilyaandreev05.shell-extension.zip" metadata.json | rg '"version": 3'
