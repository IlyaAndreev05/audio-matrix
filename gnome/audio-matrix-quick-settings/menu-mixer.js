import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

export default class AudioMatrixMenuMixer {
    constructor({addLevelRow, send}) {
        this._addLevelRow = addLevelRow;
        this._send = send;
    }

    render(routeId, nodeId, inputs, t, target) {
        for (const input of inputs || []) {
            this._addLevelRow({
                id: this.rowId(nodeId, input.id), name: input.name || input.id,
                icon: 'audio-x-generic-symbolic', value: input.volume, muted: input.muted,
                mutedLabel: input.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_mixer_input_volume',
                    routeId + '\t' + nodeId + '\t' + input.id + '\t' + value.toFixed(3)),
                onMute: () => this._send('toggle_mixer_input_mute', routeId + '\t' + nodeId + '\t' + input.id),
            }, target);
        }
        if (!(inputs || []).length)
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.noMixerInputs, {reactive: false, can_focus: false}));
    }

    rowId(nodeId, inputId) {
        return 'mixer:' + nodeId + ':' + inputId;
    }
}
