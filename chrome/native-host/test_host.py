import base64
import importlib.util
import io
import json
import pathlib
import struct
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("audio-matrix-tab-capture.py")
SPEC = importlib.util.spec_from_file_location("audio_matrix_tab_capture", MODULE_PATH)
HOST_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST_MODULE)


class FakeInput:
    def __init__(self):
        self.frames = []
        self.closed = False

    def write(self, frame):
        self.frames.append(frame)
        return len(frame)

    def close(self):
        self.closed = True


class FakeProcess:
    def __init__(self, command, **kwargs):
        self.command = command
        self.stdin = FakeInput()
        self.return_code = None
        self.waited = False

    def poll(self):
        return self.return_code

    def wait(self, timeout=None):
        self.waited = True
        self.return_code = 0
        return 0

    def terminate(self):
        self.return_code = 0

    def kill(self):
        self.return_code = -9


class HostTest(unittest.TestCase):
    def test_pipewire_target_is_only_the_audio_matrix_virtual_sink(self):
        command = HOST_MODULE.Host(pwcat="/usr/bin/pw-cat").command(17, "YouTube · Music")
        self.assertIn("audiomatrix_system", command)
        self.assertIn("--target", command)
        properties = json.loads(next(value.split("=", 1)[1] for value in command if value.startswith("--properties=")))
        self.assertEqual(HOST_MODULE.APP_ID, properties["application.id"])
        self.assertEqual("Chrome · YouTube · Music", properties["media.name"])
        self.assertTrue(properties["node.dont-fallback"])

    def test_tab_title_is_bounded_and_control_characters_are_removed(self):
        command = HOST_MODULE.Host(pwcat="/usr/bin/pw-cat").command(17, "  Music\nPlayer  ")
        properties = json.loads(next(value.split("=", 1)[1] for value in command if value.startswith("--properties=")))
        self.assertEqual("Chrome · MusicPlayer", properties["media.name"])

    def test_pcm_frames_are_validated_and_written_only_to_the_requested_stream(self):
        processes = []

        def fake_popen(command, **kwargs):
            process = FakeProcess(command, **kwargs)
            processes.append(process)
            return process

        host = HOST_MODULE.Host(popen=fake_popen, pwcat="/usr/bin/pw-cat")
        self.assertEqual({"type": "ready", "tabId": 17}, host.handle({"type": "start", "tabId": 17}))
        frame = bytes(4096 * 2 * 4)
        host.handle({"type": "pcm", "tabId": 17, "data": base64.b64encode(frame).decode("ascii")})
        self.assertEqual(frame, processes[0].stdin.frames[0])
        self.assertEqual({"type": "stopped", "tabId": 17}, host.handle({"type": "stop", "tabId": 17}))
        self.assertTrue(processes[0].stdin.closed)
        self.assertTrue(processes[0].waited)

    def test_rejects_wrong_frame_layout_and_unknown_tabs(self):
        host = HOST_MODULE.Host(popen=FakeProcess, pwcat="/usr/bin/pw-cat")
        host.handle({"type": "start", "tabId": 4})
        with self.assertRaisesRegex(RuntimeError, "invalid size"):
            host.handle({"type": "pcm", "tabId": 4, "data": base64.b64encode(b"bad").decode("ascii")})
        with self.assertRaisesRegex(RuntimeError, "invalid"):
            host.handle({"type": "start", "tabId": True})
        host.close()

    def test_native_messaging_frames_round_trip(self):
        output = io.BytesIO()
        HOST_MODULE.write_message(output, {"type": "ready", "tabId": 3})
        output.seek(0)
        self.assertEqual({"type": "ready", "tabId": 3}, HOST_MODULE.read_message(output))
        self.assertEqual(struct.unpack("<I", output.getvalue()[:4])[0], len(output.getvalue()) - 4)


if __name__ == "__main__":
    unittest.main()
