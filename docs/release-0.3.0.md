# Audio Matrix 0.3.0 release checklist

## Changes in this branch

- Replaces the GNOME sound-output submenu with the Audio Matrix route picker and user-defined, independently collapsible pin groups in one scrollable column. Source and Output nodes appear as regular pins; applications and device levels are rendered inside their node cards. GNOME's master slider and unrelated Quick Settings tiles remain in place.
- Updates volume rows in place when levels or mute states change. The sound-output menu arrow remains enabled with a single device and returns to GNOME's own device-count behavior when the extension is disabled.
- Adds JSON schema 5 node pins and migrates schemas 1–4 and YAML while retaining the existing backup behavior. Pin IDs and quick-control references are preserved or remapped when routes are duplicated, saved, and restored.
- Adds synchronized Matrix table and arrow views in the graph editor; both update the same on/off connection set without adding per-link gain controls.
- Publishes route IDs, group state, config revisions, and stable configured output references through D-Bus API 3. Structural commands based on an old config revision are rejected.
- Keeps each Java and GNOME JavaScript class at or below 200 lines of code; Gradle enforces the limit.

## Verification run

- `./gradlew test`
- `bash scripts/test-gnome-quick-settings.sh`
- `bash scripts/test-chrome-tab-capture.sh`
- `bash scripts/test-gnome-events.sh` in Event Sound Studio
- Both repositories' class-size checks and JSON syntax checks
- `git diff --check`

The GNOME extension was package/install/rollback-smoke-tested with a fake extension CLI. It has not been enabled in the user's GNOME session. No physical output, master volume, mute state, or audio route was changed, and no physical test sound was played.
