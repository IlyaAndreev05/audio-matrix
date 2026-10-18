import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {orderedQuickGroups} from '../gnome/audio-matrix-quick-settings/menu-state.js';

const commands = [];
const draggables = [];
class Icon { constructor(properties) { Object.assign(this, properties); } }
class Label extends Icon {}
class Switch {
    constructor(label, state) { this.label = label; this.state = state; this.handlers = {}; }
    connect(signal, callback) { this.handlers[signal] = callback; return signal; }
}
globalThis.audioMatrixRendererFixture = {
    orderedQuickGroups,
    St: {Label, Icon},
    DND: {
        makeDraggable(actor) { draggables.push(actor); },
        DragMotionResult: {MOVE_DROP: 1, NO_DROP: 0},
    },
    PopupMenu: {PopupSwitchMenuItem: Switch},
    TEXT: {en: {onOff: 'On / Off'}},
};
async function loadModule(name, bindings) {
    const original = await readFile(new URL(`../gnome/audio-matrix-quick-settings/${name}`, import.meta.url), 'utf8');
    const source = `const {${bindings}} = globalThis.audioMatrixRendererFixture;\n`
        + original.replace(/^import .*;\n/gm, '');
    return (await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)).default;
}
const Renderer = await loadModule('menu-renderer.js', 'St, DND, PopupMenu, TEXT');
const Groups = await loadModule('menu-groups.js', 'orderedQuickGroups, St, DND');
const state = {quickGroups: [
    {id: 'output', name: 'Speakers', order: 2},
    {id: 'source', name: 'My arbitrary source name', order: 0},
    {id: 'vibro', name: 'Shared haptics', order: 1},
]};
const context = {
    _getSnapshot: () => ({state, language: 'en'}),
    _send: (name, value) => commands.push([name, value]),
    _rows: {iconButton: (icon, name, callback) => ({icon, name, click: callback})},
    _toggleRows: new Map(),
};
const t = {reorder: 'Drag', moveUp: 'Up', moveDown: 'Down'};
function header(name) { return {label: {text: name}, children: [], add_child(child) { this.children.push(child); }}; }
const first = header(state.quickGroups[1].name);
Groups.prototype._addGroupReordering.call(context, first, 'source', t);
const [handle, up, down] = first.children;
assert.equal(draggables[0], handle, 'The explicit handle must support native Shell dragging');
assert.equal(up.reactive, false, 'First group cannot move above the column');
assert.equal(down.reactive, true);
down.click();
assert.deepEqual(commands.pop(), ['reorder_quick_group', 'source\t1']);
assert.equal(handle._delegate.getDragActor().text, 'My arbitrary source name');
assert.equal(handle._delegate.getDragActorSource(), handle);
assert.equal(first.handleDragOver({quickGroupId: 'vibro'}), 1);
assert.equal(first.handleDragOver({quickGroupId: 'source'}), 0);
assert.equal(first.handleDragOver({}), 0);
assert.equal(first.acceptDrop({quickGroupId: 'vibro'}), true);
assert.deepEqual(commands.pop(), ['reorder_quick_group', 'vibro\t0']);
assert.equal(first.acceptDrop({quickGroupId: 'source'}), false);
assert.equal(first.acceptDrop({}), false);
assert.deepEqual(state.quickGroups.map(group => group.id), ['output', 'source', 'vibro'],
    'Commands must use the shared store rather than mutating a private group copy');
const last = header('Speakers');
Groups.prototype._addGroupReordering.call(context, last, 'output', t);
assert.equal(last.children[2].can_focus, false, 'Last group cannot move below the column');
last.children[1].click();
assert.deepEqual(commands.pop(), ['reorder_quick_group', 'output\t1']);
const target = {addMenuItem(item) { this.item = item; }};
Renderer.prototype._renderToggleControl.call(context,
    {id: 'gate', nodeType: 'gate', label: 'Branch blocked', value: false}, target);
assert.equal(target.item.label, 'On / Off', 'Gate exposes only On/Off, not routing implementation text');
target.item.handlers.toggled();
assert.deepEqual(commands.pop(), ['toggle_quick_control', 'gate']);
delete globalThis.audioMatrixRendererFixture;
console.log('Group reorder behaviors cover native drag handles, persisted ordering, keyboard alternatives, and Gate');
