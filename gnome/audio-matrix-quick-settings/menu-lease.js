const SCROLL_STYLE_CLASS = 'audio-matrix-scroll';
const MIN_VIEWPORT_HEIGHT = 120;
const MAX_VIEWPORT_HEIGHT = 720;
const DEFAULT_CHROME_HEIGHT = 240;
const VIEWPORT_MARGIN = 12;
const MAX_ALLOCATION_RETRIES = 3;

/**
 * Height available to the Audio Matrix scroller on the current monitor.
 * GNOME reports monitor geometry in physical pixels while Shell actors use
 * logical pixels, so the work area is divided by the theme scale before the
 * native submenu chrome is reserved.
 */
export function viewportHeightForWorkArea(workAreaHeight, scaleFactor, reserve = DEFAULT_CHROME_HEIGHT) {
    const height = Number(workAreaHeight);
    if (!Number.isFinite(height) || height <= 0)
        return null;
    const scale = Number(scaleFactor) > 0 ? Number(scaleFactor) : 1;
    return Math.max(MIN_VIEWPORT_HEIGHT,
        Math.min(MAX_VIEWPORT_HEIGHT, Math.floor(height / scale - reserve)));
}

function hasScrollClass(actor) {
    if (typeof actor?.has_style_class_name === 'function')
        return actor.has_style_class_name(SCROLL_STYLE_CLASS);
    return typeof actor?.style_class === 'string' &&
        actor.style_class.split(/\s+/).includes(SCROLL_STYLE_CLASS);
}

/** Reversible, bounded max-height ownership for the section's scroll actor. */
export class MenuViewportLease {
    constructor({menu, section, getWorkArea, getScaleFactor, getLaters, laterType}) {
        this._menu = menu;
        this._section = section;
        this._getWorkArea = getWorkArea;
        this._getScaleFactor = getScaleFactor;
        this._getLaters = getLaters;
        this._laterType = laterType;
        this._laterId = 0;
        this._limit = 0;
        this._allocationRetries = 0;
    }

    /** Bound the viewport immediately, even before Shell allocates the submenu. */
    applyBaseline() {
        const scroll = this._findScroll();
        if (!scroll)
            return;
        const workArea = this._getWorkArea?.();
        const limit = viewportHeightForWorkArea(workArea?.height, this._getScaleFactor?.());
        if (limit === null)
            return;
        this._setLimit(scroll, limit);
        this.schedule();
    }

    schedule() {
        const laters = this._getLaters?.();
        if (!laters?.add)
            return;
        this.cancel();
        this._laterId = laters.add(this._laterType, () => {
            this._laterId = 0;
            this.refine();
            return false;
        });
    }

    cancel() {
        if (!this._laterId)
            return;
        try {
            this._getLaters?.()?.remove?.(this._laterId);
        } catch (_error) {
            // The later never runs when the compositor is shutting down.
        }
        this._laterId = 0;
    }

