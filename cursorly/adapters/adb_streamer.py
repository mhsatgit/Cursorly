"""
ADB Direct Kernel Multi-Touch Streamer.

Directly streams multi-touch events from the Android Linux kernel (/dev/input/event*)
via ADB without needing any app installed on the device.
Provides hardware-rate touch digitizer sampling (120Hz-360Hz+).
"""

import subprocess
import threading
import time
import re
from typing import Optional, Callable, Dict, Tuple

# Linux Input Event Constants
EV_SYN = 0x0000
EV_KEY = 0x0001
EV_ABS = 0x0003

SYN_REPORT = 0x0000

ABS_MT_SLOT = 0x002F          # 47
ABS_MT_TOUCH_MAJOR = 0x0030   # 48
ABS_MT_POSITION_X = 0x0035    # 53
ABS_MT_POSITION_Y = 0x0036    # 54
ABS_MT_TRACKING_ID = 0x0039   # 57
ABS_MT_PRESSURE = 0x003A      # 58

BTN_TOUCH = 0x014A


class ADBCallbackProtocol:
    def on_touch_down(self, pointer_id: int, x: float, y: float, timestamp: float): ...
    def on_touch_move(self, pointer_id: int, x: float, y: float, timestamp: float): ...
    def on_touch_up(self, pointer_id: int, x: float, y: float, timestamp: float): ...


