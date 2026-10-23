import assert from 'node:assert/strict';
import {MenuButtonLease, MenuItemsLease} from '../gnome/audio-matrix-quick-settings/menu-lease.js';

class OutputFixture {
    constructor(menuEnabled) {
        this._menuEnabled = menuEnabled;
        this.nativeMenuEnabled = menuEnabled;
        this.handlers = new Map();
        this.nextSignalId = 1;
        this.connectCalls = 0;
        this.disconnectCalls = 0;
        this.syncCalls = 0;
        this.syncHandlerCounts = [];
        this.writes = [];
        this.notificationDepth = 0;
        this.maxNotificationDepth = 0;
    }

    get menuEnabled() {
        return this._menuEnabled;
    }

    set menuEnabled(value) {
        this._menuEnabled = value;
        this.writes.push(value);
        this.notificationDepth++;
        this.maxNotificationDepth = Math.max(this.maxNotificationDepth, this.notificationDepth);
        try {
            assert.ok(this.notificationDepth <= 2, 'Menu-enabled notifications must not recurse indefinitely');
            // Emit even for unchanged values to expose unconditional notification writes.
            for (const handler of [...this.handlers.values()])
                handler(this);
        } finally {
            this.notificationDepth--;
        }
    }

    connect(signal, handler) {
        assert.equal(signal, 'notify::menu-enabled');
        this.connectCalls++;
        const id = this.nextSignalId++;
        this.handlers.set(id, handler);
        return id;
    }

    disconnect(id) {
        assert.ok(this.handlers.delete(id), 'Only a connected signal may be disconnected');
        this.disconnectCalls++;
    }

    _sync() {
        this.syncCalls++;
        this.syncHandlerCounts.push(this.handlers.size);
        this.menuEnabled = this.nativeMenuEnabled;
    }
}

{
    const output = new OutputFixture(false);
    const nativeSync = output._sync;
    const lease = new MenuButtonLease(output);
    assert.equal(output.menuEnabled, false, 'Construction captures state without enabling the arrow');
    assert.equal(output.handlers.size, 0);
    assert.deepEqual(output.writes, []);

    lease.keep();
    assert.equal(output.menuEnabled, true);
    assert.equal(output.handlers.size, 1);
    assert.equal(output.connectCalls, 1);
    assert.equal(output._sync, nativeSync, 'Native sync is never replaced');
    const keptWrites = output.writes.length;
    lease.keep();
    lease.keep();
    assert.equal(output.connectCalls, 1, 'Repeated keep does not leak handlers');
    assert.equal(output.writes.length, keptWrites, 'Repeated keep does not rewrite an enabled arrow');

    for (let i = 0; i < 3; i++) {
        output._sync();
        assert.equal(output.menuEnabled, true, 'Native sync cannot hide the leased arrow');
        assert.equal(output.handlers.size, 1);
        assert.equal(output._sync, nativeSync);
    }
    output.menuEnabled = false;
    assert.equal(output.menuEnabled, true, 'Direct native notifications also keep the arrow enabled');
    assert.equal(output.maxNotificationDepth, 2);

    const syncCalls = output.syncCalls;
    lease.restore();
    assert.equal(output.menuEnabled, false, 'Native sync regains authority on restore');
    assert.equal(output.syncCalls, syncCalls + 1);
    assert.equal(output.syncHandlerCounts.at(-1), 0, 'Disconnect happens before native sync');
    assert.equal(output.handlers.size, 0);
    assert.equal(output.disconnectCalls, 1);
    assert.equal(output._sync, nativeSync);
    const restoredWrites = output.writes.length;
    lease.restore();
    lease.keep();
    assert.equal(output.syncCalls, syncCalls + 1, 'Repeated restore does not run native sync again');
    assert.equal(output.disconnectCalls, 1);
    assert.equal(output.connectCalls, 1, 'A released lease cannot reconnect');
    assert.equal(output.writes.length, restoredWrites);

    output.nativeMenuEnabled = true;
    output._sync();
    assert.equal(output.menuEnabled, true);
    output.nativeMenuEnabled = false;
    output._sync();
    assert.equal(output.menuEnabled, false, 'Later native updates are not intercepted');
    assert.equal(output.handlers.size, 0);
}

for (const [initial, current] of [[false, true], [true, false]]) {
    const output = new OutputFixture(initial);
    const lease = new MenuButtonLease(output);
    lease.keep();
    output.nativeMenuEnabled = current;
    lease.restore();
    assert.equal(output.menuEnabled, current, 'Restoration uses current devices, not the captured boolean');
    assert.equal(output.syncCalls, 1);
    assert.equal(output.handlers.size, 0);
}

for (const initial of [false, true]) {
    const output = new OutputFixture(initial);
    output._sync = undefined;
    const lease = new MenuButtonLease(output);
    lease.keep();
    output.menuEnabled = false;
    assert.equal(output.menuEnabled, true);
    lease.restore();
    assert.equal(output.menuEnabled, initial, 'Without native sync the exact original boolean is restored');
    assert.equal(output.handlers.size, 0);
    assert.equal(output.disconnectCalls, 1);
    const restoredWrites = output.writes.length;
    lease.restore();
    lease.keep();
    assert.equal(output.writes.length, restoredWrites);
    assert.equal(output.connectCalls, 1);
    assert.equal(output.disconnectCalls, 1);
}

