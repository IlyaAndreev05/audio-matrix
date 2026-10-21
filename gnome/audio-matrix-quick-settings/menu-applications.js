import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import AudioMatrixKeyedSection from './menu-keyed-section.js';
import {pinRowId, visibleApplicationStreams} from './menu-state.js';

export default class AudioMatrixMenuApplications {
    constructor({addLevelRow, rows, send}) {
        this._addLevelRow = addLevelRow;
        this._rows = rows;
        this._send = send;
        this._browserSections = new Map();
    }

    render(pin, applications, t, target) {
        const sourceGain = pin.sourceGain;
        if (sourceGain) {
            const sourceTarget = pin.instanceKey
                ? {routeId: pin.routeId, nodeId: pin.nodeId, instanceKey: pin.instanceKey}
                : null;
            this._addLevelRow({
                id: pinRowId('source', pin), key: pinRowId('source', pin), name: pin.displayName || pin.name,
                icon: applications.length ? this._rows.applicationIcon(applications[0]) : 'application-x-executable-symbolic',
                value: sourceGain.volume, muted: sourceGain.muted,
                mutedLabel: sourceGain.muted ? t.unmute : t.mute,
                onValue: value => sourceTarget
                    ? this._send('setNodeVolume', {target: sourceTarget, value})
                    : this._send('set_source_volume', [sourceGain.routeId, sourceGain.sourceNodeId,
                        sourceGain.gainNodeId, value.toFixed(3)].join('\t')),
                onMute: () => sourceTarget
                    ? this._send('setNodeMuted', {target: sourceTarget, muted: !Boolean(sourceGain.muted)})
                    : this._send('toggle_source_mute', [sourceGain.routeId, sourceGain.sourceNodeId,
                        sourceGain.gainNodeId].join('\t')),
            }, target);
        }
        if (applications.length === 0) {
            const empty = new PopupMenu.PopupMenuItem(t.noApplications, {reactive: false, can_focus: false});
            empty._audioMatrixKey = `applications-empty:${pin.id}`;
            target.addMenuItem(empty);
            return;
        }
        for (const application of applications) {
            const browserReason = application.browserAdapter?.reason;
            if (browserReason) {
                const reason = new PopupMenu.PopupMenuItem(browserReason,
                    {reactive: false, can_focus: false});
                reason._audioMatrixKey = `browser-reason:${application.id}`;
                reason._audioMatrixKind = 'browser-reason';
                reason._audioMatrixPatch = incoming => {
                    reason.label.text = incoming.label.text;
                };
                target.addMenuItem(reason);
            }
            const streams = visibleApplicationStreams(application, pin);
            const targetAddress = application.instanceKey
                ? {routeId: pin.routeId, nodeId: pin.nodeId, instanceKey: application.instanceKey}
                : application.target || null;
            if (!sourceGain || streams.length === 0 || targetAddress)
                this._renderApplicationLevel(application, pin, t, target);
            if (streams.some(stream => stream.sessionToken))
                this._renderBrowserSubmenu(application, pin, streams, t, target);
            else
                this._renderStreams(application, pin, t, target, streams);
        }
    }

    _renderApplicationLevel(application, pin, t, target) {
        const childTarget = application.instanceKey
            ? {routeId: pin.routeId, nodeId: pin.nodeId, instanceKey: application.instanceKey}
            : application.target || null;
        const disabled = application.available === false || application.volumeAvailable === false
            || Boolean(application.browserAdapter && !childTarget);
        const name = application.name || application.id;
        const displayName = application.disabledReason ? `${name} — ${application.disabledReason}` : name;
        this._addLevelRow({
            id: `app:${application.id}`, key: `app:${application.id}`, name: displayName,
            icon: this._rows.applicationIcon(application), value: application.volume,
            muted: application.muted, mutedLabel: application.muted ? t.unmute : t.mute, disabled,
            allowMuteButton: true,
            onValue: disabled ? undefined : value => childTarget
                ? this._send('setNodeVolume', {target: childTarget, value})
                : this._send('set_app_volume', `${application.id}\t${value.toFixed(3)}`),
            onMute: disabled ? undefined : () => childTarget
                ? this._send('setNodeMuted', {target: childTarget, muted: !Boolean(application.muted)})
                : this._send('toggle_app_mute', application.id),
        }, target);
    }

    _renderBrowserSubmenu(application, pin, streams, t, target) {
        const key = `browser-app:${pin.routeId}:${pin.nodeId}:${pin.instanceKey || ''}:${application.id}`;
        let record = this._browserSections.get(key);
        if (!record) {
            const submenu = new PopupMenu.PopupSubMenuMenuItem(String(application.name || application.id), false);
            submenu._audioMatrixKey = key;
            submenu._audioMatrixKind = 'browser-application';
            record = {submenu, rows: new AudioMatrixKeyedSection(submenu.menu)};
            this._browserSections.set(key, record);
        }
        record.submenu.label.text = String(application.name || application.id);
        record.rows.begin();
        this._renderStreams(application, pin, t, record.rows, streams);
        record.rows.end();
        target.addMenuItem(record.submenu);
    }

    _renderStreams(application, pin, t, target, knownStreams = null) {
        const streams = knownStreams || visibleApplicationStreams(application, pin);
        for (const stream of streams) {
            if (stream.sessionToken) {
                const label = stream.title || stream.name || `Tab ${stream.tabId}`;
                const status = stream.muted ? t.muted : stream.audible ? t.audible : t.silent;
                const row = new PopupMenu.PopupMenuItem(`${label} — ${status}`,
                    {reactive: false, can_focus: false});
                row._audioMatrixKey = `browser-tab:${application.id}:${stream.sessionToken}`;
                row._audioMatrixKind = 'browser-tab';
                row._audioMatrixPatch = incoming => {
                    row.label.text = incoming.label.text;
                };
                target.addMenuItem(row);
                continue;
            }
            const targetAddress = stream.instanceKey
                ? {routeId: pin.routeId, nodeId: pin.nodeId, instanceKey: stream.instanceKey}
                : stream.target || null;
            this._addLevelRow({
                id: 'stream:' + stream.id, key: 'stream:' + stream.id,
                name: stream.description || stream.name,
                icon: 'audio-x-generic-symbolic', value: stream.volume, muted: stream.muted,
                mutedLabel: stream.muted ? t.unmute : t.mute,
                onValue: value => targetAddress
                    ? this._send('setNodeVolume', {target: targetAddress, value})
                    : this._send('set_stream_volume', stream.id + '\t' + value.toFixed(3)),
                onMute: () => targetAddress
                    ? this._send('setNodeMuted', {target: targetAddress, muted: !Boolean(stream.muted)})
                    : this._send('toggle_stream_mute', String(stream.id)),
            }, target);
        }
    }
}
