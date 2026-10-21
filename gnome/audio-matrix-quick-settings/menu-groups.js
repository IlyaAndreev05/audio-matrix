import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import {orderedQuickGroups} from './menu-state.js';
import {removeMenuItem, reorderMenuItems} from './menu-row-utils.js';

function detailsSignature(pin) {
    return JSON.stringify([
        pin.nodeType, pin.name, pin.available, pin.showWhenOffline,
        pin.mixerInputs?.map(input => [input.id, input.name]) || [],
        pin.routeControl && [pin.routeControl.id, pin.routeControl.nodeId, pin.routeControl.label,
            pin.routeControl.options?.map(option => [option.id, option.label]) || []],
        pin.gain && [pin.gain.routeId, pin.gain.nodeId],
        pin.output && [pin.output.id, pin.output.name, pin.output.state, pin.output.available,
            pin.output.graphGainNodeId],
        pin.controls?.map(control => [control.id, control.parameterId, control.label, control.kind,
            control.options?.map(option => [option.id, option.label]) || []]) || [],
    ]);
}

function controlsSignature(controls) {
    return JSON.stringify(controls.map(control => [control.id, control.parameterId, control.label, control.kind,
        control.nodeType, control.minimum, control.maximum, control.scale,
        control.options?.map(option => [option.id, option.label]) || []]));
}

function setHeader(header, label, open) {
    header.label.text = label;
    header.setIcon(open ? 'pan-down-symbolic' : 'pan-end-symbolic');
    if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
    else header.remove_accessible_state(Atk.StateType.EXPANDED);
}

function displayPinName(pin, t) {
    const aliases = {
        'octine-main-source': ['Все приложения (без EVENT)', 'Все приложения (без событий)'],
        'octine-main-mixer': ['Основной микшер', 'Main Mixer'],
        'octine-vibro-gain': ['Общая сила вибродинамика', 'Vibro Gain / Mute', 'Vibro Gain'],
        'octine-vibro-limiter': ['Ограничитель', 'Vibro Limiter'],
        'octine-vibro-output': ['Устройство вибродинамика', 'Vibro Output · назначьте устройство'],
        'octine-event-source': ['Звуки событий', 'Event Sound Studio'],
    };
    if (!(aliases[pin.nodeId] || []).includes(pin.name)) return pin.name || pin.nodeId;
    return ({
        'octine-main-source': t.allApplications,
        'octine-main-mixer': t.mainMixer,
        'octine-vibro-gain': t.vibroOverall,
        'octine-vibro-limiter': t.vibroLimiter,
        'octine-vibro-output': t.vibroOutput,
        'octine-event-source': t.events,
    })[pin.nodeId];
}

/** Maintains keyed group and pin actors inside the single scroll column. */
export default class AudioMatrixMenuGroups {
    constructor({send, disclosures, applications, links, mixer, output, renderControls, registerLinkRow}) {
        this._send = send;
        this._disclosures = disclosures;
        this._applications = applications;
        this._links = links;
        this._mixer = mixer;
        this._output = output;
        this._renderControls = renderControls;
        this._registerLinkRow = registerLinkRow;
        this._column = null;
        this._groupEntries = new Map();
        this._groupOrder = [];
        this._pinEntries = new Map();
    }

