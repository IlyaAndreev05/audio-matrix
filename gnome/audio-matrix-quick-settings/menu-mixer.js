import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import {visibleMixerInputs} from './menu-state.js';

export default class AudioMatrixMenuMixer {
    constructor({rows, send, registerLevelRow}) {
        this._rows = rows;
        this._send = send;
        this._register = registerLevelRow;
    }

    visible(inputs) {
        return visibleMixerInputs(inputs);
    }

    render(routeId, nodeId, pinId, inputs, t, target) {
        let rendered = 0;
        for (const input of visibleMixerInputs(inputs)) {
            const id = `mixer:${pinId}:${input.id}`;
            const commandTarget = {routeId: String(routeId), nodeId: String(nodeId), inputId: String(input.id)};
            const address = `${routeId}\t${nodeId}\t${input.id}`;
            const row = this._rows.addLevelRow({id, name: input.name || t.applicationUnavailable,
                icon: 'audio-input-microphone-symbolic', value: input.volume, muted: input.muted, commandTarget,
                available: input.available !== false,
                onValue: value => this._send('set_mixer_input_volume', `${address}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_mixer_input_mute', address)}, target);
            this._register(id, row);
            rendered++;
        }
        return rendered;
    }
}
