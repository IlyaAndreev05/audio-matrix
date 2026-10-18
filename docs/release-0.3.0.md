# Audio Matrix 0.3.0 release checklist

## Changes in this branch

- Replaces the GNOME sound-output submenu with the Audio Matrix route picker and user-defined, independently collapsible pin groups in one scrollable column. Source and Output nodes appear as regular pins; applications and device levels are rendered inside their node cards. GNOME's master slider and unrelated Quick Settings tiles remain in place.
- Updates volume rows in place when levels or mute states change. The sound-output menu arrow remains enabled with a single device and returns to GNOME's own device-count behavior when the extension is disabled.
- Gives Switch and Selector a single-choice radio menu, while MultiSwitch, MultiSelector, and Matrix keep independent connections. Pinned Matrix cards have synchronized Matrix and Arrows tabs, with the selected view saved in the JSON configuration.
- Adds a separate mute state for each Mixer input. Muting keeps its stored gain, the slider shows zero, and moving it restores sound at the chosen level.
- Adds JSON schema 5 node pins and migrates schemas 1–4 and YAML while retaining the existing backup behavior. Pin IDs and quick-control references are preserved or remapped when routes are duplicated, saved, and restored.
- Adds synchronized Matrix table and arrow views in the graph editor; both update the same on/off connection set without adding per-link gain controls.
- Publishes route IDs, group state, config revisions, and stable configured output references through D-Bus API 3. Structural commands based on an old config revision are rejected.
- Keeps Java and GNOME JavaScript classes at or below 200 code lines; both repositories enforce the limit from Gradle tests.

## Verification run

- `./gradlew test`
- `bash scripts/test-gnome-quick-settings.sh`
- `bash scripts/test-chrome-tab-capture.sh`
- `bash scripts/test-gnome-events.sh` in Event Sound Studio
- Both repositories' class-size checks and JSON syntax checks
- `git diff --check`

The GNOME extension was package/install/rollback-smoke-tested with a fake extension CLI. It has not been enabled in the user's GNOME session. No physical output, master volume, mute state, or audio route was changed, and no physical test sound was played.
