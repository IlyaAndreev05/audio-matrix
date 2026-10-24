import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {allQuickPins, orderedQuickGroups, visiblePinMixerInputs} from '../gnome/audio-matrix-quick-settings/menu-state.js';
import {removeMenuItem, reorderMenuItems} from '../gnome/audio-matrix-quick-settings/menu-row-utils.js';

// The GNOME 50 PopupBaseMenuItem takes a params object, defaults reactive and
// activate to true, and sets _activatable = reactive && activate. Both the
// Clutter.ClickGesture and the key-press handler bail out unless _activatable,
// so the stub routes activation through the same gate and keeps the real
// signal arity: 'activate': {param_types: [Clutter.Event.$gtype]}.
const CONNECT_AFTER = 1;
const SIGNAL_ARITY = {activate: 1};

class Actor {
    constructor(properties = {}) { Object.assign(this, properties); this.visible = true; this.parent = null; this.children = []; }
    get_parent() { return this.parent; }
    set_style() {}
    add_style_class_name() {}
    add_child(child) { this.children.push(child); }
    remove_child(child) { if (child.parent === this) child.parent = null; }
}
class Label {
    constructor(text = '') { this.text = typeof text === 'object' ? text.text || '' : text; }
    set_width() {}
}
class MenuItem {
    constructor(label = '', options = {}) {
        this.label = {text: label};
        this.actor = new Actor();
        this.handlers = [];
        this.states = [];
        this.children = [];
        this.options = options;
        this.reactive = options.reactive ?? true;
        this._activatable = this.reactive && options.activate !== false;
        this.gtypeName = 'Gjs_ui_popupMenu_PopupMenuItem';
        this._parent = null;
    }
    set_style() {}
    add_style_class_name() {}
    add_accessible_state(state) { this.states.push(state); }
    remove_accessible_state(state) { this.states = this.states.filter(value => value !== state); }
    setIcon(icon) { this.icon_name = icon; }
    setOrnament(value) { this.ornament = value; }
    connect(signal, callback, flags = 0) {
        this.handlers.push({signal, callback, after: !!(flags & CONNECT_AFTER)});
        return this.handlers.length;
    }
    emit(signal, ...args) {
        const arity = SIGNAL_ARITY[signal];
        if (arity !== undefined && args.length < arity)
            throw new Error(`Signal '${signal}' on ${this.gtypeName} requires ${arity} args got ${args.length}`);
        this._stopped = null;
        for (const handler of this.handlers.filter(item => item.signal === signal && !item.after))
            handler.callback(...args);
        if (this._stopped === signal) return;
        for (const handler of this.handlers.filter(item => item.signal === signal && item.after))
            handler.callback(...args);
    }
    stop_emission_by_name(signal) { this._stopped = signal; }
    // ClickGesture.recognize() and vfunc_key_press_event() only reach activate()
    // when _activatable is true; activate() itself emits with the event.
    activate(event) {
        if (!this._activatable) return;
        this.emit('activate', event);
    }
    set_sensitive(value) { this.sensitive = value; }
    add_child(child) { this.children.push(child); }
    _setParent(parent) { this._parent = parent; }
    _getTopMenu() { return this._parent ? this._parent._getTopMenu() : this; }
}
/** GNOME 50 PopupBaseMenuItem only accepts a params object. */
class BaseMenuItem extends MenuItem {
    constructor(params = {}) { super(params.label ?? '', params); }
}
class ImageMenuItem extends BaseMenuItem {
    constructor(label, icon, options = {}) {
        super(options);
        this.label.text = label;
        this.gtypeName = 'Gjs_ui_popupMenu_PopupImageMenuItem';
        this.setIcon(icon);
    }
}
class Section {
    constructor() {
        this.actor = new Actor();
        this.items = [];
        this.box = {};
        this.closed = false;
        this._parent = null;
    }
    addMenuItem(item) {
        if (item.actor?.parent) item.actor.parent.remove_child(item.actor);
        this.items.push(item);
        if (item.actor) item.actor.parent = this.actor;
        item._setParent?.(this);
        if (item instanceof MenuItem) {
            // GNOME 50 closes the top menu once the item activation handlers ran.
            item.connect('activate', () => this.itemActivated(), CONNECT_AFTER);
        }
    }
    removeMenuItem(item) {
        const index = this.items.indexOf(item);
        if (index >= 0) this.items.splice(index, 1);
        if (item.actor?.parent === this.actor) item.actor.parent = null;
    }
    removeAll() { for (const item of [...this.items]) this.removeMenuItem(item); }
    _setParent(parent) { this._parent = parent; }
    _getTopMenu() { return this._parent ? this._parent._getTopMenu() : this; }
    itemActivated() { this._getTopMenu().closed = true; }
}
const PopupMenu = {
    PopupMenuSection: Section,
    PopupMenuItem: MenuItem,
    PopupImageMenuItem: ImageMenuItem,
    PopupBaseMenuItem: BaseMenuItem,
    PopupSwitchMenuItem: MenuItem,
    PopupSubMenuMenuItem: MenuItem,
    PopupSeparatorMenuItem: MenuItem,
    Ornament: {DOT: 1, NO_DOT: 0},
};
const Atk = {StateType: {EXPANDABLE: 1, EXPANDED: 2}, Role: {RADIO_BUTTON: 1}};
const St = {Icon: Actor, Label};
const Clutter = {ActorAlign: {CENTER: 0}};
globalThis.audioMatrixGroupFixture = {PopupMenu, Atk, St, Clutter, allQuickPins, orderedQuickGroups, visiblePinMixerInputs,
    removeMenuItem, reorderMenuItems};

