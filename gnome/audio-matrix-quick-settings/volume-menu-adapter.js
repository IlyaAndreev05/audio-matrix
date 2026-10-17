import * as Main from 'resource:///org/gnome/shell/ui/main.js';

export default class AudioMatrixVolumeMenuAdapter {
    constructor(section) {
        this._section = section;
        this._menu = null;
        this._output = null;
        this._originalSync = null;
        this._hiddenItems = [];
    }

    attach() {
        const output = Main.panel.statusArea.quickSettings?._volumeOutput?._output;
        const menu = output?.menu;
        if (!menu || !menu.addMenuItem)
            return false;
        if (this._menu === menu)
            return true;
        this.detach();
        this._output = output;
        this._forceMenuButtonEnabled(output);
        this._menu = menu;
        this._hideBuiltInItems(menu);
        this._menu.addMenuItem(this._section);
        return true;
    }

    _forceMenuButtonEnabled(output) {
        const originalSync = output._sync;
        if (typeof originalSync !== 'function')
            return;
        this._originalSync = originalSync;
        output._sync = function (...args) {
            originalSync.apply(this, args);
            this.menuEnabled = true;
        };
        output.menuEnabled = true;
    }

    _hideBuiltInItems(menu) {
        const items = menu._getMenuItems?.() || menu._items || [];
        this._hiddenItems = items.filter(item => item !== this._section && item.actor)
            .map(item => ({actor: item.actor, visible: item.actor.visible}));
        for (const item of this._hiddenItems)
            item.actor.visible = false;
    }

    _restoreBuiltInItems() {
        for (const item of this._hiddenItems) {
            try {
                item.actor.visible = item.visible;
            } catch (_error) {
                // The native menu may have been rebuilt during session teardown.
            }
        }
        this._hiddenItems = [];
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
            this._restoreBuiltInItems();
        }
        this._restoreMenuButton();
        this._menu = null;
    }

    _restoreMenuButton() {
        if (this._output && this._originalSync) {
            this._output._sync = this._originalSync;
            try {
                this._originalSync.call(this._output);
            } catch (error) {
                console.debug(`GNOME sound menu state restore failed: ${error}`);
            }
        }
        this._output = null;
        this._originalSync = null;
    }

    destroy() {
        this.detach();
        this._section = null;
    }
}
