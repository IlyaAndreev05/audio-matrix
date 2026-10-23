#!/usr/bin/env python3
"""Exercise Audio Matrix routing on a private PipeWire graph with measured PCM."""

import json
import math
import os
import pathlib
import shutil
import struct
import subprocess
import tempfile
import threading
import time
import wave

SAMPLE_RATE = 48000
CHANNELS = 2
PCM_DURATION_SECONDS = 6
CONTINUOUS_FREQUENCY = 330.0
PRIMARY_SINK = "am_private_primary"
PRIMARY_MONITOR = "audiomatrix_private_primary_monitor"
EVENT_SINK = "am_private_event"
EVENT_MONITOR = "audiomatrix_private_event_monitor"
OWNED_NODE_PREFIXES = ("audiomatrix_bus_", "audiomatrix_route_", "audiomatrix_output_", "audiomatrix_app_")
REGRESSION_TESTS = [
    "io.github.ilyaandreev05.audiomatrix.audio.PipeWireSignedMatrixRouteLiveSmokeTest",
    "io.github.ilyaandreev05.audiomatrix.audio.GraphPipeWireRouteLiveSmokeTest",
    "io.github.ilyaandreev05.audiomatrix.audio.WirePlumberMixerControlTest.privatePipeWireAppliesLinearVolumeAndFastMuteWithoutHardware",
    "io.github.ilyaandreev05.audiomatrix.audio.ApplicationStreamRuleLiveSmokeTest",
    "io.github.ilyaandreev05.audiomatrix.audio.CapturedStreamRuleLiveSmokeTest",
    "io.github.ilyaandreev05.audiomatrix.audio.PipeWireApplicationRoutingTest",
    "io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteReconciliationTest",
    "io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteManagerTest",
]


def private_environment(root):
    home = root / "home"
    home.mkdir()
    for name in ("config", "data", "state"):
        (root / name).mkdir()
    environment = os.environ.copy()
    environment.update(
        HOME=str(home),
        XDG_RUNTIME_DIR=str(root),
        XDG_CONFIG_HOME=str(root / "config"),
        XDG_DATA_HOME=str(root / "data"),
        XDG_STATE_HOME=str(root / "state"),
        PIPEWIRE_RUNTIME_DIR=str(root),
        PIPEWIRE_REMOTE="pipewire-0",
        AUDIO_MATRIX_LIVE_PIPEWIRE_SMOKE="1",
    )
    for name in ("PIPEWIRE_CONFIG_DIR", "PIPEWIRE_CONFIG_PREFIX", "PIPEWIRE_CONFIG_NAME"):
        environment.pop(name, None)
    return environment


def create_seed_sink_config(root, sink_name, monitor_name, filename):
    """A private virtual sink whose monitor is an Audio Matrix owned node, never an application stream."""
    config = root / filename
    config.write_text(
        f'''context.properties = {{ log.level = 1 }}
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
      node.description = "Private route smoke seed"
      media.name = "Private route smoke seed"
      filter.graph = {{ nodes = [ {{ type = builtin name = passthrough label = linear control = {{ "Mult" = 1 "Add" = 0 }} }} ] }}
      capture.props = {{ node.name = {sink_name} node.description = "Private route smoke seed" media.class = Audio/Sink node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }}
      playback.props = {{ node.name = {monitor_name} node.description = "Private route smoke monitor" node.passive = true node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }}
    }} }}
]
''',
        encoding="utf-8",
    )
    return config


def run(command, environment, timeout=5):
    return subprocess.run(
        command,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
    )


def start(name, command, environment, logs, processes, stdout=None, stdin=None):
    log = (pathlib.Path(environment["XDG_RUNTIME_DIR"]) / f"{name}.log").open("w+")
    logs.append(log)
    process = subprocess.Popen(
        command,
        env=environment,
        stdin=stdin,
        stdout=log if stdout is None else stdout,
        stderr=log,
    )
    processes.append(process)
    return process


