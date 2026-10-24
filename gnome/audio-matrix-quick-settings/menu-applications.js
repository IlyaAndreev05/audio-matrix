import {visibleBrowserTabs} from './menu-state.js';
import {applicationIcon} from './menu-icons.js';
import {reorderMenuItems} from './menu-row-utils.js';

function nodeTarget(pin, gain) {
    return {routeId: String(gain.routeId || pin.routeId || ''),
        nodeId: String(pin.instanceKey ? pin.nodeId : gain.nodeId || gain.gainNodeId),
        instanceKey: String(pin.instanceKey || '')};
}

function tabKey(tab) {
    return [tab.browser, tab.instanceId, tab.profileId, tab.sessionToken].map(String).join(':');
}

export default class AudioMatrixMenuApplications {
    constructor({rows, send, registerLevelRow, unregisterLevelRow}) {
        this._rows = rows;
        this._send = send;
        this._registerLevelRow = registerLevelRow;
        this._unregisterLevelRow = unregisterLevelRow;
        this._rendered = new Map();
        this._views = new Map();
    }

    render(pin, applications, t, target) {
        const pinId = String(pin.id);
        const saved = pin.expanded ?? pin.view !== 'COMPACT';
        let view = this._views.get(pinId);
        if (!view) {
            view = {open: false, saved};
            this._views.set(pinId, view);
        } else if (view.saved !== saved) {
            view.saved = saved;
            view.open = saved;
        }
        const existing = this._rendered.get(pinId) || new Map();
        const descriptors = this._descriptors(pin, applications, t);
        const next = new Map();
        const ordered = [];
        for (const descriptor of descriptors) {
            let entry = existing.get(descriptor.id);
            if (!entry) entry = {item: this._rows.createLevelRow(descriptor.options), children: new Map()};
            entry.expanded = view.open;
            const options = {...descriptor.options, hasChildren: descriptor.tabs.length > 0 && pin.showChildren !== false,
                expanded: view.open, onExpand: () => {
                    view.open = !view.open;
                    entry.expanded = view.open;
                    this._rows.updateLevelRow(entry.item, {expanded: view.open});
                    this._send('set_quick_pin_view', `${pinId}\t${view.open ? 'EXPANDED' : 'COMPACT'}`);
                }};
            this._rows.updateLevelRow(entry.item, options);
            this._register(descriptor.id, entry.item);
            this._renderTabs(entry, options.hasChildren ? descriptor.tabs : [], t);
            ordered.push(entry.item);
            next.set(descriptor.id, entry);
        }
        for (const [id, entry] of existing) {
            if (next.has(id)) continue;
            this._unregisterLevelRow?.(id);
            for (const childId of entry.children.keys()) this._unregisterLevelRow?.(childId);
        }
        reorderMenuItems(target, [...existing.values()].map(entry => entry.item), ordered);
        for (const [id, entry] of existing)
            if (!next.has(id)) entry.item.destroy?.();
        this._rendered.set(pinId, next);
    }

    _register(id, item) {
        item._audioMatrixRowId = id;
        this._registerLevelRow?.(id, item);
    }

    _descriptors(pin, applications, t) {
        const gain = pin.sourceGain || pin.gain;
        if (gain) {
            const id = `pin-level:${pin.id}`;
            const application = applications[0];
            const commandTarget = nodeTarget(pin, gain);
            return [{id, options: {
                id, name: pin.name || application?.name || '',
                icon: pin.icon || (application ? applicationIcon(application) : 'application-x-executable-symbolic'),
                value: gain.volume, muted: gain.muted, commandTarget,
                available: pin.available !== false,
                subtitle: pin.available === false ? t.disconnected : '',
                onValue: value => this._send('setNodeVolume', {target: commandTarget, value: Number(value.toFixed(3))}),
                onMute: muted => this._send('setNodeMuted', {target: commandTarget, muted}),
            }, tabs: pin.browserTabs?.length ? visibleBrowserTabs({browserTabs: pin.browserTabs})
                : applications.flatMap(visibleBrowserTabs)}];
        }
        return applications.map(application => {
            const id = `app:${pin.id}:${application.id}`;
            const commandTarget = application.commandTarget || {applicationId: String(application.id)};
            const available = application.available !== false;
            return {id, options: {
                id, name: application.name || '', icon: pin.icon || applicationIcon(application),
                value: application.volume, muted: application.muted, commandTarget, available,
                subtitle: available ? '' : t.disconnected,
                capabilityHint: application.volumeAvailable === false ? t.applicationVolumeUnavailable : '',
                onValue: application.volumeAvailable === false ? null : value => {
                    if (application.commandTarget)
                        this._send('setNodeVolume', {target: commandTarget, value: Number(value.toFixed(3))});
                    else this._send('set_app_volume', `${application.id}\t${value.toFixed(3)}`);
                },
                onMute: application.muteAvailable === false ? null : muted => {
                    if (application.commandTarget) this._send('setNodeMuted', {target: commandTarget, muted});
                    else this._send('toggle_app_mute', String(application.id));
                },
            }, tabs: visibleBrowserTabs(application)};
        });
    }

