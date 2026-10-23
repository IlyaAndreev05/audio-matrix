import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import AudioMatrixMenuQuickControls from './menu-quick-controls.js';
import AudioMatrixMenuMatrix from './menu-matrix.js';
import AudioMatrixMenuMixer from './menu-mixer.js';
import {controlPosition, formatControlValue} from './menu-control-scale.js';

/** Explicit user pins reuse the same real graph controls, without populating the default panel. */
export default class AudioMatrixPinnedControls {
    constructor({rows, links, send, registerLevelRow}) {
        this._rows = rows;
        this._links = links;
        this._levels = new Map();
        this._toggles = new Map();
        this._linkRows = new Map();
        this._matrixRows = new Map();
        this._matrix = new AudioMatrixMenuMatrix(send);
        const register = (map, id, value) => {
            map.set(id, value);
            const actor = value.item || value.card?.actor || value;
            actor.connect?.('destroy', () => { if (map.get(id) === value) map.delete(id); });
        };
        this._controls = new AudioMatrixMenuQuickControls({rows, links, send, matrix: this._matrix,
            getLanguage: () => 'ru',
            registerLevelRow: (id, row) => { register(this._levels, id, row); registerLevelRow(id, row); },
            registerToggleRow: (id, row) => register(this._toggles, id, row),
            registerLinkRow: (id, row) => register(this._linkRows, id, row),
            registerMatrixRow: (id, row) => register(this._matrixRows, id, row)});
        this._mixer = new AudioMatrixMenuMixer({rows, send, registerLevelRow});
    }

    render(pin, t, target) {
        const heading = new PopupMenu.PopupMenuItem(pin.name || '', {reactive: false, can_focus: false});
        heading.add_style_class_name('audio-matrix-node-title');
        target.addMenuItem(heading);
        this._controls.render(pin.controls || [], t, target);
        if (pin.mixerInputs) this._mixer.render(pin.routeId, pin.nodeId, pin.id, pin.mixerInputs, t, target);
    }

    update(state, pendingValue, available) {
        for (const control of state.quickControls || []) {
            const target = {quickControlId: String(control.id)};
            const requested = pendingValue(target, 'value');
            const value = requested ?? control.value;
            const row = this._levels.get(`graph:${control.id}`);
            if (row) {
                const ranged = control.kind === 'range';
                this._rows.updateLevelRow(row, {value: ranged ? controlPosition({...control, value}) : value,
                    muted: requested !== undefined ? false : Boolean(control.muted), available,
                    displayValue: ranged ? formatControlValue(control, value) : `${Math.round(value * 100)}%`});
            }
            const toggle = this._toggles.get(String(control.id));
            if (toggle) {
                toggle.item.block_signal_handler(toggle.signalId);
                toggle.item.setToggleState(Boolean(value));
                toggle.item.unblock_signal_handler(toggle.signalId);
                toggle.item.setSensitive(available);
            }
            const links = this._linkRows.get(String(control.id));
            if (links) { this._links.update(links, control); this._links.setAvailable(links, available); }
            const matrix = this._matrixRows.get(String(control.id));
            if (matrix) this._matrix.update(matrix, control);
        }
    }

    destroy() {
        this._controls.destroy();
        for (const map of [this._levels, this._toggles, this._linkRows, this._matrixRows]) map.clear();
    }
}
