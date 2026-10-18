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

    commit(menu, section) {
        if (!menu || !section)
            return;
        if (section.actor)
            section.actor.visible = true;
        this.hide(menu, section);
    }

    rollback(section) {
        this.restore();
        if (section?.actor)
            section.actor.visible = false;
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
        this.originalMenuEnabled = output?.menuEnabled;
        this.signalId = output?.connect('notify::menu-enabled', () => {
            if (!output.menuEnabled)
                output.menuEnabled = true;
        });
        if (output)
            output.menuEnabled = true;
    }

    isCurrent() {
        return Boolean(this.output && this.signalId);
    }

    restore() {
        if (!this.output)
            return;
        try {
            this.output.disconnect(this.signalId);
            // Re-evaluate current devices rather than restoring a stale count.
            if (typeof this.output._sync === 'function')
                this.output._sync();
            else
                this.output.menuEnabled = this.originalMenuEnabled;
        } catch (_error) {
            // GNOME may already be tearing down the volume indicator.
        }
        this.output = null;
    }
}
