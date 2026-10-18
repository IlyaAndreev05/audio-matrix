import {menuLevelRows, menuStructureSignature} from '../gnome/audio-matrix-quick-settings/menu-state.js';

function assert(condition, message) {
    if (!condition)
        throw new Error(message);
}

const snapshot = {
    available: true,
    language: 'ru',
    state: {
        profile: 'Рабочий стол',
        activeRoute: 'Музыка',
        activeRouteId: 'route-1',
        quickGroups: [{id: 'sources', name: 'Источники', order: 0, collapsed: false}],
        quickPins: [{
            id: 'pin-output', routeId: 'route-1', nodeId: 'output-node', name: 'Вибродинамик',
            nodeType: 'output', groupId: 'outputs', order: 0, view: 'DEFAULT',
            output: {id: 'device-vibro', name: 'Family 17h', available: true, volume: 0.32, muted: false},
            controls: [], applications: [], mixerInputs: [],
        }],
        outputs: [{id: 'device-vibro', name: 'Family 17h', volume: 0.91, muted: false}],
        applications: [],
        quickControls: [],
    },
};

const firstSignature = menuStructureSignature(snapshot);
const clone = value => JSON.parse(JSON.stringify(value));
const changedPresentation = clone(snapshot);
changedPresentation.state.quickGroups[0].collapsed = true;
changedPresentation.state.quickPins[0].view = 'COMPACT';
changedPresentation.state.quickPins[0].output.volume = 0.08;
assert(menuStructureSignature(changedPresentation) === firstSignature,
    'Audio level and disclosure changes must update the open menu without rebuilding it');

const rowUpdates = menuLevelRows(snapshot.state);
const output = rowUpdates.get('graph-output:output-node');
assert(output?.volume === 0.32,
    'Pinned output row must follow its graph Gain, not the physical sink volume');
assert(!rowUpdates.has('output:device-vibro'),
    'Physical sink state must not overwrite a graph output control');

const addedPin = clone(snapshot);
addedPin.state.quickPins[0].id = 'different-pin';
assert(menuStructureSignature(addedPin) !== firstSignature,
    'Adding or replacing a pin must rebuild the menu structure');

print('GNOME mixer state tests passed (in-place updates, graph output gain binding, structural rebuilds)');
