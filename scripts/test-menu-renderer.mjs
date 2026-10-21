import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import KeyedSection from '../gnome/audio-matrix-quick-settings/menu-keyed-section.js';
import {orderedQuickGroups} from '../gnome/audio-matrix-quick-settings/menu-state.js';
import {captureMenuAnchor, restoreMenuAnchor} from '../gnome/audio-matrix-quick-settings/menu-anchor.js';

const commands = [];
let nextSignal = 0;
class FakeBox {
    constructor() { this.children = []; }
    add_child(child) { this.children.push(child); }
    remove_child(child) { this.children.splice(this.children.indexOf(child), 1); }
}
class FakeItem {
    constructor(label = '', state = undefined) {
        this.label = typeof label === 'string' ? {text: label} : {text: ''};
        this.state = state;
        this.actor = this;
        this.children = [];
        this.handlers = new Map();
        this.blocked = new Set();
        this.states = [];
        this.visible = true;
    }
    add_child(child) { this.children.push(child); }
    connect(signal, callback) { const id = ++nextSignal; this.handlers.set(id, {signal, callback}); return id; }
    block_signal_handler(id) { this.blocked.add(id); }
    unblock_signal_handler(id) { this.blocked.delete(id); }
    stop_emission_by_name() {}
    set_style() {}
    setIcon(icon) { this.icon_name = icon; }
    add_accessible_state(state) { this.states.push(state); }
    remove_accessible_state(state) { this.states = this.states.filter(item => item !== state); }
    setOrnament(value) { this.ornament = value; }
    get_child() { return this.children[0]; }
}
class FakeSwitch extends FakeItem {}
class FakeSubmenu extends FakeItem {
    constructor(label = '') { super(label); this.menu = new FakeSection(); }
}
class FakeSection {
    constructor() {
        this.items = [];
        this.box = new FakeBox();
        this.actor = {visible: true, set_style() {}};
    }
    addMenuItem(item) { this.items.push(item); this.box.add_child(item.actor); }
    removeMenuItem(item) {
        this.items.splice(this.items.indexOf(item), 1);
        this.box.remove_child(item.actor);
    }
}
class FakeLabel {
    constructor(properties = {}) { Object.assign(this, properties); }
}
const fixture = {
    PopupMenu: {
        PopupBaseMenuItem: FakeItem,
        PopupImageMenuItem: FakeItem,
        PopupMenuItem: FakeItem,
        PopupMenuSection: FakeSection,
        PopupSubMenuMenuItem: FakeSubmenu,
        PopupSwitchMenuItem: FakeSwitch,
        Ornament: {DOT: 1, NO_DOT: 0},
    },
    Atk: {Role: {RADIO_BUTTON: 1}, StateType: {EXPANDABLE: 1, EXPANDED: 2}},
    St: {Label: FakeLabel},
    boxLayout: () => new FakeBox(),
    orderedQuickGroups,
    AudioMatrixKeyedSection: KeyedSection,
};
globalThis.audioMatrixRendererFixture = fixture;

async function loadModule(name, bindings) {
    const source = (await readFile(new URL(`../gnome/audio-matrix-quick-settings/${name}`, import.meta.url), 'utf8'))
        .replace(/^import .*;\n/gm, '');
    const wrapped = `const {${bindings}} = globalThis.audioMatrixRendererFixture;\n${source}`;
    return (await import(`data:text/javascript;base64,${Buffer.from(wrapped).toString('base64')}`)).default;
}

const MenuLinks = await loadModule('menu-links.js', 'PopupMenu, Atk, St, boxLayout');
const MenuGroups = await loadModule('menu-groups.js', 'PopupMenu, Atk, AudioMatrixKeyedSection, orderedQuickGroups');

const routeControl = {
    id: 'route:main:music-switch', routeId: 'main', nodeId: 'music-switch', nodeType: 'switch',
    kind: 'choice', label: 'Music route', multiple: true, value: ['in->monitor'],
    options: [{id: 'in->monitor', label: 'Monitor'}, {id: 'in->vibro', label: 'Vibration'}],
};
const routeSection = new FakeSection();
const keyedRouteSection = new KeyedSection(routeSection);
const links = new MenuLinks((name, payload) => commands.push([name, payload]));
keyedRouteSection.begin();
const firstCard = links.render(routeControl, keyedRouteSection, {multiSwitchDescription: '1 input → multiple outputs'});
keyedRouteSection.end();
assert.equal(routeSection.items.length, 1, 'MultiSwitch is a single inline card, not a submenu');
const cardBody = firstCard.children[0];
assert.equal(cardBody.children[0].text, 'MultiSwitch', 'The inline card uses the native MultiSwitch label');
const optionRows = cardBody.children[2].children;
assert.deepEqual(optionRows.map(row => row.label.text), ['Monitor', 'Vibration']);
assert.deepEqual(optionRows.map(row => row.state), [true, false]);

