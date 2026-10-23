import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

let now = 10_000_000;
let nextUuid = 1;
let nextSource = 1;
const sources = new Map();
const commands = [];
const GLib = {
    PRIORITY_DEFAULT: 0,
    USEC_PER_SEC: 1_000_000,
    SOURCE_REMOVE: false,
    SOURCE_CONTINUE: true,
    get_monotonic_time: () => now,
    uuid_string_random: () => `00000000-0000-4000-8000-${String(nextUuid++).padStart(12, '0')}`,
    timeout_add_seconds(_priority, _seconds, callback) {
        const id = nextSource++;
        sources.set(id, callback);
        return id;
    },
    timeout_add(_priority, _milliseconds, callback) {
        const id = nextSource++;
        sources.set(id, callback);
        return id;
    },
    Source: {remove(id) { sources.delete(id); }},
    Variant: class Variant {
        constructor(signature, values) { this.signature = signature; this.values = values; }
    },
};
const Gio = {
    DBusSignalFlags: {NONE: 0},
    DBus: {session: {
        signal_subscribe() { return nextSource++; },
        signal_unsubscribe() {},
        emit_signal(_destination, _path, _interface, signal, variant) {
            commands.push({signal, variant});
        },
    }},
};
globalThis.audioMatrixStateClientFixture = {Gio, GLib, systemLanguage: () => 'en'};
const base = new URL('../gnome/audio-matrix-quick-settings/', import.meta.url);
const loadHelper = async name => {
    const helper = (await readFile(new URL(name, base), 'utf8'))
        .replace("import GLib from 'gi://GLib';", 'const {GLib} = globalThis.audioMatrixStateClientFixture;');
    return (await import(`data:text/javascript;base64,${Buffer.from(helper).toString('base64')}`)).default;
};
globalThis.audioMatrixStateClientFixture.AudioMatrixPendingIntents = await loadHelper('menu-pending-intents.js');
globalThis.audioMatrixStateClientFixture.AudioMatrixStateRenderScheduler = await loadHelper('menu-state-render-scheduler.js');
const source = (await readFile(new URL('state-client.js', base), 'utf8'))
    .replace("import Gio from 'gi://Gio';", 'const {Gio} = globalThis.audioMatrixStateClientFixture;')
    .replace("import GLib from 'gi://GLib';", 'const {GLib} = globalThis.audioMatrixStateClientFixture;')
    .replace("import {systemLanguage} from './menu-text.js';", 'const {systemLanguage} = globalThis.audioMatrixStateClientFixture;')
    .replace("import AudioMatrixPendingIntents from './menu-pending-intents.js';",
        'const {AudioMatrixPendingIntents} = globalThis.audioMatrixStateClientFixture;')
    .replace("import AudioMatrixStateRenderScheduler from './menu-state-render-scheduler.js';",
        'const {AudioMatrixStateRenderScheduler} = globalThis.audioMatrixStateClientFixture;');
const {default: StateClient} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

function state(epoch, sequence, {acks = [], volume = 0.9, muted = false, activeProfileId = 'main', applications = []} = {}) {
    return {apiVersion: 5, revision: 'revision', connected: true, language: 'en', stateEpoch: epoch,
        stateSequence: sequence, commandAcks: acks, activeProfileId, volume, muted, applications};
}
function deliver(client, value) {
    client._receiveState({deepUnpack: () => [JSON.stringify(value)]});
}
function commandValue(index = commands.length - 1) {
    const emitted = commands[index];
    assert.equal(emitted.signal, 'Command');
    return emitted.variant.values;
}
function ackFor(requestId, target, property, status = 'ok', value = null, error = undefined) {
    return {requestId, target, property, status, value, ...(error ? {error} : {})};
}
function makeClient() {
    const client = new StateClient(() => {});
    deliver(client, state('epoch-a', 1));
    return client;
}

const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
{
    const client = makeClient();
    const requestId = client.send('setNodeVolume', {target, value: 0.5});
    assert.match(requestId, /^[0-9a-f-]+:[0-9a-f-]+$/i, 'request IDs include client and request UUIDs');
    const [version, emittedId, revision, name, payload] = commandValue();
    assert.equal(version, 5);
    assert.equal(emittedId, requestId);
    assert.equal(revision, 'revision');
    assert.equal(name, 'setNodeVolume');
    assert.deepEqual(JSON.parse(payload), {target, value: 0.5});
    assert.equal(client.snapshot().pendingIntents[0].value, 0.5);

    deliver(client, state('epoch-a', 2, {volume: 0.9}));
    assert.equal(client.snapshot().pendingIntents[0].value, 0.5,
        'a later authoritative State without its ACK must not roll the 90→50 intent back');
    deliver(client, state('epoch-a', 1, {acks: [ackFor(requestId, target, 'volume', 'ok', 0.9)]}));
    assert.equal(client.snapshot().state.stateSequence, 2, 'an older State and reordered ACK are rejected');
    assert.equal(client.snapshot().pendingIntents[0].value, 0.5,
        'a stale ACK cannot settle the current volume intent');
}

