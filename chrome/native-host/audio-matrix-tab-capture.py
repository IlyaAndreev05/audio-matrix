#!/usr/bin/env python3
"""Chrome Native Messaging bridge. PCM is sent only to Audio Matrix's virtual PipeWire sink."""

import base64
import json
import os
import re
import selectors
import shutil
import socket
import stat
import struct
import subprocess
import sys
import time
import uuid

APP_ID = "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture"
APP_NAME = "Chrome Tab Capture"
SINK_NAME = "audiomatrix_system"
VERSION = 1
MAX_MESSAGE = 1_000_000
MAX_PCM_BYTES = 512 * 2 * 4
CHUNK_BYTES = MAX_PCM_BYTES
MAX_BRIDGE_LINE = 1_000_000
START_TIMEOUT_SECONDS = 3.0
HEALTH_CHECK_SECONDS = 0.5
BRIDGE_RECONNECT_SECONDS = 2.0
BRIDGE_BUFFER_LIMIT = 512
BRIDGE_UNAVAILABLE_MESSAGE = "Audio Matrix is not connected; Chrome tab controls are unavailable."

ENVELOPE_FIELDS = {
    "version", "type", "requestId", "browser", "instanceId", "profileId", "tabs"
}
ACK_FIELDS = ENVELOPE_FIELDS | {"status", "error"}
COMMAND_FIELDS = ENVELOPE_FIELDS | {"sessionToken", "command"}
TAB_FIELDS = {
    "sessionToken", "tabId", "title", "audible", "muted",
    "volumeCapability", "captureStreamKey"
}
EVENT_TYPES = {"tab_created", "tab_removed", "tab_updated", "tab_audio", "window_updated"}
BRIDGE_TYPES = EVENT_TYPES | {"snapshot", "ack"}
URL_TEXT_PATTERN = re.compile(
    r"\b[a-z][a-z0-9+.-]*://[^\s]+|\bwww\.[^\s]+|"
    r"\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d+)?(?:/[^\s]*)?",
    re.IGNORECASE
)


def valid_uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value.lower()
    except (ValueError, AttributeError):
        return False


def require_uuid(value, label):
    if not valid_uuid(value):
        raise RuntimeError(f"The {label} is not a UUID.")
    return value.lower()
def require_token(value, label):
    if not isinstance(value, str) or not value or len(value) > 128:
        raise RuntimeError(f"The {label} is invalid.")
    return value


def require_request_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._:-]{1,256}", value):
        raise RuntimeError("The request ID is invalid.")
    return value




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
def safe_bridge_title(value):
    return " ".join(URL_TEXT_PATTERN.sub("", value).split())[:512]




def capture_identity(message):
    if not isinstance(message, dict) or message.get("browser") != "chrome":
        raise RuntimeError("The capture browser identity is invalid.")
    instance_id = require_uuid(message.get("instanceId"), "instance ID")
    profile_id = require_uuid(message.get("profileId"), "profile ID")
    session_token = require_token(message.get("sessionToken"), "session token")
    capture_stream_key = require_token(message.get("captureStreamKey"), "capture stream key")
    return ("chrome", instance_id, profile_id, session_token), capture_stream_key


def validate_tab_entry(tab):
    if not isinstance(tab, dict) or set(tab) != TAB_FIELDS:
        raise RuntimeError("A browser tab entry has unexpected fields.")
    require_token(tab.get("sessionToken"), "tab session token")
    valid_tab_id(tab.get("tabId"))
    if not isinstance(tab.get("title"), str) or len(tab["title"]) > 4096:
        raise RuntimeError("A browser tab title is invalid.")
    if not isinstance(tab.get("audible"), bool) or not isinstance(tab.get("muted"), bool):
        raise RuntimeError("A browser tab audio state is invalid.")
    if tab.get("volumeCapability") not in ("none", "captured"):
        raise RuntimeError("A browser tab volume capability is invalid.")
    stream_key = tab.get("captureStreamKey")
    if not isinstance(stream_key, str):
        raise RuntimeError("A browser capture stream key is invalid.")
    if tab["volumeCapability"] == "none" and stream_key != "":
        raise RuntimeError("An uncaptured tab must not expose a capture stream key.")
    if tab["volumeCapability"] == "captured":
        require_token(stream_key, "capture stream key")
    return tab