links.update(firstCard, {...routeControl, value: ['in->vibro']});
assert.deepEqual(optionRows.map(row => row.state), [false, true],
    'A live routeControl State updates the existing checkbox actors');
optionRows[0].state = true;
optionRows[0].handlers.get([...optionRows[0].handlers.keys()][0]).callback();
assert.deepEqual(commands.pop(), ['setRouteEnabled', {
    routeId: 'main', switchNodeId: 'music-switch', linkId: 'in->monitor', enabled: true,
}], 'Route toggles dispatch the exact API v5 route JSON payload');

const disclosures = {
    groupOpen: () => true, pinOpen: () => true,
    trackGroup() {}, trackPin() {}, retain() {},
};
const indexedRoutes = new Map();
const groups = new MenuGroups({
    send: (name, value) => commands.push([name, value]), disclosures,
    applications: {render() {}}, links, mixer: {render() {}}, output: {render() {}},
    renderControls() {}, linkRows: indexedRoutes,
});
const groupSection = new FakeSection();
const groupColumn = new KeyedSection(groupSection);
const routingPin = {
    id: 'pin-multiswitch', routeId: 'main', nodeId: 'music-switch', nodeType: 'switch',
    name: 'MultiSwitch', groupId: 'routing', order: 0, view: 'EXPANDED', routeControl,
};
groups.render(groupColumn, {
    quickGroups: [{id: 'routing', name: 'Routing', order: 0, collapsed: false}], quickControls: [],
}, [routingPin], {multiSwitchDescription: '1 input → multiple outputs'});
assert.equal(indexedRoutes.get(routeControl.id) instanceof FakeItem, true,
    'Every pinned MultiSwitch routeControl row is registered for State updates');
links.update(indexedRoutes.get(routeControl.id), {...routeControl, value: ['in->vibro']});
const indexedCardRows = indexedRoutes.get(routeControl.id).children[0].children[2].children;
assert.deepEqual(indexedCardRows.map(row => row.state), [false, true],
    'The pin → quickPins.routeControl → group → checkbox update pipeline stays live');

function row(key, label) {
    const item = new FakeItem(label);
    item._audioMatrixKey = key;
    item._audioMatrixKind = 'stream';
    item._audioMatrixPatch = incoming => { item.label.text = incoming.label.text; };
    return item;
}
function reconcile(section, rows) {
    section.begin();
    for (const item of rows) section.addMenuItem(item);
    section.end();
}
const streamSection = new FakeSection();
const keyedStreams = new KeyedSection(streamSection);
const appRow = row('app:browser', 'Browser');
const tabOne = row('stream:tab-one', 'First tab');
const tabTwo = row('stream:tab-two', 'Second tab');
const footer = row('route:footer', 'Route');
reconcile(keyedStreams, [appRow, tabOne, tabTwo, footer]);
const before = captureMenuAnchor([
    {key: 'app:browser', y: 0, height: 10},
    {key: 'stream:tab-one', y: 18, height: 10},
    {key: 'stream:tab-two', y: 34, height: 10},
    {key: 'route:footer', y: 50, height: 10},
], 15, 24, 40, 'stream:tab-two');
reconcile(keyedStreams, [
    row('app:browser', 'Browser'), row('stream:new', 'New tab'),
    row('stream:tab-one', 'First tab'), row('stream:tab-two', 'Second tab'), row('route:footer', 'Route'),
]);
assert.equal(keyedStreams.item('app:browser'), appRow, 'A structural stream change preserves the app actor');
assert.equal(keyedStreams.item('stream:tab-one'), tabOne, 'Unchanged stream actors are keyed and reused');
assert.equal(keyedStreams.item('stream:tab-two'), tabTwo, 'The focused stream actor remains the same object');
const afterRows = [
    {key: 'app:browser', y: 0, height: 10},
    {key: 'stream:new', y: 18, height: 10},
    {key: 'stream:tab-one', y: 34, height: 10},
    {key: 'stream:tab-two', y: 50, height: 10},
    {key: 'route:footer', y: 66, height: 10},
];
const restored = restoreMenuAnchor(before, afterRows, 15, {lower: 0, upper: 100, pageSize: 24});
assert.equal(restored.value, 56, 'The scroll anchor row stays at the same pixel offset');
assert.equal(restored.focusKey, 'stream:tab-two', 'Keyboard focus follows its stable row ID');
const removedFocus = restoreMenuAnchor(before, afterRows.filter(item => item.key !== 'stream:tab-two'), 15,
    {lower: 0, upper: 100, pageSize: 24});
assert.equal(removedFocus.focusKey, 'route:footer', 'Removing the focus row chooses the nearest surviving neighbor');

delete globalThis.audioMatrixRendererFixture;
console.log('GNOME behavior checks cover inline MultiSwitch updates and keyed stream scroll/focus preservation');
