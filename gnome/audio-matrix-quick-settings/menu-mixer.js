import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

export default class AudioMatrixMenuMixer {
    constructor() {}

    render(routeId, nodeId, instanceKey, inputs, t, target) {
        for (const input of inputs || []) {
            const label = String(input.name || input.id);
            const row = new PopupMenu.PopupMenuItem(label, {reactive: false, can_focus: false});
            row._audioMatrixKey = `mixer:${routeId}:${nodeId}:${instanceKey || ''}:${input.id}`;
            row._audioMatrixKind = 'mixer-input';
            row._audioMatrixPatch = incoming => {
                row.label.text = incoming.label.text;
            };
            target.addMenuItem(row);
        }
        if (!(inputs || []).length) {
            const row = new PopupMenu.PopupMenuItem(t.noMixerInputs, {reactive: false, can_focus: false});
            row._audioMatrixKey = `mixer-empty:${routeId}:${nodeId}:${instanceKey || ''}`;
            target.addMenuItem(row);
        }
    }
}
