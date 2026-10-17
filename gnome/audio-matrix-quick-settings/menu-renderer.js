import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import St from 'gi://St';
import AudioMatrixMenuRows from './menu-rows.js';
import AudioMatrixMenuApplications from './menu-applications.js';
import {TEXT} from './menu-text.js';

function structureSignature(snapshot) {
    const state = snapshot.state || {};
    return JSON.stringify({
        available: snapshot.available, language: snapshot.language, commandError: snapshot.commandError,
        profile: state.profile, activeRoute: state.activeRoute, activeRouteId: state.activeRouteId,
        routes: (state.routes || []).map(({id, name}) => [id, name]),
        groups: (state.quickGroups || []).map(({id, name, order, collapsed}) => [id, name, order, collapsed]),
        pins: (state.quickPins || []).map(pin => [
            pin.id, pin.routeId, pin.nodeId, pin.name, pin.nodeType, pin.groupId, pin.order, pin.view,
            (pin.controls || []).map(control => [control.id, control.parameterId, control.label, control.kind,
                control.multiple, (control.options || []).map(option => [option.id, option.label])]),
            (pin.applications || []).map(application => [application.id, application.name,
                (application.streams || []).map(stream => [stream.id, stream.name, stream.description])]),
            pin.output ? [pin.output.id, pin.output.name, pin.output.description, pin.output.available] : null,
        ]),
        controls: (state.quickControls || []).map(control => [
            control.id, control.nodeId, control.parameterId, control.label, control.section,
            control.groupId, control.order, control.kind, control.multiple,
            (control.options || []).map(option => [option.id, option.label]),
        ]),
    });
}

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

function updateMenuControls(rows, toggles, links, menuRows, state, t) {
    if (!state) return;
    for (const application of state.applications || []) {
        updateLevelRow(rows, menuRows, `app:${application.id}`, application, t);
        for (const stream of application.streams || [])
            updateLevelRow(rows, menuRows, `stream:${stream.id}`, stream, t);
    }
    for (const output of state.outputs || [])
        updateLevelRow(rows, menuRows, `output:${output.id || output.name}`, output, t);
    for (const control of state.quickControls || []) {
        if (control.kind === 'slider')
            updateLevelRow(rows, menuRows, `graph:${control.id}`, control, t);
        if (control.kind === 'toggle') {
            const entry = toggles.get(String(control.id));
            if (!entry) continue;
            entry.item.block_signal_handler(entry.signalId);
            entry.item.state = Boolean(control.value);
            entry.item.unblock_signal_handler(entry.signalId);
        }
        if (control.kind === 'links')
            menuRows.updateGraphLinks(links.get(String(control.id)), control);
    }
}

function updateLevelRow(rows, menuRows, id, source, t) {
    menuRows.updateLevelRow(rows.get(id), {
        value: source.volume ?? source.value,
        muted: Boolean(source.muted),
        mutedLabel: source.muted ? t.unmute : t.mute,
    });
}

export default class AudioMatrixMenuRenderer {
    constructor({section, getSnapshot, send, queueSliderCommand, onOpen, onDragBegin, onDragEnd}) {
        this._section = section;
        this._getSnapshot = getSnapshot;
        this._onOpen = onOpen;
        this._sendCommand = send;
        this._groupOpenState = new Map();
        this._groupCollapsedState = new Map();
        this._pinOpenState = new Map();
        this._pinViewState = new Map();
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
    }

