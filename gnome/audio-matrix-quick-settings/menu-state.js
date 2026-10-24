export function menuStructureSignature(snapshot) {
    const state = snapshot.state || {};
    return JSON.stringify({
        language: snapshot.language,
        profile: state.profile,
        activeProfileId: state.activeProfileId,
        profiles: (state.profiles || []).map(({id, name}) => [id, name]),
        activeRoute: state.activeRoute,
        activeRouteId: state.activeRouteId,
        routes: (state.routes || []).map(({id, name}) => [id, name]),
        groups: (state.quickGroups || []).map(({id, name, order}) => [id, name, order]),
        pins: (state.quickPins || []).map(pin => [
            pin.id, pin.routeId, pin.nodeId, pin.instanceKey, pin.name, pin.icon, pin.nodeType,
            pin.groupId, pin.order, pin.pinned, pin.showChildren, pin.showWhenOffline,
            pin.tabAdapterAvailable,
            (pin.controls || []).map(control => [control.id, control.parameterId, control.label, control.kind,
                control.nodeType, control.multiple, (control.options || []).map(option => [option.id, option.label])]),
            (pin.applications || []).map(application => [application.id, application.name, application.tabAdapterAvailable,
                application.browserAdapter?.reason, application.browserAdapter?.independentTabVolume,
                (application.streams || []).map(stream => [stream.id, stream.name, stream.description]),
                (application.browserTabs || []).map(tab => [tab.browser, tab.instanceId, tab.profileId,
                    tab.sessionToken, tab.title, tab.audible, tab.muted, tab.volumeCapability, tab.captureStreamKey])]),
            (pin.mixerInputs || []).map(input => [input.id, input.name]),
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
    const visit = pin => {
        const level = pin.sourceGain || pin.sharedGain || pin.gain || pin.output;
        const parentId = `pin-level:${pin.id}`;
        if (level) rows.set(parentId, level);
        for (const app of pin.applications || []) {
            const appId = level ? parentId : `app:${pin.id}:${app.id}`;
            if (!level) rows.set(appId, app);
            for (const tab of app.browserTabs || [])
                rows.set(`browser-tab:${appId}:${[tab.browser, tab.instanceId, tab.profileId, tab.sessionToken].map(String).join(':')}`, tab);
        }
        for (const input of pin.mixerInputs || []) rows.set(`mixer:${pin.id}:${input.id}`, input);
        for (const child of pin.children || []) visit(child);
    };
    for (const pin of state.quickPins || []) visit(pin);
    return rows;
}

export function orderedQuickGroups(state) {
    return [...(state.quickGroups || [])].sort((left, right) => Number(left.order || 0) - Number(right.order || 0));
}

export function visibleApplicationStreams(application) {
    return Array.isArray(application?.streams) ? application.streams : [];
}

export function visibleBrowserTabs(application) {
    return (application?.browserTabs || []).filter(tab =>
        typeof tab.browser === 'string' && tab.browser.length > 0
        && typeof tab.instanceId === 'string' && tab.instanceId.length > 0
        && typeof tab.profileId === 'string' && tab.profileId.length > 0
        && typeof tab.sessionToken === 'string' && tab.sessionToken.length > 0);
}

/** Archived generator rows keep recovering saved gains but never occupy the live mixer. */
export function visibleMixerInputs(inputs) {
    return (inputs || []).filter(input => !(input?.generated === true && input?.available === false));
}

export function visiblePinMixerInputs(pin) {
    return visibleMixerInputs(pin?.mixerInputs);
}

export function stableMenuRows(state) {
    const rows = [];
    for (const group of orderedQuickGroups(state)) {
        const groupId = String(group.id || group.name);
        rows.push({id: `group:${groupId}`, kind: 'group', groupId});
        for (const pin of (state.quickPins || []).filter(item => String(item.groupId) === groupId)) {
            rows.push({id: `pin:${pin.id}`, kind: 'pin', pinId: String(pin.id)});
            for (const application of pin.applications || []) {
                rows.push({id: `app:${application.id}`, kind: 'application', applicationId: String(application.id)});
                for (const stream of application.streams || [])
                    rows.push({id: `stream:${stream.id}`, kind: 'stream', streamId: String(stream.id)});
                for (const tab of application.browserTabs || []) {
                    const tabKey = [tab.browser, tab.instanceId, tab.profileId, tab.sessionToken].map(String).join(':');
                    rows.push({id: `browser-tab:${tabKey}`, kind: 'browser-tab', tabKey});
                }
            }
            if (pin.routeControl)
                for (const option of pin.routeControl.options || [])
                    rows.push({id: `route:${pin.routeControl.id}:${option.id}`, kind: 'route', linkId: String(option.id)});
        }
    }
    return rows;
}

export function* allQuickPins(state) {
    function* visit(pins) {
        for (const pin of pins || []) {
            yield pin;
            yield* visit(pin.children);
        }
    }
    yield* visit(state.quickPins);
}