    render(column, state, pins, t) {
        this._column = column;
        const controls = state.quickControls || [];
        const visibleGroups = orderedQuickGroups(state).map(group => {
            const id = String(group.id || group.name);
            const entries = pins.filter(pin => String(pin.groupId) === id)
                .sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
            const attached = new Set(entries.flatMap(pin => (pin.controls || []).map(control => String(control.id))));
            const unpinnedControls = controls.filter(control => String(control.groupId) === id
                && !attached.has(String(control.id)));
            return {group, id, entries, controls: unpinnedControls};
        }).filter(item => item.entries.length > 0 || item.controls.length > 0);

        const activeGroups = new Set(visibleGroups.map(item => item.id));
        for (const [id, entry] of this._groupEntries) {
            if (activeGroups.has(id)) continue;
            for (const pinEntry of entry.pins.values()) this._pinEntries.delete(pinEntry.id);
            entry.pins.clear();
        }

        const desiredGroups = [];
        const activePins = new Set();
        for (const item of visibleGroups) {
            const entry = this._groupEntries.get(item.id) || this._createGroup(item.id);
            entry.group = item.group;
            this._renderGroup(entry, item, t, activePins);
            desiredGroups.push(entry.wrapper);
        }
        for (const [id, entry] of this._pinEntries) {
            if (activePins.has(id)) continue;
            const parent = this._groupEntries.get(entry.groupId);
            if (parent) {
                removeMenuItem(parent.content, entry.wrapper);
                parent.pinOrder = parent.pinOrder.filter(item => item !== entry.wrapper);
            }
            this._pinEntries.delete(id);
        }
        for (const [id, entry] of this._groupEntries) {
            if (!activeGroups.has(id)) this._groupEntries.delete(id);
            else entry.pinOrder = entry.pinOrder.filter(item => this._pinEntries.has(item._audioMatrixPinId));
        }
        reorderMenuItems(column, this._groupOrder, desiredGroups);
        this._groupOrder = desiredGroups;
    }

    _createGroup(id) {
        const wrapper = new PopupMenu.PopupMenuSection();
        wrapper._audioMatrixGroupId = id;
        const header = new PopupMenu.PopupImageMenuItem(id, 'pan-end-symbolic');
        header.actor.set_style('margin-top: 6px; font-weight: bold;');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        const content = new PopupMenu.PopupMenuSection();
        content.actor.set_style('margin-left: 12px; padding-left: 8px; border-left: 1px solid rgba(255, 255, 255, 0.18);');
        const controlSection = new PopupMenu.PopupMenuSection();
        wrapper.addMenuItem(header);
        wrapper.addMenuItem(content);
        content.addMenuItem(controlSection);
        const entry = {id, wrapper, header, content, controlSection, pins: new Map(), pinOrder: [], controlsSignature: ''};
        this._groupEntries.set(id, entry);
        header.connect('activate', () => {
            const nextOpen = this._disclosures.toggleGroup(id);
            this._send('set_quick_group_collapsed', `${id}\t${!nextOpen}`);
            header.stop_emission_by_name('activate');
        });
        return entry;
    }

    _renderGroup(entry, item, t, activePins) {
        const open = this._disclosures.groupOpen(item.group);
        setHeader(entry.header, String(item.group.name || item.id), open);
        entry.content.actor.visible = open;
        this._disclosures.trackGroup(item.id, entry.header, entry.content);

        const desiredPins = [];
        for (const pin of item.entries) {
            const id = String(pin.id);
            activePins.add(id);
            let pinEntry = this._pinEntries.get(id);
            if (!pinEntry) {
                pinEntry = this._createPin(pin);
                this._pinEntries.set(id, pinEntry);
            } else if (pinEntry.groupId !== item.id) {
                const previous = this._groupEntries.get(pinEntry.groupId);
                if (previous) {
                    removeMenuItem(previous.content, pinEntry.wrapper);
                    previous.pinOrder = previous.pinOrder.filter(row => row !== pinEntry.wrapper);
                    previous.pins.delete(id);
                }
            }
            pinEntry.groupId = item.id;
            pinEntry.pin = pin;
            pinEntry.wrapper._audioMatrixPinId = id;
            entry.pins.set(id, pinEntry);
            this._renderPin(pinEntry, pin, t);
            desiredPins.push(pinEntry.wrapper);
        }
        for (const [id, pinEntry] of entry.pins) {
            if (activePins.has(id)) continue;
            removeMenuItem(entry.content, pinEntry.wrapper);
            entry.pins.delete(id);
        }
        reorderMenuItems(entry.content, entry.pinOrder, desiredPins);
        entry.pinOrder = desiredPins;

        const signature = controlsSignature(item.controls);
        if (signature !== entry.controlsSignature) {
            entry.controlSection.removeAll();
            if (item.controls.length) this._renderControls(item.controls, t, entry.controlSection);
            entry.controlsSignature = signature;
        }
    }

