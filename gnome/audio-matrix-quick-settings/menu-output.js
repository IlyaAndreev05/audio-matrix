import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import {pinRowId} from './menu-state.js';

export default class AudioMatrixMenuOutput {
    constructor({addLevelRow, outputIcon, send}) {
        this._addLevelRow = addLevelRow;
        this._outputIcon = outputIcon;
        this._send = send;
    }

    render(pin, t, target) {
        if (pin.gain)
            return this._renderGain(pin, pin.gain, t, target);
        const output = pin.output;
        if (!output) {
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.outputUnavailable, {reactive: false, can_focus: false}));
            return;
        }
        if (pin.nodeType === 'device_slot')
            return this._renderSlot(pin, output, t, target);
        const state = String(output.state || (output.available ? 'ONLINE' : 'OFFLINE'));
        const name = output.description || output.name || pin.name;
        if (state !== 'ONLINE') {
            const label = t.deviceStates?.[state] || (state === 'OFFLINE' ? t.outputUnavailable : state);
            const row = new PopupMenu.PopupMenuItem(`${name || t.outputUnavailable} — ${label}`,
                {reactive: false, can_focus: false});
            row._audioMatrixKey = `output-state:${pinRowId('output', pin)}`;
            target.addMenuItem(row);
            return;
        }
        if (!output.graphGainNodeId) {
            const row = new PopupMenu.PopupImageMenuItem(name || t.outputUnavailable,
                this._outputIcon(output), {reactive: false, can_focus: false});
            row._audioMatrixKey = `output-device:${pinRowId('output', pin)}`;
            target.addMenuItem(row);
            return;
        }
        const commandTarget = graphTarget(pin, output.graphGainNodeId);
        this._addLevelRow({
            id: pinRowId('graph-output', pin), key: pinRowId('graph-output', pin),
            name: pin.displayName || output.description || output.name,
            icon: this._outputIcon(output), value: output.volume, muted: output.muted,
            mutedLabel: output.muted ? t.unmute : t.mute,
            onValue: value => this._send('setNodeVolume', {target: commandTarget, value}),
            onMute: () => this._send('setNodeMuted', {target: commandTarget, muted: !Boolean(output.muted)}),
        }, target);
    }

    _renderGain(pin, gain, t, target) {
        const commandTarget = graphTarget(pin, gain.nodeId);
        this._addLevelRow({
            id: pinRowId('graph-gain', pin), key: pinRowId('graph-gain', pin),
            name: pin.displayName || pin.name || gain.nodeId,
            icon: this._outputIcon({name: pin.displayName || pin.name || ''}), value: gain.volume,
            muted: Boolean(gain.muted), mutedLabel: gain.muted ? t.unmute : t.mute,
            onValue: value => this._send('setNodeVolume', {target: commandTarget, value}),
            onMute: () => this._send('setNodeMuted', {target: commandTarget, muted: !Boolean(gain.muted)}),
        }, target);
    }

    _renderSlot(pin, slot, t, target) {
        const state = String(slot.state || 'OFFLINE');
        const boundName = slot.description || slot.deviceName;
        const configuredName = pin.nodeId === 'octine-vibro-output'
            && ['Vibro Output · назначьте устройство', 'Устройство вибродинамика'].includes(slot.name)
            ? t.vibroOutput : (slot.name || pin.name || slot.id);
        const name = state !== 'UNBOUND' && boundName ? boundName : configuredName;
        const label = state === 'UNBOUND' ? t.assignDevice : (t.deviceStates?.[state] || state);
        if (state === 'ONLINE' && Number.isFinite(Number(slot.volume))) {
            const commandTarget = graphTarget(pin, pin.nodeId);
            this._addLevelRow({
                id: `device:${pin.routeId}:${pin.nodeId}:${pin.instanceKey || ''}`,
                name: pin.displayName || boundName || configuredName,
                icon: this._outputIcon({name: boundName || configuredName}), value: slot.volume, muted: Boolean(slot.muted),
                mutedLabel: slot.muted ? t.unmute : t.mute,
                onValue: value => this._send('setNodeVolume', {target: commandTarget, value}),
                onMute: () => this._send('setNodeMuted', {target: commandTarget, muted: !Boolean(slot.muted)}),
            }, target);
            return;
        }
        const row = new PopupMenu.PopupImageMenuItem(`${name || t.outputUnavailable} — ${label}`,
            this._outputIcon({name: name || ''}), {reactive: false, can_focus: false});
        row._audioMatrixKey = `device-state:${pin.routeId}:${pin.nodeId}:${pin.instanceKey || ''}`;
        target.addMenuItem(row);
    }
}

function graphTarget(pin, nodeId) {
    return {
        routeId: pin.routeId,
        nodeId: pin.instanceKey ? pin.nodeId : nodeId,
        instanceKey: pin.instanceKey || '',
    };
}
