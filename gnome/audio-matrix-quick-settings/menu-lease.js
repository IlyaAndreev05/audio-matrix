/** Reversible visibility ownership for stock GNOME volume submenu rows. */
export class MenuItemsLease {
    constructor() {
        this._originalVisibility = new Map();
    }

    hide(menu, section) {
        const items = menu._getMenuItems?.() || menu._items || [];
        for (const item of items) {
            if (item === section || !item.actor)
                continue;
            if (!this._originalVisibility.has(item.actor))
                this._originalVisibility.set(item.actor, item.actor.visible);
            item.actor.visible = false;
        }
    }

    restore() {
        for (const [actor, visible] of this._originalVisibility) {
            try {
                actor.visible = visible;
            } catch (_error) {
                // A menu item may be destroyed while the extension is disabled.
            }
        }
        this._originalVisibility.clear();
    }
}

/** Keeps GNOME's output submenu available while it contains our replacement. */
export class MenuButtonLease {
    constructor(output) {
        this.output = output;
        this.originalSync = output?._sync;
        this.wrappedSync = null;
        if (typeof this.originalSync !== 'function')
            return;
        const original = this.originalSync;
        this.wrappedSync = function (...args) {
            original.apply(this, args);
            this.menuEnabled = true;
        };
        output._sync = this.wrappedSync;
        output.menuEnabled = true;
    }

    isCurrent() {
        return Boolean(this.output && this.wrappedSync && this.output._sync === this.wrappedSync);
    }

    restore() {
        if (!this.output || !this.wrappedSync)
            return;
        if (this.output._sync === this.wrappedSync)
            this.output._sync = this.originalSync;
        try {
            this.originalSync.call(this.output);
        } catch (_error) {
            // GNOME may already be tearing down the volume indicator.
        }
        this.output = null;
    }
}
