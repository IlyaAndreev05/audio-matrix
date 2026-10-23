import * as Main from 'resource:///org/gnome/shell/ui/main.js';

/** Adds one full-width native section without replacing GNOME's volume UI. */
export default class AudioMatrixQuickSettingsAdapter {
    constructor(section) {
        this._section = section;
        this._menu = null;
    }

    attach() {
        const menu = Main.panel.statusArea.quickSettings?.menu;
        if (!menu?.addItem || !this._section?.actor) return false;
        const actor = this._section.actor;
        if (this._menu === menu && actor.get_parent?.() === menu._grid) return true;
        this.detach(false);
        menu.addItem(actor, 2);
        this._menu = menu;
        return true;
    }

    commit() {
        if (this._section?.actor) this._section.actor.visible = true;
    }

    rollback() {
        if (this._section?.actor) this._section.actor.visible = false;
    }

    detach(destroySection = true) {
        const actor = this._section?.actor;
        actor?.get_parent?.()?.remove_child(actor);
        if (destroySection) this._section?.destroy();
        this._menu = null;
    }

    destroy() {
        this.detach();
        this._section = null;
    }
}
