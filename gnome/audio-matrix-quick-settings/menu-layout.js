import St from 'gi://St';

export function boxLayout({spacing = 0, ...properties} = {}) {
    const actor = new St.BoxLayout(properties);
    if (spacing > 0)
        actor.set_style(`spacing: ${spacing}px;`);
    return actor;
}