    /** Fit the scroller to the allocated submenu, keeping the last bound when unallocated. */
    refine() {
        // A closed QuickToggleMenu keeps its children's previous allocation,
        // but that geometry is not on screen and must not shrink the viewport.
        if (!this._menu?.isOpen) {
            this._allocationRetries = 0;
            return;
        }
        const scroll = this._findScroll();
        const menuBox = this._menu?.box;
        const sectionActor = this._section?.actor;
        if (!scroll || !menuBox || !sectionActor)
            return;
        const position = scroll.get_transformed_position?.();
        const scrollHeight = Number(scroll.get_height?.() ?? 0) || 0;
        if (!Array.isArray(position) || !Number.isFinite(position[1]) || scrollHeight <= 0) {
            // Shell may not have allocated the fresh submenu yet; retry on the
            // next frames but keep the existing bounded viewport meanwhile.
            if (this._allocationRetries < MAX_ALLOCATION_RETRIES) {
                this._allocationRetries++;
                this.schedule();
            }
            return;
        }
        this._allocationRetries = 0;
        const workArea = this._getWorkArea?.();
        if (!workArea)
            return;
        const scaleValue = Number(this._getScaleFactor?.());
        const scale = scaleValue > 0 ? scaleValue : 1;
        const bottom = (Number(workArea.y) + Number(workArea.height)) / scale;
        if (!Number.isFinite(bottom))
            return;
        const children = menuBox.get_children?.() ?? [];
        const sectionIndex = children.indexOf(sectionActor);
        if (sectionIndex < 0)
            return;
        let below = 0;
        for (let index = sectionIndex + 1; index < children.length; index++) {
            const child = children[index];
            if (child?.visible === false)
                continue;
            below += Number(child?.get_height?.() ?? child?.height) || 0;
        }
        const available = Math.floor(bottom - position[1] - below - VIEWPORT_MARGIN);
        const limit = Math.max(MIN_VIEWPORT_HEIGHT, Math.min(MAX_VIEWPORT_HEIGHT, available));
        if (limit !== this._limit)
            this._setLimit(scroll, limit);
    }

    release() {
        this.cancel();
        this._menu = null;
        this._section = null;
        this._getWorkArea = null;
        this._getScaleFactor = null;
        this._getLaters = null;
        this._limit = 0;
        this._allocationRetries = 0;
    }

    _setLimit(scroll, limit) {
        if (limit === this._limit || typeof scroll.set_style !== 'function')
            return;
        this._limit = limit;
        scroll.set_style(`max-height: ${limit}px;`);
    }

    _findScroll() {
        const root = this._section?.actor;
        if (!root)
            return null;
        const pending = [root];
        while (pending.length > 0) {
            const actor = pending.pop();
            if (!actor)
                continue;
            if (hasScrollClass(actor))
                return actor;
            for (const child of actor.get_children?.() ?? [])
                pending.push(child);
        }
        return null;
    }
}

/** Reversible visibility ownership for explicitly selected stock submenu actors. */
export class MenuItemsLease {
    constructor(actors) {
        this._originalVisibility = new Map();
        this._restored = false;
        for (const actor of actors)
            this._originalVisibility.set(actor, actor.visible);
    }

    hide() {
        if (this._restored)
            return;
        for (const actor of this._originalVisibility.keys()) {
            if (actor.visible)
                actor.visible = false;
        }
    }

    restore() {
        if (this._restored)
            return;
        this._restored = true;
        for (const [actor, visible] of this._originalVisibility) {
            try {
                actor.visible = visible;
            } catch (_error) {
                // A selected actor may already be destroyed during Shell teardown.
            }
        }
        this._originalVisibility.clear();
    }
}

/** Keeps GNOME's output arrow available without replacing its native sync logic. */
export class MenuButtonLease {
    constructor(output) {
        this._output = output;
        this._originalMenuEnabled = output.menuEnabled;
        this._signalId = 0;
        this._enforcing = false;
    }

    keep() {
        if (!this._output)
            return;
        if (!this._signalId) {
            this._signalId = this._output.connect('notify::menu-enabled', () => {
                this._keepEnabled();
            });
        }
        this._keepEnabled();
    }

    _keepEnabled() {
        if (!this._output || this._enforcing || this._output.menuEnabled)
            return;
        this._enforcing = true;
        try {
            this._output.menuEnabled = true;
        } finally {
            this._enforcing = false;
        }
    }

    restore() {
        const output = this._output;
        if (!output)
            return;
        this._output = null;
        const signalId = this._signalId;
        this._signalId = 0;
        try {
            if (signalId)
                output.disconnect(signalId);
        } catch (_error) {
            // GNOME may already be tearing down the volume indicator.
        }
        try {
            if (typeof output._sync === 'function')
                output._sync();
            else
                output.menuEnabled = this._originalMenuEnabled;
        } catch (_error) {
            // GNOME may already be tearing down the volume indicator.
        }
    }
}
