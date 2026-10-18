# Audio Matrix 0.3.6 release checklist

## Changes

- Fix the GNOME 50 Quick Settings crash caused by passing `spacing` as a `St.BoxLayout` GObject property.
- Apply row spacing with the supported inline theme style through the shared layout helper.
- Restore the stock menu rows if rendering fails before the replacement is ready.
- Add typed GNOME controls for Filter enable, low/high-pass frequency and slope, delay, limiter settings, and EQ band frequency/gain/Q/type. Their values stay linked to graph parameters.
- Keep filter sliders in place while their values change; show units and use logarithmic frequency/Q ranges.
- Add extension package assertions for the supported layout API and include all new helpers in the GNOME extension archive.
- Bump the GNOME extension to version 15 and the application package to 0.3.6.

## Verification

- `bash scripts/test-gnome-quick-settings.sh`
- `./gradlew check --no-configuration-cache --no-daemon` (includes typed Filter control publication and range validation)
- `python3 scripts/pipewire-route-smoke.py`
- `python3 scripts/check_class_size.py`
- `git diff --check`
- `./gradlew packageDeb --no-configuration-cache`

PipeWire checks run in private sessions with virtual null sinks. They do not emit signals to physical outputs or change the user's default output, volume, mute, or Family 17h profile. Building the package does not install it. GNOME loads extension version 15 after the user's next sign-in; no session is ended automatically.
