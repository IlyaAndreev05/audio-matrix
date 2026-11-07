# AGENTS.md

## Звук на этом ПК: сначала скрытые ALSA-мьюты

Перед выводом «железо/усилитель сломан» или «код проектов виноват» проверять аппаратные ALSA-регуляторы. Общая громкость GNOME/PipeWire 100% не означает, что сигнал проходит все каскады.

Симптом: 100% на слайдере, звук тихий или отсутствует; ноутбук с тем же усилителем играет громко; monitor sink показывает сигнал.

На Realtek ALC1220 (card 2):

- `Headphone` может быть `0%`/`off` — это отдельный усилитель, не общая громкость;
- `Auto-Mute Mode` может быть `Enabled` и глушить выход.

Проверка/фикс:

```bash
amixer -c 2 sget 'Auto-Mute Mode'
amixer -c 2 sget Headphone
amixer -c 2 sset 'Auto-Mute Mode' Disabled
amixer -c 2 sset Headphone 100% unmute
amixer -c 2 sset Front 100% unmute
amixer -c 2 sset Surround 100% unmute
```

На этом ПК `Headphone` влияет на выход вибродинамика, даже если в GNOME выбран «Линейный выход». Порт `analog-output-headphones` может быть `unavailable`, но это не отменяет влияние `Headphone`.

После старта Audio Matrix/Event Sound Studio и после смены профиля карты проверять `Headphone` снова: он может сброситься. Не предлагать digital gain > 1.0 как лечение — это клиппинг. Для изоляции тракта использовать `pw-play --target alsa_output.pci-0000_0b_00.3.analog-surround-40 --volume 1.0 <файл.wav>`; monitor sink pre-volume и не доказывает аналоговый выход.
