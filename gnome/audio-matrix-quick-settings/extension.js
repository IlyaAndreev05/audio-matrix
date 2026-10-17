import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as Slider from 'resource:///org/gnome/shell/ui/slider.js';
import Gio from 'gi://Gio';
import GioUnix from 'gi://GioUnix';
import GLib from 'gi://GLib';
import Pango from 'gi://Pango';
import Shell from 'gi://Shell';
import St from 'gi://St';

const OBJECT_PATH = '/org/ilya/AudioMatrix';
const INTERFACE = 'org.ilya.AudioMatrix';
const API_VERSION = 2;
const STALE_AFTER_SECONDS = 12;

const TEXT = {
    ru: {
        title: 'Audio Matrix', connecting: 'Подключение к движку…', unavailable: 'Движок Audio Matrix недоступен',
        outputs: 'УСТРОЙСТВА ВЫВОДА', applications: 'ПРИЛОЖЕНИЯ', open: 'Эквалайзер и маршруты — открыть редактор',
        volume: 'Громкость', mute: 'Выключить звук', unmute: 'Включить звук', streams: 'Потоки',
        profile: 'Профиль', route: 'Маршрут', noApplications: 'Нет активных приложений',
        controls: 'ЗАКРЕПЛЁННЫЕ НАСТРОЙКИ', enabled: 'Включено', disabled: 'Выключено',
        percent: 'Уровень громкости',
        commandError: 'Команда не выполнена',
    },
    en: {
        title: 'Audio Matrix', connecting: 'Connecting to engine…', unavailable: 'Audio Matrix engine unavailable',
        outputs: 'OUTPUT DEVICES', applications: 'APPLICATIONS', open: 'EQ and routes — open editor',
        volume: 'Volume', mute: 'Mute', unmute: 'Unmute', streams: 'Streams',
        profile: 'Profile', route: 'Route', noApplications: 'No active applications',
        controls: 'PINNED CONTROLS', enabled: 'Enabled', disabled: 'Disabled',
        percent: 'Volume level',
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
        this._draggingSliders = 0;
        this._renderPending = false;
        this._renderTimer = 0;
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
                        this._requestRender();
                    } else if (this._commandError) {
                        this._commandError = '';
                        this._requestRender();
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
                this._requestRender();
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
        this._requestRender();
    }

    _requestRender() {
        if (this._draggingSliders > 0) {
            this._renderPending = true;
            return;
        }
        this._render();
    }

    _beginSliderDrag() {
        this._draggingSliders++;
    }

    _endSliderDrag() {
        this._draggingSliders = Math.max(0, this._draggingSliders - 1);
        if (this._draggingSliders !== 0 || !this._renderPending)
            return;
        this._renderPending = false;
        if (this._renderTimer)
            GLib.Source.remove(this._renderTimer);
        this._renderTimer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 250, () => {
            this._renderTimer = 0;
            if (this._draggingSliders === 0)
                this._render();
            else
                this._renderPending = true;
            return GLib.SOURCE_REMOVE;
        });
    }

    _queueSliderCommand(id, value, callback, immediate = false) {
        const key = String(id);
        const previous = this._sliderTimers.get(key);
        if (previous) {
            GLib.Source.remove(previous);
            this._sliderTimers.delete(key);
        }
        if (immediate) {
            callback(value);
            return;
        }
        const timer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 140, () => {
            this._sliderTimers.delete(key);
            callback(value);
            return GLib.SOURCE_REMOVE;
        });
        this._sliderTimers.set(key, timer);
    }

    _render() {
        if (!this._dynamic)
            return;
        const t = TEXT[this._language];
        this._dynamic.removeAll();
        const available = Boolean(this._state?.apiVersion === API_VERSION && this._state?.connected && !this._stale);
        this._addHeader(t, available);
        if (this._commandError)
            this._addStatus(`${t.commandError}: ${this._commandError}`, 'dialog-warning-symbolic');
        if (!available) {
            this._addStatus(this._state ? t.unavailable : t.connecting, 'dialog-information-symbolic');
            this._addAction(t.open, () => this._openApplication(false), 'preferences-system-symbolic');
            return;
        }

        this._addHeading(t.applications);
        const applications = this._state.applications || [];
        if (applications.length === 0)
            this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(t.noApplications, {reactive: false, can_focus: false}));
        for (const application of applications) {
            this._addLevelRow({
                id: `app:${application.id}`, name: application.name || application.id,
                icon: this._applicationIcon(application), value: application.volume,
                muted: application.muted, mutedLabel: application.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_app_volume', `${application.id}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_app_mute', application.id),
            });
            const streams = application.streams || [];
            if (streams.length > 0) {
                const submenu = new PopupMenu.PopupSubMenuMenuItem(`${t.streams} (${streams.length})`, false);
                for (const stream of streams) {
                    submenu.menu.addMenuItem(this._createLevelRow({
                        id: `stream:${stream.id}`, name: stream.description || stream.name,
                        icon: 'audio-x-generic-symbolic', value: stream.volume, muted: stream.muted,
                        mutedLabel: stream.muted ? t.unmute : t.mute,
                        onValue: value => this._send('set_stream_volume', `${stream.id}\t${value.toFixed(3)}`),
                        onMute: () => this._send('toggle_stream_mute', String(stream.id)),
                    }));
                }
                this._dynamic.addMenuItem(submenu);
            }
        }
        this._addHeading(t.outputs);
        for (const output of this._state.outputs || []) {
            this._addLevelRow({
                id: `output:${output.name}`, name: output.description || output.name,
                icon: this._outputIcon(output), value: output.volume, muted: output.muted,
                mutedLabel: output.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_output_volume', `${output.name}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_output_mute', output.name),
            });
        }
        const quickControls = this._state.quickControls || [];
        if (quickControls.length > 0) {
            this._addHeading(t.controls);
            let currentSection = null;
            for (const control of quickControls) {
                const section = control.section || t.controls;
                if (section !== currentSection) {
                    currentSection = section;
                    if (quickControls.length > 1)
                        this._addSubheading(section);
                }
                if (control.kind === 'slider') {
                    this._addLevelRow({
                        id: `graph:${control.id}`, name: control.label || control.parameterId,
                        icon: this._controlIcon(control), value: control.value,
                        onValue: value => this._send('set_quick_control', `${control.id}\t${value.toFixed(3)}`),
                    });
                } else if (control.kind === 'toggle') {
                    const item = new PopupMenu.PopupSwitchMenuItem(
                        control.label || control.parameterId, Boolean(control.value));
                    item.connect('toggled', () => this._send('toggle_quick_control', control.id));
                    this._dynamic.addMenuItem(item);
                } else if (control.kind === 'links') {
                    this._addGraphLinks(control);
                }
            }
        }
        this._dynamic.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this._addAction(t.open, () => this._openApplication(true), 'preferences-system-symbolic');
    }

    _addHeader(t, available) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = new St.BoxLayout({spacing: 12, x_expand: true, y_expand: true});
        row.add_child(new St.Icon({icon_name: 'audio-x-generic-symbolic', style_class: 'popup-menu-icon'}));
        const labels = new St.BoxLayout({vertical: true, spacing: 2, x_expand: true, y_expand: true});
        labels.add_child(new St.Label({text: t.title, y_expand: true}));
        const subtitle = new St.Label({
            text: this._state?.activeRoute || this._state?.profile || (available ? '—' : t.unavailable),
            style_class: 'popup-status-menu-item', y_expand: true,
        });
        subtitle.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        labels.add_child(subtitle);
        row.add_child(labels);
        row.add_child(this._iconButton('preferences-system-symbolic', t.open,
            () => this._openApplication(available)));
        item.add_child(row);
        this._dynamic.addMenuItem(item);
    }

    _addStatus(text, iconName) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = new St.BoxLayout({spacing: 10, x_expand: true});
        row.add_child(new St.Icon({icon_name: iconName, style_class: 'popup-menu-icon'}));
        const label = new St.Label({text, x_expand: true, y_expand: true, style_class: 'popup-status-menu-item'});
        label.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        row.add_child(label);
        item.add_child(row);
        this._dynamic.addMenuItem(item);
    }

    _addHeading(text) {
        this._dynamic.addMenuItem(new PopupMenu.PopupSeparatorMenuItem(text));
    }

    _addSubheading(text) {
        this._dynamic.addMenuItem(new PopupMenu.PopupMenuItem(text, {reactive: false, can_focus: false}));
    }

    _addAction(text, callback, iconName = null) {
        const item = iconName ? new PopupMenu.PopupImageMenuItem(text, iconName) : new PopupMenu.PopupMenuItem(text);
        item.connect('activate', callback);
        this._dynamic.addMenuItem(item);
    }

    _addLevelRow(options) {
        this._dynamic.addMenuItem(this._createLevelRow(options));
    }

    _createLevelRow(options) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = new St.BoxLayout({spacing: 9, x_expand: true, y_expand: true});
        const icon = options.icon || 'audio-x-generic-symbolic';
        row.add_child(typeof icon === 'string'
            ? new St.Icon({icon_name: icon, style_class: 'popup-menu-icon'})
            : new St.Icon({gicon: icon.gicon, style_class: 'popup-menu-icon'}));
        const name = new St.Label({text: String(options.name || ''), x_expand: true, y_expand: true});
        name.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        name.set_width(112);
        row.add_child(name);

        const initial = Math.max(0, Math.min(1, Number(options.value) || 0));
        const slider = new Slider.Slider(initial);
        slider.set_width(88);
        slider.x_expand = true;
        slider.accessible_name = `${options.name || ''} — ${TEXT[this._language].percent}`;
        row.add_child(slider);
        const percentage = new St.Label({text: `${Math.round(initial * 100)}%`, style_class: 'popup-status-menu-item'});
        percentage.set_width(36);
        row.add_child(percentage);

        const key = String(options.id);
        slider.connect('drag-begin', () => this._beginSliderDrag());
        slider.connect('notify::value', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            percentage.text = `${Math.round(value * 100)}%`;
            if (this._state?.connected && !this._stale && typeof options.onValue === 'function')
                this._queueSliderCommand(key, value, options.onValue);
        });
        slider.connect('drag-end', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            if (this._state?.connected && !this._stale && typeof options.onValue === 'function')
                this._queueSliderCommand(key, value, options.onValue, true);
            this._endSliderDrag();
        });
        if (typeof options.onMute === 'function') {
            const muted = Boolean(options.muted);
            row.add_child(this._iconButton(
                muted ? 'audio-volume-muted-symbolic' : 'audio-volume-high-symbolic',
                options.mutedLabel || (muted ? TEXT[this._language].unmute : TEXT[this._language].mute),
                options.onMute));
        }
        item.add_child(row);
        return item;
    }

    _iconButton(iconName, accessibleName, callback) {
        const button = new St.Button({
            style_class: 'icon-button', can_focus: true, reactive: true, track_hover: true,
            accessible_name: accessibleName,
        });
        button.set_child(new St.Icon({icon_name: iconName, style_class: 'popup-menu-icon'}));
        button.connect('clicked', callback);
        return button;
    }

    _applicationIcon(application) {
        const appSystem = Shell.AppSystem.get_default();
        const candidates = [application.application_id, application.id?.replace(/^app:/, ''), application.process]
            .filter(Boolean);
        for (const candidate of candidates) {
            for (const id of [candidate, `${candidate}.desktop`]) {
                try {
                    const icon = appSystem.lookup_app(id)?.get_icon();
                    if (icon)
                        return {gicon: icon};
                } catch (_error) {
                    // The stream can belong to an app without an installed desktop file.
                }
            }
        }
        const name = `${application.name || ''} ${application.process || ''}`.toLowerCase();
        if (name.includes('chrome'))
            return 'web-browser-symbolic';
        if (name.includes('music') || name.includes('spotify'))
            return 'audio-x-generic-symbolic';
        return 'application-x-executable-symbolic';
    }

    _outputIcon(output) {
        const name = `${output.name || ''} ${output.description || ''}`.toLowerCase();
        if (name.includes('bluez') || name.includes('bluetooth') || name.includes('liberty'))
            return 'audio-headphones-symbolic';
        if (name.includes('hdmi') || name.includes('benq') || name.includes('monitor'))
            return 'video-display-symbolic';
        if (name.includes('usb') || name.includes('dac'))
            return 'audio-card-symbolic';
        return 'audio-speakers-symbolic';
    }

    _controlIcon(control) {
        const name = `${control.label || ''} ${control.parameterId || ''}`.toLowerCase();
        if (name.includes('вибр') || name.includes('haptic') || name.includes('bass') || name.includes('бас'))
            return 'audio-speakers-symbolic';
        if (name.includes('event') || name.includes('событ'))
            return 'input-keyboard-symbolic';
        return 'audio-x-generic-symbolic';
    }

    _addGraphLinks(control) {
        const selected = new Set(Array.isArray(control.value) ? control.value : []);
        const options = control.options || [];
        const submenu = new PopupMenu.PopupSubMenuMenuItem(
            `${control.label || control.parameterId} (${selected.size}/${options.length})`, false);
        for (const option of options) {
            const active = selected.has(option.id);
            const item = new PopupMenu.PopupSwitchMenuItem(option.label || option.id, active);
            item.connect('toggled', () => this._send('set_quick_control_choice', `${control.id}\t${option.id}`));
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
            this._requestRender();
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
            this._requestRender();
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
        if (this._renderTimer)
            GLib.Source.remove(this._renderTimer);
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
