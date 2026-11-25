"""
Cursorly: Ultra-Precise Mobile Mouse Control.

Main Entrypoint and Telemetry Runner.
Supports Seamless Multi-Transport Auto-Discovery (Wi-Fi / Hotspot / USB Tethering / Bluetooth),
ADB Direct Kernel Stream (Zero-App), and Web Trackpad (Zero-Install).
Includes live sensitivity adjustments, keyboard hotkeys, Sniper mode, and dual Portrait/Landscape orientation modes.
"""

import sys
import time
import argparse
import threading
from typing import Optional

try:
    import msvcrt
except ImportError:
    msvcrt = None

from cursorly.engine.filter_one_euro import OneEuroFilter2D
from cursorly.engine.ballistics import BallisticsEngine, BallisticsMode
from cursorly.engine.gestures import GestureEngine, GestureAction, Orientation
from cursorly.injection.win32_mouse import Win32MouseInjector
from cursorly.adapters.adb_streamer import ADBMultiTouchStreamer
from cursorly.adapters.bluetooth_server import BluetoothTrackpadServer
from cursorly.adapters.discovery import DiscoveryServer, get_ipv4_interfaces
from cursorly.adapters.web_server import WebTrackpadServer


class CursorlyApp:
    def __init__(
        self,
        mode: str = "seamless",
        device_serial: Optional[str] = None,
        web_port: int = 8080,
        sensitivity: float = 0.5,
        precision: bool = False,
        orientation: str = "portrait",
        linear: bool = False,
        min_cutoff: float = 0.8,
        beta: float = 0.06,
    ):
        self.mode = mode
        self.device_serial = device_serial
        self.web_port = web_port

        # Initialize Win32 high-precision mouse injector (1ms multimedia timer)
        self.injector = Win32MouseInjector(enable_high_res_timer=True)

        # Initialize gesture recognition engine with 1€ filter and orientation
        self.gesture_engine = GestureEngine(
            min_cutoff=min_cutoff,
            beta=beta,
            orientation=Orientation(orientation),
        )
        if linear:
            self.gesture_engine.ballistics.mode = BallisticsMode.LINEAR
        self.gesture_engine.ballistics.set_sensitivity(sensitivity)
        self.gesture_engine.ballistics.precision_mode = precision

        # Telemetry metrics
        self.action_count = 0
        self.last_stats_time = time.monotonic()
        self.last_action_desc = "Idle"
        self.connected_client: Optional[str] = None

        self.adb_streamer: Optional[ADBMultiTouchStreamer] = None
        self.web_server: Optional[WebTrackpadServer] = None
        self.bt_server: Optional[BluetoothTrackpadServer] = None
        self.discovery_server: Optional[DiscoveryServer] = None
        self._running = False
        self._key_thread: Optional[threading.Thread] = None
        self._pending_thread: Optional[threading.Thread] = None
        self._pending_cond = threading.Condition()
        self._engine_lock = threading.RLock()
        self.gesture_engine.on_pending_changed = self._wake_pending_scheduler

    def _wake_pending_scheduler(self):
        with self._pending_cond:
            self._pending_cond.notify_all()

    def _pending_loop(self):
        """Background scheduler that flushes deferred taps or promotes held second taps to drag."""
        while self._running:
            delay = self.gesture_engine.next_pending_delay()
            if delay is None:
                with self._pending_cond:
                    if self._running and self.gesture_engine.next_pending_delay() is None:
                        self._pending_cond.wait(timeout=0.5)
                continue
            if delay > 0:
                with self._pending_cond:
                    self._pending_cond.wait(timeout=delay)
                continue
            actions = self.gesture_engine.poll_pending_actions()
            for act, args in actions:
                self.handle_action(act, args)

    def _on_client_change(self, client_desc: Optional[str]):
        self.connected_client = client_desc
        if client_desc:
            print(f"\n [Connected] {client_desc}")
        else:
            with self._engine_lock:
                self.gesture_engine.reset_state()
                self.injector.release_all()
            print("\n [Disconnected] Waiting for Android device...")

    def _on_device_seen(self, info: dict):
        name = info.get("device_name", "Android Device")
        ip = info.get("ip", "unknown")
        print(f"\n [Discovery] Android device available: {name} ({ip})")

    def _sync_clients(self):
        if self.web_server:
            self.web_server.broadcast_state()
        if self.bt_server:
            with self._engine_lock:
                b = self.gesture_engine.ballistics
                state = {
                    "sensitivity": round(b.sensitivity, 2),
                    "precision_mode": bool(b.precision_mode),
                    "orientation": self.gesture_engine.orientation.value,
                    "natural_scroll": bool(self.gesture_engine.natural_scroll),
                    "tap_to_click": bool(self.gesture_engine.tap_to_click),
                }
            self.bt_server.broadcast_state(state)

    def handle_action(self, action: str, args: tuple):
        """Dispatches recognized gestures to Win32 mouse/keyboard injection."""
        with self._engine_lock:
            self.action_count += 1
            self.last_action_desc = action

            if action == GestureAction.MOVE:
                dx, dy = args
                self.injector.move(dx, dy)
            elif action == GestureAction.LEFT_CLICK:
                self.injector.left_click()
            elif action == GestureAction.LEFT_DOWN:
                self.injector.left_down()
            elif action == GestureAction.LEFT_UP:
                self.injector.left_up()
            elif action == GestureAction.RIGHT_CLICK:
                self.injector.right_click()
            elif action == GestureAction.RIGHT_DOWN:
                self.injector.right_down()
            elif action == GestureAction.RIGHT_UP:
                self.injector.right_up()
            elif action == GestureAction.MIDDLE_CLICK:
                self.injector.middle_click()
            elif action == GestureAction.MIDDLE_DOWN:
                self.injector.middle_down()
            elif action == GestureAction.MIDDLE_UP:
                self.injector.middle_up()
            elif action == GestureAction.SCROLL:
                wheel_y, wheel_x = args
                if wheel_y != 0:
                    self.injector.scroll_vertical(wheel_y)
                if wheel_x != 0:
                    self.injector.scroll_horizontal(wheel_x)
            elif action == GestureAction.ZOOM:
                self.injector.zoom_delta(args[0])
            elif action == GestureAction.DESKTOP_SWITCH:
                self.injector.switch_virtual_desktop(args[0])
            elif action == GestureAction.TASK_VIEW:
                self.injector.trigger_task_view()
            elif action == GestureAction.KEY_TEXT:
                self.injector.send_unicode_text(args[0])
            elif action == GestureAction.KEY_PRESS:
                self.injector.send_key_named(args[0])
            elif action in ("sensitivity_changed", "precision_toggled", "orientation_changed", "orientation_toggled"):
                pass

    def _listen_keyboard(self):
        """Non-blocking Windows console keyboard listener for live controls."""
        if not msvcrt:
            return

        while self._running:
            try:
                if msvcrt.kbhit():
                    ch = msvcrt.getch()
                    changed = True
                    with self._engine_lock:
                        ballistics = self.gesture_engine.ballistics
                        if ch in (b'-', b'_', b'['):
                            ballistics.set_sensitivity(ballistics.sensitivity - 0.05)
                        elif ch in (b'+', b'=', b']'):
                            ballistics.set_sensitivity(ballistics.sensitivity + 0.05)
                        elif ch in (b'p', b'P'):
                            ballistics.toggle_precision_mode()
                        elif ch in (b'o', b'O', b'l', b'L'):
                            self.gesture_engine.toggle_orientation()
                        elif ch == b'1':
                            ballistics.set_sensitivity(0.20)
                        elif ch == b'2':
                            ballistics.set_sensitivity(0.40)
                        elif ch == b'3':
                            ballistics.set_sensitivity(0.60)
                        elif ch == b'4':
                            ballistics.set_sensitivity(1.00)
                        else:
                            changed = False

                    if changed:
                        self._sync_clients()
                time.sleep(0.05)
            except Exception:
                break

    def start(self):
        if hasattr(sys.stdout, "reconfigure"):
            try:
                sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass
        self._running = True

        print("=" * 68)
        print("   ⚡ Cursorly: Ultra-Precise Mobile Mouse Control Engine ⚡")
        print("=" * 68)
        print(f" [Config] Mode: {self.mode.upper()}")
        print(f" [Config] Orientation: {self.gesture_engine.orientation.value.upper()}")
        print(f" [Config] Base Sensitivity: {self.gesture_engine.ballistics.sensitivity:.2f}x")
        print(f" [Config] Precision Mode: {'ON (🎯 Sniper 0.35x)' if self.gesture_engine.ballistics.precision_mode else 'OFF'}")
        print(f" [Config] Ballistics: {self.gesture_engine.ballistics.mode.value.upper()}")
        print(f" [Config] 1€ Filter: min_cutoff={self.gesture_engine.filter.fx.min_cutoff}Hz, beta={self.gesture_engine.filter.fx.beta}")
        print("-" * 68)
        print(" 🎮 Live PC Controls:")
        print("    [O/L] Toggle Orientation (Portrait <-> Landscape)")
        print("    [-] Lower Sens | [+] Higher Sens | [P] Sniper Mode | [1-4] Presets")
        print("-" * 68)

        # 1. Start Bluetooth RFCOMM server if requested
        bt_channel: Optional[int] = None
        if self.mode in ("seamless", "both", "bluetooth"):
            self.bt_server = BluetoothTrackpadServer(
                callback_engine=self.gesture_engine,
                on_action=self.handle_action,
                on_client_change=self._on_client_change,
            )
            if self.bt_server.start():
                bt_channel = self.bt_server.bound_channel

        # 2. Start WebSocket server + Auto-Discovery (Wi-Fi / Hotspot / USB Tethering)
        if self.mode in ("seamless", "both", "web"):
            self.web_server = WebTrackpadServer(
                port=self.web_port,
                callback_engine=self.gesture_engine,
                on_action=self.handle_action,
                on_client_change=self._on_client_change,
            )
            self.web_server.start()

            self.discovery_server = DiscoveryServer(
                ws_port=self.web_port,
                bt_channel=bt_channel,
                on_device_seen=self._on_device_seen,
            )
            self.discovery_server.start()
            ifaces = get_ipv4_interfaces()
            if ifaces:
                for iface in ifaces:
                    print(f" [Auto-Discovery] Ready on {iface.kind.upper()} ({iface.ip}:{self.web_port})")

        # 3. Start legacy ADB Streamer only when explicitly requested
        if self.mode in ("adb", "both"):
            self.adb_streamer = ADBMultiTouchStreamer(
                device_serial=self.device_serial,
                callback_engine=self.gesture_engine,
                on_action=self.handle_action,
            )
            found = self.adb_streamer.detect_touch_device()
            if found:
                print(f" [ADB] Found touch device: {self.adb_streamer.device_path}")
                self.adb_streamer.start()
                self._on_client_change(f"ADB ({self.adb_streamer.device_path})")
            else:
                print(" [ADB] Notice: No direct ADB touchpanel detected.")

        # 4. Start keyboard hotkey thread & pending gesture scheduler
        self._key_thread = threading.Thread(target=self._listen_keyboard, daemon=True)
        self._key_thread.start()
        self._pending_thread = threading.Thread(target=self._pending_loop, daemon=True)
        self._pending_thread.start()

        print("-" * 68)
        print(" Active! Open the Cursorly Android app — it will auto-detect this PC.")
        print(" Press Ctrl+C to stop.")
        print("-" * 68)

        try:
            while self._running:
                time.sleep(0.5)
                now = time.monotonic()
                dt = now - self.last_stats_time
                if dt >= 0.5:
                    with self._engine_lock:
                        hz = int(self.action_count / dt)
                        self.action_count = 0
                        self.last_stats_time = now

                        b = self.gesture_engine.ballistics
                        eff_sens = b.get_effective_sensitivity()
                        mode_str = "SNIPER 🎯" if b.precision_mode else "Normal"
                        orient = self.gesture_engine.orientation
                        if orient == Orientation.LANDSCAPE:
                            orient_str = "LANDSCAPE 🔄"
                        elif orient == Orientation.LANDSCAPE_INVERTED:
                            orient_str = "LAND-INV 🔄"
                        else:
                            orient_str = "PORTRAIT 📱"
                        fingers = len(self.gesture_engine.touches)
                        client_str = (self.connected_client or "Searching...")[:20]
                        action_str = self.last_action_desc[:12]

                    sys.stdout.write(
                        f"\r [Telemetry] {hz:3d} Hz | {eff_sens:.2f}x [{mode_str:8s}] | {orient_str:13s} | F:{fingers} | {client_str:20s} | {action_str:12s}"
                    )
                    sys.stdout.flush()
        except KeyboardInterrupt:
            print("\n Stopping Cursorly...")
        finally:
            self.stop()

    def stop(self):
        self._running = False
        self._wake_pending_scheduler()
        with self._engine_lock:
            self.gesture_engine.reset_state()
            self.injector.release_all()
        if self.discovery_server:
            self.discovery_server.stop()
        if self.bt_server:
            self.bt_server.stop()
        if self.adb_streamer:
            self.adb_streamer.stop()
        if self.web_server:
            self.web_server.stop()
        print("\n Cursorly stopped cleanly.")


