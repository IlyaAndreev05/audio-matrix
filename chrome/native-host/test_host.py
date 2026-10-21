import base64
import importlib.util
import io
import json
import os
import pathlib
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

MODULE_PATH = pathlib.Path(__file__).with_name("audio-matrix-tab-capture.py")
SPEC = importlib.util.spec_from_file_location("audio_matrix_tab_capture", MODULE_PATH)
HOST_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST_MODULE)

INSTANCE_ID = "00000000-0000-4000-8000-000000000001"
PROFILE_ID = "00000000-0000-4000-8000-000000000002"
SESSION_ID = "00000000-0000-4000-8000-000000000003"
SESSION_ID_2 = "00000000-0000-4000-8000-000000000004"
STREAM_KEY = "00000000-0000-4000-8000-000000000005"
STREAM_KEY_2 = "00000000-0000-4000-8000-000000000006"


def identity(session=SESSION_ID, stream=STREAM_KEY):
    return {
        "browser": "chrome",
        "instanceId": INSTANCE_ID,
        "profileId": PROFILE_ID,
        "sessionToken": session,
        "captureStreamKey": stream
    }


def tab_entry(tab_id=17, session=SESSION_ID, captured=False, stream=STREAM_KEY):
    return {
        "sessionToken": session,
        "tabId": tab_id,
        "title": "Selected tab",
        "audible": True,
        "muted": False,
        "volumeCapability": "captured" if captured else "none",
        "captureStreamKey": stream if captured else ""
    }


def envelope(kind, tabs=None, **values):
    result = {
        "version": 1,
        "type": kind,
        "requestId": "00000000-0000-4000-8000-000000000007",
        "browser": "chrome",
        "instanceId": INSTANCE_ID,
        "profileId": PROFILE_ID,
        "tabs": [tab_entry()] if tabs is None else tabs
    }
    result.update(values)
    return result


def native_capture(kind, request=8, session=SESSION_ID, stream=STREAM_KEY, tab_id=17, **values):
    return {
        **identity(session, stream),
        "type": kind,
        "requestId": f"00000000-0000-4000-8000-{request:012d}",
        "tabId": tab_id,
        **values
    }


class FakeInput:
    def __init__(self):
        self.frames = []
        self.closed = False

    def write(self, frame):
        if self.closed:
            raise BrokenPipeError("closed")
        data = bytes(frame)
        self.frames.append(data)
        return len(data)

    def flush(self):
        return None

    def close(self):
        self.closed = True


class FakeProcess:
    def __init__(self, command, **kwargs):
        self.command = command
        self.kwargs = kwargs
        self.stdin = FakeInput()
        self.return_code = None
        self.waited = False

    def poll(self):
        return self.return_code

    def wait(self, timeout=None):
        self.waited = True
        if self.return_code is None:
            self.return_code = 0
        return self.return_code

    def terminate(self):
        self.return_code = 0

    def kill(self):
        self.return_code = -9


class FakeBridge:
    def __init__(self):
        self.lines = []

    def sendall(self, payload):
        self.lines.append(bytes(payload))


