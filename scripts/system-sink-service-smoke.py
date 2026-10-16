#!/usr/bin/env python3
"""Exercise the system-sink service script with fake wpctl and no audio server."""

import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time


SERVICE = Path(__file__).resolve().parents[1] / "src/main/systemd/user/audiomatrix-system-sink.sh"
FAKE_WPCTL = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
state_path = Path(os.environ["FAKE_WPCTL_STATE"])
state = json.loads(state_path.read_text())
args = sys.argv[1:]
if args == ["status", "-n"]:
    print(" ├─ Sinks:")
    for index, name in enumerate(state["sinks"], 1):
        selected = "* " if name == state["default"] else "  "
        print(f" │  {selected}{index:>4}. {name} [vol: 1.00]")
    print(" ├─ Sources:")
elif args[:1] == ["set-default"]:
    try:
        index = int(args[1])
        state["default"] = state["sinks"][index - 1]
    except (IndexError, ValueError):
        sys.exit(1)
    state_path.write_text(json.dumps(state))
else:
    print("unsupported fake wpctl command", args, file=sys.stderr)
    sys.exit(2)
'''


def wait_for(predicate, timeout=3):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.02)
    raise AssertionError("timed out waiting for the fake wpctl state")


def run_case(default="old_output", sinks=None, disappear_old=False, change_default=None):
    with tempfile.TemporaryDirectory(prefix="audio-matrix-system-sink-") as directory:
        root = Path(directory)
        runtime = root / "runtime"
        binary = root / "bin"
        runtime.mkdir()
        binary.mkdir()
        (binary / "wpctl").write_text(FAKE_WPCTL)
        (binary / "wpctl").chmod(0o755)
        state_path = root / "wpctl.json"
        initial_sinks = list(sinks or [default])
        if "audiomatrix_system" not in initial_sinks:
            initial_sinks.append("audiomatrix_system")
        state_path.write_text(json.dumps({"default": default, "sinks": initial_sinks, "modules": {}}))
        saved = root / "state" / "audio-matrix"
        saved.mkdir(parents=True)
        (saved / "previous-system-sink").write_text(default + "\n")
        environment = os.environ | {
            "PATH": str(binary) + os.pathsep + os.environ.get("PATH", ""),
            "XDG_RUNTIME_DIR": str(runtime),
            "XDG_STATE_HOME": str(root / "state"),
            "FAKE_WPCTL_STATE": str(state_path),
            "AUDIOMATRIX_PIPEWIRE_SINK_LIBRARY": str(Path(__file__).resolve().parents[1] / "src/main/systemd/user/pipewire-sink.sh"),
        }
        process = subprocess.Popen([str(SERVICE)], env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        state = lambda: json.loads(state_path.read_text())
        wait_for(lambda: state()["default"] == "audiomatrix_system")
        assert "audiomatrix_system" in state()["sinks"]
        if disappear_old:
            current = state()
            current["sinks"].remove(default)
            state_path.write_text(json.dumps(current))
        if change_default:
            current = state()
            current["sinks"].append(change_default)
            current["default"] = change_default
            state_path.write_text(json.dumps(current))
            wait_for(lambda: state()["default"] == "audiomatrix_system")
        process.send_signal(signal.SIGTERM)
        process.wait(timeout=5)
        current = state()
        if disappear_old:
            assert current["default"] == "audiomatrix_system"
            assert "audiomatrix_system" in current["sinks"]
        else:
            assert current["default"] == default
            assert "audiomatrix_system" in current["sinks"]


if __name__ == "__main__":
    run_case()
    run_case(sinks=["old_output", "other_output"], change_default="other_output")
    run_case(disappear_old=True)
    print("system sink service smoke passed (fake wpctl; no audio output)")
