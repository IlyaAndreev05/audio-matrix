import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {orderedQuickGroups} from '../gnome/audio-matrix-quick-settings/menu-state.js';

const commands = [];
class Icon { constructor(properties) { Object.assign(this, properties); } }
class Label extends Icon {}
class MenuItem extends Icon {
    constructor(label, iconName) {
        super({label: {text: label}, icon_name: iconName, children: [], states: [], handlers: {}});
        this.actor = this;
    }
    set_style() {}
    add_accessible_state(state) { this.states.push(state); }
    remove_accessible_state(state) { this.states = this.states.filter(item => item !== state); }
    setIcon(iconName) { this.icon_name = iconName; }
    add_child(child) { this.children.push(child); }
    connect(signal, callback) { this.handlers[signal] = callback; return signal; }
    stop_emission_by_name() {}
}
class Section {
    constructor() { this.actor = {visible: true, set_style() {}}; this.items = []; }
    addMenuItem(item) { this.items.push(item); }
}
class Switch {
    constructor(label, state) { this.label = label; this.state = state; this.handlers = {}; }
    connect(signal, callback) { this.handlers[signal] = callback; return signal; }
}
globalThis.audioMatrixRendererFixture = {
    orderedQuickGroups,
    St: {Label, Icon},
    DND: {makeDraggable() {}, DragMotionResult: {MOVE_DROP: 1, NO_DROP: 0}},
    Atk: {StateType: {EXPANDABLE: 1, EXPANDED: 2}},
    PopupMenu: {
        PopupImageMenuItem: MenuItem, PopupMenuSection: Section, PopupSwitchMenuItem: Switch,
    },
    TEXT: {en: {onOff: 'On / Off'}},
};
async function loadModule(name, bindings) {
    const original = await readFile(new URL(`../gnome/audio-matrix-quick-settings/${name}`, import.meta.url), 'utf8');
    const source = `const {${bindings}} = globalThis.audioMatrixRendererFixture;\n`
        + original.replace(/^import .*;\n/gm, '');
    return (await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)).default;
}
const Renderer = await loadModule('menu-renderer.js', 'St, DND, PopupMenu, TEXT');
const Groups = await loadModule('menu-groups.js', 'orderedQuickGroups, PopupMenu, Atk');
const tracked = {};
const context = {
    _getSnapshot: () => ({state: {}, language: 'en'}),
    _send: (name, value) => commands.push([name, value]),
    _toggleRows: new Map(),
    _column: new Section(),
    _disclosures: {
        groupOpen: () => false,
        trackGroup: (id, header, content) => { tracked.group = {id, header, content}; },
        toggleGroup: () => true,
        pinOpen: () => false,
        trackPin: (id, header, content) => { tracked.pin = {id, header, content}; },
        togglePin: () => true,
    },
    _applications: {render() { throw new Error('applications must not render for an output pin'); }},
    _mixer: {render() { throw new Error('mixer must not render for an output pin'); }},
    _output: {render(pin, t, target) { target.addMenuItem(new MenuItem('Output ready')); }},
    _renderControls: () => commands.push(['controls']),
};
context._renderPin = Groups.prototype._renderPin;
context._displayPinName = Groups.prototype._displayPinName;
const pin = {
    id: 'pin-output', routeId: 'octine-headphones', nodeId: 'octine-headphones-output',
    name: 'Наушники', nodeType: 'device_slot', groupId: 'outputs', order: 0, pinned: false,
    output: {state: 'ONLINE', available: true},
};
Groups.prototype._renderGroup.call(context, {id: 'outputs', name: 'Outputs'}, [pin], {});
const header = context._column.items[0];
assert.equal(header.icon_name, 'pan-end-symbolic', 'Group headers keep only their disclosure arrow');
assert.equal(header.children.length, 0, 'Group headers must not add reorder handles, move arrows, or pin icons');
assert.equal(context._column.items[1].actor.visible, false, 'Collapsed groups still hide their content');
assert.equal(tracked.group.id, 'outputs');
assert.equal(tracked.pin.id, 'pin-output');
assert.equal(tracked.pin.header.children.length, 0, 'Pin headers must not add a pin icon');
assert.deepEqual(commands, [['controls']], 'Rendering a pin leaves only its attached controls, never reorder commands');

