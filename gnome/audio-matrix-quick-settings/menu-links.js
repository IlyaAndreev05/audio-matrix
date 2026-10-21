import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import St from 'gi://St';
import {boxLayout} from './menu-layout.js';

const LINK_ROWS = new WeakMap();

export default class AudioMatrixMenuLinks {
    constructor(send) {
        this._send = send;
    }

    render(control, target, t = {}) {
        const multiple = Boolean(control.multiple ?? control.nodeType === 'matrix');
        if (control.nodeType === 'switch' && multiple)
            return this._renderMultiSwitch(control, target, t);

        const selected = selection(control.value);
        const options = control.options || [];
        const submenu = new PopupMenu.PopupSubMenuMenuItem(summary(control, selected, options), false);
        submenu._audioMatrixKey = `control:${control.id}`;
        submenu._audioMatrixKind = 'choice-control';
        const state = {control, rows: new Map(), multiple};
        LINK_ROWS.set(submenu, state);
        submenu._audioMatrixPatch = incoming => {
            const next = LINK_ROWS.get(incoming);
            if (next)
                this.update(submenu, next.control);
        };
        this._syncSubmenu(submenu, state, control);
        return target.addMenuItem(submenu) || submenu;
    }

    _renderMultiSwitch(control, target, t) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const root = boxLayout({vertical: true, spacing: 4, x_expand: true});
        const title = new St.Label({text: 'MultiSwitch', x_expand: true, style_class: 'popup-menu-item'});
        const description = new St.Label({text: t.multiSwitchDescription || '1 input → multiple outputs', x_expand: true,
            style_class: 'popup-status-menu-item'});
        const optionsBox = boxLayout({vertical: true, spacing: 2, x_expand: true});
        root.add_child(title);
        root.add_child(description);
        root.add_child(optionsBox);
        item.add_child(root);
        item._audioMatrixKey = `route-control:${control.id}`;
        item._audioMatrixKind = 'route-control';
        const state = {control, item, title, description, optionsBox, rows: new Map()};
        LINK_ROWS.set(item, state);
        item._audioMatrixPatch = incoming => {
            const next = LINK_ROWS.get(incoming);
            if (next)
                this.update(item, next.control);
        };
        this._syncMultiSwitch(item, state, control);
        return target.addMenuItem(item) || item;
    }

    _syncSubmenu(submenu, state, control) {
        const multiple = Boolean(control.multiple ?? control.nodeType === 'matrix');
        if (multiple !== state.multiple) {
            for (const row of state.rows.values())
                submenu.menu.removeMenuItem(row.item);
            state.rows.clear();
        }
        state.control = control;
        state.multiple = multiple;
        const options = control.options || [];
        const desired = new Set(options.map(option => String(option.id)));
        for (const [id, row] of state.rows) {
            if (desired.has(id))
                continue;
            submenu.menu.removeMenuItem(row.item);
            state.rows.delete(id);
        }
        for (const option of options) {
            const id = String(option.id);
            let row = state.rows.get(id);
            if (!row) {
                row = this._createOption(state, option);
                state.rows.set(id, row);
                submenu.menu.addMenuItem(row.item);
            }
            this._updateOption(row, option, control);
        }
        const selected = selection(control.value);
        submenu.label.text = summary(control, selected, options);
    }

    _createOption(state, option) {
        const multiple = state.multiple;
        if (multiple) {
            const item = new PopupMenu.PopupSwitchMenuItem(option.label || option.id, false);
            const signalId = item.connect('toggled', () =>
                this._choose(state.control, option.id, Boolean(item.state)));
            item.connect('activate', () => item.stop_emission_by_name('activate'));
            return {id: String(option.id), item, signalId};
        }
        const item = new PopupMenu.PopupMenuItem(option.label || option.id);
        item.accessible_role = Atk.Role.RADIO_BUTTON;
        item.connect('activate', () => this._choose(state.control, option.id));
        return {id: String(option.id), item, signalId: 0};
    }

    _renderOptionRow(state, option) {
        const item = new PopupMenu.PopupSwitchMenuItem(option.label || option.id, false);
        const signalId = item.connect('toggled', () => {
            const current = state.control;
            this._send('setRouteEnabled', {
                routeId: current.routeId, switchNodeId: current.nodeId,
                linkId: String(option.id), enabled: Boolean(item.state),
            });
        });
        item.connect('activate', () => item.stop_emission_by_name('activate'));
        state.optionsBox.add_child(item.actor);
        return {id: String(option.id), item, signalId};
    }

    _syncMultiSwitch(item, state, control) {
        state.control = control;
        state.title.text = String(control.label || 'MultiSwitch');
        state.description.text = String(control.description || '1 input → multiple outputs');
        const options = control.options || [];
        const desired = new Set(options.map(option => String(option.id)));
        for (const [id, row] of state.rows) {
            if (desired.has(id))
                continue;
            state.optionsBox.remove_child(row.item.actor);
            state.rows.delete(id);
        }
        const selected = selection(control.value);
        for (const option of options) {
            const id = String(option.id);
            let row = state.rows.get(id);
            if (!row) {
                row = this._renderOptionRow(state, option);
                state.rows.set(id, row);
            }
            row.item.label.text = String(option.label || option.id);
            row.item.block_signal_handler(row.signalId);
            row.item.state = selected.has(id);
            row.item.unblock_signal_handler(row.signalId);
        }
    }

    _updateOption(row, option, control) {
        const selected = selection(control.value);
        if (row.signalId) {
            row.item.label.text = String(option.label || option.id);
            row.item.block_signal_handler(row.signalId);
            row.item.state = selected.has(String(option.id));
            row.item.unblock_signal_handler(row.signalId);
        } else {
            row.item.label = String(option.label || option.id);
            row.item.setOrnament(selected.has(String(option.id))
                ? PopupMenu.Ornament.DOT : PopupMenu.Ornament.NO_DOT);
        }
    }

    _choose(control, optionId, enabled = null) {
        if (control.nodeType === 'switch' && enabled !== null) {
            this._send('setRouteEnabled', {
                routeId: control.routeId, switchNodeId: control.nodeId,
                linkId: String(optionId), enabled,
            });
            return;
        }
        this._send('set_quick_control_choice', `${control.id}\t${optionId}`);
    }

    update(item, control) {
        const state = LINK_ROWS.get(item);
        if (!state) return;
        if (state.optionsBox)
            this._syncMultiSwitch(item, state, control);
        else
            this._syncSubmenu(item, state, control);
    }
}

function selection(value) {
    return new Set(Array.isArray(value) ? value.map(String) : value == null ? [] : [String(value)]);
}

function summary(control, selected, options) {
    const title = control.label || control.parameterId;
    if (control.kind !== 'choice') return `${title} (${selected.size}/${options.length})`;
    const active = options.find(option => selected.has(String(option.id)));
    return `${title}: ${active?.label || [...selected][0] || '—'}`;
}
