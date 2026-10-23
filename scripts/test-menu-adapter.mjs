import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

const base = new URL('../gnome/audio-matrix-quick-settings/', import.meta.url);
const leaseUrl = new URL('menu-lease.js', base).href;
const source = (await readFile(new URL('volume-menu-adapter.js', base), 'utf8'))
    .replace("import * as Main from 'resource:///org/gnome/shell/ui/main.js';",
        'const Main = globalThis.audioMatrixShellFixture;')
    .replace("import St from 'gi://St';", 'const St = globalThis.audioMatrixStFixture;')
    .replace("import Meta from 'gi://Meta';", 'const Meta = globalThis.audioMatrixMetaFixture;')
    .replace(/import \{[^}]*\} from '\.\/menu-lease\.js';/,
        `import {MenuButtonLease, MenuItemsLease, MenuViewportLease} from ${JSON.stringify(leaseUrl)};`);
let Adapter;
let viewportHeightForWorkArea;

const workArea = {x: 0, y: 0, width: 1600, height: 1000};
const shell = {panel: {statusArea: {quickSettings: {}}}, notifyError: () => {}};
const stFixture = {ThemeContext: {get_for_stage: () => ({scale_factor: 1})}};
const laterCallbacks = new Map();
let nextLaterId = 1;
globalThis.audioMatrixShellFixture = shell;
globalThis.audioMatrixStFixture = stFixture;
globalThis.audioMatrixMetaFixture = {LaterType: {BEFORE_REDRAW: 1}};
globalThis.display = {
    get_current_monitor: () => 0,
};
globalThis.workspace_manager = {
    get_active_workspace: () => ({
        get_work_area_for_monitor: () => workArea,
    }),
};
globalThis.stage = {};
globalThis.compositor = {
    get_laters: () => ({
        add: (_type, callback) => {
            const id = nextLaterId++;
            laterCallbacks.set(id, callback);
            return id;
        },
        remove: id => laterCallbacks.delete(id),
    }),
};

