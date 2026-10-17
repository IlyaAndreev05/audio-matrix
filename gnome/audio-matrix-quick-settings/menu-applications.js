import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

export default class AudioMatrixMenuApplications {
    constructor({addLevelRow, rows, send}) {
        this._addLevelRow = addLevelRow;
        this._rows = rows;
        this._send = send;
    }

    render(applications, t, target) {
        if (applications.length === 0) {
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.noApplications, {reactive: false, can_focus: false}));
            return;
        }
        for (const application of applications) {
            this._addLevelRow({
                id: `app:${application.id}`, name: application.name || application.id,
                icon: this._rows.applicationIcon(application), value: application.volume,
                muted: application.muted, mutedLabel: application.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_app_volume', application.id + '\t' + value.toFixed(3)),
                onMute: () => this._send('toggle_app_mute', application.id),
            }, target);
            this._renderStreams(application, t, target);
        }
    }

    _renderStreams(application, t, target) {
        const streams = application.streams || [];
        if (streams.length === 0) return;
        const submenu = new PopupMenu.PopupSubMenuMenuItem(
            (application.name || t.streams) + ' (' + streams.length + ')', false);
        for (const stream of streams) {
            this._addLevelRow({
                id: 'stream:' + stream.id, name: stream.description || stream.name,
                icon: 'audio-x-generic-symbolic', value: stream.volume, muted: stream.muted,
                mutedLabel: stream.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_stream_volume', stream.id + '\t' + value.toFixed(3)),
                onMute: () => this._send('toggle_stream_mute', String(stream.id)),
            }, submenu.menu);
        }
        target.addMenuItem(submenu);
    }
}
