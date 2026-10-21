import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Atk from 'gi://Atk';
import St from 'gi://St';
import AudioMatrixKeyedSection from './menu-keyed-section.js';
import {boxLayout} from './menu-layout.js';
import {orderedQuickGroups} from './menu-state.js';

/** Keeps group and pin actors alive while reconciling their keyed children. */
export default class AudioMatrixMenuGroups {
    constructor({send, disclosures, applications, links, mixer, output, renderControls, linkRows}) {
        this._send = send;
        this._disclosures = disclosures;
        this._applications = applications;
        this._links = links;
        this._mixer = mixer;
        this._output = output;
        this._renderControls = renderControls;
        this._linkRows = linkRows;
        this._column = null;
        this._groupRows = new Map();
        this._pinRows = new Map();
    }

    render(column, state, pins, t) {
        this._column = column;
        this._linkRows.clear();
        const controls = state.quickControls || [];
        const seenGroups = new Set();
        const seenPins = new Set();
        column.begin();
        for (const group of orderedQuickGroups(state)) {
            const id = String(group.id || group.name);
            const entries = pins.filter(pin => String(pin.groupId) === id)
                .filter(pin => pin.showWhenOffline === true
                    || pin.available !== false && pin.output?.available !== false);
            if (!entries.length && !controls.some(control => String(control.groupId) === id))
                continue;
            seenGroups.add(id);
            let record = this._groupRows.get(id);
            if (!record) {
                record = this._createGroup(id);
                this._groupRows.set(id, record);
            }
            this._renderGroup(record, group, entries, t, seenPins);
            column.addMenuItem(record.header);
            column.addMenuItem(record.content);
        }
        column.end();
        for (const id of this._groupRows.keys()) {
            if (seenGroups.has(id)) continue;
            this._groupRows.get(id)?.rows.clear();
            this._groupRows.delete(id);
        }
        for (const id of this._pinRows.keys()) {
            if (seenPins.has(id)) continue;
            this._pinRows.get(id)?.rows.clear();
            this._pinRows.delete(id);
        }
        this._disclosures.retain(state);
    }

    _createGroup(id) {
        const header = new PopupMenu.PopupImageMenuItem('', 'pan-end-symbolic');
        header._audioMatrixKey = `group:${id}:header`;
        header._audioMatrixKind = 'group-header';
        header.actor.set_style('margin-top: 6px; font-weight: bold;');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        const content = new PopupMenu.PopupMenuSection();
        content._audioMatrixKey = `group:${id}:content`;
        content._audioMatrixKind = 'group-content';
        content.actor.set_style('margin-left: 12px; padding-left: 8px; border-left: 1px solid rgba(255, 255, 255, 0.18);');
        const record = {id, header, content, rows: new AudioMatrixKeyedSection(content)};
        header.connect('activate', () => {
            const nextOpen = this._disclosures.toggleGroup(id);
            this._send('set_quick_group_collapsed', `${id}\t${!nextOpen}`);
            header.stop_emission_by_name('activate');
        });
        return record;
    }

    _renderGroup(record, group, entries, t, seenPins) {
        const open = this._disclosures.groupOpen(group);
        record.header.label.text = String(group.name || record.id);
        record.header.setIcon(open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        if (open) record.header.add_accessible_state(Atk.StateType.EXPANDED);
        else record.header.remove_accessible_state(Atk.StateType.EXPANDED);
        record.content.actor.visible = open;
        this._disclosures.trackGroup(record.id, record.header, record.content);
        record.rows.begin();
        for (const pin of [...entries].sort((left, right) =>
            Number(left.order || 0) - Number(right.order || 0) || String(left.id).localeCompare(String(right.id)))) {
            seenPins.add(String(pin.id));
            this._renderPin(pin, t, record.rows, record.id);
        }
        record.rows.end();
    }

    _createPin(id) {
        const header = new PopupMenu.PopupBaseMenuItem({reactive: true, can_focus: true});
        const row = boxLayout({spacing: 8, x_expand: true});
        const icon = new St.Icon({icon_name: 'audio-x-generic-symbolic', style_class: 'popup-menu-icon'});
        const label = new St.Label({text: '', x_expand: true});
        const arrow = new St.Icon({icon_name: 'pan-end-symbolic', style_class: 'popup-menu-icon'});
        row.add_child(icon);
        row.add_child(label);
        row.add_child(arrow);
        header.add_child(row);
        header.setIcon = iconName => { arrow.icon_name = iconName; };
        header._audioMatrixKey = `pin:${id}:header`;
        header._audioMatrixKind = 'pin-header';
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        const content = new PopupMenu.PopupMenuSection();
        content._audioMatrixKey = `pin:${id}:content`;
        content._audioMatrixKind = 'pin-content';
        const record = {id, header, icon, label, arrow, content,
            rows: new AudioMatrixKeyedSection(content), groupId: ''};
        header.connect('activate', () => {
            const nextOpen = this._disclosures.togglePin(id);
            this._send('set_quick_pin_view', `${id}\t${nextOpen ? 'EXPANDED' : 'COMPACT'}`);
            header.stop_emission_by_name('activate');
        });
        return record;
    }

    _renderPin(pin, t, target, groupId) {
        const id = String(pin.id);
        let record = this._pinRows.get(id);
        if (!record) {
            record = this._createPin(id);
            this._pinRows.set(id, record);
        }
        if (record.groupId && record.groupId !== groupId) {
            const previousGroup = this._groupRows.get(record.groupId);
            previousGroup?.rows.removeKey(`pin:${id}:header`);
            previousGroup?.rows.removeKey(`pin:${id}:content`);
        }
        record.groupId = groupId;
        record.label.text = this._displayPinName(pin, t);
        record.icon.icon_name = pin.icon || 'audio-x-generic-symbolic';
        record.header.accessible_name = record.label.text;
        record.header.setIcon(open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        if (open) record.header.add_accessible_state(Atk.StateType.EXPANDED);
        else record.header.remove_accessible_state(Atk.StateType.EXPANDED);
        record.content.actor.visible = open;
        this._disclosures.trackPin(id, record.header, record.content);

        record.rows.begin();
        if ((pin.nodeType === 'source' || pin.nodeType === 'dynamic_generator')
            && !(pin.instanceKey && pin.gain && pin.registry !== 'browser_tabs'))
            this._applications.render(pin, pin.applications || [], t, record.rows);
        if (pin.nodeType === 'mixer')
            this._mixer.render(pin.routeId, pin.nodeId, pin.instanceKey || '', pin.mixerInputs || [], t, record.rows);
        if (pin.routeControl) {
            const routeRow = this._links.render(pin.routeControl, record.rows, t);
            this._linkRows.set(String(pin.routeControl.id), routeRow);
        }
        if (pin.gain || pin.nodeType === 'output' || pin.nodeType === 'device_slot')
            this._output.render(pin, t, record.rows);
        this._renderControls(pin.controls || [], t, record.rows);
        record.rows.end();
        target.addMenuItem(record.header);
        target.addMenuItem(record.content);
    }

    _displayPinName(pin, t) {
        if (pin.displayName)
            return String(pin.displayName);
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
        this._groupRows.clear();
        this._pinRows.clear();
        this._applications = null;
        this._links = null;
        this._mixer = null;
        this._output = null;
    }
}