def validate_bridge_envelope(message, snapshot_seen):
    if not isinstance(message, dict):
        raise RuntimeError("A browser bridge message is not an object.")
    kind = message.get("type")
    expected = ACK_FIELDS if kind == "ack" else ENVELOPE_FIELDS
    if kind not in BRIDGE_TYPES or set(message) != expected:
        raise RuntimeError("A browser bridge message has unexpected fields or type.")
    if type(message.get("version")) is not int or message["version"] != VERSION:
        raise RuntimeError("The browser bridge protocol version is unsupported.")
    require_request_id(message.get("requestId"))
    if message.get("browser") != "chrome":
        raise RuntimeError("The browser bridge identifies an unsupported browser.")
    require_uuid(message.get("instanceId"), "instance ID")
    require_uuid(message.get("profileId"), "profile ID")
    if kind == "snapshot":
        if snapshot_seen:
            raise RuntimeError("A browser socket connection may contain only one initial snapshot.")
    elif not snapshot_seen:
        raise RuntimeError("The first browser message on a socket must be a snapshot.")
    if kind == "ack":
        if message.get("tabs") != [] or message.get("status") not in ("ok", "error") or not isinstance(message.get("error"), str):
            raise RuntimeError("The browser command acknowledgement is invalid.")
        if message["status"] == "ok" and message["error"] != "":
            raise RuntimeError("A successful command acknowledgement cannot contain an error.")
        if message["status"] == "error" and not message["error"]:
            raise RuntimeError("A failed command acknowledgement must contain a reason.")
        return message
    if kind != "snapshot" and kind not in EVENT_TYPES:
        raise RuntimeError("The browser bridge event type is unsupported.")
    tabs = message.get("tabs")
    if not isinstance(tabs, list):
        raise RuntimeError("A browser bridge tab list is invalid.")
    seen_tabs = set()
    seen_sessions = set()
    seen_capture_keys = set()
    for tab in tabs:
        validate_tab_entry(tab)
        stream_key = tab["captureStreamKey"]
        if tab["tabId"] in seen_tabs or tab["sessionToken"] in seen_sessions or (
            tab["volumeCapability"] == "captured" and stream_key in seen_capture_keys
        ):
            raise RuntimeError("A browser bridge message contains a duplicate tab identity or capture key.")
        seen_tabs.add(tab["tabId"])
        seen_sessions.add(tab["sessionToken"])
        if tab["volumeCapability"] == "captured":
            seen_capture_keys.add(stream_key)
    return message


def validate_java_command(message):
    if not isinstance(message, dict) or message.get("type") != "command":
        raise RuntimeError("The Java browser command is invalid.")
    expected = COMMAND_FIELDS | ({"muted"} if "muted" in message else set())
    if set(message) != expected:
        raise RuntimeError("The Java browser command has unexpected fields.")
    if type(message.get("version")) is not int or message["version"] != VERSION:
        raise RuntimeError("The Java browser command protocol version is unsupported.")
    require_request_id(message.get("requestId"))
    if message.get("browser") != "chrome":
        raise RuntimeError("The Java browser command targets an unsupported browser.")
    require_uuid(message.get("instanceId"), "instance ID")
    require_uuid(message.get("profileId"), "profile ID")
    require_token(message.get("sessionToken"), "tab session token")
    if message.get("tabs") != []:
        raise RuntimeError("A Java browser command must not include tab entries.")
    if message.get("command") not in ("mute", "focus", "stopCapture"):
        raise RuntimeError("The Java browser command is unsupported.")
    if message["command"] == "mute":
        if "muted" not in message or not isinstance(message["muted"], bool):
            raise RuntimeError("The Java mute command is missing its state.")
    elif "muted" in message:
        raise RuntimeError("Only a Java mute command may include muted state.")
    return message


