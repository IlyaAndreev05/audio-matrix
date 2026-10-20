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

const renderedRoutes = [];
const renderedVolumes = [];
const graphContext = {
    _column: new Section(), _applications: {render() {}},
    _disclosures: {
        groupOpen: () => true,
        trackGroup: (_id, header, content) => { tracked.routing = {header, content}; },
        toggleGroup: () => false,
        pinOpen: () => true, trackPin: () => {}, togglePin: () => false,
    },
    _send() {},
    _links: {render: (control, target) => {
        renderedRoutes.push(control);
        target.addMenuItem(new MenuItem(control.label));
    }},
    _mixer: {render() { throw new Error('Merge must not become a second volume mixer'); }},
    _output: {render: (pin, _t, target) => {
        renderedVolumes.push(pin.name);
        target.addMenuItem(new MenuItem(pin.name));
    }},
    _renderControls() {},
};
graphContext._renderPin = Groups.prototype._renderPin;
graphContext._displayPinName = Groups.prototype._displayPinName;
const routeControl = {id: 'route:main:switch', routeId: 'main', nodeId: 'switch', nodeType: 'switch',
    kind: 'choice', label: 'Маршрутизация музыки', multiple: true, value: ['in->monitor'],
    options: [{id: 'in->monitor', label: 'Монитор'}, {id: 'in->vibro', label: 'Вибродинамик'}]};
Groups.prototype._renderGroup.call(graphContext, {id: 'routing', name: 'Маршрутизация'}, [
    {id: 'switch-pin', routeId: 'main', nodeId: 'switch', name: 'MultiSwitch', nodeType: 'switch',
        groupId: 'routing', routeControl},
    {id: 'gain-pin', routeId: 'main', nodeId: 'gain', name: 'Общая сила вибродинамика', nodeType: 'gain',
        groupId: 'routing', gain: {routeId: 'main', nodeId: 'gain', volume: 0.5, muted: false}},
], {vibroOverall: 'Общая сила'}, true);
const routingContent = tracked.routing.content;
assert.deepEqual(renderedRoutes, [routeControl], 'MultiSwitch exposes its connected output choices');
assert.deepEqual(renderedVolumes, ['Общая сила вибродинамика'], 'The common vibration Volume stays independently adjustable');
assert.equal(routingContent.items.length, 4, 'The route selector and gain remain nested under their node rows');
delete globalThis.audioMatrixRendererFixture;
console.log('GNOME menu renders the graph MultiSwitch and gain controls without bass-only branches');