class HostTest(unittest.TestCase):
    def make_host(self, ready=True, startup_timeout=0.2, bridge=None):
        processes = []

        def fake_popen(command, **kwargs):
            process = FakeProcess(command, **kwargs)
            processes.append(process)
            return process

        checker = ready if callable(ready) else lambda _stream: ready
        host = HOST_MODULE.Host(
            popen=fake_popen,
            pwcat="/usr/bin/pw-cat",
            pipewire_ready=checker,
            bridge=bridge,
            startup_timeout=startup_timeout
        )
        return host, processes

    def establish_snapshot(self, host):
        host.handle_native_message(envelope("snapshot", []))

    def test_snapshot_is_first_and_events_are_forwarded_as_ordered_jsonl(self):
        bridge = FakeBridge()
        host, _ = self.make_host(bridge=bridge)
        with self.assertRaisesRegex(RuntimeError, "first browser message"):
            host.handle_native_message(envelope("tab_created"))

        initial = envelope("snapshot", [tab_entry()])
        created = envelope("tab_created", [tab_entry(18, SESSION_ID_2)])
        audio = envelope("tab_audio", [tab_entry()])
        title_update = envelope("tab_updated", [dict(tab_entry(), title="Research https://private.invalid/path")])
        ack = envelope("ack", [], requestId="java-request-14",
                       status="error", error="The tab no longer exists.")
        for message in (initial, created, audio, title_update, ack):
            host.handle_native_message(message)

        forwarded = [json.loads(line) for line in bridge.lines]
        self.assertEqual(["snapshot", "tab_created", "tab_audio", "tab_updated", "ack"],
                         [message["type"] for message in forwarded])
        self.assertEqual(set(HOST_MODULE.ENVELOPE_FIELDS), set(forwarded[0]))
        self.assertEqual(set(HOST_MODULE.TAB_FIELDS), set(forwarded[0]["tabs"][0]))
        self.assertNotIn("url", json.dumps(forwarded).lower())
        self.assertEqual("Research", forwarded[3]["tabs"][0]["title"])

        forbidden = dict(tab_entry())
        forbidden["url"] = "https://private.invalid/"
        with self.assertRaisesRegex(RuntimeError, "unexpected fields"):
            host.handle_native_message(envelope("tab_updated", [forbidden]))

    def test_pipewire_stream_is_bound_to_stable_tab_identity_and_virtual_sink(self):
        host, _ = self.make_host()
        command = host.command(
            17, "YouTube · Music",
            ("chrome", INSTANCE_ID, PROFILE_ID, SESSION_ID),
            STREAM_KEY
        )
        self.assertIn("audiomatrix_system", command)
        self.assertIn("--target", command)
        properties = json.loads(next(value.split("=", 1)[1] for value in command if value.startswith("--properties=")))
        self.assertEqual(HOST_MODULE.APP_ID, properties["application.id"])
        self.assertEqual("YouTube · Music", properties["media.name"])
        self.assertEqual(HOST_MODULE.SINK_NAME, properties["target.object"])
        self.assertTrue(properties["node.dont-fallback"])
        self.assertTrue(properties["node.dont-reconnect"])
        self.assertEqual("chrome", properties["audio-matrix.browser"])
        self.assertEqual(INSTANCE_ID, properties["audio-matrix.instance-id"])
        self.assertEqual(PROFILE_ID, properties["audio-matrix.profile-id"])
        self.assertEqual(SESSION_ID, properties["audio-matrix.session-token"])
        self.assertEqual(STREAM_KEY, properties["audio-matrix.capture-stream-key"])
        self.assertEqual(HOST_MODULE.pipewire_node_name(STREAM_KEY), properties["node.name"])

    def test_pipewire_start_is_not_ready_until_exact_stream_is_observed(self):
        host, processes = self.make_host(ready=False, startup_timeout=0.01)
        self.establish_snapshot(host)
        response = host.handle_native_message(native_capture("capture-start", title="Music"))
        self.assertFalse(response["ok"])
        self.assertIn("ready Chrome tab stream", response["error"])
        self.assertEqual({}, host.streams)
        self.assertTrue(processes[0].stdin.closed)
        self.assertTrue(processes[0].waited)

    def test_start_and_stop_responses_wait_for_pipewire_process_lifecycle(self):
        host, processes = self.make_host()
        self.establish_snapshot(host)
        started = host.handle_native_message(native_capture("capture-start", title="Music"))
        self.assertEqual("start", started["operation"])
        self.assertTrue(started["ok"])
        self.assertIn(("chrome", INSTANCE_ID, PROFILE_ID, SESSION_ID), host.streams)
        self.assertFalse(processes[0].waited)

        stopped = host.handle_native_message(native_capture("capture-stop", request=9))
        self.assertEqual("stop", stopped["operation"])
        self.assertTrue(stopped["ok"])
        self.assertTrue(processes[0].stdin.closed)
        self.assertTrue(processes[0].waited)
        self.assertEqual({}, host.streams)

    def test_pcm_ack_is_per_chunk_and_duplicate_stream_or_sequence_is_rejected_locally(self):
        host, processes = self.make_host()
        self.establish_snapshot(host)
        started = host.handle_native_message(native_capture("capture-start", title="First"))
        self.assertTrue(started["ok"])
        duplicate = host.handle_native_message(native_capture("capture-start", request=9, title="Duplicate"))
        self.assertFalse(duplicate["ok"])
        second = host.handle_native_message(native_capture(
            "capture-start", request=10, session=SESSION_ID_2, stream=STREAM_KEY_2, tab_id=18, title="Second"
        ))
        self.assertTrue(second["ok"])

        frame = bytes(HOST_MODULE.CHUNK_BYTES)
        pcm = native_capture("capture-pcm", request=11, sequence=0,
                             data=base64.b64encode(frame).decode("ascii"))
        ack = host.handle_native_message(pcm)
        self.assertEqual({"type": "capture-response", "requestId": pcm["requestId"],
                          "operation": "pcm", "ok": True, "error": ""}, ack)
        self.assertEqual(frame, processes[0].stdin.frames[0])
        self.assertEqual([], processes[1].stdin.frames)

        duplicate_pcm = dict(pcm, requestId="00000000-0000-4000-8000-000000000012")
        failed = host.handle_native_message(duplicate_pcm)
        self.assertFalse(failed["ok"])
        self.assertNotIn(("chrome", INSTANCE_ID, PROFILE_ID, SESSION_ID), host.streams)
        self.assertIn(("chrome", INSTANCE_ID, PROFILE_ID, SESSION_ID_2), host.streams)
        self.assertTrue(processes[0].stdin.closed)
        self.assertFalse(processes[1].stdin.closed)
        host.close()

    def test_pipewire_failure_tears_down_only_the_matching_tab_stream(self):
        host, processes = self.make_host()
        self.establish_snapshot(host)
        self.assertTrue(host.handle_native_message(native_capture("capture-start", title="First"))["ok"])
        self.assertTrue(host.handle_native_message(native_capture(
            "capture-start", request=9, session=SESSION_ID_2, stream=STREAM_KEY_2,
            tab_id=18, title="Second"
        ))["ok"])
        processes[0].return_code = 1

        failures = host.reap_failed_streams()

        self.assertEqual([17], [failure["tabId"] for failure in failures])
        self.assertEqual(("chrome", INSTANCE_ID, PROFILE_ID, SESSION_ID),
                         failures[0]["identity"])
        self.assertNotIn(("chrome", INSTANCE_ID, PROFILE_ID, SESSION_ID), host.streams)
        self.assertIn(("chrome", INSTANCE_ID, PROFILE_ID, SESSION_ID_2), host.streams)
        self.assertTrue(processes[0].stdin.closed)
        self.assertFalse(processes[1].stdin.closed)
        host.close()

    def test_java_command_contract_and_native_message_framing(self):
        bridge = FakeBridge()
        host, _ = self.make_host(bridge=bridge)
        self.establish_snapshot(host)
        command = {
            "version": 1,
            "type": "command",
            "requestId": "java-request-13",
            "browser": "chrome",
            "instanceId": INSTANCE_ID,
            "profileId": PROFILE_ID,
            "tabs": [],
            "sessionToken": SESSION_ID,
            "command": "mute",
            "muted": True
        }
        self.assertEqual(command, host.handle_java_line(json.dumps(command).encode("utf-8")))
        with self.assertRaisesRegex(RuntimeError, "unexpected fields"):
            host.handle_java_line(json.dumps(dict(command, url="https://private.invalid/")).encode("utf-8"))

        output = io.BytesIO()
        HOST_MODULE.write_message(output, command)
        self.assertEqual(struct.unpack("<I", output.getvalue()[:4])[0], len(output.getvalue()) - 4)
        output.seek(0)
        self.assertEqual(command, HOST_MODULE.read_message(output))

        decoder = HOST_MODULE.NativeMessageDecoder()
        framed = output.getvalue()
        self.assertEqual([], decoder.feed(framed[:3]))
        self.assertEqual([command], decoder.feed(framed[3:]))

    def test_java_bridge_uses_only_the_owner_only_unix_runtime_socket(self):
        with tempfile.TemporaryDirectory() as runtime_dir:
            directory = pathlib.Path(runtime_dir) / "audio-matrix"
            directory.mkdir()
            path = directory / "browser.sock"
            server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            server.bind(str(path))
            os.chmod(path, 0o600)
            server.listen(1)
            server.settimeout(2)
            accepted = []

            def accept_peer():
                peer, _ = server.accept()
                accepted.append(peer)

            thread = threading.Thread(target=accept_peer)
            thread.start()
            try:
                with patch.dict(os.environ, {"XDG_RUNTIME_DIR": runtime_dir}):
                    self.assertEqual(str(path), HOST_MODULE.java_socket_path())
                    client = HOST_MODULE.connect_java_socket()
                thread.join(timeout=2)
                self.assertEqual(socket.AF_UNIX, client.family)
                self.assertEqual(1, len(accepted))
                client.close()
                accepted[0].close()
            finally:
                server.close()
                thread.join(timeout=2)

        with patch.dict(os.environ, {}, clear=True), patch.object(HOST_MODULE.os, "getuid", return_value=4321):
            self.assertEqual("/run/user/4321/audio-matrix/browser.sock", HOST_MODULE.java_socket_path())


