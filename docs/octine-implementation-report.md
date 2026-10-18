# Octine Studio implementation report — Audio Matrix

Branch: `feat/octine-haptics-event-routing`  
Baseline saved on `main` and merged to `develop`: `df3e188` (workspace artifacts preserved).

## Implemented

- Canonical multi-select `Switch` graph node. Schema-8 migration preserves existing v7 profiles, routes, graph data and active selection; legacy `multi_switch` graphs migrate explicitly.
- Monitor, Headphones and Free graph presets, installed additively, plus one persisted `octine-common-vibro` graph shared by all three. The graph is not recreated when switching profiles.
- Shared tactile topology: Main Mixer → 120 Hz low-pass copy → Vibro Mixer; EVENT → Vibro Mixer directly; Vibro Mixer → Gain/Mute → Limiter → bound Vibro Device Slot.
- Ordinary application audio enters Main Mixer through the persistent `audiomatrix_system` monitor. Per-stream graph rebuilds are suppressed for the built-in Octine profiles, eliminating the long route-rebuild delay when a music or notification stream appears.
- Device Slot 1→1 and Device Discovery with stable identity (`physicalPath`, serial, card profile, port, Bluetooth address, channels/positions, confidence). Discovered devices are not routed automatically; ambiguous/colliding identities require explicit binding; offline bindings persist.
- Vibro/ordinary physical-output conflict checks, desired/observed graph reconciliation, honest route diagnostics, link verification and rollback on partial apply.
- Route loopbacks use a 25 ms requested latency to reduce chain delay.
- Profiles page exposes the additive Octine preset installer. Graph page exposes discovery, slots and shared Vibro controls. GNOME Quick Settings uses one vertical column of named groups and pins the shared tactile controls.
- CLI for explicit setup/migration:
  - `AudioMatrix --install-octine-profiles [config.json]`
  - `AudioMatrix --replace-with-octine-profiles [config.json]`
  - `AudioMatrix --upgrade-device <deviceId> <node-name> <role> [config.json]`
  - `AudioMatrix --bind-device <slotId> <node-name> [config.json]`
  - `AudioMatrix --assign-slot <slotId> <deviceId> [config.json]`
  - `AudioMatrix --activate-profile <profileId> [config.json]`
  These commands never guess a device and never activate a profile implicitly, except the explicit replacement/activation command.

## Verification

- `./gradlew test` passes (283 tests, 13 skipped) after the final changes.
- Live PipeWire graph on this machine (Audio Matrix 1.6.2, GNOME 50.1 Wayland):
  - `audiomatrix_system` monitor is linked to the main bus.
  - Main bus feeds HDMI/USB full-range output and the analog front AUX through the 120 Hz low-pass Vibro branch.
  - Event bus feeds only the analog Vibro output; HDMI event PCM measured zero.
- Music test (100 Hz + 1 kHz tone): HDMI monitor showed both frequencies at comparable level; analog Vibro monitor showed 100 Hz and strongly attenuated 1 kHz, proving the Low-pass copy.
- Event test: normalized Octine click pulses produced analog peaks around 11,143 s16; HDMI remained zero.
- Notification continuity test: 25 event pulses continued through a visible GNOME notification with no multi-second gap.
- Click-to-captured-PCM latency measured from evdev to analog monitor: approximately **0.38 s** (capture-monitor path included). The earlier multi-second delay was route reconstruction, not the event-to-PCM path.

## Known limitations / blockers

- HDMI disappeared intermittently while the monitor/card was unavailable. The profile correctly reported the output unavailable; physical HDMI acceptance requires the display/card to stay present.
- Bluetooth headphones were not connected during verification; the Headphones profile was not physically tested.
- Physical transducer loudness and the 25 W/4 Ω power limit require the installed amplifier and a safe listening check. Digital gain is not a power guarantee.
- Continuous installed-system run: 25 minutes, 375 event pulses and a looping 100 Hz/1 kHz music tone. `pw-top` reported **0** errors for analog and HDMI at every sampled minute; no route/service restart occurred. The run was stopped at user request before the 30-minute mark, so AC-06 is **not** fully closed.
- Installed packages: Audio Matrix `0.3.9` and Event Sound Studio `0.2.2` were installed on 2026-10-18; the installed user services are active.
- Feature commit: `c304ee7`; merge into `develop`: `e4f8fdc`; branch pushed to `origin/feat/octine-haptics-event-routing`.
- A 30-minute continuous run was started on 2026-10-18 17:53 MSK; its result is appended below when finished.

## Safety

- Event output is permanently pinned to the isolated `audiomatrix_events` sink. There is no hardware fallback.
- New or ambiguous devices remain silent until explicit binding.
- No text, key history or notification content is stored or logged.
