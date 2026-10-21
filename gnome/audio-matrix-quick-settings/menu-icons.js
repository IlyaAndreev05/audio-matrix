import Shell from 'gi://Shell';

export function applicationIcon(application) {
    const appSystem = Shell.AppSystem.get_default();
    const candidates = [application.application_id, application.id?.replace(/^app:/, ''), application.process]
        .filter(Boolean);
    for (const candidate of candidates) {
        for (const id of [candidate, `${candidate}.desktop`]) {
            try {
                const icon = appSystem.lookup_app(id)?.get_icon();
                if (icon) return {gicon: icon};
            } catch (_error) {
                // Streams can belong to applications without installed desktop files.
            }
        }
    }
    const name = `${application.name || ''} ${application.process || ''}`.toLowerCase();
    if (name.includes('chrome')) return 'web-browser-symbolic';
    if (name.includes('music') || name.includes('spotify')) return 'audio-x-generic-symbolic';
    return 'application-x-executable-symbolic';
}

export function outputIcon(output) {
    const name = `${output.name || ''} ${output.description || ''}`.toLowerCase();
    if (name.includes('bluez') || name.includes('bluetooth') || name.includes('liberty'))
        return 'audio-headphones-symbolic';
    if (name.includes('hdmi') || name.includes('benq') || name.includes('monitor'))
        return 'video-display-symbolic';
    if (name.includes('usb') || name.includes('dac')) return 'audio-card-symbolic';
    return 'audio-speakers-symbolic';
}

export function controlIcon(control) {
    const name = `${control.label || ''} ${control.parameterId || ''}`.toLowerCase();
    if (name.includes('вибр') || name.includes('haptic') || name.includes('bass') || name.includes('бас'))
        return 'audio-speakers-symbolic';
    if (name.includes('event') || name.includes('событ')) return 'input-keyboard-symbolic';
    return 'audio-x-generic-symbolic';
}
