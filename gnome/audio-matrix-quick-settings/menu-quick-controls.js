import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import {controlPosition, controlValueAt, formatControlValue} from './menu-control-scale.js';
import {TEXT} from './menu-text.js';
import {controlIcon} from './menu-icons.js';

/** Renders persisted graph controls while the parent renderer owns their keyed row indexes. */
export default class AudioMatrixMenuQuickControls {
    constructor({rows, send, getLanguage, registerLevelRow, registerToggleRow, registerLinkRow, registerMatrixRow,
        links, matrix}) {
        this._rows = rows;
        this._send = send;
        this._getLanguage = getLanguage;
        this._registerLevelRow = registerLevelRow;
        this._registerToggleRow = registerToggleRow;
        this._registerLinkRow = registerLinkRow;
        this._registerMatrixRow = registerMatrixRow;
        this._links = links;
        this._matrix = matrix;
    }

    render(controls, t, target) {
        for (const control of controls) {
            if (control.kind === 'slider' || control.kind === 'range')
                this._renderSlider(control, target);
            else if (control.kind === 'toggle')
                this._renderToggle(control, target);
            else if (control.kind === 'links' && control.nodeType === 'matrix')
                this._registerMatrixRow?.(String(control.id), this._matrix.render(control, t, target));
            else if (control.kind === 'links' || control.kind === 'choice')
                this._registerLinkRow?.(String(control.id), this._links.render(control, target));
        }
    }

    _renderSlider(control, target) {
        const range = control.kind === 'range';
        const options = {
            id: `graph:${control.id}`, name: control.label || control.parameterId,
            icon: controlIcon(control), value: range ? controlPosition(control) : control.value,
            muted: Boolean(control.muted),
            onValue: value => {
                const next = range ? controlValueAt(control, value) : value;
                this._send('set_quick_control', `${control.id}\t${next.toFixed(3)}`);
            },
        };
        if (range) {
            options.displayValue = formatControlValue(control, control.value);
            options.formatValue = value => formatControlValue(control, controlValueAt(control, value));
        } else {
            options.onMute = () => this._send('toggle_quick_control', control.id);
        }
        const row = this._rows.addLevelRow(options, target);
        this._registerLevelRow?.(String(options.id), row);
    }

    _renderToggle(control, target) {
        const label = control.nodeType === 'gate' ? TEXT[this._getLanguage()].onOff
            : control.label || control.parameterId;
        const item = new PopupMenu.PopupSwitchMenuItem(label, Boolean(control.value));
        const signalId = item.connect('toggled', () => this._send('toggle_quick_control', control.id));
        item.connect('activate', () => item.stop_emission_by_name('activate'));
        target.addMenuItem(item);
        this._registerToggleRow?.(String(control.id), {item, signalId});
    }

    destroy() {
        this._rows = null;
        this._send = null;
        this._links = null;
        this._matrix = null;
    }
}
