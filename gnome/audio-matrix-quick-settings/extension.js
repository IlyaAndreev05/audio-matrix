import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as QuickSettings from 'resource:///org/gnome/shell/ui/quickSettings.js';
import Gio from 'gi://Gio';
import GioUnix from 'gi://GioUnix';
import GLib from 'gi://GLib';
import GObject from 'gi://GObject';

const OBJECT_PATH = '/org/ilya/AudioMatrix';
const INTERFACE = 'org.ilya.AudioMatrix';
const MODES = ['haptic', 'subwoofer', 'speaker', 'hybrid', 'multi-output', 'off'];
const CHANNEL_MAPS = ['stereo', 'mono', 'mono_sum', 'left', 'right', 'swap_lr', 'center', 'lfe', 'rear_left', 'rear_right', 'rear_sum', 'side_left', 'side_right', 'side_sum', 'surround_sum', 'all_sum'];
const STALE_AFTER_SECONDS = 12;

const TEXT = {
    ru: {
        connecting: 'Подключение к приложению…',
        unavailable: 'Audio Matrix не запущен',
        header: 'Управление выходом',
        open: 'Открыть Audio Matrix',
        outputs: 'Устройства вывода',
        default: 'по умолчанию',
        channels: count => `${count} кан.`,
        routeMode: 'Режим профиля',
        channelMaps: 'Каналы активных маршрутов',
        mute: 'Выключить звук выхода',
        unmute: 'Включить звук выхода',
        mode: {
            haptic: 'Вибро', subwoofer: 'Сабвуфер', speaker: 'Колонки',
            hybrid: 'Гибрид', 'multi-output': 'Несколько выходов', off: 'Выкл.',
        },
        channelMap: {
            stereo: 'Стерео', mono: 'Моно', mono_sum: 'Сумма в моно',
            left: 'Левый канал', right: 'Правый канал', swap_lr: 'Поменять L/R',
        },
    },
    en: {
        connecting: 'Connecting to Audio Matrix…',
        unavailable: 'Audio Matrix is not running',
        header: 'Output controls',
        open: 'Open Audio Matrix',
        outputs: 'Output devices',
        default: 'default',
        channels: count => `${count} ch`,
        routeMode: 'Profile mode',
        channelMaps: 'Active route channels',
        mute: 'Mute output',
        unmute: 'Unmute output',
        mode: {
            haptic: 'Haptic', subwoofer: 'Subwoofer', speaker: 'Speaker',
            hybrid: 'Hybrid', 'multi-output': 'Multi-output', off: 'Off',
        },
        channelMap: {
            stereo: 'Stereo', mono: 'Mono', mono_sum: 'Sum to mono',
            left: 'Left channel', right: 'Right channel', swap_lr: 'Swap L/R',
        },
    },
};

function systemLanguage() {
    const names = GLib.get_language_names();
    return names.some(name => name.startsWith('ru')) ? 'ru' : 'en';
}

