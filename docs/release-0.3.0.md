# Audio Matrix 0.3.0 release checklist

## Changes in this branch

- Replaces the GNOME sound-output submenu with the Audio Matrix route picker and configurable, independently collapsible groups in one scrollable column. GNOME's master slider and unrelated Quick Settings tiles remain in place.
- Updates volume rows in place when levels or mute states change. The sound-output menu arrow remains enabled with a single device and returns to GNOME's own device-count behavior when the extension is disabled.
- Adds JSON schema 4 quick-settings groups and migrates earlier JSON/YAML settings while retaining the existing backup behavior.
- Publishes route IDs, group state, config revisions, and stable configured output references through D-Bus API 3. Structural commands based on an old config revision are rejected.
- Keeps each Java and GNOME JavaScript class at or below 200 lines of code; Gradle enforces the limit.

## Verification run

- `./gradlew test`
- `bash scripts/test-gnome-quick-settings.sh`
- `bash scripts/test-chrome-tab-capture.sh`
- `bash scripts/test-gnome-events.sh` in Event Sound Studio
- Both repositories' class-size checks and JSON syntax checks

The GNOME extension was package/install/rollback-smoke-tested with a fake extension CLI. It has not been enabled in the user's GNOME session. No physical output, master volume, mute state, or audio route was changed, and no physical test sound was played.
