# Audio Matrix 0.3.8 release checklist

- Keep the GNOME mixer attached while the engine's first D-Bus state has not arrived; the empty initial state must not throw and roll back the stock menu.
- Bump the app package to 0.3.8 and the GNOME extension to version 17.
- Run the Gradle tests, GNOME extension smoke tests, the isolated PipeWire route smoke test, and the 200-line class-size audit.

This release only changes GNOME mixer startup-state handling. It does not change the default audio output, volume, mute, or the Family 17h profile, and its tests do not send sound to physical outputs.
