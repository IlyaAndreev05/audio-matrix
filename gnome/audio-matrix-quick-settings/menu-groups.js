import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import {orderedQuickGroups} from './menu-state.js';

/** Builds the single vertical column of named, collapsible groups. */
export default class AudioMatrixMenuGroups {
    constructor({send, disclosures, applications, mixer, output, renderControls}) {
        this._send = send;
        this._disclosures = disclosures;
        this._applications = applications;
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
            const commonPins = pins.filter(pin => pin.routeId === 'octine-common-vibro');
            const vibroGroup = commonPins.some(pin => pin.nodeId === 'octine-vibro-mixer')
                && commonPins.find(pin => pin.nodeId === 'octine-vibro-mixer')?.groupId === String(group.id || group.name);
            const entries = vibroGroup
                ? commonPins
                : pins.filter(pin => pin.routeId !== 'octine-common-vibro'
                    && String(pin.groupId) === String(group.id || group.name));
            if (!entries.length && !controls.some(control => String(control.groupId) === String(group.id || group.name)))
                continue;
            this._renderGroup(group, entries, t, vibroGroup);
        }
    }

    _renderGroup(group, entries, t, vibroGroup = false) {
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
        if (vibroGroup) this._renderVibroGroup(entries, t, content);
        else for (const pin of entries) this._renderPin(pin, t, content);
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

    _renderVibroGroup(entries, t, target) {
        const mixerPin = entries.find(pin => pin.nodeId === 'octine-vibro-mixer');
        const gainPin = entries.find(pin => pin.nodeId === 'octine-vibro-gain');
        const detailPins = entries.filter(pin => !['octine-vibro-mixer', 'octine-vibro-gain'].includes(pin.nodeId))
            .sort((left, right) => {
                const rank = id => ({'octine-main-source': 0, 'octine-main-mixer': 1,
                    'octine-event-source': 2, 'octine-bass-low-pass': 3,
                    'octine-vibro-limiter': 4, 'octine-vibro-output': 5})[id] ?? 6;
                return rank(left.nodeId) - rank(right.nodeId);
            });
        if (mixerPin)
            this._mixer.render(mixerPin.routeId, mixerPin.nodeId, mixerPin.mixerInputs || [], t, target);
        if (gainPin) this._output.render({...gainPin, name: t.vibroOverall}, t, target);
        if (!detailPins.length) return;

        const id = 'details:vibro';
        const open = this._detailsOpen.get(id) ?? false;
        const header = new PopupMenu.PopupImageMenuItem(t.details,
            open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
        const content = new PopupMenu.PopupMenuSection();
        content.actor.set_style('margin-left: 16px; padding-left: 8px; border-left: 1px solid rgba(255, 255, 255, 0.18);');
        for (const pin of detailPins) {
            if (pin.nodeId === 'octine-bass-low-pass' && (pin.controls || []).length)
                this._renderControls(pin.controls, t, content);
            else this._renderPin(pin, t, content);
        }
        target.addMenuItem(header);
        target.addMenuItem(content);
        content.actor.visible = open;
        header.connect('activate', () => {
            const next = !this._detailsOpen.get(id);
            this._detailsOpen.set(id, next);
            content.actor.visible = next;
            header.setIcon(next ? 'pan-down-symbolic' : 'pan-end-symbolic');
            if (next) header.add_accessible_state(Atk.StateType.EXPANDED);
            else header.remove_accessible_state(Atk.StateType.EXPANDED);
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
        if (pin.nodeType === 'source')
            this._applications.render(pin, pin.applications || [], t, content);
        if (pin.nodeType === 'mixer')
            this._mixer.render(pin.routeId, pin.nodeId, pin.mixerInputs || [], t, content);
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
            'octine-main-mixer': ['Main Mixer'],
            'octine-vibro-mixer': ['Vibro Mixer'],
            'octine-vibro-gain': ['Vibro Gain / Mute'],
            'octine-vibro-limiter': ['Vibro Limiter'],
            'octine-vibro-output': ['Vibro Output · назначьте устройство'],
            'octine-bass-low-pass': ['Басы · 120 Hz', 'Басы · 60 Гц'],
            'octine-event-source': ['Event Sound Studio'],
        };
        if (!(aliases[pin.nodeId] || []).includes(pin.name)) return pin.name || pin.nodeId;
        return ({
            'octine-main-source': t.allApplications,
            'octine-main-mixer': t.mainMixer,
            'octine-vibro-mixer': t.vibroMixer,
            'octine-vibro-gain': t.vibroOverall,
            'octine-vibro-limiter': t.vibroLimiter,
            'octine-vibro-output': t.vibroOutput,
            'octine-bass-low-pass': t.bassMusic,
            'octine-event-source': t.events,
        })[pin.nodeId];
    }

    destroy() {
        this._column = null;
        this._detailsOpen.clear();
        this._applications = null;
        this._mixer = null;
        this._output = null;
    }
}
