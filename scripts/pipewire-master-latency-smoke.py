#!/usr/bin/env python3
"""Measure the GNOME master-volume reaction on a private PipeWire graph."""

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
        AUDIO_MATRIX_MASTER_LATENCY_SMOKE="1",
        AUDIO_MATRIX_MASTER_LATENCY_RESULT=str(root / "master-latency-result.json"),
    )
    for name in ("PIPEWIRE_CONFIG_DIR", "PIPEWIRE_CONFIG_PREFIX", "PIPEWIRE_CONFIG_NAME"):
        environment.pop(name, None)
    config_dir = root / "pipewire-config"
    config_dir.mkdir()
    for source in pathlib.Path("/usr/share/pipewire").iterdir():
        if source.is_file():
            shutil.copy(source, config_dir / source.name)
    (config_dir / "pipewire.conf.d").mkdir()
    shutil.copy(pathlib.Path("src/main/pipewire/pipewire.conf.d/90-audiomatrix-system-sink.conf"),
                config_dir / "pipewire.conf.d" / "90-audiomatrix-system-sink.conf")
    environment["PIPEWIRE_CONFIG_DIR"] = str(config_dir)
    return environment


def start(name, command, environment, logs, processes):
    log = (pathlib.Path(environment["XDG_RUNTIME_DIR"]) / f"{name}.log").open("w+")
    logs.append(log)
    process = subprocess.Popen(command, env=environment, stdout=log, stderr=log)
    processes.append(process)
    return process


def wait_for_core(process, environment, log_path):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Private PipeWire exited: {log_path.read_text()[-3000:]}")
        if subprocess.run(["pw-cli", "info", "0"], env=environment,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
            return
        time.sleep(0.1)
    raise RuntimeError(f"Private PipeWire did not become ready: {log_path.read_text()[-3000:]}")


def main():
    required = ("pipewire", "wireplumber", "pw-cli", "pw-dump", "pw-link", "pw-cat", "wpctl")
    missing = [command for command in required if shutil.which(command) is None]
    if missing:
        raise SystemExit("Missing required PipeWire tools: " + ", ".join(missing))

    with tempfile.TemporaryDirectory(prefix="audio-matrix-master-latency-", dir="/tmp") as temporary:
        root = pathlib.Path(temporary)
        environment = private_environment(root)
        server_environment = environment.copy()
        server_environment.pop("PIPEWIRE_REMOTE", None)
        logs, processes = [], []
        try:
            server = start("pipewire", ["pipewire"], server_environment, logs, processes)
            wait_for_core(server, environment, root / "pipewire.log")
            wireplumber = start("wireplumber", ["wireplumber", "--profile", "policy"], environment, logs, processes)
            time.sleep(0.5)
            if wireplumber.poll() is not None:
                raise RuntimeError(f"Private WirePlumber exited: {(root / 'wireplumber.log').read_text()[-3000:]}")

            log_path = root / "gradle-latency.log"
            with log_path.open("w+") as log:
                process = subprocess.Popen(
                    ["./gradlew", "test",
                     "--tests", "io.github.ilyaandreev05.audiomatrix.audio.PipeWireMasterVolumeLatencyLiveSmokeTest",
                     "--no-configuration-cache", "--no-daemon", "--rerun-tasks"],
                    env=environment, stdout=log, stderr=log)
                processes.append(process)
                code = process.wait()
            result_path = pathlib.Path(environment["AUDIO_MATRIX_MASTER_LATENCY_RESULT"])
            if code != 0:
                diagnostic = result_path.read_text() if result_path.exists() else "no result file"
                raise RuntimeError("Private master-volume latency smoke failed:\n"
                                   f"{log_path.read_text()[-5000:]}\nresult: {diagnostic}")
            result = json.loads(result_path.read_text())
            expected = ("samples", "latency_ms_p50", "latency_ms_p95", "latency_ms_max",
                        "pcm_master_ratio", "pcm_loud_amplitude", "pcm_quiet_amplitude")
            if any(key not in result for key in expected):
                raise RuntimeError(f"The master-volume result is incomplete: {result}")
            if result["samples"] < 20:
                raise RuntimeError(f"Too few latency samples: {result['samples']}")
            print(
                "Private master-volume latency measured: "
                f"p50={result['latency_ms_p50']:.1f} ms "
                f"p95={result['latency_ms_p95']:.1f} ms "
                f"max={result['latency_ms_max']:.1f} ms "
                f"samples={result['samples']} "
                f"detect_p50={result.get('detect_ms_p50', float('nan')):.1f} "
                f"detect_p95={result.get('detect_ms_p95', float('nan')):.1f} "
                f"config_p50={result.get('config_ms_p50', float('nan')):.1f} "
                f"config_p95={result.get('config_ms_p95', float('nan')):.1f} "
                f"apply_effect_p50={result.get('latency_ms_p50', 0) - result.get('detect_ms_p50', 0) - result.get('config_ms_p50', 0):.1f} "
                f"pcm_ratio={result['pcm_master_ratio']:.4f} "
                f"pcm_loud={result['pcm_loud_amplitude']:.4f} "
                f"pcm_quiet={result['pcm_quiet_amplitude']:.4f}"
            )
            if result["latency_ms_p95"] >= 150.0:
                raise RuntimeError(f"Master-volume p95 exceeded 150 ms: {result['latency_ms_p95']}")
            if abs(result["pcm_master_ratio"] - 0.5) >= 0.03:
                raise RuntimeError(f"Master volume no longer scales PCM by 0.5: {result['pcm_master_ratio']}")
            print("Private master-volume latency smoke passed.")
            return 0
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=2)
            for log in logs:
                log.close()


if __name__ == "__main__":
    raise SystemExit(main())
