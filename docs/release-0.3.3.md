# Audio Matrix 0.3.3 release checklist

## Changes

- Keep GNOME mixer sliders and disclosures in place while live values or collapsed state change; do not rebuild the menu for those updates.
- Render Chrome child audio streams directly inside its pinned source card when the source matches one browser application.
- Keep pinned output-node level rows separate from physical sink rows so a hardware volume refresh cannot overwrite graph Gain state.
- Remove per-connection gain editing from the graph editor. Existing connection factors migrate into explicit Gain nodes, and schema 7 records the migration.
- Enforce a maximum of 200 code lines per Java, GJS, and Python class during `test`, `check`, and Debian package builds.

## Verification

- `./gradlew check`
- `bash scripts/test-gnome-quick-settings.sh`
- `bash scripts/test-chrome-tab-capture.sh`
- `python3 scripts/system-sink-config-smoke.py`
- `python3 scripts/pipewire-reconnect-smoke.py`
- `python3 scripts/check_class_size.py`
- `git diff --check`

These checks use fixture graphs, fake PipeWire processes, temporary extension installs, and isolated test sessions. They do not change the current system output, volume, mute, or Family 17h profile and do not play audio through physical outputs. Manual activation of the GNOME 50 extension and audible output on physical connectors still require user review after installation.

The `.deb` is prepared for manual installation. Package preparation does not install it, change system services or extensions, or modify personal audio configuration.
