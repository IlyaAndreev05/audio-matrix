# Browser audio controls

Chrome and Firefox remain ordinary PipeWire application streams with their normal application/stream controls. The number of streams does not imply that they correspond to browser tabs.

## Chrome tab bridge

The optional Chrome extension connects through the Java-owned owner-only Unix socket `$XDG_RUNTIME_DIR/audio-matrix/browser.sock` (or `/run/user/<uid>/audio-matrix/browser.sock` when `XDG_RUNTIME_DIR` is unset). It uses JSON Lines protocol v1 and sends tab titles/state without URLs. The socket is local to the current user; the native host does not listen on TCP. A browser reconnect sends a complete current-tab snapshot before tab lifecycle/state events. Stable per-tab settings use the extension's browser/profile instance tokens plus a per-session tab token; the transient Chrome tab ID is never used as a saved identity.

Tab metadata and mute/focus commands are available while the extension is connected. The Java socket is optional for the capture itself: the native host keeps capturing the tab into the Audio Matrix virtual sink when Audio Matrix is not running, reports the missing bridge to the extension, and replays the current snapshot when the socket appears. Only the tab rows in the GNOME menu require the socket; a disconnect makes them unavailable without losing saved settings or stopping an active capture. A separate per-tab volume control appears only after the user explicitly starts tab capture and the native host confirms that the captured PipeWire stream is ready. Capture requires the Chrome extension's user gesture and the existing tab-capture permission. Audio Matrix never enables the extension, grants permissions, routes capture to physical hardware, or infers tab identity from PipeWire stream counts. Browser-wide and captured-tab gain controls remain separate and multiply for effective tab volume; captured PCM enters the Audio Matrix virtual sink only once. Stopping capture closes both capture sides and restores normal Chrome playback. Disconnect or failure makes tab instances unavailable without deleting their saved settings.

Install the optional host files with `/opt/audiomatrix/bin/install-chrome-tab-capture.sh`, then manually load the unpacked extension from `~/.local/share/audio-matrix/chrome-tab-capture` in `chrome://extensions`. The installer only registers/copies extension files and the Native Messaging host; it does not enable or start the extension. To remove the integration and restore prior host files, use `/opt/audiomatrix/bin/uninstall-chrome-tab-capture.sh`.

## Firefox

Firefox remains an ordinary application whose system PipeWire streams retain their normal volume and mute controls. Firefox per-tab metadata, mute, focus, native messaging, and PCM capture are not connected to Audio Matrix. The existing Firefox add-on and its packaging remain unchanged. Audio Matrix never infers a Firefox tab control from multiple system streams.
