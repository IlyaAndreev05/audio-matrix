import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

export default class AudioMatrixMenuMixer {
    constructor({rows, send, registerLevelRow}) {
        this._rows = rows;
        this._send = send;
        this._register = registerLevelRow;
    }

    render(routeId, nodeId, pinId, inputs, t, target) {
        for (const input of inputs || []) {
            const id = `mixer:${pinId}:${input.id}`;
            const commandTarget = {routeId: String(routeId), nodeId: String(nodeId), inputId: String(input.id)};
            const address = `${routeId}\t${nodeId}\t${input.id}`;
            const row = this._rows.addLevelRow({id, name: input.name || t.applicationUnavailable,
                icon: 'audio-input-microphone-symbolic', value: input.volume, muted: input.muted, commandTarget,
                available: input.available !== false,
                onValue: value => this._send('set_mixer_input_volume', `${address}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_mixer_input_mute', address)}, target);
            this._register(id, row);
        }
        if (!(inputs || []).length)
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.noMixerInputs, {reactive: false, can_focus: false}));
    }
}
