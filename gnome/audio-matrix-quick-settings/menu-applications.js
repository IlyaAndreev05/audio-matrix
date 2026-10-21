import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import {visibleApplicationStreams, visibleBrowserTabs} from './menu-state.js';
import {applicationIcon} from './menu-icons.js';
import {reorderMenuItems} from './menu-row-utils.js';

export default class AudioMatrixMenuApplications {
    constructor({rows, send, registerLevelRow, unregisterLevelRow}) {
        this._rows = rows;
        this._send = send;
        this._registerLevelRow = registerLevelRow;
        this._unregisterLevelRow = unregisterLevelRow;
        this._rendered = new Map();
    }

    render(pin, applications, t, target) {
        const pinId = String(pin.id || `${pin.routeId}:${pin.nodeId}:${pin.instanceKey || ''}`);
        const existing = this._rendered.get(pinId) || new Map();
        const descriptors = this._descriptors(pin, applications, t);
        const next = new Map();
        const ordered = [];
        for (const descriptor of descriptors) {
            let row = existing.get(descriptor.id);
            if (!row || row.kind !== descriptor.kind) {
                row = this._createRow(descriptor);
            } else if (descriptor.kind === 'level') {
                this._rows.updateLevelRow(row.item, descriptor.options);
            } else {
                const label = row.item.label;
                if (label?.text !== undefined) label.text = descriptor.label;
                row.item.set_sensitive?.(descriptor.reactive);
                row.item.reactive = descriptor.reactive;
                row.item.can_focus = descriptor.reactive;
            }
            row.item._audioMatrixRowId = descriptor.id;
            if (descriptor.kind === 'level') this._registerLevelRow?.(descriptor.id, row.item);
            next.set(descriptor.id, row);
            ordered.push(row.item);
        }
        for (const [id, row] of existing) {
            if (!next.has(id) && row.kind === 'level') this._unregisterLevelRow?.(id);
        }
        reorderMenuItems(target, [...existing.values()].map(row => row.item), ordered);
        this._rendered.set(pinId, next);
    }

    _descriptors(pin, applications, t) {
        const rows = [];
        const sourceGain = pin.sourceGain;
        const generatedChild = Boolean(pin.instanceKey);
        const tabs = pin.browserTabs || [];
        if (sourceGain) {
            rows.push({id: `source:${pin.nodeId}`, kind: 'level', options: {
                id: `source:${pin.nodeId}`, name: pin.name,
                icon: applications.length ? applicationIcon(applications[0]) : 'application-x-executable-symbolic',
                value: sourceGain.volume, muted: sourceGain.muted,
                mutedLabel: sourceGain.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_source_volume', [sourceGain.routeId, sourceGain.sourceNodeId,
                    sourceGain.gainNodeId, value.toFixed(3)].join('\t')),
                onMute: () => this._send('toggle_source_mute', [sourceGain.routeId, sourceGain.sourceNodeId,
                    sourceGain.gainNodeId].join('\t')),
            }});
        }
        const hasTabs = tabs.length > 0
            || applications.some(application => visibleBrowserTabs(application).length > 0);
        if (applications.length === 0 && !pin.gain && !hasTabs) {
            rows.push({id: `no-apps:${pin.id}`, kind: 'label', label: t.noApplications, reactive: false});
            return rows;
        }
        for (const application of applications) {
            if (application.browserAdapter?.reason)
                rows.push({id: `browser-reason:${application.id}`, kind: 'label',
                    label: application.browserAdapter.reason, reactive: false});
            if (!sourceGain && !generatedChild) {
                if (application.volumeAvailable === false || application.available === false) {
                    rows.push({id: `app:${application.id}`, kind: 'label',
                        label: `${application.name || application.id} — ${t.outputUnavailable}`, reactive: false});
                } else {
                    rows.push({id: `app:${application.id}`, kind: 'level', options: {
                        id: `app:${application.id}`, name: application.name || application.id,
                        icon: applicationIcon(application), value: application.volume,
                        muted: application.muted, mutedLabel: application.muted ? t.unmute : t.mute,
                        onValue: value => this._send('set_app_volume', application.id + '\t' + value.toFixed(3)),
                        onMute: () => this._send('toggle_app_mute', application.id),
                    }});
                }
            } else if (generatedChild && application.volumeAvailable === false) {
                rows.push({id: `app:${application.id}`, kind: 'label',
                    label: `${application.name || application.id} — ${t.outputUnavailable}`, reactive: false});
            }
            if (!generatedChild) for (const stream of visibleApplicationStreams(application)) {
                rows.push({id: `stream:${stream.id}`, kind: 'level', options: {
                    id: `stream:${stream.id}`, name: stream.description || stream.name,
                    icon: 'audio-x-generic-symbolic', value: stream.volume, muted: stream.muted,
                    mutedLabel: stream.muted ? t.unmute : t.mute,
                    onValue: value => this._send('set_stream_volume', stream.id + '\t' + value.toFixed(3)),
                    onMute: () => this._send('toggle_stream_mute', String(stream.id)),
                }});
            }
        }
        const browserTabs = tabs.length ? tabs : applications.flatMap(visibleBrowserTabs);
        for (const tab of browserTabs) {
            const key = [tab.browser, tab.instanceId, tab.profileId, tab.sessionToken].join(':');
            rows.push({id: `browser-tab:${key}`, kind: 'label',
                label: String(tab.title || tab.browser), reactive: false});
        }
        return rows;
    }

    _createRow(descriptor) {
        if (descriptor.kind === 'level') {
            const item = this._rows.createLevelRow(descriptor.options);
            return {kind: descriptor.kind, item};
        }
        const item = new PopupMenu.PopupMenuItem(descriptor.label, {
            reactive: descriptor.reactive, can_focus: descriptor.reactive,
        });
        return {kind: descriptor.kind, item};
    }

    rowsForPin(pinId) {
        const rows = this._rendered.get(String(pinId));
        if (!rows) return [];
        return [...rows].map(([id, row]) => ({id, actor: row.item.actor || row.item}));
    }

    destroy() {
        this._rendered.clear();
        this._rows = null;
        this._send = null;
        this._registerLevelRow = null;
        this._unregisterLevelRow = null;
    }
}
