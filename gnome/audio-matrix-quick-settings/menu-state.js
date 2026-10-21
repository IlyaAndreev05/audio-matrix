export function canonicalTarget(target) {
    if (Array.isArray(target))
        return `[${target.map(canonicalTarget).join(',')}]`;
    if (target && typeof target === 'object')
        return `{${Object.keys(target).sort().map(key => `${JSON.stringify(key)}:${canonicalTarget(target[key])}`).join(',')}}`;
    return JSON.stringify(target);
}

export function intentKey(target, property) {
    return `${canonicalTarget(target)}\u0000${String(property)}`;
}

export function pendingValue(snapshot, target, property, fallback) {
    if (!target || !snapshot?.pending)
        return fallback;
    const intent = snapshot.pending.get(intentKey(target, property));
    return intent ? intent.desired : fallback;
}

export function menuStructureSignature(snapshot) {
    const state = snapshot.state || {};
    return JSON.stringify({
        available: snapshot.available, apiMismatch: snapshot.apiMismatch, language: snapshot.language,
        profiles: (state.profiles || []).map(({id, name}) => [id, name]),
        routes: (state.routes || []).map(({id, name}) => [id, name]),
        groups: orderedQuickGroups(state).map(({id, name, order}) => [id, name, order]),
        pins: (state.quickPins || []).map(pin => [
            pin.id, pin.routeId, pin.nodeId, pin.instanceKey || '', pin.name, pin.displayName, pin.icon,
            pin.nodeType, pin.groupId, pin.order, pin.pinned, pin.showChildren, pin.showWhenOffline,
            pin.tabAdapterAvailable,
            (pin.controls || []).map(control => [control.id, control.parameterId, control.label, control.kind,
                control.nodeType, control.multiple, (control.options || []).map(option => [option.id, option.label])]),
            (pin.applications || []).map(application => [application.id, application.name,
                application.browserAdapter?.reason,
                visibleApplicationStreams(application, pin).map(stream => [
                    stream.id, stream.sessionToken, stream.tabId, stream.title, stream.name, stream.description,
                    stream.volumeCapability, stream.captureStreamKey,
                ])]),
            (pin.mixerInputs || []).map(input => [input.id, input.name]),
            pin.gain ? [pin.gain.routeId, pin.gain.nodeId] : null,
            pin.output ? [pin.output.id, pin.output.name, pin.output.description,
                pin.output.available, pin.output.state, pin.output.graphGainNodeId] : null,
            pin.routeControl ? [pin.routeControl.id, pin.routeControl.routeId, pin.routeControl.nodeId,
                pin.routeControl.label, pin.routeControl.nodeType, pin.routeControl.multiple,
                (pin.routeControl.options || []).map(option => [option.id, option.label])] : null,
            pin.sourceGain ? [pin.sourceGain.routeId, pin.sourceGain.sourceNodeId, pin.sourceGain.gainNodeId] : null,
        ]),
        controls: (state.quickControls || []).map(control => [
            control.id, control.routeId, control.nodeId, control.parameterId, control.label, control.section,
            control.groupId, control.order, control.kind, control.multiple, control.nodeType,
            (control.options || []).map(option => [option.id, option.label]),
            control.minimum, control.maximum, control.scale, control.unit, control.precision,
        ]),
    });
}

export function hasAvailableMenuState(snapshot) {
    return Boolean(snapshot?.available && snapshot?.state);
}

export function pinRowId(kind, pin) {
    return `${kind}:${pin.routeId || ''}:${pin.nodeId || ''}:${pin.instanceKey || ''}`;
}

export function menuLevelRows(state) {
    const rows = new Map();
    for (const application of state.applications || []) {
        rows.set(`app:${application.id}`, {
            ...application,
            target: application.target || nodeTarget(application.routeId, application.nodeId, application.instanceKey),
        });
        for (const stream of application.streams || [])
            rows.set(`stream:${stream.id}`, stream);
    }
    for (const pin of state.quickPins || []) {
        const pinTarget = nodeTarget(pin.routeId, pin.nodeId, pin.instanceKey);
        for (const application of pin.applications || []) {
            const target = application.instanceKey
                ? nodeTarget(pin.routeId, pin.nodeId, application.instanceKey)
                : application.target || nodeTarget(application.routeId, application.nodeId, application.instanceKey);
            rows.set(`app:${application.id}`, {...application, target});
            for (const stream of application.streams || []) {
                const streamTarget = stream.instanceKey
                    ? nodeTarget(pin.routeId, pin.nodeId, stream.instanceKey)
                    : stream.target || null;
                rows.set(`stream:${stream.id}`, {...stream, target: streamTarget});
            }
        }
        if (pin.sourceGain)
            rows.set(pinRowId('source', pin), {...pin.sourceGain,
                target: pin.instanceKey ? pinTarget : nodeTarget(pin.sourceGain.routeId, pin.sourceGain.gainNodeId, '')});
        if (pin.gain)
            rows.set(pinRowId('graph-gain', pin), {...pin.gain, target: pinTarget});
        if (pin.output)
            rows.set(pinRowId('graph-output', pin), {...pin.output,
                target: pin.instanceKey ? pinTarget
                    : nodeTarget(pin.routeId, pin.output.graphGainNodeId, '')});
        for (const input of pin.mixerInputs || [])
            rows.set(`${pinRowId('mixer', pin)}:${input.id}`, input);
    }
    return rows;
}

function nodeTarget(routeId, nodeId, instanceKey = '') {
    if (!routeId || !nodeId)
        return null;
    return {routeId, nodeId, instanceKey: instanceKey || ''};
}

export function orderedQuickGroups(state) {
    return [...(state.quickGroups || [])].sort((left, right) =>
        Number(left.order || 0) - Number(right.order || 0) || String(left.id).localeCompare(String(right.id)));
}

export function visibleApplicationStreams(application, pin = {}) {
    if (pin.showChildren === false)
        return [];
    const identity = [application.id, application.application_id, application.name, application.process]
        .filter(Boolean).join(' ').toLowerCase();
    const tabs = application.tabs || application.browserAdapter?.tabs || [];
    if (/chrome|chromium/.test(identity))
        return tabs.filter(tab => typeof tab.sessionToken === 'string' && tab.sessionToken.length > 0
            && tab.tabId !== undefined && tab.tabId !== null);
    if (/firefox|mozilla/.test(identity))
        return [];
    return application.streams || [];
}
