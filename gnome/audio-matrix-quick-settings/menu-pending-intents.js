import GLib from 'gi://GLib';

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

/** Keeps optimistic values until a matching ordered State acknowledgment arrives. */
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
        }));
    }

    track(requestId, shape, epoch, sequence) {
        const intent = shape.target ? {
            key: intentKey(shape.target, shape.property), requestId, target: shape.target,
            property: shape.property, value: shape.desired, baselineEpoch: epoch, baselineSequence: sequence,
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
        }
        if (intent) this._pending.set(intent.key, intent);
        const timer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 5, () => {
            this._requests.delete(requestId);
            if (intent && this._pending.get(intent.key)?.requestId !== requestId)
                return GLib.SOURCE_REMOVE;
            if (intent) this._pending.delete(intent.key);
            this._onError('Нет ответа от движка Audio Matrix');
            this._onChange();
            return GLib.SOURCE_REMOVE;
        });
        this._requests.set(requestId, {timer, intent});
        return intent;
    }

    acceptAcks(acks) {
        if (!Array.isArray(acks)) return;
        for (const ack of acks) {
            const request = this._requests.get(ack?.requestId);
            if (!request) continue;
            if (request.intent === null) {
                this._clearRequest(ack.requestId);
                this._onError(ack.status === 'error' ? String(ack.error || 'Команда не выполнена') : '');
                continue;
            }
            const intent = this._pending.get(intentKey(ack?.target, ack?.property));
            if (!intent || !ackMatches(ack, intent)) continue;
            this._clearRequest(intent.requestId);
            this._pending.delete(intent.key);
            this._onError(ack.status === 'error' ? String(ack.error || 'Команда не выполнена') : '');
        }
    }

    cancel(requestId) {
        const request = this._requests.get(requestId);
        if (!request) return;
        this._clearRequest(requestId);
        if (request.intent && this._pending.get(request.intent.key)?.requestId === requestId)
            this._pending.delete(request.intent.key);
    }

    clear() {
        for (const {timer} of this._requests.values())
            GLib.Source.remove(timer);
        this._requests.clear();
        this._pending.clear();
    }

    stop() {
        this.clear();
    }

    _clearRequest(requestId) {
        const request = this._requests.get(requestId);
        if (!request) return;
        GLib.Source.remove(request.timer);
        this._requests.delete(requestId);
    }
}
