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
    Source: {remove(id) { if (!id) throw new Error('GLib.Source.remove(0)'); sources.delete(id); }},
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
    return (await import(`data:text/javascript;base64,${Buffer.from(helper).toString('base64')}`));
};
const pendingModule = await loadHelper('menu-pending-intents.js');
globalThis.audioMatrixStateClientFixture.AudioMatrixPendingIntents = pendingModule.default;
globalThis.audioMatrixStateClientFixture.observedProperty = pendingModule.observedProperty;
globalThis.audioMatrixStateClientFixture.AudioMatrixStateRenderScheduler =
    (await loadHelper('menu-state-render-scheduler.js')).default;
const source = (await readFile(new URL('state-client.js', base), 'utf8'))
    .replace("import Gio from 'gi://Gio';", 'const {Gio} = globalThis.audioMatrixStateClientFixture;')
    .replace("import GLib from 'gi://GLib';", 'const {GLib} = globalThis.audioMatrixStateClientFixture;')
    .replace("import {systemLanguage} from './menu-text.js';", 'const {systemLanguage} = globalThis.audioMatrixStateClientFixture;')
    .replace("import AudioMatrixPendingIntents from './menu-pending-intents.js';",
        'const {AudioMatrixPendingIntents} = globalThis.audioMatrixStateClientFixture;')
    .replace("import AudioMatrixStateRenderScheduler from './menu-state-render-scheduler.js';",
        'const {AudioMatrixStateRenderScheduler} = globalThis.audioMatrixStateClientFixture;');
const {default: StateClient} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

function state(epoch, sequence, {acks = [], volume = 0.9, muted = false, activeProfileId = 'main',
    applications = [], quickPins = [], quickControls = []} = {}) {
    return {apiVersion: 5, revision: 'revision', connected: true, language: 'en', stateEpoch: epoch,
        stateSequence: sequence, commandAcks: acks, activeProfileId, volume, muted, applications,
        quickPins, quickControls};
}
function deliver(client, value) {
    client._receiveState({deepUnpack: () => [JSON.stringify(value)]});
}
function commandValue(index = commands.length - 1) {
    const emitted = commands[index];
    assert.equal(emitted.signal, 'Command');
    return emitted.variant.values;
}
function ackFor(requestId, target, property, status = 'ok', value = null, error = undefined, sequence = 0) {
    return {requestId, target, property, status, value, stateSequence: sequence, ...(error ? {error} : {})};
}
function makeClient() {
    const client = new StateClient(() => {});
    deliver(client, state('epoch-a', 1));
    return client;
}
function pending(client) { return client.snapshot().pendingIntents; }
function request(client, requestId) { return client._intentTracker._requests.get(requestId); }
function fire(sourceId) {
    const callback = sources.get(sourceId);
    assert.ok(callback, `timer ${sourceId} is registered`);
    sources.delete(sourceId);
    return callback();
}
function liveTimers(client) {
    const ids = [];
    for (const entry of client._intentTracker._requests.values()) {
        if (entry.timer) ids.push(entry.timer);
        if (entry.observationTimer) ids.push(entry.observationTimer);
    }
    return ids;
}
// Node targets are resolved through the pinned graph projection, never the master volume.
function gainState(volume, {muted = false, ...rest} = {}) {
    return {volume, quickPins: [{id: 'gain-pin', routeId: 'main', nodeId: 'gain-1',
        gain: {routeId: 'main', nodeId: 'gain-1', volume, muted}}], ...rest};
}