({default: Adapter} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`));
({viewportHeightForWorkArea} = await import(leaseUrl));

function runLaters() {
    const callbacks = [...laterCallbacks.values()];
    laterCallbacks.clear();
    for (const callback of callbacks) callback();
}

class Emitter {
    constructor() {
        this._handlers = new Map();
        this._nextId = 1;
        this.connectCalls = [];
        this.disconnectCalls = [];
    }

    connect(signal, handler) {
        const id = this._nextId++;
        this._handlers.set(id, {signal, handler});
        this.connectCalls.push(signal);
        return id;
    }

    disconnect(id) {
        if (!this._handlers.delete(id))
            throw new Error(`No handler ${id} to disconnect`);
        this.disconnectCalls.push(id);
    }

    emit(signal, ...args) {
        for (const entry of [...this._handlers.values()]) {
            if (entry.signal === signal)
                entry.handler(this, ...args);
        }
    }

    handlersFor(signal) {
        return [...this._handlers.values()].filter(entry => entry.signal === signal).length;
    }
}

class Actor {
    constructor(name, {styleClass = '', height = 0} = {}) {
        this.name = name;
        this.children = [];
        this.parent = null;
        this._visible = true;
        this.visibilityWrites = [];
        this.height = height;
        this.style_class = styleClass;
        this.style = null;
        this.stageY = 0;
        this._delegate = null;
    }

    get visible() {
        return this._visible;
    }

    set visible(value) {
        this.visibilityWrites.push(value);
        this._visible = value;
    }

    add_child(actor) {
        this.children.push(actor);
        actor.parent = this;
        return actor;
    }

    // Clutter's "below" is the lower stacking index, which the vertical box
    // layout paints above the sibling, i.e. addMenuItem(item, n) inserts at n.
    insert_child_below(actor, sibling) {
        const index = this.children.indexOf(sibling);
        if (index < 0)
            throw new Error(`${sibling?.name} is not a child of ${this.name}`);
        this.children.splice(index, 0, actor);
        actor.parent = this;
    }

    remove_child(actor) {
        const index = this.children.indexOf(actor);
        if (index < 0)
            throw new Error(`${actor?.name} is not a child of ${this.name}`);
        this.children.splice(index, 1);
        actor.parent = null;
    }

    get_children() {
        return [...this.children];
    }

    get_parent() {
        return this.parent;
    }

    get_height() {
        return this.height;
    }

    get_transformed_position() {
        return [0, this.stageY];
    }

    set_style(style) {
        this.style = style;
    }

    has_style_class_name(name) {
        return this.style_class.split(/\s+/).includes(name);
    }
}

function menuSection(name, actor) {
    const section = {
        actor,
        isMenuItem: true,
        connections: new Set(),
        _parent: null,
        destroyed: false,
        _setParent(parent) {
            this._parent = parent;
        },
        connectObject(...args) {
            this.connections.add(args.at(-1));
        },
        disconnectObject(source) {
            this.connections.delete(source);
        },
        destroy() {
            assert.equal(this.destroyed, false, `${name} must be destroyed exactly once`);
            this.destroyed = true;
        },
    };
    actor._delegate = section;
    return section;
}

class Menu extends Emitter {
    constructor() {
        super();
        this.box = new Actor('menu-box');
        this.length = 0;
        this.isOpen = false;
        this.objectConnections = new Set();
        this.header = new Actor('submenu-header', {height: 56});
        this.box.add_child(this.header);
    }

    _getMenuItems() {
        return this.box.children
            .map(child => child._delegate)
            .filter(item => item?.isMenuItem);
    }

    addMenuItem(item, position) {
        const items = this._getMenuItems();
        if (position === undefined)
            this.box.add_child(item.actor);
        else if (position < items.length)
            this.box.insert_child_below(item.actor, items[position].actor);
        else
            this.box.add_child(item.actor);
        // PopupMenuBase.addMenuItem() connects both emitters in both directions.
        this.objectConnections.add(item);
        item.connectObject('active-changed', () => {}, this);
        item._setParent(this);
        this.length++;
        return item;
    }

    connectObject(...args) {
        this.objectConnections.add(args.at(-1));
    }

    disconnectObject(source) {
        this.objectConnections.delete(source);
    }

    connect(signal, handler) {
        assert.equal(signal, 'open-state-changed', `unexpected menu signal ${signal}`);
        return super.connect(signal, handler);
    }
}

class Output extends Emitter {
    constructor(menu, deviceSection, {menuEnabled = false} = {}) {
        super();
        this.menu = menu;
        this._deviceSection = deviceSection;
        this._menuEnabled = menuEnabled;
        this.nativeMenuEnabled = menuEnabled;
        this.syncCalls = 0;
        this.menuEnabledWrites = [];
    }

    get menuEnabled() {
        return this._menuEnabled;
    }

    set menuEnabled(value) {
        this.menuEnabledWrites.push(value);
        if (value === this._menuEnabled)
            return;
        this._menuEnabled = value;
        this.emit('notify::menu-enabled');
    }

    connect(signal, handler) {
        assert.equal(signal, 'notify::menu-enabled', `unexpected output signal ${signal}`);
        return super.connect(signal, handler);
    }

    _sync() {
        this.syncCalls++;
        this.menuEnabled = this.nativeMenuEnabled;
    }
}

function buildMenu() {
    const menu = new Menu();
    const deviceActor = new Actor('device-section-actor', {height: 40});
    const deviceSection = menuSection('device-section', deviceActor);
    menu.addMenuItem(deviceSection);
    deviceActor.add_child(new Actor('device-one', {height: 32}));
    deviceActor.add_child(new Actor('device-two', {height: 32}));
    const separator = menuSection('separator', new Actor('separator', {height: 1}));
    menu.addMenuItem(separator);
    const settings = menuSection('settings', new Actor('settings', {height: 40}));
    menu.addMenuItem(settings);
    return {menu, deviceSection, deviceActor, separator, settings};
}

function audioSection() {
    const actor = new Actor('audio-matrix', {styleClass: 'audio-matrix-panel'});
    const scroll = new Actor('audio-matrix-scroll', {styleClass: 'audio-matrix-scroll', height: 300});
    actor.add_child(scroll);
    const section = menuSection('audio-matrix-section', actor);
    return {section, actor, scroll};
}

function itemNames(menu) {
    return menu.box.children.map(child => child.name);
}

// --- Missing indicator during Shell startup ---------------------------------
const content = audioSection();
const adapter = new Adapter(content.section);
assert.equal(content.actor.visible, false, 'The panel starts hidden and never adds a bottom block');
assert.equal(adapter.attach(), false);
assert.equal(content.section.destroyed, false);
assert.equal(shell.notifyError !== undefined, true);

const first = buildMenu();
const output = new Output(first.menu, first.deviceSection);
shell.panel.statusArea.quickSettings._volumeOutput = {_output: output};

// --- Insertion directly before the native device section --------------------
assert.equal(adapter.attach(), true);
assert.deepEqual(itemNames(first.menu),
    ['submenu-header', 'audio-matrix', 'device-section-actor', 'separator', 'settings'],
    'The full section is inserted immediately before the native device section');
assert.equal(first.menu.header.visible, true, 'Native submenu header stays visible');
assert.ok(first.menu.objectConnections.has(content.section));
assert.ok(content.section.connections.has(first.menu));
assert.equal(first.menu.length, 4);
assert.equal(output.menuEnabled, true, 'The volume arrow is leased open');
assert.equal(output.handlersFor('notify::menu-enabled'), 1);

first.deviceActor.visibilityWrites.length = 0;
first.menu.header.visibilityWrites.length = 0;
first.separator.actor.visibilityWrites.length = 0;
first.settings.actor.visibilityWrites.length = 0;
for (const child of first.deviceActor.children)
    child.visibilityWrites.length = 0;

assert.equal(adapter.commit(), true);
assert.equal(content.actor.visible, true, 'A successful render reveals the Audio Matrix section');
assert.equal(first.deviceActor.visible, false, 'Only the native device section is hidden');
assert.deepEqual(first.deviceActor.visibilityWrites, [false]);
for (const untouched of [first.menu.header, first.separator.actor, first.settings.actor,
    ...first.deviceActor.children]) {
    assert.deepEqual(untouched.visibilityWrites, [],
        `${untouched.name} keeps its native visibility`);
}
assert.equal(content.scroll.style, 'max-height: 720px;',
    'The viewport is bounded from the monitor work area and theme scale');

// --- Idempotent attachment --------------------------------------------------
assert.equal(adapter.attach(), true);
assert.equal(adapter.commit(), true);
assert.deepEqual(itemNames(first.menu),
    ['submenu-header', 'audio-matrix', 'device-section-actor', 'separator', 'settings']);
assert.equal(first.menu.length, 4, 'Repeated attachment cannot duplicate the section');
assert.deepEqual(first.deviceActor.visibilityWrites, [false],
    'Repeated commit neither re-hides nor recaptures the native actor');
assert.equal(output.handlersFor('notify::menu-enabled'), 1, 'Repeated attach keeps one lease');

// Upstream _addDevice() adds a child inside the permanent device section and
// calls _sync(); the hidden section owns only its own actor, never the children.
const hotplug = new Actor('device-hotplug', {height: 32});
first.deviceActor.add_child(hotplug);
output._sync();
assert.equal(output.menuEnabled, true, 'A hotplug sync cannot hide the leased arrow');
assert.equal(hotplug.visible, true, 'A hotplug child keeps its own visibility');
assert.equal(adapter.commit(), true);
assert.deepEqual(hotplug.visibilityWrites, [], 'Hotplug children are never written');
assert.equal(first.deviceActor.visible, false);

// --- Rollback restores the native menu exactly once -------------------------
const syncCalls = output.syncCalls;
adapter.rollback();
assert.equal(content.actor.visible, false);
assert.equal(first.deviceActor.visible, true, 'Rollback restores the native device section');
assert.equal(output.syncCalls, syncCalls + 1, 'Native sync runs exactly once per lease release');
assert.equal(output.menuEnabled, output.nativeMenuEnabled);
assert.equal(output.handlersFor('notify::menu-enabled'), 0, 'Rollback releases the arrow lease');
assert.equal(laterCallbacks.size, 0, 'Rollback cancels pending viewport work');
const rollbackWrites = first.deviceActor.visibilityWrites.length;
adapter.rollback();
assert.equal(output.syncCalls, syncCalls + 1, 'A repeated rollback never syncs again');
assert.equal(first.deviceActor.visibilityWrites.length, rollbackWrites);

assert.equal(adapter.commit(), true);
assert.equal(first.deviceActor.visible, false);
assert.equal(output.menuEnabled, true, 'A new render leases the arrow again');

// --- Native menu replacement reparents without destroying -------------------
const second = buildMenu();
output.nativeMenuEnabled = true;
output.menu = second.menu;
output._deviceSection = second.deviceSection;
const reattachSync = output.syncCalls;
assert.equal(adapter.attach(), true);
assert.equal(output.syncCalls, reattachSync + 1, 'The old menu lease is released on reparent');
assert.equal(first.menu.box.children.includes(content.actor), false,
    'The section leaves the old menu without being destroyed');
assert.equal(first.menu.objectConnections.has(content.section), false,
    'oldMenu.disconnectObject(section) removes its side of addMenuItem');
assert.equal(content.section.connections.has(first.menu), false,
    'section.disconnectObject(oldMenu) removes its side of addMenuItem');
assert.equal(first.menu.length, 3, 'The old menu length is repaired exactly once');
assert.equal(content.section.destroyed, false, 'Reparenting never destroys the section');
assert.equal(content.section._parent, second.menu);
assert.deepEqual(itemNames(second.menu),
    ['submenu-header', 'audio-matrix', 'device-section-actor', 'separator', 'settings']);
assert.equal(first.deviceActor.visible, true, 'The old native device list becomes visible again');
assert.equal(second.deviceActor.visible, true, 'The new device list stays native until commit');
assert.equal(adapter.commit(), true);
assert.equal(second.deviceActor.visible, false);
assert.equal(first.deviceActor.visible, true, 'Committing in the new menu never re-hides the old one');

// --- Allocation-based refinement and fallbacks ------------------------------
assert.equal(viewportHeightForWorkArea(1000, 1), 720);
assert.equal(viewportHeightForWorkArea(900, 1), 660);
assert.equal(viewportHeightForWorkArea(1000, 2), 260);
assert.equal(viewportHeightForWorkArea(200, 1), 120);
assert.equal(viewportHeightForWorkArea(0, 1), null);
assert.equal(viewportHeightForWorkArea(undefined, 1), null);

workArea.height = 900;
assert.equal(adapter.commit(), true);
assert.equal(content.scroll.style, 'max-height: 660px;',
    'The baseline viewport follows the smaller work area');
content.scroll.stageY = 150;
content.scroll.height = 300;
second.separator.actor.height = 1;
second.settings.actor.height = 40;
second.menu.isOpen = true;
second.menu.emit('open-state-changed', true);
assert.equal(laterCallbacks.size, 1, 'Opening the submenu schedules one allocation check');
runLaters();
assert.equal(content.scroll.style, 'max-height: 697px;',
    'The allocated submenu leaves room for the separator and Sound Settings');

content.scroll.height = 0;
second.menu.emit('open-state-changed', true);
runLaters();
assert.equal(content.scroll.style, 'max-height: 697px;',
    'Without an allocation the previous bounded viewport stays in place');

const savedDisplay = globalThis.display;
const savedWorkspaceManager = globalThis.workspace_manager;
globalThis.display = undefined;
globalThis.workspace_manager = undefined;
adapter.commit();
assert.equal(content.scroll.style, 'max-height: 697px;',
    'A missing display never turns the viewport unbounded');
globalThis.display = savedDisplay;
globalThis.workspace_manager = savedWorkspaceManager;

stFixture.ThemeContext.get_for_stage = () => ({scale_factor: 2});
workArea.height = 1000;
assert.equal(adapter.commit(), true);
assert.equal(content.scroll.style, 'max-height: 260px;',
    'Theme scale is applied before reserving native submenu chrome');
stFixture.ThemeContext.get_for_stage = () => ({scale_factor: 1});

// --- Incompatible native structure ------------------------------------------
const incompatible = buildMenu();
incompatible.menu.box.remove_child(incompatible.deviceSection.actor);
incompatible.menu.box.children.unshift(new Actor('unrelated-native-block'));
output.menu = incompatible.menu;
output._deviceSection = incompatible.deviceSection;
const notifications = [];
shell.notifyError = (title, message) => notifications.push({title, message});
assert.equal(adapter.attach(), false);
assert.equal(notifications.length, 1, 'An incompatible structure reports one Russian error');
assert.match(notifications[0].message, /несовместима/);
assert.equal(incompatible.menu.box.children.includes(content.actor), false,
    'An incompatible structure never receives a bottom block');
assert.equal(content.actor.visible, false);
assert.equal(content.section.destroyed, false);
assert.equal(adapter.attach(), false);
assert.equal(notifications.length, 1, 'The same incompatible structure is reported only once');

// --- Disable destroys exactly once and restores the native list -------------
output.menu = second.menu;
output._deviceSection = second.deviceSection;
assert.equal(adapter.attach(), true);
assert.equal(adapter.commit(), true);
adapter.destroy();
assert.equal(content.section.destroyed, true);
assert.equal(second.deviceActor.visible, true, 'Disable restores the native device section');
assert.equal(second.menu.box.children.includes(content.actor), false);
assert.equal(second.menu.length, 3);
assert.equal(output.handlersFor('notify::menu-enabled'), 0);
assert.equal(second.menu.handlersFor('open-state-changed'), 0);
adapter.destroy();
assert.equal(content.section.destroyed, true, 'A second destroy does not touch the section again');

const unattached = audioSection();
new Adapter(unattached.section).destroy();
assert.equal(unattached.section.destroyed, true,
    'Disable destroys a section that never attached');

console.log('Output submenu adapter inserts before native devices, leases the arrow, hides only devices, ' +
    'reparents without destruction, and bounds its viewport from the work area.');
