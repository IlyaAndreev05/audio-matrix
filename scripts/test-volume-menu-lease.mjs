import assert from 'node:assert/strict';
import {MenuButtonLease, MenuItemsLease} from '../gnome/audio-matrix-quick-settings/menu-lease.js';

const visible = {visible: true};
const hidden = {visible: false};
const replacement = {actor: {visible: true}};
const section = {actor: {visible: true}};
const menu = {_items: [{actor: visible}, {actor: hidden}, section]};
const visibility = new MenuItemsLease();
visibility.hide(menu, section);
visibility.hide(menu, section);
assert.equal(visible.visible, false);
assert.equal(hidden.visible, false);
assert.equal(section.actor.visible, true);
menu._items.push(replacement);
visibility.hide(menu, section);
visibility.restore();
assert.equal(visible.visible, true);
assert.equal(hidden.visible, false);
assert.equal(replacement.actor.visible, true);

const output = {
    deviceCount: 1,
    menuEnabled: false,
    _sync() { this.menuEnabled = this.deviceCount > 1; },
};
const originalSync = output._sync;
const button = new MenuButtonLease(output);
output._sync();
assert.equal(output.menuEnabled, true);
output.deviceCount = 3;
output._sync();
assert.equal(output.menuEnabled, true);
button.restore();
assert.equal(output._sync, originalSync);
assert.equal(output.menuEnabled, true);
output.deviceCount = 1;
output._sync();
assert.equal(output.menuEnabled, false);

console.log('Volume menu leases preserve stock state across repeated attachment and detach');
