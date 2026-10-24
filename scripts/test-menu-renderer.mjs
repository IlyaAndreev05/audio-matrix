import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {orderedQuickGroups, visibleBrowserTabs, menuLevelRows, allQuickPins, menuStructureSignature,
    visibleMixerInputs, visiblePinMixerInputs} from '../gnome/audio-matrix-quick-settings/menu-state.js';
import {captureMenuViewport, restoreMenuViewport} from '../gnome/audio-matrix-quick-settings/menu-viewport.js';
import {controlPosition, controlValueAt, formatControlValue} from '../gnome/audio-matrix-quick-settings/menu-control-scale.js';

class Actor {
    constructor(properties = {}) {
        Object.assign(this, {visible: true, parent: null, children: [], handlers: {}, y: 0, height: 20}, properties);
        this.styleClasses = [];
    }
    get_parent() { return this.parent; }
    get_transformed_position() { return [0, this.y]; }
    get_height() { return this.height; }
    set_style() {}
    add_style_class_name(name) { this.styleClasses.push(name); }
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
    stop_emission_by_name(signal) { this.stoppedSignal = signal; }
    destroy() { this.destroyed = true; this.parent?.remove_child(this); }
}
class MenuItem extends Actor {
    constructor(label = '', options = {}) {
        super(options);
        this.actor = this;
        this.label = {text: label};
        this.reactive = options.reactive ?? true;
        this._activatable = this.reactive && options.activate !== false;
    }
    setIcon(icon) { this.icon_name = icon; }
    activate(event) {
        if (!this._activatable) return;
        this.emit('activate', event);
    }
}
/** GNOME 50 PopupBaseMenuItem only accepts a params object. */
class BaseMenuItem extends MenuItem {
    constructor(params = {}) { super(params.label ?? '', params); }
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
    PopupImageMenuItem: MenuItem, PopupBaseMenuItem: BaseMenuItem};
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
    TEXT: {ru: {volume: 'Громкость', mute: 'Выключить звук', unmute: 'Включить звук',
        open: 'Эквалайзер и маршруты', applicationUnavailable: 'Приложение недоступно'}},
    Clutter: {ActorAlign: {CENTER: 0}},
    boxLayout: properties => new Actor(properties), outputIcon: () => 'audio-speakers-symbolic',
    orderedQuickGroups, visibleBrowserTabs, visibleMixerInputs, visiblePinMixerInputs, menuLevelRows, allQuickPins,
    menuStructureSignature, captureMenuViewport, restoreMenuViewport,
    controlPosition, controlValueAt, formatControlValue, controlIcon: () => 'audio-x-generic-symbolic',
    removeMenuItem, reorderMenuItems,
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
assert.equal(routeCard.actor.styleClasses.includes('audio-matrix-route-card'), true,
    'The route card keeps its geometry class');
assert.equal(routeCard.actor.styleClasses.includes('quick-toggle-menu'), true,
    'The route card uses the native themed surface');
assert.equal(monitor._activatable, true,
    'A route row is activatable so the GNOME 50 ClickGesture toggles it');
monitor.activate({});
assert.deepEqual(commands.pop(), ['setRouteEnabled', {routeId: 'main', switchNodeId: 'multi', linkId: 'in->monitor', enabled: false}]);
assert.equal(monitor.stoppedSignal, 'activate',
    'A route toggle suppresses the after-handler that would close the top menu');
const reference = groups._pinEntries.get(pin.id).routeRef;
links.update(reference, {...routeControl, value: ['in->vibro']});
assert.equal(monitor.children.at(-1).get_child().visible, false);
assert.equal(vibration.children.at(-1).get_child().visible, true);
groups.render(column, state, state.quickPins, {});
assert.equal(groups._pinEntries.get(pin.id).wrapper, firstPin, 'A route value change preserves the focused row');
links.update(reference, {...routeControl, value: ['in->vibro'], options: [routeControl.options[0], {...routeControl.options[1], available: false}]});
assert.equal(vibration.reactive, false);
assert.equal(vibration.children.at(-1).get_child().visible, false, 'An offline output cannot look actively routed');