    render(snapshot = this._getSnapshot()) {
        if (!this._section) return;
        const structure = structureSignature(snapshot);
        if (structure === this._structure) {
            updateMenuControls(this._levelRows, this._toggleRows, this._linkRows, this._rows, snapshot.state, TEXT[snapshot.language]);
            return;
        }
        this._structure = structure;
        this._levelRows.clear();
        this._toggleRows.clear();
        this._linkRows.clear();
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
            return;
        }
        this._addRoutePicker(state, t);
        this._column = createScrollableGroups(this._section);
        const groups = state.quickGroups || [];
        for (const group of groups)
            this._renderGroup(group, state.quickPins || [], t);
        this._groupOpenState = new Map([...this._groupOpenState].filter(([id]) => groups.some(group => group.id === id)));
        this._groupCollapsedState = new Map(groups.map(group => [String(group.id), Boolean(group.collapsed)]));
        this._pinOpenState = new Map([...this._pinOpenState].filter(([id]) => (state.quickPins || []).some(pin => pin.id === id)));
        this._pinViewState = new Map((state.quickPins || []).map(pin => [String(pin.id), String(pin.view || 'DEFAULT')]));
        this._section.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this._rows.addAction(t.open, () => this._onOpen(true), 'preferences-system-symbolic');
    }

    _addRoutePicker(state, t) {
        const routes = state.routes || [];
        if (routes.length < 2) return;
        const name = state.activeRoute || routes.find(route => route.id === state.activeRouteId)?.name || '—';
        const picker = new PopupMenu.PopupSubMenuMenuItem(`${t.route}: ${name}`, false);
        for (const route of routes) {
            const item = new PopupMenu.PopupMenuItem(route.name || route.id);
            item.connect('activate', () => this._send('select_route', route.id));
            picker.menu.addMenuItem(item);
        }
        this._section.addMenuItem(picker);
    }

    _renderGroup(group, pins, t) {
        const id = String(group.id || group.name);
        const collapsed = Boolean(group.collapsed);
        const previousCollapsed = this._groupCollapsedState.get(id);
        const configChanged = previousCollapsed !== undefined && previousCollapsed !== collapsed;
        const wasOpen = this._groupOpenState.get(id);
        const open = configChanged ? !collapsed : (wasOpen ?? !collapsed);
        const header = new PopupMenu.PopupImageMenuItem(
            String(group.name || id), open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
        const content = new PopupMenu.PopupMenuSection();
        const entries = pins.filter(pin => String(pin.groupId) === id)
            .sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
        for (const pin of entries)
            this._renderPin(pin, t, content);
        this._column.addMenuItem(header);
        this._column.addMenuItem(content);
        content.actor.visible = open;
        this._groupOpenState.set(id, open);
        const toggle = () => {
            const nextOpen = !this._groupOpenState.get(id);
            this._groupOpenState.set(id, nextOpen);
            content.actor.visible = nextOpen;
            header.setIcon(nextOpen ? 'pan-down-symbolic' : 'pan-end-symbolic');
            if (nextOpen) header.add_accessible_state(Atk.StateType.EXPANDED);
            else header.remove_accessible_state(Atk.StateType.EXPANDED);
            this._send('set_quick_group_collapsed', `${id}\t${!nextOpen}`);
        };
        header.activate = toggle;
        header.connect('activate', () => {
            toggle();
            header.stop_emission_by_name('activate');
        });
    }

    _renderPin(pin, t, target) {
        const id = String(pin.id);
        const view = String(pin.view || 'DEFAULT');
        const previousView = this._pinViewState.get(id);
        const viewChanged = previousView !== undefined && previousView !== view;
        const open = viewChanged ? view !== 'COMPACT' : (this._pinOpenState.get(id) ?? view !== 'COMPACT');
        const header = new PopupMenu.PopupImageMenuItem(
            String(pin.name || pin.nodeId), open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
        const content = new PopupMenu.PopupMenuSection();
        if (pin.nodeType === 'source')
            this._applications.render(pin.applications || [], t, content);
        if (pin.nodeType === 'output')
            this._renderPinnedOutput(pin, t, content);
        this._renderControls(pin.controls || [], content);
        target.addMenuItem(header);
        target.addMenuItem(content);
        content.actor.visible = open;
        this._pinOpenState.set(id, open);
        const toggle = () => {
            const nextOpen = !this._pinOpenState.get(id);
            this._pinOpenState.set(id, nextOpen);
            content.actor.visible = nextOpen;
            header.setIcon(nextOpen ? 'pan-down-symbolic' : 'pan-end-symbolic');
            if (nextOpen) header.add_accessible_state(Atk.StateType.EXPANDED);
            else header.remove_accessible_state(Atk.StateType.EXPANDED);
            this._send('set_quick_pin_view', id + '\t' + (nextOpen ? 'EXPANDED' : 'COMPACT'));
        };
        header.activate = toggle;
        header.connect('activate', () => {
            toggle();
            header.stop_emission_by_name('activate');
        });
    }

    _renderPinnedOutput(pin, t, target) {
        const output = pin.output;
        if (!output?.available) {
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.unavailable, {reactive: false, can_focus: false}));
            return;
        }
        this._addLevelRow({
            id: 'output:' + (output.id || output.name), name: output.description || output.name,
            icon: this._rows.outputIcon(output), value: output.volume, muted: output.muted,
            mutedLabel: output.muted ? t.unmute : t.mute,
            onValue: value => this._send('set_output_volume',
                (output.id || output.name) + '\t' + value.toFixed(3)),
            onMute: () => this._send('toggle_output_mute', output.id || output.name),
        }, target);
    }

    _renderControls(controls, target) {
        for (const control of controls) {
            if (control.kind === 'slider') this._renderSliderControl(control, target);
            else if (control.kind === 'toggle') this._renderToggleControl(control, target);
            else if (control.kind === 'links')
                this._linkRows.set(String(control.id), this._rows.addGraphLinks(control, target));
        }
    }

    _renderSliderControl(control, target) {
        this._addLevelRow({
            id: `graph:${control.id}`, name: control.label || control.parameterId,
            icon: this._rows.controlIcon(control), value: control.value, muted: control.muted,
            onMute: () => this._send('toggle_quick_control', control.id),
            onValue: value => this._send('set_quick_control', `${control.id}\t${value.toFixed(3)}`),
        }, target);
    }

    _addLevelRow(options, target) {
        const row = this._rows.addLevelRow(options, target);
        this._levelRows.set(String(options.id), row);
    }

    _renderToggleControl(control, target) {
        const item = new PopupMenu.PopupSwitchMenuItem(control.label || control.parameterId, Boolean(control.value));
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
        this._rows = null;
        this._applications = null;
        this._levelRows.clear();
        this._toggleRows.clear();
        this._linkRows.clear();
        this._pinOpenState.clear();
        this._pinViewState.clear();
    }
}
