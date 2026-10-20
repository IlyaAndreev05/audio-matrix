import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import {orderedQuickGroups} from './menu-state.js';

/** Builds the single vertical column of named, collapsible groups. */
export default class AudioMatrixMenuGroups {
    constructor({send, disclosures, applications, links, mixer, output, renderControls}) {
        this._send = send;
        this._disclosures = disclosures;
        this._applications = applications;
        this._links = links;
        this._mixer = mixer;
        this._output = output;
        this._renderControls = renderControls;
        this._column = null;
        this._detailsOpen = new Map();
    }

    render(column, state, pins, t) {
        this._column = column;
        const controls = state.quickControls || [];
        for (const group of orderedQuickGroups(state)) {
            const entries = pins.filter(pin => String(pin.groupId) === String(group.id || group.name));
            if (!entries.length && !controls.some(control => String(control.groupId) === String(group.id || group.name)))
                continue;
            this._renderGroup(group, entries, t);
        }
    }

    _renderGroup(group, entries, t) {
        const id = String(group.id || group.name);
        const open = this._disclosures.groupOpen(group);
        const header = new PopupMenu.PopupImageMenuItem(
            String(group.name || id), open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        header.actor.set_style('margin-top: 6px; font-weight: bold;');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
        const content = new PopupMenu.PopupMenuSection();
        content.actor.set_style('margin-left: 12px; padding-left: 8px; border-left: 1px solid rgba(255, 255, 255, 0.18);');
        entries.sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
        for (const pin of entries) this._renderPin(pin, t, content);
        this._column.addMenuItem(header);
        this._column.addMenuItem(content);
        content.actor.visible = open;
        this._disclosures.trackGroup(id, header, content);
        const toggle = () => {
            const nextOpen = this._disclosures.toggleGroup(id);
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
        const open = this._disclosures.pinOpen(pin);
        const header = new PopupMenu.PopupImageMenuItem(
            this._displayPinName(pin, t), open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
        const content = new PopupMenu.PopupMenuSection();
        if (pin.nodeType === 'source' || pin.nodeType === 'dynamic_generator')
            this._applications.render(pin, pin.applications || [], t, content);
        if (pin.nodeType === 'mixer')
            this._mixer.render(pin.routeId, pin.nodeId, pin.mixerInputs || [], t, content);
        if (pin.routeControl)
            this._links.render(pin.routeControl, content);
        if (pin.gain || pin.nodeType === 'output' || pin.nodeType === 'device_slot')
            this._output.render(pin, t, content);
        this._renderControls(pin.controls || [], t, content);
        target.addMenuItem(header);
        target.addMenuItem(content);
        content.actor.visible = open;
        this._disclosures.trackPin(id, header, content);
        const toggle = () => {
            const nextOpen = this._disclosures.togglePin(id);
            this._send('set_quick_pin_view', id + '\t' + (nextOpen ? 'EXPANDED' : 'COMPACT'));
        };
        header.activate = toggle;
        header.connect('activate', () => {
            toggle();
            header.stop_emission_by_name('activate');
        });
    }

    _displayPinName(pin, t) {
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

    destroy() {
        this._column = null;
        this._detailsOpen.clear();
        this._applications = null;
        this._links = null;
        this._mixer = null;
        this._output = null;
    }
}
