#!/usr/bin/env python3
"""Firefox metadata/mute launcher sharing Audio Matrix's audited local transport."""

import importlib.util
from pathlib import Path


def load_transport():
    # The package and the per-user installer both preserve this same directory layout.
    root = Path(__file__).resolve().parents[2]
    path = root / "chrome" / "native-host" / "audio-matrix-tab-capture.py"
    spec = importlib.util.spec_from_file_location("audio_matrix_browser_transport", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("Не найден локальный транспорт Audio Matrix.")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    return transport


if __name__ == "__main__":
    raise SystemExit(load_transport().main(browser="firefox"))
