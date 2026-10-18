#!/usr/bin/env python3
"""Chrome Native Messaging bridge. PCM is sent only to Audio Matrix's virtual PipeWire sink."""

import base64
import json
import os
import shutil
import struct
import subprocess
import sys
import time

APP_ID = "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture"
APP_NAME = "Chrome Tab Capture"
SINK_NAME = "audiomatrix_system"
MAX_MESSAGE = 1_000_000
MAX_PCM_BYTES = 512_000


class Host:
    def __init__(self, popen=subprocess.Popen, pwcat=None):
        self.popen = popen
        self.pwcat = pwcat or shutil.which("pw-cat")
        self.streams = {}

    def command(self, tab_id, title=""):
        if not self.pwcat:
            raise RuntimeError("pw-cat is unavailable; Audio Matrix tab capture is stopped.")
        properties = {
            "application.id": APP_ID,
            "application.name": APP_NAME,
            "application.process.binary": "audio-matrix-tab-capture",
            "media.name": valid_tab_title(title, tab_id),
            "target.object": SINK_NAME,
            "node.dont-fallback": True,
            "node.dont-reconnect": True,
        }
        return [self.pwcat, "--playback", "--raw", "--target", SINK_NAME,
                "--rate", "48000", "--channels", "2", "--channel-map", "FL,FR",
                "--format", "f32", "--properties=" + json.dumps(properties, separators=(",", ":")), "-"]

    def handle(self, message):
        kind = message.get("type")
        if kind == "start":
            tab_id = valid_tab_id(message.get("tabId"))
            self.stop(tab_id)
            process = self.popen(self.command(tab_id, message.get("title")), stdin=subprocess.PIPE,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, bufsize=0)
            time.sleep(0.025)
            if process.poll() is not None:
                raise RuntimeError("PipeWire rejected the Chrome tab stream; no hardware fallback was requested.")
            self.streams[tab_id] = process
            return {"type": "ready", "tabId": tab_id}
        if kind == "pcm":
            tab_id = valid_tab_id(message.get("tabId"))
            process = self.streams.get(tab_id)
            if process is None or process.poll() is not None:
                raise RuntimeError("The Chrome tab stream is not active.")
            try:
                data = base64.b64decode(message.get("data", ""), validate=True)
            except (ValueError, TypeError) as error:
                raise RuntimeError("The PCM frame is malformed.") from error
            if not data or len(data) > MAX_PCM_BYTES or len(data) % 8:
                raise RuntimeError("The PCM frame has an invalid size.")
            process.stdin.write(data)
            return None
        if kind == "stop":
            tab_id = valid_tab_id(message.get("tabId"))
            self.stop(tab_id)
            return {"type": "stopped", "tabId": tab_id}
        raise RuntimeError("Unsupported Chrome tab capture message.")

    def stop(self, tab_id):
        process = self.streams.pop(tab_id, None)
        if process is None:
            return
        try:
            process.stdin.close()
        except (BrokenPipeError, OSError):
            pass
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    def close(self):
        for tab_id in list(self.streams):
            self.stop(tab_id)


def valid_tab_id(value):
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise RuntimeError("The Chrome tab identifier is invalid.")
    return value


def valid_tab_title(value, tab_id):
    if not isinstance(value, str):
        return f"Tab {tab_id}"
    title = "".join(character for character in value.strip() if character >= " " and character != "\x7f")
    title = title[:128]
    return title or f"Tab {tab_id}"


def read_message(stream):
    header = stream.read(4)
    if not header:
        return None
    if len(header) != 4:
        raise EOFError("Truncated Native Messaging header.")
    size = struct.unpack("<I", header)[0]
    if size > MAX_MESSAGE:
        raise ValueError("Native Messaging frame is too large.")
    payload = stream.read(size)
    if len(payload) != size:
        raise EOFError("Truncated Native Messaging payload.")
    return json.loads(payload.decode("utf-8"))


def write_message(stream, message):
    payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
    stream.write(struct.pack("<I", len(payload)))
    stream.write(payload)
    stream.flush()


def main():
    host = Host()
    try:
        while True:
            try:
                message = read_message(sys.stdin.buffer)
            except Exception as error:
                write_message(sys.stdout.buffer, {"type": "error", "message": str(error)})
                continue
            if message is None:
                break
            try:
                reply = host.handle(message)
                if reply:
                    write_message(sys.stdout.buffer, reply)
            except Exception as error:
                write_message(sys.stdout.buffer, {"type": "error", "message": str(error)})
    finally:
        host.close()


if __name__ == "__main__":
    main()