const appCommands = [];
const fakeRows = {
    createLevelRow(options) { const item = new MenuItem(); item.options = options; item.tabs = new Section(); return item; },
    updateLevelRow(item, options) { item.options = {...item.options, ...options}; },
    childSection(item) { return item.tabs; },
};
const appRenderer = new Applications({rows: fakeRows, send: (name, value) => appCommands.push([name, value])});
const appSection = new Section();
const browserTab = {browser: 'chrome', instanceId: 'instance-1', profileId: 'default', sessionToken: 'session-1',
    title: 'Discord', volume: .3, muted: false, available: true};
const appPin = {id: 'app-source', routeId: 'main', nodeId: 'apps', view: 'EXPANDED', expanded: true};
const appA = {id: 'chrome', name: 'Chrome', volume: .7, streams: [{id: 'stream-1'}], browserTabs: [browserTab]};
appRenderer.render(appPin, [appA], {}, appSection);
const appRow = appSection.items[0];
assert.equal(appRow.options.expanded, false,
    'A browser card saved as EXPANDED still starts collapsed');
assert.deepEqual(appRenderer.rowsForPin(appPin.id).map(row => row.id), ['app:app-source:chrome'],
    'Nested browser tabs stay hidden while the card is collapsed');
assert.equal(appRow.options.hasChildren, true, 'The browser card advertises its nested tabs');
appRow.options.onExpand();
assert.equal(appRow.options.expanded, true, 'Expanding the card is a local session choice');
assert.deepEqual(appCommands.pop(), ['set_quick_pin_view', 'app-source\tEXPANDED'],
    'Expanding a browser card asks the app to store the expanded view');
assert.equal(appRenderer.rowsForPin(appPin.id).some(row => row.id.startsWith('browser-tab:')), true,
    'Expanding the card reveals its nested tabs');
appRenderer.render(appPin, [{...appA, streams: [...appA.streams, {id: 'stream-2'}]}], {}, appSection);
assert.equal(appSection.items[0], appRow, 'New streams must not replace the application card');
assert.equal(appSection.items.length, 1, 'Technical PipeWire streams are not separate application cards');
assert.equal(appRow.options.expanded, true,
    'An ordinary state update keeps the expansion the user chose');
appRenderer.render({...appPin, view: 'COMPACT', expanded: false}, [appA], {}, appSection);
assert.equal(appRow.options.expanded, false, 'A confirmed COMPACT view collapses the card');
assert.deepEqual(appRenderer.rowsForPin(appPin.id).map(row => row.id), ['app:app-source:chrome'],
    'A collapsed card hides its tabs again');
