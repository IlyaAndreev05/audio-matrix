import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as Slider from 'resource:///org/gnome/shell/ui/slider.js';
import Atk from 'gi://Atk';
import Pango from 'gi://Pango';
import Clutter from 'gi://Clutter';
import St from 'gi://St';
import {boxLayout} from './menu-layout.js';
import {TEXT} from './menu-text.js';

const LEVEL_ROW_STATE = new WeakMap();

function iconButton(iconName, accessibleName, callback) {
    const button = new St.Button({style_class: 'audio-matrix-icon-button', can_focus: true,
        reactive: true, track_hover: true, accessible_name: accessibleName});
    button.set_child(new St.Icon({icon_name: iconName, style_class: 'audio-matrix-button-icon'}));
    button.connect('clicked', callback);
    return button;
}

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

    addHeader(_state, available) {
        const t = TEXT.ru;
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        item.add_style_class_name('audio-matrix-heading');
        const row = boxLayout({spacing: 10, x_expand: true});
        row.add_child(new St.Icon({icon_name: 'audio-x-generic-symbolic', style_class: 'audio-matrix-group-icon'}));
        const labels = boxLayout({vertical: true, spacing: 3, x_expand: true});
        labels.add_child(new St.Label({text: t.title, style_class: 'audio-matrix-title'}));
        labels.add_child(new St.Label({text: t.subtitle, style_class: 'audio-matrix-subtitle'}));
        row.add_child(labels);
        item._audioMatrixAvailable = available;
        item._audioMatrixOpenButton = iconButton('preferences-system-symbolic', t.open,
            () => this._onOpen(item._audioMatrixAvailable));
        row.add_child(item._audioMatrixOpenButton);
        item.add_child(row);
        this._section.addMenuItem(item);
        return item;
    }

    updateHeader(item, _state, available) {
        if (item) item._audioMatrixAvailable = available;
    }

    addStatus(text, iconName) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const row = boxLayout({spacing: 10, x_expand: true});
        row.add_child(new St.Icon({icon_name: iconName, style_class: 'audio-matrix-group-icon'}));
        const label = new St.Label({text, x_expand: true, style_class: 'audio-matrix-status'});
        label.clutter_text.line_wrap = true;
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
        const item = this.createLevelRow(options);
        target.addMenuItem(item);
        return item;
    }

    createLevelRow(options) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        item.add_style_class_name(options.nested ? 'audio-matrix-tab' : 'audio-matrix-card');
        const row = boxLayout({vertical: true, spacing: 9, x_expand: true});
        const heading = boxLayout({spacing: 9, x_expand: true});
        const iconActor = new St.Icon({style_class: 'audio-matrix-node-icon', y_align: Clutter.ActorAlign.CENTER});
        heading.add_child(iconActor);
        const copy = boxLayout({vertical: true, spacing: 2, x_expand: true, y_align: Clutter.ActorAlign.CENTER});
        const name = new St.Label({text: '', x_expand: true, style_class: 'audio-matrix-node-title'});
        name.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        copy.add_child(name);
        const subtitle = new St.Label({text: '', style_class: 'audio-matrix-node-subtitle'});
        subtitle.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        copy.add_child(subtitle);
        heading.add_child(copy);
        const percentage = new St.Label({text: '', style_class: 'audio-matrix-percent',
            x_align: Clutter.ActorAlign.END, y_align: Clutter.ActorAlign.CENTER});
        heading.add_child(percentage);
        const slider = new Slider.Slider(0);
        slider.add_style_class_name('audio-matrix-slider');
        slider.x_expand = true;
        item._audioMatrixSlider = slider;
        const state = {slider, percentage, name, subtitle, iconActor, options: {...options}, dragging: false};
        state.muteButton = iconButton('audio-volume-high-symbolic', '', () => {
            const current = state.options;
            current.onMute?.(!Boolean(current.muted));
        });
        state.muteButton.accessible_role = Atk.Role.TOGGLE_BUTTON;
        heading.add_child(state.muteButton);
        state.expandButton = iconButton('pan-down-symbolic', '', () => state.options.onExpand?.());
        state.expandButton.add_accessible_state(Atk.StateType.EXPANDABLE);
        heading.add_child(state.expandButton);
        const hint = new St.Label({text: '', style_class: 'audio-matrix-capability-hint', visible: false});
        hint.clutter_text.line_wrap = true;
        state.infoButton = iconButton('dialog-information-symbolic', '', () => { hint.visible = !hint.visible; });
        heading.add_child(state.infoButton);
        row.add_child(heading);
        row.add_child(slider);
        row.add_child(hint);
        state.hint = hint;
        state.children = new PopupMenu.PopupMenuSection();
        state.children.actor.add_style_class_name('audio-matrix-tabs');
        row.add_child(state.children.actor);
        item.add_child(row);
        LEVEL_ROW_STATE.set(item, state);
        state.valueSignalId = this._connectSlider(slider, percentage, state);
        this.updateLevelRow(item, options);
        return item;
    }

    updateLevelRow(item, options) {
        const state = LEVEL_ROW_STATE.get(item);
        if (!state) return;
        state.options = {...state.options, ...options};
        const current = state.options;
        item._audioMatrixCommandTarget = current.commandTarget || null;
        item._audioMatrixMuteTarget = current.muteCommandTarget || current.commandTarget || null;
        const value = state.localValue ?? (current.muted ? 0 : Math.max(0, Math.min(1, Number(current.value) || 0)));
        if (!state.dragging) {
            state.slider.block_signal_handler(state.valueSignalId);
            state.slider.value = value;
            state.slider.unblock_signal_handler(state.valueSignalId);
            state.percentage.text = state.localValue != null ? `${Math.round(value * 100)}%`
                : current.displayValue ?? `${Math.round(value * 100)}%`;
        }
        state.name.text = String(current.name || '');
        state.subtitle.text = current.subtitle || '';
        state.subtitle.visible = Boolean(current.subtitle);
        state.slider.accessible_name = `${TEXT.ru.volume}: ${current.name || ''}`;
        const available = current.available !== false;
        state.slider.reactive = available && typeof current.onValue === 'function';
        state.slider.can_focus = state.slider.reactive;
        state.slider.opacity = state.slider.reactive ? 255 : 100;
        const icon = current.icon || 'audio-x-generic-symbolic';
        if (typeof icon === 'string') state.iconActor.icon_name = icon;
        else state.iconActor.gicon = icon.gicon;
        const muted = state.localValue != null ? false : Boolean(current.muted);
        state.muteButton.visible = typeof current.onMute === 'function';
        state.muteButton.reactive = available && state.muteButton.visible;
        state.muteButton.can_focus = state.muteButton.reactive;
        state.muteButton.get_child().icon_name = muted ? 'audio-volume-muted-symbolic' : 'audio-volume-high-symbolic';
        state.muteButton.accessible_name = `${muted ? TEXT.ru.unmute : TEXT.ru.mute}: ${current.name || ''}`;
        if (muted) state.muteButton.add_accessible_state(Atk.StateType.CHECKED);
        else state.muteButton.remove_accessible_state(Atk.StateType.CHECKED);
        const hasChildren = Boolean(current.hasChildren);
        state.expandButton.visible = hasChildren;
        state.expandButton.get_child().icon_name = current.expanded ? 'pan-up-symbolic' : 'pan-down-symbolic';
        state.expandButton.accessible_name = `${current.expanded ? 'Свернуть' : 'Раскрыть'}: ${current.name || ''}`;
        if (current.expanded) state.expandButton.add_accessible_state(Atk.StateType.EXPANDED);
        else state.expandButton.remove_accessible_state(Atk.StateType.EXPANDED);
        state.children.actor.visible = hasChildren && Boolean(current.expanded);
        state.hint.text = current.capabilityHint || '';
        state.infoButton.accessible_name = current.capabilityHint || '';
        state.infoButton.visible = Boolean(current.capabilityHint);
        if (!current.capabilityHint) state.hint.visible = false;
        item.opacity = available ? 255 : 145;
    }

    childSection(item) {
        return LEVEL_ROW_STATE.get(item)?.children || null;
    }

    setAvailable(item, available) {
        const state = LEVEL_ROW_STATE.get(item);
        if (!state) return;
        if (!available) {
            state.slider.reactive = false;
            state.slider.can_focus = false;
            state.muteButton.reactive = false;
            state.muteButton.can_focus = false;
            state.expandButton.reactive = false;
        }
    }

    _connectSlider(slider, percentage, state) {
        const key = String(state.options.id);
        slider.connect('drag-begin', () => {
            state.dragging = true;
            this._onDragBegin?.();
        });
        const valueSignalId = slider.connect('notify::value', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            const options = state.options;
            percentage.text = options.formatValue ? options.formatValue(value) : `${Math.round(value * 100)}%`;
            state.localValue = value;
            state.options.value = value;
            state.options.muted = false;
            state.muteButton.get_child().icon_name = 'audio-volume-high-symbolic';
            if (typeof options.onValue === 'function')
                this._queueSliderCommand(key, value, next => {
                    state.localValue = null;
                    state.options.onValue?.(next);
                });
        });
        slider.connect('drag-end', () => {
            const value = Math.max(0, Math.min(1, Number(slider.value) || 0));
            if (typeof state.options.onValue === 'function')
                this._queueSliderCommand(key, value, next => {
                    state.localValue = null;
                    state.options.onValue?.(next);
                }, true);
            state.dragging = false;
            this._onDragEnd?.();
        });
        return valueSignalId;
    }

}
