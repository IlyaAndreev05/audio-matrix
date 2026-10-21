import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

let now = 1_000_000;
let nextTimer = 0;
let nextUuid = 0;
const timers = new Map();
const emitted = [];
const GLib = {
    PRIORITY_DEFAULT: 0,
    PRIORITY_DEFAULT_IDLE: 1,
    SOURCE_REMOVE: false,
    SOURCE_CONTINUE: true,
    USEC_PER_SEC: 1_000_000,
    get_monotonic_time: () => now,
    uuid_string_random: () => `uuid-${++nextUuid}`,
    timeout_add(_priority, _delay, callback) {
        const id = ++nextTimer;
        timers.set(id, {callback, seconds: false});
        return id;
    },
    timeout_add_seconds(_priority, _delay, callback) {
        const id = ++nextTimer;
        timers.set(id, {callback, seconds: true});
        return id;
    },
    Source: {remove(id) { timers.delete(id); }},
    Variant: class {
        constructor(type, value) { this.type = type; this.value = value; }
    },
};
const Gio = {
    DBusSignalFlags: {NONE: 0},
    DBus: {session: {
        signal_subscribe() { return 1; },
        signal_unsubscribe() {},
        emit_signal(_destination, _path, _interface, _name, variant) { emitted.push(variant); },
    }},
};
const fixture = {Gio, GLib, systemLanguage: () => 'en'};
globalThis.audioMatrixStateClientFixture = fixture;
const source = (await readFile(new URL('../gnome/audio-matrix-quick-settings/state-client.js', import.meta.url), 'utf8'))
    .replace(/^import .*;\n/gm, '');
const wrapped = `const {Gio, GLib, systemLanguage} = globalThis.audioMatrixStateClientFixture;\n${source}`;
const {default: StateClient} = await import(`data:text/javascript;base64,${Buffer.from(wrapped).toString('base64')}`);

const client = new StateClient(() => {});
const target = {routeId: 'route-main', nodeId: 'gain-node', instanceKey: ''};
function state(stateEpoch, stateSequence, commandAcks = [], extras = {}) {
    return {apiVersion: 5, connected: true, stateEpoch, stateSequence, revision: 'cfg-r1',
        language: 'en', profiles: [{id: 'profile-main', name: 'Main'}], activeProfileId: 'profile-main',
        commandAcks, ...extras};
}
function receive(value) {
    client._receiveState({deepUnpack: () => [JSON.stringify(value)]});
}
function ack(requestId, requestTarget, property, status = 'ok', stateSequence = 0, value = null) {
    return {requestId, target: requestTarget, property, status, stateSequence, value};
}
receive(state('epoch-a', 1));

const fifty = client.send('setNodeVolume', {target, value: 0.5});
assert.match(fifty, /^uuid-1:uuid-2$/, 'Request IDs carry a stable per-client prefix');
const volumeSignal = emitted.at(-1);
assert.equal(volumeSignal.type, '(ussss)', 'The API v5 command keeps the established D-Bus tuple');
assert.equal(volumeSignal.value[0], 5);
assert.equal(volumeSignal.value[3], 'setNodeVolume');
assert.deepEqual(JSON.parse(volumeSignal.value[4]), {target, value: 0.5},
    'Graph volume commands use the fixed API v5 JSON payload');

receive(state('epoch-a', 2, [], {observedVolume: 0.9}));
assert.equal([...client.snapshot().pending.values()][0]?.desired, 0.5,
    'A newer stale-value State keeps the optimistic slider intent');
const thirty = client.send('setNodeVolume', {target, value: 0.3});
assert.notEqual(thirty, fifty);
receive(state('epoch-a', 3, [ack(fifty, target, 'volume', 'ok', 3, 0.5)], {observedVolume: 0.5}));
assert.equal([...client.snapshot().pending.values()][0]?.desired, 0.3,
    'A reordered ACK for 50 cannot release the newer 30 intent');
receive(state('epoch-a', 2, [ack(thirty, target, 'volume', 'ok', 2, 0.3)]));
assert.equal(client.snapshot().state.stateSequence, 3, 'Reordered stale State is a no-op');
assert.equal([...client.snapshot().pending.values()][0]?.desired, 0.3,
    'A stale ACK inside a rejected State does not release the pending setter');
