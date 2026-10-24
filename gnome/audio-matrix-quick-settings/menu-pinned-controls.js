import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import AudioMatrixMenuQuickControls from './menu-quick-controls.js';
import AudioMatrixMenuMatrix from './menu-matrix.js';
import AudioMatrixMenuMixer from './menu-mixer.js';
import AudioMatrixMenuDisclosure from './menu-disclosure.js';
import {controlPosition, formatControlValue} from './menu-control-scale.js';

/** Explicit user pins reuse the same real graph controls, without populating the default panel. */
export default class AudioMatrixPinnedControls {
    constructor({rows, links, send, registerLevelRow, disclosures = null}) {
        this._rows = rows;
        this._links = links;
        this._send = send;
        this._levels = new Map();
        this._toggles = new Map();
        this._linkRows = new Map();
        this._matrixRows = new Map();
        this._matrix = new AudioMatrixMenuMatrix(send);
        // The renderer may inject the shared map so saved view changes reach this header too;
        // a private map keeps the nested disclosure working in isolated fixtures.
        this._disclosures = disclosures || new AudioMatrixMenuDisclosure();
        this._ownsDisclosures = !disclosures;
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
        const controls = pin.controls || [];
        const visibleInputs = pin.mixerInputs ? this._mixer.visible(pin.mixerInputs) : [];
        const hasMixer = Boolean(pin.mixerInputs) && visibleInputs.length > 0;
        // No visible row means no card at all: not a heading, not an empty placeholder.
        if (!controls.length && !hasMixer) return;
        const id = String(pin.id);
        const header = new PopupMenu.PopupImageMenuItem(pin.name || '', 'pan-end-symbolic');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        const content = new PopupMenu.PopupMenuSection();
        content.actor.add_style_class_name('audio-matrix-tabs');
        target.addMenuItem(header);
        target.addMenuItem(content);
        if (controls.length) this._controls.render(controls, t, content);
        if (hasMixer) this._mixer.render(pin.routeId, pin.nodeId, pin.id, visibleInputs, t, content);
        const open = Boolean(this._disclosures.pinOpen(pin));
        content.actor.visible = open && pin.showChildren !== false;
        header.setIcon(open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
        this._disclosures.trackPin(id, header, content, pin.showChildren !== false);
        header.connect('activate', () => {
            const next = this._disclosures.togglePin(id);
            this._send('set_quick_pin_view', `${pin.id}\t${next ? 'EXPANDED' : 'COMPACT'}`);
            header.stop_emission_by_name('activate');
        });
    }

    update(state, pendingValue, available) {
        if (this._ownsDisclosures) {
            this._disclosures.retain(state);
            this._disclosures.sync(state);
        }
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
        if (this._ownsDisclosures) this._disclosures.destroy();
    }
}
