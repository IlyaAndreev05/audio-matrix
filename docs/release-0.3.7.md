# Audio Matrix 0.3.7 release checklist

- Resolve persistent device IDs in GNOME output rows through the configured device selectors, so output controls remain bound after PipeWire assigns new runtime names.
- Label an unavailable physical output as an unavailable output device; reserve the engine-unavailable message for a disconnected bridge.
- Reject an incomplete graph before route activation so an empty draft cannot replace the current route.
- Bump the app package to 0.3.7 and the GNOME extension to version 16.
- Run the Gradle test suite, GNOME extension smoke checks, the PipeWire route smoke test in its private session, and the class-size audit.

The PipeWire smoke test uses its own virtual endpoints. No physical output, system volume, mute state, default output, or Family 17h setting is used for test signals.
