import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {systemLanguage} from './menu-text.js';

const OBJECT_PATH = '/org/ilya/AudioMatrix';
const INTERFACE = 'org.ilya.AudioMatrix';
const API_VERSION = 4;
const STALE_AFTER_SECONDS = 12;

export default class AudioMatrixStateClient {
    constructor(onChange) {
        this._onChange = onChange;
        this._language = systemLanguage();
        this._state = null;
        this._stale = false;
        this._lastPayload = '';
        this._lastStateAt = GLib.get_monotonic_time();
        this._sliderTimers = new Map();
        this._pendingCommands = new Map();
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
                this._state = null;
                this._changed();
            }
            return GLib.SOURCE_CONTINUE;
        });
    }

    snapshot() {
        const available = Boolean(this._state?.apiVersion === API_VERSION && this._state?.connected && !this._stale);
        return {
            state: this._state,
            language: this._language,
            stale: this._stale,
            available,
            commandError: this._commandError,
            apiMismatch: this._apiMismatch,
        };
    }

    _receiveState(parameters) {
        try {
            const wasUnavailable = !this.snapshot().available;
            this._lastStateAt = GLib.get_monotonic_time();
            this._stale = false;
            const state = JSON.parse(parameters.deepUnpack()[0]);
            this._apiMismatch = state.apiVersion === API_VERSION ? ''
                : `Audio Matrix API ${state.apiVersion ?? '—'}; extension requires API ${API_VERSION}`;
            this._state = state;
            const payload = JSON.stringify(state);
            if (payload === this._lastPayload) {
                if (wasUnavailable)
                    this._changed();
                return;
            }
            this._lastPayload = payload;
            if (state.language === 'en' || state.language === 'ru')
                this._language = state.language;
            this._changed();
        } catch (error) {
            console.debug(`Audio Matrix state parse failed: ${error}`);
        }
    }

    _receiveResult(parameters) {
        try {
            const [version, requestId, status, message] = parameters.deepUnpack();
            if (version !== API_VERSION)
                return;
            const timer = this._pendingCommands.get(requestId);
            if (timer)
                GLib.Source.remove(timer);
            this._pendingCommands.delete(requestId);
            if (status !== 'ok')
                this._commandError = String(message || 'Unknown error');
            else
                this._commandError = '';
            this._changed();
        } catch (error) {
            console.debug(`Audio Matrix command result parse failed: ${error}`);
        }
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
        if (immediate) {
            callback(value);
            return;
        }
        const timer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 140, () => {
            this._sliderTimers.delete(key);
            callback(value);
            return GLib.SOURCE_REMOVE;
        });
        this._sliderTimers.set(key, timer);
    }

    send(name, value) {
        if (!this.snapshot().available)
            return;
        const requestId = GLib.uuid_string_random();
        const timer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 5, () => {
            this._pendingCommands.delete(requestId);
            this._commandError = 'No response from the Audio Matrix engine';
            this._changed();
            return GLib.SOURCE_REMOVE;
        });
        this._pendingCommands.set(requestId, timer);
        this._changed();
        try {
            Gio.DBus.session.emit_signal(null, OBJECT_PATH, INTERFACE, 'Command',
                new GLib.Variant('(ussss)', [API_VERSION, requestId, String(this._state?.revision ?? ''), name, value]));
        } catch (error) {
            GLib.Source.remove(timer);
            this._pendingCommands.delete(requestId);
            this._commandError = String(error);
            this._changed();
        }
    }

    stop() {
        for (const timer of this._sliderTimers.values())
            GLib.Source.remove(timer);
        this._sliderTimers.clear();
        for (const timer of this._pendingCommands.values())
            GLib.Source.remove(timer);
        this._pendingCommands.clear();
        if (this._renderTimer)
            GLib.Source.remove(this._renderTimer);
        if (this._watchdogId)
            GLib.Source.remove(this._watchdogId);
        for (const id of this._signalIds)
            Gio.DBus.session.signal_unsubscribe(id);
        this._signalIds = [];
    }
}
