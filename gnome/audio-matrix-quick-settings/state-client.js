import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {systemLanguage} from './menu-text.js';
import AudioMatrixPendingIntents from './menu-pending-intents.js';
import AudioMatrixStateRenderScheduler from './menu-state-render-scheduler.js';

const OBJECT_PATH = '/org/ilya/AudioMatrix';
const INTERFACE = 'org.ilya.AudioMatrix';
const API_VERSION = 5;
const STALE_AFTER_SECONDS = 12;


function normalizeTarget(target, keys) {
    if (!target || typeof target !== 'object' || Array.isArray(target))
        throw new TypeError('Command target must be an object');
    const normalized = {};
    for (const key of keys) {
        const value = target[key];
        if (key === 'instanceKey' && value == null) {
            normalized[key] = '';
            continue;
        }
        if (typeof value !== 'string' || value.length === 0 && key !== 'instanceKey')
            throw new TypeError(`Command target ${key} must be a non-empty string`);
        normalized[key] = value;
    }
    return normalized;
}

function canonical(value) {
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
    if (value && typeof value === 'object')
        return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
    return JSON.stringify(value);
}

function commandShape(name, value) {
    if (name === 'setNodeVolume' || name === 'setNodeMuted') {
        const target = normalizeTarget(value?.target, ['routeId', 'nodeId', 'instanceKey']);
        const property = name === 'setNodeVolume' ? 'volume' : 'muted';
        const field = name === 'setNodeVolume' ? 'value' : 'muted';
        const next = value?.[field];
        if (name === 'setNodeVolume' && (!Number.isFinite(next) || next < 0 || next > 1))
            throw new TypeError('Node volume must be between 0 and 1');
        if (name === 'setNodeMuted' && typeof next !== 'boolean')
            throw new TypeError('Node mute must be boolean');
        return {payload: {target, [field]: next}, target, property, desired: next};
    }
    if (name === 'setRouteEnabled') {
        const target = normalizeTarget(value, ['routeId', 'switchNodeId', 'linkId']);
        if (typeof value.enabled !== 'boolean')
            throw new TypeError('Route enabled state must be boolean');
        return {payload: {...target, enabled: value.enabled}, target, property: 'enabled', desired: value.enabled};
    }
    if (name === 'select_profile') {
        const target = normalizeTarget(value, ['profileId']);
        return {payload: target, target, property: 'activeProfileId', desired: target.profileId};
    }
    return {payload: value, target: null, property: null, desired: null};
}

function intentKey(target, property) {
    return `${canonical(target)}\u0000${property}`;
}

