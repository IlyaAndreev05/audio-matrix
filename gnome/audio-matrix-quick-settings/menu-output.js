import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

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
        const graphTarget = `${pin.routeId}\t${pin.nodeId}\t${output.graphGainNodeId}`;
        this._addLevelRow({
            id: 'graph-output:' + pin.nodeId, name: output.description || output.name,
            icon: this._outputIcon(output), value: output.volume, muted: output.muted,
            mutedLabel: output.muted ? t.unmute : t.mute,
            onValue: value => this._send('set_graph_output_volume', graphTarget + '\t' + value.toFixed(3)),
            onMute: () => this._send('toggle_graph_output_mute', graphTarget),
        }, target);
    }

    _renderGain(pin, gain, t, target) {
        const graphTarget = `${gain.routeId}\t${gain.nodeId}`;
        this._addLevelRow({
            id: 'graph-gain:' + pin.nodeId, name: pin.name || gain.nodeId,
            icon: this._outputIcon({name: pin.name || ''}), value: gain.volume,
            muted: Boolean(gain.muted), mutedLabel: gain.muted ? t.unmute : t.mute,
            onValue: value => this._send('set_graph_gain', `${graphTarget}\t${value.toFixed(3)}`),
            onMute: () => this._send('toggle_graph_gain_mute', graphTarget),
        }, target);
    }

    _renderSlot(pin, slot, t, target) {
        const state = String(slot.state || 'OFFLINE');
        // The slot label is authoritative; deviceName/description identify the
        // hardware and must not rename the pin.
        const name = slot.name || pin.name || slot.id;
        const label = t.deviceStates?.[state] || state;
        // Device Slot exposes no graph Gain and no on/off parameter: report the
        // real binding state instead of fabricating controls.
        target.addMenuItem(new PopupMenu.PopupImageMenuItem(`${name || t.outputUnavailable} — ${label}`,
            this._outputIcon({name: name || ''}), {reactive: false, can_focus: false}));
    }
}
