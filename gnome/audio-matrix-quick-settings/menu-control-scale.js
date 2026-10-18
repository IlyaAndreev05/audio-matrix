function clamp(value) {
    return Math.max(0, Math.min(1, Number(value) || 0));
}

export function controlPosition(control) {
    const minimum = Number(control.minimum);
    const maximum = Number(control.maximum);
    const value = Number(control.value);
    if (!Number.isFinite(minimum) || !Number.isFinite(maximum) || maximum <= minimum)
        return clamp(value);
    if (control.scale === 'log' && minimum > 0 && value > 0)
        return clamp((Math.log(value) - Math.log(minimum)) / (Math.log(maximum) - Math.log(minimum)));
    return clamp((value - minimum) / (maximum - minimum));
}

export function controlValueAt(control, position) {
    const minimum = Number(control.minimum);
    const maximum = Number(control.maximum);
    const fraction = clamp(position);
    if (control.scale === 'log' && minimum > 0 && maximum > minimum)
        return Math.exp(Math.log(minimum) + fraction * (Math.log(maximum) - Math.log(minimum)));
    return minimum + fraction * (maximum - minimum);
}

export function formatControlValue(control, value) {
    const number = Number(value);
    const precision = Math.max(0, Math.min(3, Number(control.precision) || 0));
    if (!Number.isFinite(number)) return '—';
    if (control.unit === 'Hz' && number >= 1000) {
        const digits = number >= 10000 ? 0 : 1;
        return `${(number / 1000).toFixed(digits)} kHz`;
    }
    const formatted = Number(number.toFixed(precision)).toString();
    return control.unit ? `${formatted} ${control.unit}` : formatted;
}