commands.length = 0;
const target = new Section();
Renderer.prototype._renderToggleControl.call(context,
    {id: 'gate', nodeType: 'gate', label: 'Branch blocked', value: false}, target);
const gate = target.items[0];
assert.equal(gate.label, 'On / Off', 'Gate exposes only On/Off, not routing implementation text');
gate.handlers.toggled();
assert.deepEqual(commands.pop(), ['toggle_quick_control', 'gate']);

const vibroControls = [];
const vibroContext = {
    _column: new Section(), _detailsOpen: new Map(), _applications: {render() {}},
    _disclosures: {
        groupOpen: () => true,
        trackGroup: (_id, header, content) => { tracked.vibro = {header, content}; },
        toggleGroup: () => false,
        pinOpen: () => false, trackPin() {}, togglePin: () => false,
    },
    _send() {},
    _mixer: {render: (_route, _node, inputs, _t, target) =>
        inputs.forEach(input => target.addMenuItem(new MenuItem(input.label)))},
    _output: {render: (pin, _t, target) => target.addMenuItem(new MenuItem(pin.name))},
    _renderControls: (controls, _t, target) => {
        vibroControls.push(...controls.map(control => control.label));
        controls.forEach(control => target.addMenuItem(new MenuItem(control.label)));
    },
};
vibroContext._renderPin = Groups.prototype._renderPin;
vibroContext._renderVibroGroup = Groups.prototype._renderVibroGroup;
vibroContext._displayPinName = Groups.prototype._displayPinName;
const detailControls = [{id: 'cutoff', label: 'Bass cutoff'}, {id: 'slope', label: 'Bass slope'}];
Groups.prototype._renderGroup.call(vibroContext,
    {id: 'vibro', name: 'Vibration'}, [
        {id: 'mixer-pin', routeId: 'common', nodeId: 'octine-vibro-mixer', nodeType: 'mixer', groupId: 'vibro',
            mixerInputs: [{id: 'events', label: 'Events'}, {id: 'bass', label: 'Bass'}]},
        {id: 'gain-pin', routeId: 'common', nodeId: 'octine-vibro-gain', name: 'Overall', nodeType: 'gain', groupId: 'vibro',
            gain: {routeId: 'common', nodeId: 'gain', volume: 0.5, muted: false}},
        {id: 'filter-pin', routeId: 'common', nodeId: 'octine-bass-low-pass', name: 'Bass filter', nodeType: 'filter', groupId: 'vibro', controls: detailControls},
        {id: 'output-pin', routeId: 'common', nodeId: 'octine-vibro-output', name: 'Vibration device', nodeType: 'device_slot', groupId: 'vibro'},
    ], {details: 'Details', vibroOverall: 'Overall', onOff: 'On / Off'}, true);
const vibroContent = tracked.vibro.content;
assert.deepEqual(vibroContent.items.slice(0, 3).map(item => item.label.text),
    ['Events', 'Bass', 'Overall'], 'The three live vibration controls stay at the top level');
const detailsHeader = vibroContent.items[3];
const detailsContent = vibroContent.items[4];
vibroContext._detailsContent = detailsContent;
assert.equal(detailsHeader.label.text, 'Details');
assert.equal(detailsContent.actor.visible, false, 'Technical controls start collapsed');
assert.deepEqual(vibroControls, ['Bass cutoff', 'Bass slope'], 'Bass cutoff controls render inside Details');
assert.deepEqual(detailsContent.items.slice(0, 2).map(item => item.label.text), ['Bass cutoff', 'Bass slope']);
detailsHeader.handlers.activate();
assert.equal(detailsContent.actor.visible, true, 'Details expands inside the vibration group');
assert.deepEqual(vibroControls, ['Bass cutoff', 'Bass slope']);
delete globalThis.audioMatrixRendererFixture;
console.log('Group and pin headers expose disclosure only; reorder/pin controls remain in the main app');
