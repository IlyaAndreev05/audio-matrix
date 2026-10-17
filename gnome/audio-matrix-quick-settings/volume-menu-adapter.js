import * as Main from 'resource:///org/gnome/shell/ui/main.js';

export default class AudioMatrixVolumeMenuAdapter {
    constructor(section) {
        this._section = section;
        this._menu = null;
    }

    attach() {
        const indicator = Main.panel.statusArea.quickSettings?._volumeOutput;
        const menu = indicator?._output?.menu;
        if (!menu || !menu.addMenuItem)
            return false;
        if (this._menu === menu)
            return true;
        this.detach();
        this._menu = menu;
        this._menu.addMenuItem(this._section);
        return true;
    }

    detach() {
        if (!this._menu || !this._section)
            return;
        try {
            if (this._menu.removeMenuItem)
                this._menu.removeMenuItem(this._section);
            else
                this._section.destroy();
        } catch (error) {
            console.debug(`Audio Matrix menu cleanup failed: ${error}`);
        }
        this._menu = null;
    }

    destroy() {
        this.detach();
        this._section = null;
    }
}
