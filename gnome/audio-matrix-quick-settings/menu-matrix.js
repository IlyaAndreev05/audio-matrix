import Clutter from 'gi://Clutter';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import St from 'gi://St';
import {boxLayout} from './menu-layout.js';

const MATRIX_STATE = new WeakMap();

export default class AudioMatrixMenuMatrix {
    constructor(send) {
        this._send = send;
    }

    render(control, t, target) {
        const state = this._create(control, t);
        MATRIX_STATE.set(state.item, state);
        target.addMenuItem(state.item);
        return state.item;
    }

    _create(control, t) {
        const item = new PopupMenu.PopupBaseMenuItem({reactive: false, activate: false, can_focus: false});
        const root = boxLayout({vertical: true, spacing: 7, x_expand: true});
        const count = new St.Label({x_expand: true});
        const tabs = boxLayout({spacing: 6, x_expand: true});
        const matrixTab = this._tab(t.matrix, tabs);
        const arrowsTab = this._tab(t.arrows, tabs);
        const matrixPage = boxLayout({vertical: true, spacing: 5, x_expand: true});
        const arrowPage = boxLayout({vertical: true, spacing: 5, x_expand: true});
        const state = {item, control, count, matrixTab, arrowsTab, matrixPage, arrowPage,
            selected: new Set(Array.isArray(control.value) ? control.value : []), controls: new Map(), lines: new Map(), t};
        this._buildMatrix(state);
        this._buildArrows(state);
        matrixTab.connect('clicked', () => this._select(state, 'MATRIX'));
        arrowsTab.connect('clicked', () => this._select(state, 'ARROWS'));
        root.add_child(count);
        root.add_child(tabs);
        root.add_child(matrixPage);
        root.add_child(arrowPage);
        item.add_child(root);
        this._sync(state, control.view || 'MATRIX');
        return state;
    }

    _tab(label, target) {
        const button = this._button(label, label);
        target.add_child(button);
        return button;
    }

    _buildMatrix(state) {
        const pairs = this._pairs(state.control);
        const inputs = [...new Set(pairs.map(pair => pair.input))];
        const outputs = [...new Set(pairs.map(pair => pair.output))];
        const layout = new Clutter.GridLayout();
        layout.set_column_spacing(7);
        layout.set_row_spacing(4);
        const grid = new St.Widget({layout_manager: layout, x_expand: true});
        this._attach(layout, grid, new St.Label({text: state.t.input}), 0, 0);
        outputs.forEach((port, index) => this._attach(layout, grid, new St.Label({text: port}), index + 1, 0));
        inputs.forEach((input, row) => {
            this._attach(layout, grid, new St.Label({text: input}), 0, row + 1);
            outputs.forEach((output, column) => {
                const pair = pairs.find(candidate => candidate.input === input && candidate.output === output);
                if (pair) this._addChoice(state, pair, grid, layout, column + 1, row + 1, '✓', '—');
            });
        });
        state.matrixPage.add_child(grid);
    }

    _buildArrows(state) {
        const pairs = this._pairs(state.control);
        const activeTitle = new St.Label({text: state.t.activeLinks});
        state.arrowPage.add_child(activeTitle);
        for (const pair of pairs) {
            const line = new St.Label({text: `${pair.input} ━━━━━▶ ${pair.output}`, x_expand: true});
            state.lines.set(pair.id, line);
            state.arrowPage.add_child(line);
        }
        const choicesTitle = new St.Label({text: state.t.changeLink});
        state.arrowPage.add_child(choicesTitle);
        for (const pair of pairs)
            this._addChoice(state, pair, state.arrowPage, null, 0, 0, '✓', '—');
    }

    _addChoice(state, pair, parent, layout, column, row, checked, unchecked) {
        const button = this._button('', `${pair.input} → ${pair.output}`);
        const label = button.get_child();
        const change = () => this._send('set_quick_control_choice', `${state.control.id}\t${pair.id}`);
        button.connect('clicked', change);
        if (layout) this._attach(layout, parent, button, column, row);
        else parent.add_child(button);
        const controls = state.controls.get(pair.id) || [];
        controls.push({label, button, checked, unchecked});
        state.controls.set(pair.id, controls);
    }

    _button(text, accessibleName) {
        const button = new St.Button({style_class: 'button', can_focus: true, reactive: true,
            x_expand: true, accessible_name: accessibleName});
        button.set_child(new St.Label({text}));
        return button;
    }

    _attach(layout, grid, child, column, row) {
        grid.add_child(child);
        layout.attach(child, column, row, 1, 1);
    }

    _pairs(control) {
        return (control.options || []).map(option => {
            const [input, output] = String(option.id).split('->', 2);
            return {id: option.id, input, output};
        }).filter(pair => pair.input && pair.output);
    }

    _select(state, view) {
        this._sync(state, view);
        this._send('set_quick_matrix_view', `${state.control.id}\t${view}`);
    }

    _sync(state, view) {
        state.matrixPage.visible = view !== 'ARROWS';
        state.arrowPage.visible = view === 'ARROWS';
        this._setTabStyle(state.matrixTab, view !== 'ARROWS');
        this._setTabStyle(state.arrowsTab, view === 'ARROWS');
        state.count.text = `${state.control.label || state.control.parameterId} (${state.selected.size}/${state.controls.size})`;
        for (const [id, entries] of state.controls) {
            const enabled = state.selected.has(id);
            for (const entry of entries) entry.label.text = enabled ? entry.checked : entry.unchecked;
            state.lines.get(id)?.set_visible(enabled);
        }
    }

    _setTabStyle(tab, active) {
        if (active) tab.add_style_class_name('suggested-action');
        else tab.remove_style_class_name('suggested-action');
    }

    update(item, control) {
        const state = MATRIX_STATE.get(item);
        if (!state) return;
        state.control = control;
        state.selected = new Set(Array.isArray(control.value) ? control.value : []);
        this._sync(state, control.view || 'MATRIX');
    }
}
