import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import St from 'gi://St';
import AudioMatrixMenuRows from './menu-rows.js';
import AudioMatrixMenuApplications from './menu-applications.js';
import AudioMatrixMenuLinks from './menu-links.js';
import AudioMatrixMenuMatrix from './menu-matrix.js';
import AudioMatrixMenuMixer from './menu-mixer.js';
import AudioMatrixMenuOutput from './menu-output.js';
import AudioMatrixMenuDisclosure from './menu-disclosure.js';
import AudioMatrixMenuGroups from './menu-groups.js';
import AudioMatrixMenuQuickControls from './menu-quick-controls.js';
import {menuLevelRows, menuStructureSignature} from './menu-state.js';
import {captureMenuViewport, restoreMenuViewport} from './menu-viewport.js';
import {outputIcon} from './menu-icons.js';
import {controlPosition, formatControlValue} from './menu-control-scale.js';
import {TEXT} from './menu-text.js';
import {reorderMenuItems} from './menu-row-utils.js';

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
    scroll.set_style(`max-height: ${Math.max(120, Math.min(560, Math.floor(availableHeight * 0.55)))}px; max-width: 24em;`);
    scroll._delegate = column;
    column.actor = scroll;
    section.addMenuItem(column);
    return column;
}

function canonical(value) {
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
    if (value && typeof value === 'object')
        return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
    return JSON.stringify(value);
}

function pendingValue(pending, target, property) {
    if (!target) return undefined;
    return pending.find(item => item.property === property && canonical(item.target) === canonical(target))?.value;
}