// A success ACK retires the engine timeout but keeps the optimistic value until a State observes it.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const requestId = client.send('setNodeVolume', {target, value: 0.5});
    assert.match(requestId, /^[0-9a-f-]+:[0-9a-f-]+$/i, 'request IDs include client and request UUIDs');
    const [version, emittedId, revision, name, payload] = commandValue();
    assert.equal(version, 5);
    assert.equal(emittedId, requestId);
    assert.equal(revision, 'revision');
    assert.equal(name, 'setNodeVolume');
    assert.deepEqual(JSON.parse(payload), {target, value: 0.5});
    assert.deepEqual(pending(client)[0], {requestId, target, property: 'volume', value: 0.5,
        acknowledged: false, ackSequence: 0, observationTimer: 0});
    const ackTimer = request(client, requestId).timer;
    assert.ok(ackTimer, 'the five-second engine timer is registered');

    deliver(client, state('epoch-a', 2, gainState(0.9, {
        acks: [ackFor(requestId, target, 'volume', 'ok', 0.5, undefined, 2)]})));
    assert.equal(pending(client).length, 1,
        'an accepted ACK alone must not retire the optimistic volume');
    assert.equal(pending(client)[0].acknowledged, true);
    assert.ok(pending(client)[0].ackSequence > 0, 'the ACK sequence is retained for diagnostics');
    assert.equal(sources.has(ackTimer), false, 'the five-second engine timer is retired by the ACK');
    const observationTimer = request(client, requestId).observationTimer;
    assert.ok(observationTimer, 'a three-second observation deadline is armed after the ACK');

    deliver(client, state('epoch-a', 3, gainState(0.9)));
    assert.equal(pending(client).length, 1,
        'a State that still observes the old volume cannot retire the intent');
    assert.equal(request(client, requestId).observationTimer, observationTimer,
        'an unmatched State does not restart the observation deadline');

    deliver(client, state('epoch-a', 4, gainState(0.5)));
    assert.equal(pending(client).length, 0, 'the matching observed volume retires the intent');
    assert.equal(request(client, requestId), undefined, 'settlement removes the request and its timers');
    assert.equal(sources.has(observationTimer), false, 'the observation timer is cancelled');
    assert.equal(client.snapshot().commandError, '');
}

// The same State that carries the ACK may already observe the new level.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const requestId = client.send('setNodeVolume', {target, value: 0.5});
    deliver(client, state('epoch-a', 2, gainState(0.5, {
        acks: [ackFor(requestId, target, 'volume', 'ok', 0.5, undefined, 2)]})));
    assert.equal(pending(client).length, 0);
    assert.deepEqual(liveTimers(client), []);
}

// A matching observation may settle the intent before its separate ACK arrives.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const requestId = client.send('setNodeVolume', {target, value: 0.5});
    deliver(client, state('epoch-a', 2, gainState(0.5)));
    assert.equal(pending(client).length, 0, 'the ordered observation settles the pending intent');
    assert.deepEqual(liveTimers(client), [], 'all timers are cancelled with the intent');
    deliver(client, state('epoch-a', 3, gainState(0.5, {
        acks: [ackFor(requestId, target, 'volume', 'ok', 0.5, undefined, 3)]})));
    assert.equal(client.snapshot().commandError, '',
        'a late ACK for a settled request is a no-op, not an error');
}

// Repeated cumulative ACKs must not restart deadlines or resurrect a settled intent.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const requestId = client.send('setNodeVolume', {target, value: 0.5});
    const ack = ackFor(requestId, target, 'volume', 'ok', 0.5, undefined, 2);
    deliver(client, state('epoch-a', 2, gainState(0.9, {acks: [ack]})));
    const observationTimer = request(client, requestId).observationTimer;
    deliver(client, state('epoch-a', 3, gainState(0.9, {acks: [ack]})));
    assert.equal(pending(client).length, 1);
    assert.equal(request(client, requestId).observationTimer, observationTimer,
        'a repeated cumulative ACK does not restart the observation timer');
    deliver(client, state('epoch-a', 4, gainState(0.5)));
    assert.equal(pending(client).length, 0);
    deliver(client, state('epoch-a', 5, gainState(0.9, {acks: [ack]})));
    assert.equal(pending(client).length, 0, 'a late duplicate ACK cannot resurrect the intent');
    assert.equal(client.snapshot().commandError, '');
}

