/** Reconciles PopupMenuSection rows by stable identity without replacing its actor. */
export default class AudioMatrixKeyedSection {
    constructor(section) {
        this.section = section;
        this._items = new Map();
        this._order = [];
        this._next = [];
        this._replaced = new Set();
    }

    begin() {
        this._next = [];
        this._replaced.clear();
    }

    addMenuItem(incoming) {
        const key = String(incoming._audioMatrixKey ?? `anonymous:${this._next.length}`);
        if (this._next.includes(key))
            throw new Error(`Duplicate GNOME menu row key: ${key}`);
        this._next.push(key);
        const previous = this._items.get(key);
        if (previous?.item === incoming)
            return incoming;
        if (previous && previous.kind === incoming._audioMatrixKind
            && typeof previous.item._audioMatrixPatch === 'function') {
            previous.item._audioMatrixPatch(incoming);
            incoming.destroy?.();
            return previous.item;
        }
        if (previous) {
            this._replaced.add(key);
            this.section.removeMenuItem(previous.item);
        }
        this._items.set(key, {item: incoming, kind: incoming._audioMatrixKind});
        return incoming;
    }

    end() {
        const nextKeys = new Set(this._next);
        for (const key of this._order) {
            if (nextKeys.has(key))
                continue;
            const entry = this._items.get(key);
            if (entry)
                this.section.removeMenuItem(entry.item);
            this._items.delete(key);
        }

        const previousOrder = this._order.filter(key => nextKeys.has(key) && !this._replaced.has(key));
        const additions = this._next.filter(key => !this._order.includes(key) || this._replaced.has(key));
        const appendOrder = [...previousOrder, ...additions];
        const appendOnly = appendOrder.length === this._next.length
            && appendOrder.every((key, index) => key === this._next[index]);
        if (!appendOnly) {
            for (const key of previousOrder) {
                const entry = this._items.get(key);
                if (entry)
                    this.section.removeMenuItem(entry.item);
            }
            for (const key of this._next) {
                const entry = this._items.get(key);
                if (entry)
                    this.section.addMenuItem(entry.item);
            }
        } else {
            for (const key of additions) {
                const entry = this._items.get(key);
                if (entry)
                    this.section.addMenuItem(entry.item);
            }
        }
        this._order = [...this._next];
    }

    clear() {
        for (const key of this._order) {
            const entry = this._items.get(key);
            if (entry)
                this.section.removeMenuItem(entry.item);
        }
        this._items.clear();
        this._order = [];
        this._next = [];
        this._replaced.clear();
    }

    removeKey(key) {
        const normalized = String(key);
        const entry = this._items.get(normalized);
        if (entry) {
            this.section.removeMenuItem(entry.item);
            this._items.delete(normalized);
        }
        this._order = this._order.filter(item => item !== normalized);
        this._next = this._next.filter(item => item !== normalized);
    }

    item(key) {
        return this._items.get(String(key))?.item ?? null;
    }

    values() {
        return this._order.map(key => this._items.get(key)?.item).filter(Boolean);
    }

    get actor() {
        return this.section.actor;
    }
}