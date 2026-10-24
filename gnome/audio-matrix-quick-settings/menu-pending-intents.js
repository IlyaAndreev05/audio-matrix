import GLib from 'gi://GLib';

const ACK_TIMEOUT_SECONDS = 5;
const OBSERVATION_TIMEOUT_MILLIS = 3000;
const VOLUME_TOLERANCE = 0.01;

function canonical(value) {
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
    if (value && typeof value === 'object')
        return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
    return JSON.stringify(value);
}

function intentKey(target, property) {
    return `${canonical(target)}\u0000${property}`;
}

function ackMatches(ack, intent) {
    return ack?.requestId === intent.requestId
        && ack?.property === intent.property
        && canonical(ack?.target) === canonical(intent.target);
}

function stringsEqual(left, right) {
    return String(left ?? '') === String(right ?? '');
}

function numbersClose(observed, desired) {
    return Number.isFinite(observed) && Number.isFinite(desired)
        && Math.abs(observed - desired) <= VOLUME_TOLERANCE;
}

function propertyMatches(property, observed, desired, exact = false) {
    if (property === 'muted' || property === 'enabled' || exact) return observed === desired;
    return numbersClose(observed, desired);
}

function visitPins(pins, visit) {
    for (const pin of pins || []) {
        const result = visit(pin);
        if (result) return result;
        const nested = visitPins(pin.children, visit);
        if (nested) return nested;
    }
    return null;
}

function pinLevel(state, target) {
    return visitPins(state?.quickPins, pin => {
        for (const level of [pin.gain, pin.sharedGain]) {
            if (!level || !target.routeId) continue;
            if (!stringsEqual(level.routeId, target.routeId)) continue;
            if (!stringsEqual(level.nodeId, target.nodeId)) continue;
            // Generated pins of one generator share its node id; only the instance key disambiguates.
            if (!stringsEqual(level.instanceKey, target.instanceKey)) continue;
            return level;
        }
        const sourceGain = pin.sourceGain;
        if (sourceGain && target.routeId && stringsEqual(sourceGain.routeId, target.routeId)
            && (stringsEqual(sourceGain.gainNodeId, target.nodeId) || stringsEqual(sourceGain.sourceNodeId, target.nodeId)))
            return sourceGain;
        const output = pin.output;
        if (output && target.routeId && stringsEqual(pin.routeId, target.routeId)
            && (stringsEqual(output.graphGainNodeId, target.nodeId) || stringsEqual(output.id, target.nodeId)))
            return output;
        return null;
    });
}

function mixerRow(state, target) {
    return visitPins(state?.quickPins, pin => {
        if (!stringsEqual(pin.routeId, target.routeId) || !stringsEqual(pin.nodeId, target.nodeId))
            return null;
        return (pin.mixerInputs || []).find(input => stringsEqual(input.id, target.inputId)) || null;
    });
}

function browserTab(state, target) {
    const visitApplications = applications => {
        for (const application of applications || []) {
            for (const tab of application.browserTabs || []) {
                if (stringsEqual(tab.browser, target.browser) && stringsEqual(tab.instanceId, target.instanceId)
                    && stringsEqual(tab.profileId, target.profileId) && stringsEqual(tab.sessionToken, target.sessionToken))
                    return tab;
            }
            const nested = visitApplications(application.children);
            if (nested) return nested;
        }
        return null;
    };
    return visitApplications(state?.applications) || visitPins(state?.quickPins, pin => {
        const visitTabs = tabs => {
            for (const tab of tabs || []) {
                if (stringsEqual(tab.browser, target.browser) && stringsEqual(tab.instanceId, target.instanceId)
                    && stringsEqual(tab.profileId, target.profileId) && stringsEqual(tab.sessionToken, target.sessionToken))
                    return tab;
            }
            return null;
        };
        return visitTabs(pin.browserTabs);
    });
}

/**
 * Resolves the observed property an optimistic intent is waiting for.
 * Returns `{found:false}` when the published target no longer exists, so its intent can be dropped.
 */