// An error ACK belongs to its requestId even when the reported target is wrong.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const requestId = client.send('setNodeMuted', {target, muted: true});
    assert.equal(pending(client)[0].value, true);
    deliver(client, state('epoch-a', 2, {muted: false,
        acks: [ackFor(requestId, {...target, nodeId: 'another-gain'}, 'muted', 'error', null,
            'Permission denied', 2)]}));
    assert.equal(pending(client).length, 0, 'an error ACK drops the optimistic value');
    assert.deepEqual(liveTimers(client), [], 'an error ACK cancels both timers');
    assert.equal(client.snapshot().commandError, 'Permission denied');
    assert.equal(client.snapshot().state.muted, false, 'the authoritative State is displayed after rollback');
}

// The five-second engine deadline and the three-second observation deadline stay distinct.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const requestId = client.send('setNodeVolume', {target, value: 0.2});
    fire(request(client, requestId).timer);
    assert.equal(pending(client).length, 0, 'the engine deadline rolls the optimistic value back');
    assert.equal(client.snapshot().commandError, 'Нет ответа от движка Audio Matrix');
}
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const requestId = client.send('setNodeVolume', {target, value: 0.2});
    deliver(client, state('epoch-a', 2, gainState(0.9, {
        acks: [ackFor(requestId, target, 'volume', 'ok', 0.2, undefined, 2)]})));
    fire(request(client, requestId).observationTimer);
    assert.equal(pending(client).length, 0, 'the observation deadline rolls the optimistic value back');
    assert.equal(client.snapshot().commandError, 'Изменение принято, но новое состояние не подтверждено',
        'an unconfirmed change is not reported as an engine timeout');
    assert.equal(client.snapshot().state.volume, 0.9, 'the last observed value is shown again');
}

// A newer request for the same target retires the older request and its timers.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const oldRequest = client.send('setNodeVolume', {target, value: 0.5});
    const currentRequest = client.send('setNodeVolume', {target, value: 0.3});
    assert.notEqual(oldRequest, currentRequest);
    assert.equal(pending(client)[0].value, 0.3);
    assert.equal(request(client, oldRequest), undefined, 'the superseded request keeps no timers');
    deliver(client, state('epoch-a', 2, gainState(0.5, {
        acks: [ackFor(oldRequest, target, 'volume', 'ok', 0.5, undefined, 2)]})));
    assert.equal(pending(client)[0].value, 0.3,
        'an older ACK cannot cancel a newer 50→30 intent for the same target/property');
    deliver(client, state('epoch-a', 3, gainState(0.3, {
        acks: [ackFor(currentRequest, target, 'volume', 'ok', 0.3, undefined, 3)]})));
    assert.equal(pending(client).length, 0);
}

// A new bridge epoch, an explicit stop, and a vanished target all cancel intents and timers.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    client.send('setNodeMuted', {target, muted: true});
    deliver(client, state('epoch-b', 1, {muted: false}));
    assert.equal(pending(client).length, 0, 'a bridge epoch change clears intents');
    assert.deepEqual(liveTimers(client), [], 'a bridge epoch change cancels timers');
    assert.equal(client.snapshot().state.stateSequence, 1, 'a new epoch can restart at sequence one');
}
{
    const client = makeClient();
    const applicationId = 'app:org.example.player';
    client.send('set_app_volume', `${applicationId}\t0.500`);
    deliver(client, state('epoch-a', 2, {applications: []}));
    assert.equal(pending(client).length, 0, 'a disappeared target drops its intent');
    assert.deepEqual(liveTimers(client), []);
}
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    client.send('setNodeVolume', {target, value: 0.4});
    client.stop();
    assert.equal(pending(client).length, 0);
    assert.deepEqual(liveTimers(client), []);
}

// Presentation commands have no observable scalar and settle on the ACK itself.
{
    const client = makeClient();
    const requestId = client.send('set_quick_group_collapsed', 'events\ttrue');
    assert.equal(pending(client).length, 0, 'a presentation command tracks no optimistic scalar');
    deliver(client, state('epoch-a', 2, {acks: [ackFor(requestId, null, null, 'ok', null, undefined, 2)]}));
    assert.equal(client.snapshot().commandError, '');
    assert.deepEqual(liveTimers(client), []);
}