def pipewire_node_name(capture_stream_key):
    return f"audio-matrix-chrome-{capture_stream_key}"


class PipeWireCapture:
    def __init__(self, popen, pwcat, pw_dump, pipewire_ready, startup_timeout):
        self.popen = popen
        self.pwcat = pwcat
        self.pw_dump = pw_dump
        self.pipewire_ready = pipewire_ready
        self.startup_timeout = startup_timeout
        self.streams = {}
        self.next_health_check = 0.0

    def command(self, tab_id, title, identity, capture_stream_key):
        if not self.pwcat:
            raise RuntimeError("pw-cat is unavailable; Audio Matrix tab capture is stopped.")
        browser, instance_id, profile_id, session_token = identity
        properties = {
            "application.id": APP_ID,
            "application.name": APP_NAME,
            "application.process.binary": "audio-matrix-tab-capture",
            "media.name": valid_tab_title(title, tab_id),
            "target.object": SINK_NAME,
            "node.dont-fallback": True,
            "node.dont-reconnect": True,
            "node.name": pipewire_node_name(capture_stream_key),
            "audio-matrix.browser": browser,
            "audio-matrix.instance-id": instance_id,
            "audio-matrix.profile-id": profile_id,
            "audio-matrix.session-token": session_token,
            "audio-matrix.capture-stream-key": capture_stream_key
        }
        return [self.pwcat, "--playback", "--raw", "--target", SINK_NAME,
                "--rate", "48000", "--channels", "2", "--channel-map", "FL,FR",
                "--format", "f32", "--properties=" + json.dumps(properties, separators=(",", ":")), "-"]

    def _pipewire_has_stream(self, stream):
        if self.pipewire_ready is not None:
            return bool(self.pipewire_ready(dict(stream)))
        if not self.pw_dump:
            return False
        try:
            result = subprocess.run([self.pw_dump, "-N"], capture_output=True, text=True,
                                    check=False, timeout=1.0)
            if result.returncode != 0:
                return False
            document = json.loads(result.stdout)
        except (OSError, subprocess.SubprocessError, ValueError):
            return False
        if not isinstance(document, list):
            return False
        expected_name = pipewire_node_name(stream["captureStreamKey"])
        expected = {
            "audio-matrix.browser": stream["identity"][0],
            "audio-matrix.instance-id": stream["identity"][1],
            "audio-matrix.profile-id": stream["identity"][2],
            "audio-matrix.session-token": stream["identity"][3],
            "audio-matrix.capture-stream-key": stream["captureStreamKey"]
        }
        for item in document:
            if not isinstance(item, dict) or item.get("type") != "PipeWire:Interface:Node":
                continue
            info = item.get("info")
            props = info.get("props") if isinstance(info, dict) else None
            if not isinstance(props, dict) or props.get("node.name") != expected_name:
                continue
            if all(props.get(key) == value for key, value in expected.items()):
                return True
        return False

    def _wait_for_pipewire(self, stream):
        deadline = time.monotonic() + self.startup_timeout
        while time.monotonic() < deadline:
            process = stream["process"]
            if process.poll() is not None:
                raise RuntimeError("PipeWire rejected the Chrome tab stream; no hardware fallback was requested.")
            if self._pipewire_has_stream(stream):
                return
            time.sleep(0.05)
        raise RuntimeError("PipeWire did not report a ready Chrome tab stream; no hardware fallback was requested.")

    def start(self, message):
        identity, capture_stream_key = capture_identity(message)
        tab_id = valid_tab_id(message.get("tabId"))
        title = message.get("title", "")
        if not isinstance(title, str):
            raise RuntimeError("The selected tab title is invalid.")
        key = identity
        if key in self.streams:
            raise RuntimeError("A PipeWire stream is already active for this Chrome tab session.")
        process = self.popen(self.command(tab_id, title, identity, capture_stream_key),
                             stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, bufsize=0)
        stream = {
            "identity": identity,
            "captureStreamKey": capture_stream_key,
            "tabId": tab_id,
            "title": valid_tab_title(title, tab_id),
            "process": process,
            "nextSequence": 0
        }
        self.streams[key] = stream
        try:
            if process.stdin is None:
                raise RuntimeError("PipeWire did not provide a PCM input stream.")
            self._wait_for_pipewire(stream)
        except Exception:
            self._stop_key(key)
            raise
        return stream

    def _write_pcm(self, stream, data):
        view = memoryview(data)
        while view:
            written = stream["process"].stdin.write(view)
            if not written:
                raise RuntimeError("PipeWire stopped accepting Chrome tab audio.")
            view = view[written:]
        flush = getattr(stream["process"].stdin, "flush", None)
        if flush:
            flush()

    def write_pcm(self, message):
        identity, capture_stream_key = capture_identity(message)
        tab_id = valid_tab_id(message.get("tabId"))
        key = identity
        stream = self.streams.get(key)
        if stream is None or stream["tabId"] != tab_id or stream["captureStreamKey"] != capture_stream_key:
            raise RuntimeError("The matching Chrome tab PipeWire stream is not active.")
        sequence = message.get("sequence")
        if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence != stream["nextSequence"]:
            self._stop_key(key)
            raise RuntimeError("The PCM chunk sequence is duplicated or out of order.")
        process = stream["process"]
        if process.poll() is not None:
            self._stop_key(key)
            raise RuntimeError("The Chrome tab PipeWire stream exited.")
        try:
            data = base64.b64decode(message.get("data", ""), validate=True)
        except (ValueError, TypeError) as error:
            self._stop_key(key)
            raise RuntimeError("The PCM chunk is malformed.") from error
        if len(data) != CHUNK_BYTES:
            self._stop_key(key)
            raise RuntimeError("The PCM chunk must contain exactly 512 stereo float frames.")
        try:
            self._write_pcm(stream, data)
        except (BrokenPipeError, OSError, ValueError, RuntimeError) as error:
            self._stop_key(key)
            raise RuntimeError("PipeWire failed while receiving Chrome tab audio.") from error
        stream["nextSequence"] += 1
        return len(data)

    def _stop_key(self, key):
        stream = self.streams.pop(key, None)
        if stream is None:
            return
        process = stream["process"]
        try:
            process.stdin.close()
        except (AttributeError, BrokenPipeError, OSError):
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

    def stop(self, message):
        identity, capture_stream_key = capture_identity(message)
        tab_id = valid_tab_id(message.get("tabId"))
        stream = self.streams.get(identity)
        if stream and (stream["tabId"] != tab_id or stream["captureStreamKey"] != capture_stream_key):
            raise RuntimeError("The stop request does not match the active Chrome tab capture.")
        self._stop_key(identity)

    def reap_failed_streams(self):
        now = time.monotonic()
        if now < self.next_health_check:
            return []
        self.next_health_check = now + HEALTH_CHECK_SECONDS
        failures = []
        for key, stream in list(self.streams.items()):
            process_failed = stream["process"].poll() is not None
            pipewire_missing = not process_failed and not self._pipewire_has_stream(stream)
            if process_failed or pipewire_missing:
                failures.append({
                    "identity": stream["identity"],
                    "captureStreamKey": stream["captureStreamKey"],
                    "tabId": stream["tabId"],
                    "error": "The Chrome tab PipeWire stream stopped unexpectedly."
                })
                self._stop_key(key)
        return failures

    def close(self):
        for key in list(self.streams):
            self._stop_key(key)


