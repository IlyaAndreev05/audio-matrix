#!/usr/bin/env node
/**
 * Fails when an extension module calls a helper that another extension module
 * exports without importing it. Such a call only breaks inside GNOME Shell,
 * where the real ES module graph is used, so the renderer stubs the imports
 * away and the defect stays invisible to the behavioural tests.
 */
import {readFileSync, readdirSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'gnome', 'audio-matrix-quick-settings');
const files = readdirSync(source).filter(name => name.endsWith('.js'));

const exported = new Map();
for (const file of files) {
    const text = readFileSync(join(source, file), 'utf8');
    const names = new Set();
    for (const match of text.matchAll(/export\s+(?:async\s+)?(?:function|class|const|let|var)\s+([A-Za-z_$][\w$]*)/g))
        names.add(match[1]);
    for (const match of text.matchAll(/export\s*\{([^}]*)\}/g))
        for (const entry of match[1].split(',')) {
            const name = entry.split(/\s+as\s+/).pop().trim();
            if (name) names.add(name);
        }
    exported.set(file, names);
}

const failures = [];
for (const file of files) {
    const text = readFileSync(join(source, file), 'utf8');
    const imported = new Set();
    for (const match of text.matchAll(/import\s*\{([^}]*)\}\s*from\s*'\.\/([^']+)'/g)) {
        for (const entry of match[1].split(',')) {
            const name = entry.split(/\s+as\s+/)[0].trim();
            if (name) imported.add(name);
        }
    }
    for (const match of text.matchAll(/import\s+([A-Za-z_$][\w$]*)\s+from/g)) imported.add(match[1]);
    const local = exported.get(file);
    const called = new Set();
    for (const match of text.matchAll(/(?<![.\w$])([A-Za-z_$][\w$]*)\s*\(/g)) called.add(match[1]);
    for (const name of called) {
        if (imported.has(name) || local.has(name)) continue;
        const owners = [...exported].filter(([, names]) => names.has(name)).map(([owner]) => owner);
        if (owners.length === 0) continue;
        failures.push(`${file}: calls ${name}() exported by ${owners.join(', ')} without importing it`);
    }
}

if (failures.length > 0) {
    for (const failure of failures) console.error(`FAIL ${failure}`);
    process.exit(1);
}
console.log(`extension imports OK: ${files.length} modules, no unimported cross-module call`);