// A generated Mixer input: ACK first, then the matching ordered observation.
{
    const client = makeClient();
    const pinId = 'smoke-mixer';
    const nodeId = 'octine-main-mixer';
    const inputId = 'generated-mixer-input-9f1c';
    const target = {routeId: 'main', nodeId, inputId};
    const pins = (volume, muted) => [{id: pinId, routeId: 'main', nodeId, mixerInputs: [
        {id: 'applications', name: 'Все приложения, кроме событий', volume: 1, muted: false,
            available: true, generated: false},
        {id: inputId, name: 'Проигрыватель', volume, muted, available: true, generated: true},
    ]}];
    deliver(client, state('epoch-a', 2, {quickPins: pins(0.9, false)}));

    const volumeRequest = client.send('set_mixer_input_volume', `main\t${nodeId}\t${inputId}\t0.350`);
    assert.deepEqual(pending(client)[0], {requestId: volumeRequest, target, property: 'volume', value: 0.35,
        acknowledged: false, ackSequence: 0, observationTimer: 0});
    deliver(client, state('epoch-a', 3, {quickPins: pins(0.9, false),
        acks: [ackFor(volumeRequest, target, 'volume', 'ok', 0.35, undefined, 3)]}));
    assert.equal(pending(client).length, 1,
        'the bridge ACK alone keeps the optimistic mixer value until it is observed');
    assert.equal(client.snapshot().commandError, '');
    deliver(client, state('epoch-a', 4, {quickPins: pins(0.35, false)}));
    assert.equal(pending(client).length, 0, 'the observed mixer volume settles the row');
    assert.equal(client.snapshot().commandError, '');

    const muteRequest = client.send('toggle_mixer_input_mute', `main\t${nodeId}\t${inputId}`);
    assert.deepEqual(pending(client)[0], {requestId: muteRequest, target, property: 'muted', value: true,
        acknowledged: false, ackSequence: 0, observationTimer: 0},
        'a mute toggle tracks the observed state so its ACK can settle it');
    deliver(client, state('epoch-a', 5, {quickPins: pins(0.35, false),
        acks: [ackFor(muteRequest, target, 'muted', 'ok', null, undefined, 5)]}));
    assert.equal(pending(client).length, 1,
        'a successful mute ACK carries no value and still waits for the observed mute');
    deliver(client, state('epoch-a', 6, {quickPins: pins(0.35, true)}));
    assert.equal(pending(client).length, 0, 'the observed mute settles the toggle');
    assert.equal(client.snapshot().commandError, '', 'an applied mute must not report a delayed timeout');
    assert.deepEqual(liveTimers(client), [], 'the settled mute leaves no timeout behind');

    const rejected = client.send('set_mixer_input_volume', `main\t${nodeId}\t${inputId}\t0.100`);
    deliver(client, state('epoch-a', 7, {quickPins: pins(0.35, true),
        acks: [ackFor(rejected, target, 'volume', 'error', null, 'Mixer input has no live owner', 7)]}));
    assert.equal(pending(client).length, 0, 'a rejected mixer command drops its optimistic value');
    assert.equal(client.snapshot().commandError, 'Mixer input has no live owner');
    assert.equal(client.snapshot().state.quickPins[0].mixerInputs[1].volume, 0.35,
        'the row falls back to the observed level instead of a masked success');
}

