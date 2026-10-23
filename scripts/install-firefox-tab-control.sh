#!/bin/sh
set -eu

data_home=${XDG_DATA_HOME:-$HOME/.local/share}
state_home=${XDG_STATE_HOME:-$HOME/.local/state}
host_name=io.github.ilyaandreev05.audiomatrix.firefox_tab_control
extension_id=audio-matrix-tabs@ilyaandreev05.github.io
target_bundle="$data_home/audio-matrix/firefox-tab-control"
target_host="$HOME/.mozilla/native-messaging-hosts/$host_name.json"
state_dir="$state_home/audio-matrix/firefox-tab-control"
bundle_backup="$target_bundle.pre-audiomatrix"
host_backup="$target_host.pre-audiomatrix"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source_extension=${AM_FIREFOX_EXTENSION_SOURCE:-/opt/audiomatrix/lib/firefox/tab-control}
source_host=${AM_FIREFOX_NATIVE_HOST_SOURCE:-/opt/audiomatrix/lib/firefox/native-host/audio-matrix-tab-control.py}
source_transport=${AM_BROWSER_TRANSPORT_SOURCE:-/opt/audiomatrix/lib/chrome/native-host/audio-matrix-tab-capture.py}

if [ ! -r "$source_extension/background.js" ]; then
    repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
    source_extension="$repo_root/firefox/tab-control"
fi
if [ ! -r "$source_host" ]; then
    repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
    source_host="$repo_root/firefox/native-host/audio-matrix-tab-control.py"
fi
if [ ! -r "$source_transport" ]; then
    repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
    source_transport="$repo_root/chrome/native-host/audio-matrix-tab-capture.py"
fi
if [ ! -r "$source_extension/manifest.json" ] || [ ! -r "$source_extension/background.js" ] || [ ! -r "$source_host" ] || [ ! -r "$source_transport" ]; then
    echo 'Не найдены файлы интеграции Firefox из пакета Audio Matrix.' >&2
    exit 1
fi
if [ -e "$state_dir/bundle-state" ] || [ -e "$state_dir/host-state" ] || [ -e "$bundle_backup" ] || [ -e "$host_backup" ]; then
    echo 'Уже существует резервная копия Firefox. Сначала выполните uninstall-firefox-tab-control.sh или проверьте её вручную.' >&2
    exit 1
fi

mkdir -p "$(dirname "$target_bundle")" "$(dirname "$target_host")" "$state_dir"
if [ -e "$target_bundle" ]; then mv "$target_bundle" "$bundle_backup"; bundle_state=present; else bundle_state=absent; fi
if [ -e "$target_host" ]; then mv "$target_host" "$host_backup"; host_state=present; else host_state=absent; fi
tmp_bundle="$target_bundle.install.$$"
tmp_host="$target_host.install.$$"
restore_on_error() {
    result=$?
    if [ "$result" -ne 0 ]; then
        rm -rf -- "$tmp_bundle" "$target_bundle"
        rm -f -- "$tmp_host" "$target_host"
        if [ -e "$bundle_backup" ]; then mv "$bundle_backup" "$target_bundle"; fi
        if [ -e "$host_backup" ]; then mv "$host_backup" "$target_host"; fi
        rm -f -- "$state_dir/bundle-state" "$state_dir/host-state"
    fi
    exit "$result"
}
trap restore_on_error EXIT HUP INT TERM
mkdir -p "$tmp_bundle/extension" "$tmp_bundle/firefox/native-host" "$tmp_bundle/chrome/native-host"
cp -R "$source_extension/." "$tmp_bundle/extension/"
install -m 700 "$source_host" "$tmp_bundle/firefox/native-host/audio-matrix-tab-control.py"
install -m 700 "$source_transport" "$tmp_bundle/chrome/native-host/audio-matrix-tab-capture.py"
python3 - "$tmp_host" "$target_bundle/firefox/native-host/audio-matrix-tab-control.py" "$extension_id" "$host_name" <<'PY'
import json
import os
import sys

destination, host_path, extension_id, host_name = sys.argv[1:]
with open(destination, "w", encoding="utf-8") as output:
    json.dump({
        "name": host_name,
        "description": "Audio Matrix: сведения о вкладках Firefox и выключение звука; без захвата PCM",
        "path": os.path.abspath(host_path),
        "type": "stdio",
        "allowed_extensions": [extension_id],
    }, output, ensure_ascii=False, indent=2)
    output.write("\n")
PY
mv "$tmp_bundle" "$target_bundle"
mv "$tmp_host" "$target_host"
printf '%s\n' "$bundle_state" > "$state_dir/bundle-state"
printf '%s\n' "$host_state" > "$state_dir/host-state"
trap - EXIT HUP INT TERM
printf 'Файлы Firefox установлены в %s/extension.\n' "$target_bundle"
printf 'Загрузите manifest.json как временное дополнение в about:debugging или установите подписанный XPI. Скрипт не запускает и не настраивает Firefox.\n'
printf 'Вкладки появятся в работающем Audio Matrix после подключения дополнения; Firefox предоставляет выключение звука, но не независимую PCM-громкость вкладки.\n'
