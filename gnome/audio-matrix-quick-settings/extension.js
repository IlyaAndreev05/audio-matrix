import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Gio from 'gi://Gio';
import GioUnix from 'gi://GioUnix';
import GLib from 'gi://GLib';

const OBJECT_PATH = '/org/ilya/AudioMatrix';
const INTERFACE = 'org.ilya.AudioMatrix';
const API_VERSION = 2;
const STALE_AFTER_SECONDS = 12;

const TEXT = {
    ru: {
        title: 'Audio Matrix', connecting: 'Подключение к движку…', unavailable: 'Движок Audio Matrix недоступен',
        outputs: 'УСТРОЙСТВА ВЫВОДА', applications: 'ПРИЛОЖЕНИЯ', open: 'Эквалайзер и маршруты — открыть редактор',
        volume: 'Громкость', mute: 'Mute', unmute: 'Включить звук', streams: 'Потоки',
        profile: 'Профиль', route: 'Маршрут', noApplications: 'Нет активных приложений',
        controls: 'ЗАКРЕПЛЁННЫЕ ПАРАМЕТРЫ', enabled: 'Включено', disabled: 'Выключено',
        commandError: 'Команда не выполнена',
    },
    en: {
        title: 'Audio Matrix', connecting: 'Connecting to engine…', unavailable: 'Audio Matrix engine unavailable',
        outputs: 'OUTPUT DEVICES', applications: 'APPLICATIONS', open: 'EQ and routes — open editor',
        volume: 'Volume', mute: 'Mute', unmute: 'Unmute', streams: 'Streams',
        profile: 'Profile', route: 'Route', noApplications: 'No active applications',
        controls: 'PINNED CONTROLS', enabled: 'Enabled', disabled: 'Disabled',
        commandError: 'Command failed',
    },
};

function systemLanguage() {
    return GLib.get_language_names().some(name => name.startsWith('ru')) ? 'ru' : 'en';
}

