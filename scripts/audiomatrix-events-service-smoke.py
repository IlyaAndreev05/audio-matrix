#!/usr/bin/env python3
"""Validate the isolated event sink service inside a private PipeWire session."""

import os
import subprocess
import tempfile


with tempfile.TemporaryDirectory(prefix="audio-matrix-events-pw-", dir="/tmp") as runtime:
    environment = os.environ.copy()
    environment["XDG_RUNTIME_DIR"] = runtime
    environment["PIPEWIRE_RUNTIME_DIR"] = runtime
    environment["XDG_CONFIG_HOME"] = os.path.join(runtime, "config")
    environment["PIPEWIRE_REMOTE"] = "pipewire-0"
    environment["AUDIO_MATRIX_EVENTS_SERVICE_SMOKE"] = "1"
    raise SystemExit(
        subprocess.run(
            [
                "./gradlew",
                "test",
                "--tests",
                "io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixEventsServiceLiveSmokeTest",
                "--no-configuration-cache",
                "--no-daemon",
            ],
            env=environment,
            check=False,
        ).returncode
    )
