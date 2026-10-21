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
extension_source="$repo_root/gnome/audio-matrix-quick-settings"
gjs -m "$repo_root/scripts/test-gnome-quick-settings-state.js"
node "$repo_root/scripts/test-menu-state-client.mjs"
node "$repo_root/scripts/test-volume-menu-lease.mjs"
node "$repo_root/scripts/test-menu-adapter.mjs"
node "$repo_root/scripts/test-menu-renderer.mjs"
node "$repo_root/scripts/test-menu-group-disclosure.mjs"
node "$repo_root/scripts/test-menu-output.mjs"
node "$repo_root/scripts/test-menu-control-scale.mjs"
node "$repo_root/scripts/test-extension-imports.mjs"
for source_file in "$extension_source"/*.js; do node --check "$source_file"; done
gnome-extensions pack "$extension_source" --force --out-dir "$scratch" \
    --extra-source=menu-pending-intents.js \
    --extra-source=menu-state-render-scheduler.js \
    --extra-source=menu-icons.js \
    --extra-source=menu-row-utils.js \
    --extra-source=menu-viewport.js \
    --extra-source=menu-quick-controls.js \
    --extra-source=menu-text.js \
    --extra-source=state-client.js \
    --extra-source=menu-rows.js \
    --extra-source=menu-applications.js \
    --extra-source=menu-links.js \
    --extra-source=menu-layout.js \
    --extra-source=menu-control-scale.js \
    --extra-source=menu-matrix.js \
    --extra-source=menu-mixer.js \
    --extra-source=menu-output.js \
    --extra-source=menu-disclosure.js \
    --extra-source=menu-groups.js \
    --extra-source=menu-renderer.js \
    --extra-source=menu-state.js \
    --extra-source=menu-lease.js \
    --extra-source=volume-menu-adapter.js
unzip -t "$scratch/audio-matrix-quick-settings@ilyaandreev05.shell-extension.zip"
unzip -p "$scratch/audio-matrix-quick-settings@ilyaandreev05.shell-extension.zip" metadata.json |
    node -e 'let data = ""; process.stdin.on("data", chunk => data += chunk); process.stdin.on("end", () => { if (JSON.parse(data).version !== 19) process.exit(1); });'
for module in menu-text.js state-client.js menu-pending-intents.js menu-state-render-scheduler.js menu-icons.js menu-row-utils.js menu-viewport.js menu-quick-controls.js menu-rows.js menu-applications.js menu-links.js menu-layout.js menu-control-scale.js menu-matrix.js menu-mixer.js menu-output.js menu-disclosure.js menu-groups.js menu-renderer.js menu-state.js menu-lease.js volume-menu-adapter.js; do
    unzip -Z1 "$scratch/audio-matrix-quick-settings@ilyaandreev05.shell-extension.zip" | grep -Fxq "$module"
done

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
    AUDIO_MATRIX_GNOME_EXTENSION_SOURCE="$repo_root/gnome/audio-matrix-quick-settings" \
    bash "$repo_root/scripts/install-gnome-quick-settings.sh"
for source_file in "$extension_source"/*.js; do cmp "$source_file" "$target/$(basename "$source_file")"; done
test -r "$target.pre-audiomatrix/extension.js"
env PATH="$fake_bin:$PATH" XDG_DATA_HOME="$user_data" XDG_STATE_HOME="$user_state" FAKE_GNOME_ENABLED="$enabled" \
    bash "$repo_root/scripts/uninstall-gnome-quick-settings.sh"
grep -Fq 'old extension files' "$target/extension.js"
test -r "$target.audiomatrix-removed/extension.js"
grep -Fxq "$uuid" "$enabled"

rm -rf "$target" "$target.audiomatrix-removed"
mkdir -p "$target" "$target.pre-audiomatrix" "$user_state/audio-matrix/gnome-extension"
printf '{"uuid":"%s","version":6}\n' "$uuid" > "$target/metadata.json"
printf '%s\n' 'Audio Matrix version 6' > "$target/extension.js"
printf '{"uuid":"%s","version":5}\n' "$uuid" > "$target.pre-audiomatrix/metadata.json"
printf '%s\n' 'original extension files' > "$target.pre-audiomatrix/extension.js"
printf '%s\n' present > "$user_state/audio-matrix/gnome-extension/previous-state"
printf '%s\n' enabled > "$user_state/audio-matrix/gnome-extension/previous-enabled"
env PATH="$fake_bin:$PATH" XDG_DATA_HOME="$user_data" XDG_STATE_HOME="$user_state" FAKE_GNOME_ENABLED="$enabled" \
    AUDIO_MATRIX_GNOME_EXTENSION_SOURCE="$repo_root/gnome/audio-matrix-quick-settings" \
    bash "$repo_root/scripts/install-gnome-quick-settings.sh"
for source_file in "$extension_source"/*.js; do cmp "$source_file" "$target/$(basename "$source_file")"; done
upgrade_backup="$(find "$user_data/gnome-shell/extensions" -maxdepth 1 -type d -name "$uuid.audiomatrix-replaced-6-*" -print -quit)"
test -n "$upgrade_backup"
grep -Fq 'Audio Matrix version 6' "$upgrade_backup/extension.js"
grep -Fxq present "$user_state/audio-matrix/gnome-extension/previous-state"
env PATH="$fake_bin:$PATH" XDG_DATA_HOME="$user_data" XDG_STATE_HOME="$user_state" FAKE_GNOME_ENABLED="$enabled" \
    bash "$repo_root/scripts/uninstall-gnome-quick-settings.sh"
grep -Fq 'original extension files' "$target/extension.js"
