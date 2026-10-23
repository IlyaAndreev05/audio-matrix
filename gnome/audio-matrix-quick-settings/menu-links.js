import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import Clutter from 'gi://Clutter';
import St from 'gi://St';
import {boxLayout} from './menu-layout.js';
import {outputIcon} from './menu-icons.js';

const LINK_ROWS = new WeakMap();

export default class AudioMatrixMenuLinks {
    constructor(send) {
        this._send = send;
    }

    render(control, target) {
        const card = new PopupMenu.PopupMenuSection();
        card.actor.add_style_class_name('audio-matrix-route-card');
        const heading = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        heading.add_style_class_name('audio-matrix-route-heading');
        heading.add_child(new St.Icon({icon_name: 'network-transmit-receive-symbolic',
            style_class: 'audio-matrix-node-icon'}));
        const copy = boxLayout({vertical: true, spacing: 2, x_expand: true});
        copy.add_child(new St.Label({text: 'Распределитель звука', style_class: 'audio-matrix-node-title'}));
        copy.add_child(new St.Label({text: 'Один вход → несколько выходов', style_class: 'audio-matrix-node-subtitle'}));
        heading.add_child(copy);
        card.addMenuItem(heading);
        const selected = selection(control.value);
        const rows = (control.options || []).map(option => this._createOption(control, option, selected, card));
        const reference = {card, heading};
        LINK_ROWS.set(reference, {rows, controlId: String(control.id)});
        target.addMenuItem(card);
        return reference;
    }

    _createOption(control, option, selected, card) {
        const item = new PopupMenu.PopupBaseMenuItem({activate: false, can_focus: true});
        item.add_style_class_name('audio-matrix-route-row');
        item.accessible_role = Atk.Role.CHECK_BOX;
        item.accessible_name = option.label || '';
        item._audioMatrixRowId = `route:${control.id}:${option.id}`;
        item.add_child(new St.Icon({icon_name: option.icon || outputIcon({name: option.label}),
            style_class: 'audio-matrix-node-icon'}));
        const copy = boxLayout({vertical: true, spacing: 2, x_expand: true});
        const label = new St.Label({text: option.label || '', x_expand: true,
            y_align: Clutter.ActorAlign.CENTER});
        label.clutter_text.ellipsize = 3;
        copy.add_child(label);
        const status = new St.Label({text: 'Не подключено', style_class: 'audio-matrix-node-subtitle', visible: false});
        copy.add_child(status);
        item.add_child(copy);
        const check = new St.Bin({style_class: 'audio-matrix-route-check', y_align: Clutter.ActorAlign.CENTER});
        const mark = new St.Icon({icon_name: 'object-select-symbolic'});
        check.set_child(mark);
        item.add_child(check);
        const row = {id: String(option.id), item, mark, status, option, control, selected: selected.has(String(option.id))};
        item.connect('activate', () => {
            if (row.option.available === false || row.option.enabled === false) return;
            this._choose(row.control, row.id, !row.selected);
            item.stop_emission_by_name('activate');
        });
        card.addMenuItem(item);
        this._updateOption(row, option, row.selected);
        return row;
    }

    _choose(control, optionId, enabled) {
        if (control.nodeType === 'switch') {
            this._send('setRouteEnabled', {routeId: String(control.routeId),
                switchNodeId: String(control.switchNodeId || control.nodeId), linkId: String(optionId), enabled});
            return;
        }
        this._send('set_quick_control_choice', `${control.id}\t${optionId}`);
    }

    _updateOption(row, option, selected) {
        row.option = option;
        const available = option.available !== false && option.enabled !== false;
        row.selected = available && selected;
        row.mark.visible = row.selected;
        row.item.reactive = available;
        row.item.can_focus = available;
        row.item.opacity = available ? 255 : 125;
        row.status.visible = !available;
        if (row.selected) {
            row.item.add_style_pseudo_class('checked');
            row.item.add_accessible_state(Atk.StateType.CHECKED);
        } else {
            row.item.remove_style_pseudo_class('checked');
            row.item.remove_accessible_state(Atk.StateType.CHECKED);
        }
    }

    update(reference, control) {
        const state = LINK_ROWS.get(reference);
        if (!state) return;
        const selected = selection(control.value);
        const options = new Map((control.options || []).map(option => [String(option.id), option]));
        for (const row of state.rows) {
            row.control = control;
            this._updateOption(row, options.get(row.id) || row.option, selected.has(row.id));
        }
    }

    setAvailable(reference, available) {
        if (available) return;
        for (const row of LINK_ROWS.get(reference)?.rows || []) {
            row.item.reactive = false;
            row.item.can_focus = false;
        }
    }

    rowsFor(reference) {
        const state = LINK_ROWS.get(reference);
        return state ? state.rows.map(row => ({id: `route:${state.controlId}:${row.id}`,
            actor: row.item.actor || row.item})) : [];
    }
}

function selection(value) {
    return new Set(Array.isArray(value) ? value.map(String) : value == null ? [] : [String(value)]);
}
