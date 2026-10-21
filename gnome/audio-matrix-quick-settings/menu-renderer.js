import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import St from 'gi://St';
import Atk from 'gi://Atk';
import GLib from 'gi://GLib';
import AudioMatrixKeyedSection from './menu-keyed-section.js';
import AudioMatrixMenuRows from './menu-rows.js';
import AudioMatrixMenuApplications from './menu-applications.js';
import AudioMatrixMenuLinks from './menu-links.js';
import AudioMatrixMenuMatrix from './menu-matrix.js';
import AudioMatrixMenuMixer from './menu-mixer.js';
import AudioMatrixMenuOutput from './menu-output.js';
import AudioMatrixMenuDisclosure from './menu-disclosure.js';
import AudioMatrixMenuGroups from './menu-groups.js';
import {hasAvailableMenuState, menuLevelRows, menuStructureSignature, pendingValue} from './menu-state.js';
import {controlPosition, controlValueAt, formatControlValue} from './menu-control-scale.js';
import {TEXT} from './menu-text.js';
import {captureMenuAnchor, restoreMenuAnchor} from './menu-anchor.js';

const TOGGLE_ROWS = new WeakMap();

function createScrollableGroups(section) {
    const content = new PopupMenu.PopupMenuSection();
    const scroll = new St.ScrollView({
        style_class: 'popup-sub-menu',
        hscrollbar_policy: St.PolicyType.NEVER,
        vscrollbar_policy: St.PolicyType.AUTOMATIC,
        x_expand: true,
        child: content.box,
    });
    const monitor = global.display.get_monitor_geometry(global.display.get_current_monitor());
    const availableHeight = monitor?.height || 800;
    scroll.set_style(`max-height: ${Math.max(120, Math.min(560, Math.floor(availableHeight * 0.55)))}px; max-width: 24em;`);
    scroll._delegate = content;
    content.actor = scroll;
    content._audioMatrixKey = 'scroll-content';
    section.addMenuItem(content);
    return {scroll, content, column: new AudioMatrixKeyedSection(content)};
}

