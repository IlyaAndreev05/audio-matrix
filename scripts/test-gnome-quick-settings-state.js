import {hasAvailableMenuState, menuLevelRows, menuStructureSignature} from '../gnome/audio-matrix-quick-settings/menu-state.js';

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
            output: {id: 'device-vibro', name: 'Family 17h', graphGainNodeId: 'green-gain',
                available: true, volume: 0.32, muted: false},
            controls: [], applications: [], mixerInputs: [],
        }],
        outputs: [{id: 'device-vibro', name: 'Family 17h', volume: 0.91, muted: false}],
        applications: [],
        quickControls: [],
    },
};

const firstSignature = menuStructureSignature(snapshot);
assert(hasAvailableMenuState(snapshot), 'Connected mixer state should be renderable');
assert(!hasAvailableMenuState({available: false, state: null}),
    'The initial empty bridge state must not be rendered as connected controls');
assert(!hasAvailableMenuState({available: true, state: null}),
    'A missing state payload must not reach disclosure rendering');
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

const changedGainReference = clone(snapshot);
changedGainReference.state.quickPins[0].output.graphGainNodeId = 'new-green-gain';
assert(menuStructureSignature(changedGainReference) !== firstSignature,
    'Replacing a pinned output Gain must rebuild the command target');

const range = clone(snapshot);
range.state.quickControls = [{id: 'cutoff', nodeId: 'low-pass', parameterId: 'frequency_hz',
    label: 'Cutoff', kind: 'range', minimum: 20, maximum: 20000, scale: 'log', unit: 'Hz', precision: 0, value: 120}];
const rangeSignature = menuStructureSignature(range);
const movedCutoff = clone(range);
movedCutoff.state.quickControls[0].value = 180;
assert(menuStructureSignature(movedCutoff) === rangeSignature,
    'Moving a filter slider must update its row without rebuilding the menu');
const changedRange = clone(range);
changedRange.state.quickControls[0].maximum = 10000;
assert(menuStructureSignature(changedRange) !== rangeSignature,
    'Changing a filter control range must rebuild its slider');

print('GNOME mixer state tests passed (in-place volume/filter updates, graph output gain binding, structural rebuilds)');