// Quick controls resolve by id; numeric values use the volume tolerance.
{
    const client = makeClient();
    const levelTarget = {quickControlId: 'vibro-level'};
    deliver(client, state('epoch-a', 2, {quickControls: [
        {id: 'vibro-mode', kind: 'choice', value: 1},
        {id: 'vibro-level', kind: 'range', value: 0.4},
    ]}));
    const levelRequest = client.send('set_quick_control', 'vibro-level\t0.41');
    assert.deepEqual(pending(client)[0], {requestId: levelRequest, target: levelTarget,
        property: 'value', value: 0.41, acknowledged: false, ackSequence: 0, observationTimer: 0});
    deliver(client, state('epoch-a', 3, {quickControls: [
        {id: 'vibro-mode', kind: 'choice', value: 1},
        {id: 'vibro-level', kind: 'range', value: 0.39},
    ], acks: [ackFor(levelRequest, levelTarget, 'value', 'ok', 0.41, undefined, 3)]}));
    assert.equal(pending(client).length, 1, 'a stale control value outside the tolerance cannot settle the intent');
    deliver(client, state('epoch-a', 4, {quickControls: [
        {id: 'vibro-mode', kind: 'choice', value: 1},
        {id: 'vibro-level', kind: 'range', value: 0.405},
    ]}));
    assert.equal(pending(client).length, 0, 'a range within 0.01 settles');
}

// Mute comparisons are exact: no tolerance can turn 0.99 into "muted".
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const pins = muted => [{id: 'gain-pin', routeId: 'main', nodeId: 'gain-1',
        gain: {routeId: 'main', nodeId: 'gain-1', volume: 0.5, muted}}];
    deliver(client, state('epoch-a', 2, {quickPins: pins(false)}));
    const requestId = client.send('setNodeMuted', {target, muted: true});
    deliver(client, state('epoch-a', 3, {quickPins: pins(false),
        acks: [ackFor(requestId, target, 'muted', 'ok', null, undefined, 3)]}));
    assert.equal(pending(client).length, 1, 'the unchanged observed mute cannot settle the toggle');
    deliver(client, state('epoch-a', 4, {quickPins: pins(true)}));
    assert.equal(pending(client).length, 0);
}

// Application and slider coalescing keeps the final drag value until it is observed.
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
    assert.deepEqual(pending(client)[0], {requestId: latestRequest, target, property: 'volume', value: 0.63,
        acknowledged: false, ackSequence: 0, observationTimer: 0});

    deliver(client, state('epoch-a', 2, {
        applications: [{id: applicationId, volume: 0.9, muted: false}],
    }));
    assert.equal(pending(client)[0].value, 0.63,
        'a stale application snapshot cannot roll back the final drag value before its ACK');
    deliver(client, state('epoch-a', 3, {
        applications: [{id: applicationId, volume: 0.63, muted: false}],
        acks: [ackFor(latestRequest, target, 'volume', 'ok', 0.63, undefined, 3)],
    }));
    assert.equal(pending(client).length, 0);
    assert.equal(client.snapshot().state.applications[0].volume, 0.63,
        'the matching State ACK settles against the observed application level');
}

// Generated pins of one generator share a node id; the instance key selects the addressed row.
{
    const client = makeClient();
    const nodeId = 'generated-apps';
    const pins = (first, second) => [
        {id: 'generated-pin:one', routeId: 'main', nodeId, instanceKey: 'instance-a',
            gain: {routeId: 'main', nodeId, instanceKey: 'instance-a', volume: first, muted: false}},
        {id: 'generated-pin:two', routeId: 'main', nodeId, instanceKey: 'instance-b',
            gain: {routeId: 'main', nodeId, instanceKey: 'instance-b', volume: second, muted: false}},
    ];
    deliver(client, state('epoch-a', 2, {quickPins: pins(0.9, 0.3)}));
    const target = {routeId: 'main', nodeId, instanceKey: 'instance-b'};
    const requestId = client.send('setNodeVolume', {target, value: 0.5});
    deliver(client, state('epoch-a', 3, {quickPins: pins(0.5, 0.3)}));
    assert.equal(pending(client).length, 1,
        'another instance observing the desired level cannot settle this pin');
    assert.equal(client.snapshot().commandError, '');
    deliver(client, state('epoch-a', 4, {quickPins: pins(0.5, 0.5)}));
    assert.equal(pending(client).length, 0, 'only the addressed instance observes and settles the intent');
    assert.equal(request(client, requestId), undefined);
}

