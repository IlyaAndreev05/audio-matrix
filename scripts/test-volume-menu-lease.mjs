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

visibility.commit(menu, section);
assert.equal(visible.visible, false);
assert.equal(section.actor.visible, true);
visibility.rollback(section);
assert.equal(visible.visible, true);
assert.equal(section.actor.visible, false);

const output = {
    deviceCount: 1,
    _menuEnabled: false,
    _listener: null,
    get menuEnabled() { return this._menuEnabled; },
    set menuEnabled(value) {
        if (this._menuEnabled === value) return;
        this._menuEnabled = value;
        this._listener?.();
    },
    connect(signal, listener) {
        assert.equal(signal, 'notify::menu-enabled');
        this._listener = listener;
        return 1;
    },
    disconnect(id) { assert.equal(id, 1); this._listener = null; },
    _sync() { this.menuEnabled = this.deviceCount > 1; },
};
const originalSync = output._sync;
const button = new MenuButtonLease(output);
assert.equal(button.isCurrent(), true);
assert.equal(output._sync, originalSync, 'Lease must not monkey-patch Shell methods');
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
assert.equal(button.isCurrent(), false);
assert.equal(output._listener, null, 'Disabling must disconnect the visibility override');
button.restore();

const propertyOnly = {
    menuEnabled: false,
    connect() { return 2; },
    disconnect(id) { assert.equal(id, 2); },
};
const propertyLease = new MenuButtonLease(propertyOnly);
assert.equal(propertyOnly.menuEnabled, true);
propertyLease.restore();
assert.equal(propertyOnly.menuEnabled, false, 'Shell variants without _sync must restore the prior state');

console.log('Volume menu leases preserve stock state across repeated attachment and detach');
