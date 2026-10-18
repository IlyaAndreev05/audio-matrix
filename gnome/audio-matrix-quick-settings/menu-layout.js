import St from 'gi://St';
import Clutter from 'gi://Clutter';

export function boxLayout({spacing = 0, vertical = false, ...properties} = {}) {
    const actor = new St.BoxLayout({
        orientation: vertical ? Clutter.Orientation.VERTICAL : Clutter.Orientation.HORIZONTAL,
        ...properties,
    });
    if (spacing > 0)
        actor.set_style(`spacing: ${spacing}px;`);
    return actor;
}
