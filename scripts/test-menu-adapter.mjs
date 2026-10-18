import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

const base = new URL('../gnome/audio-matrix-quick-settings/', import.meta.url);
globalThis.audioMatrixShellFixture = {panel: {statusArea: {quickSettings: {}}}};
const source = (await readFile(new URL('volume-menu-adapter.js', base), 'utf8'))
    .replace("import * as Main from 'resource:///org/gnome/shell/ui/main.js';",
        'const Main = globalThis.audioMatrixShellFixture;')
    .replace("'./menu-lease.js'", JSON.stringify(new URL('menu-lease.js', base).href));
const {default: Adapter} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

function volumeOutput() {
    const stock = {actor: {visible: true}};
    const box = {
        children: [],
        remove_child(actor) { this.children.splice(this.children.indexOf(actor), 1); actor.parent = null; },
    };
    const menu = {
        _getMenuItems() { return [stock, ...box.children.map(actor => actor._delegate)]; },
        addMenuItem(item) { assert.equal(item.destroyed, false); box.children.push(item.actor); item.actor.parent = box; },
    };
    return {
        menu, stock, box, menuEnabled: false,
        connect() { return 1; },
        disconnect() {},
        _sync() { this.menuEnabled = false; },
    };
}
function section() {
    const result = {
        destroyed: false, disconnects: [],
        disconnectObject(owner) { this.disconnects.push(owner); },
        destroy() { assert.equal(this.destroyed, false, 'Section must be destroyed once'); this.destroyed = true; },
        actor: {visible: true, parent: null, get_parent() { return this.parent; }},
    };
    result.actor._delegate = result;
    return result;
}
const quickSettings = globalThis.audioMatrixShellFixture.panel.statusArea.quickSettings;
const content = section();
const adapter = new Adapter(content);
assert.equal(adapter.attach(), false, 'No private volume implementation must fail safely');
const first = volumeOutput();
quickSettings._volumeOutput = {_output: first};
assert.equal(adapter.attach(), true);
assert.equal(first.menuEnabled, true, 'One-output systems still need the standard expansion button');
adapter.commit();
assert.equal(first.stock.actor.visible, false);
adapter.rollback();
assert.equal(first.stock.actor.visible, true, 'Failed renders restore the native device rows');
assert.equal(content.actor.visible, false);
adapter.commit();
adapter.attach();
assert.equal(first.box.children.length, 1, 'Repeated attachment must not duplicate the column');
const second = volumeOutput();
quickSettings._volumeOutput._output = second;
assert.equal(adapter.attach(), true);
assert.equal(first.stock.actor.visible, true, 'Replacing Quick Settings restores the old native menu');
assert.equal(first.menuEnabled, false);
assert.equal(first.box.children.length, 0);
assert.deepEqual(content.disconnects, [first.menu]);
assert.equal(content.destroyed, false, 'Shell reconstruction must preserve the live rendered column');
adapter.commit();
assert.equal(second.stock.actor.visible, false);
adapter.destroy();
assert.equal(second.stock.actor.visible, true, 'Disabling restores the current native menu');
assert.equal(second.menuEnabled, false);
assert.equal(second.box.children.length, 0);
assert.equal(content.destroyed, true);
adapter.destroy();

const unattached = section();
const neverAttached = new Adapter(unattached);
neverAttached.destroy();
assert.equal(unattached.destroyed, true, 'Disabling before Shell exposes volume still frees the section');
delete globalThis.audioMatrixShellFixture;
console.log('Volume adapter behaviors cover standard expansion, rollback, Shell reconstruction, and disable');