export function observedProperty(state, target, property) {
    if (!target || typeof target !== 'object') return {found: false};
    if (target.inputId !== undefined) {
        const input = mixerRow(state, target);
        if (!input) return {found: false};
        return {found: true, value: property === 'volume' ? input.volume : input.muted};
    }
    if (target.nodeId !== undefined) {
        const level = pinLevel(state, target);
        if (level) return {found: true, value: property === 'volume' ? level.volume : level.muted};
        const application = (state?.applications || []).find(item =>
            item.commandTarget && canonical(item.commandTarget) === canonical(target));
        if (application) return {found: true, value: property === 'volume' ? application.volume : application.muted};
        const tab = browserTab(state, target);
        if (tab) return {found: true, value: property === 'volume' ? tab.volume : tab.muted};
        return {found: false};
    }
    if (target.linkId !== undefined) {
        const control = visitPins(state?.quickPins, pin => {
            const routeControl = pin.routeControl;
            return routeControl && stringsEqual(routeControl.routeId ?? pin.routeId, target.routeId)
                && stringsEqual(routeControl.switchNodeId ?? routeControl.nodeId, target.switchNodeId)
                ? routeControl : null;
        });
        if (!control) return {found: false};
        const linkId = String(target.linkId);
        if (Array.isArray(control.options) && !control.options.some(option => stringsEqual(option?.id, linkId)))
            return {found: false};
        const selected = (control.value || []).map(value => String(value));
        return {found: true, value: selected.includes(linkId), exact: true};
    }
    if (target.applicationId !== undefined) {
        const application = (state?.applications || []).find(item => stringsEqual(item.id, target.applicationId));
        if (!application) return {found: false};
        return {found: true, value: property === 'volume' ? application.volume : application.muted};
    }
    if (target.sessionToken !== undefined || target.browser !== undefined) {
        const tab = browserTab(state, target);
        if (!tab) return {found: false};
        return {found: true, value: property === 'volume' ? tab.volume : tab.muted};
    }
    if (target.quickControlId !== undefined) {
        const control = (state?.quickControls || []).find(item => stringsEqual(item.id, target.quickControlId));
        if (!control) return {found: false};
        return {found: true, value: control.value,
            exact: property !== 'value' || control.kind === 'choice' || control.kind === 'links'};
    }
    return {found: false};
}

/**
 * Keeps optimistic values until an accepted ordered State observes them.
 *
 * <p>An acknowledgment proves that the engine accepted the write, not that the level is already
 * observable. A success ACK retires the five-second engine timeout and arms a three-second
 * observation deadline; only a matching State (or that deadline) retires the optimistic value.</p>
 */
export default class AudioMatrixPendingIntents {
    constructor({onChange, onError}) {
        this._onChange = onChange;
        this._onError = onError;
        this._pending = new Map();
        this._requests = new Map();
    }

    snapshot() {
        return [...this._pending.values()].map(intent => ({
            requestId: intent.requestId, target: {...intent.target},
            property: intent.property, value: intent.value,
            acknowledged: intent.acknowledged, ackSequence: intent.ackSequence,
            observationTimer: intent.observationTimer,
        }));
    }

