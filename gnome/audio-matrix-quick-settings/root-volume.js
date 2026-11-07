import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import {QuickSlider} from 'resource:///org/gnome/shell/ui/quickSettings.js';
import AudioMatrixMenuRows from './menu-rows.js';

export default class AudioMatrixRootVolume {
    constructor({send, queueSliderCommand, onDragBegin, onDragEnd}) {
        this._send = send;
        this._section = new PopupMenu.PopupMenuSection();
        this._section.actor.add_style_class_name('audio-matrix-root-volume');
        this._rows = new AudioMatrixMenuRows({section: this._section, getLanguage: () => 'ru',
            send, queueSliderCommand, onOpen: () => {}, onDragBegin, onDragEnd});
        this._masterRow = null;
        this._outputRows = new Map();
        this._nativeVolume = null;
        this._hideNative = false;
        this._attached = false;
    }

    attach() {
        const menu = Main.panel.statusArea.quickSettings?.menu;
        if (!menu?.box) return false;
        if (this._section.actor.get_parent?.() !== menu.box)
            menu.box.add_child(this._section.actor);
        this._attached = true;
        // The Shell builds the quick settings grid asynchronously; hide the
        // native slider here too so a late indicator is still covered.
        this._syncNativeVolume(this._hideNative);
        return true;
    }

    render(snapshot) {
        const state = snapshot.state;
        const root = state?.root;
        this._section.actor.visible = Boolean(root?.enabled);
        this._hideNative = Boolean(root?.hideNativeVolume);
        this._syncNativeVolume(this._hideNative);
        if (!root?.enabled) return;
        if (root.showMaster) {
            this._masterRow ??= this._rows.addLevelRow({
                id: 'root-master', name: 'Общая громкость', icon: 'audio-volume-high-symbolic',
                value: 0, muted: false, commandTarget: {nodeId: 'master'},
                onValue: value => this._send('set_master_volume', Number(value).toFixed(3)),
                onMute: muted => this._send('set_master_mute', String(muted)),
            });
            this._rows.updateLevelRow(this._masterRow, {
                id: 'root-master', name: 'Общая громкость', icon: 'audio-volume-high-symbolic',
                value: state.master?.volume ?? 0, muted: Boolean(state.master?.muted),
                displayValue: state.master?.muted ? '0%' : `${Math.round((state.master?.volume ?? 0) * 100)}%`,
                commandTarget: {nodeId: 'master'},
                onValue: value => this._send('set_master_volume', Number(value).toFixed(3)),
                onMute: muted => this._send('set_master_mute', String(muted)),
            });
        } else if (this._masterRow) {
            this._masterRow.destroy?.(); this._masterRow = null;
        }
        const seen = new Set();
        for (const output of root.outputs || []) {
            const key = String(output.id ?? output.nodeName);
            seen.add(key);
            let row = this._outputRows.get(key);
            const options = {
                id: `root-output:${key}`, name: output.name || output.description || key,
                icon: /head|науш/i.test(output.name || '') ? 'audio-headphones-symbolic' : 'audio-speakers-symbolic',
                value: output.volume ?? 0, muted: Boolean(output.muted),
                displayValue: output.muted ? '0%' : `${Math.round((output.volume ?? 0) * 100)}%`,
                commandTarget: {deviceId: key}, available: output.available !== false,
                onValue: value => this._send('set_device_volume', `${key}\t${Number(value).toFixed(3)}`),
                onMute: muted => this._send('set_device_mute', `${key}\t${muted}`),
            };
            if (!row) row = this._rows.addLevelRow(options);
            else this._rows.updateLevelRow(row, options);
            this._outputRows.set(key, row);
        }
        for (const [key, row] of this._outputRows) {
            if (seen.has(key)) continue;
            row.destroy?.(); this._outputRows.delete(key);
        }
    }

    _syncNativeVolume(hide) {
        const grid = Main.panel.statusArea.quickSettings?.menu?._grid;
        // GNOME 50's output control is an OutputStreamSlider (a QuickSlider
        // subclass) whose inner slider carries the accessible name "Volume".
        const candidate = grid?.get_children?.().find(child =>
            child instanceof QuickSlider &&
            /volume|громкость/i.test(child.accessible_name || child.slider?.accessible_name || ''));
        if (!candidate) return;
        this._nativeVolume = candidate;
        candidate.visible = !hide;
    }

    destroy() {
        if (this._nativeVolume) this._nativeVolume.visible = true;
        this._section?.actor?.destroy?.();
        this._section = null; this._masterRow = null; this._outputRows.clear();
    }
}