async function loadModule(name) {
    let source = await readFile(new URL(`../gnome/audio-matrix-quick-settings/${name}`, import.meta.url), 'utf8');
    source = source.replace(/^import .*;\n/gm, '');
    source = `const {PopupMenu, Atk, St, Clutter, allQuickPins, orderedQuickGroups, visiblePinMixerInputs, removeMenuItem, reorderMenuItems} = globalThis.audioMatrixGroupFixture;\n${source}`;
    return (await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)).default;
}
const Disclosure = await loadModule('menu-disclosure.js');
const Groups = await loadModule('menu-groups.js');

const commands = [];
const disclosures = new Disclosure();
const groups = new Groups({
    send: (name, value) => commands.push([name, value]),
    disclosures,
    applications: {render() {}},
    links: {render() {}},
    mixer: {render() {}},
    output: {render() {}},
    renderControls() {},
    registerLinkRow() {},
});
const column = new Section();
const language = {unmute: 'Unmute', mute: 'Mute'};

function render(state) {
    groups.render(column, state, state.quickPins || [], language);
    disclosures.retain(state);
    disclosures.sync(state);
}
function stateWith(collapsed, extraPin = false) {
    const quickPins = [
        {id: 'pin-apps', name: 'Составной блок', nodeType: 'composite', groupId: 'applications', order: 0,
            view: 'EXPANDED', expanded: true, children: []},
        {id: 'pin-out', name: 'Выход', nodeType: 'device_slot', groupId: 'outputs', order: 0,
            output: {id: 'rear-lineout', name: 'Выход', available: true}},
        {id: 'pin-route', name: 'Маршруты', nodeType: 'switch', groupId: 'routing', order: 0,
            routeControl: {id: 'route:main:multi', routeId: 'main', nodeId: 'multi', multiple: true,
                value: ['in->monitor'], options: [{id: 'in->monitor', label: 'Монитор', available: true}]}},
    ];
    if (extraPin)
        quickPins.push({id: 'pin-extra', name: 'Новый источник', nodeType: 'source', groupId: 'applications', order: 1});
    return {
        quickGroups: [
            {id: 'applications', name: 'Приложения', order: 0, collapsed},
            {id: 'outputs', name: 'Выходные устройства', order: 1, collapsed: true},
            {id: 'routing', name: 'Маршрутизация', order: 2, collapsed: false},
        ],
        quickPins,
        quickControls: [],
    };
}
function activate(item) {
    try {
        item.activate({type: 'button-release'});
        return null;
    } catch (error) {
        return error;
    }
}

// The stub itself must model GNOME 50: activate:false used to disable the
// ClickGesture, which is why group headers never reacted to pointer input.
{
    let inertFired = false;
    const inert = new PopupMenu.PopupBaseMenuItem({activate: false, can_focus: true});
    inert.connect('activate', () => { inertFired = true; });
    inert.activate({});
    assert.equal(inert._activatable, false, 'activate:false makes a popup item non-activatable');
    assert.equal(inertFired, false, 'A non-activatable item never reaches its activate handler');
    let clickableFired = false;
    const clickable = new PopupMenu.PopupBaseMenuItem({can_focus: true});
    clickable.connect('activate', () => { clickableFired = true; });
    clickable.activate({});
    assert.equal(clickable._activatable, true, 'An item without activate:false stays activatable');
    assert.equal(clickableFired, true, 'An activatable item runs its activate handler');
}

render(stateWith(true));
const applications = groups._groupEntries.get('applications');
assert.equal(applications.header._activatable, true,
    'The group header is activatable so the GNOME 50 ClickGesture and key handling reach it');
assert.equal(applications.content.actor.visible, false,
    'A group the state reports as collapsed starts hidden');
assert.equal(groups._groupEntries.get('outputs').content.actor.visible, false,
    'Every collapsed group starts hidden');
assert.equal(groups._groupEntries.get('routing').content.actor.visible, false,
    'A group saved as expanded still starts collapsed');

const failure = activate(applications.header);
assert.equal(failure, null,
    `Activating a group header must not fail: ${failure?.message ?? ''}`);
assert.equal(applications.content.actor.visible, true,
    'Activating the header of a collapsed group opens its content');
assert.deepEqual(commands.pop(), ['set_quick_group_collapsed', 'applications\tfalse'],
    'Opening a group asks the app to store collapsed=false');
assert.equal(column.closed, false, 'Toggling a group keeps the Quick Settings panel open');
assert.equal(groups._groupEntries.get('outputs').content.actor.visible, false,
    'Opening one group leaves the other collapsed groups closed');
