# Audio Matrix 0.4.0

Audio Matrix manages PipeWire playback with one editable graph and a GNOME Quick Settings extension. The graph has one **Основной** profile, a dynamic ordinary-application source, a MultiSwitch for outputs, a separate Event Sound Studio path, and a shared output limiter. The default music route reaches the monitor only; the vibration output stays unassigned or disabled until selected. The old bass-only branch and required low-pass filter are removed.

Volumes are linear: `0.5` is half amplitude and `1.0` is unity. Physical output levels are read from PipeWire and changed through WirePlumber's Mixer API. The GNOME commands set absolute volume, mute, or route state; mute preserves the previous level. The Quick Settings bridge is API v4, and its extension version is 18.

Configuration schema 10 migrates older settings after creating a `config.json.before-schema-10` backup and a full `migration_archive`. It retains user device bindings, mute and level values, names, and pins. See [the 0.4.0 migration report](docs/migration-report-0.4.0.md) for the migration details and [browser adapter limits](docs/browser-adapters.md).

Build and verify the Java application and GNOME extension with:

```sh
./gradlew check build packageDeb
bash scripts/test-gnome-quick-settings.sh
```

The Debian packages are written to `build/deb/`. The private PipeWire smoke test is available as `python3 scripts/pipewire-route-smoke.py`; it creates a temporary virtual sink and does not play audio through physical devices. Event Sound Studio 0.2.3 is built from its companion project.
