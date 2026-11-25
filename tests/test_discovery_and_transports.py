"""Unit tests for multi-interface discovery, RFCOMM framing, and extended gestures."""

import json
import struct
import sys
import unittest
from unittest.mock import patch

from cursorly.adapters.adb_streamer import ADBMultiTouchStreamer
from cursorly.adapters.bluetooth_server import (
    FRAME_BINARY_TOUCH,
    FRAME_JSON_CONTROL,
    decode_rfcomm_frames,
    encode_rfcomm_frame,
)
from cursorly.adapters.discovery import (
    InterfaceAddress,
    classify_interface,
    encode_discovery_announcement,
)
from cursorly.adapters.web_server import PACKET_FORMAT, WebTrackpadServer
from cursorly.engine.gestures import GestureAction, GestureEngine


class TestDiscoveryAndTransports(unittest.TestCase):
    def test_interface_classification(self):
        self.assertEqual(classify_interface("Ethernet 3", "Remote NDIS based Internet Sharing Device"), "usb")
        self.assertEqual(classify_interface("Local Area Connection* 10", "Microsoft Wi-Fi Direct Virtual Adapter"), "hotspot")
        self.assertEqual(classify_interface("Wi-Fi", "Intel(R) Wi-Fi 6 AX201", if_type=71), "wifi")
        self.assertEqual(classify_interface("Ethernet", "Realtek PCIe GbE Family Controller", if_type=6), "ethernet")

    def test_discovery_announcement_json(self):
        ifaces = [InterfaceAddress(ip="192.168.42.10", netmask="255.255.255.0", broadcast="192.168.42.255", kind="usb", name="USB RNDIS")]
        raw = encode_discovery_announcement("TEST-PC", 8080, ifaces, bt_mac="AA:BB:CC:DD:EE:FF", bt_channel=4)
        parsed = json.loads(raw.decode("utf-8"))
        self.assertEqual(parsed["service"], "cursorly")
        self.assertEqual(parsed["host"], "TEST-PC")
        self.assertEqual(parsed["ws_port"], 8080)
        self.assertEqual(parsed["bt_channel"], 4)
        self.assertEqual(parsed["ips"][0]["kind"], "usb")

    def test_rfcomm_framing_partial_and_coalesced(self):
        touch_pkt = struct.pack(PACKET_FORMAT, 1, 0, 120.5, 240.25, 12.345)
        ctrl_pkt = json.dumps({"action": "left_click"}).encode("utf-8")
        stream = encode_rfcomm_frame(FRAME_BINARY_TOUCH, touch_pkt) + encode_rfcomm_frame(FRAME_JSON_CONTROL, ctrl_pkt)

        # Feed partial chunk first
        buf = bytearray(stream[:7])
        self.assertEqual(decode_rfcomm_frames(buf), [])
        buf.extend(stream[7:])
        frames = decode_rfcomm_frames(buf)
        self.assertEqual(len(frames), 2)
        self.assertEqual(frames[0], (FRAME_BINARY_TOUCH, touch_pkt))
        self.assertEqual(frames[1], (FRAME_JSON_CONTROL, ctrl_pkt))
        self.assertEqual(len(buf), 0)

    def test_three_finger_swipe_and_pinch_zoom(self):
        engine = GestureEngine()
        t = 1.0
        # 3-finger swipe right -> desktop switch
        for pid in (1, 2, 3):
            engine.on_touch_down(pid, 100.0 + pid * 20.0, 200.0, t)
        events = []
        for pid in (1, 2, 3):
            events.extend(engine.on_touch_move(pid, 200.0 + pid * 20.0, 200.0, t + 0.05))
        self.assertIn((GestureAction.DESKTOP_SWITCH, (1,)), events)
        for pid in (1, 2, 3):
            engine.on_touch_up(pid, 200.0 + pid * 20.0, 200.0, t + 0.1)

        # 2-finger pinch spread -> zoom
        engine.on_touch_down(1, 100.0, 100.0, 2.0)
        engine.on_touch_down(2, 140.0, 100.0, 2.0)
        engine.on_touch_move(1, 80.0, 100.0, 2.02)
        zoom_events = engine.on_touch_move(2, 160.0, 100.0, 2.02)
        self.assertTrue(any(act == GestureAction.ZOOM for act, _ in zoom_events))

    def test_web_api_info_route_and_state_payload(self):
        engine = GestureEngine()
        engine.ballistics.set_sensitivity(0.75)
        engine.ballistics.precision_mode = True
        server = WebTrackpadServer(callback_engine=engine)
        app = server.create_app()

        info_route = next(r for r in app.routes if getattr(r, "path", None) == "/api/info")
        coro = info_route.endpoint()
        try:
            coro.send(None)
            self.fail("Expected coroutine to return immediately")
        except StopIteration as stop:
            resp = stop.value
        info = json.loads(resp.body.decode("utf-8"))
        self.assertEqual(info["service"], "cursorly")
        self.assertEqual(info["sensitivity"], 0.75)
        self.assertTrue(info["precision_mode"])
        self.assertEqual(info["orientation"], "portrait")
        self.assertTrue(info["tap_to_click"])

    def test_adb_detect_touch_device_resets_bounds_across_devices(self):
        sample_output = (
            "add device 1: /dev/input/event1\n"
            "  ABS_MT_POSITION_X : max 4096\n"
            "add device 2: /dev/input/event2\n"
            "  ABS_MT_POSITION_Y : max 2048\n"
            "add device 3: /dev/input/event5\n"
            "  ABS_MT_POSITION_X : max 1080\n"
            "  ABS_MT_POSITION_Y : max 2400\n"
        )
        streamer = ADBMultiTouchStreamer()
        with patch("subprocess.run") as mock_run:
            mock_run.return_value.stdout = sample_output
            found = streamer.detect_touch_device()
        self.assertTrue(found)
        self.assertEqual(streamer.device_path, "/dev/input/event5")
        self.assertEqual(streamer.max_x, 1080.0)
        self.assertEqual(streamer.max_y, 2400.0)

    def test_windows_bluetooth_mac_lookup_does_not_crash(self):
        if sys.platform == "win32":
            from cursorly.adapters.discovery import get_local_bluetooth_mac

            mac = get_local_bluetooth_mac()
            if mac is not None:
                self.assertRegex(mac, r"^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")


if __name__ == "__main__":
    unittest.main()
