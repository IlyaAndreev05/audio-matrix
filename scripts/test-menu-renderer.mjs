import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {orderedQuickGroups, visibleBrowserTabs, menuLevelRows, allQuickPins} from '../gnome/audio-matrix-quick-settings/menu-state.js';
import {captureMenuViewport, restoreMenuViewport} from '../gnome/audio-matrix-quick-settings/menu-viewport.js';

class Actor {
    constructor(properties = {}) { Object.assign(this, {visible: true, parent: null, children: [], handlers: {}, y: 0, height: 20}, properties); }
    get_parent() { return this.parent; }
    get_transformed_position() { return [0, this.y]; }
    get_height() { return this.height; }
    set_style() {}
    add_style_class_name() {}
    add_style_pseudo_class() {}
    remove_style_pseudo_class() {}
    add_accessible_state() {}
    remove_accessible_state() {}
    add_child(child) { this.children.push(child); child.parent = this; }
    remove_child(child) { this.children = this.children.filter(item => item !== child); child.parent = null; }
    set_child(child) { this.child = child; this.children = [child]; child.parent = this; }
    get_child() { return this.child || this.children[0]; }
    connect(signal, callback) { this.handlers[signal] = callback; return signal; }
    emit(signal, ...args) { this.handlers[signal]?.(this, ...args); }
    stop_emission_by_name() {}
    destroy() { this.destroyed = true; this.parent?.remove_child(this); }
}
class MenuItem extends Actor {
    constructor(label = '', options = {}) { super(options); this.actor = this; this.label = {text: label}; }
    setIcon(icon) { this.icon_name = icon; }
}
class Section extends Actor {
    constructor() { super(); this.actor = this; this.box = this; this.items = []; }
    addMenuItem(item) { item.parent?.remove_child(item); this.items.push(item); this.add_child(item); }
    remove_child(item) { super.remove_child(item); this.items = this.items.filter(value => value !== item); }
    removeMenuItem(item) { this.remove_child(item); }
    removeAll() { for (const item of [...this.items]) item.destroy(); }
}
class Label extends Actor {
    constructor(properties) { super(properties); this.clutter_text = {}; }
}
const PopupMenu = {PopupMenuSection: Section, PopupMenuItem: MenuItem,
    PopupImageMenuItem: MenuItem, PopupBaseMenuItem: MenuItem};
const commands = [];
function removeMenuItem(section, item) { section.removeMenuItem(item); }
function reorderMenuItems(section, previous, desired) {
    if (previous.length === desired.length && previous.every((item, index) => item === desired[index])) return;
    for (const item of previous) removeMenuItem(section, item);
    for (const item of desired) section.addMenuItem(item);
}
const fixture = {PopupMenu, Atk: {StateType: {EXPANDABLE: 1, EXPANDED: 2, CHECKED: 3},
        Role: {CHECK_BOX: 1, TOGGLE_BUTTON: 4}},
    St: {Label, Icon: Actor, Bin: Actor, Button: Actor, ScrollView: Actor,
        PolicyType: {NEVER: 0, AUTOMATIC: 1}},
    Slider: {Slider: class FakeSlider extends Actor {
        constructor(value) { super(); this.value = value; this.reactive = true; this.can_focus = true; }
        block_signal_handler() {}
        unblock_signal_handler() {}
    }},
    Pango: {EllipsizeMode: {END: 0}},
    Meta: {LaterType: {BEFORE_REDRAW: 1}},
    TEXT: {ru: {volume: 'Громкость', mute: 'Выключить звук', unmute: 'Включить звук'}},
    Clutter: {ActorAlign: {CENTER: 0}},
    boxLayout: properties => new Actor(properties), outputIcon: () => 'audio-speakers-symbolic',
    orderedQuickGroups, visibleBrowserTabs, menuLevelRows, allQuickPins, removeMenuItem, reorderMenuItems,
    applicationIcon: () => 'application-x-executable-symbolic'};