{
    const client = makeClient();
    const oldRequest = client.send('setNodeVolume', {target, value: 0.5});
    const currentRequest = client.send('setNodeVolume', {target, value: 0.3});
    assert.notEqual(oldRequest, currentRequest);
    assert.equal(client.snapshot().pendingIntents[0].value, 0.3);
    deliver(client, state('epoch-a', 2, {volume: 0.5, acks: [ackFor(oldRequest, target, 'volume', 'ok', 0.5)]}));
    assert.equal(client.snapshot().pendingIntents[0].value, 0.3,
        'an older ACK cannot cancel a newer 50→30 intent for the same target/property');
    const wrongTarget = {...target, nodeId: 'another-gain'};
    deliver(client, state('epoch-a', 3, {volume: 0.3,
        acks: [ackFor(currentRequest, wrongTarget, 'volume', 'ok', 0.3)]}));
    assert.equal(client.snapshot().pendingIntents[0].value, 0.3,
        'matching request ID alone cannot settle an ACK with a different target');
    deliver(client, state('epoch-a', 4, {volume: 0.3,
        acks: [ackFor(currentRequest, target, 'volume', 'ok', 0.3)]}));
    assert.equal(client.snapshot().pendingIntents.length, 0);
}

{
    const client = makeClient();
    const requestId = client.send('setNodeMuted', {target, muted: true});
    client._receiveResult({deepUnpack: () => [5, requestId, 'error', 'Result is not an ACK']});
    assert.equal(client.snapshot().pendingIntents[0].value, true,
        'a Result signal alone is ignored and does not settle an intent');
    deliver(client, state('epoch-a', 2, {muted: false,
        acks: [ackFor(requestId, target, 'muted', 'error', null, 'Permission denied')]}));
    assert.equal(client.snapshot().pendingIntents.length, 0);
    assert.equal(client.snapshot().state.muted, false, 'error rollback displays the authoritative State');
    assert.equal(client.snapshot().commandError, 'Permission denied');
}

{
    const client = makeClient();
    client.send('setNodeVolume', {target, value: 0.2});
    const timer = [...sources.keys()].find(id => client._intentTracker._requests.values().next().value?.timer === id);
    assert.ok(timer, 'intent timeout is registered');
    const onTimeout = sources.get(timer);
    sources.delete(timer);
    onTimeout();
    assert.equal(client.snapshot().pendingIntents.length, 0, 'timeout rolls the optimistic intent back');
    assert.equal(client.snapshot().state.volume, 0.9);
}

{
    const client = makeClient();
    client.send('setNodeMuted', {target, muted: true});
    deliver(client, state('epoch-b', 1, {muted: false}));
    assert.equal(client.snapshot().pendingIntents.length, 0, 'a bridge epoch change clears intents');
    assert.equal(client.snapshot().state.stateEpoch, 'epoch-b');
    assert.equal(client.snapshot().state.stateSequence, 1, 'a new epoch can restart at sequence one');
}

{
    const client = makeClient();
    const applicationId = 'app:org.example.player';
    const target = {applicationId};
    const requests = [];
    const sendVolume = value => requests.push(client.send('set_app_volume',
        `${applicationId}\t${value.toFixed(3)}`));
    const sliderId = `application:${applicationId}`;

    client.beginSliderDrag();
    client.queueSliderCommand(sliderId, 0.4, sendVolume);
    client.queueSliderCommand(sliderId, 0.63, sendVolume);
    client.queueSliderCommand(sliderId, 0.63, sendVolume, true);
    client.endSliderDrag();

    const latestRequest = requests.at(-1);
    const [version, requestId, _revision, name, payload] = commandValue();
    assert.equal(version, 5);
    assert.equal(name, 'set_app_volume');
    assert.equal(requestId, latestRequest);
    assert.equal(payload, `${applicationId}\t0.630`);
    assert.deepEqual(client.snapshot().pendingIntents[0],
        {requestId: latestRequest, target, property: 'volume', value: 0.63});

    deliver(client, state('epoch-a', 2, {
        applications: [{id: applicationId, volume: 0.9, muted: false}],
    }));
    assert.equal(client.snapshot().pendingIntents[0].value, 0.63,
        'a stale application snapshot cannot roll back the final drag value before its ACK');
    deliver(client, state('epoch-a', 3, {
        applications: [{id: applicationId, volume: 0.63, muted: false}],
        acks: [ackFor(latestRequest, target, 'volume', 'ok', 0.63)],
    }));
    assert.equal(client.snapshot().pendingIntents.length, 0);
    assert.equal(client.snapshot().state.applications[0].volume, 0.63,
        'the matching State ACK settles against the observed application level');
}


delete globalThis.audioMatrixStateClientFixture;
console.log('State client v5 tests cover ordered State ACKs, coalesced intents, rollback, and epochs');

{
    const client = makeClient();
    client.start();
    now += 13 * GLib.USEC_PER_SEC;
    sources.get(client._watchdogId)();
    assert.equal(client.snapshot().available, false, 'A silent bridge becomes unavailable after the freshness limit');
    deliver(client, state('epoch-a', 1));
    assert.equal(client.snapshot().state.stateSequence, 1, 'A heartbeat replay does not advance state sequence');
    assert.equal(client.snapshot().available, true, 'An identical bridge heartbeat refreshes freshness without replacing state');
    client.stop();
}
