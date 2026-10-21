import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {systemLanguage} from './menu-text.js';

const OBJECT_PATH = '/org/ilya/AudioMatrix';
const INTERFACE = 'org.ilya.AudioMatrix';
const API_VERSION = 5;
const STALE_AFTER_SECONDS = 12;
const COMMAND_TIMEOUT_SECONDS = 5;
const RETIRED_EPOCH_LIMIT = 16;

function canonical(value) {
    if (Array.isArray(value))
        return `[${value.map(canonical).join(',')}]`;
    if (value && typeof value === 'object')
        return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
    return JSON.stringify(value);
}

export function intentKey(target, property) {
    return `${canonical(target)}\u0000${String(property)}`;
}

function intentFor(name, payload) {
    if (name === 'select_profile' && payload && typeof payload === 'object')
        return {target: {profileId: String(payload.profileId || '')}, property: 'activeProfileId',
            desired: String(payload.profileId || '')};
    if (name === 'setNodeVolume' && payload && typeof payload === 'object')
        return {target: payload.target, property: 'volume', desired: payload.value};
    if (name === 'setNodeMuted' && payload && typeof payload === 'object')
        return {target: payload.target, property: 'muted', desired: payload.muted};
    if (name === 'setRouteEnabled' && payload && typeof payload === 'object') {
        const target = {routeId: payload.routeId, switchNodeId: payload.switchNodeId, linkId: payload.linkId};
        return {target, property: 'enabled', desired: payload.enabled};
    }
    if (name === 'set_source_volume' || name === 'toggle_source_mute') {
        const [routeId, , gainNodeId, value] = String(payload).split('\t');
        if (routeId && gainNodeId)
            return {target: {routeId, nodeId: gainNodeId, instanceKey: ''},
                property: name === 'set_source_volume' ? 'volume' : 'muted',
                desired: name === 'set_source_volume' ? Number(value) : undefined};
    }
    return null;
}

function stateVersion(state) {
    return Number.isSafeInteger(state?.stateSequence) && state.stateSequence >= 0
        && typeof state?.stateEpoch === 'string' && state.stateEpoch.length > 0;
}