NODE_BEHAVIORAL_HARNESS = r"""
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const workerSource = fs.readFileSync(process.argv[1], "utf8");
const offscreenSource = fs.readFileSync(process.argv[2], "utf8");
const workletSource = fs.readFileSync(process.argv[3], "utf8");
let serial = 1;
const crypto = { randomUUID: () => `00000000-0000-4000-8000-${String(serial++).padStart(12, "0")}` };
const extensionId = "audio-matrix-test";

class Hook {
  constructor() { this.listeners = []; }
  addListener(listener) { this.listeners.push(listener); }
  fire(...args) { for (const listener of [...this.listeners]) listener(...args); }
}

class NativePort {
  constructor(browser) {
    this.browser = browser;
    this.sent = [];
    this.onMessage = new Hook();
    this.onDisconnect = new Hook();
    this.failNextStart = false;
  }
  postMessage(message) {
    this.sent.push(JSON.parse(JSON.stringify(message)));
    if (!String(message.type).startsWith("capture-")) return;
    const operation = message.type.slice("capture-".length);
    this.browser.timeline.push(`host-request-${operation}`);
    const delay = operation === "start" ? 12 : operation === "stop" ? 24 : 3;
    setTimeout(() => {
      const failed = operation === "start" && this.failNextStart;
      this.failNextStart = false;
      if (!failed && operation === "start") this.browser.timeline.push("host-ready");
      this.onMessage.fire({
        type: "capture-response",
        requestId: message.requestId,
        operation,
        ok: !failed,
        error: failed ? "Fake PipeWire did not become ready." : ""
      });
    }, delay);
  }
  emit(message) { this.onMessage.fire(message); }
  disconnect() { this.onDisconnect.fire(); }
}

function clone(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

function createHarness(store) {
  const browser = {
    store: store || { local: {}, session: {} },
    tabs: new Map([
      [10, { id: 10, title: "First tab", audible: true, mutedInfo: { muted: false }, windowId: 1, active: true,
        url: "https://must-not-be-forwarded.invalid/first" }],
      [20, { id: 20, title: "Second tab", audible: false, mutedInfo: { muted: false }, windowId: 1, active: false,
        url: "https://must-not-be-forwarded.invalid/second" }]
    ]),
    ports: [],
    messageListeners: [],
    timeline: [],
    tracks: [],
    contexts: [],
    nodes: [],
    documentExists: false,
    failGetUserMedia: false,
    events: {
      created: new Hook(), updated: new Hook(), removed: new Hook(),
      activated: new Hook(), focused: new Hook()
    }
  };

  function dispatch(message, sender) {
    return new Promise((resolve, reject) => {
      let responded = false;
      let asynchronous = false;
      const sendResponse = (value) => {
        if (responded) return;
        responded = true;
        resolve(value);
      };
      try {
        for (const listener of [...browser.messageListeners]) {
          if (listener(message, sender, sendResponse) === true) asynchronous = true;
        }
        if (!asynchronous && !responded) resolve(undefined);
      } catch (error) {
        reject(error);
      }
    });
  }

  function activateTab(tabId) {
    const target = browser.tabs.get(tabId);
    if (!target) throw new Error("fake tab not found");
    for (const tab of browser.tabs.values()) tab.active = tab.windowId === target.windowId && tab.id === tabId;
  }

  function makeChrome(source) {
    const sender = {
      id: extensionId,
      url: source === "popup" ? `chrome-extension://${extensionId}/popup.html` :
        source === "offscreen" ? `chrome-extension://${extensionId}/offscreen.html` :
          `chrome-extension://${extensionId}/service-worker.js`
    };
    const storageArea = (area) => ({
      async get(key) {
        if (typeof key === "string") return { [key]: clone(browser.store[area][key]) };
        if (Array.isArray(key)) return Object.fromEntries(key.map((item) => [item, clone(browser.store[area][item])]));
        return clone(browser.store[area]);
      },
      async set(values) { Object.assign(browser.store[area], clone(values)); }
    });
    return {
      runtime: {
        id: extensionId,
        lastError: null,
        getURL: (path) => `chrome-extension://${extensionId}/${path}`,
        connectNative: () => {
          const port = new NativePort(browser);
          browser.ports.push(port);
          return port;
        },
        onMessage: { addListener: (listener) => browser.messageListeners.push(listener) },
        sendMessage: (message) => dispatch(message, sender)
      },
      storage: { local: storageArea("local"), session: storageArea("session") },
      tabs: {
        onCreated: browser.events.created,
        onUpdated: browser.events.updated,
        onRemoved: browser.events.removed,
        onActivated: browser.events.activated,
        async query(query = {}) {
          let tabs = [...browser.tabs.values()];
          if (Number.isInteger(query.windowId)) tabs = tabs.filter((tab) => tab.windowId === query.windowId);
          if (query.active) tabs = tabs.filter((tab) => tab.active);
          return tabs.map(clone);
        },
        async get(tabId) {
          const tab = browser.tabs.get(tabId);
          if (!tab) throw new Error("No such tab.");
          return clone(tab);
        },
        async update(tabId, values) {
          const tab = browser.tabs.get(tabId);
          if (!tab) throw new Error("No such tab.");
          if (Object.hasOwn(values, "muted")) tab.mutedInfo.muted = values.muted;
          if (values.active) activateTab(tabId);
          Object.assign(tab, values);
          return clone(tab);
        }
      },
      windows: {
        onFocusChanged: browser.events.focused,
        async update(windowId, values) { return { id: windowId, ...values }; }
      },
      offscreen: {
        async hasDocument() { return browser.documentExists; },
        async createDocument() { browser.documentExists = true; }
      }
    };
  }

  class FakeTrack {
    constructor() { this.readyState = "live"; this.listeners = new Map(); }
    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, new Set());
      this.listeners.get(type).add(listener);
    }
    removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
    stop() {
      this.readyState = "ended";
      for (const listener of this.listeners.get("ended") || []) listener();
    }
  }

  class FakeAudioContext {
    constructor(options) {
      this.sampleRate = options.sampleRate;
      this.state = "suspended";
      this.destination = {};
      this.audioWorklet = { addModule: async () => {} };
      browser.contexts.push(this);
    }
    createMediaStreamSource() { return { connect() {}, disconnect() {} }; }
    async resume() { this.state = "running"; }
    async close() { this.state = "closed"; }
  }

  class FakeNodePort {
    constructor() { this.onmessage = null; }
    emit(message) { this.onmessage?.({ data: message }); }
    postMessage(message) {
      if (message.type === "start")
        setTimeout(() => this.emit({ type: "started" }), 6);
      if (message.type === "stop")
        setTimeout(() => this.emit({ type: "stopped" }), 6);
    }
  }

  class FakeAudioWorkletNode {
    constructor() {
      this.port = new FakeNodePort();
      browser.nodes.push(this);
      this.onprocessorerror = null;
      setTimeout(() => {
        browser.timeline.push("worklet-ready");
        this.port.emit({ type: "ready" });
      }, 8);
    }
    connect() {}
    disconnect() {}
  }

  const navigator = {
    mediaDevices: {
      async getUserMedia() {
        browser.timeline.push("offscreen-open");
        if (browser.failGetUserMedia) throw new Error("Fake tab capture permission failed.");
        const track = new FakeTrack();
        const stream = { getAudioTracks: () => [track], getTracks: () => [track] };
        browser.tracks.push(track);
        return stream;
      }
    }
  };
  vm.runInNewContext(offscreenSource, {
    chrome: makeChrome("offscreen"), navigator, AudioContext: FakeAudioContext,
    AudioWorkletNode: FakeAudioWorkletNode, Float32Array, ArrayBuffer, Uint8Array, btoa,
    setTimeout, clearTimeout, Promise, Map, Set, Number, Object, String, Error
  });
  vm.runInNewContext(workerSource, {
    chrome: makeChrome("worker"), crypto, setTimeout, clearTimeout, Promise, Map, Set,
    Number, Object, Array, String, Boolean, Error, console
  });
  setTimeout(() => browser.ports[0].emit({ type: "bridge-ready" }), 0);
  return {
    browser,
    dispatch,
    activateTab,
    port: browser.ports[0],
    popupSender: { id: extensionId, url: `chrome-extension://${extensionId}/popup.html` }
  };
}

async function waitFor(predicate, label) {
  const deadline = Date.now() + 2000;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function message(harness, value, sender = harness.popupSender) {
  return harness.dispatch(value, sender);
}

async function waitForSnapshot(harness) {
  await waitFor(() => harness.port.sent.some((entry) => entry.type === "snapshot"), "snapshot");
  return harness.port.sent.find((entry) => entry.type === "snapshot");
}

async function startTab(harness, tabId) {
  harness.activateTab(tabId);
  return message(harness, { action: "start", tabId, streamId: `stream-${tabId}` });
}

async function runExtensionStateTests() {
  const store = { local: {}, session: {} };
  const first = createHarness(store);
  const firstSnapshot = await waitForSnapshot(first);
  assert.equal(first.port.sent[0].type, "snapshot");
  assert.equal(firstSnapshot.tabs.length, 2);
  assert.deepEqual(Object.keys(firstSnapshot.tabs[0]).sort(),
    ["sessionToken", "tabId", "title", "audible", "muted", "volumeCapability", "captureStreamKey"].sort());
  assert.equal(JSON.stringify(firstSnapshot).includes("url"), false);
  const originalSession = firstSnapshot.tabs.find((tab) => tab.tabId === 10).sessionToken;

  const restarted = createHarness(store);
  const snapshot = await waitForSnapshot(restarted);
  assert.equal(snapshot.instanceId, firstSnapshot.instanceId);
  assert.equal(snapshot.profileId, firstSnapshot.profileId);
  assert.equal(snapshot.tabs.find((tab) => tab.tabId === 10).sessionToken, originalSession);
  const freshBrowserSession = createHarness({ local: store.local, session: {} });
  const freshSnapshot = await waitForSnapshot(freshBrowserSession);
  assert.equal(freshSnapshot.instanceId, firstSnapshot.instanceId);
  assert.equal(freshSnapshot.profileId, firstSnapshot.profileId);
  assert.notEqual(freshSnapshot.tabs.find((tab) => tab.tabId === 10).sessionToken, originalSession);

  const commandId = crypto.randomUUID();
  restarted.port.emit({
    version: 1, type: "command", requestId: commandId, browser: "chrome",
    instanceId: snapshot.instanceId, profileId: snapshot.profileId, tabs: [],
    sessionToken: originalSession, command: "mute", muted: true
  });
  await waitFor(() => restarted.port.sent.some((entry) => entry.type === "ack" && entry.requestId === commandId),
    "mute command acknowledgement");
  const ack = restarted.port.sent.find((entry) => entry.type === "ack" && entry.requestId === commandId);
  assert.equal(ack.status, "ok");
  assert.equal(ack.error, "");
  assert.deepEqual(ack.tabs, []);
  assert.equal(restarted.browser.tabs.get(10).mutedInfo.muted, true);

  const created = { id: 30, title: "Third tab https://not-forwarded.invalid/", audible: false, mutedInfo: { muted: false }, windowId: 1, active: false };
  restarted.browser.tabs.set(30, created);
  restarted.browser.events.created.fire({ ...created, url: "https://not-forwarded.invalid/" });
  await waitFor(() => restarted.port.sent.some((entry) => entry.type === "tab_created"), "tab-created event");
  const titled = { ...restarted.browser.tabs.get(10), title: "First tab title" };
  restarted.browser.tabs.set(10, titled);
  restarted.browser.events.updated.fire(10, { title: "First tab title" }, { ...titled, url: "https://not-forwarded.invalid/" });
  await waitFor(() => restarted.port.sent.some((entry) => entry.type === "tab_updated"), "tab-updated event");
  const changed = { ...restarted.browser.tabs.get(10), audible: true };
  restarted.browser.tabs.set(10, changed);
  restarted.browser.events.updated.fire(10, { audible: true }, { ...changed, url: "https://not-forwarded.invalid/" });
  await waitFor(() => restarted.port.sent.some((entry) => entry.type === "tab_audio"), "tab-audio event");
  restarted.browser.events.activated.fire({ tabId: 20, windowId: 1 });
  await waitFor(() => restarted.port.sent.some((entry) => entry.type === "window_updated"), "window event");
  restarted.browser.tabs.delete(30);
  restarted.browser.events.removed.fire(30);
  await waitFor(() => restarted.port.sent.some((entry) => entry.type === "tab_removed"), "tab-removed event");
  const events = restarted.port.sent.filter((entry) =>
    ["tab_created", "tab_updated", "tab_audio", "window_updated", "tab_removed"].includes(entry.type));
  assert.deepEqual(events.map((entry) => entry.type),
    ["tab_created", "tab_updated", "tab_audio", "window_updated", "tab_removed"]);
  assert.equal(JSON.stringify(events).includes("url"), false);

  const failedHost = restarted.port;
  failedHost.failNextStart = true;
  const failedStart = await startTab(restarted, 10);
  assert.equal(failedStart.ok, false);
  assert.equal(restarted.browser.tracks.length, 0);
  const afterHostFailure = await message(restarted, { action: "status" });
  assert.deepEqual(afterHostFailure.captures, []);
  restarted.browser.failGetUserMedia = true;
  const failedMedia = await startTab(restarted, 10);
  restarted.browser.failGetUserMedia = false;
  assert.equal(failedMedia.ok, false);
  assert.equal(restarted.browser.tracks.length, 0);
  const afterMediaFailure = await message(restarted, { action: "status" });
  assert.deepEqual(afterMediaFailure.captures, []);

  const readinessStart = restarted.browser.timeline.length;
  const started = await startTab(restarted, 10);
  assert.equal(started.ok, true);
  const startingRecord = restarted.browser.store.session.audioMatrixTabCaptures["10"];
  assert.ok(startingRecord);
  const startupSteps = restarted.browser.timeline.slice(readinessStart);
  assert.ok(startupSteps.indexOf("host-ready") < startupSteps.indexOf("offscreen-open"));
  assert.ok(startupSteps.indexOf("offscreen-open") < startupSteps.indexOf("worklet-ready"));
  const captureStatus = await message(restarted, { action: "status" });
  assert.deepEqual(captureStatus.captures, [{ tabId: 10, status: "active" }]);

  const firstTrack = restarted.browser.tracks[0];
  const firstContext = restarted.browser.contexts[0];
  const stopStartedAt = Date.now();
  const stopped = await message(restarted, { action: "stop", tabId: 10 });
  assert.equal(stopped.ok, true);
  assert.equal(stopped.playbackRecovered, true);
  assert.equal(stopped.trackState, "ended");
  assert.equal(stopped.contextState, "closed");
  assert.equal(firstTrack.readyState, "ended");
  assert.equal(firstContext.state, "closed");
  assert.ok(Date.now() - stopStartedAt >= 20, "stop waited for the native host acknowledgement");

  const firstCapture = await startTab(restarted, 10);
  assert.equal(firstCapture.ok, true);
  const recordOne = restarted.browser.store.session.audioMatrixTabCaptures["10"];
  assert.equal(recordOne.captureStreamKey, startingRecord.captureStreamKey);
  const secondCapture = await startTab(restarted, 20);
  assert.equal(secondCapture.ok, true);
  const recordTwo = restarted.browser.store.session.audioMatrixTabCaptures["20"];
  const firstTrackAfterStart = restarted.browser.tracks.find((track) => track.readyState === "live");
  const secondTrack = restarted.browser.tracks.filter((track) => track.readyState === "live")[1];
  const firstCaptureNode = restarted.browser.nodes.at(-2);
  firstCaptureNode.port.emit({ type: "failure", error: "The four-chunk PCM queue filled." });
  await waitFor(() => {
    const active = restarted.browser.store.session.audioMatrixTabCaptures;
    return !active["10"] && active["20"]?.status === "active";
  }, "localized capture failure teardown");
  assert.equal(firstTrackAfterStart.readyState, "ended");
  assert.equal(secondTrack.readyState, "live");
  const remaining = await message(restarted, { action: "status" });
  assert.deepEqual(remaining.captures, [{ tabId: 20, status: "active" }]);

  const stopCommandId = crypto.randomUUID();
  restarted.port.emit({
    version: 1, type: "command", requestId: stopCommandId, browser: "chrome",
    instanceId: snapshot.instanceId, profileId: snapshot.profileId, tabs: [],
    sessionToken: recordTwo.sessionToken, command: "stopCapture"
  });
  await waitFor(() => restarted.port.sent.some((entry) => entry.type === "ack" && entry.requestId === stopCommandId),
    "stop-capture command acknowledgement");
  const stopAck = restarted.port.sent.find((entry) => entry.type === "ack" && entry.requestId === stopCommandId);
  assert.equal(stopAck.status, "ok");
  assert.equal(secondTrack.readyState, "ended");
  assert.equal(restarted.browser.contexts.at(-1).state, "closed");
  assert.equal(restarted.browser.store.session.audioMatrixTabCaptures["20"], undefined);
  assert.ok(!restarted.port.sent.some((entry) => Object.hasOwn(entry, "url")));
}

function runWorkletQueueTest() {
  class Port {
    constructor() { this.onmessage = null; this.messages = []; }
    postMessage(message) { this.messages.push(message); }
  }
  class BaseProcessor {
    constructor() { this.port = new Port(); }
  }
  let Processor;
  vm.runInNewContext(workletSource, {
    AudioWorkletProcessor: BaseProcessor,
    sampleRate: 48000,
    registerProcessor: (_name, implementation) => { Processor = implementation; },
    Float32Array, Set, Error
  });
  const processor = new Processor();
  processor.port.onmessage({ data: { type: "start" } });
  const input = [[new Float32Array(128), new Float32Array(128)]];
  const output = [[new Float32Array(128), new Float32Array(128)]];
  let alive = true;
  for (let index = 0; index < 20 && alive; index++)
    alive = processor.process(input, output);
  const chunks = processor.port.messages.filter((message) => message.type === "pcm");
  const failures = processor.port.messages.filter((message) => message.type === "failure");
  assert.equal(chunks.length, 4);
  assert.ok(chunks.every((message) => message.data.byteLength === 512 * 2 * 4));
  assert.equal(failures.length, 1);
  assert.match(failures[0].error, /four unacknowledged 512-frame chunks/);
  assert.equal(processor.outstanding.size, 4);
  const acknowledged = new Processor();
  acknowledged.port.onmessage({ data: { type: "start" } });
  for (let index = 0; index < 4; index++) acknowledged.process(input, output);
  assert.equal(acknowledged.outstanding.size, 1);
  acknowledged.port.onmessage({ data: { type: "ack", sequence: 0 } });
  assert.equal(acknowledged.outstanding.size, 0);
  for (let index = 0; index < 4; index++) acknowledged.process(input, output);
  assert.equal(acknowledged.port.messages.filter((message) => message.type === "pcm").length, 2);
  assert.equal(acknowledged.port.messages.some((message) => message.type === "failure"), false);
  assert.equal(acknowledged.outstanding.size, 1);
}

(async () => {
  await runExtensionStateTests();
  runWorkletQueueTest();
  console.log("Behavioral Chrome bridge, offscreen recovery, and PCM queue assertions passed.");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
"""


@unittest.skipUnless(shutil.which("node"), "Node.js is required for Chrome extension behavioral tests.")
class ExtensionBehaviorTest(unittest.TestCase):
    def test_fake_browser_offscreen_and_worklet_state_transitions(self):
        root = MODULE_PATH.parents[1]
        worker = root / "tab-capture" / "service-worker.js"
        offscreen = root / "tab-capture" / "offscreen.js"
        worklet = root / "tab-capture" / "pcm-worklet.js"
        subprocess.run(
            ["node", "-e", NODE_BEHAVIORAL_HARNESS, str(worker), str(offscreen), str(worklet)],
            check=True,
            capture_output=True,
            text=True,
            timeout=20
        )


if __name__ == "__main__":
    unittest.main()
