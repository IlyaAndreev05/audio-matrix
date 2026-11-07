# Audio Matrix 0.4.2

Audio Matrix manages PipeWire playback through an editable graph and a native GNOME Shell extension inside the system's Quick Settings. Open the arrow beside the main output-volume slider to see the full Audio Matrix panel: application cards, output devices, and pinned graph controls. The panel replaces only the native output-device list, not the master slider, microphone controls, tiles, or Sound Settings. It uses native Shell controls with Russian-language labels, not a web view or a separate mixer window.

The Java-to-GNOME bridge uses API v5; the Quick Settings extension is version 21. The current configuration schema is 11. See the [0.4.2 release notes](docs/release-0.4.2.md) for the detailed change list and verification bounds.

Device display aliases are stored in the existing JSON configuration as `device.name`; no separate alias database is used. The canonical user config is `~/.config/audio-matrix/config.json` (or the equivalent under `XDG_CONFIG_HOME`). When that JSON file is absent, a legacy `config.yaml` is imported, upgraded as needed, and retained as `config.yaml.before-json`.

Chrome's optional browser adapter provides tab metadata and mute/focus commands. It exposes a separate per-tab volume control only after explicit tab capture is active and the captured PipeWire stream is ready. Firefox's optional adapter provides tab metadata and mute/unmute but no independent per-tab PCM volume, so its per-tab volume control is disabled. Both browsers retain ordinary PipeWire stream controls for browser-wide volume. See [Browser audio controls](docs/browser-adapters.md) for details.

Dynamic mixer inputs use their application's display name rather than a generated port ID. Their volume and mute controls edit the mixer input, not the application's separate volume multiplier. Inputs without a current live binding remain visible with an offline label but cannot be edited; an unavailable name is shown as «Приложение недоступно». Failed commands clear their pending state and restore the observed value rather than displaying an unconfirmed setting.

## Changes in 0.4.2

- The full panel now opens from the arrow beside the output-volume slider and sits directly before the native device list; only that list is hidden. The arrow stays available even with a single output, and the header, master slider, separator, Sound Settings, microphone controls, and tiles remain.
- Dynamic mixer inputs are labelled with their application name and stay visible but non-interactive while offline. Volume and mute share one validated mutation and confirm the exact pinned target; a failure clears the pending state and restores the observed value.
- Master-volume changes no longer wait for the 250 ms topology debounce: level-only snapshot coalescing is 5 ms, and a guarded level-only reconcile applies only the changed bus/route levels, falling back to a full reconcile on any doubt. A private 20-sample measurement improved from p50 160.8 / p95 178.4 ms to p50 48.8 / p95 89.3 ms.
- A private end-to-end proof accepted one production `api_test` trigger and measured nonzero PCM at the event sink, bus, route output, and a virtual rear sink. Event Sound Studio reported accepted/enqueued/rendered 567/567/567, dropped 0, and WAV ready 53/53. Physical audibility and vibration remain unverified; at master 0.23 the post-volume rear signal was about 6.6% (−23.7 dB) of the event-sink signal.

The application package is 0.4.2 and the GNOME extension is 21. Event Sound Studio remains at 0.2.4 and is not rebuilt for this update.

## Manual installation

The built integration archive is `build/Audio-Matrix-GNOME-install.zip`. It contains `audiomatrix_0.4.2_amd64.deb`, the original unchanged `eventsoundstudio_0.2.4_amd64.deb`, and `INSTALL.txt` at the archive root.

Extract the ZIP and open a terminal in the extracted directory. Install both packages in one command. The Audio Matrix package must be installed before running the extension installer, so `/opt/audiomatrix` contains extension version 21:

```sh
sudo apt install ./audiomatrix_0.4.2_amd64.deb ./eventsoundstudio_0.2.4_amd64.deb
```

Event Sound Studio 0.2.4 is unchanged; if it is already installed, this command leaves it at the same version.

Then run the **installed** GNOME extension installer as the logged-in user, without `sudo`:

```sh
/opt/audiomatrix/bin/install-gnome-quick-settings.sh
```

If `AUDIO_MATRIX_GNOME_EXTENSION_SOURCE` is set in your environment, clear it for this command (`env -u AUDIO_MATRIX_GNOME_EXTENSION_SOURCE /opt/audiomatrix/bin/install-gnome-quick-settings.sh`) so the installed package is used.

That helper copies the extension into the user's GNOME extensions directory; it does **not** enable it. Enable it separately in GNOME Extensions or run:

```sh
gnome-extensions enable audio-matrix-quick-settings@ilyaandreev05
```

Then log out of GNOME and log back in so the updated Shell integration and session-managed Audio Matrix engine are loaded. If GNOME does not yet recognize a newly installed extension, log in again before enabling it. A manually launched Audio Matrix process must also be closed and reopened to load the new Java code. The user performs all installation, enablement, application restart, and session steps manually; building the archive performs none of them.

The repository's GitHub Actions workflow is manual (`workflow_dispatch`) only: it has no automatic `push` or `pull_request` trigger.

## Диагностика тихого или пропавшего звука: скрытый ALSA-мьют

Если общая громкость GNOME/PipeWire показывает 100%, а звук тихий или отсутствует, слайдер — не последний слой. На Realtek ALC1220 (в этом ПК — card 2) есть отдельные аппаратные регуляторы, которых нет в GNOME Sound:

- `Auto-Mute Mode` может глушить выход при детекции переднего джека;
- `Headphone` — отдельный усилитель/мьют. При `0%`/`off` сигнал не проходит, даже если `Master`, `PCM`, `Front` и `Surround` стоят на 100%.

Проверка и восстановление:

```bash
amixer -c 2 sget 'Auto-Mute Mode'
amixer -c 2 sget Headphone
amixer -c 2 sset 'Auto-Mute Mode' Disabled
amixer -c 2 sset Headphone 100% unmute
amixer -c 2 sset Front 100% unmute
amixer -c 2 sset Surround 100% unmute
```

После этого даже низкая общая громкость (например, 22%) уже слышна. Если вернуть `Headphone` в `0%`/`off`, звук снова пропадёт, сколько бы ни было на общем слайдере.

На этом ПК `Headphone` влияет на выход вибродинамика, хотя в GNOME может быть выбран «Линейный выход». Порт `analog-output-headphones` при этом может отображаться как недоступный — это не отменяет влияние `Headphone`.

Важно:

- после запуска Audio Matrix/Event Sound Studio, смены профиля карты или переподключения джека проверять `Headphone` снова: он может сброситься;
- не лечить это цифровым усилением больше 1.0 — полная шкала уже достигнута, дальше только клиппинг;
- для прямой проверки в обход Audio Matrix использовать `pw-play --target alsa_output.pci-0000_0b_00.3.analog-surround-40 --volume 1.0 <файл.wav>`; запись monitor sink показывает сигнал до аппаратного регулятора и не доказывает наличие звука на аналоговом выходе.