class JavaBridge:
    """Best-effort link to the Audio Matrix Java browser socket.

    Chrome tab capture never depends on this link. When Audio Matrix is not
    running, or the socket breaks, the envelope is kept for the next connection
    and the failure is reported to the extension instead of aborting capture.
    """

    def __init__(self, connect=None, notify=None, reconnect_seconds=BRIDGE_RECONNECT_SECONDS,
                 buffer_limit=BRIDGE_BUFFER_LIMIT):
        self.connect = connect or connect_java_socket
        self.notify = notify or (lambda message: None)
        self.reconnect_seconds = reconnect_seconds
        self.buffer_limit = buffer_limit
        self.socket = None
        self.error = ""
        self.detail = ""
        self.snapshot = None
        self.buffered = []
        self.next_attempt = 0.0

    @property
    def connected(self):
        return self.socket is not None

    def report(self, connected, message):
        self.notify({"type": "bridge-error", "connected": connected, "message": message})

    def fail(self, reason):
        """Records a bridge failure and reports every new failure once."""
        if reason == self.detail and self.error:
            return self.error
        self.detail = reason
        self.error = BRIDGE_UNAVAILABLE_MESSAGE
        print(f"Audio Matrix browser bridge unavailable: {reason}", file=sys.stderr)
        self.report(False, self.error)
        return self.error

    def connected_now(self):
        """Marks the Java socket as available and reports the recovered state."""
        self.error = ""
        self.detail = ""
        self.report(True, "")

    def close(self):
        socket = self.socket
        self.socket = None
        if socket is None:
            return
        try:
            socket.close()
        except OSError:
            pass

    def disconnect(self, reason):
        """Drops the Java socket after a disconnect and reports the failure."""
        self.close()
        self.fail(reason)

    def connect_now(self):
        """Connects to the Java socket and replays the snapshot plus buffered events."""
        if self.socket is not None:
            return self.socket
        now = time.monotonic()
        if now < self.next_attempt:
            return None
        self.next_attempt = now + self.reconnect_seconds
        try:
            socket = self.connect()
        except Exception as error:
            self.fail(str(error))
            return None
        try:
            if self.snapshot is not None:
                socket.sendall(self.snapshot)
            for payload in self.buffered:
                socket.sendall(payload)
        except OSError as error:
            self.fail(f"The Audio Matrix browser socket failed: {error}")
            try:
                socket.close()
            except OSError:
                pass
            return None
        self.buffered = []
        self.socket = socket
        self.connected_now()
        return socket

    def _send(self, payload):
        socket = self.socket or self.connect_now()
        if socket is None:
            self.fail(self.detail or "The Audio Matrix browser socket is unavailable.")
            return False
        try:
            socket.sendall(payload)
        except OSError as error:
            self.fail(f"The Audio Matrix browser socket failed: {error}")
            self.close()
            return False
        if self.error:
            self.connected_now()
        return True

    def forward(self, payload, snapshot):
        """Sends one envelope to Java and keeps it for the next connection on failure."""
        if snapshot:
            self.snapshot = payload
            self.buffered = []
        if self._send(payload):
            return True
        if not snapshot:
            self.buffered.append(payload)
            if len(self.buffered) > self.buffer_limit:
                del self.buffered[:len(self.buffered) - self.buffer_limit]
        return False