    _renderTabs(parent, tabs, t) {
        const target = this._rows.childSection(parent.item);
        if (!target) return;
        const next = new Map();
        const ordered = [];
        for (const tab of tabs) {
            const id = `browser-tab:${parent.item._audioMatrixRowId}:${tabKey(tab)}`;
            const adapterTarget = {browser: tab.browser, instanceId: tab.instanceId,
                profileId: tab.profileId, sessionToken: tab.sessionToken};
            const commandTarget = tab.commandTarget || adapterTarget;
            const volumeAvailable = Boolean(tab.commandTarget) && tab.volumeCapability !== 'unsupported'
                && tab.volumeCapability !== 'mute_only' && tab.volumeAvailable !== false;
            const knownVolume = Number.isFinite(tab.volume);
            const options = {id, name: tab.title || tab.browser, icon: tab.icon || 'web-browser-symbolic', nested: true,
                value: knownVolume ? tab.volume : 0, muted: tab.muted, commandTarget,
                muteCommandTarget: adapterTarget,
                displayValue: tab.muted ? '0%' : knownVolume ? `${Math.round(tab.volume * 100)}%` : '—',
                available: tab.available !== false,
                capabilityHint: volumeAvailable ? '' : t.tabVolumeUnavailable,
                onValue: volumeAvailable ? value => {
                    if (tab.muted) this._send('setBrowserTabMuted', {target: adapterTarget, muted: false});
                    this._send('setNodeVolume', {target: tab.commandTarget, value: Number(value.toFixed(3))});
                } : null,
                onMute: tab.muteAvailable === false ? null : muted => this._send('setBrowserTabMuted', {
                    target: adapterTarget, muted,
                }),
            };
            let entry = parent.children.get(id);
            if (!entry) entry = {item: this._rows.createLevelRow(options)};
            this._rows.updateLevelRow(entry.item, options);
            this._register(id, entry.item);
            next.set(id, entry);
            ordered.push(entry.item);
        }
        reorderMenuItems(target, [...parent.children.values()].map(entry => entry.item), ordered);
        for (const [id, entry] of parent.children) {
            if (next.has(id)) continue;
            this._unregisterLevelRow?.(id);
            entry.item.destroy?.();
        }
        parent.children = next;
    }

    rowsForPin(pinId) {
        const rows = this._rendered.get(String(pinId));
        if (!rows) return [];
        return [...rows].flatMap(([id, row]) => [{id, actor: row.item.actor || row.item},
            ...(row.expanded ? [...row.children].map(([childId, child]) => ({id: childId,
                actor: child.item.actor || child.item})) : [])]);
    }

    forgetPin(pinId) {
        for (const [id, row] of this._rendered.get(String(pinId)) || []) {
            this._unregisterLevelRow?.(id);
            for (const childId of row.children.keys()) this._unregisterLevelRow?.(childId);
        }
        this._rendered.delete(String(pinId));
        this._views.delete(String(pinId));
    }

    destroy() {
        for (const id of this._rendered.keys()) this.forgetPin(id);
        this._views.clear();
        this._rows = null;
        this._send = null;
    }
}
