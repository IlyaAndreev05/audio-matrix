import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
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

function commandShape(name, value, state, pending) {
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
    if (name === 'setBrowserTabMuted') {
        const target = normalizeTarget(value?.target, ['browser', 'instanceId', 'profileId', 'sessionToken']);
        if (typeof value?.muted !== 'boolean')
            throw new TypeError('Browser tab mute must be boolean');
        return {payload: {target, muted: value.muted}, target, property: 'muted', desired: value.muted};
    }
    if (name === 'set_app_volume' || name === 'toggle_app_mute') {
        const [applicationId, rawVolume] = String(value).split('\t');
        const target = {applicationId};
        if (name === 'set_app_volume') {
            const volume = Number(rawVolume);
            if (!applicationId || !Number.isFinite(volume) || volume < 0 || volume > 1)
                throw new TypeError('Application volume must be between 0 and 1');
            return {payload: value, target, property: 'volume', desired: volume};
        }
        const current = pending.find(intent => intent.property === 'muted'
            && canonical(intent.target) === canonical(target));
        const application = (state?.applications || []).find(item => String(item.id) === applicationId);
        return {payload: value, target, property: 'muted',
            desired: !(current?.value ?? Boolean(application?.muted))};
    }
    if (name === 'set_quick_control') {
        const [quickControlId, rawValue] = String(value).split('\t');
        const desired = Number(rawValue);
        if (!quickControlId || !Number.isFinite(desired)) throw new TypeError('Control value must be finite');
        return {payload: value, target: {quickControlId}, property: 'value', desired};
    }
    if (name === 'set_mixer_input_volume') {
        const [routeId, nodeId, inputId, rawValue] = String(value).split('\t');
        const desired = Number(rawValue);
        if (!routeId || !nodeId || !inputId || !Number.isFinite(desired) || desired < 0 || desired > 1)
            throw new TypeError('Mixer input volume must be between 0 and 1');
        return {payload: value, target: {routeId, nodeId, inputId}, property: 'volume', desired};
    }
    return {payload: value, target: null, property: null, desired: null};
}

export default class AudioMatrixStateClient {
    constructor(onChange) {
        this._language = 'ru';
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
                this._apiMismatch = `Audio Matrix API ${state.apiVersion ?? '—'}; расширению требуется API ${API_VERSION}`;
                this._changed();
                return;
            }
            if (typeof state.stateEpoch !== 'string' || state.stateEpoch.length === 0
                || !Number.isSafeInteger(state.stateSequence) || state.stateSequence < 1) {
                this._apiMismatch = 'В состоянии Audio Matrix API v5 отсутствует эпоха или номер обновления';
                this._changed();
                return;
            }
            const nextEpoch = state.stateEpoch !== this._stateEpoch;
            if (!nextEpoch && state.stateSequence <= this._stateSequence) {
                if (state.stateSequence === this._stateSequence) {
                    this._lastStateAt = GLib.get_monotonic_time();
                    if (this._stale) {
                        this._stale = false;
                        this._changed();
                    }
                }
                return;
            }
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
            shape = commandShape(name, value, this._state, this._intentTracker.snapshot());
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