{
    const output = new OutputFixture(true);
    const lease = new MenuButtonLease(output);
    output.nativeMenuEnabled = false;
    lease.restore();
    lease.restore();
    assert.equal(output.menuEnabled, false);
    assert.equal(output.syncCalls, 1);
    assert.equal(output.connectCalls, 0, 'An unused lease never connects a handler');
    assert.equal(output.disconnectCalls, 0);
}

{
    const output = new OutputFixture(false);
    let unrelatedNotifications = 0;
    const unrelatedId = output.connect('notify::menu-enabled', () => unrelatedNotifications++);
    for (let i = 0; i < 4; i++) {
        const lease = new MenuButtonLease(output);
        lease.keep();
        lease.keep();
        assert.equal(output.handlers.size, 2);
        output._sync();
        assert.equal(output.menuEnabled, true);
        lease.restore();
        assert.equal(output.menuEnabled, false);
        assert.equal(output.handlers.size, 1, 'Repeated attachment removes only its own handler');
        assert.equal(output.syncHandlerCounts.at(-1), 1);
    }
    assert.equal(output.connectCalls, 5);
    assert.equal(output.disconnectCalls, 4);
    const previousNotifications = unrelatedNotifications;
    output.menuEnabled = true;
    assert.equal(unrelatedNotifications, previousNotifications + 1, 'Unrelated signal handlers survive restoration');
    output.disconnect(unrelatedId);
    assert.equal(output.handlers.size, 0);
}

function actor(name, initialVisibility) {
    let visible = initialVisibility;
    return {
        name,
        children: [],
        writes: [],
        destroyed: false,
        get visible() {
            return visible;
        },
        set visible(value) {
            if (this.destroyed)
                throw new Error(`${name} is destroyed`);
            this.writes.push(value);
            visible = value;
        },
    };
}

{
    const shown = actor('native-device-section', true);
    const hidden = actor('native-hidden-heading', false);
    const stillHidden = actor('native-hidden-row', false);
    const sibling = actor('unrelated-menu-section', true);
    const child = actor('nested-control', true);
    const parent = actor('output-menu', true);
    parent.children.push(shown, hidden, stillHidden, sibling);
    shown.children.push(child);
    const selected = [shown, hidden, stillHidden];
    const lease = new MenuItemsLease(selected);
    for (const item of [shown, hidden, stillHidden, sibling, child, parent])
        assert.deepEqual(item.writes, [], 'Construction does not change visibility');

    // Snapshots belong to the constructor's actors, not a later mutable actor list.
    selected.splice(0, 1);
    selected.push(sibling);
    shown.visible = false;
    hidden.visible = true;
    lease.hide();
    assert.equal(shown.visible, false);
    assert.equal(hidden.visible, false);
    assert.equal(stillHidden.visible, false);
    assert.equal(sibling.visible, true);
    assert.equal(child.visible, true);
    assert.equal(parent.visible, true);
    assert.deepEqual(stillHidden.writes, [], 'An already hidden actor needs no hide write');

    const hotplugSibling = actor('new-native-device-section', true);
    const hotplugChild = actor('new-device-row', true);
    const hiddenHotplugChild = actor('new-hidden-device-row', false);
    parent.children.push(hotplugSibling);
    shown.children.push(hotplugChild, hiddenHotplugChild);
    const hiddenWriteCounts = [shown.writes.length, hidden.writes.length, stillHidden.writes.length];
    lease.hide();
    assert.deepEqual([shown.writes.length, hidden.writes.length, stillHidden.writes.length], hiddenWriteCounts);

    lease.restore();
    assert.equal(shown.visible, true, 'Visibility is captured at construction, not at the first hide');
    assert.equal(hidden.visible, false, 'An initially hidden actor stays hidden after restoration');
    assert.equal(stillHidden.visible, false);
    assert.deepEqual([shown.writes.length, hidden.writes.length, stillHidden.writes.length],
        hiddenWriteCounts.map(count => count + 1), 'Every selected snapshot is restored exactly once');
    for (const item of [sibling, child, parent, hotplugSibling, hotplugChild, hiddenHotplugChild])
        assert.deepEqual(item.writes, [], 'Siblings, descendants and hotplug actors are never owned');
    assert.equal(hotplugSibling.visible, true);
    assert.equal(hotplugChild.visible, true);
    assert.equal(hiddenHotplugChild.visible, false);

    shown.visible = false;
    hidden.visible = true;
    stillHidden.visible = true;
    const releasedWriteCounts = [shown.writes.length, hidden.writes.length, stillHidden.writes.length];
    lease.restore();
    lease.hide();
    assert.deepEqual([shown.visible, hidden.visible, stillHidden.visible], [false, true, true]);
    assert.deepEqual([shown.writes.length, hidden.writes.length, stillHidden.writes.length], releasedWriteCounts,
        'A released visibility lease never writes again');
}

{
    const destroyed = actor('destroyed-section', true);
    const surviving = actor('surviving-section', true);
    const lease = new MenuItemsLease([destroyed, surviving]);
    lease.hide();
    destroyed.destroyed = true;
    assert.doesNotThrow(() => lease.restore());
    assert.equal(surviving.visible, true, 'One destroyed actor does not prevent other snapshots from restoring');
    const restoredWrites = surviving.writes.length;
    lease.restore();
    assert.equal(surviving.writes.length, restoredWrites);
}

console.log('Output-menu leases preserve native sync, release signal handlers, and restore only explicitly selected actors.');
