import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import Clutter from 'gi://Clutter';
import St from 'gi://St';
import {orderedQuickGroups} from './menu-state.js';
import {removeMenuItem, reorderMenuItems} from './menu-row-utils.js';

function detailsSignature(pin) {
    return JSON.stringify([pin.nodeType, pin.name, pin.icon, pin.available, pin.showWhenOffline,
        (pin.controls || []).map(control => [control.id, control.kind, control.label, control.minimum,
            control.maximum, control.scale, (control.options || []).map(option => [option.id, option.label])]),
        (pin.mixerInputs || []).map(input => [input.id, input.name]),
        pin.gain && [pin.gain.routeId, pin.gain.nodeId],
        pin.output && [pin.output.id, pin.output.name, pin.output.description, pin.output.state,
            pin.output.available, pin.output.graphGainNodeId],
        pin.routeControl && [pin.routeControl.id, pin.routeControl.nodeId,
            (pin.routeControl.options || []).map(option => [option.id, option.label, option.icon])]]);
}

function groupIcon(id) {
    return ({applications: 'view-app-grid-symbolic', sources: 'view-app-grid-symbolic',
        outputs: 'audio-speakers-symbolic', routing: 'network-transmit-receive-symbolic',
        events: 'notifications-symbolic'})[id] || 'audio-x-generic-symbolic';
}

function renderable(pin) {
    if (pin.pinned === false || pin.available === false && !pin.showWhenOffline) return false;
    if (pin.registry === 'browser_tabs') return false;
    if (pin.registry === 'applications' && !pin.applications?.length && !pin.gain && !pin.sourceGain) return false;
    return Boolean(pin.id);
}

function visiblePinRows(pin, applicationsRenderer, links, rows) {
    const composite = pin.composite;
    if (composite) {
        rows.push({id: `pin:${pin.id}`, actor: composite.header});
        if (composite.content.actor.visible)
            for (const wrapper of composite.order)
                visiblePinRows(composite.entries.get(wrapper._audioMatrixPinId), applicationsRenderer, links, rows);
        return;
    }
    const applications = applicationsRenderer.rowsForPin(pin.id);
    if (applications.length) rows.push(...applications);
    else rows.push({id: `pin:${pin.id}`, actor: pin.wrapper.actor || pin.wrapper});
    if (pin.routeRef) rows.push(...links.rowsFor(pin.routeRef).map(row => ({...row, id: `pin:${pin.id}:${row.id}`})));
}

/** Only native user-facing audio cards; graph containers never acquire a row. */
export default class AudioMatrixMenuGroups {
    constructor({send, disclosures, applications, links, output, registerLinkRow, forgetLevelRows, renderExtra}) {
        this._send = send;
        this._disclosures = disclosures;
        this._applications = applications;
        this._links = links;
        this._output = output;
        this._registerLinkRow = registerLinkRow;
        this._forgetLevelRows = forgetLevelRows;
        this._renderExtra = renderExtra;
        this._groupEntries = new Map();
        this._groupOrder = [];
        this._pinEntries = new Map();
    }

    render(column, state, pins, t) {
        const desiredGroups = [];
        const activePins = new Set();
        const activeGroups = new Set();
        for (const group of orderedQuickGroups(state)) {
            const id = String(group.id || group.name);
            activeGroups.add(id);
            const entry = this._groupEntries.get(id) || this._createGroup(id);
            entry.header.label.text = String(group.name || id);
            entry.header.setIcon(this._disclosures.groupOpen(group) ? 'pan-up-symbolic' : 'pan-down-symbolic');
            entry.content.actor.visible = this._disclosures.groupOpen(group);
            this._disclosures.trackGroup(id, entry.header, entry.content);
            const desired = [];
            let entries = pins.filter(pin => String(pin.groupId) === id && renderable(pin))
                .sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
            for (const pin of entries) {
                const pinId = String(pin.id);
                activePins.add(pinId);
                let pinEntry = this._pinEntries.get(pinId);
                if (!pinEntry) {
                    pinEntry = {id: pinId, wrapper: new PopupMenu.PopupMenuSection(), signature: null};
                    this._pinEntries.set(pinId, pinEntry);
                }
                if (pinEntry.groupId && pinEntry.groupId !== id) {
                    const previous = this._groupEntries.get(pinEntry.groupId);
                    if (previous) {
                        removeMenuItem(previous.content, pinEntry.wrapper);
                        previous.pinOrder = previous.pinOrder.filter(item => item !== pinEntry.wrapper);
                    }
                }
                pinEntry.groupId = id;
                pinEntry.pin = pin;
                pinEntry.wrapper._audioMatrixPinId = pinId;
                this._renderPin(pinEntry, pin, t);
                desired.push(pinEntry.wrapper);
            }
            reorderMenuItems(entry.content, entry.pinOrder, desired);
            entry.pinOrder = desired;
            entry.empty.actor.visible = desired.length === 0;
            entry.empty.label.text = id === 'applications' || id === 'sources' ? t.noApplications
                : id === 'outputs' ? t.noOutputs : t.noPinnedItems;
            desiredGroups.push(entry.wrapper);
        }
        for (const [id, entry] of this._pinEntries) {
            if (activePins.has(id)) continue;
            this._forgetEntry(entry);
            this._pinEntries.delete(id);
        }
        reorderMenuItems(column, this._groupOrder, desiredGroups);
        for (const [id, entry] of this._groupEntries) {
            if (activeGroups.has(id)) continue;
            entry.wrapper.destroy?.();
            this._groupEntries.delete(id);
        }
        this._groupOrder = desiredGroups;
    }