def wait_for_core(process, environment, log_path):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Private PipeWire exited: {log_path.read_text()[-3000:]}")
        if run(["pw-cli", "info", "0"], environment).returncode == 0:
            return
        time.sleep(0.1)
    raise RuntimeError(f"Private PipeWire did not become ready: {log_path.read_text()[-3000:]}")


def node_rows(environment):
    result = run(["pw-dump", "-N"], environment)
    if result.returncode != 0:
        return []
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return []


def node_by_name(environment, name):
    for node in node_rows(environment):
        props = node.get("info", {}).get("props", {})
        if props.get("node.name") == name:
            return node
    return None


def wait_for_sink(process, environment, sink_name, log_path):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Private sink client exited: {log_path.read_text()[-3000:]}")
        node = node_by_name(environment, sink_name)
        if node is not None:
            return int(node["id"])
        time.sleep(0.1)
    raise RuntimeError(f"Private sink did not appear: {log_path.read_text()[-3000:]}")


def wait_for_node(environment, name, present=True, process=None, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None and present:
            raise RuntimeError(f"PipeWire client exited before node appeared: {name}")
        node = node_by_name(environment, name)
        if (node is not None) == present:
            return node
        time.sleep(0.05)
    state = "appear" if present else "disappear"
    raise RuntimeError(f"PipeWire node did not {state}: {name}")


def wait_for_application_stream(environment, application_id, present=True, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        node = next((row for row in node_rows(environment)
                     if row.get("info", {}).get("props", {}).get("application.id") == application_id), None)
        if (node is not None) == present:
            return node
        time.sleep(0.05)
    state = "appear" if present else "disappear"
    raise RuntimeError(f"Application playback stream did not {state}: {application_id}")


def stop(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=2)


def write_tone(path, frequency, amplitude, duration_seconds):
    frames = int(SAMPLE_RATE * duration_seconds)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(CHANNELS)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        samples = bytearray(frames * CHANNELS * 2)
        for frame in range(frames):
            value = int(amplitude * 32767 * math.sin(2 * math.pi * frequency * frame / SAMPLE_RATE))
            struct.pack_into("<hh", samples, frame * CHANNELS * 2, value, value)
        output.writeframes(samples)


def port_links(environment):
    """Parses the pw-link listing into the connected port pairs."""
    listing = run(["pw-link", "-l"], environment).stdout
    result = []
    current = ""
    for line in listing.splitlines():
        if not line.startswith((" ", "\t")) and ":" in line:
            current = line.strip()
        elif line.strip().startswith("|->"):
            result.append((current, line.strip()[3:].strip()))
    return result


def start_recording(name, target, root, environment, logs, processes, output_files):
    """Records the requested node; a capture that silently follows the default source is a failure."""
    path = root / f"{name}.f32"
    output = path.open("wb")
    output_files.append(output)
    properties = json.dumps({"node.name": name})
    process = start(name, [
        "pw-cat", "--record", "--target", target, "--latency=20ms", "--properties", properties,
        "--raw", "--format", "f32", "--rate", str(SAMPLE_RATE), "--channels", "2",
        "--channel-map", "FL,FR", "-",
    ], environment, logs, processes, stdout=output)
    wait_for_node(environment, name, process=process)
    connected = [link for link in port_links(environment)
                 if link[0].startswith(target + ":") and link[1].startswith(name + ":")]
    if not connected:
        raise RuntimeError(f"The capture {name} did not record {target}; links: {port_links(environment)}")
    return process, output, path


def start_playback(name, application_id, process_name, target, wav_path, volume,
                   environment, logs, processes):
    properties = json.dumps({
        "application.id": application_id,
        "application.process.binary": process_name,
        "media.name": name,
    })
    return start(name, [
        "pw-play", "--target", target, "--volume", str(volume), "--latency=20ms",
        "--properties", properties, str(wav_path),
    ], environment, logs, processes)


def read_mono_f32(path):
    data = path.read_bytes()
    usable = len(data) - len(data) % 8
    return [0.5 * (left + right) for left, right in struct.iter_unpack("<ff", data[:usable])]


def tone_amplitude(samples, frequency, start_frame=0):
    first = min(len(samples) - 1, start_frame)
    last = max(first + 1, len(samples) - SAMPLE_RATE // 4)
    count = last - first
    if count <= 0:
        return 0.0
    sin_sum = 0.0
    cos_sum = 0.0
    for frame in range(first, last):
        phase = 2 * math.pi * frequency * frame / SAMPLE_RATE
        sample = samples[frame]
        sin_sum += sample * math.sin(phase)
        cos_sum += sample * math.cos(phase)
    return 2 * math.hypot(sin_sum, cos_sum) / count


def last_window_amplitude(path, frequency, window_seconds=1.0):
    samples = read_mono_f32(path)
    if not samples:
        return 0.0
    return tone_amplitude(samples, frequency, max(0, len(samples) - int(SAMPLE_RATE * window_seconds)))


def read_route_measurement(root):
    """Reads the PCM measurement that the Audio Matrix routing smoke published after routing."""
    path = root / "route-smoke-result.json"
    if not path.exists():
        raise RuntimeError("The Audio Matrix routing smoke published no PCM measurement")
    measurement = json.loads(path.read_text())
    for key in ("app_gain", "event_gain", "app_leak", "event_leak"):
        if key not in measurement:
            raise RuntimeError(f"The Audio Matrix routing smoke measurement is incomplete: {measurement}")
    return measurement


def read_route_latency(root):
    """Reads simultaneous PCM taps measured while the Audio Matrix event route is alive."""
    path = root / "route-latency-result.json"
    if not path.exists():
        raise RuntimeError("The Audio Matrix event route published no PCM latency measurement")
    measurement = json.loads(path.read_text())
    expected = ("latency_ms_p50", "latency_ms_p95", "latency_ms_max", "sample_count",
                "reference_node", "output_node", "capture_node", "sample_rate", "links")
    if any(key not in measurement for key in expected):
        raise RuntimeError(f"The routed event latency measurement is incomplete: {measurement}")
    if (measurement.get("measurement") != "simultaneous-reference-and-routed-monitor-pcm"
            or measurement["reference_node"] != "audiomatrix_events"
            or measurement["sample_rate"] != SAMPLE_RATE or measurement["sample_count"] < 5
            or not measurement["links"]):
        raise RuntimeError(f"The latency probe did not measure the isolated event route: {measurement}")
    latencies = [measurement[key] for key in ("latency_ms_p50", "latency_ms_p95", "latency_ms_max")]
    if any(not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0
           for value in latencies) or latencies != sorted(latencies) or latencies[-1] > 100.0:
        raise RuntimeError(f"The routed event latency is invalid or exceeded 100 ms: {measurement}")
    return measurement


def start_continuous_pcm(name, sink_name, environment, logs, processes):
    properties = json.dumps({"node.name": name, "media.name": name})
    process = start(name, [
        "pw-cat", "--playback", "--target", sink_name, "--latency=20ms", "--properties", properties,
        "--raw", "--format", "f32", "--rate", str(SAMPLE_RATE), "--channels", "2",
        "--channel-map", "FL,FR", "-",
    ], environment, logs, processes, stdin=subprocess.PIPE)
    wait_for_node(environment, name, process=process)
    stop_writer = threading.Event()
    writer_errors = []

    def write_chunks():
        cursor = 0
        frames = 480
        try:
            while not stop_writer.is_set():
                chunk = bytearray(frames * CHANNELS * 4)
                for frame in range(frames):
                    value = 0.1 * math.sin(2 * math.pi * CONTINUOUS_FREQUENCY * (cursor + frame) / SAMPLE_RATE)
                    struct.pack_into("<ff", chunk, frame * CHANNELS * 4, value, value)
                # The pipe back-pressure paces the writer; sleeping here would starve the stream.
                process.stdin.write(chunk)
                cursor += frames
        except (BrokenPipeError, OSError, ValueError) as error:
            if not stop_writer.is_set():
                writer_errors.append(error)

    writer = threading.Thread(target=write_chunks, name="private-pcm-writer", daemon=True)
    writer.start()
    return process, stop_writer, writer, writer_errors


def stop_continuous_pcm(process, stop_writer, writer, output_errors):
    stop_writer.set()
    writer.join(timeout=2)
    try:
        process.stdin.close()
    except (BrokenPipeError, OSError, ValueError):
        pass
    stop(process)
    if output_errors:
        raise RuntimeError(f"Continuous private PCM writer failed: {output_errors[0]}")


def pipewire_stats(environment):
    result = run(["pw-top", "-r", "pipewire-0", "-b", "-n", "1"], environment, timeout=8)
    if result.returncode != 0:
        raise RuntimeError(f"Could not sample private PipeWire xrun counters: {result.stdout[-2000:]}")
    lines = result.stdout.splitlines()
    header = next((line.split() for line in lines if {"ID", "QUANT", "RATE", "ERR"}.issubset(set(line.split()))), None)
    if header is None:
        raise RuntimeError("pw-top did not expose QUANT/RATE/ERR columns in batch mode")
    quant_index, rate_index, error_index = (header.index(column) for column in ("QUANT", "RATE", "ERR"))
    errors = []
    periods = []
    for line in lines:
        fields = line.split()
        if len(fields) <= max(quant_index, rate_index, error_index):
            continue
        try:
            quantum, rate, count = int(fields[quant_index]), int(fields[rate_index]), int(fields[error_index])
        except ValueError:
            continue
        errors.append(count)
        if quantum > 0 and rate > 0:
            periods.append(1000.0 * quantum / rate)
    if not errors:
        raise RuntimeError("pw-top returned no node ERR counters")
    return {"xrun_errors": max(errors), "period_ms": max(periods, default=0.0)}


def owned_audio_matrix_nodes(environment):
    """Only the nodes that the Audio Matrix route manager owns are reported as its graph."""
    owned = []
    for node in node_rows(environment):
        props = node.get("info", {}).get("props", {})
        name = str(props.get("node.name", ""))
        if name.startswith(OWNED_NODE_PREFIXES):
            owned.append(f"{node.get('id')}:{name}")
    return tuple(sorted(owned))


def run_live_tests(root, environment, logs, processes, xrun_peak, period_peak, tests, label):
    command = ["./gradlew", "test"]
    for test in tests:
        command += ["--tests", test]
    command += ["--no-configuration-cache", "--no-daemon", "--rerun-tasks"]
    process = start(label, command, environment, logs, processes)
    observed = set()
    next_stats = time.monotonic() + 1.0
    while process.poll() is None:
        observed.update(owned_audio_matrix_nodes(environment))
        if time.monotonic() >= next_stats:
            stats = pipewire_stats(environment)
            xrun_peak = max(xrun_peak, stats["xrun_errors"])
            period_peak = max(period_peak, stats["period_ms"])
            next_stats = time.monotonic() + 1.0
        time.sleep(0.1)
    code = process.wait()
    observed.update(owned_audio_matrix_nodes(environment))
    if code != 0:
        log_path = root / f"{label}.log"
        raise RuntimeError(f"Private PipeWire route integration tests failed: {log_path.read_text()[-5000:]}")
    return sorted(observed), xrun_peak, period_peak


def main():
    required = ("pipewire", "wireplumber", "pw-cli", "pw-dump", "pw-link", "pw-play", "pw-cat", "pw-top", "wpctl")
    missing = [command for command in required if shutil.which(command) is None]
    if missing:
        raise SystemExit("Missing required PipeWire tools: " + ", ".join(missing))

    with tempfile.TemporaryDirectory(prefix="audio-matrix-private-route-", dir="/tmp") as temporary:
        root = pathlib.Path(temporary)
        environment = private_environment(root)
        server_environment = environment.copy()
        server_environment.pop("PIPEWIRE_REMOTE", None)
        logs = []
        output_files = []
        processes = []
        secondary = None
        main_writer = None
        try:
            server = start("pipewire", ["pipewire"], server_environment, logs, processes)
            wait_for_core(server, environment, root / "pipewire.log")
            wireplumber = start("wireplumber", ["wireplumber", "--profile", "policy"], environment, logs, processes)
            time.sleep(0.2)
            if wireplumber.poll() is not None:
                raise RuntimeError(f"Private WirePlumber exited: {(root / 'wireplumber.log').read_text()[-3000:]}")

            primary_config = create_seed_sink_config(root, PRIMARY_SINK, PRIMARY_MONITOR, "primary-sink.conf")
            secondary_config = create_seed_sink_config(root, EVENT_SINK, EVENT_MONITOR, "secondary-sink.conf")
            primary = start("primary-sink", ["pipewire", "-c", str(primary_config)], environment, logs, processes)
            primary_id = wait_for_sink(primary, environment, PRIMARY_SINK, root / "primary-sink.log")
            secondary = start("secondary-sink", ["pipewire", "-c", str(secondary_config)], environment, logs, processes)
            secondary_id = wait_for_sink(secondary, environment, EVENT_SINK, root / "secondary-sink.log")
            environment["AUDIO_MATRIX_LIVE_MIXER_NODE_ID"] = str(primary_id)
            environment["AUDIO_MATRIX_ROUTE_SMOKE_RESULT"] = str(root / "route-smoke-result.json")
            environment["AUDIO_MATRIX_ROUTE_LATENCY_RESULT"] = str(root / "route-latency-result.json")

            churn_wav = root / "churn-tone.wav"
            write_tone(churn_wav, 440, 0.25, PCM_DURATION_SECONDS)
            stats_before = pipewire_stats(environment)

            # The routed PCM measurement: Audio Matrix applies the real fixture and the smoke measures it.
            owned_ids, xrun_peak, period_peak = run_live_tests(
                root, environment, logs, processes, stats_before["xrun_errors"], 0.0,
                ["io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixRoutePcmLiveSmokeTest",
                 "io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixEventLatencyLiveSmokeTest"],
                "gradle-route-smoke")
            measurement = read_route_measurement(root)
            latency = read_route_latency(root)
            owned_ids = sorted(set(owned_ids) | set(owned_audio_matrix_nodes(environment)))
            xrun_peak = max(xrun_peak, pipewire_stats(environment)["xrun_errors"])
            route_xruns = xrun_peak - stats_before["xrun_errors"]

            main_writer = start_continuous_pcm("am_smoke_continuous_pcm", PRIMARY_SINK, environment, logs, processes)
            time.sleep(1.0)
            continuity_capture, continuity_output, continuity_path = start_recording(
                "am_smoke_continuity_capture", PRIMARY_MONITOR, root, environment, logs, processes, output_files)
            time.sleep(1.0)
            continuity_before = last_window_amplitude(continuity_path, CONTINUOUS_FREQUENCY)

            churn_app_a = "org.example.AudioMatrixRouteSmoke.ChurnA"
            churn_app_b = "org.example.AudioMatrixRouteSmoke.ChurnB"
            churn_a = start_playback("Audio Matrix stream churn A", churn_app_a, "route-smoke-churn",
                                     PRIMARY_SINK, churn_wav, 0.5, environment, logs, processes)
            first_stream = wait_for_application_stream(environment, churn_app_a)
            first_stream_id = int(first_stream["id"])
            stop(churn_a)
            wait_for_application_stream(environment, churn_app_a, present=False)
            churn_b = start_playback("Audio Matrix stream churn B", churn_app_b, "route-smoke-churn",
                                     EVENT_SINK, churn_wav, 1.0, environment, logs, processes)
            second_stream = wait_for_application_stream(environment, churn_app_b)
            second_stream_id = int(second_stream["id"])
            stop(secondary)
            wait_for_node(environment, EVENT_SINK, present=False)
            wait_for_node(environment, PRIMARY_SINK, present=True)
            time.sleep(1.0)
            continuity_during = last_window_amplitude(continuity_path, CONTINUOUS_FREQUENCY)
            if continuity_before <= 0.0 or continuity_during < continuity_before * 0.5:
                raise RuntimeError("The surviving branch lost its signal while the other output disappeared: "
                                   f"before={continuity_before:.4f} during={continuity_during:.4f}")
            stop(continuity_capture)
            continuity_output.close()
            stop(churn_b)
            secondary = start("secondary-sink-return", ["pipewire", "-c", str(secondary_config)],
                              environment, logs, processes)
            secondary_id_after = wait_for_sink(secondary, environment, EVENT_SINK, root / "secondary-sink-return.log")
            xrun_peak = max(xrun_peak, pipewire_stats(environment)["xrun_errors"])
            churn_xruns = xrun_peak - stats_before["xrun_errors"] - route_xruns

            stop_continuous_pcm(*main_writer)
            main_writer = None
            regression_failure = None
            try:
                owned_ids, xrun_peak, period_peak = run_live_tests(root, environment, logs, processes,
                                                                    xrun_peak, period_peak,
                                                                    REGRESSION_TESTS, "gradle-tests")
            except RuntimeError as error:
                regression_failure = error
            stats_after = pipewire_stats(environment)
            xrun_peak = max(xrun_peak, stats_after["xrun_errors"])
            xrun_delta = max(0, xrun_peak - stats_before["xrun_errors"])
            regression_xruns = xrun_delta - route_xruns - churn_xruns
            print(
                "Private route smoke measured: "
                f"measured_app_gain={measurement['app_gain']:.3f} "
                f"measured_event_gain={measurement['event_gain']:.3f} "
                f"app_event_leak={measurement['app_leak']:.4f} "
                f"event_app_leak={measurement['event_leak']:.4f} "
                f"routed_app_nodes={measurement.get('app_owned_nodes', {})} "
                f"owned_node_ids={sorted(set(owned_ids))} "
                f"routed_app_node_names={sorted(measurement.get('app_owned_nodes', {}).values())} "
                f"stream_churn_ids={first_stream_id}->{second_stream_id} "
                f"output_churn_id={secondary_id}->{secondary_id_after} "
                f"surviving_branch_signal={continuity_before:.4f}->{continuity_during:.4f} "
                f"routed_event_latency_ms_p50={latency['latency_ms_p50']:.2f} "
                f"p95={latency['latency_ms_p95']:.2f} max={latency['latency_ms_max']:.2f} "
                f"latency_samples={latency['sample_count']} "
                f"latency_reference={latency['reference_node']} latency_output={latency['output_node']} "
                f"graph_period_ms={max(period_peak, stats_after['period_ms']):.2f} xrun_delta={xrun_delta} "
                f"xrun_route_including_latency={route_xruns} xrun_churn={churn_xruns} "
                f"xrun_regression={regression_xruns}"
            )
            if regression_failure is not None:
                raise regression_failure
            # The route manager starts and stops its own loopback nodes while streams are live;
            # those nodes report their own xruns. The harness phases must stay clean.
            harness_xruns = churn_xruns + regression_xruns
            if harness_xruns:
                raise RuntimeError(f"Private PipeWire reported {harness_xruns} new xruns/errors "
                                   f"in the smoke harness phases")
            print("Private route smoke passed.")
            return 0
        finally:
            if main_writer is not None:
                process, stop_writer, writer, _ = main_writer
                stop_writer.set()
                writer.join(timeout=2)
                try:
                    process.stdin.close()
                except (BrokenPipeError, OSError, ValueError):
                    pass
                stop(process)
            for process in reversed(processes):
                stop(process)
            for output in output_files:
                output.close()
            for log in logs:
                log.close()


if __name__ == "__main__":
    raise SystemExit(main())
