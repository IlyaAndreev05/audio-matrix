#!/usr/bin/env python3
"""Prove that one synthetic api_test event reaches private PCM taps end-to-end.

The harness never touches the host audio graph: it starts its own PipeWire core,
WirePlumber policy, virtual sinks, Xvfb display and D-Bus session. Audio Matrix and
Event Sound Studio start from *copies* of the live configurations inside a private
HOME; the physical output slots of the copy are remapped to the virtual sinks, and
the private copy's master and rear gains are capped with their mutes released.
The trigger is the application's own "API event: test" button, and the bound sound
is identified in every PCM tap by correlating with its configured WAV asset.

Usage: python3 scripts/private-event-delivery-proof.py [--keep]
"""

import hashlib
import json
import os
import re
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import wave

import numpy as np

SAMPLE_RATE = 48000
CHANNELS = 2
FRAMES_PER_SECOND = SAMPLE_RATE
REAR_SINK = "am_private_rear"
REAR_MONITOR = "am_private_rear_monitor"
MONITOR_SINK = "am_private_monitor"
MONITOR_MONITOR = "am_private_monitor_monitor"
HOST_REAR_DEVICE = "alsa_output.pci-0000_0b_00.3.analog-stereo"
LIVE_AUDIO_MATRIX = pathlib.Path("/home/ilya/.config/audio-matrix/config.json")
LIVE_EVENT_SOUND_STUDIO = pathlib.Path("/home/ilya/.config/event-sound-studio")
AUDIO_MATRIX_APP = pathlib.Path(os.environ.get("AUDIO_MATRIX_APP", "/opt/audiomatrix/bin/AudioMatrix"))
EVENT_SOUND_STUDIO_APP = pathlib.Path(os.environ.get("EVENT_SOUND_STUDIO_APP", "/opt/eventsoundstudio/bin/EventSoundStudio"))
EVENT_LOOPBACK = pathlib.Path(__file__).resolve().parent.parent / "src/main/systemd/user/audiomatrix-events-loopback.sh"
# The API test button of the 1320x850 Event Sound Studio home page, as fractions.
API_BUTTON = (1208 / 1320, 568 / 850)
# Highest linear master/rear gain this proof allows in its own private graph.
PRIVATE_VOLUME_CAP = 0.042875
# Peak-ratio measurement tolerance: taps are independent capture streams, so the
# correlated burst peak may differ by a sample phase, not because the gain differs.
RATIO_RELATIVE_TOLERANCE = 0.25
REAR_DEVICE_ID = "family17h-rear-lineout"


class ProofError(RuntimeError):
    pass


def run(command, environment, timeout=10):
    return subprocess.run(command, env=environment, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=False)


def start(name, command, environment, root, logs, stdout=None):
    log = (root / f"{name}.log").open("w+")
    logs.append(log)
    process = subprocess.Popen(command, env=environment, stdout=log if stdout is None else stdout, stderr=log)
    return process


def stop(process, timeout=3):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=timeout)


def private_environment(root, display):
    home = root / "home"
    home.mkdir()
    for directory in ("config", "data", "state", "cache"):
        (root / directory).mkdir()
    environment = os.environ.copy()
    environment.update(
        HOME=str(home),
        XDG_RUNTIME_DIR=str(root),
        XDG_CONFIG_HOME=str(root / "config"),
        XDG_DATA_HOME=str(root / "data"),
        XDG_STATE_HOME=str(root / "state"),
        XDG_CACHE_HOME=str(root / "cache"),
        PIPEWIRE_RUNTIME_DIR=str(root),
        PIPEWIRE_REMOTE="pipewire-0",
        DISPLAY=f":{display}",
        GDK_BACKEND="x11",
        NO_AT_BRIDGE="1",
    )
    for name in ("PIPEWIRE_CONFIG_DIR", "PIPEWIRE_CONFIG_PREFIX", "PIPEWIRE_CONFIG_NAME",
                 "WAYLAND_DISPLAY", "XDG_SESSION_TYPE"):
        environment.pop(name, None)
    if not str(root).startswith("/tmp/"):
        raise ProofError(f"The private runtime must stay under /tmp, not {root}")
    return environment


def free_display():
    for number in range(90, 130):
        if not pathlib.Path(f"/tmp/.X{number}-lock").exists():
            return number
    raise ProofError("No free Xvfb display number in :90..:129")