function updateMenuControls(rows, toggles, linkRows, matrixRows, linkRenderer, matrixRenderer, menuRows,
    state, t, pending) {
    if (!state) return;
    for (const pin of state.quickPins || []) {
        const control = pin.routeControl;
        if (!control) continue;
        const active = new Set(Array.isArray(control.value) ? control.value.map(String) : []);
        for (const option of control.options || []) {
            const target = {routeId: String(control.routeId),
                switchNodeId: String(control.switchNodeId || control.nodeId), linkId: String(option.id)};
            const intent = pendingValue(pending, target, 'enabled');
            if (intent === true) active.add(String(option.id));
            else if (intent === false) active.delete(String(option.id));
        }
        linkRenderer.update(linkRows.get(String(control.id)), {...control, value: [...active]});
    }
    for (const [id, source] of menuLevelRows(state))
        updateLevelRow(rows, menuRows, id, source, t, pending);
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

function updateLevelRow(rows, menuRows, id, source, t, pending) {
    const row = rows.get(id);
    if (!row) return;
    const target = row._audioMatrixCommandTarget;
    const volume = pendingValue(pending, target, 'volume');
    const muted = pendingValue(pending, target, 'muted');
    menuRows.updateLevelRow(row, {
        value: volume ?? source.volume ?? source.value,
        muted: muted ?? Boolean(source.muted),
        mutedLabel: (muted ?? source.muted) ? t.unmute : t.mute,
    });
}

function createProfilePicker(section, send) {
    const item = new PopupMenu.PopupSubMenuMenuItem('Profile: —', false);
    item.accessible_name = 'Audio Matrix profile';
    section.addMenuItem(item);
    return {item, rows: new Map(), order: [], send};
}

function updateProfilePicker(picker, state, t, pending) {
    const profiles = state?.profiles || [];
    const requested = pending.find(intent => intent.property === 'activeProfileId')?.value;
    const activeId = requested ?? state?.activeProfileId ?? '';
    const active = profiles.find(profile => String(profile.id) === String(activeId));
    picker.item.label.text = `${t.profile}: ${active?.name || (profiles[0]?.name ?? '—')}`;
    picker.item.accessible_name = t.profile;
    const desired = [];
    for (const profile of profiles) {
        const id = String(profile.id);
        let row = picker.rows.get(id);
        if (!row) {
            row = new PopupMenu.PopupMenuItem(String(profile.name || id));
            row.accessible_role = Atk.Role.RADIO_BUTTON;
            row.connect('activate', () => picker.send('select_profile', {profileId: id}));
            picker.rows.set(id, row);
        }
        row.label.text = String(profile.name || id);
        row.setOrnament(id === String(activeId) ? PopupMenu.Ornament.DOT : PopupMenu.Ornament.NO_DOT);
        desired.push(row);
    }
    for (const id of picker.rows.keys()) {
        if (!profiles.some(profile => String(profile.id) === id))
            picker.rows.delete(id);
    }
    reorderMenuItems(picker.item.menu, picker.order, desired);
    picker.order = desired;
}

function createRoutePicker(section, send) {
    const item = new PopupMenu.PopupSubMenuMenuItem('Route: —', false);
    item.accessible_name = 'Audio Matrix route';
    section.addMenuItem(item);
    return {item, rows: new Map(), order: [], send};
}

function updateRoutePicker(picker, state, t) {
    const routes = state?.routes || [];
    const activeId = String(state?.activeRouteId || '');
    const active = routes.find(route => String(route.id) === activeId);
    picker.item.label.text = `${t.route}: ${active?.name || state?.activeRoute || '—'}`;
    picker.item.accessible_name = t.route;
    picker.item.actor.visible = routes.length > 1;
    const desired = [];
    for (const route of routes) {
        const id = String(route.id);
        let row = picker.rows.get(id);
        if (!row) {
            row = new PopupMenu.PopupMenuItem(String(route.name || id));
            row.accessible_role = Atk.Role.RADIO_BUTTON;
            row.connect('activate', () => picker.send('select_route', id));
            picker.rows.set(id, row);
        }
        row.label.text = String(route.name || id);
        row.setOrnament(id === activeId ? PopupMenu.Ornament.DOT : PopupMenu.Ornament.NO_DOT);
        desired.push(row);
    }
    for (const id of picker.rows.keys()) {
        if (!routes.some(route => String(route.id) === id))
            picker.rows.delete(id);
    }
    reorderMenuItems(picker.item.menu, picker.order, desired);
    picker.order = desired;
}

function setVisible(item, visible) {
    if (item?.actor) item.actor.visible = visible;
    else if (item) item.visible = visible;
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
        this._matrixRows = new Map();
        this._structure = null;
        this._column = null;
        this._rows = new AudioMatrixMenuRows({
            section, getLanguage: () => this._getSnapshot().language, send,
            queueSliderCommand, onOpen, onDragBegin, onDragEnd,
        });
        this._applications = new AudioMatrixMenuApplications({
            rows: this._rows, send: (name, value) => this._send(name, value),
            registerLevelRow: (id, row) => this._levelRows.set(id, row),
            unregisterLevelRow: id => this._levelRows.delete(id),
        });
        this._links = new AudioMatrixMenuLinks((name, value) => this._send(name, value));
        this._matrix = new AudioMatrixMenuMatrix((name, value) => this._send(name, value));
        this._mixer = new AudioMatrixMenuMixer({
            addLevelRow: (options, target) => this._addLevelRow(options, target),
            send: (name, value) => this._send(name, value),
        });
        this._output = new AudioMatrixMenuOutput({
            addLevelRow: (options, target) => this._addLevelRow(options, target),
            outputIcon,
            send: (name, value) => this._send(name, value),
        });
        this._groups = new AudioMatrixMenuGroups({
            send: (name, value) => this._send(name, value),
            disclosures: this._disclosures,
            applications: this._applications,
            links: this._links,
            mixer: this._mixer,
            output: this._output,
            renderControls: (controls, t, target) => this._quickControls.render(controls, t, target),
            registerLinkRow: (id, row) => this._linkRows.set(id, row),
        });
        this._quickControls = new AudioMatrixMenuQuickControls({
            rows: this._rows, send: (name, value) => this._send(name, value),
            getLanguage: () => this._getSnapshot().language,
            links: this._links, matrix: this._matrix,
            registerLevelRow: (id, row) => this._levelRows.set(id, row),
            registerToggleRow: (id, row) => this._toggleRows.set(id, row),
            registerLinkRow: (id, row) => this._linkRows.set(id, row),
            registerMatrixRow: (id, row) => this._matrixRows.set(id, row),
        });
        this._initialized = false;
    }

    render(snapshot = this._getSnapshot()) {
        if (!this._section) return;
        if (!this._initialized) this._createShell(snapshot);
        const {state, available, language, commandError, apiMismatch} = snapshot;
        const t = TEXT[language];
        this._rows.updateHeader(this._header, state, available);
        updateProfilePicker(this._profilePicker, state, t, snapshot.pendingIntents || []);
        updateRoutePicker(this._routePicker, state, t);
        this._rows.updateStatus(this._errorStatus,
            commandError ? `${t.commandError}: ${commandError}` : '');
        setVisible(this._errorStatus, Boolean(commandError));
        this._rows.updateStatus(this._connectionStatus,
            apiMismatch || (state && available ? '' : state ? t.unavailable : t.connecting));
        setVisible(this._connectionStatus, !available);
        setVisible(this._column, Boolean(state));
        if (this._column?.actor) this._column.actor.reactive = Boolean(available);
        if (!state) return;

        const structure = menuStructureSignature(snapshot);
        if (structure !== this._structure) {
            const focus = global.stage?.get_key_focus?.() || null;
            const viewport = this._column
                ? captureMenuViewport(this._column.actor, this._groups.visibleRows(), focus) : null;
            this._groups.render(this._column, state, state.quickPins || [], t);
            this._disclosures.retain(state);
            this._structure = structure;
            if (viewport) restoreMenuViewport(this._column.actor, viewport, this._groups.visibleRows(),
                actor => global.stage?.set_key_focus?.(actor));
        }
        updateMenuControls(this._levelRows, this._toggleRows, this._linkRows, this._matrixRows,
            this._links, this._matrix, this._rows, state, t, snapshot.pendingIntents || []);
        this._disclosures.sync(state);
    }

    _createShell(snapshot) {
        this._section.actor.set_style('max-width: 24em;');
        this._header = this._rows.addHeader(snapshot.state, snapshot.available);
        this._profilePicker = createProfilePicker(this._section, (name, value) => this._send(name, value));
        this._routePicker = createRoutePicker(this._section, (name, value) => this._send(name, value));
        this._errorStatus = this._rows.addStatus('', 'dialog-warning-symbolic');
        this._connectionStatus = this._rows.addStatus('', 'dialog-information-symbolic');
        this._column = createScrollableGroups(this._section);
        this._separator = new PopupMenu.PopupSeparatorMenuItem();
        this._section.addMenuItem(this._separator);
        this._openItem = this._rows.addAction(TEXT[snapshot.language].open,
            () => this._onOpen(Boolean(this._getSnapshot().available)), 'preferences-system-symbolic');
        this._initialized = true;
    }

    _renderControls(controls, t, target) {
        this._quickControls.render(controls, t, target);
    }

    _addLevelRow(options, target) {
        const row = this._rows.addLevelRow(options, target);
        row._audioMatrixCommandTarget = options.commandTarget || null;
        this._levelRows.set(String(options.id), row);
        return row;
    }

    _send(name, value) {
        this._sendCommand?.(name, value);
    }

    destroy() {
        this._section = null;
        this._groups = null;
        this._quickControls?.destroy();
        this._quickControls = null;
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