    _createPin(pin) {
        const id = String(pin.id);
        const wrapper = new PopupMenu.PopupMenuSection();
        const header = new PopupMenu.PopupImageMenuItem(pin.name || pin.nodeId, 'pan-end-symbolic');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        const content = new PopupMenu.PopupMenuSection();
        const appSection = new PopupMenu.PopupMenuSection();
        const mixerSection = new PopupMenu.PopupMenuSection();
        const routeSection = new PopupMenu.PopupMenuSection();
        const outputSection = new PopupMenu.PopupMenuSection();
        const controlSection = new PopupMenu.PopupMenuSection();
        for (const section of [appSection, mixerSection, routeSection, outputSection, controlSection])
            content.addMenuItem(section);
        wrapper.addMenuItem(header);
        wrapper.addMenuItem(content);
        const entry = {id, wrapper, header, content, appSection, mixerSection, routeSection,
            outputSection, controlSection, groupId: '', pin: null, detailsSignature: ''};
        header.connect('activate', () => {
            const nextOpen = this._disclosures.togglePin(id);
            this._send('set_quick_pin_view', `${id}\t${nextOpen ? 'EXPANDED' : 'COMPACT'}`);
            header.stop_emission_by_name('activate');
        });
        return entry;
    }

    _renderPin(entry, pin, t) {
        const open = this._disclosures.pinOpen(pin);
        setHeader(entry.header, displayPinName(pin, t), open);
        entry.content.actor.visible = open;
        this._disclosures.trackPin(entry.id, entry.header, entry.content);
        if (pin.nodeType === 'source'
            || (pin.nodeType === 'dynamic_generator' && pin.registry !== 'outputs'))
            this._applications.render(pin, pin.applications || [], t, entry.appSection);

        const signature = detailsSignature(pin);
        if (signature === entry.detailsSignature) return;
        for (const section of [entry.mixerSection, entry.routeSection, entry.outputSection, entry.controlSection])
            section.removeAll();
        if (pin.nodeType === 'mixer')
            this._mixer.render(pin.routeId, pin.nodeId, pin.mixerInputs || [], t, entry.mixerSection);
        if (pin.routeControl) {
            entry.routeRef = this._links.render(pin.routeControl, entry.routeSection);
            this._registerLinkRow?.(String(pin.routeControl.id), entry.routeRef);
        } else {
            entry.routeRef = null;
        }
        if (pin.gain || pin.output || pin.nodeType === 'output' || pin.nodeType === 'device_slot')
            this._output.render(pin, t, entry.outputSection);
        this._renderControls(pin.controls || [], t, entry.controlSection);
        entry.detailsSignature = signature;
    }


    visibleRows() {
        const rows = [];
        for (const wrapper of this._groupOrder) {
            const group = this._groupEntries.get(wrapper._audioMatrixGroupId);
            if (!group) continue;
            rows.push({id: `group:${group.id}`, actor: group.header.actor || group.header});
            if (group.content.actor.visible === false) continue;
            for (const pinWrapper of group.pinOrder) {
                const pin = this._pinEntries.get(pinWrapper._audioMatrixPinId);
                if (!pin) continue;
                rows.push({id: `pin:${pin.id}`, actor: pin.header.actor || pin.header});
                if (pin.content.actor.visible === false) continue;
                rows.push(...this._applications.rowsForPin(pin.id));
                if (pin.routeRef) rows.push(...this._links.rowsFor(pin.routeRef));
            }
        }
        return rows;
    }
}
