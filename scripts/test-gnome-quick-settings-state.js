import {hasAvailableMenuState, menuLevelRows, menuStructureSignature, orderedQuickGroups, stableMenuRows,
    visibleApplicationStreams, visibleBrowserTabs} from '../gnome/audio-matrix-quick-settings/menu-state.js';

function assert(condition, message) {
    if (!condition)
        throw new Error(message);
}

const snapshot = {
    available: true,
    language: 'ru',
    state: {
        profiles: [{id: 'main', name: 'Основной'}],
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

const groups = {quickGroups: [
    {id: 'outputs', name: 'My output group', order: 20},
    {id: 'custom', name: 'Anything / любые имена', order: 10},
    {id: 'sources', name: 'Sources', order: 0},
]};
assert(orderedQuickGroups(groups).map(group => group.id).join(',') === 'sources,custom,outputs',
    'Source/output/custom groups must share one persisted vertical ordering');
assert(groups.quickGroups[0].id === 'outputs', 'Rendering must not mutate the shared snapshot');
const renamed = clone(snapshot);
renamed.state.quickGroups[0].name = 'Anything / любые имена';
assert(menuStructureSignature(renamed) !== firstSignature, 'Arbitrary group renames must reach Shell');
const chrome = {id: 'google-chrome', streams: [{id: 'native-stream', name: 'Chrome audio'}]};
assert(visibleApplicationStreams(chrome).length === 1,
    'A PipeWire stream remains an ordinary app stream even without browser tab identity');
const browserTab = {browser: 'chrome', instanceId: 'instance-1', profileId: 'default',
    sessionToken: 'session-1', tabId: 19, title: 'YouTube'};
assert(visibleBrowserTabs({browserTabs: [browserTab]}).length === 1,
    'A browser-tab row is sourced from an adapter snapshot with a session token');
assert(visibleBrowserTabs({browserTabs: [{tabId: 19, title: 'YouTube'}]}).length === 0,
    'A title or transient tab ID alone cannot fabricate a browser-tab row');
const browserSnapshot = clone(snapshot);
browserSnapshot.state.quickPins[0].groupId = 'sources';
browserSnapshot.state.quickPins[0].applications = [{id: 'chrome', browserTabs: [browserTab]}];
const stableRows = stableMenuRows(browserSnapshot.state);
assert(stableRows.some(row => row.id === 'browser-tab:chrome:instance-1:default:session-1'),
    'Browser tabs use adapter session identity, not title, URL, or tab ID');
const profileSnapshot = clone(snapshot);
profileSnapshot.state.profiles = [{id: 'main', name: 'Основной'}, {id: 'studio', name: 'Студия'}];
assert(menuStructureSignature(profileSnapshot) !== firstSignature,
    'The persisted profile selector refreshes when profile choices change');
const capabilityChange = clone(snapshot);
capabilityChange.state.quickPins[0].tabAdapterAvailable = true;
assert(menuStructureSignature(capabilityChange) !== firstSignature,
    'Adapter connect/disconnect must rebuild the visible stream controls');
const gain = clone(snapshot);
gain.state.quickPins.push({id: 'pin-gain', nodeId: 'vibro-gain', name: 'Vibro Gain',
    nodeType: 'gain', groupId: 'vibro', order: 1,
    gain: {routeId: 'common', nodeId: 'gain-1', volume: 0.5, muted: false}, controls: [], applications: []});
const gainSignature = menuStructureSignature(gain);
assert(menuLevelRows(gain.state).get('graph-gain:vibro-gain')?.volume === 0.5,
    'Pinned Gain must expose a live level row');
const movedGain = clone(gain);
movedGain.state.quickPins.at(-1).gain.volume = 0.2;
movedGain.state.quickPins.at(-1).gain.muted = true;
assert(menuStructureSignature(movedGain) === gainSignature,
    'Moving or muting a pinned Gain must update the row without rebuilding the menu');
const retargetedGain = clone(gain);
retargetedGain.state.quickPins.at(-1).gain.nodeId = 'gain-2';
assert(menuStructureSignature(retargetedGain) !== gainSignature,
    'Replacing the Gain command target must rebuild the slider');

const pinMark = clone(snapshot);
pinMark.state.quickPins[0].pinned = true;
assert(menuStructureSignature(pinMark) !== firstSignature, 'Shared Pin marking changes must refresh Shell');
const slot = clone(snapshot);
slot.state.quickPins[0].nodeType = 'device_slot';
slot.state.quickPins[0].output = {id: 'usb', name: 'Vibro slot', deviceName: 'USB DAC',
    available: false, state: 'UNBOUND'};
const unboundSlot = menuStructureSignature(slot);
slot.state.quickPins[0].output.state = 'ONLINE';
slot.state.quickPins[0].output.available = true;
assert(menuStructureSignature(slot) !== unboundSlot, 'Device Slot ONLINE/OFFLINE changes must rebuild its row');
slot.state.quickPins[0].output.state = 'CONFLICT';
assert(menuStructureSignature(slot) !== unboundSlot, 'Device conflicts must reach the rendered row');
const renamedSlot = clone(slot);
renamedSlot.state.quickPins[0].output.name = 'Scene output';
assert(menuStructureSignature(renamedSlot) !== menuStructureSignature(slot),
    'Arbitrary slot label changes must reach the row');
const hardwareRename = clone(slot);
hardwareRename.state.quickPins[0].output.deviceName = 'Another DAC';
assert(menuStructureSignature(hardwareRename) === menuStructureSignature(slot),
    'deviceName must not be rendered or trigger structural rebuilds');

print('GNOME mixer state tests passed (in-place volume/filter updates, graph output gain binding, structural rebuilds)');
