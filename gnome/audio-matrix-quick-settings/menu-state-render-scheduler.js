import GLib from 'gi://GLib';

/** Coalesces menu refreshes during pointer drags and slider dispatch bursts. */
export default class AudioMatrixStateRenderScheduler {
    constructor(onChange) {
        this._onChange = onChange;
        this._sliderTimers = new Map();
        this._dragging = 0;
        this._renderPending = false;
        this._renderTimer = 0;
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
        if (this._dragging !== 0 || !this._renderPending)
            return;
        this._renderPending = false;
        if (this._renderTimer)
            GLib.Source.remove(this._renderTimer);
        this._renderTimer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 250, () => {
            this._renderTimer = 0;
            if (this._dragging === 0)
                this._onChange?.(snapshot());
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

    stop() {
        for (const timer of this._sliderTimers.values())
            GLib.Source.remove(timer);
        this._sliderTimers.clear();
        if (this._renderTimer)
            GLib.Source.remove(this._renderTimer);
        this._renderTimer = 0;
        this._renderPending = false;
        this._dragging = 0;
    }
}