export default class AudioMatrixStateClient {
    constructor(onChange) {
        this._onChange = onChange;
        this._clientId = GLib.uuid_string_random();
        this._language = systemLanguage();
        this._state = null;
        this._stale = false;
        this._epoch = '';
        this._sequence = -1;
        this._retiredEpochs = new Set();
        this._lastStateAt = GLib.get_monotonic_time();
        this._sliderTimers = new Map();
        this._sliderValues = new Map();
        this._pendingCommands = new Map();
        this._latestIntentByKey = new Map();
        this._draggingSliders = 0;
        this._renderPending = false;
        this._renderTimer = 0;
        this._commandError = '';
        this._apiMismatch = '';
        this._signalIds = [];
        this._watchdogId = 0;
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
                this._failPending('Audio Matrix connection timed out');
                this._state = null;
                this._changed();
            }
            return GLib.SOURCE_CONTINUE;
        });
    }

    snapshot() {
        const pending = new Map();
        for (const [key, requestId] of this._latestIntentByKey) {
            const command = this._pendingCommands.get(requestId);
            if (command)
                pending.set(key, {target: command.target, property: command.property, desired: command.desired,
                    requestId, baselineEpoch: command.baselineEpoch, baselineSequence: command.baselineSequence});
        }
        const available = Boolean(this._state?.apiVersion === API_VERSION && this._state?.connected && !this._stale);
        return {
            state: this._state,
            language: this._language,
            stale: this._stale,
            available,
            commandError: this._commandError,
            apiMismatch: this._apiMismatch,
            pending,
        };
    }

    _receiveState(parameters) {
        try {
            const state = JSON.parse(parameters.deepUnpack()[0]);
            const apiMismatch = state.apiVersion === API_VERSION ? ''
                : `Audio Matrix API ${state.apiVersion ?? '—'}; extension requires API ${API_VERSION}`;
            if (!stateVersion(state)) {
                this._apiMismatch = apiMismatch;
                this._state = null;
                this._failPending(apiMismatch || 'Audio Matrix state has no epoch or sequence');
                this._changed();
                return;
            }

            const nextEpoch = String(state.stateEpoch);
            if (nextEpoch === this._epoch) {
                if (state.stateSequence <= this._sequence)
                    return;
            } else {
                if (this._retiredEpochs.has(nextEpoch))
                    return;
                const hadPending = this._pendingCommands.size > 0;
                if (this._epoch) {
                    this._retiredEpochs.add(this._epoch);
                    if (this._retiredEpochs.size > RETIRED_EPOCH_LIMIT)
                        this._retiredEpochs.delete(this._retiredEpochs.values().next().value);
                }
                this._epoch = nextEpoch;
                this._sequence = -1;
                this._failPending(hadPending ? 'Audio Matrix engine restarted before confirming the command' : '');
                if (!hadPending)
                    this._commandError = '';
            }
            this._apiMismatch = apiMismatch;

            this._lastStateAt = GLib.get_monotonic_time();
            this._stale = false;
            this._sequence = state.stateSequence;
            this._state = state;
            if (state.language === 'en' || state.language === 'ru')
                this._language = state.language;
            this._acceptAcks(state);
            this._changed();
        } catch (error) {
            console.debug(`Audio Matrix state parse failed: ${error}`);
        }
    }

    _acceptAcks(state) {
        for (const ack of state.commandAcks || []) {
            const requestId = String(ack.requestId);
            const command = this._pendingCommands.get(requestId);
            if (!command || command.baselineEpoch !== this._epoch)
                continue;
            if (command.intentKey) {
                const ackKey = intentKey(ack.target, ack.property);
                if (ackKey !== command.intentKey || this._latestIntentByKey.get(ackKey) !== requestId)
                    continue;
            }
            this._settleCommand(requestId);
            if (ack.status === 'ok')
                this._commandError = '';
            else
                this._commandError = String(ack.error || ack.status || 'Audio Matrix rejected the command');
        }
    }

    _receiveResult(parameters) {
        try {
            const [version, requestId, status, message] = parameters.deepUnpack();
            if (version !== API_VERSION)
                return;
            const id = String(requestId);
            const command = this._pendingCommands.get(id);
            if (!command || status === 'ok')
                return;
            this._settleCommand(id);
            this._commandError = String(message || 'Unknown error');
            this._changed();
        } catch (error) {
            console.debug(`Audio Matrix command result parse failed: ${error}`);
        }
    }

    _settleCommand(requestId) {
        const command = this._pendingCommands.get(requestId);
        if (!command)
            return;
        if (command.timer)
            GLib.Source.remove(command.timer);
        this._pendingCommands.delete(requestId);
        if (command.intentKey && this._latestIntentByKey.get(command.intentKey) === requestId)
            this._latestIntentByKey.delete(command.intentKey);
    }

    _failPending(message) {
        for (const requestId of this._pendingCommands.keys())
            this._settleCommand(requestId);
        if (message)
            this._commandError = message;
    }

    _changed() {
        if (this._draggingSliders > 0) {
            this._renderPending = true;
            return;
        }
        this._onChange?.(this.snapshot());
    }

    beginSliderDrag() {
        this._draggingSliders++;
    }

    endSliderDrag() {
        this._draggingSliders = Math.max(0, this._draggingSliders - 1);
        if (this._draggingSliders !== 0 || !this._renderPending)
            return;
        this._renderPending = false;
        if (this._renderTimer)
            GLib.Source.remove(this._renderTimer);
        this._renderTimer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 250, () => {
            this._renderTimer = 0;
            if (this._draggingSliders === 0)
                this._onChange?.(this.snapshot());
            else
                this._renderPending = true;
            return GLib.SOURCE_REMOVE;
        });
    }

    queueSliderCommand(id, value, callback, immediate = false) {
        const key = String(id);
        const previous = this._sliderTimers.get(key);
        if (previous) {
            GLib.Source.remove(previous);
            this._sliderTimers.delete(key);
        }
        this._sliderValues.set(key, {value, callback});
        if (immediate) {
            const pending = this._sliderValues.get(key);
            this._sliderValues.delete(key);
            pending?.callback(pending.value);
            return;
        }
        if (this._draggingSliders > 0)
            return;
        const timer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 140, () => {
            this._sliderTimers.delete(key);
            const pending = this._sliderValues.get(key);
            this._sliderValues.delete(key);
            pending?.callback(pending.value);
            return GLib.SOURCE_REMOVE;
        });
        this._sliderTimers.set(key, timer);
    }

    send(name, payload) {
        if (!this.snapshot().available)
            return null;
        const requestId = `${this._clientId}:${GLib.uuid_string_random()}`;
        const intent = intentFor(name, payload);
        const key = intent ? intentKey(intent.target, intent.property) : '';
        if (key) {
            const priorRequest = this._latestIntentByKey.get(key);
            if (priorRequest)
                this._settleCommand(priorRequest);
        }
        const command = {
            timer: 0, intentKey: key, target: intent?.target ?? null, property: intent?.property ?? '',
            desired: intent?.desired, baselineEpoch: this._epoch, baselineSequence: this._sequence,
        };
        command.timer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, COMMAND_TIMEOUT_SECONDS, () => {
            if (!this._pendingCommands.has(requestId))
                return GLib.SOURCE_REMOVE;
            this._settleCommand(requestId);
            this._commandError = 'No response from the Audio Matrix engine';
            this._changed();
            return GLib.SOURCE_REMOVE;
        });
        this._pendingCommands.set(requestId, command);
        if (key)
            this._latestIntentByKey.set(key, requestId);
        this._commandError = '';
        this._changed();
        try {
            const value = payload && typeof payload === 'object' ? JSON.stringify(payload) : String(payload ?? '');
            Gio.DBus.session.emit_signal(null, OBJECT_PATH, INTERFACE, 'Command',
                new GLib.Variant('(ussss)', [API_VERSION, requestId, String(this._state?.revision ?? ''), name, value]));
            return requestId;
        } catch (error) {
            this._settleCommand(requestId);
            this._commandError = String(error);
            this._changed();
            return null;
        }
    }

    stop() {
        for (const timer of this._sliderTimers.values())
            GLib.Source.remove(timer);
        this._sliderTimers.clear();
        this._sliderValues.clear();
        this._failPending('');
        if (this._renderTimer)
            GLib.Source.remove(this._renderTimer);
        if (this._watchdogId)
            GLib.Source.remove(this._watchdogId);
        for (const id of this._signalIds)
            Gio.DBus.session.signal_unsubscribe(id);
        this._signalIds = [];
    }
}
