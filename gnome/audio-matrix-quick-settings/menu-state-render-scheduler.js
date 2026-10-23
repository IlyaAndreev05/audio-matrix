import GLib from 'gi://GLib';

/** Keeps actors steady during drags while publishing the latest level at 25 Hz. */
export default class AudioMatrixStateRenderScheduler {
    constructor(onChange) {
        this._onChange = onChange;
        this._sliderTimers = new Map();
        this._dragging = 0;
        this._renderPending = false;
    }

    changed(snapshot) {
        if (this._dragging > 0) {
            this._renderPending = true;
            return;
        }
        this._onChange?.(snapshot());
    }

    beginSliderDrag() {
        this._dragging++;
    }

    endSliderDrag(snapshot) {
        this._dragging = Math.max(0, this._dragging - 1);
        if (this._dragging !== 0) return;
        this._renderPending = false;
        this._onChange?.(snapshot());
    }

    queueSliderCommand(id, value, callback, immediate = false) {
        const key = String(id);
        const previous = this._sliderTimers.get(key);
        if (immediate) {
            if (previous) GLib.Source.remove(previous.timer);
            this._sliderTimers.delete(key);
            callback(value);
            return;
        }
        if (previous) {
            previous.value = value;
            previous.callback = callback;
            previous.dirty = true;
            return;
        }
        callback(value);
        const entry = {value, callback, dirty: false, timer: 0};
        entry.timer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 40, () => {
            if (!entry.dirty) {
                this._sliderTimers.delete(key);
                return GLib.SOURCE_REMOVE;
            }
            entry.dirty = false;
            entry.callback(entry.value);
            return GLib.SOURCE_CONTINUE;
        });
        this._sliderTimers.set(key, entry);
    }

    stop() {
        for (const entry of this._sliderTimers.values()) GLib.Source.remove(entry.timer);
        this._sliderTimers.clear();
        this._renderPending = false;
        this._dragging = 0;
    }
}
