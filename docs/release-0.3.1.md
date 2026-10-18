# Audio Matrix 0.3.1 release checklist

## Changes

- Stores each graph Matrix node's table or arrow view in `graph_ui_matrix_views`, apart from node positions and DSP values. Duplicating, saving, restoring, and deleting routes carries or removes that view state.
- Adds an explicit unity Gain after every graph source and before every graph output. The migration preserves existing edge factors, switch settings, and output limiter behavior; source endpoint Gain controls appear in GNOME, and output rows control the graph Output Gain.
- Bumps the JSON schema to version 6. Existing `config.json` is backed up to `config.json.before-schema-6` before migration; YAML and archive imports pass through the same migration.
- Keeps route Matrix cells connected through endpoint Gain nodes. Cells for processed graph paths are read-only in the shortcut matrix and remain editable in the graph editor.
- Keeps Java and GNOME JavaScript classes at or below 200 code lines.

## Verification

- `PYTHONDONTWRITEBYTECODE=1 ./gradlew test --rerun-tasks`
- `python3 scripts/check_class_size.py`
- `python3 -m json.tool src/main/resources/config.example.json`
- `python3 -m json.tool src/main/resources/schemas/config.schema.json`
- `git diff --check`

Tests use a fake PipeWire snapshot for output controls and offline graph fixtures for schema migration. No physical output, system volume, mute state, or active route was changed, and no signal was played.

The `.deb` is prepared for manual installation. Installation and the schema-6 migration are not applied by this release preparation step.
