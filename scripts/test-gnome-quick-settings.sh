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
if rg -q 'addExternalIndicator|QuickMenuToggle|SystemIndicator' "$extension_source"; then
    echo 'Audio Matrix must extend the standard volume menu without creating a separate tile' >&2
    exit 1
fi
rg -q '_volumeOutput' "$extension_source"
rg -q 'PopupSliderMenuItem' "$extension_source"
rg -q 'quickControls' "$extension_source"
rg -q "set_quick_control" "$extension_source"
rg -q "set_quick_control_choice" "$extension_source"
rg -q '_addGraphLinks' "$extension_source"
rg -Fq "new GLib.Variant('(usss)'" "$extension_source"
rg -q "'Result'" "$extension_source"
node --check "$extension_source"
gnome-extensions pack "$repo_root/gnome/audio-matrix-quick-settings" --force --out-dir "$scratch"
unzip -t "$scratch/audio-matrix-quick-settings@ilyaandreev05.shell-extension.zip"
unzip -p "$scratch/audio-matrix-quick-settings@ilyaandreev05.shell-extension.zip" metadata.json | rg '"version": 6'

fake_bin="$scratch/fake-bin"
user_data="$scratch/user-data"
user_state="$scratch/user-state"
mkdir -p "$fake_bin" "$user_data/gnome-shell/extensions/audio-matrix-quick-settings@ilyaandreev05"
cat > "$fake_bin/gnome-extensions" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
    list) cat "$FAKE_GNOME_ENABLED" ;;
    enable) grep -Fxq "$2" "$FAKE_GNOME_ENABLED" || printf '%s\n' "$2" >> "$FAKE_GNOME_ENABLED" ;;
    disable) sed "/^$2$/d" "$FAKE_GNOME_ENABLED" > "$FAKE_GNOME_ENABLED.next"; mv "$FAKE_GNOME_ENABLED.next" "$FAKE_GNOME_ENABLED" ;;
    *) exit 2 ;;
esac
EOF
chmod +x "$fake_bin/gnome-extensions"
uuid='audio-matrix-quick-settings@ilyaandreev05'
target="$user_data/gnome-shell/extensions/$uuid"
enabled="$scratch/enabled-extensions"
printf '%s\n' "$uuid" > "$enabled"
printf '{"uuid":"%s","version":3}\n' "$uuid" > "$target/metadata.json"
printf '%s\n' 'old extension files' > "$target/extension.js"
env PATH="$fake_bin:$PATH" XDG_DATA_HOME="$user_data" XDG_STATE_HOME="$user_state" FAKE_GNOME_ENABLED="$enabled" \
    bash "$repo_root/scripts/install-gnome-quick-settings.sh"
cmp "$repo_root/gnome/audio-matrix-quick-settings/extension.js" "$target/extension.js"
test -r "$target.pre-audiomatrix/extension.js"
env PATH="$fake_bin:$PATH" XDG_DATA_HOME="$user_data" XDG_STATE_HOME="$user_state" FAKE_GNOME_ENABLED="$enabled" \
    bash "$repo_root/scripts/uninstall-gnome-quick-settings.sh"
grep -Fq 'old extension files' "$target/extension.js"
test -r "$target.audiomatrix-removed/extension.js"
grep -Fxq "$uuid" "$enabled"
