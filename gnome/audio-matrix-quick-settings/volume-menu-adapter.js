import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import St from 'gi://St';
import Meta from 'gi://Meta';
import {MenuButtonLease, MenuItemsLease, MenuViewportLease} from './menu-lease.js';

/**
 * Inserts the full Audio Matrix section directly in front of the native device
 * list inside GNOME 50's output submenu. The native header, separator, Sound
 * Settings row and every device actor keep their own state; only the device
 * section container is hidden while the Audio Matrix section is committed.
 */
export default class AudioMatrixQuickSettingsAdapter {
    constructor(section) {
        this._section = section;
        this._menu = null;
        this._output = null;
        this._deviceSection = null;
        this._deviceActor = null;
        this._buttonLease = null;
        this._itemsLease = null;
        this._viewportLease = null;
        this._menuOpenId = 0;
        this._warning = null;
        if (section?.actor)
            section.actor.visible = false;
    }

    attach() {
        if (!this._section?.actor)
            return false;
        const output = Main.panel.statusArea.quickSettings?._volumeOutput?._output;
        if (!output) {
            // The volume indicator does not exist yet during Shell startup.
            this._detachSection(false);
            return false;
        }
        const menu = output.menu;
        const deviceSection = output._deviceSection;
        const deviceActor = deviceSection?.actor;
        const items = menu?._getMenuItems?.();
        const position = Array.isArray(items) ? items.indexOf(deviceSection) : -1;
        const compatible = Boolean(menu?.addMenuItem && menu.box && deviceActor &&
            position >= 0 && deviceActor.get_parent?.() === menu.box &&
            typeof output.connect === 'function' && typeof output.disconnect === 'function');
        if (!compatible) {
            this._detachSection(false);
            this._reportIncompatible(output, menu, deviceSection);
            return false;
        }
        this._warning = null;
        if (this._output === output && this._menu === menu &&
            this._deviceSection === deviceSection && this._deviceActor === deviceActor &&
            this._section.actor.get_parent?.() === menu.box)
            return true;
        // Reparent without destroying the section: the old native menu may be
        // torn down during a Shell reconstruction and must not keep reacting to
        // the reparented section.
        this._detachSection(false);
        this._output = output;
        this._menu = menu;
        this._deviceSection = deviceSection;
        this._deviceActor = deviceActor;
        try {
            menu.addMenuItem(this._section, position);
            this._listenMenuOpen();
            this._buttonLease = new MenuButtonLease(output);
            this._buttonLease.keep();
            this._viewportLease = new MenuViewportLease({
                menu,
                section: this._section,
                getWorkArea: () => this._monitorWorkArea(),
                getScaleFactor: () => this._scaleFactor(),
                getLaters: () => global.compositor?.get_laters?.(),
                laterType: Meta.LaterType.BEFORE_REDRAW,
            });
            return true;
        } catch (error) {
            this._detachSection(false);
            this._reportIncompatible(output, menu, deviceSection, error);
            return false;
        }
    }

    commit() {
        if (!this._menu || !this._section?.actor ||
            this._section.actor.get_parent?.() !== this._menu.box)
            return false;
        this._buttonLease ??= new MenuButtonLease(this._output);
        this._buttonLease.keep();
        this._itemsLease ??= new MenuItemsLease([this._deviceActor]);
        this._section.actor.visible = true;
        this._itemsLease.hide();
        this._viewportLease?.applyBaseline();
        return true;
    }

    rollback() {
        this._viewportLease?.cancel();
        if (this._section?.actor)
            this._section.actor.visible = false;
        this._itemsLease?.restore();
        this._itemsLease = null;
        this._buttonLease?.restore();
        this._buttonLease = null;
    }

    detach(destroySection = true) {
        this._detachSection(destroySection);
    }

    destroy() {
        this.detach();
        this._warning = null;
    }

    _detachSection(destroySection) {
        this.rollback();
        this._viewportLease?.release();
        this._viewportLease = null;
        this._disconnectMenu();
        const section = this._section;
        const menu = this._menu;
        if (menu && section?.actor) {
            // PopupMenuBase.addMenuItem() connects both emitters. Leaving the
            // old menu connected makes it react to the section after reparenting.
            try {
                section.disconnectObject?.(menu);
            } catch (_error) {
                // The old menu may already be destroyed during Shell teardown.
            }
            try {
                menu.disconnectObject?.(section);
            } catch (_error) {
                // The old menu may already be destroyed during Shell teardown.
            }
            if (section._parent === menu) {
                section._setParent?.(null);
                if (Number.isFinite(menu.length))
                    menu.length--;
            }
            try {
                section.actor.get_parent?.()?.remove_child?.(section.actor);
            } catch (_error) {
                // The actor follows a destroyed parent during Shell teardown.
            }
        }
        this._menu = null;
        this._output = null;
        this._deviceSection = null;
        this._deviceActor = null;
        if (destroySection) {
            this._section = null;
            section?.destroy();
        }
    }

    _listenMenuOpen() {
        if (!this._menu?.connect)
            return;
        this._menuOpenId = this._menu.connect('open-state-changed', (_menu, open) => {
            if (open)
                this._viewportLease?.schedule();
        });
    }

    _disconnectMenu() {
        const menu = this._menu;
        if (menu && this._menuOpenId) {
            try {
                menu.disconnect(this._menuOpenId);
            } catch (_error) {
                // The menu may already be destroyed during Shell teardown.
            }
        }
        this._menuOpenId = 0;
    }

    _monitorWorkArea() {
        const index = global.display?.get_current_monitor?.() ?? 0;
        try {
            // GNOME 50 exposes work areas through the active workspace.
            const workspace = global.workspace_manager?.get_active_workspace?.() ??
                global.workspace_manager?.get_workspace_by_index?.(0);
            if (workspace?.get_work_area_for_monitor)
                return workspace.get_work_area_for_monitor(index);
        } catch (_error) {
            // Fall through to the legacy display API on older Shell versions.
        }
        const display = global.display;
        if (!display?.get_monitor_work_area)
            return null;
        try {
            return display.get_monitor_work_area(index);
        } catch (_error) {
            return null;
        }
    }

    _scaleFactor() {
        try {
            return St.ThemeContext.get_for_stage(global.stage).scale_factor || 1;
        } catch (_error) {
            return 1;
        }
    }

    _reportIncompatible(output, menu, deviceSection, error = null) {
        if (this._warning?.[0] === output && this._warning?.[1] === menu &&
            this._warning?.[2] === deviceSection)
            return;
        this._warning = [output, menu, deviceSection];
        if (error)
            console.error(`Audio Matrix: не удалось подключить меню громкости: ${error}`);
        Main.notifyError('Audio Matrix',
            'Структура меню громкости GNOME несовместима с Audio Matrix. ' +
            'Оставлено штатное меню устройств.');
    }
}
