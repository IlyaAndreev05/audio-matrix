# Audio Matrix 0.4.0 migration report

This report describes the schema-10 migration implemented and tested in Audio Matrix 0.4.0. The release build did not read, rewrite, or migrate the user's live configuration.

On first application startup with a schema earlier than 10, Audio Matrix copies the existing JSON file to `config.json.before-schema-10` (or a numbered sibling), then stores a complete copy of the original document in `migration_archive.prior_config`. The current UI presents one profile, **Основной**. Previous profiles and the old graph remain in the archive for recovery.

The migration selects the saved monitor route for the new graph, restores monitor and vibration-device bindings, and carries forward device levels, mute state, application settings, and GNOME pins when those values exist. It preserves applied amplitude when converting old values written through `wpctl`'s cubic scale to linear gains. Event Sound Studio PCM settings are in the companion project and are not transformed by this migration.

The resulting music MultiSwitch initially selects the monitor only. Its vibration output route stays off, and the Event Sound Studio path remains independent. The old required musical bass/low-pass branch is removed. Saved event, music, and shared vibration levels are retained; when no previous level exists, vibration gains use the conservative defaults 0.65, 0.6, and 0.6 respectively. An unknown output level is stored as zero until PipeWire reports it or the user changes it, so unmuting cannot silently substitute unity gain.

Validation covers schema-9 migration and archival, output binding restoration, independent event/music routing, mute and level preservation, external-file conflict checks, and atomic configuration writes. The release process never sends audio to a physical output. The optional runtime mixer test uses a temporary PipeWire server and a virtual sink only.

The desktop reports current device names separately at release time; they are runtime observations and are not written into the user's configuration by the build.

## Release validation

Audio Matrix 0.4.0: `./gradlew check build` passed with 297 tests, 0 failures, and 14 hardware/runtime-only tests skipped. The separate private-PipeWire smoke passed against a temporary virtual sink: it checked linear levels 0, 0.5, and 1.0, mute/unmute restoration at both zero and 0.5, and mute-command p95 below 100 ms. GNOME Quick Settings checks passed, including the version-18 extension package and idempotent graph controls. No physical audio was played and no hardware level was changed.

Event Sound Studio 0.2.3: `./gradlew check build` passed. Its Debian package built successfully with Gradle's configuration cache disabled; the regular package task currently uses Gradle project access during task execution and is not configuration-cache compatible.

The GNOME menu was validated by JavaScript and extension-package tests. A live GNOME Shell visual pass at 100%, 150%, and 200% scaling was not run in this release environment.

## Devices observed

Read-only `wpctl status` inspection on 2026-10-20 found the Audio Matrix logical sink `audiomatrix_system` selected as the default output, with the following hardware sinks:

- USB PnP Audio Device, digital stereo (IEC958), reported level 1.00.
- TU106 High Definition Audio Controller, digital stereo (HDMI), reported level 0.34.
- Family 17h (Models 00h-0fh) HD Audio Controller, analog stereo, reported level 0.71.

The active default input was the USB PnP Audio Device mono input (reported level 0.51); a Logitech HD Pro Webcam C920 stereo input was also present (reported level 1.00). These are PipeWire's reported values, not calibrated loudness measurements. Device discovery did not alter routing or volume.

## Browser adapter limits

Chrome and Firefox playback is discovered through PipeWire as ordinary application streams. Per-tab Chrome capture still needs the browser's user gesture and optional extension. Firefox's included unsigned toolbar add-on only focuses or mutes tabs and is not connected to the GNOME menu; it does not provide per-tab PCM volume. See [browser-adapters.md](browser-adapters.md).