function ackMatches(ack, intent) {
    return ack?.requestId === intent.requestId
        && ack?.property === intent.property
        && canonical(ack?.target) === canonical(intent.target);
}
export default class AudioMatrixStateClient {
    constructor(onChange) {
        this._language = systemLanguage();
        this._state = null;
        this._stale = false;
        this._lastStateAt = GLib.get_monotonic_time();
        this._stateEpoch = '';
        this._stateSequence = 0;
        this._clientId = GLib.uuid_string_random();
        this._commandError = '';
        this._apiMismatch = '';
        this._signalIds = [];
        this._watchdogId = 0;
        this._renderScheduler = new AudioMatrixStateRenderScheduler(onChange);
        this._intentTracker = new AudioMatrixPendingIntents({
            onChange: () => this._changed(),
            onError: message => { this._commandError = message; },
        });
    }
    start() {
        this._signalIds.push(Gio.DBus.session.signal_subscribe(
            null, INTERFACE, 'State', OBJECT_PATH, null, Gio.DBusSignalFlags.NONE,
            (_connection, _sender, _path, _iface, _signal, parameters) => this._receiveState(parameters)));
        this._signalIds.push(Gio.DBus.session.signal_subscribe(
            null, INTERFACE, 'Result', OBJECT_PATH, null, Gio.DBusSignalFlags.NONE,
            (_connection, _sender, _path, _iface, _signal, parameters) => this._receiveResult(parameters)));
        this._watchdogId = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 4, () => {
            const age = (GLib.get_monotonic_time() - this._lastStateAt) / GLib.USEC_PER_SEC;
            if (age > STALE_AFTER_SECONDS && !this._stale) {
                this._stale = true;
                this._changed();
            }
            return GLib.SOURCE_CONTINUE;
        });
    }

    snapshot() {
        const available = Boolean(this._state?.apiVersion === API_VERSION && this._state?.connected
            && this._stateEpoch && !this._stale && !this._apiMismatch);
        return {
            state: this._state,
            language: this._language,
            stale: this._stale,
            available,
            commandError: this._commandError,
            apiMismatch: this._apiMismatch,
            pendingIntents: this._intentTracker.snapshot(),
        };
    }

    _receiveState(parameters) {
        try {
            const state = JSON.parse(parameters.deepUnpack()[0]);
            if (state.apiVersion !== API_VERSION) {
                this._apiMismatch = `Audio Matrix API ${state.apiVersion ?? '—'}; extension requires API ${API_VERSION}`;
                this._changed();
                return;
            }
            if (typeof state.stateEpoch !== 'string' || state.stateEpoch.length === 0
                || !Number.isSafeInteger(state.stateSequence) || state.stateSequence < 1) {
                this._apiMismatch = 'Audio Matrix API v5 state is missing its epoch or sequence';
                this._changed();
                return;
            }
            const nextEpoch = state.stateEpoch !== this._stateEpoch;
            if (!nextEpoch && state.stateSequence <= this._stateSequence)
                return;
            this._lastStateAt = GLib.get_monotonic_time();
            this._stale = false;
            this._apiMismatch = '';
            if (nextEpoch) {
                this._intentTracker.clear();
                this._stateSequence = 0;
                this._commandError = '';
            }
            this._stateEpoch = state.stateEpoch;
            this._stateSequence = state.stateSequence;
            this._state = state;
            if (state.language === 'en' || state.language === 'ru')
                this._language = state.language;
            this._intentTracker.acceptAcks(state.commandAcks);
            this._changed();
        } catch (error) {
            console.debug(`Audio Matrix state parse failed: ${error}`);
        }
    }

    _receiveResult(_parameters) {}

    _changed() {
        this._renderScheduler.changed(() => this.snapshot());
    }

    beginSliderDrag() {
        this._renderScheduler.beginSliderDrag();
    }

    endSliderDrag() {
        this._renderScheduler.endSliderDrag(() => this.snapshot());
    }

    queueSliderCommand(id, value, callback, immediate = false) {
        this._renderScheduler.queueSliderCommand(id, value, callback, immediate);
    }

    send(name, value) {
        if (!this.snapshot().available)
            return null;
        let shape;
        try {
            shape = commandShape(name, value);
        } catch (error) {
            this._commandError = String(error.message || error);
            this._changed();
            return null;
        }
        const requestId = `${this._clientId}:${GLib.uuid_string_random()}`;
        this._intentTracker.track(requestId, shape, this._stateEpoch, this._stateSequence);
        this._changed();
        try {
            const payload = typeof shape.payload === 'string' ? shape.payload : JSON.stringify(shape.payload);
            Gio.DBus.session.emit_signal(null, OBJECT_PATH, INTERFACE, 'Command',
                new GLib.Variant('(ussss)', [API_VERSION, requestId, String(this._state?.revision ?? ''), name, payload]));
        } catch (error) {
            this._intentTracker.cancel(requestId);
            this._commandError = String(error);
            this._changed();
            return null;
        }
        return requestId;
    }


    stop() {
        this._renderScheduler.stop();
        this._intentTracker.stop();
        if (this._watchdogId)
            GLib.Source.remove(this._watchdogId);
        for (const id of this._signalIds)
            Gio.DBus.session.signal_unsubscribe(id);
        this._signalIds = [];
    }
}
