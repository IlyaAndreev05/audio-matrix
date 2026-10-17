import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import AudioMatrixMenuRows from './menu-rows.js';
import {TEXT} from './menu-text.js';

export default class AudioMatrixMenuRenderer {
    constructor({section, getSnapshot, send, queueSliderCommand, onOpen, onDragBegin, onDragEnd}) {
        this._section = section;
        this._getSnapshot = getSnapshot;
        this._onOpen = onOpen;
        this._sendCommand = send;
        this._rows = new AudioMatrixMenuRows({
            section,
            getLanguage: () => this._getSnapshot().language,
            send,
            queueSliderCommand,
            onOpen,
            onDragBegin,
            onDragEnd,
        });
    }

    render(snapshot = this._getSnapshot()) {
        if (!this._section)
            return;
        const {state, available, language, commandError} = snapshot;
        const t = TEXT[language];
        this._section.removeAll();
        this._rows.addHeader(state, available);
        if (commandError)
            this._rows.addStatus(`${t.commandError}: ${commandError}`, 'dialog-warning-symbolic');
        if (!available) {
            this._rows.addStatus(state ? t.unavailable : t.connecting, 'dialog-information-symbolic');
            this._rows.addAction(t.open, () => this._onOpen(false), 'preferences-system-symbolic');
            return;
        }
        this._renderApplications(state, t);
        this._renderOutputs(state, t);
        this._renderQuickControls(state, t);
        this._section.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
        this._rows.addAction(t.open, () => this._onOpen(true), 'preferences-system-symbolic');
    }

    _renderApplications(state, t) {
        this._rows.addHeading(t.applications);
        const applications = state.applications || [];
        if (applications.length === 0)
            this._section.addMenuItem(new PopupMenu.PopupMenuItem(t.noApplications, {reactive: false, can_focus: false}));
        for (const application of applications) {
            this._rows.addLevelRow({
                id: `app:${application.id}`, name: application.name || application.id,
                icon: this._rows.applicationIcon(application), value: application.volume,
                muted: application.muted, mutedLabel: application.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_app_volume', `${application.id}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_app_mute', application.id),
            });
            this._renderStreams(application, t);
        }
    }

    _renderStreams(application, t) {
        const streams = application.streams || [];
        if (streams.length === 0)
            return;
        const submenu = new PopupMenu.PopupSubMenuMenuItem(`${t.streams} (${streams.length})`, false);
        for (const stream of streams) {
            submenu.menu.addMenuItem(this._rows.createLevelRow({
                id: `stream:${stream.id}`, name: stream.description || stream.name,
                icon: 'audio-x-generic-symbolic', value: stream.volume, muted: stream.muted,
                mutedLabel: stream.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_stream_volume', `${stream.id}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_stream_mute', String(stream.id)),
            }));
        }
        this._section.addMenuItem(submenu);
    }

    _renderOutputs(state, t) {
        this._rows.addHeading(t.outputs);
        for (const output of state.outputs || []) {
            this._rows.addLevelRow({
                id: `output:${output.name}`, name: output.description || output.name,
                icon: this._rows.outputIcon(output), value: output.volume, muted: output.muted,
                mutedLabel: output.muted ? t.unmute : t.mute,
                onValue: value => this._send('set_output_volume', `${output.name}\t${value.toFixed(3)}`),
                onMute: () => this._send('toggle_output_mute', output.name),
            });
        }
    }

    _renderQuickControls(state, t) {
        const controls = state.quickControls || [];
        if (controls.length === 0)
            return;
        this._rows.addHeading(t.controls);
        let currentSection = null;
        for (const control of controls) {
            const section = control.section || t.controls;
            if (section !== currentSection) {
                currentSection = section;
                if (controls.length > 1)
                    this._rows.addSubheading(section);
            }
            if (control.kind === 'slider')
                this._renderSliderControl(control);
            else if (control.kind === 'toggle')
                this._renderToggleControl(control);
            else if (control.kind === 'links')
                this._rows.addGraphLinks(control);
        }
    }

    _renderSliderControl(control) {
        this._rows.addLevelRow({
            id: `graph:${control.id}`, name: control.label || control.parameterId,
            icon: this._rows.controlIcon(control), value: control.value,
            onValue: value => this._send('set_quick_control', `${control.id}\t${value.toFixed(3)}`),
        });
    }

    _renderToggleControl(control) {
        const item = new PopupMenu.PopupSwitchMenuItem(control.label || control.parameterId, Boolean(control.value));
        item.connect('toggled', () => this._send('toggle_quick_control', control.id));
        this._section.addMenuItem(item);
    }

    _send(name, value) {
        this._sendCommand?.(name, value);
    }

    destroy() {
        this._section = null;
        this._rows = null;
    }
}
