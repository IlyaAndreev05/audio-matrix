export function removeMenuItem(section, item) {
    if (typeof section.removeMenuItem === 'function') {
        section.removeMenuItem(item);
        return;
    }
    if (Array.isArray(section._items)) {
        const index = section._items.indexOf(item);
        if (index >= 0) section._items.splice(index, 1);
    }
    const actor = item?.actor;
    const parent = actor?.get_parent?.();
    if (parent?.remove_child) parent.remove_child(actor);
}

export function reorderMenuItems(section, previous, desired) {
    if (previous.length === desired.length && previous.every((item, index) => item === desired[index]))
        return;
    for (const item of previous) removeMenuItem(section, item);
    for (const item of desired) section.addMenuItem(item);
}

export function clearMenuItems(section, items) {
    for (const item of items) removeMenuItem(section, item);
}
