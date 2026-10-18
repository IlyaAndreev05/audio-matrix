# Audio Matrix 0.3.5 release checklist

## Changes

- Fix GNOME extension teardown so the volume menu removes and destroys its inserted section exactly once.
- Add a regression guard against destroying the section again from the extension lifecycle.
- Clarify that installing the Debian package stores the extension under `/opt`; copying it into the active user's GNOME profile is a separate step.
- Keep the Java, GJS, and Python class-size rule at no more than 200 code lines.

## Verification

- `./gradlew check`
- `bash scripts/test-gnome-quick-settings.sh`
- `bash scripts/test-chrome-tab-capture.sh`
- `python3 scripts/system-sink-config-smoke.py`
- `python3 scripts/pipewire-route-smoke.py`
- `python3 scripts/pipewire-reconnect-smoke.py`
- `python3 scripts/check_class_size.py`
- `git diff --check`
- `./gradlew packageDeb`

The checks use isolated or fake PipeWire sessions and temporary extension directories. They do not change the live system output, volume, mute, or Family 17h profile and do not play sound through physical outputs. The built package is prepared for manual installation. GNOME 50 loads the new extension after the user's next sign-in; this release does not log the user out automatically.