    track(requestId, shape, epoch, sequence) {
        const intent = shape.target ? {
            key: intentKey(shape.target, shape.property), requestId, target: shape.target,
            property: shape.property, value: shape.desired, baselineEpoch: epoch, baselineSequence: sequence,
            acknowledged: false, ackSequence: 0, observationTimer: 0,
        } : null;
        if (intent) {
            const previous = this._pending.get(intent.key);
            if (previous) this._clearRequest(previous.requestId);
            if (shape.property === 'volume') {
                const muteKey = intentKey(shape.target, 'muted');
                const mute = this._pending.get(muteKey);
                if (mute) {
                    this._clearRequest(mute.requestId);
                    this._pending.delete(muteKey);
                }
            }
            this._pending.set(intent.key, intent);
        }
        const timer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, ACK_TIMEOUT_SECONDS, () => {
            const request = this._requests.get(requestId);
            if (!request || request.acknowledged) return GLib.SOURCE_REMOVE;
            this._dropIntent(request.intent);
            this._requests.delete(requestId);
            this._onError('Нет ответа от движка Audio Matrix');
            this._onChange();
            return GLib.SOURCE_REMOVE;
        });
        this._requests.set(requestId, {timer, observationTimer: 0, intent,
            acknowledged: false, ackSequence: 0});
        return intent;
    }

    acceptAcks(acks) {
        if (!Array.isArray(acks)) return;
        for (const ack of acks) {
            const request = this._requests.get(ack?.requestId);
            if (!request) continue;
            const status = ack?.status === 'error' ? 'error' : 'ok';
            if (request.intent === null) {
                this._clearRequest(ack.requestId);
                this._onError(status === 'error' ? String(ack.error || 'Команда не выполнена') : '');
                continue;
            }
            if (status === 'error') {
                // An error belongs to its requestId even when the reported target is incomplete.
                this._clearRequest(ack.requestId);
                this._dropIntent(request.intent);
                this._onError(String(ack.error || 'Команда не выполнена'));
                continue;
            }
            if (request.acknowledged) continue;
            if (!ackMatches(ack, request.intent)) continue;
            this._retireAckTimer(request);
            request.acknowledged = true;
            request.ackSequence = Number.isSafeInteger(ack.stateSequence) ? ack.stateSequence : 0;
            const intent = request.intent;
            if (intent && this._pending.get(intent.key)?.requestId === request.intent.requestId) {
                intent.acknowledged = true;
                intent.ackSequence = request.ackSequence;
                this._armObservationTimer(ack.requestId, request);
                this._onError('');
            }
        }
    }

    /**
     * Settles intents against one accepted ordered State of the current epoch.
     * A matching observation wins even before a separate ACK; a vanished target is dropped.
     */
    observe(state, epoch) {
        if (!state) return;
        const sequence = state.stateSequence;
        for (const [requestId, request] of [...this._requests]) {
            const intent = request.intent;
            if (!intent || this._pending.get(intent.key)?.requestId !== requestId) continue;
            if (intent.baselineEpoch !== epoch || !Number.isSafeInteger(sequence)
                || sequence <= intent.baselineSequence) continue;
            const observed = observedProperty(state, intent.target, intent.property);
            if (!observed.found) {
                this._clearRequest(requestId);
                this._dropIntent(intent);
                continue;
            }
            if (propertyMatches(intent.property, observed.value, intent.value, observed.exact)) {
                this._clearRequest(requestId);
                this._dropIntent(intent);
                this._onError('');
            }
        }
    }

    cancel(requestId) {
        const request = this._requests.get(requestId);
        if (!request) return;
        this._clearRequest(requestId);
        this._dropIntent(request.intent);
    }

    clear() {
        for (const {timer, observationTimer} of this._requests.values()) {
            if (timer) GLib.Source.remove(timer);
            if (observationTimer) GLib.Source.remove(observationTimer);
        }
        this._requests.clear();
        this._pending.clear();
    }

    stop() {
        this.clear();
    }

    _armObservationTimer(requestId, request) {
        if (request.observationTimer) return;
        request.observationTimer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, OBSERVATION_TIMEOUT_MILLIS, () => {
            request.observationTimer = 0;
            const intent = request.intent;
            if (this._requests.get(requestId) !== request
                || !intent || this._pending.get(intent.key)?.requestId !== requestId)
                return GLib.SOURCE_REMOVE;
            this._requests.delete(requestId);
            this._dropIntent(intent);
            this._onError('Изменение принято, но новое состояние не подтверждено');
            this._onChange();
            return GLib.SOURCE_REMOVE;
        });
        if (request.intent) request.intent.observationTimer = request.observationTimer;
    }

    _retireAckTimer(request) {
        if (!request.timer) return;
        GLib.Source.remove(request.timer);
        request.timer = 0;
    }

    _dropIntent(intent) {
        if (!intent) return;
        if (this._pending.get(intent.key)?.requestId === intent.requestId)
            this._pending.delete(intent.key);
    }

    _clearRequest(requestId) {
        const request = this._requests.get(requestId);
        if (!request) return;
        if (request.timer) GLib.Source.remove(request.timer);
        if (request.observationTimer) GLib.Source.remove(request.observationTimer);
        this._requests.delete(requestId);
    }
}