function updateMenuControls(rows, toggles, linkRows, matrixRows, linkRenderer, matrixRenderer,
    menuRows, snapshot, t) {
    const state = snapshot.state;
    if (!state) return;
    for (const pin of state.quickPins || []) {
        if (!pin.routeControl) continue;
        const control = {...pin.routeControl};
        const selected = new Set(Array.isArray(control.value) ? control.value.map(String)
            : control.value == null ? [] : [String(control.value)]);
        for (const option of control.options || []) {
            const enabled = pendingValue(snapshot, {
                routeId: control.routeId, switchNodeId: control.nodeId, linkId: String(option.id),
            }, 'enabled', selected.has(String(option.id)));
            if (enabled) selected.add(String(option.id));
            else selected.delete(String(option.id));
        }
        control.value = [...selected];
        linkRenderer.update(linkRows.get(String(control.id)), control);
    }
    for (const [id, source] of menuLevelRows(state))
        updateLevelRow(rows, menuRows, id, source, snapshot, t);
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

function updateLevelRow(rows, menuRows, id, source, snapshot, t) {
    menuRows.updateLevelRow(rows.get(id), {
        value: pendingValue(snapshot, source.target, 'volume', source.volume ?? source.value),
        muted: Boolean(pendingValue(snapshot, source.target, 'muted', source.muted)),
        mutedLabel: source.muted ? t.unmute : t.mute,
    });
}

function scrollAdjustment(scroll) {
    return scroll.vscroll?.adjustment || scroll.get_vscroll_bar?.()?.adjustment
        || scroll.get_vscroll_bar?.()?.get_adjustment?.() || null;
}

function keyedRows(actor, result = []) {
    if (!actor) return result;
    const item = actor._delegate || actor;
    if (item._audioMatrixKey && actor.visible !== false)
        result.push({key: String(item._audioMatrixKey), actor});
    for (const child of actor.get_children?.() || [])
        keyedRows(child, result);
    return result;
}

function findRowByKey(scroll, key) {
    return keyedRows(scroll.get_child?.() || scroll, []).find(row => row.key === key)?.actor || null;
}

function transformedY(actor) {
    try {
        return actor.get_transformed_position()[1];
    } catch (_error) {
        return 0;
    }
}

function focusKey(actor) {
    for (let current = actor; current; current = current.get_parent?.()) {
        const item = current._delegate || current;
        if (item._audioMatrixKey)
            return String(item._audioMatrixKey);
    }
    return '';
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
        this._rows = new AudioMatrixMenuRows({
            section, getLanguage: () => this._getSnapshot().language, send,
            queueSliderCommand, onOpen, onDragBegin, onDragEnd,
        });
        section.actor.set_style('max-width: 24em;');
        this._header = this._rows.addHeader(null, false);
        section.addMenuItem(this._header.item);
        this._profilePicker = new PopupMenu.PopupSubMenuMenuItem('Profile: —', false);
        this._profilePicker._audioMatrixKey = 'profile-picker';
        section.addMenuItem(this._profilePicker);
        this._profileRows = new Map();
        this._routePicker = new PopupMenu.PopupSubMenuMenuItem('Route: —', false);
        this._routePicker._audioMatrixKey = 'route-picker';
        section.addMenuItem(this._routePicker);
        this._routeRows = new Map();
        this._status = this._rows.addStatus('', 'dialog-information-symbolic');
        this._status.item.actor.visible = false;
        section.addMenuItem(this._status.item);
        this._scrollHost = createScrollableGroups(section);
        this._column = this._scrollHost.column;
        this._separator = new PopupMenu.PopupSeparatorMenuItem();
        section.addMenuItem(this._separator);
        this._openItem = this._rows.addAction('', () => this._onOpen(Boolean(this._getSnapshot().available)),
            'preferences-system-symbolic', section);
        this._applications = new AudioMatrixMenuApplications({
            addLevelRow: (options, target) => this._addLevelRow(options, target),
            rows: this._rows,
            send: (name, value) => this._send(name, value),
        });
        this._links = new AudioMatrixMenuLinks((name, value) => this._send(name, value));
        this._matrix = new AudioMatrixMenuMatrix((name, value) => this._send(name, value));
        this._mixer = new AudioMatrixMenuMixer();
        this._output = new AudioMatrixMenuOutput({
            addLevelRow: (options, target) => this._addLevelRow(options, target),
            outputIcon: output => this._rows.outputIcon(output),
            send: (name, value) => this._send(name, value),
        });
        this._groups = new AudioMatrixMenuGroups({
            send: (name, value) => this._send(name, value),
            disclosures: this._disclosures,
            applications: this._applications,
            links: this._links,
            mixer: this._mixer,
            output: this._output,
            renderControls: (controls, t, target) => this._renderControls(controls, t, target),
            linkRows: this._linkRows,
        });
    }

    render(snapshot = this._getSnapshot()) {
        if (!this._section) return;
        const state = snapshot.state;
        const t = TEXT[snapshot.language] || TEXT.en;
        const available = hasAvailableMenuState(snapshot);
        this._rows.updateHeader(this._header, state, available);
        this._updateProfilePicker(state, snapshot, t);
        this._updateRoutePicker(state, snapshot, t);
        this._updateStatus(snapshot, t);
        this._openItem.label.text = t.open;
        this._openItem.accessible_name = t.open;
        this._status.item.actor.visible = Boolean(snapshot.commandError || !available);

        const structure = menuStructureSignature(snapshot);
        const structureChanged = structure !== this._structure;
        if (structureChanged) {
            const viewport = this._captureViewport();
            this._levelRows.clear();
            this._toggleRows.clear();
            this._linkRows.clear();
            this._matrixRows.clear();
            this._disclosures.resetRows();
            const renderState = available ? state : {quickGroups: [], quickPins: [], quickControls: []};
            this._groups.render(this._column, renderState, available ? state.quickPins || [] : [], t);
            if (available)
                this._disclosures.sync(state);
            this._structure = structure;
            if (viewport)
                this._restoreViewport(viewport);
        }
        if (available)
            updateMenuControls(this._levelRows, this._toggleRows, this._linkRows, this._matrixRows,
                this._links, this._matrix, this._rows, snapshot, t);
    }

    _updateProfilePicker(state, snapshot, t) {
        const profiles = state?.profiles || [];
        const profileIds = new Set(profiles.map(profile => String(profile.id)));
        for (const [id, row] of this._profileRows) {
            if (profileIds.has(id)) continue;
            this._profilePicker.menu.removeMenuItem(row);
            this._profileRows.delete(id);
        }
        const pending = [...(snapshot.pending?.values() || [])]
            .find(intent => intent.property === 'activeProfileId')?.desired;
        const activeId = String(pending ?? state?.activeProfileId ?? '');
        const active = profiles.find(profile => String(profile.id) === activeId);
        this._profilePicker.label.text = `${t.profile}: ${active?.name || state?.profile || '—'}`;
        this._profilePicker.actor.visible = true;
        for (const profile of profiles) {
            const id = String(profile.id);
            let row = this._profileRows.get(id);
            if (!row) {
                row = new PopupMenu.PopupMenuItem(String(profile.name || id));
                row.accessible_role = Atk.Role.RADIO_BUTTON;
                row.connect('activate', () => this._send('select_profile', {profileId: id}));
                this._profileRows.set(id, row);
                this._profilePicker.menu.addMenuItem(row);
            }
            row.label.text = String(profile.name || id);
            row.setOrnament(id === activeId ? PopupMenu.Ornament.DOT : PopupMenu.Ornament.NO_DOT);
        }
    }

    _updateRoutePicker(state, snapshot, t) {
        const routes = state?.routes || [];
        const routeIds = new Set(routes.map(route => String(route.id)));
        for (const [id, row] of this._routeRows) {
            if (routeIds.has(id)) continue;
            this._routePicker.menu.removeMenuItem(row);
            this._routeRows.delete(id);
        }
        const activeId = String(state?.activeRouteId || '');
        const name = state?.activeRoute || routes.find(route => String(route.id) === activeId)?.name || '—';
        this._routePicker.label.text = `${t.route}: ${name}`;
        this._routePicker.actor.visible = routes.length > 1;
        for (const route of routes) {
            const id = String(route.id);
            let row = this._routeRows.get(id);
            if (!row) {
                row = new PopupMenu.PopupMenuItem(String(route.name || id));
                row.connect('activate', () => this._send('select_route', id));
                this._routeRows.set(id, row);
                this._routePicker.menu.addMenuItem(row);
            }
            row.label.text = String(route.name || id);
            row.setOrnament(id === activeId ? PopupMenu.Ornament.DOT : PopupMenu.Ornament.NO_DOT);
        }
    }

    _updateStatus(snapshot, t) {
        if (snapshot.commandError) {
            this._status.icon.icon_name = 'dialog-warning-symbolic';
            this._status.label.text = `${t.commandError}: ${snapshot.commandError}`;
        } else if (!snapshot.available) {
            this._status.icon.icon_name = 'dialog-information-symbolic';
            this._status.label.text = snapshot.apiMismatch || (snapshot.state ? t.unavailable : t.connecting);
        } else {
            this._status.label.text = '';
        }
    }

    _captureViewport() {
        const scroll = this._scrollHost.scroll;
        const adjustment = scrollAdjustment(scroll);
        if (!adjustment)
            return null;
        const rows = keyedRows(scroll.get_child?.() || scroll)
            .filter(row => row.actor.visible !== false)
            .map(row => ({key: row.key, y: transformedY(row.actor), height: Number(row.actor.height) || 1}));
        const focus = global.stage.get_key_focus?.() || null;
        return {
            ...captureMenuAnchor(rows, transformedY(scroll), Number(scroll.height) || 0,
                adjustment.value, focusKey(focus)),
            focus,
        };
    }

    _restoreViewport(viewport) {
        GLib.idle_add(GLib.PRIORITY_DEFAULT_IDLE, () => {
            const scroll = this._scrollHost?.scroll;
            const adjustment = scroll ? scrollAdjustment(scroll) : null;
            if (!scroll || !adjustment)
                return GLib.SOURCE_REMOVE;
            const currentRows = keyedRows(scroll.get_child?.() || scroll)
                .filter(row => row.actor.visible !== false)
                .map(row => ({key: row.key, actor: row.actor, y: transformedY(row.actor),
                    height: Number(row.actor.height) || 1}));
            const restored = restoreMenuAnchor(viewport, currentRows, transformedY(scroll), {
                lower: adjustment.lower, upper: adjustment.upper, pageSize: adjustment.page_size,
            });
            adjustment.value = restored.value;

            let focus = viewport.focus;
            if (restored.focusKey && restored.focusKey !== viewport.focusKey)
                focus = findRowByKey(scroll, restored.focusKey);
            focus?.grab_key_focus?.();
            return GLib.SOURCE_REMOVE;
        });
    }


    _renderControls(controls, t, target) {
        for (const control of controls) {
            if (control.kind === 'slider' || control.kind === 'range') this._renderSliderControl(control, target);
            else if (control.kind === 'toggle') this._renderToggleControl(control, target);
            else if (control.kind === 'links' && control.nodeType === 'matrix')
                this._matrixRows.set(String(control.id), this._matrix.render(control, t, target));
            else if (control.kind === 'links' || control.kind === 'choice')
                this._linkRows.set(String(control.id), this._links.render(control, target, t));
        }
    }

    _renderSliderControl(control, target) {
        const range = control.kind === 'range';
        const options = {
            id: `graph:${control.id}`, key: `graph:${control.id}`, name: control.label || control.parameterId,
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
        const language = this._getSnapshot().language || 'en';
        const label = control.nodeType === 'gate' ? TEXT[language].onOff
            : control.label || control.parameterId;
        const state = {control, item: null, signalId: 0};
        const item = new PopupMenu.PopupSwitchMenuItem(label, Boolean(control.value));
        state.item = item;
        state.signalId = item.connect('toggled', () => this._send('toggle_quick_control', state.control.id));
        item.connect('activate', () => item.stop_emission_by_name('activate'));
        item._audioMatrixKey = `toggle:${control.id}`;
        item._audioMatrixKind = 'quick-toggle';
        TOGGLE_ROWS.set(item, state);
        item._audioMatrixPatch = incoming => {
            const next = TOGGLE_ROWS.get(incoming);
            if (!next) return;
            state.control = next.control;
            item.label.text = next.item.label.text;
            item.block_signal_handler(state.signalId);
            item.state = next.item.state;
            item.unblock_signal_handler(state.signalId);
        };
        const actual = target.addMenuItem(item) || item;
        const actualState = TOGGLE_ROWS.get(actual) || state;
        this._toggleRows.set(String(control.id), actualState);
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
        this._profileRows.clear();
        this._routeRows.clear();
        this._disclosures.destroy();
    }
}
