import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import St from 'gi://St';
import Meta from 'gi://Meta';
import AudioMatrixMenuRows from './menu-rows.js';
import AudioMatrixMenuApplications from './menu-applications.js';
import AudioMatrixMenuLinks from './menu-links.js';
import AudioMatrixMenuOutput from './menu-output.js';
import AudioMatrixMenuDisclosure from './menu-disclosure.js';
import AudioMatrixMenuGroups from './menu-groups.js';
import AudioMatrixPinnedControls from './menu-pinned-controls.js';
import {allQuickPins, menuLevelRows, menuStructureSignature} from './menu-state.js';
import {captureMenuViewport, restoreMenuViewport} from './menu-viewport.js';
import {outputIcon} from './menu-icons.js';
import {TEXT} from './menu-text.js';

function createScrollableGroups(section) {
    const column = new PopupMenu.PopupMenuSection();
    const scroll = new St.ScrollView({style_class: 'audio-matrix-scroll',
        hscrollbar_policy: St.PolicyType.NEVER, vscrollbar_policy: St.PolicyType.AUTOMATIC,
        x_expand: true, child: column.box});
    const monitor = global.display.get_monitor_geometry(global.display.get_current_monitor());
    const scale = St.ThemeContext.get_for_stage(global.stage).scale_factor || 1;
    const height = (monitor?.height || 800) / scale;
    scroll.set_style(`max-height: ${Math.max(120, Math.min(720, Math.floor(height - 360)))}px;`);
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

function within(root, actor) {
    for (let current = actor; current; current = current.get_parent?.())
        if (current === root) return true;
    return false;
}

export default class AudioMatrixMenuRenderer {
    constructor({section, getSnapshot, send, queueSliderCommand, onOpen, onDragBegin, onDragEnd}) {
        this._section = section;
        this._getSnapshot = getSnapshot;
        this._disclosures = new AudioMatrixMenuDisclosure();
        this._levelRows = new Map();
        this._linkRows = new Map();
        this._structure = null;
        this._viewportLater = 0;
        this._rows = new AudioMatrixMenuRows({section, getLanguage: () => 'ru', send,
            queueSliderCommand, onOpen, onDragBegin, onDragEnd});
        this._applications = new AudioMatrixMenuApplications({rows: this._rows, send,
            registerLevelRow: (id, row) => this._levelRows.set(id, row),
            unregisterLevelRow: id => this._levelRows.delete(id)});
        this._links = new AudioMatrixMenuLinks(send);
        this._output = new AudioMatrixMenuOutput({
            addLevelRow: (options, target) => {
                const row = this._rows.addLevelRow(options, target);
                this._levelRows.set(String(options.id), row);
                return row;
            }, outputIcon, send});
        this._pinnedControls = new AudioMatrixPinnedControls({rows: this._rows, links: this._links, send,
            registerLevelRow: (id, row) => this._levelRows.set(id, row)});
        this._groups = new AudioMatrixMenuGroups({send, disclosures: this._disclosures,
            applications: this._applications, links: this._links, output: this._output,
            registerLinkRow: (id, row) => this._linkRows.set(id, row),
            renderExtra: (pin, t, target) => this._pinnedControls.render(pin, t, target),
            forgetLevelRows: wrapper => {
                for (const [id, row] of this._levelRows)
                    if (within(wrapper.actor, row.actor || row)) this._levelRows.delete(id);
                for (const [id, reference] of this._linkRows)
                    if (within(wrapper.actor, reference.card?.actor)) this._linkRows.delete(id);
            }});
    }

    render(snapshot = this._getSnapshot()) {
        if (!this._section) return;
        if (!this._column) this._createShell(snapshot);
        const {state, available, commandError, apiMismatch} = snapshot;
        const t = TEXT.ru;
        this._rows.updateHeader(this._header, state, available);
        this._rows.updateStatus(this._errorStatus, commandError ? `${t.commandError}: ${commandError}` : '');
        this._errorStatus.actor.visible = Boolean(commandError);
        this._rows.updateStatus(this._connectionStatus, apiMismatch || (available ? '' : state ? t.unavailable : t.connecting));
        this._connectionStatus.actor.visible = !available;
        this._column.actor.visible = Boolean(state);
        if (!state) return;
        const structure = menuStructureSignature(snapshot);
        const changed = structure !== this._structure;
        const focus = changed ? global.stage?.get_key_focus?.() : null;
        const viewport = changed ? captureMenuViewport(this._column.actor, this._groups.visibleRows(), focus) : null;
        this._groups.render(this._column, state, state.quickPins || [], t);
        this._disclosures.retain(state);
        this._disclosures.sync(state);
        this._structure = structure;
        this._updateControls(state, snapshot.pendingIntents || [], available);
        this._pinnedControls.update(state, (target, property) =>
            pendingValue(snapshot.pendingIntents || [], target, property), available);
        if (viewport) this._restoreViewport(viewport);
    }

    _updateControls(state, pending, available) {
        for (const [id, source] of menuLevelRows(state)) {
            const row = this._levelRows.get(id);
            if (!row) continue;
            const volume = pendingValue(pending, row._audioMatrixCommandTarget, 'volume');
            const muteTarget = row._audioMatrixMuteTarget || row._audioMatrixCommandTarget;
            const requestedMute = pendingValue(pending, muteTarget, 'muted');
            const muted = requestedMute ?? (volume !== undefined ? false : Boolean(source.muted));
            const value = volume ?? source.volume ?? source.value;
            this._rows.updateLevelRow(row, {value, muted,
                displayValue: muted ? '0%' : Number.isFinite(value) ? `${Math.round(value * 100)}%` : '—'});
            this._rows.setAvailable(row, available);
        }
        for (const pin of allQuickPins(state)) {
            const control = pin.routeControl;
            if (!control) continue;
            const selected = new Set(Array.isArray(control.value) ? control.value.map(String) : []);
            for (const option of control.options || []) {
                const target = {routeId: String(control.routeId),
                    switchNodeId: String(control.switchNodeId || control.nodeId), linkId: String(option.id)};
                const intent = pendingValue(pending, target, 'enabled');
                if (intent === true) selected.add(String(option.id));
                else if (intent === false) selected.delete(String(option.id));
            }
            const reference = this._linkRows.get(String(pin.id));
            this._links.update(reference, {...control, value: [...selected]});
            this._links.setAvailable(reference, available);
        }
    }

    _restoreViewport(viewport) {
        if (this._viewportLater) global.compositor.get_laters().remove(this._viewportLater);
        // New actors have no valid coordinates until Shell completes allocation.
        this._viewportLater = global.compositor.get_laters().add(Meta.LaterType.BEFORE_REDRAW, () => {
            this._viewportLater = 0;
            if (this._column) restoreMenuViewport(this._column.actor, viewport, this._groups.visibleRows(),
                actor => global.stage?.set_key_focus?.(actor));
            return false;
        });
    }

    _createShell(snapshot) {
        this._section.actor.add_style_class_name('audio-matrix-panel');
        this._section.actor.x_expand = true;
        this._header = this._rows.addHeader(snapshot.state, snapshot.available);
        this._errorStatus = this._rows.addStatus('', 'dialog-warning-symbolic');
        this._connectionStatus = this._rows.addStatus('', 'dialog-information-symbolic');
        this._column = createScrollableGroups(this._section);
    }

    destroy() {
        if (this._viewportLater) global.compositor.get_laters().remove(this._viewportLater);
        this._viewportLater = 0;
        this._applications.destroy();
        this._pinnedControls.destroy();
        this._disclosures.destroy();
        this._levelRows.clear();
        this._linkRows.clear();
        this._column = null;
        this._section = null;
    }
}
