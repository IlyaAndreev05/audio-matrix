import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {MenuButtonLease, MenuItemsLease} from './menu-lease.js';

export default class AudioMatrixVolumeMenuAdapter {
    constructor(section) {
        this._section = section;
        this._menu = null;
        this._output = null;
        this._menuButtonLease = null;
        this._menuItemsLease = new MenuItemsLease();
    }

    attach() {
        const output = Main.panel.statusArea.quickSettings?._volumeOutput?._output;
        const menu = output?.menu;
        if (!menu || !menu.addMenuItem)
            return false;
        if (this._menu === menu) {
            if (output !== this._output || !this._menuButtonLease?.isCurrent()) {
                this._menuButtonLease?.restore();
                this._output = output;
                this._menuButtonLease = new MenuButtonLease(output);
            }
            const items = menu._getMenuItems?.() || menu._items || [];
            if (!items.includes(this._section))
                menu.addMenuItem(this._section);
            return true;
        }
        this.detach(false);
        this._output = output;
        this._menuButtonLease = new MenuButtonLease(output);
        this._menu = menu;
        this._menu.addMenuItem(this._section);
        return true;
    }

    commit() {
        this._menuItemsLease.commit(this._menu, this._section);
    }

    rollback() {
        this._menuItemsLease.rollback(this._section);
    }

    detach(destroySection = true) {
        this._menuItemsLease.restore();
        if (this._menu && this._section) {
            try {
                // PopupMenu sections are actors. Detach without destroying when
                // Quick Settings is rebuilt, then let the new menu adopt it.
                this._section.disconnectObject?.(this._menu);
                const actor = this._section.actor;
                actor.get_parent()?.remove_child(actor);
            } catch (error) {
                console.debug(`Audio Matrix menu cleanup failed: ${error}`);
            }
        }
        if (destroySection)
            this._section?.destroy();
        this._menuButtonLease?.restore();
        this._menuButtonLease = null;
        this._menu = null;
        this._output = null;
    }

    destroy() {
        this.detach();
        this._section = null;
    }
}
