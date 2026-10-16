#!/usr/bin/env python3
"""Parse and instantiate the packaged null sink in an isolated PipeWire daemon."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
DROP_IN = ROOT / "src/main/pipewire/pipewire.conf.d/90-audiomatrix-system-sink.conf"
OBJECTS = re.search(r"context\.objects\s*=\s*\[(.*)\]\s*$", DROP_IN.read_text(), re.DOTALL)
if OBJECTS is None:
    raise SystemExit("Audio Matrix PipeWire drop-in has no context.objects list")

BASE_CONFIG = """context.properties = {
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
        node.name = AudioMatrix-Smoke-Driver
        node.group = pipewire.dummy
        priority.driver = 20000
    } }
""" + OBJECTS.group(1) + "\n]\n"


with tempfile.TemporaryDirectory(prefix="audio-matrix-system-sink-pw-") as directory:
    runtime = Path(directory)
    config = runtime / "private-pipewire.conf"
    log = runtime / "pipewire.log"
    config.write_text(BASE_CONFIG)
    environment = os.environ | {
        "XDG_RUNTIME_DIR": str(runtime),
        "PIPEWIRE_RUNTIME_DIR": str(runtime),
        "PIPEWIRE_REMOTE": "pipewire-0",
    }
    with log.open("w") as output:
        server = subprocess.Popen(["pipewire", "-c", str(config)], env=environment,
                                  stdout=output, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 8
        nodes = []
        while time.monotonic() < deadline and server.poll() is None:
            snapshot = subprocess.run(["pw-dump"], env=environment, capture_output=True, text=True, check=False)
            if snapshot.returncode == 0:
                data = json.loads(snapshot.stdout)
                nodes = [item.get("info", {}).get("props", {}) for item in data
                         if item.get("type") == "PipeWire:Interface:Node"]
                if any(node.get("node.name") == "audiomatrix_system" for node in nodes):
                    break
            time.sleep(0.1)
        system_sink = next((node for node in nodes if node.get("node.name") == "audiomatrix_system"), None)
        if system_sink is None:
            raise AssertionError("Private PipeWire daemon did not create audiomatrix_system: " + log.read_text())
        if system_sink.get("media.class") != "Audio/Sink":
            raise AssertionError(f"Virtual input has unexpected class: {system_sink.get('media.class')}")
        if int(system_sink.get("priority.session", 0)) != 2000:
            raise AssertionError("Virtual system input did not keep its configured default priority")
        if any(node.get("media.class", "").startswith("Audio/Source") for node in nodes):
            raise AssertionError("Private configuration unexpectedly created an audio input device")
        print("system sink config smoke passed (private PipeWire, no hardware nodes or test signal)")
    finally:
        server.terminate()
        try:
            server.wait(timeout=3)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait(timeout=3)
