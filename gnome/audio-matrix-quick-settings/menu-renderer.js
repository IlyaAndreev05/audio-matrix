import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import St from 'gi://St';
import AudioMatrixMenuRows from './menu-rows.js';
import {TEXT} from './menu-text.js';

const SOURCE_GROUP = 'sources';
const OUTPUT_GROUP = 'outputs';

function structureSignature(snapshot) {
    const state = snapshot.state || {};
    return JSON.stringify({
        available: snapshot.available, language: snapshot.language, commandError: snapshot.commandError,
        profile: state.profile, activeRoute: state.activeRoute, activeRouteId: state.activeRouteId,
        routes: (state.routes || []).map(({id, name}) => [id, name]),
        groups: (state.quickGroups || []).map(({id, name, order, collapsed}) => [id, name, order, collapsed]),
        outputs: (state.outputs || []).map(({id, name, description}) => [id, name, description]),
        applications: (state.applications || []).map(application => [
            application.id, application.name, application.application_id, application.process,
            (application.streams || []).map(stream => [stream.id, stream.name, stream.description]),
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

function updateLevelRows(rows, menuRows, state, t) {
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
        this._levelRows = new Map();
        this._structure = null;
        this._column = null;
        this._rows = new AudioMatrixMenuRows({
            section, getLanguage: () => this._getSnapshot().language, send,
            queueSliderCommand, onOpen, onDragBegin, onDragEnd,
        });
    }

    render(snapshot = this._getSnapshot()) {
        if (!this._section) return;
        const structure = structureSignature(snapshot);
        if (structure === this._structure) {
            updateLevelRows(this._levelRows, this._rows, snapshot.state, TEXT[snapshot.language]);
            return;
        }
        this._structure = structure;
        this._levelRows.clear();
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
        const controls = state.quickControls || [];
        const groups = state.quickGroups || [];
        for (const group of groups)
            this._renderGroup(group, state, controls, t);
        this._groupOpenState = new Map([...this._groupOpenState].filter(([id]) => groups.some(group => group.id === id)));
        this._groupCollapsedState = new Map(groups.map(group => [String(group.id), Boolean(group.collapsed)]));
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

    _renderGroup(group, state, controls, t) {
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
        const entries = controls.filter(control => String(control.groupId || control.section) === id)
            .sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
        if (id === SOURCE_GROUP) this._renderApplications(state, t, content);
        if (id === OUTPUT_GROUP) this._renderOutputs(state, t, content);
        this._renderControls(entries, content);
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

    _renderApplications(state, t, target) {
        const applications = state.applications || [];
        if (applications.length === 0) {
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.noApplications, {reactive: false, can_focus: false}));
            return;
        }
        for (const application of applications) {
            this._addLevelRow({
                id: `app:${application.id}`, name: application.name || application.id,
                icon: this._rows.applicationIcon(application), value: application.volume,
                muted: application.muted, mutedLabel: application.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_app_volume', `${application.id}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_app_mute', application.id),
            }, target);
            this._renderStreams(application, t, target);
        }
    }

    _renderStreams(application, t, target) {
        const streams = application.streams || [];
        if (streams.length === 0) return;
        const submenu = new PopupMenu.PopupSubMenuMenuItem(`${application.name || t.streams} (${streams.length})`, false);
        for (const stream of streams) {
            this._addLevelRow({
                id: `stream:${stream.id}`, name: stream.description || stream.name,
                icon: 'audio-x-generic-symbolic', value: stream.volume, muted: stream.muted,
                mutedLabel: stream.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_stream_volume', `${stream.id}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_stream_mute', String(stream.id)),
            }, submenu.menu);
        }
        target.addMenuItem(submenu);
    }

    _renderOutputs(state, t, target) {
        for (const output of state.outputs || []) {
            this._addLevelRow({
                id: `output:${output.id || output.name}`, name: output.description || output.name,
                icon: this._rows.outputIcon(output), value: output.volume, muted: output.muted,
                mutedLabel: output.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_output_volume', `${output.id || output.name}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_output_mute', output.id || output.name),
            }, target);
        }
    }

    _renderControls(controls, target) {
        for (const control of controls) {
            if (control.kind === 'slider') this._renderSliderControl(control, target);
            else if (control.kind === 'toggle') this._renderToggleControl(control, target);
            else if (control.kind === 'links') this._rows.addGraphLinks(control, target);
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
        item.connect('toggled', () => this._send('toggle_quick_control', control.id));
        target.addMenuItem(item);
    }

    _send(name, value) {
        this._sendCommand?.(name, value);
    }

    destroy() {
        this._section = null;
        this._rows = null;
        this._levelRows.clear();
    }
}
