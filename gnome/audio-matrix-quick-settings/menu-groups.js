import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as DND from 'resource:///org/gnome/shell/ui/dnd.js';
import Atk from 'gi://Atk';
import St from 'gi://St';
import {orderedQuickGroups} from './menu-state.js';

/** Builds the single vertical column of named, collapsible, reorderable groups. */
export default class AudioMatrixMenuGroups {
    constructor({getSnapshot, send, disclosures, rows, applications, mixer, output, renderControls}) {
        this._getSnapshot = getSnapshot;
        this._send = send;
        this._disclosures = disclosures;
        this._rows = rows;
        this._applications = applications;
        this._mixer = mixer;
        this._output = output;
        this._renderControls = renderControls;
        this._column = null;
    }

    render(column, state, pins, t) {
        this._column = column;
        for (const group of orderedQuickGroups(state))
            this._renderGroup(group, pins, t);
    }

    _renderGroup(group, pins, t) {
        const id = String(group.id || group.name);
        const open = this._disclosures.groupOpen(group);
        const header = new PopupMenu.PopupImageMenuItem(
            String(group.name || id), open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
        this._addGroupReordering(header, id, t);
        if (pins.some(pin => String(pin.groupId) === id && pin.pinned))
            header.add_child(new St.Icon({
                icon_name: 'view-pin-symbolic', style_class: 'popup-menu-icon', accessible_name: t.pinned,
            }));
        const content = new PopupMenu.PopupMenuSection();
        const entries = pins.filter(pin => String(pin.groupId) === id)
            .sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
        for (const pin of entries)
            this._renderPin(pin, t, content);
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
            String(pin.name || pin.nodeId), open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        header.add_accessible_state(Atk.StateType.EXPANDABLE);
        if (open) header.add_accessible_state(Atk.StateType.EXPANDED);
        header.add_child(new St.Icon({
            icon_name: 'view-pin-symbolic', style_class: 'popup-menu-icon', accessible_name: t.pinned,
        }));
        const content = new PopupMenu.PopupMenuSection();
        if (pin.nodeType === 'source')
            this._applications.render(pin, pin.applications || [], t, content);
        if (pin.nodeType === 'mixer')
            this._mixer.render(pin.routeId, pin.nodeId, pin.mixerInputs || [], t, content, pin.pinned);
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

    _addGroupReordering(header, id, t) {
        const groups = orderedQuickGroups(this._getSnapshot().state);
        const index = groups.findIndex(group => String(group.id || group.name) === id);
        const move = position => this._send('reorder_quick_group', `${id}\t${position}`);
        const handle = this._rows.iconButton('list-drag-handle-symbolic', t.reorder, () => {});
        const source = {
            quickGroupId: id,
            getDragActor: () => new St.Label({text: header.label.text, style_class: 'popup-menu-item'}),
            getDragActorSource: () => handle,
        };
        handle._delegate = source;
        DND.makeDraggable(handle);
        header.add_child(handle);
        header.handleDragOver = dragSource => dragSource.quickGroupId && dragSource.quickGroupId !== id
            ? DND.DragMotionResult.MOVE_DROP : DND.DragMotionResult.NO_DROP;
        header.acceptDrop = dragSource => {
            if (!dragSource.quickGroupId || dragSource.quickGroupId === id)
                return false;
            this._send('reorder_quick_group', `${dragSource.quickGroupId}\t${index}`);
            return true;
        };
        const up = this._rows.iconButton('go-up-symbolic', t.moveUp, () => move(index - 1));
        const down = this._rows.iconButton('go-down-symbolic', t.moveDown, () => move(index + 1));
        up.reactive = up.can_focus = index > 0;
        down.reactive = down.can_focus = index < groups.length - 1;
        header.add_child(up);
        header.add_child(down);
    }

    destroy() {
        this._column = null;
        this._applications = null;
        this._mixer = null;
        this._output = null;
    }
}