appRenderer.render({...appPin, view: 'EXPANDED', expanded: true}, [appA], {}, appSection);
assert.equal(appRow.options.expanded, true, 'A later confirmed EXPANDED view reopens the card');
appRow.options.onExpand();
assert.equal(appRow.options.expanded, false);
assert.deepEqual(appCommands.pop(), ['set_quick_pin_view', 'app-source\tCOMPACT'],
    'Collapsing a browser card asks the app to store the compact view');

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

    // The old branded heading is replaced by a compact trailing gear action only.
    const headerSection = new Section();
    const opened = [];
    const headerRows = new Rows({section: headerSection, getLanguage: () => 'ru', send() {},
        queueSliderCommand: (_key, value, callback) => callback(value),
        onOpen: available => opened.push(available), onDragBegin() {}, onDragEnd() {}});
    const openAction = headerRows.addOpenAction({}, true);
    assert.equal(headerSection.items.length, 1, 'The panel adds exactly one open-action row');
    const [spacer, gear] = openAction.children[0].children;
    assert.equal(spacer.x_expand, true, 'An expanding spacer pushes the gear to the trailing edge');
    assert.equal(gear, openAction._audioMatrixOpenButton, 'The row contains the existing gear button');
    assert.equal(gear.accessible_name, 'Эквалайзер и маршруты', 'The gear keeps its accessible label');
    assert.equal(gear.child.icon_name, 'preferences-system-symbolic', 'The gear keeps its shell icon');
    const headerTexts = [];
    const collectText = actor => {
        if (typeof actor.text === 'string') headerTexts.push(actor.text);
        for (const child of actor.children || []) collectText(child);
    };
    collectText(openAction);
    assert.deepEqual(headerTexts, [], 'The open action carries no branded title or subtitle text');
    gear.emit('clicked');
    assert.deepEqual(opened, [true], 'The gear opens with the current availability');
    headerRows.updateOpenAction(openAction, {}, false);
    gear.emit('clicked');
    assert.deepEqual(opened, [true, false], 'The gear forwards the latest availability');

    const card = headerRows.addLevelRow({id: 'card', name: 'Chrome', value: 0.5, muted: false, available: true});
    assert.equal(card.styleClasses.includes('audio-matrix-card'), true, 'Cards keep their geometry class');
    assert.equal(card.styleClasses.includes('quick-toggle-menu'), true,
        'Cards use the native themed surface instead of a hardcoded black background');
    const nestedCard = headerRows.createLevelRow({id: 'nested', nested: true, value: 0.5, muted: false});
    assert.equal(nestedCard.styleClasses.includes('audio-matrix-tab'), true, 'Nested tab rows keep their class');
    assert.equal(nestedCard.styleClasses.includes('quick-toggle-menu'), false,
        'Nested tab rows do not become full themed cards');

    // A pinned mixer that only references archived generator instances must not
    // leave a heading, a placeholder or an empty section behind.
    const QuickControls = await loadModule('menu-quick-controls.js');
    const Matrix = await loadModule('menu-matrix.js');
    const Disclosure = await loadModule('menu-disclosure.js');
    fixture.AudioMatrixMenuQuickControls = QuickControls;
    fixture.AudioMatrixMenuMatrix = Matrix;
    fixture.AudioMatrixMenuMixer = Mixer;
    fixture.AudioMatrixMenuDisclosure = Disclosure;
    const Pinned = await loadModule('menu-pinned-controls.js');
    const pinned = new Pinned({rows, links, send() {}, registerLevelRow() {}});
    const archivedTarget = new Section();
    pinned.render({id: 'mixer-archived', name: 'Микшер', routeId: 'main', nodeId, controls: [],
        mixerInputs: [{id: 'generated-mixer-input-9', name: 'Проигрыватель', generated: true, available: false,
            volume: 0.4, muted: false}]}, {}, archivedTarget);
    assert.equal(archivedTarget.items.length, 0,
        'A mixer pin with only archived inputs renders no heading or placeholder');
    const staticTarget = new Section();
    pinned.render({id: 'mixer-static', name: 'Микшер', routeId: 'main', nodeId, controls: [],
        mixerInputs: [{id: 'applications', name: 'Все приложения', volume: 1, muted: false, available: false}]},
    {}, staticTarget);
    assert.equal(staticTarget.items.length, 2,
        'A static offline mixer input keeps its heading and its content section');
    assert.equal(staticTarget.items[0].label.text, 'Микшер', 'The surviving mixer keeps its name');
    assert.equal(staticTarget.items[1].actor.visible, false,
        'A pinned mixer section starts collapsed on the first render');
    assert.equal(staticTarget.items[1].items.length, 1,
        'The static offline mixer input still renders its card');
    assert.equal(staticTarget.items[1].items[0]._audioMatrixCommandTarget.inputId, 'applications',
        'The surviving mixer row keeps its exact input identity');
}

delete globalThis.audioMatrixRendererFixture;
console.log('Native card behavior: exact routing, offline state, stable application cards and scroll anchors');
