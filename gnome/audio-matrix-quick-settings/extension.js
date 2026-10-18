import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import Gio from 'gi://Gio';
import GioUnix from 'gi://GioUnix';
import GLib from 'gi://GLib';
import AudioMatrixStateClient from './state-client.js';
import AudioMatrixMenuRenderer from './menu-renderer.js';
import AudioMatrixVolumeMenuAdapter from './volume-menu-adapter.js';

export default class AudioMatrixQuickSettingsExtension extends Extension {
    enable() {
        this._dynamic = new PopupMenu.PopupMenuSection();
        this._client = new AudioMatrixStateClient(snapshot => this._render(snapshot));
        this._renderer = new AudioMatrixMenuRenderer({
            section: this._dynamic,
            getSnapshot: () => this._client.snapshot(),
            send: (name, value) => this._client.send(name, value),
            queueSliderCommand: (...args) => this._client.queueSliderCommand(...args),
            onOpen: connected => this._openApplication(connected),
            onDragBegin: () => this._client.beginSliderDrag(),
            onDragEnd: () => this._client.endSliderDrag(),
        });
        this._adapter = new AudioMatrixVolumeMenuAdapter(this._dynamic);
        this._client.start();
        this._retryId = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 1, () => {
            if (this._adapter.attach())
                this._render(this._client.snapshot());
            return GLib.SOURCE_CONTINUE;
        });
    }

    _render(snapshot) {
        try {
            this._renderer?.render(snapshot);
            this._adapter?.commit();
        } catch (error) {
            console.error(`Audio Matrix menu render failed; restoring GNOME items: ${error}`);
            this._adapter?.rollback();
        }
    }

    _openApplication(bridgeConnected) {
        if (bridgeConnected) {
            this._client.send('open', '');
            return;
        }
        try {
            const app = GioUnix.DesktopAppInfo.new('audiomatrix-AudioMatrix.desktop');
            if (app?.launch([], null))
                return;
        } catch (error) {
            console.debug(`Audio Matrix desktop launch failed: ${error}`);
        }
        try {
            Gio.Subprocess.new(['/opt/audiomatrix/bin/AudioMatrix'], Gio.SubprocessFlags.NONE);
        } catch (error) {
            console.debug(`Audio Matrix executable launch failed: ${error}`);
        }
    }

    disable() {
        if (this._retryId)
            GLib.Source.remove(this._retryId);
        this._client?.stop();
        this._adapter?.destroy();
        this._renderer?.destroy();
        // The GNOME popup menu owns the inserted section and removes/destroys
        // its actor through removeMenuItem(). Destroying it again here causes
        // disposed-actor errors when GNOME tears down Quick Settings.
        this._client = null;
        this._adapter = null;
        this._renderer = null;
        this._dynamic = null;
    }
}