globalThis.audioMatrixRendererFixture = fixture;
async function loadModule(name) {
    const source = (await readFile(new URL(`../gnome/audio-matrix-quick-settings/${name}`, import.meta.url), 'utf8'))
        .replace(/^import .*;\n/gm, '');
    return (await import(`data:text/javascript;base64,${Buffer.from(`const {${Object.keys(fixture).join(',')}} = globalThis.audioMatrixRendererFixture;\n${source}`).toString('base64')}`)).default;
}
const Links = await loadModule('menu-links.js');
const Groups = await loadModule('menu-groups.js');
const Applications = await loadModule('menu-applications.js');
const routeControl = {id: 'route:main:multi', routeId: 'main', nodeId: 'multi', nodeType: 'switch',
    multiple: true, value: ['in->monitor'], options: [{id: 'in->monitor', label: 'Монитор'}, {id: 'in->vibro', label: 'Вибродинамик'}]};
const links = new Links((name, value) => commands.push([name, value]));
const groups = new Groups({send() {}, disclosures: {groupOpen: () => true, trackGroup() {}},
    applications: {render() {}}, links, output: {render() {}}});
const column = new Section();
const pin = {id: 'pin-switch', routeId: 'main', nodeId: 'multi', name: 'Распределитель звука', nodeType: 'switch', groupId: 'routing', routeControl};
const state = {quickGroups: [{id: 'routing', name: 'Маршрутизация', order: 0}], quickPins: [pin]};
groups.render(column, state, state.quickPins, {});
const firstPin = groups._pinEntries.get(pin.id).wrapper;
const routeCard = firstPin.items[0];
const monitor = routeCard.items[1];
const vibration = routeCard.items[2];
monitor.emit('activate', {});
assert.deepEqual(commands.pop(), ['setRouteEnabled', {routeId: 'main', switchNodeId: 'multi', linkId: 'in->monitor', enabled: false}]);
const reference = groups._pinEntries.get(pin.id).routeRef;
links.update(reference, {...routeControl, value: ['in->vibro']});
assert.equal(monitor.children.at(-1).get_child().visible, false);
assert.equal(vibration.children.at(-1).get_child().visible, true);
groups.render(column, state, state.quickPins, {});
assert.equal(groups._pinEntries.get(pin.id).wrapper, firstPin, 'A route value change preserves the focused row');
links.update(reference, {...routeControl, value: ['in->vibro'], options: [routeControl.options[0], {...routeControl.options[1], available: false}]});
assert.equal(vibration.reactive, false);
assert.equal(vibration.children.at(-1).get_child().visible, false, 'An offline output cannot look actively routed');

const fakeRows = {
    createLevelRow(options) { const item = new MenuItem(); item.options = options; item.tabs = new Section(); return item; },
    updateLevelRow(item, options) { item.options = {...item.options, ...options}; },
    childSection(item) { return item.tabs; },
};
const appRenderer = new Applications({rows: fakeRows, send() {}});
const appSection = new Section();
const appPin = {id: 'app-source', routeId: 'main', nodeId: 'apps'};
const appA = {id: 'chrome', name: 'Chrome', volume: .7, streams: [{id: 'stream-1'}]};
appRenderer.render(appPin, [appA], {}, appSection);
const appRow = appSection.items[0];
appRenderer.render(appPin, [{...appA, streams: [...appA.streams, {id: 'stream-2'}]}], {}, appSection);
assert.equal(appSection.items[0], appRow, 'New streams must not replace the application card');
assert.equal(appSection.items.length, 1, 'Technical PipeWire streams are not separate application cards');

const scroll = {adjustment: {value: 20, lower: 0, upper: 220, page_size: 50},
    get_vadjustment() { return this.adjustment; }, get_transformed_position() { return [0, 0]; }};
const anchorActor = {visible: true, height: 10, get_height() { return this.height; },
    get_parent() { return scroll; }, get_transformed_position() { return [0, 30 - scroll.adjustment.value]; }};
const focus = {get_parent: () => anchorActor};
const viewport = captureMenuViewport(scroll, [{id: 'retained-card', actor: anchorActor}], focus);
scroll.adjustment.upper += 30;
anchorActor.get_transformed_position = () => [0, 50 - scroll.adjustment.value];
let focusedActor = null;
restoreMenuViewport(scroll, viewport, [{id: 'retained-card', actor: anchorActor}], actor => focusedActor = actor);
assert.equal(anchorActor.get_transformed_position()[1], viewport.anchor.offset, 'Insertion preserves the scroll anchor offset');
assert.equal(focusedActor, null, 'The retained actor keeps keyboard focus');