class ADBMultiTouchStreamer:
    """Streams multi-touch events from Android Linux kernel via ADB."""

    def __init__(
        self,
        device_serial: Optional[str] = None,
        callback_engine: Optional[ADBCallbackProtocol] = None,
        on_action: Optional[Callable[[str, tuple], None]] = None,
    ):
        self.device_serial = device_serial
        self.callback_engine = callback_engine
        self.on_action = on_action

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._proc: Optional[subprocess.Popen] = None

        self.device_path = "/dev/input/event5"
        self.max_x = 20352.0
        self.max_y = 44800.0

        # Multi-touch slot tracking state
        self.current_slot = 0
        # slot_id -> dict(x, y, active, moved, new_down, new_up)
        self.slots: Dict[int, Dict[str, any]] = {}
        # slot_id -> tracking_id
        self.slot_tracking: Dict[int, int] = {}

    def _cmd_prefix(self) -> list[str]:
        if self.device_serial:
            return ["adb", "-s", self.device_serial]
        return ["adb"]

    def detect_touch_device(self) -> bool:
        """Auto-detect touchpanel input device and coordinate limits."""
        cmd = self._cmd_prefix() + ["shell", "getevent", "-lp"]
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
            output = res.stdout

            current_dev = None
            is_touch = False
            max_x, max_y = 0.0, 0.0

            for line in output.splitlines():
                dev_match = re.search(r"add device \d+:\s*(/dev/input/event\d+)", line)
                if dev_match:
                    current_dev = dev_match.group(1)
                    is_touch = False
                    max_x, max_y = 0.0, 0.0
                    continue

                if "ABS_MT_POSITION_X" in line:
                    is_touch = True
                    m = re.search(r"max\s+(\d+)", line)
                    if m:
                        max_x = float(m.group(1))
                elif "ABS_MT_POSITION_Y" in line:
                    m = re.search(r"max\s+(\d+)", line)
                    if m:
                        max_y = float(m.group(1))

                if is_touch and current_dev and max_x > 0 and max_y > 0:
                    self.device_path = current_dev
                    self.max_x = max_x
                    self.max_y = max_y
                    return True

            return False
        except Exception as e:
            print(f"[ADBStreamer] Warning auto-detecting touch device: {e}")
            return False

    def start(self):
        """Start streaming events in a background worker thread."""
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._stream_loop, daemon=True)
        self._thread.start()

    def stop(self):
        """Stop streamer and kill ADB process."""
        self._running = False
        if self._proc:
            try:
                if self._proc.stdout:
                    self._proc.stdout.close()
            except Exception:
                pass
            try:
                self._proc.terminate()
                self._proc.wait(timeout=0.5)
            except Exception:
                try:
                    self._proc.kill()
                except Exception:
                    pass
            self._proc = None
        if self._thread:
            self._thread.join(timeout=1.0)
            self._thread = None

    def _stream_loop(self):
        """Main event loop reading getevent stdout with SYN-level frame consolidation."""
        cmd = self._cmd_prefix() + ["exec-out", f"getevent {self.device_path}"]
        print(f"[ADBStreamer] Starting direct kernel stream: {' '.join(cmd)}")
        print(f"[ADBStreamer] Calibrated Touch Resolution: {self.max_x:.0f} x {self.max_y:.0f}")

        try:
            self._proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                bufsize=65536,
            )
        except Exception as e:
            print(f"[ADBStreamer] Failed to launch adb getevent: {e}")
            self._running = False
            return

        stdout = self._proc.stdout
        if not stdout:
            return

        # Uniform isotropic scaling: identical factor for X and Y to preserve 1:1 circular gestures
        # Maps full phone width to 1920 reference units
        uniform_scale = 1920.0 / self.max_x if self.max_x > 0 else 0.094

        while self._running:
            line = stdout.readline()
            if not line:
                break

            parts = line.split()
            if len(parts) < 3:
                continue

            try:
                etype = int(parts[0], 16)
                ecode = int(parts[1], 16)
                eval_ = int(parts[2], 16)
            except ValueError:
                continue

            now = time.monotonic()

            if etype == EV_ABS:
                slot_data = self.slots.setdefault(
                    self.current_slot,
                    {"x": 0.0, "y": 0.0, "active": False, "moved": False, "new_down": False, "new_up": False}
                )

                if ecode == ABS_MT_SLOT:
                    self.current_slot = eval_
                    self.slots.setdefault(
                        self.current_slot,
                        {"x": 0.0, "y": 0.0, "active": False, "moved": False, "new_down": False, "new_up": False}
                    )

                elif ecode == ABS_MT_TRACKING_ID:
                    is_lift = (eval_ == 0xFFFFFFFF or eval_ == -1 or eval_ > 0x7FFFFFFF)
                    if is_lift:
                        slot_data["new_up"] = True
                        slot_data["active"] = False
                    else:
                        slot_data["active"] = True
                        slot_data["new_down"] = True
                        self.slot_tracking[self.current_slot] = eval_

                elif ecode == ABS_MT_POSITION_X:
                    norm_x = eval_ * uniform_scale
                    slot_data["x"] = norm_x
                    if slot_data.get("active", False) and not slot_data.get("new_down", False):
                        slot_data["moved"] = True

                elif ecode == ABS_MT_POSITION_Y:
                    norm_y = eval_ * uniform_scale
                    slot_data["y"] = norm_y
                    if slot_data.get("active", False) and not slot_data.get("new_down", False):
                        slot_data["moved"] = True

            elif etype == EV_SYN and ecode == SYN_REPORT:
                # Frame boundary: Consolidate state changes per slot in this frame
                if self.callback_engine:
                    for s_id, s_info in list(self.slots.items()):
                        pid = self.slot_tracking.get(s_id, s_id)

                        if s_info.get("new_down", False):
                            actions = self.callback_engine.on_touch_down(pid, s_info["x"], s_info["y"], now)
                            s_info["new_down"] = False
                            if self.on_action:
                                for act, args in actions:
                                    self.on_action(act, args)

                        if s_info.get("moved", False):
                            actions = self.callback_engine.on_touch_move(pid, s_info["x"], s_info["y"], now)
                            s_info["moved"] = False
                            if self.on_action:
                                for act, args in actions:
                                    self.on_action(act, args)

                        if s_info.get("new_up", False):
                            actions = self.callback_engine.on_touch_up(pid, s_info["x"], s_info["y"], now)
                            s_info["new_up"] = False
                            if s_id in self.slot_tracking:
                                del self.slot_tracking[s_id]
                            if self.on_action:
                                for act, args in actions:
                                    self.on_action(act, args)
