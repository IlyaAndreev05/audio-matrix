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
            this._menuItemsLease.hide(menu, this._section);
            const items = menu._getMenuItems?.() || menu._items || [];
            if (!items.includes(this._section))
                menu.addMenuItem(this._section);
            return true;
        }
        this.detach();
        this._output = output;
        this._menuButtonLease = new MenuButtonLease(output);
        this._menu = menu;
        this._menuItemsLease.hide(menu, this._section);
        this._menu.addMenuItem(this._section);
        return true;
    }

    detach() {
        if (this._menu && this._section) {
            try {
                if (this._menu.removeMenuItem)
                    this._menu.removeMenuItem(this._section);
                else
                    this._section.destroy();
            } catch (error) {
                console.debug(`Audio Matrix menu cleanup failed: ${error}`);
            }
            this._menuItemsLease.restore();
        }
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
