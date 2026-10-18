# Audio Matrix 0.3.2 release checklist

## Changes

- Replaces the vertical graph editor layout with a route list, graph canvas, and selected-node inspector in three resizable columns.
- Adds port-to-port drag connections and highlights the enabled graph path leading into and out of the selected node. Node positions and zoom remain presentation state.
- Compiles graph EQ bands into the existing PipeWire filter chain and carries graph delay values into the route plan. Filter settings remain in the graph JSON.
- Keeps the GNOME Shell extension attached across rebuilt volume menus and restores the stock menu items and button behavior when disabled. Extension version is 14 for GNOME Shell 50.
- Gives captured Chrome streams the tab title as their PipeWire stream label without forwarding the tab URL or storing tab details in the graph.
- Enforces a 200-code-line maximum for Java, GJS, and Python classes in both `test` and `check`; direct `.deb` packaging also runs the check.

## Verification

- `./gradlew test --no-daemon`
- `bash scripts/test-gnome-quick-settings.sh`
- `bash scripts/test-chrome-tab-capture.sh`
- `python3 scripts/system-sink-config-smoke.py`
- `python3 scripts/pipewire-reconnect-smoke.py`
- `PipeWireFilterChainLiveSmokeTest` in a private PipeWire + policy WirePlumber runtime
- `python3 scripts/check_class_size.py`
- `git diff --check`

The checks use graph fixtures, fake PipeWire processes, temporary extension installs, and a private test environment. They do not change the active system output, volume, mute, or Family 17h profile, and they do not play audio through physical outputs. A GNOME 50.1 live-session activation and audible output through the physical connectors still require user review after manual installation.

The Debian package is prepared for manual installation. No package, system service, GNOME extension state, or personal audio configuration is changed by this release preparation step.