const AudioMatrixToggle = GObject.registerClass(class AudioMatrixToggle extends QuickSettings.QuickMenuToggle {
    _init(extension) {
        const language = systemLanguage();
        super._init({title: 'Audio Matrix', subtitle: TEXT[language].connecting,
            iconName: 'audio-speakers-symbolic', toggleMode: true});
        this._extension = extension;
        this._language = language;
        this._state = null;
        this._lastPayload = '';
        this._lastStateAt = GLib.get_monotonic_time();
        this._stale = false;

        this.menu.setHeader('audio-speakers-symbolic', 'Audio Matrix', TEXT[language].header);
        this._dynamic = new PopupMenu.PopupMenuSection();
        this.menu.addMenuItem(this._dynamic);
        this.menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this.menu.addAction(TEXT[language].open, () => extension._openApplication(Boolean(this._state && !this._stale)));
        this._signalId = Gio.DBus.session.signal_subscribe(
            null, INTERFACE, 'State', OBJECT_PATH, null, Gio.DBusSignalFlags.NONE,
            (_connection, _sender, _path, _iface, _signal, parameters) => {
                try {
                    const state = JSON.parse(parameters.deepUnpack()[0]);
                    this._lastStateAt = GLib.get_monotonic_time();
                    this._stale = false;
                    this._setState(state);
                } catch (error) {
                    console.debug(`Audio Matrix state parse failed: ${error}`);
                }
            });
        this._watchdogId = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 4, () => {
            const elapsed = (GLib.get_monotonic_time() - this._lastStateAt) / GLib.USEC_PER_SEC;
            if (elapsed > STALE_AFTER_SECONDS && !this._stale) {
                this._stale = true;
                this._setUnavailable();
            }
            return GLib.SOURCE_CONTINUE;
        });
        this.connect('clicked', () => {
            if (this._state?.connected)
                this._extension._send('toggle_mute', '');
            else
                this.checked = false;
        });
    }

    _setState(state) {
        this._state = state;
        this._language = state.language === 'en' ? 'en' : state.language === 'ru' ? 'ru' : this._language;
        const payload = JSON.stringify(state);
        if (payload === this._lastPayload)
            return;
        this._lastPayload = payload;
        this._render();
    }

    _setUnavailable() {
        this._state = {connected: false, outputs: [], channelRoutes: [], mode: 'off', profile: '', muted: false};
        this._lastPayload = '';
        this._render();
    }

    _render() {
        const t = TEXT[this._language];
        const state = this._state;
        const connected = Boolean(state?.connected);
        const unavailable = !state;
        this.subtitle = connected
            ? `${state.profile || 'Profile'} · ${t.mode[state.mode] || state.mode}${state.muted ? (this._language === 'ru' ? ' · без звука' : ' · muted') : ''}`
            : unavailable ? t.connecting : t.unavailable;
        this.checked = connected && !state.muted;
        this._dynamic.removeAll();

        this._addHeading(t.outputs);
        for (const output of (connected ? state.outputs || [] : [])) {
            const selected = output.name === state.selectedOutput;
            const defaultMark = output.default ? ` · ${t.default}` : '';
            const channels = Number.isFinite(output.channels) ? ` · ${t.channels(output.channels)}` : '';
            const item = new PopupMenu.PopupMenuItem(`${selected ? '✓ ' : ''}${output.description}${channels}${defaultMark}`);
            item.setSensitive(connected);
            item.connect('activate', () => this._extension._send('select_output', output.name));
            this._dynamic.addMenuItem(item);
        }

        this._dynamic.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this._addHeading(t.routeMode);
        for (const mode of MODES) {
            const item = new PopupMenu.PopupMenuItem(`${mode === state?.mode ? '✓ ' : ''}${t.mode[mode]}`);
            item.setSensitive(connected);
            item.connect('activate', () => this._extension._send('set_mode', mode));
            this._dynamic.addMenuItem(item);
        }

        this._dynamic.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this._addHeading(t.channelMaps);
        for (const route of (connected ? state.channelRoutes || [] : [])) {
            const current = t.channelMap[route.channel_map] || route.channel_map;
            const submenu = new PopupMenu.PopupSubMenuMenuItem(`${route.name} · ${current}`, false);
            submenu.setSensitive(connected);
            for (const channelMap of CHANNEL_MAPS) {
                const item = new PopupMenu.PopupMenuItem(`${channelMap === route.channel_map ? '✓ ' : ''}${t.channelMap[channelMap]}`);
                item.setSensitive(connected);
                item.connect('activate', () => this._extension._send('set_channel_map', `${route.id}\t${channelMap}`));
                submenu.menu.addMenuItem(item);
            }
            this._dynamic.addMenuItem(submenu);
        }

        const muteItem = new PopupMenu.PopupMenuItem(state?.muted ? t.unmute : t.mute);
        muteItem.setSensitive(connected);
        muteItem.connect('activate', () => {
            if (connected)
                this._extension._send('toggle_mute', '');
        });
        this._dynamic.addMenuItem(muteItem);
    }

    _addHeading(text) {
        const heading = new PopupMenu.PopupMenuItem(text);
        heading.setSensitive(false);
        this._dynamic.addMenuItem(heading);
    }

    destroy() {
        if (this._signalId)
            Gio.DBus.session.signal_unsubscribe(this._signalId);
        if (this._watchdogId)
            GLib.Source.remove(this._watchdogId);
        super.destroy();
    }
});

const AudioMatrixIndicator = GObject.registerClass(class AudioMatrixIndicator extends QuickSettings.SystemIndicator {
    _init(extension) {
        super._init();
        this.quickSettingsItems.push(new AudioMatrixToggle(extension));
    }
    destroy() {
        this.quickSettingsItems.forEach(item => item.destroy());
        super.destroy();
    }
});

export default class AudioMatrixQuickSettingsExtension extends Extension {
    enable() {
        this._indicator = new AudioMatrixIndicator(this);
        Main.panel.statusArea.quickSettings.addExternalIndicator(this._indicator);
    }
    _send(name, value) {
        Gio.DBus.session.emit_signal(null, OBJECT_PATH, INTERFACE, 'Command', new GLib.Variant('(ss)', [name, value]));
    }
    _openApplication(bridgeConnected) {
        if (bridgeConnected) {
            this._send('open', '');
            return;
        }
        try {
            const app = GioUnix.DesktopAppInfo.new('audiomatrix-AudioMatrix.desktop');
            if (app?.launch([], null))
                return;
        } catch (error) {
            console.debug(`Audio Matrix desktop launch failed: ${error}`);
        }
        try {
            Gio.Subprocess.new(['/opt/audiomatrix/bin/AudioMatrix'], Gio.SubprocessFlags.NONE);
        } catch (error) {
            console.debug(`Audio Matrix executable launch failed: ${error}`);
        }
    }
    disable() {
        this._indicator?.destroy();
        this._indicator = null;
    }
}
