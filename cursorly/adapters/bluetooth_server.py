"""
Bluetooth RFCOMM/SPP Server for Cursorly.

Accepts connections from the Cursorly Android app over Bluetooth RFCOMM
using Python's native AF_BLUETOOTH socket support on Windows, carrying the
exact same 18-byte binary touch frames and JSON control messages as WebSocket.
"""

import json
import socket
import struct
import threading
from typing import Callable, List, Optional, Tuple

from .discovery import CURSORLY_BT_UUID

# Framing over stream socket:
# [1 byte frame_type][2 byte big-endian payload_len][payload]
FRAME_BINARY_TOUCH = 0x01
FRAME_JSON_CONTROL = 0x02
FRAME_HEADER_FORMAT = "!BH"
FRAME_HEADER_SIZE = struct.calcsize(FRAME_HEADER_FORMAT)
MAX_FRAME_SIZE = 65535

PACKET_FORMAT = "!BBffd"
PACKET_SIZE = struct.calcsize(PACKET_FORMAT)


def encode_rfcomm_frame(frame_type: int, payload: bytes) -> bytes:
    """Wrap a binary or JSON payload in a length-prefixed RFCOMM frame."""
    if len(payload) > MAX_FRAME_SIZE:
        raise ValueError(f"Frame payload too large: {len(payload)} > {MAX_FRAME_SIZE}")
    return struct.pack(FRAME_HEADER_FORMAT, frame_type, len(payload)) + payload


def decode_rfcomm_frames(buffer: bytearray) -> List[Tuple[int, bytes]]:
    """
    Extract all complete frames from a mutable stream bytearray.
    Leaves incomplete trailing bytes in `buffer` for the next recv().
    """
    frames: List[Tuple[int, bytes]] = []
    while len(buffer) >= FRAME_HEADER_SIZE:
        frame_type, length = struct.unpack_from(FRAME_HEADER_FORMAT, buffer, 0)
        total = FRAME_HEADER_SIZE + length
        if len(buffer) < total:
            break
        payload = bytes(buffer[FRAME_HEADER_SIZE:total])
        del buffer[:total]
        frames.append((frame_type, payload))
    return frames


