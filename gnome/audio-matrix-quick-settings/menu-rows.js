import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as Slider from 'resource:///org/gnome/shell/ui/slider.js';
import Pango from 'gi://Pango';
import Clutter from 'gi://Clutter';
import St from 'gi://St';
import {boxLayout} from './menu-layout.js';
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
        const row = boxLayout({spacing: 12, x_expand: true, y_expand: true});
        row.add_child(new St.Icon({icon_name: 'audio-x-generic-symbolic', style_class: 'popup-menu-icon'}));
        const labels = boxLayout({vertical: true, spacing: 2, x_expand: true, y_expand: true});
        labels.add_child(new St.Label({text: t.title, y_expand: true}));
        const subtitle = new St.Label({
            text: state?.activeRoute || state?.profile || (available ? '—' : t.unavailable),
            style_class: 'popup-status-menu-item', y_expand: true,
        });
        subtitle.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        item._audioMatrixSubtitle = subtitle;
        labels.add_child(subtitle);
        row.add_child(labels);
        item._audioMatrixOpenButton = this.iconButton('preferences-system-symbolic', t.open,
            () => this._onOpen(available));
        row.add_child(item._audioMatrixOpenButton);
        item.add_child(row);
        this._section.addMenuItem(item);
        return item;
    }

    updateHeader(item, state, available) {
        if (!item?._audioMatrixSubtitle) return;
        const t = TEXT[this._getLanguage()];
        item._audioMatrixSubtitle.text = state?.activeRoute || state?.profile || (available ? '—' : t.unavailable);
        item._audioMatrixOpenButton.accessible_name = t.open;
    }

    addStatus(text, iconName) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = boxLayout({spacing: 10, x_expand: true});
        row.add_child(new St.Icon({icon_name: iconName, style_class: 'popup-menu-icon'}));
        const label = new St.Label({text, x_expand: true, y_expand: true, style_class: 'popup-status-menu-item'});
        label.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        item._audioMatrixStatusLabel = label;
        row.add_child(label);
        item.add_child(row);
        this._section.addMenuItem(item);
        return item;
    }

    updateStatus(item, text) {
        if (item?._audioMatrixStatusLabel) item._audioMatrixStatusLabel.text = text;
    }

    addAction(text, callback, iconName = null, target = this._section) {
        const item = iconName ? new PopupMenu.PopupImageMenuItem(text, iconName) : new PopupMenu.PopupMenuItem(text);
        item.connect('activate', callback);
        target.addMenuItem(item);
        return item;
    }


    addLevelRow(options, target = this._section) {
        const row = this.createLevelRow(options);
        target.addMenuItem(row);
        return row;
    }

    createLevelRow(options) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = boxLayout({vertical: true, spacing: 3, x_expand: true, y_expand: true});
        const icon = options.icon || 'audio-x-generic-symbolic';
        const iconActor = typeof icon === 'string'
            ? new St.Icon({icon_name: icon, style_class: 'popup-menu-icon'})
            : new St.Icon({gicon: icon.gicon, style_class: 'popup-menu-icon'});
        const heading = boxLayout({spacing: 7, x_expand: true, y_expand: true});
        heading.add_child(iconActor);
        const name = new St.Label({text: String(options.name || ''), x_expand: true, y_expand: true});
        name.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        heading.add_child(name);
        const initial = options.muted ? 0 : Math.max(0, Math.min(1, Number(options.value) || 0));
        const percentage = new St.Label({text: options.displayValue || `${Math.round(initial * 100)}%`,
            style_class: 'popup-status-menu-item'});
        percentage.set_width(42);
        percentage.x_align = Clutter.ActorAlign.END;
        heading.add_child(percentage);
        row.add_child(heading);
        const controls = boxLayout({spacing: 7, x_expand: true, y_expand: true});
        const slider = new Slider.Slider(initial);
        slider.x_expand = true;
        slider.accessible_name = `${options.name || ''} — ${TEXT[this._getLanguage()].percent}`;
        controls.add_child(slider);
        const state = {slider, percentage, muteButton: null, name, iconActor, options: {...options}};
        state.valueSignalId = this._connectSlider(slider, percentage, state);
        if (typeof options.onMute === 'function') {
            const muted = Boolean(options.muted);
            const t = TEXT[this._getLanguage()];
            state.muteButton = this.iconButton(
                muted ? 'audio-volume-muted-symbolic' : 'audio-volume-high-symbolic',
                options.mutedLabel || (muted ? t.unmute : t.mute), () => state.options.onMute?.());
            controls.add_child(state.muteButton);
        }
        row.add_child(controls);
        item.add_child(row);
        LEVEL_ROW_STATE.set(item, state);
        return item;
    }

    updateLevelRow(item, options) {
        const state = LEVEL_ROW_STATE.get(item);
        if (!state) return;
        state.options = {...state.options, ...options};
        const current = state.options;
        const initial = current.muted ? 0 : Math.max(0, Math.min(1, Number(current.value) || 0));
        state.slider.block_signal_handler(state.valueSignalId);
        state.slider.value = initial;
        state.slider.unblock_signal_handler(state.valueSignalId);
        state.percentage.text = current.displayValue || `${Math.round(initial * 100)}%`;
        state.name.text = String(current.name || '');
        state.slider.accessible_name = `${current.name || ''} — ${TEXT[this._getLanguage()].percent}`;
        const icon = current.icon || 'audio-x-generic-symbolic';
        if (typeof icon === 'string') {
            state.iconActor.icon_name = icon;
            state.iconActor.gicon = null;
        } else {
            state.iconActor.gicon = icon.gicon;
            state.iconActor.icon_name = null;
        }
        if (!state.muteButton) return;
        const muted = Boolean(current.muted);
        const t = TEXT[this._getLanguage()];
        const iconActor = state.muteButton.get_child();
        iconActor.icon_name = muted ? 'audio-volume-muted-symbolic' : 'audio-volume-high-symbolic';
        state.muteButton.accessible_name = current.mutedLabel || (muted ? t.unmute : t.mute);
    }

    _connectSlider(slider, percentage, state) {
        const key = String(state.options.id);
        slider.connect('drag-begin', () => this._onDragBegin());
        const valueSignalId = slider.connect('notify::value', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            const options = state.options;
            percentage.text = options.formatValue
                ? options.formatValue(value) : `${Math.round(value * 100)}%`;
            if (typeof options.onValue === 'function')
                this._queueSliderCommand(key, value, next => state.options.onValue?.(next));
        });
        slider.connect('drag-end', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            if (typeof state.options.onValue === 'function')
                this._queueSliderCommand(key, value, next => state.options.onValue?.(next), true);
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


}
