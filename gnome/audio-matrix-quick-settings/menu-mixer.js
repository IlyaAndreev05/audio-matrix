import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

export default class AudioMatrixMenuMixer {
    constructor({addLevelRow, send}) {
        this._addLevelRow = addLevelRow;
        this._send = send;
    }

    render(routeId, nodeId, inputs, t, target) {
        const ordered = [...(inputs || [])].sort((left, right) => {
            const rank = id => id === 'events' ? 0 : id === 'bass' ? 1 : 2;
            return rank(left.id) - rank(right.id);
        });
        for (const input of ordered) {
            const label = input.id === 'bass' ? t.bassMusic
                : input.id === 'events' ? t.events : input.id === 'additional' ? t.additionalInput
                    : input.name || input.id;
            this._addLevelRow({
                id: this.rowId(nodeId, input.id), name: label,
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