// Route toggles resolve through the switch matrix instead of being dropped as a vanished target.
{
    const client = makeClient();
    const control = value => ({id: 'route:main:multi', routeId: 'main', nodeId: 'multi', switchNodeId: 'multi',
        nodeType: 'switch', kind: 'choice', multiple: true, value,
        options: [{id: 'in->monitor', label: 'Monitor', available: true},
            {id: 'in->vibro', label: 'Vibro', available: true}]});
    const pins = value => [{id: 'route-pin', routeId: 'main', nodeId: 'multi', routeControl: control(value)}];
    deliver(client, state('epoch-a', 2, {quickPins: pins(['in->monitor'])}));
    const target = {routeId: 'main', switchNodeId: 'multi', linkId: 'in->vibro'};
    const requestId = client.send('setRouteEnabled',
        {routeId: 'main', switchNodeId: 'multi', linkId: 'in->vibro', enabled: true});
    assert.deepEqual(pending(client)[0], {requestId, target, property: 'enabled', value: true,
        acknowledged: false, ackSequence: 0, observationTimer: 0});
    deliver(client, state('epoch-a', 3, {quickPins: pins(['in->monitor']),
        acks: [ackFor(requestId, target, 'enabled', 'ok', true, undefined, 3)]}));
    assert.equal(pending(client).length, 1,
        'a route toggle is not dropped while its link is still disabled');
    deliver(client, state('epoch-a', 4, {quickPins: pins(['in->monitor', 'in->vibro'])}));
    assert.equal(pending(client).length, 0, 'the enabled link settles the route toggle');
    assert.equal(client.snapshot().commandError, '');
}

// A successful command clears a stale error from an earlier failure.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const failed = client.send('setNodeVolume', {target, value: 0.1});
    deliver(client, state('epoch-a', 2, gainState(0.9, {
        acks: [ackFor(failed, target, 'volume', 'error', null, 'Engine refused', 2)]})));
    assert.equal(client.snapshot().commandError, 'Engine refused');
    const accepted = client.send('setNodeVolume', {target, value: 0.6});
    deliver(client, state('epoch-a', 3, gainState(0.9, {
        acks: [ackFor(accepted, target, 'volume', 'ok', 0.6, undefined, 3)]})));
    assert.equal(client.snapshot().commandError, '', 'an accepted ACK clears the earlier command error');
    assert.equal(pending(client).length, 1);
}

// An ordered observation that settles a command also clears a stale error.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const failed = client.send('setNodeVolume', {target, value: 0.1});
    deliver(client, state('epoch-a', 2, gainState(0.9, {
        acks: [ackFor(failed, target, 'volume', 'error', null, 'Engine refused', 2)]})));
    assert.equal(client.snapshot().commandError, 'Engine refused');
    client.send('setNodeVolume', {target, value: 0.4});
    deliver(client, state('epoch-a', 3, gainState(0.4)));
    assert.equal(pending(client).length, 0);
    assert.equal(client.snapshot().commandError, '', 'the matching observation clears the earlier command error');
}

// clear() must not pass a retired zero timer id to GLib.
{
    const client = makeClient();
    const target = {routeId: 'main', nodeId: 'gain-1', instanceKey: ''};
    const requestId = client.send('setNodeVolume', {target, value: 0.5});
    deliver(client, state('epoch-a', 2, gainState(0.9, {
        acks: [ackFor(requestId, target, 'volume', 'ok', 0.5, undefined, 2)]})));
    assert.ok(request(client, requestId).observationTimer, 'the observation timer is armed');
    client.stop();
    assert.equal(pending(client).length, 0, 'stopping a shell with a retired ACK timer still clears intents');
}

delete globalThis.audioMatrixStateClientFixture;
console.log('State client v5 tests cover ACK-versus-observation, deadlines, duplicates, epochs, and mixer rows');

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