class Host:
    def __init__(self, popen=subprocess.Popen, pwcat=None, pw_dump=None,
                 pipewire_ready=None, bridge=None, startup_timeout=START_TIMEOUT_SECONDS,
                 bridge_connect=None, notify=None, bridge_reconnect=BRIDGE_RECONNECT_SECONDS):
        self.capture = PipeWireCapture(
            popen,
            pwcat or shutil.which("pw-cat"),
            pw_dump or shutil.which("pw-dump"),
            pipewire_ready,
            startup_timeout
        )
        self.java = JavaBridge(connect=bridge_connect, notify=notify,
                               reconnect_seconds=bridge_reconnect)
        if bridge is not None:
            self.java.socket = bridge
        self.snapshot_seen = False

    @property
    def bridge(self):
        return self.java.socket

    @property
    def bridge_error(self):
        return self.java.error

    @property
    def streams(self):
        return self.capture.streams

    def command(self, tab_id, title, identity, capture_stream_key):
        return self.capture.command(tab_id, title, identity, capture_stream_key)

    def start(self, message):
        return self.capture.start(message)

    def write_pcm(self, message):
        return self.capture.write_pcm(message)

    def stop(self, message):
        return self.capture.stop(message)

    def reap_failed_streams(self):
        return self.capture.reap_failed_streams()

    def close(self):
        self.capture.close()
        self.java.close()

    def handle_capture(self, message):
        request_id = message.get("requestId") if isinstance(message, dict) else None
        operation = {
            "capture-start": "start",
            "capture-pcm": "pcm",
            "capture-stop": "stop"
        }.get(message.get("type") if isinstance(message, dict) else None, "unknown")
        try:
            if operation == "unknown":
                raise RuntimeError("Unsupported Chrome tab capture message.")
            require_uuid(request_id, "native request ID")
            if operation == "start":
                self.start(message)
            elif operation == "pcm":
                self.write_pcm(message)
            else:
                self.stop(message)
            return {
                "type": "capture-response",
                "requestId": request_id,
                "operation": operation,
                "ok": True,
                "error": "",
                "bridgeError": self.bridge_error
            }
        except Exception as error:
            return {
                "type": "capture-response",
                "requestId": request_id if isinstance(request_id, str) else "",
                "operation": operation,
                "ok": False,
                "error": str(error),
                "bridgeError": self.bridge_error
            }

    def forward_envelope(self, message):
        validate_bridge_envelope(message, self.snapshot_seen)
        for tab in message["tabs"]:
            tab["title"] = safe_bridge_title(tab["title"])
        payload = json.dumps(message, separators=(",", ":"), ensure_ascii=False).encode("utf-8") + b"\n"
        if len(payload) > MAX_BRIDGE_LINE:
            raise RuntimeError("The browser bridge envelope is too large.")
        snapshot = message["type"] == "snapshot"
        if snapshot:
            self.snapshot_seen = True
        self.java.forward(payload, snapshot)
        return True

    def handle_native_message(self, message):
        if isinstance(message, dict) and message.get("type") in BRIDGE_TYPES:
            self.forward_envelope(message)
            return None
        if not self.snapshot_seen:
            raise RuntimeError("A browser snapshot must be sent before native capture messages.")
        return self.handle_capture(message)

    def handle_java_line(self, line):
        if not self.java.connected:
            raise RuntimeError("The Java browser socket is unavailable.")
        if not self.snapshot_seen:
            raise RuntimeError("A browser snapshot must be sent before Java commands.")
        if len(line) > MAX_BRIDGE_LINE:
            raise RuntimeError("The Java browser message is too large.")
        try:
            message = json.loads(line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RuntimeError("The Java browser message is malformed JSON.") from error
        return validate_java_command(message)



class NativeMessageDecoder:
    def __init__(self):
        self.buffer = bytearray()
        self.expected_size = None

    def feed(self, data):
        self.buffer.extend(data)
        messages = []
        while True:
            if self.expected_size is None:
                if len(self.buffer) < 4:
                    break
                self.expected_size = struct.unpack("<I", self.buffer[:4])[0]
                del self.buffer[:4]
                if self.expected_size > MAX_MESSAGE:
                    raise ValueError("Native Messaging frame is too large.")
            if len(self.buffer) < self.expected_size:
                break
            payload = bytes(self.buffer[:self.expected_size])
            del self.buffer[:self.expected_size]
            self.expected_size = None
            messages.append(json.loads(payload.decode("utf-8")))
        return messages


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
    payload = json.dumps(message, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    if len(payload) > MAX_MESSAGE:
        raise ValueError("Native Messaging frame is too large.")
    stream.write(struct.pack("<I", len(payload)))
    stream.write(payload)
    stream.flush()


def java_socket_path():
    runtime_dir = os.environ.get("XDG_RUNTIME_DIR")
    if not runtime_dir:
        runtime_dir = f"/run/user/{os.getuid()}"
    if not os.path.isabs(runtime_dir):
        raise RuntimeError("XDG_RUNTIME_DIR must be an absolute path.")
    return os.path.join(runtime_dir, "audio-matrix", "browser.sock")


def connect_java_socket():
    path = java_socket_path()
    try:
        info = os.lstat(path)
    except OSError as error:
        raise RuntimeError(f"The Audio Matrix browser socket is unavailable: {path}") from error
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise RuntimeError("The Audio Matrix browser socket is not an owner-only socket.")
    bridge = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        bridge.connect(path)
        if hasattr(socket, "SO_PEERCRED"):
            credentials = bridge.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i"))
            _pid, peer_uid, _gid = struct.unpack("3i", credentials)
            if peer_uid != os.getuid():
                raise RuntimeError("The Audio Matrix browser socket owner does not match this user.")
        return bridge
    except Exception:
        bridge.close()
        raise


def _write_capture_error(stream, failure):
    browser, instance_id, profile_id, session_token = failure["identity"]
    write_message(stream, {
        "type": "capture-error",
        "browser": browser,
        "instanceId": instance_id,
        "profileId": profile_id,
        "sessionToken": session_token,
        "captureStreamKey": failure["captureStreamKey"],
        "tabId": failure["tabId"],
        "error": failure["error"]
    })


def _register_bridge(selector, host, registered):
    """Keeps the selector in step with the Java socket of the host."""
    socket = host.bridge
    fd = socket.fileno() if socket is not None else None
    if fd == registered:
        return registered
    if registered is not None:
        try:
            selector.unregister(registered)
        except (KeyError, ValueError, OSError):
            pass
    if fd is None:
        return None
    try:
        selector.register(fd, selectors.EVENT_READ, "java")
    except (KeyError, ValueError, OSError):
        return None
    return fd


def _read_java_commands(host, buffer):
    """Reads the pending Java commands and returns them for the extension."""
    socket = host.bridge
    if socket is None:
        return []
    try:
        data = socket.recv(65536)
    except OSError as error:
        host.java.disconnect(f"The Audio Matrix browser socket failed: {error}")
        return []
    if not data:
        host.java.disconnect("The Audio Matrix browser socket disconnected.")
        return []
    buffer.extend(data)
    if len(buffer) > MAX_BRIDGE_LINE and b"\n" not in buffer:
        raise RuntimeError("The Java browser message is too large.")
    commands = []
    while b"\n" in buffer:
        line, _, remainder = buffer.partition(b"\n")
        buffer[:] = remainder
        if not line:
            raise RuntimeError("The Java browser socket sent an empty message.")
        commands.append(host.handle_java_line(bytes(line)))
    return commands


def main():
    host = Host(notify=lambda message: write_message(sys.stdout.buffer, message))
    selector = selectors.DefaultSelector()
    decoder = NativeMessageDecoder()
    bridge_buffer = bytearray()
    input_fd = sys.stdin.fileno()
    registered_bridge = None
    try:
        selector.register(input_fd, selectors.EVENT_READ, "native")
        write_message(sys.stdout.buffer, {"type": "bridge-ready"})
        host.java.connect_now()
        registered_bridge = _register_bridge(selector, host, registered_bridge)
        running = True
        while running:
            for key, _events in selector.select(timeout=0.1):
                if key.data == "native":
                    data = os.read(input_fd, 65536)
                    if not data:
                        running = False
                        break
                    for message in decoder.feed(data):
                        try:
                            reply = host.handle_native_message(message)
                        except Exception as error:
                            if isinstance(message, dict) and message.get("type") in BRIDGE_TYPES:
                                raise RuntimeError(f"The browser bridge envelope was rejected: {error}") from error
                            request_id = message.get("requestId", "") if isinstance(message, dict) else ""
                            reply = {
                                "type": "capture-response",
                                "requestId": request_id if isinstance(request_id, str) else "",
                                "operation": "unknown",
                                "ok": False,
                                "error": str(error),
                                "bridgeError": host.bridge_error
                            }
                        if reply:
                            write_message(sys.stdout.buffer, reply)
                else:
                    for command in _read_java_commands(host, bridge_buffer):
                        write_message(sys.stdout.buffer, command)
            host.java.connect_now()
            registered_bridge = _register_bridge(selector, host, registered_bridge)
            for failure in host.reap_failed_streams():
                _write_capture_error(sys.stdout.buffer, failure)
    except Exception as error:
        try:
            write_message(sys.stdout.buffer, {"type": "bridge-error", "message": str(error)})
        except Exception:
            pass
        return 1
    finally:
        host.close()
        selector.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