def main():
    parser = argparse.ArgumentParser(description="Cursorly Input Translator")
    parser.add_argument(
        "--mode",
        choices=["seamless", "both", "adb", "web", "bluetooth"],
        default="seamless",
        help="Input mode (default: seamless zero-ADB Wi-Fi/Hotspot/USB/Bluetooth)",
    )
    parser.add_argument("--serial", type=str, default=None, help="ADB device serial (default: auto-detect single device)")
    parser.add_argument("--port", type=int, default=8080, help="Web trackpad HTTP port")
    parser.add_argument("--sens", type=float, default=0.5, help="Pointer sensitivity multiplier (default: 0.5)")
    parser.add_argument("--precision", action="store_true", help="Start with Precision Sniper Mode enabled (0.35x dampener)")
    parser.add_argument("--orientation", "-o", choices=["portrait", "landscape", "landscape_inverted"], default="portrait", help="Device orientation mode (default: portrait)")
    parser.add_argument("--landscape", action="store_true", help="Quick flag to start in Landscape mode")
    parser.add_argument("--linear", action="store_true", help="Enable 1:1 raw linear tracking without ballistics")
    parser.add_argument("--min-cutoff", type=float, default=0.8, help="1€ filter min cutoff in Hz (default: 0.8)")
    parser.add_argument("--beta", type=float, default=0.06, help="1€ filter beta coefficient (default: 0.06)")

    args = parser.parse_args()

    orientation = "landscape" if args.landscape else args.orientation

    app = CursorlyApp(
        mode=args.mode,
        device_serial=args.serial,
        web_port=args.port,
        sensitivity=args.sens,
        precision=args.precision,
        orientation=orientation,
        linear=args.linear,
        min_cutoff=args.min_cutoff,
        beta=args.beta,
    )
    app.start()


if __name__ == "__main__":
    main()
