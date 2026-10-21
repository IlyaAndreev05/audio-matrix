import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {orderedQuickGroups, visibleApplicationStreams, visibleBrowserTabs} from '../gnome/audio-matrix-quick-settings/menu-state.js';
import {captureMenuViewport, restoreMenuViewport} from '../gnome/audio-matrix-quick-settings/menu-viewport.js';

const commands = [];
class Actor {
    constructor() { this.visible = true; this.parent = null; this.y = 0; this.height = 20; }
    get_parent() { return this.parent; }
    get_transformed_position() { return [0, this.y]; }
    get_height() { return this.height; }
    set_style() {}
    remove_child(child) { if (child.parent === this) child.parent = null; }
}
class Label {
    constructor(text = '') { this.text = text; }
    set_width() {}
}
class MenuItem {
    constructor(label = '', options = {}) {
        this.label = {text: label};
        this.actor = new Actor();
        this.handlers = {};
        this.children = [];
        this.states = [];
        this.options = options;
        this.reactive = options.reactive ?? true;
        this.can_focus = options.can_focus ?? true;
    }
    set_style() {}
    add_accessible_state(state) { this.states.push(state); }
    remove_accessible_state(state) { this.states = this.states.filter(value => value !== state); }
    setIcon(icon) { this.icon_name = icon; }
    setOrnament(value) { this.ornament = value; }
    connect(signal, callback) { this.handlers[signal] = callback; return signal; }
    emit(signal) { this.handlers[signal]?.(); }
    stop_emission_by_name() {}
    block_signal_handler() {}
    unblock_signal_handler() {}
    set_sensitive(value) { this.sensitive = value; }
    add_child(child) { this.children.push(child); }
    get_child() { return this.children[0]; }
}
class Section {
    constructor() {
        this.actor = new Actor();
        this.items = [];
        this.box = {};
    }
    addMenuItem(item) {
        if (item.actor?.parent) item.actor.parent.remove_child(item.actor);
        this.items.push(item);
        if (item.actor) item.actor.parent = this.actor;
    }
    removeMenuItem(item) {
        const index = this.items.indexOf(item);
        if (index >= 0) this.items.splice(index, 1);
        if (item.actor?.parent === this.actor) item.actor.parent = null;
    }
    removeAll() { for (const item of [...this.items]) this.removeMenuItem(item); }
}
class Switch extends MenuItem {
    constructor(label, state) { super(label); this.state = state; }
}
class Submenu extends MenuItem {
    constructor(label) { super(label); this.menu = new Section(); }
}
const PopupMenu = {
    PopupMenuSection: Section,
    PopupMenuItem: MenuItem,
    PopupImageMenuItem: MenuItem,
    PopupBaseMenuItem: MenuItem,
    PopupSwitchMenuItem: Switch,
    PopupSubMenuMenuItem: Submenu,
    PopupSeparatorMenuItem: MenuItem,
    Ornament: {DOT: 1, NO_DOT: 0},
};
function removeMenuItem(section, item) { section.removeMenuItem(item); }
function reorderMenuItems(section, previous, desired) {
    if (previous.length === desired.length && previous.every((item, index) => item === desired[index])) return;
    for (const item of previous) removeMenuItem(section, item);
    for (const item of desired) section.addMenuItem(item);
}
const fixture = {PopupMenu, Atk: {StateType: {EXPANDABLE: 1, EXPANDED: 2}, Role: {RADIO_BUTTON: 1}},
    orderedQuickGroups, visibleApplicationStreams, visibleBrowserTabs, removeMenuItem, reorderMenuItems,
    applicationIcon: () => 'application-x-executable-symbolic'};
globalThis.audioMatrixRendererFixture = fixture;

async function loadModule(name, bindings, replacements) {
    let source = await readFile(new URL(`../gnome/audio-matrix-quick-settings/${name}`, import.meta.url), 'utf8');
    source = source.replace(/^import .*;\n/gm, line => replacements[line.trim()] || '');
    source = `const {${bindings}} = globalThis.audioMatrixRendererFixture;\n${source}`;
    return (await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)).default;
}
const moduleImports = {
    "import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';": '',
    "import Atk from 'gi://Atk';": '',
    "import {orderedQuickGroups} from './menu-state.js';": '',
    "import {removeMenuItem, reorderMenuItems} from './menu-row-utils.js';": '',
    "import {visibleApplicationStreams, visibleBrowserTabs} from './menu-state.js';": '',
    "import {reorderMenuItems} from './menu-row-utils.js';": '',
    "import {applicationIcon} from './menu-icons.js';": '',
};
const Links = await loadModule('menu-links.js', 'PopupMenu, Atk', moduleImports);
const Groups = await loadModule('menu-groups.js',
    'PopupMenu, Atk, orderedQuickGroups, removeMenuItem, reorderMenuItems', moduleImports);
const Applications = await loadModule('menu-applications.js',
    'PopupMenu, visibleApplicationStreams, visibleBrowserTabs, reorderMenuItems, applicationIcon', moduleImports);

const language = {unmute: 'Unmute', mute: 'Mute', noApplications: 'No applications',
    outputUnavailable: 'Unavailable', allApplications: 'All applications', mainMixer: 'Main Mixer',
    vibroOverall: 'Vibro Gain', vibroLimiter: 'Limiter', vibroOutput: 'Vibro output', events: 'Events'};
const routeControl = {id: 'route:main:multi', routeId: 'main', nodeId: 'multi', nodeType: 'switch',
    kind: 'choice', label: 'Music routing', multiple: true, value: ['in->monitor'], options: [
        {id: 'in->monitor', label: 'Monitor'}, {id: 'in->vibro', label: 'Vibro'},
    ]};
