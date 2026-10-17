#!/usr/bin/env python3
"""Validate the isolated event sink service inside a private PipeWire session."""

import os
import pathlib
import shutil
import subprocess
import tempfile


with tempfile.TemporaryDirectory(prefix="audio-matrix-events-pw-", dir="/tmp") as runtime:
    environment = os.environ.copy()
    environment["XDG_RUNTIME_DIR"] = runtime
    environment["PIPEWIRE_RUNTIME_DIR"] = runtime
    environment["XDG_CONFIG_HOME"] = os.path.join(runtime, "config")
    environment["XDG_DATA_HOME"] = os.path.join(runtime, "data")
    environment["XDG_STATE_HOME"] = os.path.join(runtime, "state")
    private_home = pathlib.Path(runtime, "pipewire-home")
    private_home.mkdir()
    private_bin = pathlib.Path(runtime, "bin")
    private_bin.mkdir()
    pipewire_wrapper = private_bin / "pipewire"
    pipewire_wrapper.write_text(
        f'#!/bin/sh\nHOME="$AM_PRIVATE_HOME"\nexport HOME\nexec {shutil.which("pipewire")} "$@"\n',
        encoding="utf-8",
    )
    pipewire_wrapper.chmod(0o755)
    environment["PATH"] = os.pathsep.join([str(private_bin), environment.get("PATH", "")])
    environment["AM_PRIVATE_HOME"] = str(private_home)
    for name in ("PIPEWIRE_CONFIG_DIR", "PIPEWIRE_CONFIG_PREFIX", "PIPEWIRE_CONFIG_NAME"):
        environment.pop(name, None)
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
                "--rerun-tasks",
            ],
            env=environment,
            check=False,
        ).returncode
    )
