import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import {visibleApplicationStreams} from './menu-state.js';

export default class AudioMatrixMenuApplications {
    constructor({addLevelRow, rows, send}) {
        this._addLevelRow = addLevelRow;
        this._rows = rows;
        this._send = send;
    }

    render(pin, applications, t, target) {
        const sourceGain = pin.sourceGain;
        if (sourceGain) {
            this._addLevelRow({
                id: `source:${pin.nodeId}`, name: pin.name,
                icon: applications.length ? this._rows.applicationIcon(applications[0]) : 'application-x-executable-symbolic',
                value: sourceGain.volume, muted: sourceGain.muted,
                mutedLabel: sourceGain.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_source_volume', [sourceGain.routeId, sourceGain.sourceNodeId,
                    sourceGain.gainNodeId, value.toFixed(3)].join('\t')),
                onMute: () => this._send('toggle_source_mute', [sourceGain.routeId, sourceGain.sourceNodeId,
                    sourceGain.gainNodeId].join('\t')),
            }, target);
        }
        if (applications.length === 0) {
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.noApplications, {reactive: false, can_focus: false}));
            return;
        }
        for (const application of applications) {
            const browserReason = application.browserAdapter?.reason;
            if (browserReason) {
                target.addMenuItem(new PopupMenu.PopupMenuItem(browserReason,
                    {reactive: false, can_focus: false}));
            }
            if (sourceGain) {
                if (applications.length === 1) {
                    this._renderStreams(application, pin, t, target);
                } else {
                    const submenu = new PopupMenu.PopupSubMenuMenuItem(
                        `${application.name || application.id}`, false);
                    this._renderStreams(application, pin, t, submenu.menu);
                    target.addMenuItem(submenu);
                }
            } else {
                this._addLevelRow({
                    id: `app:${application.id}`, name: application.name || application.id,
                    icon: this._rows.applicationIcon(application), value: application.volume,
                    muted: application.muted, mutedLabel: application.muted ? t.unmute : t.mute,
                    onValue: value => this._send('set_app_volume', application.id + '\t' + value.toFixed(3)),
                    onMute: () => this._send('toggle_app_mute', application.id),
                }, target);
                this._renderStreams(application, pin, t, target);
            }
        }
    }

    _renderStreams(application, pin, t, target) {
        const streams = visibleApplicationStreams(application, pin);
        if (streams.length === 0) return;
        for (const stream of streams) {
            this._addLevelRow({
                id: 'stream:' + stream.id, name: stream.description || stream.name,
                icon: 'audio-x-generic-symbolic', value: stream.volume, muted: stream.muted,
                mutedLabel: stream.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_stream_volume', stream.id + '\t' + value.toFixed(3)),
                onMute: () => this._send('toggle_stream_mute', String(stream.id)),
            }, target);
        }
    }
}
