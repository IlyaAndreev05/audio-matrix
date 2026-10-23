#!/bin/sh
set -eu

data_home=${XDG_DATA_HOME:-$HOME/.local/share}
state_home=${XDG_STATE_HOME:-$HOME/.local/state}
host_name=io.github.ilyaandreev05.audiomatrix.firefox_tab_control
target_bundle="$data_home/audio-matrix/firefox-tab-control"
target_host="$HOME/.mozilla/native-messaging-hosts/$host_name.json"
state_dir="$state_home/audio-matrix/firefox-tab-control"
bundle_backup="$target_bundle.pre-audiomatrix"
host_backup="$target_host.pre-audiomatrix"
bundle_removed="$target_bundle.audiomatrix-removed"
host_removed="$target_host.audiomatrix-removed"

if [ ! -r "$state_dir/bundle-state" ] || [ ! -r "$state_dir/host-state" ]; then
    echo 'Сохранённое состояние установки Firefox не найдено.' >&2
    exit 1
fi
IFS= read -r bundle_state < "$state_dir/bundle-state"
IFS= read -r host_state < "$state_dir/host-state"
case "$bundle_state:$host_state" in present:present|present:absent|absent:present|absent:absent) ;; *) echo 'Повреждено состояние установки Firefox.' >&2; exit 1 ;; esac
if [ "$bundle_state" = present ] && [ ! -d "$bundle_backup" ]; then echo "Нет резервной копии: $bundle_backup" >&2; exit 1; fi
if [ "$host_state" = present ] && [ ! -r "$host_backup" ]; then echo "Нет резервной копии: $host_backup" >&2; exit 1; fi
if [ -e "$bundle_removed" ] || [ -e "$host_removed" ]; then echo 'Уже существует сохранённая удалённая установка Firefox.' >&2; exit 1; fi

if [ -e "$target_bundle" ]; then mv "$target_bundle" "$bundle_removed"; fi
if [ -e "$target_host" ]; then mv "$target_host" "$host_removed"; fi
if [ "$bundle_state" = present ]; then mv "$bundle_backup" "$target_bundle"; fi
if [ "$host_state" = present ]; then mv "$host_backup" "$target_host"; fi
rm -f -- "$state_dir/bundle-state" "$state_dir/host-state"
rmdir "$state_dir" 2>/dev/null || true
printf 'Прежние файлы Firefox восстановлены. Удалите вручную загруженное дополнение в about:addons или about:debugging.\n'
printf 'Заменённые файлы сохранены: %s и %s.\n' "$bundle_removed" "$host_removed"