assert.equal(groups._groupEntries.get('routing').content.actor.visible, false,
    'Opening one group does not auto-expand a group saved as expanded');

render(stateWith(true));
assert.equal(applications.content.actor.visible, true,
    'The group stays open when the next state still reports collapsed=true');
assert.equal(groups._groupEntries.get('outputs').content.actor.visible, false,
    'A rejected command does not open the other collapsed groups');

render(stateWith(true, true));
assert.equal(applications.content.actor.visible, true,
    'The group stays open when a rejected command arrives together with a rebuilt menu');

render(stateWith(false, true));
assert.equal(applications.content.actor.visible, true,
    'The group stays open when the next state reports collapsed=false');

const collapseFailure = activate(applications.header);
assert.equal(collapseFailure, null,
    `Activating a group header must not fail: ${collapseFailure?.message ?? ''}`);
assert.equal(applications.content.actor.visible, false,
    'Activating the header of an open group collapses it again');
assert.deepEqual(commands.pop(), ['set_quick_group_collapsed', 'applications\ttrue'],
    'Collapsing a group asks the app to store collapsed=true');

render(stateWith(false));
assert.equal(applications.content.actor.visible, false,
    'An ordinary state update does not reopen a group the user closed locally');

const pin = groups._pinEntries.get('pin-apps');
assert.equal(pin.composite.content.actor.visible, false,
    'A composite pin saved as EXPANDED still starts collapsed');
assert.equal(pin.composite.header._activatable, true,
    'The composite pin header stays activatable');
const pinFailure = activate(pin.composite.header);
assert.equal(pinFailure, null,
    `Activating a pin header must not fail: ${pinFailure?.message ?? ''}`);
assert.equal(pin.composite.content.actor.visible, true, 'Activating a composite header expands its children');
assert.deepEqual(commands.pop(), ['set_quick_pin_view', 'pin-apps\tEXPANDED'],
    'Expanding a pin asks the app to store the expanded view');

const pinCollapseFailure = activate(pin.composite.header);
assert.equal(pinCollapseFailure, null,
    `Collapsing a pin header must not fail: ${pinCollapseFailure?.message ?? ''}`);
assert.equal(pin.composite.content.actor.visible, false, 'A second activation collapses the pin again');
assert.deepEqual(commands.pop(), ['set_quick_pin_view', 'pin-apps\tCOMPACT'],
    'Collapsing a pin asks the app to store the compact view');
render(stateWith(false));
assert.equal(pin.composite.content.actor.visible, false,
    'An ordinary state update does not reopen a pin the user collapsed locally');

// A section without renderable controls must not reserve a header or a row.
const emptySources = {quickGroups: [{id: 'sources', name: 'Источники', order: 0}], quickPins: [], quickControls: []};
render(emptySources);
assert.equal(groups._groupEntries.has('sources'), false,
    'A group without renderable pins renders no header at all');
assert.equal(column.items.length, 0, 'An empty group leaves no stale section in the menu');
const sourcePin = {id: 'pin-src', name: 'Источник', nodeType: 'source', groupId: 'sources', order: 0,
    controls: [{id: 'control-1', kind: 'slider', label: 'Уровень', value: 1}]};
render({...emptySources, quickPins: [sourcePin]});
const sources = groups._groupEntries.get('sources');
assert.ok(sources, 'The group appears again once a renderable control exists');
assert.equal(sources.empty, undefined, 'No empty placeholder row is created for a group');
assert.equal(groups._pinEntries.has('pin-src'), true, 'The renderable control is kept in the group');
render(emptySources);
assert.equal(groups._groupEntries.has('sources'), false,
    'The group disappears when its last renderable control is gone');
assert.equal(column.items.length, 0, 'The keyed cleanup removed the vanished group');

// Archived generator instances keep recovering their saved gains but must not
// materialise as a live mixer heading or an empty section.
const eventsGroup = {id: 'events', name: 'События', order: 0};
const archivedMixer = {id: 'pin-mixer', name: 'Основной микшер', nodeType: 'mixer', groupId: 'events',
    mixerInputs: [{id: 'generated-mixer-input-9', name: 'Проигрыватель', generated: true, available: false,
        volume: 0.4, muted: false}], controls: []};
render({quickGroups: [eventsGroup], quickPins: [archivedMixer], quickControls: []});
assert.equal(groups._groupEntries.has('events'), false,
    'A mixer pin with only archived inputs renders nothing');
assert.equal(column.items.length, 0, 'An archived-only mixer does not leave an empty group');
const staticMixer = {...archivedMixer, mixerInputs: [{id: 'applications', name: 'Все приложения',
    volume: 1, muted: false, available: false}]};
render({quickGroups: [eventsGroup], quickPins: [staticMixer], quickControls: []});
assert.equal(groups._groupEntries.has('events'), true,
    'A static offline mixer input keeps its section visible');
assert.equal(groups._pinEntries.has('pin-mixer'), true, 'The static mixer pin stays in the menu');

delete globalThis.audioMatrixGroupFixture;
console.log('Groups and pins start collapsed, open on activation, keep local choice and hide empty sections');
