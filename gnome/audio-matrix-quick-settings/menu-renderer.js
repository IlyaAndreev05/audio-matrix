import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import St from 'gi://St';
import AudioMatrixMenuRows from './menu-rows.js';
import AudioMatrixMenuApplications from './menu-applications.js';
import AudioMatrixMenuLinks from './menu-links.js';
import AudioMatrixMenuMatrix from './menu-matrix.js';
import AudioMatrixMenuMixer from './menu-mixer.js';
import AudioMatrixMenuOutput from './menu-output.js';
import AudioMatrixMenuDisclosure from './menu-disclosure.js';
import AudioMatrixMenuGroups from './menu-groups.js';
import {hasAvailableMenuState, menuLevelRows, menuStructureSignature} from './menu-state.js';
import {controlPosition, controlValueAt, formatControlValue} from './menu-control-scale.js';
import {TEXT} from './menu-text.js';

function createScrollableGroups(section) {
    const column = new PopupMenu.PopupMenuSection();
    const scroll = new St.ScrollView({
        style_class: 'popup-sub-menu',
        hscrollbar_policy: St.PolicyType.NEVER,
        vscrollbar_policy: St.PolicyType.AUTOMATIC,
        x_expand: true,
        child: column.box,
    });
    const monitor = global.display.get_monitor_geometry(global.display.get_current_monitor());
    const availableHeight = monitor?.height || 800;
    scroll.set_style(`max-height: ${Math.max(120, Math.min(560, Math.floor(availableHeight * 0.55)))}px;`);
    scroll._delegate = column;
    column.actor = scroll;
    section.addMenuItem(column);
    return column;
}

function updateMenuControls(rows, toggles, linkRows, matrixRows, linkRenderer, matrixRenderer, menuRows, state, t) {
    if (!state) return;
    for (const [id, source] of menuLevelRows(state))
        updateLevelRow(rows, menuRows, id, source, t);
    for (const control of state.quickControls || []) {
        if (control.kind === 'slider' || control.kind === 'range') {
            const range = control.kind === 'range';
            menuRows.updateLevelRow(rows.get(`graph:${control.id}`), {
                value: range ? controlPosition(control) : control.value,
                muted: Boolean(control.muted),
                displayValue: range ? formatControlValue(control, control.value) : undefined,
            });
        }
        if (control.kind === 'toggle') {
            const entry = toggles.get(String(control.id));
            if (!entry) continue;
            entry.item.block_signal_handler(entry.signalId);
            entry.item.state = Boolean(control.value);
            entry.item.unblock_signal_handler(entry.signalId);
        }
        if (control.kind === 'links' || control.kind === 'choice') {
            const id = String(control.id);
            if (control.nodeType === 'matrix') matrixRenderer.update(matrixRows.get(id), control);
            else linkRenderer.update(linkRows.get(id), control);
        }
    }
}

function updateLevelRow(rows, menuRows, id, source, t) {
    menuRows.updateLevelRow(rows.get(id), {
        value: source.volume ?? source.value,
        muted: Boolean(source.muted),
        mutedLabel: source.muted ? t.unmute : t.mute,
    });
}

function addRoutePicker(section, state, t, send) {
    const routes = state.routes || [];
    if (routes.length < 2) return;
    const name = state.activeRoute || routes.find(route => route.id === state.activeRouteId)?.name || '—';
    const picker = new PopupMenu.PopupSubMenuMenuItem(`${t.route}: ${name}`, false);
    for (const route of routes) {
        const item = new PopupMenu.PopupMenuItem(route.name || route.id);
        item.connect('activate', () => send('select_route', route.id));
        picker.menu.addMenuItem(item);
    }
    section.addMenuItem(picker);
}

