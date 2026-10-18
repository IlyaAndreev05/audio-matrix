import Atk from 'gi://Atk';

export default class AudioMatrixMenuDisclosure {
    constructor() {
        this._groupOpen = new Map();
        this._groupCollapsed = new Map();
        this._groupRows = new Map();
        this._pinOpen = new Map();
        this._pinView = new Map();
        this._pinRows = new Map();
    }

    resetRows() {
        this._groupRows.clear();
        this._pinRows.clear();
    }

    groupOpen(group) {
        const id = String(group.id || group.name);
        const collapsed = Boolean(group.collapsed);
        const previous = this._groupCollapsed.get(id);
        const open = previous !== undefined && previous !== collapsed
            ? !collapsed : (this._groupOpen.get(id) ?? !collapsed);
        this._groupCollapsed.set(id, collapsed);
        this._groupOpen.set(id, open);
        return open;
    }

    pinOpen(pin) {
        const id = String(pin.id);
        const view = String(pin.view || 'DEFAULT');
        const previous = this._pinView.get(id);
        const open = previous !== undefined && previous !== view
            ? view !== 'COMPACT' : (this._pinOpen.get(id) ?? view !== 'COMPACT');
        this._pinView.set(id, view);
        this._pinOpen.set(id, open);
        return open;
    }

    trackGroup(id, header, content) {
        this._groupRows.set(id, {header, content});
    }

    trackPin(id, header, content) {
        this._pinRows.set(id, {header, content});
    }

    toggleGroup(id) {
        const open = !this._groupOpen.get(id);
        this._groupOpen.set(id, open);
        this._apply(this._groupRows.get(id), open);
        return open;
    }

    togglePin(id) {
        const open = !this._pinOpen.get(id);
        this._pinOpen.set(id, open);
        this._apply(this._pinRows.get(id), open);
        return open;
    }

    sync(state) {
        for (const group of state.quickGroups || []) {
            const id = String(group.id || group.name);
            const collapsed = Boolean(group.collapsed);
            if (this._groupCollapsed.has(id) && this._groupCollapsed.get(id) !== collapsed) {
                const open = !collapsed;
                this._groupOpen.set(id, open);
                this._apply(this._groupRows.get(id), open);
            }
            this._groupCollapsed.set(id, collapsed);
        }
        for (const pin of state.quickPins || []) {
            const id = String(pin.id);
            const view = String(pin.view || 'DEFAULT');
            if (this._pinView.has(id) && this._pinView.get(id) !== view) {
                const open = view !== 'COMPACT';
                this._pinOpen.set(id, open);
                this._apply(this._pinRows.get(id), open);
            }
            this._pinView.set(id, view);
        }
    }

    retain(state) {
        this._retain(this._groupOpen, (state.quickGroups || []).map(group => String(group.id || group.name)));
        this._retain(this._groupCollapsed, (state.quickGroups || []).map(group => String(group.id || group.name)));
        this._retain(this._pinOpen, (state.quickPins || []).map(pin => String(pin.id)));
        this._retain(this._pinView, (state.quickPins || []).map(pin => String(pin.id)));
    }

    _retain(map, ids) {
        const keep = new Set(ids);
        for (const id of map.keys())
            if (!keep.has(id)) map.delete(id);
    }

    _apply(row, open) {
        if (!row) return;
        row.content.actor.visible = open;
        row.header.setIcon(open ? 'pan-down-symbolic' : 'pan-end-symbolic');
        if (open) row.header.add_accessible_state(Atk.StateType.EXPANDED);
        else row.header.remove_accessible_state(Atk.StateType.EXPANDED);
    }

    destroy() {
        this.resetRows();
        this._groupOpen.clear();
        this._groupCollapsed.clear();
        this._pinOpen.clear();
        this._pinView.clear();
    }
}
