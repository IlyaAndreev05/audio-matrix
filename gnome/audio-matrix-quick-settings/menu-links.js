import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';

const LINK_ROWS = new WeakMap();

export default class AudioMatrixMenuLinks {
    constructor(send) {
        this._send = send;
    }

    render(control, target) {
        const selected = selection(control.value);
        const options = control.options || [];
        const multiple = Boolean(control.multiple ?? control.nodeType === 'matrix');
        const submenu = new PopupMenu.PopupSubMenuMenuItem(summary(control, selected, options), false);
        const rows = options.map(option => this._createOption(control, option, selected, multiple, submenu));
        LINK_ROWS.set(submenu, {rows, multiple, optionCount: options.length});
        target.addMenuItem(submenu);
        return submenu;
    }

    _createOption(control, option, selected, multiple, submenu) {
        const label = option.label || option.id;
        if (multiple) {
            const item = new PopupMenu.PopupSwitchMenuItem(label, selected.has(option.id));
            const signalId = item.connect('toggled', () => this._choose(control, option.id, Boolean(item.state)));
            item.connect('activate', () => item.stop_emission_by_name('activate'));
            submenu.menu.addMenuItem(item);
            return {id: option.id, item, signalId};
        }
        const item = new PopupMenu.PopupMenuItem(label);
        item.accessible_role = Atk.Role.RADIO_BUTTON;
        item.setOrnament(selected.has(option.id) ? PopupMenu.Ornament.DOT : PopupMenu.Ornament.NO_DOT);
        item.connect('activate', () => this._choose(control, option.id));
        submenu.menu.addMenuItem(item);
        return {id: option.id, item, signalId: 0};
    }

    _choose(control, optionId, enabled = null) {
        if (control.nodeType === 'switch' && enabled !== null) {
            this._send('setRouteEnabled', `${control.routeId}\t${control.nodeId}\t${optionId}\t${enabled}`);
            return;
        }
        this._send('set_quick_control_choice', `${control.id}\t${optionId}`);
    }

    update(submenu, control) {
        const state = LINK_ROWS.get(submenu);
        if (!state) return;
        const selected = selection(control.value);
        for (const row of state.rows) {
            if (state.multiple) {
                row.item.block_signal_handler(row.signalId);
                row.item.state = selected.has(row.id);
                row.item.unblock_signal_handler(row.signalId);
            } else {
                row.item.setOrnament(selected.has(row.id)
                    ? PopupMenu.Ornament.DOT : PopupMenu.Ornament.NO_DOT);
            }
        }
        submenu.label.text = summary(control, selected, control.options || []);
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