def start_xvfb(root, display, logs):
    process = start("xvfb", ["Xvfb", f":{display}", "-screen", "0", "1600x1000x24", "-nolisten", "tcp"],
                    os.environ.copy(), root, logs)
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ProofError(f"Xvfb exited: {(root / 'xvfb.log').read_text()[-2000:]}")
        if run(["xdpyinfo", "-display", f":{display}"], os.environ.copy()).returncode == 0:
            return process
        time.sleep(0.1)
    raise ProofError("Xvfb did not become ready")


def start_dbus(root, logs):
    address = f"unix:path={root / 'bus'}"
    process = start("dbus", ["dbus-daemon", "--session", "--nofork", f"--address={address}"],
                    os.environ.copy(), root, logs)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ProofError(f"Private D-Bus exited: {(root / 'dbus.log').read_text()[-2000:]}")
        if (root / "bus").exists():
            return process, address
        time.sleep(0.05)
    raise ProofError("Private D-Bus socket did not appear")


def sink_config(name, monitor):
    return f"""context.properties = {{ log.level = 1 }}
context.spa-libs = {{
  audio.convert.* = audioconvert/libspa-audioconvert
  support.* = support/libspa-support
}}
context.modules = [
  {{ name = libpipewire-module-rt args = {{ }} flags = [ ifexists nofail ] }}
  {{ name = libpipewire-module-protocol-native }}
  {{ name = libpipewire-module-client-node }}
  {{ name = libpipewire-module-adapter }}
  {{ name = libpipewire-module-filter-chain args = {{
      node.description = "Private proof sink {name}"
      media.name = "Private proof sink {name}"
      filter.graph = {{ nodes = [ {{ type = builtin name = passthrough label = linear control = {{ "Mult" = 1 "Add" = 0 }} }} ] }}
      capture.props = {{ node.name = {name} node.description = "Private proof sink {name}" media.class = Audio/Sink node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }}
      playback.props = {{ node.name = {monitor} node.description = "Private proof sink {name} monitor" node.passive = true node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }}
    }} }}
]
"""


def node_rows(environment):
    result = run(["pw-dump", "-N"], environment)
    if result.returncode != 0:
        return []
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return []


def nodes_named(environment, name):
    return [node for node in node_rows(environment)
            if node.get("info", {}).get("props", {}).get("node.name") == name]


