import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

export default class AudioMatrixMenuOutput {
    constructor({addLevelRow, outputIcon, send}) {
        this._addLevelRow = addLevelRow;
        this._outputIcon = outputIcon;
        this._send = send;
    }

    render(pin, t, target) {
        const output = pin.output;
        if (!output?.available || !output.graphGainNodeId) {
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.unavailable, {reactive: false, can_focus: false}));
            return;
        }
        const graphTarget = `${pin.routeId}\t${pin.nodeId}\t${output.graphGainNodeId}`;
        this._addLevelRow({
            id: 'graph-output:' + pin.nodeId, name: output.description || output.name,
            icon: this._outputIcon(output), value: output.volume, muted: output.muted,
            mutedLabel: output.muted ? t.unmute : t.mute,
            onValue: value => this._send('set_graph_output_volume', graphTarget + '\t' + value.toFixed(3)),
            onMute: () => this._send('toggle_graph_output_mute', graphTarget),
        }, target);
    }
}
