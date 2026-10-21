import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

function targetFor(pin, graphNodeId, fallbackRouteId) {
    const instanceKey = String(pin.instanceKey || '');
    return {
        routeId: String(pin.routeId || fallbackRouteId || ''),
        nodeId: instanceKey ? String(pin.nodeId) : String(graphNodeId),
        instanceKey,
    };
}
export default class AudioMatrixMenuOutput {
    constructor({addLevelRow, outputIcon, send}) {
        this._addLevelRow = addLevelRow;
        this._outputIcon = outputIcon;
        this._send = send;
    }

    render(pin, t, target) {
        if (pin.sharedGain)
            this._renderSharedGain(pin, pin.sharedGain, t, target);
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
            target.addMenuItem(new PopupMenu.PopupMenuItem(`${name || t.outputUnavailable} — ${label}`,
                {reactive: false, can_focus: false}));
            return;
        }
        if (!output.graphGainNodeId) {
            // The route exposes no controllable Gain; show the bound device only.
            target.addMenuItem(new PopupMenu.PopupImageMenuItem(name || t.outputUnavailable,
                this._outputIcon(output), {reactive: false, can_focus: false}));
            return;
        }
        const commandTarget = targetFor(pin, output.graphGainNodeId);
        this._addLevelRow({
            id: 'graph-output:' + pin.nodeId, name: output.description || output.name,
            icon: this._outputIcon(output), value: output.volume, muted: output.muted,
            mutedLabel: output.muted ? t.unmute : t.mute, commandTarget,
            onValue: value => this._send('setNodeVolume', {
                target: commandTarget, value: Number(value.toFixed(3)),
            }),
            onMute: () => this._send('setNodeMuted', {
                target: commandTarget, muted: !Boolean(output.muted),
            }),
        }, target);
    }

    _renderSharedGain(pin, sharedGain, t, target) {
        const commandTarget = {routeId: String(sharedGain.routeId), nodeId: String(sharedGain.nodeId),
            instanceKey: ''};
        this._addLevelRow({
            id: 'shared-gain:' + commandTarget.nodeId, name: t.vibroOverall || 'Master',
            icon: this._outputIcon({name: t.vibroOverall || ''}), value: sharedGain.volume,
            muted: Boolean(sharedGain.muted), mutedLabel: sharedGain.muted ? t.unmute : t.mute,
            commandTarget,
            onValue: value => this._send('setNodeVolume', {
                target: commandTarget, value: Number(value.toFixed(3)),
            }),
            onMute: () => this._send('setNodeMuted', {
                target: commandTarget, muted: !Boolean(sharedGain.muted),
            }),
        }, target);
    }

    _renderGain(pin, gain, t, target) {
        if (pin.available === false && pin.showWhenOffline) {
            target.addMenuItem(new PopupMenu.PopupMenuItem(
                `${pin.name || gain.nodeId} — ${Math.round(Number(gain.volume) * 100)}% — ${t.outputUnavailable}`,
                {reactive: false, can_focus: false}));
            return;
        }
        const commandTarget = targetFor(pin, gain.nodeId, gain.routeId);
        this._addLevelRow({
            id: 'graph-gain:' + pin.nodeId, name: pin.name || gain.nodeId,
            icon: this._outputIcon({name: pin.name || ''}), value: gain.volume,
            muted: Boolean(gain.muted), mutedLabel: gain.muted ? t.unmute : t.mute,
            commandTarget,
            onValue: value => this._send('setNodeVolume', {
                target: commandTarget, value: Number(value.toFixed(3)),
            }),
            onMute: () => this._send('setNodeMuted', {
                target: commandTarget, muted: !Boolean(gain.muted),
            }),
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
            const commandTarget = targetFor(pin, pin.nodeId);
            this._addLevelRow({
                id: `device:${pin.nodeId}`, name: boundName || configuredName,
                icon: this._outputIcon({name: boundName || configuredName}), value: slot.volume,
                muted: Boolean(slot.muted), mutedLabel: slot.muted ? t.unmute : t.mute, commandTarget,
                onValue: value => this._send('setNodeVolume', {
                    target: commandTarget, value: Number(value.toFixed(3)),
                }),
                onMute: () => this._send('setNodeMuted', {
                    target: commandTarget, muted: !Boolean(slot.muted),
                }),
            }, target);
            return;
        }
        // Device Slot exposes no graph Gain and no on/off parameter: report the
        // real binding state instead of fabricating controls.
        target.addMenuItem(new PopupMenu.PopupImageMenuItem(`${name || t.outputUnavailable} — ${label}`,
            this._outputIcon({name: name || ''}), {reactive: false, can_focus: false}));
    }
}
