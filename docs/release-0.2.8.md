# Audio Matrix 0.2.8

- GNOME's existing volume menu now presents application, stream, and output levels as compact single-line rows with icons, percentages, and accessible mute buttons. Graph controls remain linked to their stored node parameters; Matrix connections use switches in the submenu.
- Multi-input and multi-output router nodes now show their active internal links as compact curved paths in the graph canvas, alongside the existing editable connection matrix.
- PipeWire stream identity now falls back to properties on the owning client. Event routing matches the dedicated Event Sound Studio application ID, leaving ordinary Java Sound preview playback on the regular app route.
- A stream with incompatible PipeWire ports no longer aborts the full graph. Existing links are recognized before connecting, so an already-created link does not fail an otherwise valid apply.
- The system-sink monitor uses one `wpctl status` snapshot per three-second poll instead of multiple status calls every second. Stopping or restarting it keeps the virtual sink selected; the explicit disable helper restores the saved physical output.
- `restart-audiomatrix-safe.sh` handles upgrades from the previous service version without briefly selecting the saved physical output.
- The GNOME extension installer supports safe upgrades while retaining the first pre-Audio-Matrix backup and preserving the replaced Audio Matrix version for recovery.

The configured vibration master remains at `0.00` by default. This release does not raise it or play a signal on physical outputs.