export default class AudioMatrixMenuRenderer {
    constructor({section, getSnapshot, send, queueSliderCommand, onOpen, onDragBegin, onDragEnd}) {
        this._section = section;
        this._getSnapshot = getSnapshot;
        this._onOpen = onOpen;
        this._sendCommand = send;
        this._disclosures = new AudioMatrixMenuDisclosure();
        this._levelRows = new Map();
        this._toggleRows = new Map();
        this._linkRows = new Map();
        this._structure = null;
        this._column = null;
        this._rows = new AudioMatrixMenuRows({
            section, getLanguage: () => this._getSnapshot().language, send,
            queueSliderCommand, onOpen, onDragBegin, onDragEnd,
        });
        this._applications = new AudioMatrixMenuApplications({
            addLevelRow: (options, target) => this._addLevelRow(options, target),
            rows: this._rows,
            send: (name, value) => this._send(name, value),
        });
        this._links = new AudioMatrixMenuLinks((name, value) => this._send(name, value));
        this._matrix = new AudioMatrixMenuMatrix((name, value) => this._send(name, value));
        this._matrixRows = new Map();
        this._mixer = new AudioMatrixMenuMixer({
            addLevelRow: (options, target) => this._addLevelRow(options, target),
            send: (name, value) => this._send(name, value),
        });
        this._output = new AudioMatrixMenuOutput({
            addLevelRow: (options, target) => this._addLevelRow(options, target),
            outputIcon: output => this._rows.outputIcon(output),
            send: (name, value) => this._send(name, value),
        });
        this._groups = new AudioMatrixMenuGroups({
            send: (name, value) => this._send(name, value),
            disclosures: this._disclosures,
            applications: this._applications,
            mixer: this._mixer,
            output: this._output,
            renderControls: (controls, t, target) => this._renderControls(controls, t, target),
        });
    }

    render(snapshot = this._getSnapshot()) {
        if (!this._section) return;
        const structure = menuStructureSignature(snapshot);
        if (structure === this._structure) {
            if (!hasAvailableMenuState(snapshot)) return;
            try {
                updateMenuControls(this._levelRows, this._toggleRows, this._linkRows, this._matrixRows,
                    this._links, this._matrix, this._rows, snapshot.state, TEXT[snapshot.language]);
                this._disclosures.sync(snapshot.state);
                return;
            } catch (error) {
                this._structure = null;
                throw error;
            }
        }
        this._structure = null;
        this._levelRows.clear();
        this._toggleRows.clear();
        this._linkRows.clear();
        this._matrixRows.clear();
        this._disclosures.resetRows();
        const {state, available, language, commandError} = snapshot;
        const t = TEXT[language];
        this._section.removeAll();
        this._column = null;
        this._rows.addHeader(state, available);
        if (commandError)
            this._rows.addStatus(`${t.commandError}: ${commandError}`, 'dialog-warning-symbolic');
        if (!available) {
            this._rows.addStatus(state ? t.unavailable : t.connecting, 'dialog-information-symbolic');
            this._rows.addAction(t.open, () => this._onOpen(false), 'preferences-system-symbolic');
            this._structure = structure;
            return;
        }
        addRoutePicker(this._section, state, t, (name, value) => this._send(name, value));
        this._column = createScrollableGroups(this._section);
        this._groups.render(this._column, state, state.quickPins || [], t);
        this._disclosures.retain(state);
        this._section.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this._rows.addAction(t.open, () => this._onOpen(true), 'preferences-system-symbolic');
        this._structure = structure;
    }

    _renderControls(controls, t, target) {
        for (const control of controls) {
            if (control.kind === 'slider' || control.kind === 'range') this._renderSliderControl(control, target);
            else if (control.kind === 'toggle') this._renderToggleControl(control, target);
            else if (control.kind === 'links' && control.nodeType === 'matrix')
                this._matrixRows.set(String(control.id), this._matrix.render(control, t, target));
            else if (control.kind === 'links' || control.kind === 'choice')
                this._linkRows.set(String(control.id), this._links.render(control, target));
        }
    }

    _renderSliderControl(control, target) {
        const range = control.kind === 'range';
        const options = {
            id: `graph:${control.id}`, name: control.label || control.parameterId,
            icon: this._rows.controlIcon(control), value: range ? controlPosition(control) : control.value,
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
        this._addLevelRow(options, target);
    }

    _addLevelRow(options, target) {
        const row = this._rows.addLevelRow(options, target);
        this._levelRows.set(String(options.id), row);
    }

    _renderToggleControl(control, target) {
        const label = control.nodeType === 'gate' ? TEXT[this._getSnapshot().language].onOff
            : control.label || control.parameterId;
        const item = new PopupMenu.PopupSwitchMenuItem(label, Boolean(control.value));
        const signalId = item.connect('toggled', () => this._send('toggle_quick_control', control.id));
        item.connect('activate', () => item.stop_emission_by_name('activate'));
        target.addMenuItem(item);
        this._toggleRows.set(String(control.id), {item, signalId});
    }

    _send(name, value) {
        this._sendCommand?.(name, value);
    }

    destroy() {
        this._section = null;
        this._groups?.destroy();
        this._groups = null;
        this._rows = null;
        this._applications = null;
        this._links = null;
        this._matrix = null;
        this._mixer = null;
        this._output = null;
        this._levelRows.clear();
        this._toggleRows.clear();
        this._linkRows.clear();
        this._matrixRows.clear();
        this._disclosures.destroy();
    }
}