export default class AudioMatrixQuickSettingsExtension extends Extension {
    enable() {
        this._language = systemLanguage();
        this._state = null;
        this._stale = false;
        this._lastPayload = '';
        this._lastStateAt = GLib.get_monotonic_time();
        this._sliderTimers = new Map();
        this._pendingCommands = new Map();
        this._commandError = '';
        this._dynamic = new PopupMenu.PopupMenuSection();
        this._signalId = Gio.DBus.session.signal_subscribe(
            null, INTERFACE, 'State', OBJECT_PATH, null, Gio.DBusSignalFlags.NONE,
            (_connection, _sender, _path, _iface, _signal, parameters) => {
                try {
                    this._lastStateAt = GLib.get_monotonic_time();
                    this._stale = false;
                    this._setState(JSON.parse(parameters.deepUnpack()[0]));
                } catch (error) {
                    console.debug(`Audio Matrix state parse failed: ${error}`);
                }
            });
        this._resultSignalId = Gio.DBus.session.signal_subscribe(
            null, INTERFACE, 'Result', OBJECT_PATH, null, Gio.DBusSignalFlags.NONE,
            (_connection, _sender, _path, _iface, _signal, parameters) => {
                try {
                    const [version, requestId, status, message] = parameters.deepUnpack();
                    if (version !== API_VERSION)
                        return;
                    const timer = this._pendingCommands.get(requestId);
                    if (timer)
                        GLib.Source.remove(timer);
                    this._pendingCommands.delete(requestId);
                    if (status !== 'ok') {
                        this._commandError = String(message || 'Unknown error');
                        this._render();
                    } else if (this._commandError) {
                        this._commandError = '';
                        this._render();
                    }
                } catch (error) {
                    console.debug(`Audio Matrix command result parse failed: ${error}`);
                }
            });
        this._watchdogId = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 4, () => {
            const age = (GLib.get_monotonic_time() - this._lastStateAt) / GLib.USEC_PER_SEC;
            if (age > STALE_AFTER_SECONDS && !this._stale) {
                this._stale = true;
                this._state = null;
                this._render();
            }
            return GLib.SOURCE_CONTINUE;
        });
        this._retryId = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 1, () => {
            if (this._attachToSystemVolumeMenu()) {
                this._retryId = 0;
                this._render();
                return GLib.SOURCE_REMOVE;
            }
            return GLib.SOURCE_CONTINUE;
        });
    }

    _attachToSystemVolumeMenu() {
        const indicator = Main.panel.statusArea.quickSettings?._volumeOutput;
        const menu = indicator?._output?.menu;
        if (!menu || !menu.addMenuItem)
            return false;
        if (this._menu === menu)
            return true;
        if (this._menu && this._dynamic)
            this._removeSection();
        this._menu = menu;
        this._menu.addMenuItem(this._dynamic);
        return true;
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

    _render() {
        if (!this._dynamic)
            return;
        const t = TEXT[this._language];
        this._dynamic.removeAll();
        this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(t.title, {reactive: false, can_focus: false}));
        const available = Boolean(this._state?.apiVersion === API_VERSION && this._state?.connected && !this._stale);
        if (this._commandError)
            this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(`${t.commandError}: ${this._commandError}`,
                {reactive: false, can_focus: false}));
        if (!available) {
            this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(this._state ? t.unavailable : t.connecting,
                {reactive: false, can_focus: false}));
            this._addAction(t.open, () => this._openApplication(false));
            return;
        }

        this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(`${t.profile}: ${this._state.profile || '—'}`,
            {reactive: false, can_focus: false}));
        if (this._state.activeRoute)
            this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(`${t.route}: ${this._state.activeRoute}`,
                {reactive: false, can_focus: false}));
        const quickControls = this._state.quickControls || [];
        if (quickControls.length > 0) {
            this._dynamic.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
            this._addHeading(t.controls);
            let currentSection = null;
            for (const control of quickControls) {
                const section = control.section || t.controls;
                if (section !== currentSection) {
                    currentSection = section;
                    this._addHeading(section);
                }
                if (control.kind === 'slider') {
                    this._addGraphSlider(control);
                } else if (control.kind === 'toggle') {
                    this._addAction(`${control.label || control.parameterId}: ${control.value ? t.enabled : t.disabled}`,
                        () => this._send('toggle_quick_control', control.id));
                } else if (control.kind === 'links') {
                    this._addGraphLinks(control);
                }
            }
        }
        this._addHeading(t.outputs);
        for (const output of this._state.outputs || []) {
            const name = `${output.description || output.name}${output.default ? ' · ✓' : ''}`;
            this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(name, {reactive: false, can_focus: false}));
            this._addSlider(`${output.name} · ${t.volume}`, output.volume,
                value => this._send('set_output_volume', `${output.name}\t${value.toFixed(3)}`));
            this._addAction(output.muted ? t.unmute : t.mute,
                () => this._send('toggle_output_mute', output.name));
        }

        this._dynamic.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this._addHeading(t.applications);
        const applications = this._state.applications || [];
        if (applications.length === 0)
            this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(t.noApplications, {reactive: false, can_focus: false}));
        for (const application of applications) {
            this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(application.name || application.id,
                {reactive: false, can_focus: false}));
            this._addSlider(application.name || application.id, application.volume,
                value => this._send('set_app_volume', `${application.id}\t${value.toFixed(3)}`));
            this._addAction(application.muted ? t.unmute : t.mute,
                () => this._send('toggle_app_mute', application.id));
            const streams = application.streams || [];
            if (streams.length > 0) {
                const submenu = new PopupMenu.PopupSubMenuMenuItem(`${t.streams} (${streams.length})`, false);
                for (const stream of streams) {
                    submenu.menu.addMenuItem(new PopupMenu.PopupMenuItem(stream.description || stream.name,
                        {reactive: false, can_focus: false}));
                    const slider = new PopupMenu.PopupSliderMenuItem(Number(stream.volume) || 0);
                    slider.connect('value-changed', (_item, value) =>
                        this._send('set_stream_volume', `${stream.id}\t${value.toFixed(3)}`));
                    submenu.menu.addMenuItem(slider);
                    const mute = new PopupMenu.PopupMenuItem(stream.muted ? t.unmute : t.mute);
                    mute.connect('activate', () => this._send('toggle_stream_mute', String(stream.id)));
                    submenu.menu.addMenuItem(mute);
                }
                this._dynamic.addMenuItem(submenu);
            }
        }
        this._dynamic.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this._addAction(t.open, () => this._openApplication(true));
    }

    _addHeading(text) {
        this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(text, {reactive: false, can_focus: false}));
    }

    _addAction(text, callback) {
        const item = new PopupMenu.PopupMenuItem(text);
        item.connect('activate', callback);
        this._dynamic.addMenuItem(item);
    }

    _addSlider(label, value, callback) {
        this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(label, {reactive: false, can_focus: false}));
        const item = new PopupMenu.PopupSliderMenuItem(Math.max(0, Math.min(1, Number(value) || 0)));
        item.connect('value-changed', (_slider, current) => {
            if (this._state?.connected && !this._stale)
                callback(current);
        });
        this._dynamic.addMenuItem(item);
    }

    _addGraphSlider(control) {
        const id = String(control.id);
        this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(control.label || control.parameterId,
            {reactive: false, can_focus: false}));
        const item = new PopupMenu.PopupSliderMenuItem(Math.max(0, Math.min(1, Number(control.value) || 0)));
        item.connect('value-changed', (_slider, current) => {
            if (!this._state?.connected || this._stale)
                return;
            const pending = this._sliderTimers.get(id);
            if (pending)
                GLib.Source.remove(pending);
            const timer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 140, () => {
                this._sliderTimers.delete(id);
                this._send('set_quick_control', `${id}\t${current.toFixed(3)}`);
                return GLib.SOURCE_REMOVE;
            });
            this._sliderTimers.set(id, timer);
        });
        this._dynamic.addMenuItem(item);
    }

    _addGraphLinks(control) {
        const selected = new Set(Array.isArray(control.value) ? control.value : []);
        const options = control.options || [];
        const submenu = new PopupMenu.PopupSubMenuMenuItem(
            `${control.label || control.parameterId} (${selected.size}/${options.length})`, false);
        for (const option of options) {
            const active = selected.has(option.id);
            const item = new PopupMenu.PopupMenuItem(`${active ? '✓' : '○'}  ${option.label || option.id}`);
            item.connect('activate', () => this._send('set_quick_control_choice', `${control.id}\t${option.id}`));
            submenu.menu.addMenuItem(item);
        }
        this._dynamic.addMenuItem(submenu);
    }

    _send(name, value) {
        if (!this._state?.connected || this._stale)
            return;
        const requestId = GLib.uuid_string_random();
        const timer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 5, () => {
            this._pendingCommands.delete(requestId);
            this._commandError = 'No response from the Audio Matrix engine';
            this._render();
            return GLib.SOURCE_REMOVE;
        });
        this._pendingCommands.set(requestId, timer);
        try {
            Gio.DBus.session.emit_signal(null, OBJECT_PATH, INTERFACE, 'Command',
                new GLib.Variant('(usss)', [API_VERSION, requestId, name, value]));
        } catch (error) {
            GLib.Source.remove(timer);
            this._pendingCommands.delete(requestId);
            this._commandError = String(error);
            this._render();
        }
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

    _removeSection() {
        if (!this._menu || !this._dynamic)
            return;
        try {
            if (this._menu.removeMenuItem)
                this._menu.removeMenuItem(this._dynamic);
            else
                this._dynamic.destroy();
        } catch (error) {
            console.debug(`Audio Matrix menu cleanup failed: ${error}`);
        }
        this._menu = null;
    }

    disable() {
        for (const timer of this._sliderTimers?.values() ?? [])
            GLib.Source.remove(timer);
        this._sliderTimers?.clear();
        for (const timer of this._pendingCommands?.values() ?? [])
            GLib.Source.remove(timer);
        this._pendingCommands?.clear();
        if (this._retryId)
            GLib.Source.remove(this._retryId);
        if (this._watchdogId)
            GLib.Source.remove(this._watchdogId);
        if (this._signalId)
            Gio.DBus.session.signal_unsubscribe(this._signalId);
        if (this._resultSignalId)
            Gio.DBus.session.signal_unsubscribe(this._resultSignalId);
        this._removeSection();
        this._dynamic?.destroy();
        this._dynamic = null;
        this._state = null;
    }
}
