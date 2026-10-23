#!/usr/bin/env python3
"""Exercise the built application's generated Mixer input commands on private audio only.

The script starts a private PipeWire daemon, a policy WirePlumber, two virtual sinks and a private
D-Bus + Xvfb session, writes its own configuration under a temporary HOME, then drives the real
built application through its real D-Bus command channel:

* the generated Mixer input of a live application is published with score 1.0;
* ``set_mixer_input_volume`` sets it to 0.35, the kept state and the saved JSON both stay 0.35,
  no later published state bounces back, and the measured PCM in the virtual sink scales by 0.35;
* ``toggle_mixer_input_mute`` mutes it, the gain survives, and the virtual sink goes silent;
* stopping the source makes the input non-interactive but keeps name, level and mute;
* the reconnected source keeps the same stable port id and published 0.35 / muted state, and
  unmuting restores the measured PCM.

Requires Python 3, the built Gradle distribution and the usual PipeWire, D-Bus and Xvfb tools:

    python3 scripts/pipewire-generated-mixer-smoke.py --app build/install/audio-matrix/bin/audio-matrix

Never touches host audio: every PipeWire, D-Bus and config path is private. ``--artifacts DIR``
retains the private config, logs, State transcript and PCM captures.
"""

import argparse
import importlib.util
import json
import math
import os
import pathlib
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("route_smoke", ROOT / "scripts/pipewire-route-smoke.py")
pw = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pw)
APP_ID = "org.example.AudioMatrixGeneratedMixerSmoke"
PIN_ID = "smoke-mixer"
MIXER = "octine-main-mixer"
GENERATOR = "octine-main-source"
OUTPUT_NODE = "smoke-output"
OUTPUT_DEVICE = "smoke-device"
INPUT = "am_private_generated_input"
OUTPUT = pw.PRIMARY_SINK
TARGET_VOLUME = 0.35

# No ALSA/Bluetooth/device factories: the only driver and sinks are virtual.
CORE_CONFIG = """context.properties = {
 core.daemon = true
 core.name = pipewire-0
 default.clock.rate = 48000
 default.clock.quantum = 256
}
context.spa-libs = {
 audio.convert.* = audioconvert/libspa-audioconvert
 support.* = support/libspa-support
}
context.modules = [
 { name = libpipewire-module-protocol-native }
 { name = libpipewire-module-profiler }
 { name = libpipewire-module-metadata }
 { name = libpipewire-module-spa-node-factory }
 { name = libpipewire-module-client-node }
 { name = libpipewire-module-adapter }
 { name = libpipewire-module-link-factory }
 { name = libpipewire-module-access }
]
context.objects = [
 { factory = metadata args = { metadata.name = default } }
 { factory = spa-node-factory args = {
   factory.name = support.node.driver
   node.name = AudioMatrix-Generated-Smoke-Driver
   node.group = pipewire.dummy
   priority.driver = 20000
 } }
]
"""