const linkRows = new Map();
const links = new Links((name, value) => commands.push([name, value]));
const disclosures = {
    groups: new Map(), pins: new Map(),
    groupOpen: group => !group.collapsed, pinOpen: pin => pin.view !== 'COMPACT',
    trackGroup(id, header, content) { this.groups.set(id, {header, content}); },
    trackPin(id, header, content) { this.pins.set(id, {header, content}); },
    toggleGroup() { return true; }, togglePin() { return true; },
};
const groups = new Groups({send: (name, value) => commands.push([name, value]), disclosures,
    applications: {render() {}}, links, mixer: {render() {}}, output: {render() {}},
    renderControls() {}, registerLinkRow: (id, row) => linkRows.set(id, row)});
const column = new Section();
const state = {quickGroups: [{id: 'routing', name: 'Routing', order: 0, collapsed: false}],
    quickPins: [{id: 'pin-switch', routeId: 'main', nodeId: 'multi', name: 'MultiSwitch',
        nodeType: 'switch', groupId: 'routing', order: 0, routeControl}], quickControls: []};
groups.render(column, state, state.quickPins, language);
const pinEntry = groups._pinEntries.get('pin-switch');
const firstPinRow = pinEntry.wrapper;
const multiSwitch = linkRows.get(routeControl.id);
assert.ok(multiSwitch, 'An existing pinned routeControl is registered for live updates');
assert.equal(pinEntry.routeSection.items[0].label.text, 'MultiSwitch');
assert.equal(pinEntry.routeSection.items[1].label.text, '1 вход → несколько выходов');
const monitor = pinEntry.routeSection.items[2];
monitor.state = false;
monitor.handlers.toggled();
assert.deepEqual(commands.pop(), ['setRouteEnabled', {
    routeId: 'main', switchNodeId: 'multi', linkId: 'in->monitor', enabled: false,
}], 'MultiSwitch publishes the exact route and activeLink endpoint IDs');
const liveControl = {...routeControl, value: ['in->vibro']};
links.update(multiSwitch, liveControl);
assert.equal(monitor.state, false);
assert.equal(pinEntry.routeSection.items[3].state, true,
    'A published activeLinks change updates the existing checkbox actors');
groups.render(column, state, state.quickPins, language);
assert.equal(groups._pinEntries.get('pin-switch').wrapper, firstPinRow,
    'A value-only route update preserves the keyed pin row');

const rowOptions = [];
const fakeRows = {
    createLevelRow(options) { const item = new MenuItem(options.name); item.rowOptions = {...options}; return item; },
    updateLevelRow(item, options) { item.rowOptions = {...item.rowOptions, ...options}; },
    applicationIcon: () => 'application-x-executable-symbolic',
};
const appRenderer = new Applications({rows: fakeRows, send() {}});
const appSection = new Section();
const appPin = {id: 'app-source', routeId: 'main', nodeId: 'apps'};
const appA = {id: 'app:chrome', name: 'Chrome', volume: .7, muted: false,
    streams: [{id: 'stream-1', name: 'Audio one', volume: .4, muted: false}]};
appRenderer.render(appPin, [appA], language, appSection);
const appRow = appSection.items.find(row => row.rowOptions?.id === 'app:app:chrome');
const streamRow = appSection.items.find(row => row.rowOptions?.id === 'stream:stream-1');
const appB = {...appA, streams: [...appA.streams, {id: 'stream-2', name: 'Audio two', volume: .2, muted: false}]};
appRenderer.render(appPin, [appB], language, appSection);
assert.equal(appSection.items.find(row => row.rowOptions?.id === 'app:app:chrome'), appRow,
    'Adding a stream retains the existing keyed application row actor');
assert.equal(appSection.items.find(row => row.rowOptions?.id === 'stream:stream-1'), streamRow,
    'Adding a stream retains the existing keyed stream row actor');
assert.equal(appSection.items.length, 3, 'Only the new stream row is inserted');

const scroll = {adjustment: {value: 20, lower: 0, upper: 220, page_size: 50},
    get_vadjustment() { return this.adjustment; }, get_transformed_position() { return [0, 0]; }};
const anchorActor = {visible: true, height: 10, get_height() { return this.height; },
    get_parent() { return scroll; }, get_transformed_position() { return [0, 30 - scroll.adjustment.value]; }};
const focus = {get_parent: () => anchorActor};
const viewport = captureMenuViewport(scroll, [
    {id: 'removed-above', actor: {visible: true, height: 10, get_height() { return 10; }, get_transformed_position: () => [0, -30]}},
    {id: 'stream:stream-1', actor: anchorActor},
], focus);
assert.equal(viewport.anchor.id, 'stream:stream-1');
scroll.adjustment.upper += 30;
anchorActor.get_transformed_position = () => [0, 50 - scroll.adjustment.value];
let focusedActor = null;
restoreMenuViewport(scroll, viewport, [{id: 'stream:stream-1', actor: anchorActor}], actor => focusedActor = actor);
assert.equal(anchorActor.get_transformed_position()[1], viewport.anchor.offset,
    'Structural insertion preserves the stable row anchor pixel offset');
assert.equal(focusedActor, null, 'A still-mounted focused row keeps its actor and keyboard focus');

delete globalThis.audioMatrixRendererFixture;
console.log('Menu behavior covers keyed stream identity, viewport anchors, and exact MultiSwitch links');
