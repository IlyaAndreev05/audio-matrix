import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

export default class AudioMatrixMenuMixer {
    constructor() {}

    render(routeId, nodeId, inputs, t, target) {
        for (const input of inputs || [])
            target.addMenuItem(new PopupMenu.PopupMenuItem(input.name || input.id,
                {reactive: false, can_focus: false}));
        if (!(inputs || []).length)
            target.addMenuItem(new PopupMenu.PopupMenuItem(t.noMixerInputs, {reactive: false, can_focus: false}));
    }
}