    _createGroup(id) {
        const wrapper = new PopupMenu.PopupMenuSection();
        wrapper.actor.add_style_class_name('audio-matrix-group');
        wrapper._audioMatrixGroupId = id;
        const header = new PopupMenu.PopupBaseMenuItem({activate: false, can_focus: true});
        header.add_style_class_name('audio-matrix-group-header');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        header.add_child(new St.Icon({icon_name: groupIcon(id), style_class: 'audio-matrix-group-icon'}));
        header.label = new St.Label({text: '', x_expand: true, y_align: Clutter.ActorAlign.CENTER});
        header.label_actor = header.label;
        header.add_child(header.label);
        const chevron = new St.Icon({icon_name: 'pan-down-symbolic', style_class: 'audio-matrix-group-icon'});
        header.add_child(chevron);
        header.setIcon = icon => { chevron.icon_name = icon; };
        const content = new PopupMenu.PopupMenuSection();
        content.actor.add_style_class_name('audio-matrix-group-content');
        const empty = new PopupMenu.PopupMenuItem('', {reactive: false, can_focus: false});
        empty.add_style_class_name('audio-matrix-empty');
        content.addMenuItem(empty);
        wrapper.addMenuItem(header);
        wrapper.addMenuItem(content);
        const entry = {id, wrapper, header, content, empty, pinOrder: []};
        this._groupEntries.set(id, entry);
        header.connect('activate', () => {
            const open = this._disclosures.toggleGroup(id);
            this._send('set_quick_group_collapsed', `${id}\t${!open}`);
            header.stop_emission_by_name('activate');
        });
        return entry;
    }

    _renderPin(entry, pin, t) {
        if (pin.nodeType === 'composite') {
            this._renderComposite(entry, pin, t);
            return;
        }
        if (pin.applications?.length || pin.sourceGain
            || pin.registry === 'applications') {
            this._applications.render(pin, pin.applications || [], t, entry.wrapper);
            return;
        }
        const signature = detailsSignature(pin);
        if (signature === entry.signature) return;
        this._forgetLevelRows?.(entry.wrapper);
        entry.wrapper.removeAll();
        if (pin.routeControl) {
            entry.routeRef = this._links.render(pin.routeControl, entry.wrapper);
            this._registerLinkRow?.(String(pin.id), entry.routeRef);
        } else if (pin.gain || pin.sharedGain || pin.output) {
            this._output.render(pin, t, entry.wrapper);
        } else {
            this._renderExtra?.(pin, t, entry.wrapper);
        }
        entry.signature = signature;
    }

    _renderComposite(entry, pin, t) {
        if (!entry.composite) {
            entry.wrapper.removeAll();
            const header = new PopupMenu.PopupImageMenuItem(pin.name || '', 'pan-down-symbolic');
            header.add_accessible_state(Atk.StateType.EXPANDABLE);
            const content = new PopupMenu.PopupMenuSection();
            content.actor.add_style_class_name('audio-matrix-tabs');
            entry.wrapper.addMenuItem(header);
            entry.wrapper.addMenuItem(content);
            entry.composite = {header, content, entries: new Map(), order: []};
            header.connect('activate', () => {
                const open = this._disclosures.togglePin(String(pin.id));
                this._send('set_quick_pin_view', `${pin.id}\t${open ? 'EXPANDED' : 'COMPACT'}`);
                header.stop_emission_by_name('activate');
            });
        }
        const composite = entry.composite;
        composite.header.label.text = pin.name || '';
        this._disclosures.trackPin(String(pin.id), composite.header, composite.content, pin.showChildren !== false);
        composite.content.actor.visible = pin.showChildren !== false && this._disclosures.pinOpen(pin);
        const wanted = new Set();
        const order = [];
        for (const child of pin.children || []) {
            if (!renderable(child)) continue;
            const id = String(child.id);
            wanted.add(id);
            let nested = composite.entries.get(id);
            if (!nested) {
                nested = {id, wrapper: new PopupMenu.PopupMenuSection(), signature: null};
                nested.wrapper._audioMatrixPinId = id;
                composite.entries.set(id, nested);
            }
            this._renderPin(nested, child, t);
            order.push(nested.wrapper);
        }
        reorderMenuItems(composite.content, composite.order, order);
        for (const [id, nested] of composite.entries) {
            if (wanted.has(id)) continue;
            this._forgetEntry(nested);
            composite.entries.delete(id);
        }
        composite.order = order;
    }

    _forgetEntry(entry) {
        for (const child of entry.composite?.entries.values() || []) this._forgetEntry(child);
        this._applications.forgetPin?.(entry.id);
        this._forgetLevelRows?.(entry.wrapper);
        entry.wrapper.destroy?.();
    }


    visibleRows() {
        const rows = [];
        for (const wrapper of this._groupOrder) {
            const group = this._groupEntries.get(wrapper._audioMatrixGroupId);
            if (!group) continue;
            rows.push({id: `group:${group.id}`, actor: group.header.actor || group.header});
            if (!group.content.actor.visible) continue;
            for (const pinWrapper of group.pinOrder) {
                const pin = this._pinEntries.get(pinWrapper._audioMatrixPinId);
                if (!pin) continue;
                visiblePinRows(pin, this._applications, this._links, rows);
            }
        }
        return rows;
    }
}
