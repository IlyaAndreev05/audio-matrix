import importlib.util
import json
from pathlib import Path
import unittest
import uuid

LAUNCHER = Path(__file__).with_name("audio-matrix-tab-control.py")
spec = importlib.util.spec_from_file_location("firefox_launcher", LAUNCHER)
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)
transport = launcher.load_transport()


class SocketStub:
    def __init__(self):
        self.messages = []

    def sendall(self, payload):
        self.messages.append(json.loads(payload))

    def close(self):
        pass


class FirefoxHostTest(unittest.TestCase):
    def setUp(self):
        self.socket = SocketStub()
        self.started = []
        self.host = transport.Host(bridge=self.socket, browser="firefox", popen=lambda *args, **kwargs: self.started.append(args))
        self.snapshot = {
            "version": 1, "type": "snapshot", "requestId": str(uuid.uuid4()), "browser": "firefox",
            "instanceId": str(uuid.uuid4()), "profileId": str(uuid.uuid4()),
            "tabs": [{"sessionToken": str(uuid.uuid4()), "tabId": 3, "title": "Музыка https://example.com/private",
                      "audible": True, "muted": False, "volumeCapability": "none", "captureStreamKey": ""}],
        }

    def tearDown(self):
        self.host.close()

    def command(self, action, **extra):
        return {key: value for key, value in self.snapshot.items() if key not in ("type", "tabs", "requestId")} | {
            "type": "command", "requestId": str(uuid.uuid4()), "tabs": [],
            "sessionToken": self.snapshot["tabs"][0]["sessionToken"], "command": action, **extra,
        }

    def test_forwards_only_real_url_free_metadata_and_accepts_mute(self):
        self.host.handle_native_message(self.snapshot)
        forwarded = self.socket.messages[0]
        self.assertEqual("firefox", forwarded["browser"])
        self.assertEqual("Музыка", forwarded["tabs"][0]["title"])
        self.assertEqual("none", forwarded["tabs"][0]["volumeCapability"])
        command = self.command("mute", muted=True)
        self.assertEqual(command, self.host.handle_java_line(json.dumps(command).encode()))
        self.assertEqual([], self.started)

    def test_rejects_capture_claims_and_capture_commands(self):
        captured = dict(self.snapshot["tabs"][0], volumeCapability="captured", captureStreamKey="not-a-firefox-capability")
        with self.assertRaises(RuntimeError):
            transport.validate_tab_entry(captured, "firefox")
        self.host.handle_native_message(self.snapshot)
        with self.assertRaises(RuntimeError):
            self.host.handle_native_message({"type": "capture-start", "browser": "firefox"})
        with self.assertRaises(RuntimeError):
            self.host.handle_java_line(json.dumps(self.command("stopCapture")).encode())
        self.assertEqual([], self.started)


if __name__ == "__main__":
    unittest.main()
