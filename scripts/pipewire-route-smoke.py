#!/usr/bin/env python3
"""Apply and clear a silent route graph in a private PipeWire session."""

import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import time


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


def create_seed_sink_config(root, sink_name):
    config = root / "seed-sink.conf"
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
      playback.props = {{ node.name = {sink_name}_monitor node.description = "Private route smoke monitor" node.passive = true node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }}
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


def wait_for_core(process, environment, log_path):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Private PipeWire exited: {log_path.read_text()[-3000:]}")
        if run(["pw-cli", "info", "0"], environment).returncode == 0:
            return
        time.sleep(0.1)
    raise RuntimeError(f"Private PipeWire did not become ready: {log_path.read_text()[-3000:]}")


def wait_for_sink(process, environment, sink_name, log_path):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Private sink client exited: {log_path.read_text()[-3000:]}")
        result = run(["pw-dump", "-N"], environment)
        if result.returncode == 0:
            nodes = json.loads(result.stdout)
            if any(node.get("info", {}).get("props", {}).get("node.name") == sink_name for node in nodes):
                return
        time.sleep(0.1)
    raise RuntimeError(f"Private sink did not appear: {log_path.read_text()[-3000:]}")


def stop(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=2)


def main():
    required = ("pipewire", "wireplumber", "pw-cli", "pw-dump", "pw-link", "wpctl")
    missing = [command for command in required if shutil.which(command) is None]
    if missing:
        raise SystemExit("Missing required PipeWire tools: " + ", ".join(missing))

    with tempfile.TemporaryDirectory(prefix="audio-matrix-private-route-", dir="/tmp") as temporary:
        root = pathlib.Path(temporary)
        environment = private_environment(root)
        server_environment = environment.copy()
        server_environment.pop("PIPEWIRE_REMOTE", None)
        logs = {}
        processes = []

        def start(name, command, env=environment):
            logs[name] = (root / f"{name}.log").open("w+")
            process = subprocess.Popen(command, env=env, stdout=logs[name], stderr=subprocess.STDOUT)
            processes.append(process)
            return process

        try:
            server = start("pipewire", ["pipewire"], server_environment)
            wait_for_core(server, environment, root / "pipewire.log")
            wireplumber = start("wireplumber", ["wireplumber", "--profile", "policy"])
            time.sleep(0.2)
            if wireplumber.poll() is not None:
                raise RuntimeError(f"Private WirePlumber exited: {(root / 'wireplumber.log').read_text()[-3000:]}")
            sink_name = "am_private_route_seed"
            config = create_seed_sink_config(root, sink_name)
            seed = start("seed-sink", ["pipewire", "-c", str(config)])
            wait_for_sink(seed, environment, sink_name, root / "seed-sink.log")
            command = [
                "./gradlew", "test", "--tests",
                "io.github.ilyaandreev05.audiomatrix.audio.PipeWireSignedMatrixRouteLiveSmokeTest",
                "--no-configuration-cache", "--no-daemon", "--rerun-tasks",
            ]
            result = subprocess.run(command, env=environment, check=False)
            return result.returncode
        finally:
            for process in reversed(processes):
                stop(process)
            for log in logs.values():
                log.close()


if __name__ == "__main__":
    raise SystemExit(main())
