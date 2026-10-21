function actorY(actor) {
    const point = actor?.get_transformed_position?.();
    if (Array.isArray(point)) return Number(point[1]) || 0;
    if (point && typeof point.y === 'number') return point.y;
    return Number(actor?.y) || 0;
}

function actorHeight(actor) {
    return Number(actor?.get_height?.() ?? actor?.height) || 0;
}

function adjustmentFor(scroll) {
    return scroll?.get_vadjustment?.() || scroll?.vadjustment || scroll?.adjustment || null;
}

function rowContains(rowActor, focus) {
    for (let actor = focus; actor; actor = actor.get_parent?.())
        if (actor === rowActor) return true;
    return false;
}

function mountedWithin(root, focus) {
    for (let actor = focus; actor; actor = actor.get_parent?.())
        if (actor === root || actor === root?.actor) return true;
    return false;
}

export function captureMenuViewport(scroll, rows, focus) {
    const scrollY = actorY(scroll);
    let anchor = null;
    for (const row of rows) {
        if (!row.actor || row.actor.visible === false) continue;
        const y = actorY(row.actor);
        if (y + actorHeight(row.actor) > scrollY) {
            anchor = {id: row.id, offset: y - scrollY};
            break;
        }
    }
    const focused = rows.find(row => row.actor && rowContains(row.actor, focus));
    return {
        anchor,
        rowIds: rows.map(row => row.id),
        focusId: focused?.id || null,
        focus,
        scrollValue: adjustmentFor(scroll)?.value ?? 0,
    };
}

export function restoreMenuViewport(scroll, snapshot, rows, setFocus) {
    if (!snapshot) return;
    const byId = new Map(rows.map(row => [row.id, row]));
    let anchor = snapshot.anchor ? byId.get(snapshot.anchor.id) : null;
    if (!anchor && snapshot.anchor) {
        const previousIndex = snapshot.rowIds.indexOf(snapshot.anchor.id);
        for (let index = previousIndex + 1; index < snapshot.rowIds.length && !anchor; index++)
            anchor = byId.get(snapshot.rowIds[index]) || null;
        for (let index = previousIndex - 1; index >= 0 && !anchor; index--)
            anchor = byId.get(snapshot.rowIds[index]) || null;
    }
    const adjustment = adjustmentFor(scroll);
    if (adjustment && anchor?.actor) {
        const currentOffset = actorY(anchor.actor) - actorY(scroll);
        const delta = currentOffset - (snapshot.anchor?.offset ?? 0);
        const lower = Number(adjustment.lower) || 0;
        const upper = Number(adjustment.upper) || 0;
        const pageSize = Number(adjustment.page_size ?? adjustment.pageSize) || 0;
        adjustment.value = Math.max(lower, Math.min(Math.max(lower, upper - pageSize), adjustment.value + delta));
    }
    if (snapshot.focusId && !mountedWithin(scroll, snapshot.focus) && typeof setFocus === 'function') {
        const focusedRow = byId.get(snapshot.focusId) || anchor;
        if (focusedRow?.actor) setFocus(focusedRow.actor);
    }
}