class BluetoothTrackpadServer:
    """Listens on Windows Bluetooth RFCOMM for Cursorly Android clients."""

    def __init__(
        self,
        callback_engine=None,
        on_action: Optional[Callable[[str, tuple], None]] = None,
        on_client_change: Optional[Callable[[Optional[str]], None]] = None,
        channel: int = 0,
    ):
        self.callback_engine = callback_engine
        self.on_action = on_action
        self.on_client_change = on_client_change
        self.requested_channel = channel
        self.bound_channel: Optional[int] = None

        self._running = False
        self._server_sock: Optional[socket.socket] = None
        self._accept_thread: Optional[threading.Thread] = None
        self._active_client: Optional[socket.socket] = None
        self._client_lock = threading.Lock()
        self._write_lock = threading.Lock()
        self.available = False

    def start(self) -> bool:
        """Start RFCOMM listener if a Bluetooth adapter is active. Returns True on success."""
        if self._running:
            return True
        if not hasattr(socket, "AF_BLUETOOTH"):
            return False

        try:
            sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            sock.bind(("00:00:00:00:00:00", self.requested_channel))
            sock.listen(1)
            sock.settimeout(0.5)
            _, channel = sock.getsockname()
            self.bound_channel = int(channel)
            self._server_sock = sock
            self._running = True
            self.available = True

            self._accept_thread = threading.Thread(target=self._accept_loop, daemon=True)
            self._accept_thread.start()
            print(f"[Bluetooth] RFCOMM server ready on channel {self.bound_channel} (UUID {CURSORLY_BT_UUID})")
            return True
        except OSError as exc:
            self.available = False
            self.bound_channel = None
            print(f"[Bluetooth] Adapter unavailable or radio off ({exc}); Wi-Fi/USB still active.")
            return False

    def stop(self) -> None:
        self._running = False
        with self._client_lock:
            if self._active_client:
                try:
                    self._active_client.close()
                except Exception:
                    pass
                self._active_client = None
        if self._server_sock:
            try:
                self._server_sock.close()
            except Exception:
                pass
            self._server_sock = None
        if self._accept_thread:
            self._accept_thread.join(timeout=1.0)

    def _send_frame_locked(self, client: socket.socket, frame: bytes) -> None:
        with self._write_lock:
            client.sendall(frame)

    def broadcast_state(self, state: dict) -> None:
        """Push current host sensitivity/precision/orientation state to connected BT client."""
        with self._client_lock:
            client = self._active_client
        if not client:
            return
        try:
            frame = encode_rfcomm_frame(
                FRAME_JSON_CONTROL,
                json.dumps({"event": "state_sync", **state}).encode("utf-8"),
            )
            self._send_frame_locked(client, frame)
        except OSError:
            pass

    def _accept_loop(self) -> None:
        while self._running and self._server_sock:
            try:
                client, addr = self._server_sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break

            with self._client_lock:
                self._active_client = client
            if self.on_client_change:
                self.on_client_change(f"Bluetooth ({addr[0]})")
            self._handle_client(client)
            with self._client_lock:
                if self._active_client is client:
                    self._active_client = None
            if self.on_client_change:
                self.on_client_change(None)

    def _handle_client(self, client: socket.socket) -> None:
        buf = bytearray()
        try:
            client.settimeout(1.0)
            while self._running:
                try:
                    chunk = client.recv(4096)
                except socket.timeout:
                    continue
                if not chunk:
                    break
                buf.extend(chunk)
                for frame_type, payload in decode_rfcomm_frames(buf):
                    self._dispatch_frame(client, frame_type, payload)
        except OSError:
            pass
        finally:
            try:
                client.close()
            except Exception:
                pass

    def _dispatch_frame(self, client: socket.socket, frame_type: int, payload: bytes) -> None:
        if frame_type == FRAME_BINARY_TOUCH and len(payload) >= PACKET_SIZE:
            etype, pid, x, y, ts = struct.unpack(PACKET_FORMAT, payload[:PACKET_SIZE])
            if self.callback_engine:
                if etype == 0:
                    actions = self.callback_engine.on_touch_down(pid, x, y, ts)
                elif etype == 1:
                    actions = self.callback_engine.on_touch_move(pid, x, y, ts)
                elif etype == 2:
                    actions = self.callback_engine.on_touch_up(pid, x, y, ts)
                else:
                    actions = []
                if self.on_action:
                    for act, args in actions:
                        self.on_action(act, args)
        elif frame_type == FRAME_JSON_CONTROL:
            try:
                msg = json.loads(payload.decode("utf-8"))
                act = msg.get("action")
                if act == "ping":
                    reply = encode_rfcomm_frame(
                        FRAME_JSON_CONTROL,
                        json.dumps({"event": "pong", "t": msg.get("t")}).encode("utf-8"),
                    )
                    self._send_frame_locked(client, reply)
                    return
                if act == "client_hello":
                    if self.on_client_change:
                        name = msg.get("device_name", "Android")
                        self.on_client_change(f"{name} (Bluetooth)")
                    if self.callback_engine:
                        with self.callback_engine._lock:
                            b = self.callback_engine.ballistics
                            state = {
                                "event": "state_sync",
                                "sensitivity": round(b.sensitivity, 2),
                                "precision_mode": bool(b.precision_mode),
                                "orientation": self.callback_engine.orientation.value,
                                "natural_scroll": bool(getattr(self.callback_engine, "natural_scroll", False)),
                                "tap_to_click": bool(getattr(self.callback_engine, "tap_to_click", True)),
                            }
                        sync_frame = encode_rfcomm_frame(
                            FRAME_JSON_CONTROL,
                            json.dumps(state).encode("utf-8"),
                        )
                        self._send_frame_locked(client, sync_frame)
                    return
                self._handle_control_payload(msg)
            except Exception:
                pass

    def _handle_control_payload(self, payload: dict) -> None:
        act = payload.get("action")
        if not act:
            return
        if act == "set_sensitivity" and self.callback_engine:
            val = float(payload.get("value", 0.5))
            self.callback_engine.ballistics.set_sensitivity(val)
            if self.on_action:
                self.on_action("sensitivity_changed", (val,))
        elif act == "toggle_precision" and self.callback_engine:
            is_prec = self.callback_engine.ballistics.toggle_precision_mode()
            if self.on_action:
                self.on_action("precision_toggled", (is_prec,))
        elif act == "set_orientation" and self.callback_engine:
            new_o = self.callback_engine.set_orientation(payload.get("orientation", "portrait"))
            if self.on_action:
                self.on_action("orientation_changed", (new_o,))
        elif act == "toggle_orientation" and self.callback_engine:
            new_o = self.callback_engine.toggle_orientation()
            if self.on_action:
                self.on_action("orientation_toggled", (new_o,))
        elif act == "set_natural_scroll" and self.callback_engine:
            enabled = bool(payload.get("enabled", False))
            self.callback_engine.set_natural_scroll(enabled)
        elif act == "set_tap_to_click" and self.callback_engine:
            enabled = bool(payload.get("enabled", True))
            self.callback_engine.set_tap_to_click(enabled)
        elif act == "key_text" and self.on_action:
            self.on_action("key_text", (str(payload.get("text", "")),))
        elif act == "key_press" and self.on_action:
            self.on_action("key_press", (str(payload.get("key", "")),))
        elif act == "zoom" and self.on_action:
            self.on_action("zoom", (int(payload.get("delta", 0)),))
        elif self.on_action:
            self.on_action(act, ())