{
    // A generated Mixer input without a live owner keeps a human label but no interaction, and the
    // renderer forwards that per-row availability instead of only the bridge connection state.
    const Rows = await loadModule('menu-rows.js');
    fixture.AudioMatrixMenuRows = Rows;
    const Mixer = await loadModule('menu-mixer.js');
    const Renderer = await loadModule('menu-renderer.js');
    const rows = new Rows({section: new Section(), getLanguage: () => 'ru', send() {},
        queueSliderCommand: (_key, value, callback) => callback(value),
        onOpen() {}, onDragBegin() {}, onDragEnd() {}});
    const mixerRows = new Map();
    const sent = [];
    const mixer = new Mixer({rows, send: (name, value) => sent.push([name, value]),
        registerLevelRow: (id, row) => mixerRows.set(id, row)});
    const nodeId = 'octine-main-mixer';
    const generated = 'generated-mixer-input-4a5b';
    const inputs = available => [
        {id: 'applications', name: 'Все приложения, кроме событий', volume: 1, muted: false, available: true},
        {id: generated, name: 'Проигрыватель', volume: 0.4, muted: false, available},
    ];
    const t = {applicationUnavailable: 'Приложение недоступно'};
    mixer.render('main', nodeId, 'smoke-mixer', inputs(true), t, new Section());
    const online = mixerRows.get(`mixer:smoke-mixer:${generated}`);
    assert.deepEqual(online._audioMatrixCommandTarget, {routeId: 'main', nodeId, inputId: generated},
        'the pinned mixer row carries the exact route/node/input identity');
    assert.equal(online._audioMatrixSlider.reactive, true);
    online._audioMatrixSlider.value = 0.42;
    online._audioMatrixSlider.emit('notify::value');
    assert.deepEqual(sent.at(-1), ['set_mixer_input_volume', `main\t${nodeId}\t${generated}\t0.420`]);
    const muteButton = online.children[0].children[0].children.find(child => child.accessible_role === 4);
    assert.ok(muteButton, 'the mixer row has its mute toggle');
    muteButton.emit('clicked');
    assert.deepEqual(sent.at(-1), ['toggle_mixer_input_mute', `main\t${nodeId}\t${generated}`],
        'the mute toggle sends routeId TAB nodeId TAB inputId without a volume field');

    const renderer = Object.create(Renderer.prototype);
    renderer._levelRows = mixerRows;
    renderer._rows = rows;
    renderer._links = {update() {}, setAvailable() {}};
    const pin = {id: 'smoke-mixer', routeId: 'main', nodeId, mixerInputs: inputs(true)};
    renderer._updateControls({quickPins: [pin]}, [], true);
    assert.equal(online._audioMatrixSlider.reactive, true);
    pin.mixerInputs = inputs(false);
    renderer._updateControls({quickPins: [pin]}, [], true);
    assert.equal(online._audioMatrixSlider.reactive, false,
        'an input without a live owner must not accept commands');
    assert.equal(online.opacity, 145);
    pin.mixerInputs = inputs(true);
    renderer._updateControls({quickPins: [pin]}, [], true);
    assert.equal(online._audioMatrixSlider.reactive, true, 'a reconnected input becomes interactive again');

    mixer.render('main', nodeId, 'unknown-pin',
        [{id: 'generated-mixer-input-orphan', volume: 1, muted: false, available: false}], t, new Section());
    const orphan = mixerRows.get('mixer:unknown-pin:generated-mixer-input-orphan');
    assert.equal(orphan._audioMatrixSlider.accessible_name, 'Громкость: Приложение недоступно',
        'an unresolved generated input shows a human fallback instead of its port id');
}

delete globalThis.audioMatrixRendererFixture;
console.log('Native card behavior: exact routing, offline state, stable application cards and scroll anchors');
