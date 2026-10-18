import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

export default class AudioMatrixMenuOutput {
    constructor({addLevelRow, outputIcon, send}) {
        this._addLevelRow = addLevelRow;
        this._outputIcon = outputIcon;
        this._send = send;
    }

    render(pin, t, target) {
        const output = pin.output;
        if (!output?.available) {
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.unavailable, {reactive: false, can_focus: false}));
            return;
        }
        this._addLevelRow({
            id: 'graph-output:' + pin.nodeId, name: output.description || output.name,
            icon: this._outputIcon(output), value: output.volume, muted: output.muted,
            mutedLabel: output.muted ? t.unmute : t.mute,
            onValue: value => this._send('set_output_volume', (output.id || output.name) + '\t' + value.toFixed(3)),
            onMute: () => this._send('toggle_output_mute', output.id || output.name),
        }, target);
    }
}
