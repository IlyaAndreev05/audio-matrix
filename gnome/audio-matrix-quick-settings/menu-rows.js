import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as Slider from 'resource:///org/gnome/shell/ui/slider.js';
import Pango from 'gi://Pango';
import Clutter from 'gi://Clutter';
import Shell from 'gi://Shell';
import St from 'gi://St';
import {boxLayout} from './menu-layout.js';
import {TEXT} from './menu-text.js';

const LEVEL_ROW_STATE = new WeakMap();
const HEADER_STATE = new WeakMap();

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
        const row = boxLayout({spacing: 12, x_expand: true, y_expand: true});
        row.add_child(new St.Icon({icon_name: 'audio-x-generic-symbolic', style_class: 'popup-menu-icon'}));
        const labels = boxLayout({vertical: true, spacing: 2, x_expand: true, y_expand: true});
        const title = new St.Label({text: t.title, y_expand: true});
        labels.add_child(title);
        const subtitle = new St.Label({
            text: state?.activeRoute || state?.profile || (available ? '—' : t.unavailable),
            style_class: 'popup-status-menu-item', y_expand: true,
        });
        subtitle.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        labels.add_child(subtitle);
        row.add_child(labels);
        row.add_child(this.iconButton('preferences-system-symbolic', t.open,
            () => this._onOpen(Boolean(HEADER_STATE.get(item)?.available))));
        item.add_child(row);
        item._audioMatrixKey = 'header';
        item._audioMatrixKind = 'header';
        const header = {item, title, subtitle};
        item._audioMatrixPatch = incoming => {
            const next = HEADER_STATE.get(incoming);
            if (next) this.updateHeader(header, next.state, next.available);
        };
        HEADER_STATE.set(item, {state, available});
        return header;
    }

    updateHeader(header, state, available) {
        const t = TEXT[this._getLanguage()];
        const profile = (state?.profiles || []).find(item => item.id === state?.activeProfileId);
        header.title.text = t.title;
        header.subtitle.text = profile?.name || state?.profile || state?.activeRoute || (available ? '—' : t.unavailable);
        HEADER_STATE.set(header.item, {state, available});
    }

    addStatus(text, iconName) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = boxLayout({spacing: 10, x_expand: true});
        const icon = new St.Icon({icon_name: iconName, style_class: 'popup-menu-icon'});
        row.add_child(icon);
        const label = new St.Label({text, x_expand: true, y_expand: true, style_class: 'popup-status-menu-item'});
        label.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        row.add_child(label);
        item.add_child(row);
        item._audioMatrixKey = 'status';
        return {item, icon, label};
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
        return item;
    }

    addLevelRow(options, target = this._section) {
        const row = this.createLevelRow(options);
        return target.addMenuItem(row) || row;
    }

    createLevelRow(options) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = boxLayout({vertical: true, spacing: 3, x_expand: true, y_expand: true});
        const iconName = options.icon || 'audio-x-generic-symbolic';
        const icon = typeof iconName === 'string'
            ? new St.Icon({icon_name: iconName, style_class: 'popup-menu-icon'})
            : new St.Icon({gicon: iconName.gicon, style_class: 'popup-menu-icon'});
        const heading = boxLayout({spacing: 7, x_expand: true, y_expand: true});
        heading.add_child(icon);
        const name = new St.Label({text: String(options.name || ''), x_expand: true, y_expand: true});
        name.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        heading.add_child(name);
        const initial = options.muted ? 0 : Math.max(0, Math.min(1, Number(options.value) || 0));
        const percentage = new St.Label({text: options.displayValue || `${Math.round(initial * 100)}%`, style_class: 'popup-status-menu-item'});
        percentage.set_width(42);
        percentage.x_align = Clutter.ActorAlign.END;
        heading.add_child(percentage);
        row.add_child(heading);
        const controls = boxLayout({spacing: 7, x_expand: true, y_expand: true});
        const slider = new Slider.Slider(initial);
        slider.x_expand = true;
        slider.reactive = !options.disabled;
        slider.can_focus = !options.disabled;
        slider.opacity = options.disabled ? 0.55 : 1;
        slider.accessible_name = `${options.name || ''} — ${TEXT[this._getLanguage()].percent}`;
        controls.add_child(slider);
        const binding = {options};
        const valueSignalId = this._connectSlider(slider, percentage, binding);
        let muteButton = null;
        if (typeof options.onMute === 'function' || options.allowMuteButton) {
            const muted = Boolean(options.muted);
            const t = TEXT[this._getLanguage()];
            muteButton = this.iconButton(
                muted ? 'audio-volume-muted-symbolic' : 'audio-volume-high-symbolic',
                options.mutedLabel || (muted ? t.unmute : t.mute), () => binding.options.onMute?.());
            controls.add_child(muteButton);
            muteButton.reactive = !options.disabled && typeof options.onMute === 'function';
            muteButton.can_focus = !options.disabled && typeof options.onMute === 'function';
            muteButton.opacity = options.disabled || typeof options.onMute !== 'function' ? 0.55 : 1;
        }
        row.add_child(controls);
        item.add_child(row);
        LEVEL_ROW_STATE.set(item, {slider, percentage, valueSignalId, muteButton, icon, name, binding});
        item._audioMatrixKey = String(options.key || options.id);
        item._audioMatrixKind = 'level';
        item._audioMatrixPatch = incoming => {
            const next = LEVEL_ROW_STATE.get(incoming);
            if (next)
                this.updateLevelRow(item, next.binding.options);
        };
        return item;
    }

    updateLevelRow(item, options) {
        const state = LEVEL_ROW_STATE.get(item);
        if (!state) return;
        state.binding.options = options;
        const initial = options.muted ? 0 : Math.max(0, Math.min(1, Number(options.value) || 0));
        state.slider.block_signal_handler(state.valueSignalId);
        state.slider.value = initial;
        state.slider.unblock_signal_handler(state.valueSignalId);
        state.slider.reactive = !options.disabled;
        state.slider.can_focus = !options.disabled;
        state.slider.opacity = options.disabled ? 0.55 : 1;
        state.slider.accessible_name = `${options.name || ''} — ${TEXT[this._getLanguage()].percent}`;
        state.name.text = String(options.name || '');
        if (typeof options.icon === 'string')
            state.icon.icon_name = options.icon;
        else if (options.icon?.gicon)
            state.icon.gicon = options.icon.gicon;
        state.percentage.text = options.displayValue || `${Math.round(initial * 100)}%`;
        if (!state.muteButton) return;
        const muted = Boolean(options.muted);
        const t = TEXT[this._getLanguage()];
        const icon = state.muteButton.get_child();
        icon.icon_name = muted ? 'audio-volume-muted-symbolic' : 'audio-volume-high-symbolic';
        state.muteButton.accessible_name = options.mutedLabel || (muted ? t.unmute : t.mute);
        state.muteButton.reactive = !options.disabled && typeof options.onMute === 'function';
        state.muteButton.can_focus = !options.disabled && typeof options.onMute === 'function';
        state.muteButton.opacity = options.disabled || typeof options.onMute !== 'function' ? 0.55 : 1;
    }

    _connectSlider(slider, percentage, binding) {
        const key = String(binding.options.key || binding.options.id);
        slider.connect('drag-begin', () => this._onDragBegin());
        const valueSignalId = slider.connect('notify::value', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            const options = binding.options;
            percentage.text = options.formatValue ? options.formatValue(value) : `${Math.round(value * 100)}%`;
            if (typeof options.onValue === 'function' && !options.disabled)
                this._queueSliderCommand(key, value, next => binding.options.onValue?.(next));
        });
        slider.connect('drag-end', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            if (typeof binding.options.onValue === 'function' && !binding.options.disabled)
                this._queueSliderCommand(key, value, next => binding.options.onValue?.(next), true);
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

}
