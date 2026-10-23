import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {allQuickPins, orderedQuickGroups} from '../gnome/audio-matrix-quick-settings/menu-state.js';
import {removeMenuItem, reorderMenuItems} from '../gnome/audio-matrix-quick-settings/menu-row-utils.js';

// The GNOME 50 popup menu item declares its activation signal with a required
// event argument ('activate': {param_types: [Clutter.Event.$gtype]}). GJS
// validates the argument count before the signal is dispatched and throws
// "Signal 'activate' on ... requires 1 args got 0" when a handler emits the
// signal without the event, so the stub below enforces the very same contract
// as the live shell.
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
    // GNOME 50: PopupBaseMenuItem.activate(event) emits 'activate' with the event.
    activate(event) { this.emit('activate', event); }
    set_sensitive(value) { this.sensitive = value; }
    add_child(child) { this.children.push(child); }
    _setParent(parent) { this._parent = parent; }
    _getTopMenu() { return this._parent ? this._parent._getTopMenu() : this; }
}
class ImageMenuItem extends MenuItem {
    constructor(label, icon) {
        super(label);
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
    PopupBaseMenuItem: MenuItem,
    PopupSwitchMenuItem: MenuItem,
    PopupSubMenuMenuItem: MenuItem,
    PopupSeparatorMenuItem: MenuItem,
    Ornament: {DOT: 1, NO_DOT: 0},
};
const Atk = {StateType: {EXPANDABLE: 1, EXPANDED: 2}, Role: {RADIO_BUTTON: 1}};
const St = {Icon: Actor, Label};
const Clutter = {ActorAlign: {CENTER: 0}};
globalThis.audioMatrixGroupFixture = {PopupMenu, Atk, St, Clutter, allQuickPins, orderedQuickGroups, removeMenuItem, reorderMenuItems};

async function loadModule(name) {
    let source = await readFile(new URL(`../gnome/audio-matrix-quick-settings/${name}`, import.meta.url), 'utf8');
    source = source.replace(/^import .*;\n/gm, '');
    source = `const {PopupMenu, Atk, St, Clutter, allQuickPins, orderedQuickGroups, removeMenuItem, reorderMenuItems} = globalThis.audioMatrixGroupFixture;\n${source}`;
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
            view: 'COMPACT', children: []},
        {id: 'pin-out', name: 'Выход', nodeType: 'device_slot', groupId: 'outputs', order: 0},
        {id: 'pin-route', name: 'Маршруты', nodeType: 'switch', groupId: 'routing', order: 0},
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

render(stateWith(true));
const applications = groups._groupEntries.get('applications');
assert.equal(applications.content.actor.visible, false,
    'A group the state reports as collapsed starts hidden');
assert.equal(groups._groupEntries.get('outputs').content.actor.visible, false,
    'Every collapsed group starts hidden');

const failure = activate(applications.header);
assert.equal(failure, null,
    `Activating a group header must not fail: ${failure?.message ?? ''}`);
assert.equal(applications.content.actor.visible, true,
    'Activating the header of a collapsed group opens its content');
assert.deepEqual(commands.pop(), ['set_quick_group_collapsed', 'applications\tfalse'],
    'Opening a group asks the app to store collapsed=false');
assert.equal(column.closed, false, 'Toggling a group keeps the Quick Settings panel open');

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

const pin = groups._pinEntries.get('pin-apps');
const pinFailure = activate(pin.composite.header);
assert.equal(pinFailure, null,
    `Activating a pin header must not fail: ${pinFailure?.message ?? ''}`);
assert.equal(pin.composite.content.actor.visible, true, 'Activating a composite header expands its children');
assert.deepEqual(commands.pop(), ['set_quick_pin_view', 'pin-apps\tEXPANDED'],
    'Expanding a pin asks the app to store the expanded view');

delete globalThis.audioMatrixGroupFixture;
console.log('Group and pin headers expand on activation and survive the next state update');
