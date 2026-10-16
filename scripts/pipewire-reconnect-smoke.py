#!/usr/bin/env python3
"""Run the PipeWire reconnect smoke against a private temporary daemon."""

import os
import subprocess
import tempfile


with tempfile.TemporaryDirectory(prefix="audio-matrix-pw-reconnect-", dir="/tmp") as runtime:
    environment = os.environ.copy()
    environment["XDG_RUNTIME_DIR"] = runtime
    environment["PIPEWIRE_RUNTIME_DIR"] = runtime
    environment["PIPEWIRE_REMOTE"] = "pipewire-0"
    environment["AUDIO_MATRIX_PIPEWIRE_RECONNECT_SMOKE"] = "1"
    raise SystemExit(
        subprocess.run(
            [
                "./gradlew",
                "test",
                "--tests",
                "io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackendReconnectLiveSmokeTest",
                "--no-configuration-cache",
                "--no-daemon",
            ],
            env=environment,
            check=False,
        ).returncode
    )
