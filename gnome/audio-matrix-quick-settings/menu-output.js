function targetFor(pin, graphNodeId, fallbackRouteId) {
    return {routeId: String(pin.routeId || fallbackRouteId || ''),
        nodeId: String(pin.instanceKey ? pin.nodeId : graphNodeId),
        instanceKey: String(pin.instanceKey || '')};
}

export default class AudioMatrixMenuOutput {
    constructor({addLevelRow, outputIcon, send}) {
        this._addLevelRow = addLevelRow;
        this._outputIcon = outputIcon;
        this._send = send;
    }

    render(pin, t, target) {
        const gain = pin.sharedGain || pin.gain;
        const output = pin.output;
        const source = gain || output;
        if (!source) return;
        const online = pin.available !== false && (output
            ? String(output.state || (output.available ? 'ONLINE' : 'OFFLINE')) === 'ONLINE' : true);
        const knownVolume = Number.isFinite(source.volume);
        const name = pin.name || output?.name || output?.description || '';
        const nodeId = gain?.nodeId || output?.graphGainNodeId || pin.nodeId;
        const commandTarget = pin.sharedGain
            ? {routeId: String(gain.routeId), nodeId: String(gain.nodeId), instanceKey: ''}
            : targetFor(pin, nodeId, gain?.routeId);
        const id = `pin-level:${pin.id}`;
        const controllable = knownVolume && Boolean(nodeId);
        return this._addLevelRow({
            id, name, icon: pin.icon || this._outputIcon(output || {name}),
            value: knownVolume ? source.volume : 0, muted: Boolean(source.muted),
            displayValue: source.muted ? '0%' : knownVolume ? `${Math.round(source.volume * 100)}%` : '—',
            subtitle: online ? '' : t.disconnected,
            available: online, commandTarget,
            capabilityHint: online && !controllable ? t.outputVolumeUnavailable : '',
            onValue: controllable ? value => this._send('setNodeVolume', {
                target: commandTarget, value: Number(value.toFixed(3)),
            }) : null,
            onMute: controllable ? muted => this._send('setNodeMuted', {target: commandTarget, muted}) : null,
        }, target);
    }
}