class StateChannel:
    """Reads the real State signals and sends real commands back to the running application."""

    def __init__(self, root, environment, logs, processes):
        self.environment = environment
        self.messages = queue.Queue()
        self.latest = None
        self.history = []
        self.errors = []
        self.process = pw.start("state-monitor", ["busctl", "--user", "monitor", "--json=short"],
                                environment, logs, processes, stdout=subprocess.PIPE)
        self.transcript = (root / "states.jsonl").open("w", encoding="utf-8")
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def read(self):
        try:
            for raw in self.process.stdout:
                line = raw.decode("utf-8").strip()
                if not line.startswith("{"):
                    continue
                message = json.loads(line)
                if message.get("interface") != "org.ilya.AudioMatrix" or message.get("member") != "State":
                    continue
                data = (message.get("payload") or {}).get("data") or []
                if not data:
                    continue
                state = json.loads(data[0])
                self.transcript.write(json.dumps(state, ensure_ascii=False) + "\n")
                self.transcript.flush()
                self.history.append(state)
                self.messages.put(state)
        except Exception as error:
            self.errors.append(error)

    def wait(self, predicate, description, app, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.errors:
                raise RuntimeError(f"State monitor could not decode a real signal: {self.errors[0]}")
            if app.poll() is not None:
                raise RuntimeError(f"Built Audio Matrix exited ({app.returncode}) waiting for {description}")
            try:
                self.latest = self.messages.get(timeout=0.2)
            except queue.Empty:
                continue
            if predicate(self.latest):
                return self.latest
        raise AssertionError(f"Timed out waiting for {description}; last state: {self.latest}")

    def command(self, app, name, input_id, value=None):
        request = "generated-smoke:" + uuid.uuid4().hex
        fields = ["main", MIXER, input_id]
        if value is not None:
            fields.append(str(value))
        result = pw.run(["busctl", "--user", "emit", "/org/ilya/AudioMatrix",
                         "org.ilya.AudioMatrix", "Command", "ussss", "5", request,
                         str(self.latest["revision"]), name, "\t".join(fields)], self.environment)
        if result.returncode:
            raise RuntimeError(f"Could not emit the real command: {result.stdout}")
        state = self.wait(lambda state: any(ack.get("requestId") == request
                                           for ack in state.get("commandAcks", [])),
                          f"acknowledgment {request}", app)
        ack = next(item for item in state["commandAcks"] if item.get("requestId") == request)
        assert ack["status"] == "ok", f"The real command failed: {ack}"
        assert ack["target"] == {"routeId": "main", "nodeId": MIXER, "inputId": input_id}, ack
        assert ack["property"] == ("muted" if name.endswith("mute") else "volume"), ack
        assert ack["stateSequence"] == state["stateSequence"], (ack, state)
        return ack

    def close(self):
        pw.stop(self.process)
        self.reader.join(timeout=3)
        self.transcript.close()


def mixer_inputs(state):
    pin = next((item for item in state.get("quickPins", [])
                if item.get("nodeId") == MIXER and item.get("routeId") == "main"), None)
    return pin.get("mixerInputs", []) if pin else []


def input_state(state, input_id):
    return next((item for item in mixer_inputs(state) if item.get("id") == input_id), None)


def generated_input(state):
    return next((item for item in mixer_inputs(state)
                 if str(item.get("id", "")).startswith("generated-mixer-input-")), None)


def assert_level(state, input_id, volume, muted):
    item = input_state(state, input_id)
    assert item is not None, f"No published Mixer input {input_id}: {mixer_inputs(state)}"
    assert math.isclose(item["volume"], volume, abs_tol=1e-6), item
    assert item["muted"] is muted, item
    assert item.get("available", True), item
    return item


def saved_level(config_path, input_id, volume, muted):
    config = json.loads(config_path.read_text())
    route = next(route for route in config["route_library"] if route["id"] == "main")
    mixer = next(node for node in route["nodes"] if node["id"] == MIXER)
    assert math.isclose(mixer["parameters"]["input_gains"][input_id], volume, abs_tol=1e-6), mixer
    assert mixer["parameters"]["input_mutes"][input_id] is muted, mixer
    assert not any(node["id"].startswith("generated-") for node in route["nodes"]), route
    assert mixer["parameters"]["input_gains"]["applications"] == 1.0, mixer
    return mixer


def assert_stable(channel, input_id, volume, muted, since_sequence, seconds=2.0):
    """No later published state may bounce the applied values back."""
    time.sleep(seconds)
    for state in list(channel.history):
        if int(state.get("stateSequence", 0)) <= since_sequence:
            continue
        item = input_state(state, input_id)
        if item is None:
            continue
        assert math.isclose(item["volume"], volume, abs_tol=1e-6), f"the published level bounced: {item}"
        assert item["muted"] is muted, f"the published mute bounced: {item}"


def measurement(path, baseline=None, muted=False):
    # Exclude the graph transition and previously buffered PCM from the measured window.
    time.sleep(1.6)
    amplitude = pw.last_window_amplitude(path, 440.0)
    if baseline is None:
        assert amplitude > 0.02, f"No routed PCM reached the private output: {amplitude}"
    elif muted:
        assert amplitude < baseline * 0.02, f"Mute did not silence the virtual output: {amplitude}/{baseline}"
    else:
        assert abs(amplitude / baseline - TARGET_VOLUME) < 0.06, \
            f"Wrong real PCM gain: {amplitude}/{baseline}"
    return amplitude


def fixture(app, config_path, environment):
    config_path.parent.mkdir(parents=True)
    exported = pw.run([str(app), "--export-default-config", str(config_path)], environment, timeout=60)
    if exported.returncode:
        raise RuntimeError(f"The built application could not export its config: {exported.stdout}")
    config = json.loads(config_path.read_text())
    route = next(route for route in config["route_library"] if route["id"] == "main")
    nodes = {node["id"]: node for node in route["nodes"]}
    generator, mixer = nodes[GENERATOR], nodes[MIXER]
    assert generator["type"] == "dynamic_generator" and generator["parameters"]["registry"] == "applications"
    assert mixer["type"] == "mixer" and mixer["parameters"]["input_gains"]["applications"] == 1.0
    output = {"id": OUTPUT_NODE, "name": "Виртуальный выход", "type": "output",
              "channels": ["FL", "FR"], "parameters": {"device_id": OUTPUT_DEVICE}}
    # A never-matching saved source keeps the route compilable when the only application stream
    # stops: the application then keeps applying and publishing the reconciled discovery instead of
    # freezing the panel behind a failed "needs one source" apply. It carries no PCM.
    keepalive = {"id": "smoke-keepalive", "name": "Постоянный источник", "type": "source",
                 "channels": ["FL", "FR"],
                 "parameters": {"selector": "application:org.example.SmokeKeepalive"}}
    route["nodes"] = [generator, mixer, keepalive, output]
    route["connections"] = [
        {"id": "apps-mixer", "from_node": GENERATOR, "from_port": "out",
         "to_node": MIXER, "to_port": "applications", "enabled": True, "gain": 1.0},
        {"id": "keepalive-additional", "from_node": "smoke-keepalive", "from_port": "out",
         "to_node": MIXER, "to_port": "additional", "enabled": True, "gain": 1.0},
        {"id": "mixer-output", "from_node": MIXER, "from_port": "out",
         "to_node": OUTPUT_NODE, "to_port": "in", "enabled": True, "gain": 1.0},
    ]
    config["route_library"] = [route]
    config["devices"] = [{"id": OUTPUT_DEVICE, "name": "Виртуальный выход", "role": "output",
                          "selector": {"kind": "name_equals", "value": OUTPUT},
                          "volume": 1.0, "mute": False}]
    config["applications"] = []
    config["mixer"].update(global_volume=1.0, global_mute=False)
    config["engine"]["apply_on_start"] = True
    config["quick_settings"]["pins"] = [{"id": PIN_ID, "route_id": "main", "node_id": MIXER,
                                         "group_id": "routing", "view": "EXPANDED", "order": 0,
                                         "control_ids": []}]
    config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2))


