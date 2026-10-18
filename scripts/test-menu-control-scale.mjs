import assert from 'node:assert/strict';
import {controlPosition, controlValueAt, formatControlValue} from '../gnome/audio-matrix-quick-settings/menu-control-scale.js';

const frequency = {minimum: 20, maximum: 20000, scale: 'log', unit: 'Hz', precision: 0, value: 100};
assert.ok(Math.abs(controlPosition(frequency) - (Math.log(100) - Math.log(20)) / (Math.log(20000) - Math.log(20))) < 1e-12);
assert.ok(Math.abs(controlValueAt(frequency, 0.5) - Math.sqrt(20 * 20000)) < 1e-9);
assert.equal(formatControlValue(frequency, 2000), '2.0 kHz');

const ceiling = {minimum: -12, maximum: 0, scale: 'linear', unit: 'dB', precision: 1, value: -6};
assert.equal(controlPosition(ceiling), 0.5);
assert.equal(controlValueAt(ceiling, 0.25), -9);
assert.equal(formatControlValue(ceiling, -6), '-6 dB');
assert.equal(controlPosition({...frequency, value: 40000}), 1);
console.log('GNOME range controls map to their typed values and display units.');
