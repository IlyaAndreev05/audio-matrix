import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as Slider from 'resource:///org/gnome/shell/ui/slider.js';
import Pango from 'gi://Pango';
import Shell from 'gi://Shell';
import St from 'gi://St';
import {TEXT} from './menu-text.js';

const LEVEL_ROW_STATE = new WeakMap();

export default class AudioMatrixMenuRows {
    constructor({section, getLanguage, send, queueSliderCommand, onOpen, onDragBegin, onDragEnd}) {
        this._section = section;
        this._getLanguage = getLanguage;
        this._send = send;
        this._queueSliderCommand = queueSliderCommand;
        this._onOpen = onOpen;
        this._onDragBegin = onDragBegin;
        this._onDragEnd = onDragEnd;
    }

    addHeader(state, available) {
        const t = TEXT[this._getLanguage()];
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = new St.BoxLayout({spacing: 12, x_expand: true, y_expand: true});
        row.add_child(new St.Icon({icon_name: 'audio-x-generic-symbolic', style_class: 'popup-menu-icon'}));
        const labels = new St.BoxLayout({vertical: true, spacing: 2, x_expand: true, y_expand: true});
        labels.add_child(new St.Label({text: t.title, y_expand: true}));
        const subtitle = new St.Label({
            text: state?.activeRoute || state?.profile || (available ? '—' : t.unavailable),
            style_class: 'popup-status-menu-item', y_expand: true,
        });
        subtitle.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        labels.add_child(subtitle);
        row.add_child(labels);
        row.add_child(this.iconButton('preferences-system-symbolic', t.open, () => this._onOpen(available)));
        item.add_child(row);
        this._section.addMenuItem(item);
    }

    addStatus(text, iconName) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = new St.BoxLayout({spacing: 10, x_expand: true});
        row.add_child(new St.Icon({icon_name: iconName, style_class: 'popup-menu-icon'}));
        const label = new St.Label({text, x_expand: true, y_expand: true, style_class: 'popup-status-menu-item'});
        label.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        row.add_child(label);
        item.add_child(row);
        this._section.addMenuItem(item);
    }

    addHeading(text) {
        this._section.addMenuItem(new PopupMenu.PopupSeparatorMenuItem(text));
    }

    addSubheading(text, target = this._section) {
        target.addMenuItem(new PopupMenu.PopupMenuItem(text, {reactive: false, can_focus: false}));
    }

    addAction(text, callback, iconName = null, target = this._section) {
        const item = iconName ? new PopupMenu.PopupImageMenuItem(text, iconName) : new PopupMenu.PopupMenuItem(text);
        item.connect('activate', callback);
        target.addMenuItem(item);
    }

    addLevelRow(options, target = this._section) {
        const row = this.createLevelRow(options);
        target.addMenuItem(row);
        return row;
    }

    createLevelRow(options) {
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
        const initial = options.muted ? 0 : Math.max(0, Math.min(1, Number(options.value) || 0));
        const slider = new Slider.Slider(initial);
        slider.set_width(88);
        slider.x_expand = true;
        slider.accessible_name = `${options.name || ''} — ${TEXT[this._getLanguage()].percent}`;
        row.add_child(slider);
        const percentage = new St.Label({text: `${Math.round(initial * 100)}%`, style_class: 'popup-status-menu-item'});
        percentage.set_width(36);
        row.add_child(percentage);
        const valueSignalId = this._connectSlider(slider, percentage, options);
        let muteButton = null;
        if (typeof options.onMute === 'function') {
            const muted = Boolean(options.muted);
            const t = TEXT[this._getLanguage()];
            muteButton = this.iconButton(
                muted ? 'audio-volume-muted-symbolic' : 'audio-volume-high-symbolic',
                options.mutedLabel || (muted ? t.unmute : t.mute), options.onMute);
            row.add_child(muteButton);
        }
        item.add_child(row);
        LEVEL_ROW_STATE.set(item, {slider, percentage, valueSignalId, muteButton});
        return item;
    }

    updateLevelRow(item, options) {
        const state = LEVEL_ROW_STATE.get(item);
        if (!state) return;
        const initial = options.muted ? 0 : Math.max(0, Math.min(1, Number(options.value) || 0));
        state.slider.block_signal_handler(state.valueSignalId);
        state.slider.value = initial;
        state.slider.unblock_signal_handler(state.valueSignalId);
        state.percentage.text = `${Math.round(initial * 100)}%`;
        if (!state.muteButton) return;
        const muted = Boolean(options.muted);
        const t = TEXT[this._getLanguage()];
        const icon = state.muteButton.get_child();
        icon.icon_name = muted ? 'audio-volume-muted-symbolic' : 'audio-volume-high-symbolic';
        state.muteButton.accessible_name = options.mutedLabel || (muted ? t.unmute : t.mute);
    }

    _connectSlider(slider, percentage, options) {
        const key = String(options.id);
        slider.connect('drag-begin', () => this._onDragBegin());
        const valueSignalId = slider.connect('notify::value', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            percentage.text = `${Math.round(value * 100)}%`;
            if (typeof options.onValue === 'function')
                this._queueSliderCommand(key, value, options.onValue);
        });
        slider.connect('drag-end', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            if (typeof options.onValue === 'function')
                this._queueSliderCommand(key, value, options.onValue, true);
            this._onDragEnd();
        });
        return valueSignalId;
    }

    iconButton(iconName, accessibleName, callback) {
        const button = new St.Button({
            style_class: 'icon-button', can_focus: true, reactive: true, track_hover: true,
            accessible_name: accessibleName,
        });
        button.set_child(new St.Icon({icon_name: iconName, style_class: 'popup-menu-icon'}));
        button.connect('clicked', callback);
        return button;
    }

    applicationIcon(application) {
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
                    // Streams can belong to applications without installed desktop files.
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

    outputIcon(output) {
        const name = `${output.name || ''} ${output.description || ''}`.toLowerCase();
        if (name.includes('bluez') || name.includes('bluetooth') || name.includes('liberty'))
            return 'audio-headphones-symbolic';
        if (name.includes('hdmi') || name.includes('benq') || name.includes('monitor'))
            return 'video-display-symbolic';
        if (name.includes('usb') || name.includes('dac'))
            return 'audio-card-symbolic';
        return 'audio-speakers-symbolic';
    }

    controlIcon(control) {
        const name = `${control.label || ''} ${control.parameterId || ''}`.toLowerCase();
        if (name.includes('вибр') || name.includes('haptic') || name.includes('bass') || name.includes('бас'))
            return 'audio-speakers-symbolic';
        if (name.includes('event') || name.includes('событ'))
            return 'input-keyboard-symbolic';
        return 'audio-x-generic-symbolic';
    }

    addGraphLinks(control, target = this._section) {
        const selected = new Set(Array.isArray(control.value) ? control.value : []);
        const options = control.options || [];
        const submenu = new PopupMenu.PopupSubMenuMenuItem(
            `${control.label || control.parameterId} (${selected.size}/${options.length})`, false);
        for (const option of options) {
            const item = new PopupMenu.PopupSwitchMenuItem(option.label || option.id, selected.has(option.id));
            item.connect('toggled', () => this._send('set_quick_control_choice', `${control.id}\t${option.id}`));
            submenu.menu.addMenuItem(item);
        }
        target.addMenuItem(submenu);
    }
}
