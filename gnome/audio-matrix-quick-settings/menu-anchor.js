/** Captures and restores stable row anchors across keyed menu structure changes. */
export function captureMenuAnchor(rows, viewportTop, viewportHeight, scrollValue, focusedKey = '') {
    const ordered = [...rows].sort((left, right) => left.y - right.y);
    const anchorIndex = ordered.findIndex(row => row.y + row.height > viewportTop
        && row.y < viewportTop + viewportHeight);
    const focusIndex = ordered.findIndex(row => row.key === focusedKey);
    return {
        scrollValue,
        anchorIndex,
        focusIndex,
        focusKey: focusedKey,
        rows: ordered.map(row => ({key: row.key, offset: row.y - viewportTop})),
    };
}

export function restoreMenuAnchor(snapshot, currentRows, viewportTop, bounds = {}) {
    const current = new Map(currentRows.map(row => [row.key, row]));
    let anchor = snapshot.anchorIndex >= 0 ? snapshot.rows[snapshot.anchorIndex] : null;
    if (!anchor || !current.has(anchor.key))
        anchor = nearestSurvivor(snapshot.rows, current, snapshot.anchorIndex);

    let value = snapshot.scrollValue;
    if (anchor && current.has(anchor.key))
        value += current.get(anchor.key).y - viewportTop - anchor.offset;
    const lower = Number(bounds.lower) || 0;
    const upper = Math.max(lower, (Number(bounds.upper) || lower) - (Number(bounds.pageSize) || 0));
    value = Math.max(lower, Math.min(upper, value));

    let focusKey = snapshot.focusKey;
    if (!current.has(focusKey))
        focusKey = nearestSurvivor(snapshot.rows, current, snapshot.focusIndex)?.key || '';
    return {value, focusKey};
}

function nearestSurvivor(rows, current, index) {
    if (!rows.length) return null;
    const center = Math.max(0, Math.min(rows.length - 1, index));
    for (let distance = 0; distance < rows.length; distance++) {
        const after = rows[center + distance];
        const before = rows[center - distance];
        if (after && current.has(after.key)) return after;
        if (before && current.has(before.key)) return before;
    }
    return null;
}
