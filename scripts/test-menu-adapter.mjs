import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

const base = new URL('../gnome/audio-matrix-quick-settings/', import.meta.url);
globalThis.audioMatrixShellFixture = {panel: {statusArea: {quickSettings: {}}}};
const source = (await readFile(new URL('volume-menu-adapter.js', base), 'utf8'))
    .replace("import * as Main from 'resource:///org/gnome/shell/ui/main.js';",
        'const Main = globalThis.audioMatrixShellFixture;');
const {default: Adapter} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

function menu() {
    const grid = {children: [], remove_child(actor) {
        this.children.splice(this.children.indexOf(actor), 1);
        actor.parent = null;
    }};
    const stock = {name: 'system-volume', visible: true};
    grid.children.push(stock);
    return {_grid: grid, stock, addItem(actor, span) {
        assert.equal(span, 2);
        this._grid.children.push(actor);
        actor.parent = this._grid;
    }};
}
function section() {
    return {destroyed: false, actor: {visible: true, parent: null, get_parent() { return this.parent; }},
        destroy() { assert.equal(this.destroyed, false); this.destroyed = true; }};
}
const quickSettings = globalThis.audioMatrixShellFixture.panel.statusArea.quickSettings;
const content = section();
const adapter = new Adapter(content);
assert.equal(adapter.attach(), false);
const first = menu();
quickSettings.menu = first;
assert.equal(adapter.attach(), true);
adapter.commit();
assert.equal(first.stock.visible, true, 'Native system controls remain visible beside Audio Matrix');
adapter.attach();
assert.deepEqual(first._grid.children, [first.stock, content.actor], 'Repeated attachment cannot duplicate the panel');
adapter.rollback();
assert.equal(first.stock.visible, true, 'A failed Audio Matrix render never hides system controls');
assert.equal(content.actor.visible, false);
const replacement = menu();
quickSettings.menu = replacement;
adapter.attach();
assert.deepEqual(first._grid.children, [first.stock], 'Shell reconstruction detaches only Audio Matrix');
assert.equal(content.destroyed, false);
adapter.commit();
assert.equal(replacement.stock.visible, true);
adapter.destroy();
assert.deepEqual(replacement._grid.children, [replacement.stock], 'Disable retains native controls');
assert.equal(content.destroyed, true);
adapter.destroy();
const unattached = section();
new Adapter(unattached).destroy();
assert.equal(unattached.destroyed, true);
delete globalThis.audioMatrixShellFixture;
console.log('Quick Settings preserves native controls during attachment, rollback, reconstruction and disable');
