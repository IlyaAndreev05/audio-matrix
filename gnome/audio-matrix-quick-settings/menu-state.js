export function menuStructureSignature(snapshot) {
    // Shared capability changes must rebuild stream rows, even with identical stream IDs.
    const state = snapshot.state || {};
    return JSON.stringify({
        available: snapshot.available, language: snapshot.language, commandError: snapshot.commandError,
        profile: state.profile, activeRoute: state.activeRoute, activeRouteId: state.activeRouteId,
        routes: (state.routes || []).map(({id, name}) => [id, name]),
        groups: (state.quickGroups || []).map(({id, name, order}) => [id, name, order]),
        pins: (state.quickPins || []).map(pin => [
            pin.id, pin.routeId, pin.nodeId, pin.name, pin.nodeType, pin.groupId, pin.order, pin.pinned,
            pin.tabAdapterAvailable,
            (pin.controls || []).map(control => [control.id, control.parameterId, control.label, control.kind,
                control.nodeType, control.multiple, (control.options || []).map(option => [option.id, option.label])]),
            (pin.applications || []).map(application => [application.id, application.name, application.tabAdapterAvailable,
                application.browserAdapter?.reason, application.browserAdapter?.independentTabVolume,
                (application.streams || []).map(stream => [stream.id, stream.name, stream.description])]),
            (pin.mixerInputs || []).map(input => [input.id, input.name, input.muted]),
            pin.gain ? [pin.gain.routeId, pin.gain.nodeId] : null,
            pin.output ? [pin.output.id, pin.output.name, pin.output.description,
                pin.output.available, pin.output.state, pin.output.graphGainNodeId] : null,
            pin.routeControl ? [pin.routeControl.id, pin.routeControl.nodeId, pin.routeControl.label,
                pin.routeControl.nodeType, pin.routeControl.multiple,
                (pin.routeControl.options || []).map(option => [option.id, option.label])] : null,
            pin.sourceGain ? [pin.sourceGain.routeId, pin.sourceGain.sourceNodeId, pin.sourceGain.gainNodeId] : null,
        ]),
        controls: (state.quickControls || []).map(control => [
            control.id, control.nodeId, control.parameterId, control.label, control.section,
            control.groupId, control.order, control.kind, control.multiple, control.nodeType,
            (control.options || []).map(option => [option.id, option.label]),
            control.minimum, control.maximum, control.scale, control.unit, control.precision,
        ]),
    });
}

export function hasAvailableMenuState(snapshot) {
    return Boolean(snapshot?.available && snapshot?.state);
}

export function menuLevelRows(state) {
    const rows = new Map();
    for (const application of state.applications || []) {
        rows.set(`app:${application.id}`, application);
        for (const stream of application.streams || [])
            rows.set(`stream:${stream.id}`, stream);
    }
    for (const pin of state.quickPins || []) {
        if (pin.sourceGain)
            rows.set(`source:${pin.nodeId}`, pin.sourceGain);
        if (pin.gain)
            rows.set(`graph-gain:${pin.nodeId}`, pin.gain);
        if (pin.output)
            rows.set(`graph-output:${pin.nodeId}`, pin.output);
        for (const input of pin.mixerInputs || [])
            rows.set(`mixer:${pin.nodeId}:${input.id}`, input);
    }
    return rows;
}

export function orderedQuickGroups(state) {
    return [...(state.quickGroups || [])].sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
}

export function visibleApplicationStreams(application, pin = {}) {
    const identity = [application.id, application.application_id, application.name, application.process]
        .filter(Boolean).join(' ').toLowerCase();
    if (/chrome|chromium/.test(identity) && (application.tabAdapterAvailable ?? pin.tabAdapterAvailable) !== true)
        return [];
    return application.streams || [];
}