def wait_node(environment, name, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = nodes_named(environment, name)
        if found:
            return found[0]
        time.sleep(0.05)
    raise ProofError(f"Node did not appear: {name}")


def ports(environment, direction):
    return [line.strip() for line in run(["pw-link", direction], environment).stdout.splitlines() if line.strip()]


def links(environment):
    """Parses pw-link -l into (source port, destination port) pairs."""
    pairs, current = [], ""
    for line in run(["pw-link", "-l"], environment).stdout.splitlines():
        if not line.startswith((" ", "\t")) and ":" in line:
            current = line.strip()
        elif line.strip().startswith("|->"):
            pairs.append((current, line.strip()[3:].strip()))
    return pairs


def wait_links(environment, required, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        present = set(links(environment))
        if all(pair in present for pair in required):
            return
        time.sleep(0.1)
    present = set(links(environment))
    raise ProofError(f"Private links did not appear: {[pair for pair in required if pair not in present]}; "
                     f"observed={sorted(present)}")


def output_port(environment, node, channel):
    candidates = ports(environment, "-o")
    for pattern in (f"{node}:monitor_{channel}", f"{node}:output_{channel}"):
        if pattern in candidates:
            return pattern
    raise ProofError(f"No output port {node} {channel} in the private graph")


def cap_private_volume(container, key):
    before = float(container.get(key, 0.0))
    after = min(before, PRIVATE_VOLUME_CAP)
    container[key] = after
    return {"before": before, "after": after}


def copy_audio_matrix_config(root):
    destination = root / "config/audio-matrix/config.json"
    destination.parent.mkdir(parents=True)
    config = json.loads(LIVE_AUDIO_MATRIX.read_text())
    caps = {}
    mixer = config.get("mixer")
    if not isinstance(mixer, dict):
        raise ProofError("The live Audio Matrix config has no mixer section")
    caps["mixer.global_volume"] = cap_private_volume(mixer, "global_volume")
    before_mute = mixer.get("global_mute")
    mixer["global_mute"] = False
    caps["mixer.global_mute"] = {"before": before_mute, "after": False}
    rear = next((device for device in config.get("devices", [])
                 if device.get("id") == REAR_DEVICE_ID), None)
    if rear is None:
        raise ProofError(f"The live Audio Matrix config has no {REAR_DEVICE_ID} device slot")
    caps[f"devices.{REAR_DEVICE_ID}.volume"] = cap_private_volume(rear, "volume")
    before_rear_mute = rear.get("mute")
    rear["mute"] = False
    caps[f"devices.{REAR_DEVICE_ID}.mute"] = {"before": before_rear_mute, "after": False}
    remap = {REAR_DEVICE_ID: REAR_SINK, "benq-hdmi": MONITOR_SINK}
    remapped = []
    for device in config.get("devices", []):
        target = remap.get(device.get("id"))
        if target is None:
            continue
        device.pop("identity", None)
        device.pop("kind", None)
        device["selector"] = {"kind": "name_equals", "value": target}
        remapped.append(device["id"])
    destination.write_text(json.dumps(config, ensure_ascii=False, indent=1))
    return {"devices": remapped, "caps": caps}


def copy_event_sound_studio_config(root):
    destination = root / "config/event-sound-studio"
    destination.mkdir(parents=True)
    shutil.copy2(LIVE_EVENT_SOUND_STUDIO / "config.json", destination / "config.json")
    for name in ("assets", "library"):
        shutil.copytree(LIVE_EVENT_SOUND_STUDIO / name, destination / name, dirs_exist_ok=True)
    config = json.loads((destination / "config.json").read_text())
    active = str(config.get("active_profile"))
    profile = next(item for item in config.get("profiles", []) if item.get("id") == active)
    binding = next(item for item in config.get("bindings", [])
                   if item.get("event") == "api_test" and item.get("enabled")
                   and item.get("id") in profile.get("enabled_bindings", []))
    sound = next(item for item in config.get("sounds", []) if item.get("id") == binding.get("sound"))
    asset = destination / sound["path"]
    if not asset.is_file():
        raise ProofError(f"Bound api_test asset is missing from the private copy: {asset}")
    return {"config": destination / "config.json", "binding": binding, "sound": sound, "asset": asset}


def start_core(root, environment, logs):
    server_environment = environment.copy()
    server_environment.pop("PIPEWIRE_REMOTE", None)
    pipewire = start("pipewire", ["pipewire"], server_environment, root, logs)
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if pipewire.poll() is not None:
            raise ProofError(f"Private PipeWire exited: {(root / 'pipewire.log').read_text()[-2000:]}")
        if run(["pw-cli", "info", "0"], environment).returncode == 0:
            break
        time.sleep(0.1)
    else:
        raise ProofError("Private PipeWire core did not become ready")
    wireplumber = start("wireplumber", ["wireplumber", "--profile", "policy"], environment, root, logs)
    time.sleep(0.3)
    if wireplumber.poll() is not None:
        raise ProofError(f"Private WirePlumber exited: {(root / 'wireplumber.log').read_text()[-2000:]}")
    sinks = []
    for name, monitor, filename in ((REAR_SINK, REAR_MONITOR, "rear.conf"), (MONITOR_SINK, MONITOR_MONITOR, "monitor.conf")):
        config = root / filename
        config.write_text(sink_config(name, monitor))
        sinks.append(start(f"{name}-sink", ["pipewire", "-c", str(config)], environment, root, logs))
        wait_node(environment, name)
    loopback = start("event-loopback", ["/bin/sh", str(EVENT_LOOPBACK)], environment, root, logs)
    wait_node(environment, "audiomatrix_events")
    time.sleep(1.0)
    return pipewire, wireplumber, sinks, loopback


def start_audio_matrix(root, environment, logs):
    process = start("audiomatrix", [str(AUDIO_MATRIX_APP), "--background"], environment, root, logs)
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ProofError(f"Audio Matrix exited: {(root / 'audiomatrix.log').read_text()[-4000:]}")
        nodes = [node.get("info", {}).get("props", {}) for node in node_rows(environment)]
        names = [props.get("node.name", "") for props in nodes]
        # The bus loopback also exposes a *_discard playback node; only the Audio/Sink is the bus.
        bus = next((name for name, props in zip(names, nodes)
                    if name.startswith("audiomatrix_bus_") and not name.endswith("_discard")
                    and props.get("media.class") == "Audio/Sink"), "")
        route = next((name for name in names
                      if name.startswith("audiomatrix_route_") and name.endswith("_playback")), "")
        limiter_in = next((name for name in names
                           if name.startswith("audiomatrix_output_") and name.endswith("_limiter_input")), "")
        limiter_out = next((name for name in names
                            if name.startswith("audiomatrix_output_") and name.endswith("_limiter_output")), "")
        if bus and route and limiter_in and limiter_out:
            route_capture = route[: -len("_playback")] + "_capture"
            required = []
            for channel in ("FL", "FR"):
                required += [
                    (f"audiomatrix_events:monitor_{channel}", f"{bus}:playback_{channel}"),
                    (f"{bus}:monitor_{channel}", f"{route_capture}:input_{channel}"),
                    (f"{route}:output_{channel}", f"{limiter_in}:playback_{channel}"),
                    (f"{limiter_out}:output_{channel}", f"{REAR_SINK}:playback_{channel}"),
                ]
            try:
                wait_links(environment, required, timeout=40)
                return process, {"bus": bus, "route": route, "limiter_input": limiter_in,
                                 "limiter_output": limiter_out, "route_capture": route_capture}
            except ProofError:
                time.sleep(0.3)
        else:
            time.sleep(0.2)
    seen = sorted({node.get('info', {}).get('props', {}).get('node.name', '') for node in node_rows(environment)})
    raise ProofError(f"Audio Matrix private event chain did not appear; nodes={seen} links={sorted(links(environment))} "
                     f"log={(root / 'audiomatrix.log').read_text()[-4000:]}")


def wait_ess_window(display, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = subprocess.run(["xdotool", "search", "--name", "^Event Sound Studio$"],
                                env={**os.environ, "DISPLAY": f":{display}"},
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip().splitlines()[0]
        time.sleep(0.2)
    raise ProofError("The private Event Sound Studio window did not appear")


def wait_ess_stream(environment, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if nodes_named(environment, "eventsoundstudio_pcm"):
            present = set(links(environment))
            required = [(f"eventsoundstudio_pcm:output_{channel}", f"audiomatrix_events:playback_{channel}")
                        for channel in ("FL", "FR")]
            if all(pair in present for pair in required):
                return
        time.sleep(0.15)
    raise ProofError("Event Sound Studio did not link its private stream to audiomatrix_events")


def start_tap(name, source, environment, root, logs):
    path = root / f"{name}.f32"
    output = path.open("wb")
    process = start(name, ["pw-cat", "--record", "--target=0", "--latency=20ms",
                           "--properties", json.dumps({"node.name": name, "node.autoconnect": False,
                                                       "stream.dont-remix": True}),
                           "--raw", "--format", "f32", "--rate", str(SAMPLE_RATE),
                           "--channels", str(CHANNELS), "--channel-map", "FL,FR", "-"],
                    environment, root, logs, stdout=output)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if process.poll() is not None:
            output.close()
            raise ProofError(f"Tap {name} exited: {(root / f'{name}.log').read_text()[-2000:]}")
        current = ports(environment, "-i")
        if all(f"{name}:input_{channel}" in current for channel in ("FL", "FR")):
            break
        time.sleep(0.05)
    else:
        output.close()
        raise ProofError(f"Tap {name} input ports did not appear")
    required = []
    for channel in ("FL", "FR"):
        destination = f"{name}:input_{channel}"
        result = run(["pw-link", output_port(environment, source, channel), destination], environment)
        if result.returncode != 0:
            output.close()
            raise ProofError(f"Could not link {source} to {name}: {result.stdout.strip()}")
        required.append((output_port(environment, source, channel), destination))
    wait_links(environment, required, timeout=5)
    return {"name": name, "source": source, "process": process, "output": output,
            "started": time.time(), "path": path}


def click_button(display, window, environment):
    geometry = subprocess.run(["xdotool", "getwindowgeometry", "--shell", window],
                              env=environment, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, check=True).stdout
    values = dict(line.split("=", 1) for line in geometry.splitlines() if "=" in line)
    x = int(float(values["WIDTH"]) * API_BUTTON[0])
    y = int(float(values["HEIGHT"]) * API_BUTTON[1])
    subprocess.run(["xdotool", "mousemove", "--window", window, str(x), str(y)],
                   env={**environment, "DISPLAY": f":{display}"}, check=True)
    subprocess.run(["xdotool", "click", "1"], env={**environment, "DISPLAY": f":{display}"}, check=True)
    return {"x": x, "y": y, "width": int(values["WIDTH"]), "height": int(values["HEIGHT"])}


def read_f32(path):
    data = path.read_bytes()
    usable = len(data) - len(data) % 8
    if usable == 0:
        return np.zeros(0, dtype=np.float64)
    return np.frombuffer(data[:usable], dtype="<f4").reshape(-1, 2).mean(axis=1).astype(np.float64)


def read_wav(path):
    with wave.open(str(path), "rb") as source:
        frames = source.getnframes()
        channels = source.getnchannels()
        width = source.getsampwidth()
        data = source.readframes(frames)
    if width != 2:
        raise ProofError(f"Only 16-bit WAV assets are supported: {path}")
    samples = np.frombuffer(data, dtype="<i2").astype(np.float64) / 32768.0
    if channels == 2:
        samples = samples.reshape(-1, 2).mean(axis=1)
    return samples


def correlation_curve(signal, template):
    """Normalized cross-correlation of the template across the whole signal."""
    template = template - template.mean()
    template_norm = float(np.linalg.norm(template))
    if template_norm == 0.0:
        raise ProofError("The bound sound asset is silent")
    if len(signal) < len(template):
        return np.zeros(0, dtype=np.float64)
    correlation = np.correlate(signal, template, mode="valid")
    energy = np.convolve(signal ** 2, np.ones(len(template)), mode="valid")
    return correlation / (np.sqrt(np.maximum(energy, 1e-30)) * template_norm)


def burst_at(signal, index, template):
    segment = signal[index:index + len(template)]
    if len(segment) == 0:
        return {"peak": 0.0, "rms": 0.0}
    return {"peak": float(np.max(np.abs(segment))), "rms": float(np.sqrt(np.mean(segment ** 2)))}


def analyze_taps(taps, template, click_time):
    signals = {tap["name"]: read_f32(tap["path"]) for tap in taps}
    reference = next(tap for tap in taps if tap["name"] == "tap_event_sink")
    event_signal = signals[reference["name"]]
    curve = correlation_curve(event_signal, template)
    click_offset = click_time - reference["started"]
    if len(curve) == 0:
        return {"trigger_detected": False, "taps": {}, "event_sink_corr_max": 0.0,
                "click_offset_s": round(click_offset, 4)}
    candidates = []
    for index in np.flatnonzero(curve > 0.9):
        if index == 0 or curve[index] >= curve[index - 1]:
            if index + 1 >= len(curve) or curve[index] >= curve[index + 1]:
                candidates.append(int(index))
    if not candidates:
        return {"trigger_detected": False, "taps": {},
                "event_sink_corr_max": float(np.max(curve)), "click_offset_s": round(click_offset, 4)}
    window = [index for index in candidates if -0.05 <= index / FRAMES_PER_SECOND - click_offset <= 0.6]
    chosen = min(window or candidates, key=lambda index: abs(index / FRAMES_PER_SECOND - click_offset - 0.08))
    # The captured trigger instance, not just the raw asset, is the template for the other taps:
    # their capture processes started at different moments, so a fixed time window is unreliable.
    instance = event_signal[chosen:chosen + len(template)]
    results = {"click_offset_s": round(click_offset, 4),
               "event_sink_onset_s": round(chosen / FRAMES_PER_SECOND, 4),
               "trigger_detected": bool(window), "taps": {}}
    for tap in taps:
        name = tap["name"]
        signal = signals[name]
        own_curve = curve if name == reference["name"] else correlation_curve(signal, instance)
        local = chosen if name == reference["name"] else int(np.argmax(own_curve))
        measured = burst_at(signal, local, template)
        results["taps"][name] = {
            "source": tap["source"],
            "corr": round(float(own_curve[local]), 5),
            "corr_global_max": round(float(np.max(own_curve)), 5),
            "onset_s": round(local / FRAMES_PER_SECOND, 4),
            "peak": round(measured["peak"], 8),
            "rms": round(measured["rms"], 8),
        }
    event_peak = results["taps"]["tap_event_sink"]["peak"]
    for values in results["taps"].values():
        values["ratio_to_event_sink"] = round(values["peak"] / event_peak, 5) if event_peak else 0.0
    return results


def node_linear_volume(environment, name):
    found = nodes_named(environment, name)
    for parameter in found[0].get("info", {}).get("params", {}).get("Props", []) if found else []:
        volumes = parameter.get("channelVolumes")
        if volumes:
            return float(volumes[0])
    return None


def verify_volume_ratios(analysis, route_volume, rear_volume):
    """Compares the measured tap gains with the linear gains the private graph actually runs."""
    measured = analysis.get("taps", {})
    checks = {}
    for name, expected in (("tap_route_output", route_volume),
                           ("tap_rear_post_volume", route_volume * rear_volume)):
        actual = float(measured.get(name, {}).get("ratio_to_event_sink", 0.0))
        tolerance = expected * RATIO_RELATIVE_TOLERANCE
        checks[name] = {"expected": round(expected, 8), "measured": round(actual, 8),
                        "relative_error": round(abs(actual - expected) / expected, 5) if expected else None,
                        "tolerance": round(tolerance, 8),
                        "matched": abs(actual - expected) <= tolerance}
    return checks


def host_environment():
    """The caller may itself run inside a private PipeWire environment; host probes must not inherit it."""
    environment = {key: value for key, value in os.environ.items() if not key.startswith("PIPEWIRE_")}
    environment.pop("DBUS_SESSION_BUS_ADDRESS", None)
    runtime = pathlib.Path(f"/run/user/{os.getuid()}")
    if runtime.is_dir():
        environment["XDG_RUNTIME_DIR"] = str(runtime)
    else:
        environment.pop("XDG_RUNTIME_DIR", None)
    return environment


def host_state():
    status = run(["wpctl", "status", "-n"], host_environment(), timeout=15).stdout
    sinks = {}
    for line in status.splitlines():
        match = re.match(r"^[^0-9*]*(\*)?[^0-9]*(\d+)\.\s+(\S+)\s+\[vol:\s*([0-9.]+)", line)
        if match:
            sinks[match.group(3)] = (match.group(4), bool(match.group(1)))
    files = {}
    for path in (LIVE_AUDIO_MATRIX, LIVE_EVENT_SOUND_STUDIO / "config.json"):
        stat = path.stat()
        files[str(path)] = {"mtime_ns": stat.st_mtime_ns, "size": stat.st_size,
                            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    return {"sinks": sinks, "files": files}


def private_graph_has_no_hardware(environment):
    for node in node_rows(environment):
        props = node.get("info", {}).get("props", {})
        if any(key in props for key in ("device.api", "api.alsa.card", "api.bluez5.address")):
            raise ProofError(f"Private core discovered hardware: {props}")


def main():
    keep = "--keep" in sys.argv[1:]
    for command in ("pipewire", "wireplumber", "pw-cli", "pw-dump", "pw-link", "pw-cat", "pw-loopback",
                    "wpctl", "Xvfb", "xdpyinfo", "xdotool", "dbus-daemon"):
        if shutil.which(command) is None:
            raise SystemExit(f"Missing required tool: {command}")
    if not AUDIO_MATRIX_APP.exists() or not EVENT_SOUND_STUDIO_APP.exists():
        raise SystemExit(f"Installed applications are required: {AUDIO_MATRIX_APP}, {EVENT_SOUND_STUDIO_APP}")
    if not LIVE_AUDIO_MATRIX.is_file() or not (LIVE_EVENT_SOUND_STUDIO / "config.json").is_file():
        raise SystemExit("Live configurations are missing; they are read-only inputs for the private copies")

    host_before = host_state()
    root = pathlib.Path(tempfile.mkdtemp(prefix="audio-matrix-private-event-proof-", dir="/tmp"))
    display = free_display()
    environment = private_environment(root, display)
    logs, processes, taps = [], [], []
    am = ess = None
    succeeded = False
    try:
        xvfb = start_xvfb(root, display, logs)
        processes.append(xvfb)
        dbus, address = start_dbus(root, logs)
        processes.append(dbus)
        environment["DBUS_SESSION_BUS_ADDRESS"] = address
        pipewire, wireplumber, sinks, loopback = start_core(root, environment, logs)
        processes.extend([pipewire, wireplumber, *sinks, loopback])
        private_graph_has_no_hardware(environment)

        private_config = copy_audio_matrix_config(root)
        studio = copy_event_sound_studio_config(root)
        am, chain = start_audio_matrix(root, environment, logs)
        processes.append(am)
        ess = start("ess", [str(EVENT_SOUND_STUDIO_APP)], environment, root, logs)
        processes.append(ess)
        window = wait_ess_window(display)
        wait_ess_stream(environment)
        # The audio engine opens its event line before the 53 WAV assets finish loading;
        # triggering earlier is accepted by the button but the router has no rendered sound yet.
        time.sleep(8)

        tap_sources = {
            "tap_event_sink": "audiomatrix_events",
            "tap_bus": chain["bus"],
            "tap_route_output": chain["route"],
            "tap_rear_pre_volume": REAR_SINK,
            "tap_rear_post_volume": REAR_MONITOR,
        }
        template = read_wav(studio["asset"])
        analysis = {"trigger_detected": False, "taps": {}}
        click = None
        attempts = 0
        for attempts in range(1, 3):
            for name, source in tap_sources.items():
                taps.append(start_tap(name, source, environment, root, logs))
            time.sleep(0.8)
            clicked_at = time.time()
            click = click_button(display, window, environment)
            time.sleep(1.8)
            for tap in taps:
                stop(tap["process"], timeout=2)
                tap["output"].close()
            analysis = analyze_taps([{"name": tap["name"], "source": tap["source"], "started": tap["started"],
                                      "path": tap["path"]} for tap in taps], template, clicked_at)
            if analysis.get("trigger_detected"):
                break
            taps.clear()
            time.sleep(1.5)
        route_volume = node_linear_volume(environment, chain["route"])
        sink_volume = node_linear_volume(environment, REAR_SINK)
        ratio_checks = verify_volume_ratios(analysis, route_volume, sink_volume) \
            if route_volume is not None and sink_volume is not None else {}
        host_after = host_state()
        host_unchanged = host_before["sinks"] == host_after["sinks"] and host_before["files"] == host_after["files"]
        summary = {
            "private_root": str(root),
            "trigger": {"button": "API event: test", "binding": studio["binding"]["id"],
                        "sound": studio["sound"]["id"], "asset": str(studio["asset"])},
            "click": click,
            "trigger_attempts": attempts,
            "private_chain": chain,
            "remapped_devices": private_config["devices"],
            "private_volume_caps": private_config["caps"],
            "private_volume_cap": PRIVATE_VOLUME_CAP,
            "route_linear_volume": route_volume,
            "rear_linear_volume": sink_volume,
            "ratio_checks": ratio_checks,
            "analysis": analysis,
            "host_unchanged": host_unchanged,
            "host_rear_volume_before": host_before["sinks"].get(HOST_REAR_DEVICE),
            "host_rear_volume_after": host_after["sinks"].get(HOST_REAR_DEVICE),
        }
        (root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1))
        print(json.dumps(summary, ensure_ascii=False, indent=1))

        measured = analysis.get("taps", {})
        if not analysis.get("trigger_detected") or not measured:
            raise ProofError("The api_test sound was not detected at the event sink tap")
        for name in tap_sources:
            if measured.get(name, {}).get("peak", 0.0) <= 0.0:
                raise ProofError(f"PCM is zero at {name}: {measured.get(name)}")
        if route_volume is None or sink_volume is None:
            raise ProofError("The private graph did not expose the route/rear linear volume")
        if route_volume > PRIVATE_VOLUME_CAP + 1e-6 or sink_volume > PRIVATE_VOLUME_CAP + 1e-6:
            raise ProofError(f"The private volume cap did not hold: route={route_volume}, rear={sink_volume}, "
                             f"cap={PRIVATE_VOLUME_CAP}")
        for name, check in ratio_checks.items():
            if not check["matched"]:
                raise ProofError(f"{name} ratio {check['measured']} does not match the private graph gain "
                                 f"{check['expected']} (tolerance {check['tolerance']})")
        if not host_unchanged:
            raise ProofError("The private proof changed the host audio state")
        succeeded = True
        print("Private event delivery proof passed.", file=sys.stderr)
        return 0
    finally:
        for tap in taps:
            stop(tap["process"], timeout=1)
            try:
                tap["output"].close()
            except Exception:
                pass
        stop(ess)
        stop(am)
        for process in reversed(processes):
            stop(process, timeout=2)
        for log in logs:
            try:
                log.close()
            except Exception:
                pass
        if keep or not succeeded:
            print(f"Artifacts kept in {root}", file=sys.stderr)
        else:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