def exercise(app, root):
    environment = pw.private_environment(root)
    # Xvfb and the private D-Bus session are intentionally inherited.
    environment.pop("WAYLAND_DISPLAY", None)
    library = app.resolve().parents[1] / "lib"
    environment["JAVA_OPTS"] = f"--module-path {library} --add-modules javafx.controls"
    logs, processes, files = [], [], []
    channel = None
    try:
        core_config = root / "core.conf"
        core_config.write_text(CORE_CONFIG)
        server_env = environment.copy()
        server_env.pop("PIPEWIRE_REMOTE", None)
        server = pw.start("pipewire", ["pipewire", "-c", str(core_config)], server_env, logs, processes)
        pw.wait_for_core(server, environment, root / "pipewire.log")
        policy = pw.start("wireplumber", ["wireplumber", "--profile", "policy"], environment, logs, processes)
        for name, monitor in ((INPUT, "audiomatrix_private_input_monitor"), (OUTPUT, pw.PRIMARY_MONITOR)):
            conf = pw.create_seed_sink_config(root, name, monitor, name + ".conf")
            sink = pw.start(name, ["pipewire", "-c", str(conf)], environment, logs, processes)
            pw.wait_for_sink(sink, environment, name, root / (name + ".log"))
        if policy.poll() is not None:
            raise RuntimeError("The private WirePlumber policy exited; see wireplumber.log")
        config_path = root / "config/audio-matrix/config.json"
        fixture(app, config_path, environment)
        channel = StateChannel(root, environment, logs, processes)
        application = pw.start("audio-matrix", [str(app), "--background"], environment, logs, processes)
        wav = root / "tone.wav"
        pw.write_tone(wav, 440.0, 0.1, 240)

        def playback(label):
            return pw.start_playback(label, APP_ID, "generated-mixer-smoke", INPUT, wav, 1.0,
                                     environment, logs, processes)

        player = playback("player-before")
        first_stream = pw.wait_for_application_stream(environment, APP_ID)
        state = channel.wait(lambda state: generated_input(state) is not None,
                             "the live generated mixer input", application)
        input_id = generated_input(state)["id"]
        assert_level(state, input_id, 1.0, False)
        print(f"observed generated input {input_id} at 1.0", flush=True)

        capture, output, pcm = pw.start_recording("generated-smoke-capture", pw.PRIMARY_MONITOR,
                                                  root, environment, logs, processes, files)
        baseline = measurement(pcm)
        print(f"baseline PCM amplitude {baseline:.4f}", flush=True)

        ack = channel.command(application, "set_mixer_input_volume", input_id, TARGET_VOLUME)
        state = channel.wait(lambda state: input_state(state, input_id) is not None
                             and math.isclose(input_state(state, input_id)["volume"], TARGET_VOLUME, abs_tol=1e-6),
                             "the published 0.35 level", application)
        assert_level(state, input_id, TARGET_VOLUME, False)
        saved_level(config_path, input_id, TARGET_VOLUME, False)
        assert_stable(channel, input_id, TARGET_VOLUME, False, ack["stateSequence"])
        gain = measurement(pcm, baseline)
        print(f"volume command published 0.35 (ack value {ack.get('value')}), PCM {gain:.4f}", flush=True)

        ack = channel.command(application, "toggle_mixer_input_mute", input_id)
        state = channel.wait(lambda state: input_state(state, input_id) is not None
                             and input_state(state, input_id)["muted"] is True,
                             "the published mute", application)
        assert_level(state, input_id, TARGET_VOLUME, True)
        saved_level(config_path, input_id, TARGET_VOLUME, True)
        assert_stable(channel, input_id, TARGET_VOLUME, True, ack["stateSequence"])
        silence = measurement(pcm, baseline, muted=True)
        print(f"mute command silenced the private output {silence:.5f}", flush=True)

        pw.stop(player)
        pw.wait_for_application_stream(environment, APP_ID, present=False)
        offline = channel.wait(lambda state: (input_state(state, input_id) is not None
                                              and input_state(state, input_id)["available"] is False),
                               "the non-interactive offline input", application)
        offline_input = input_state(offline, input_id)
        assert math.isclose(offline_input["volume"], TARGET_VOLUME, abs_tol=1e-6), offline_input
        assert offline_input["muted"] is True, offline_input
        name = str(offline_input.get("name") or "")
        assert name, offline_input
        assert not any(name.startswith(prefix) for prefix in
                       ("app:", "process:", "stream:", "device:", "generated-")), offline_input
        assert input_id not in name, "the offline label must never be the technical port id"
        print(f"offline input kept a human label {name!r} at 0.35 muted and became non-interactive", flush=True)

        player = playback("player-after")
        second_stream = pw.wait_for_application_stream(environment, APP_ID)
        first_serial = first_stream["info"]["props"]["object.serial"]
        second_serial = second_stream["info"]["props"]["object.serial"]
        assert first_serial != second_serial, "The reconnect did not replace the runtime stream"
        state = channel.wait(lambda state: (generated_input(state) or {}).get("available") is True,
                             "the reconnected interactive input", application)
        assert generated_input(state)["id"] == input_id, "The stable port id changed across a reconnect"
        assert_level(state, input_id, TARGET_VOLUME, True)
        muted_after_reconnect = measurement(pcm, baseline, muted=True)
        print(f"reconnected input kept the same port and mute {muted_after_reconnect:.5f}", flush=True)

        ack = channel.command(application, "toggle_mixer_input_mute", input_id)
        state = channel.wait(lambda state: input_state(state, input_id) is not None
                             and input_state(state, input_id)["muted"] is False,
                             "the published unmute", application)
        assert_level(state, input_id, TARGET_VOLUME, False)
        saved_level(config_path, input_id, TARGET_VOLUME, False)
        assert_stable(channel, input_id, TARGET_VOLUME, False, ack["stateSequence"])
        restored = measurement(pcm, baseline)
        pw.stop(capture)
        result = dict(input_id=input_id, stream_ids=[first_stream["id"], second_stream["id"]],
                      stream_serials=[first_serial, second_serial],
                      baseline=baseline, gain=gain, muted=silence, reconnected_muted=muted_after_reconnect,
                      reconnected_gain=restored, config=str(config_path),
                      state_epoch=state["stateEpoch"])
        (root / "result.json").write_text(json.dumps(result, indent=2))
        print(json.dumps(result, indent=2))
        print("Built-process generated mixer private PipeWire smoke passed.")
    except Exception:
        for name in ("audio-matrix", "pipewire", "wireplumber"):
            path = root / (name + ".log")
            if path.exists():
                print(f"\n{name} log:\n{path.read_text(errors='replace')[-6000:]}", file=sys.stderr)
        raise
    finally:
        if channel is not None:
            channel.close()
        for process in reversed(processes):
            pw.stop(process)
        for output in files:
            output.close()
        for log in logs:
            log.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--app", required=True, type=pathlib.Path,
                        help="Already built Audio Matrix executable")
    parser.add_argument("--artifacts", type=pathlib.Path,
                        help="New directory for retained private artifacts")
    args = parser.parse_args()
    args.app = args.app.resolve()
    if not args.app.is_file() or not os.access(args.app, os.X_OK):
        parser.error(f"Built executable missing or not executable: {args.app}")
    if not (args.app.parents[1] / "lib").is_dir():
        parser.error(f"Built distribution libraries missing next to {args.app}")
    required = ("pipewire", "wireplumber", "pw-cli", "pw-dump", "pw-link", "pw-play", "pw-cat",
                "pw-loopback", "dbus-run-session", "dbus-monitor", "busctl", "xvfb-run", "xauth")
    missing = [name for name in required if shutil.which(name) is None]
    if missing:
        parser.error("Missing dependencies: " + ", ".join(missing))
    if os.environ.get("AUDIO_MATRIX_GENERATED_SMOKE_PRIVATE_BUS") != "1":
        environment = os.environ.copy()
        environment["AUDIO_MATRIX_GENERATED_SMOKE_PRIVATE_BUS"] = "1"
        return subprocess.run(["dbus-run-session", "--", "xvfb-run", "-a", sys.executable,
                               str(pathlib.Path(__file__).resolve()), *sys.argv[1:]],
                              env=environment).returncode
    if args.artifacts:
        root = args.artifacts.resolve()
        root.mkdir(mode=0o700, parents=True, exist_ok=False)
        print(f"Private smoke artifacts: {root}", flush=True)
        exercise(args.app, root)
    else:
        with tempfile.TemporaryDirectory(prefix="audio-matrix-generated-smoke-", dir="/tmp") as temporary:
            exercise(args.app, pathlib.Path(temporary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