client._receiveResult({deepUnpack: () => [5, thirty, 'ok', 'accepted']});
assert.equal(client.snapshot().pending.size, 1, 'Result-only success never releases the optimistic value');
receive(state('epoch-a', 4, [ack(thirty, target, 'volume', 'ok', 4, 0.3)], {observedVolume: 0.3}));
assert.equal(client.snapshot().pending.size, 0, 'Only the matching accepted State ACK settles the latest volume');

const volume = client.send('setNodeVolume', {target, value: 0.6});
const mute = client.send('setNodeMuted', {target, muted: true});
assert.equal(client.snapshot().pending.size, 2, 'Volume and mute intents are tracked independently');
receive(state('epoch-a', 5, [ack(volume, target, 'volume', 'ok', 5, 0.6)]));
assert.deepEqual([...client.snapshot().pending.values()].map(intent => intent.property), ['muted'],
    'A volume ACK does not release mute intent');
receive(state('epoch-a', 6, [ack(mute, target, 'muted', 'ok', 6, true)]));
assert.equal(client.snapshot().pending.size, 0);

const failed = client.send('setNodeVolume', {target, value: 0.4});
client._receiveResult({deepUnpack: () => [5, failed, 'error', 'Backend rejected volume']});
assert.equal(client.snapshot().pending.size, 0, 'A failed Result releases only its matching intent');
assert.equal(client.snapshot().commandError, 'Backend rejected volume');
receive(state('epoch-a', 7, [], {observedVolume: 0.6}));
assert.equal(client.snapshot().state.observedVolume, 0.6, 'Failure returns the UI to authoritative State');

const timedOut = client.send('setNodeMuted', {target, muted: false});
const timeout = [...timers].find(([, timer]) => timer.seconds)?.[0];
assert.ok(timeout, 'Command timeout is registered');
timers.get(timeout).callback();
assert.equal(client.snapshot().pending.size, 0, 'A timeout releases the pending optimistic value');
assert.equal(client.snapshot().commandError, 'No response from the Audio Matrix engine');

const restarted = client.send('setNodeVolume', {target, value: 0.7});
receive(state('epoch-b', 1));
assert.equal(client.snapshot().state.stateEpoch, 'epoch-b');
assert.equal(client.snapshot().pending.size, 0, 'Bridge epoch restart clears commands from the old lifetime');
assert.match(client.snapshot().commandError, /restarted/i);
receive(state('epoch-a', 99, [ack(restarted, target, 'volume', 'ok', 99, 0.7)]));
assert.equal(client.snapshot().state.stateEpoch, 'epoch-b', 'A retired epoch cannot roll the client back');

const volumeAfterRestart = client.send('setNodeVolume', {target, value: 0.8});
const muteAfterRestart = client.send('setNodeMuted', {target, muted: true});
receive(state('epoch-b', 2, [ack(volumeAfterRestart, target, 'volume', 'ok', 2, 0.8)]));
assert.equal([...client.snapshot().pending.values()][0]?.requestId, muteAfterRestart);
receive(state('epoch-b', 3, [ack(muteAfterRestart, target, 'muted', 'error', 3, false)]));
assert.equal(client.snapshot().pending.size, 0);
assert.equal(client.snapshot().commandError, 'error', 'A failed State ACK exposes authoritative failure');

const dispatched = [];
client.beginSliderDrag();
client.queueSliderCommand('drag-volume', 0.9, value => dispatched.push(value));
client.queueSliderCommand('drag-volume', 0.5, value => dispatched.push(value));
client.queueSliderCommand('drag-volume', 0.3, value => dispatched.push(value));
assert.deepEqual(dispatched, [], 'Intermediate drag values are coalesced without dispatch');
client.queueSliderCommand('drag-volume', 0.3, value => dispatched.push(value), true);
assert.deepEqual(dispatched, [0.3], 'Drag end dispatches only the final slider value');
client.endSliderDrag();
client.stop();
delete globalThis.audioMatrixStateClientFixture;
console.log('GNOME state client behavior checks cover ordered intents, ACKs, failures, timeout, epoch restart, and final drag dispatch');
