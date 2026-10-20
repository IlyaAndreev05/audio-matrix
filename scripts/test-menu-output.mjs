import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

class Item {
    constructor(label, options) { this.label = label; this.options = options; }
}
globalThis.audioMatrixOutputFixture = {
    PopupMenu: {
        PopupMenuItem: Item,
        PopupImageMenuItem: class extends Item {
            constructor(label, icon, options) { super(label, options); this.icon = icon; }
        },
    },
};
const original = await readFile(new URL('../gnome/audio-matrix-quick-settings/menu-output.js', import.meta.url), 'utf8');
const source = 'const {PopupMenu} = globalThis.audioMatrixOutputFixture;\n' + original.replace(/^import .*;\n/gm, '');
const Output = (await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)).default;

const t = {
    outputUnavailable: 'Output device unavailable', mute: 'Mute', unmute: 'Unmute',
    assignDevice: 'Assign a device',
    deviceStates: {
        ONLINE: 'Connected', UNBOUND: 'Not bound', OFFLINE: 'Offline', CONFLICT: 'Device conflict',
        AMBIGUOUS: 'Ambiguous binding', IGNORED: 'Ignored',
    },
};
const levels = [];
const rows = [];
const commands = [];
const output = new Output({
    addLevelRow: options => levels.push(options),
    outputIcon: () => 'audio-card-symbolic',
    send: (name, value) => commands.push([name, value]),
});
function render(pin) {
    const items = [];
    rows.push(items);
    output.render(pin, t, {addMenuItem: item => items.push(item)});
    return items;
}
assert.equal(render({nodeId: 'slot'})[0].label, 'Output device unavailable',
    'A pin without device metadata must not invent an output');

const slotStates = {
    OFFLINE: 'Rear AUX — Offline', UNBOUND: 'Vibro slot — Assign a device',
    CONFLICT: 'Rear AUX — Device conflict', AMBIGUOUS: 'Rear AUX — Ambiguous binding',
    IGNORED: 'Rear AUX — Ignored',
};
for (const [state, label] of Object.entries(slotStates)) {
    const items = render({nodeId: 'slot', name: 'Pin label', nodeType: 'device_slot',
        output: {id: 'usb', name: 'Vibro slot', deviceName: 'USB DAC', description: 'Rear AUX',
            state, available: false, message: 'detail'}});
    assert.equal(items.length, 1);
    assert.equal(items[0].label, label, `${state} must report the assigned device when one exists`);
    assert.equal(items[0].options.reactive, false);
}
assert.equal(levels.length, 0, 'Device slots must never fabricate a synthetic Gain level');
const missingState = render({nodeId: 'slot', name: 'Pin label', nodeType: 'device_slot',
    output: {id: 'usb', name: 'Vibro slot', deviceName: 'USB DAC', available: true}});
assert.equal(missingState[0].label, 'USB DAC — Offline',
    'A missing binding state must report the assigned device as OFFLINE, never invent UNBOUND');

const bound = render({routeId: 'route', nodeId: 'slot', name: 'Pin label', nodeType: 'device_slot',
    output: {id: 'usb', name: 'Vibro slot', deviceName: 'USB DAC', description: 'Rear AUX',
        state: 'ONLINE', available: true}});
assert.equal(bound[0].label, 'Rear AUX — Connected', 'ONLINE device slots show the assigned device');
assert(bound[0].label.includes('Rear AUX'), 'The assigned device description should be visible');
assert.equal(bound[0].icon, 'audio-card-symbolic');
assert.equal(bound[0].options.reactive, false, 'Device Slot has no backend on/off parameter to expose');
assert.equal(bound[0].options.can_focus, false);
assert.equal(levels.length, 0, 'No slider or fake power toggle for a Device Slot');

const offlineOutput = render({routeId: 'route', nodeId: 'output',
    output: {id: 'hdmi', name: 'HDMI', available: false, state: 'OFFLINE'}});
assert.equal(offlineOutput[0].label, 'HDMI — Offline', 'Output nodes report real device availability');
render({routeId: 'route', nodeId: 'output', name: 'Monitor',
    output: {id: 'hdmi', name: 'HDMI', state: 'ONLINE', available: true, volume: 0.4,
        muted: false, graphGainNodeId: 'gain-1'}});
assert.equal(levels.length, 1);
assert.equal(levels[0].id, 'graph-output:output');
assert.equal(levels[0].value, 0.4);

render({nodeId: 'vibro-gain', name: 'Vibro Gain',
    gain: {routeId: 'common', nodeId: 'gain-1', volume: 0.55, muted: false}});
const gainRow = levels.at(-1);
assert.equal(gainRow.id, 'graph-gain:vibro-gain');
assert.equal(gainRow.name, 'Vibro Gain');
assert.equal(gainRow.value, 0.55);
assert.equal(gainRow.muted, false);
gainRow.onValue(0.25);
assert.deepEqual(commands.pop(), ['set_graph_gain', 'common\tgain-1\t0.250']);
gainRow.onMute();
assert.deepEqual(commands.pop(), ['toggle_graph_gain_mute', 'common\tgain-1']);
const mutedGain = render({nodeId: 'vibro-gain', name: 'Vibro Gain',
    gain: {routeId: 'common', nodeId: 'gain-1', volume: 0.55, muted: true}});
assert.equal(mutedGain.length, 0, 'Gain uses a level row, not a placeholder');
assert.equal(levels.at(-1).muted, true);
assert.equal(levels.at(-1).mutedLabel, 'Unmute');
delete globalThis.audioMatrixOutputFixture;
console.log('Device availability and pinned Gain rows expose truthful controls and commands');
