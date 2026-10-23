# Audio Matrix 0.4.1

Audio Matrix manages PipeWire playback through an editable graph and a native GNOME Shell extension embedded in the system's Quick Settings. The extension uses native Shell controls, with Russian-language panel labels. It is not a web view or a separate mixer window.

The Java-to-GNOME bridge uses API v5; the Quick Settings extension is version 20. The current configuration schema is 11.

Device display aliases are stored in the existing JSON configuration as `device.name`; no separate alias database is used. The canonical user config is `~/.config/audio-matrix/config.json` (or the equivalent under `XDG_CONFIG_HOME`). When that JSON file is absent, a legacy `config.yaml` is imported, upgraded as needed, and retained as `config.yaml.before-json`.

Chrome's optional browser adapter provides tab metadata and mute/focus commands. It exposes a separate per-tab volume control only after explicit tab capture is active and the captured PipeWire stream is ready. Firefox's optional adapter provides tab metadata and mute/unmute but no independent per-tab PCM volume, so its per-tab volume control is disabled. Both browsers retain ordinary PipeWire stream controls for browser-wide volume. See [Browser audio controls](docs/browser-adapters.md) for details.

## Manual installation

The built integration archive is `build/Audio-Matrix-GNOME-install.zip`. It contains `audiomatrix_0.4.1_amd64.deb`, `eventsoundstudio_0.2.4_amd64.deb`, and `INSTALL.txt` at the archive root.

Download and extract the ZIP, then manually install both `.deb` files together using a package installer, or from the extracted archive directory run:

```sh
sudo apt install ./*.deb
```

After package installation, run the GNOME extension installer as the logged-in user:

```sh
/opt/audiomatrix/bin/install-gnome-quick-settings.sh
```

That helper copies the extension into the user's GNOME extensions directory; it does **not** enable it. Enable it separately in GNOME Extensions or run:

```sh
gnome-extensions enable audio-matrix-quick-settings@ilyaandreev05
```

Then log out of GNOME and log back in so the updated Shell integration is loaded. The user performs these installation and session steps manually.

The repository's GitHub Actions workflow is manual (`workflow_dispatch`) only: it has no automatic `push` or `pull_request` trigger.
