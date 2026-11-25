"""
Native Windows Win32 Mouse and Keyboard Injection.

Uses Win32 SendInput API and multimedia high-precision timer (winmm.timeBeginPeriod)
to ensure sub-millisecond scheduling precision and high-frequency input injection.
"""

import ctypes
from ctypes import wintypes
import atexit
from typing import Optional


# Win32 Constants
INPUT_MOUSE = 0
INPUT_KEYBOARD = 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000

KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_UNICODE = 0x0004

VK_BACK = 0x08
VK_TAB = 0x09
VK_RETURN = 0x0D
VK_SHIFT = 0x10
VK_CONTROL = 0x11
VK_MENU = 0x12
VK_ESCAPE = 0x1B
VK_SPACE = 0x20
VK_END = 0x23
VK_HOME = 0x24
VK_LEFT = 0x25
VK_UP = 0x26
VK_RIGHT = 0x27
VK_DOWN = 0x28
VK_DELETE = 0x2E
VK_A = 0x41
VK_C = 0x43
VK_D = 0x44
VK_V = 0x56
VK_X = 0x58
VK_Z = 0x5A
VK_LWIN = 0x5B

EXTENDED_VKS = {VK_LEFT, VK_UP, VK_RIGHT, VK_DOWN, VK_HOME, VK_END, VK_DELETE}

NAMED_KEY_MAP = {
    "enter": VK_RETURN,
    "backspace": VK_BACK,
    "escape": VK_ESCAPE,
    "esc": VK_ESCAPE,
    "tab": VK_TAB,
    "space": VK_SPACE,
    "delete": VK_DELETE,
    "up": VK_UP,
    "down": VK_DOWN,
    "left": VK_LEFT,
    "right": VK_RIGHT,
    "home": VK_HOME,
    "end": VK_END,
    "win": VK_LWIN,
}

WHEEL_DELTA = 120


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class INPUT_UNION(ctypes.Union):
    _fields_ = [
        ("mi", MOUSEINPUT),
        ("ki", KEYBDINPUT),
        ("hi", HARDWAREINPUT),
    ]


class INPUT(ctypes.Structure):
    _fields_ = [
        ("type", wintypes.DWORD),
        ("u", INPUT_UNION),
    ]


class Win32MouseInjector:
    """Provides high-frequency, sub-pixel Windows mouse and gesture injection."""

    def __init__(self, enable_high_res_timer: bool = True):
        self._user32 = ctypes.windll.user32
        self._winmm = ctypes.windll.winmm
        self._high_res_timer = enable_high_res_timer

        if self._high_res_timer:
            # Request 1ms resolution from the Windows system scheduler
            self._winmm.timeBeginPeriod(1)
            atexit.register(self._cleanup_timer)

        self._left_pressed = False
        self._right_pressed = False
        self._middle_pressed = False

    def _cleanup_timer(self):
        if self._high_res_timer:
            try:
                self._winmm.timeEndPeriod(1)
            except Exception:
                pass

    def _send_mouse(self, flags: int, dx: int = 0, dy: int = 0, data: int = 0) -> bool:
        inp = INPUT()
        inp.type = INPUT_MOUSE
        inp.u.mi.dx = wintypes.LONG(dx)
        inp.u.mi.dy = wintypes.LONG(dy)
        inp.u.mi.mouseData = wintypes.DWORD(data)
        inp.u.mi.dwFlags = wintypes.DWORD(flags)
        inp.u.mi.time = 0
        inp.u.mi.dwExtraInfo = None

        count = self._user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))
        return count == 1

    def move(self, dx: int, dy: int) -> bool:
        """Inject relative mouse movement."""
        if dx == 0 and dy == 0:
            return True
        return self._send_mouse(MOUSEEVENTF_MOVE, dx=dx, dy=dy)

    def left_down(self) -> bool:
        self._left_pressed = True
        return self._send_mouse(MOUSEEVENTF_LEFTDOWN)

    def left_up(self) -> bool:
        self._left_pressed = False
        return self._send_mouse(MOUSEEVENTF_LEFTUP)

    def left_click(self) -> bool:
        return self.left_down() and self.left_up()

    def right_down(self) -> bool:
        self._right_pressed = True
        return self._send_mouse(MOUSEEVENTF_RIGHTDOWN)

    def right_up(self) -> bool:
        self._right_pressed = False
        return self._send_mouse(MOUSEEVENTF_RIGHTUP)

    def right_click(self) -> bool:
        return self.right_down() and self.right_up()

    def middle_down(self) -> bool:
        self._middle_pressed = True
        return self._send_mouse(MOUSEEVENTF_MIDDLEDOWN)

    def middle_up(self) -> bool:
        self._middle_pressed = False
        return self._send_mouse(MOUSEEVENTF_MIDDLEUP)

    def release_all(self) -> None:
        """Release any held mouse buttons to prevent stuck-button state on disconnect/exit."""
        if self._left_pressed:
            self.left_up()
        if self._right_pressed:
            self.right_up()
        if self._middle_pressed:
            self.middle_up()

    def middle_click(self) -> bool:
        return self.middle_down() and self.middle_up()

    def scroll_vertical(self, delta: int) -> bool:
        """
        Inject vertical scroll.
        High-resolution apps accept smooth fractional increments (e.g. 20, 40)
        Standard detent is 120.
        """
        if delta == 0:
            return True
        # Cast to DWORD-safe signed integer
        data = delta & 0xFFFFFFFF
        return self._send_mouse(MOUSEEVENTF_WHEEL, data=data)

    def scroll_horizontal(self, delta: int) -> bool:
        """Inject horizontal scroll."""
        if delta == 0:
            return True
        data = delta & 0xFFFFFFFF
        return self._send_mouse(MOUSEEVENTF_HWHEEL, data=data)

    def _send_key(self, vk_code: int, flags: int = 0) -> bool:
        inp = INPUT()
        inp.type = INPUT_KEYBOARD
        inp.u.ki.wVk = wintypes.WORD(vk_code)
        inp.u.ki.wScan = 0
        inp.u.ki.dwFlags = wintypes.DWORD(flags)
        inp.u.ki.time = 0
        inp.u.ki.dwExtraInfo = None
        return self._user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT)) == 1

    def switch_virtual_desktop(self, direction: int) -> None:
        """
        Switch Windows virtual desktop.
        direction < 0: Win + Ctrl + Left
        direction > 0: Win + Ctrl + Right
        """
        target_arrow = VK_LEFT if direction < 0 else VK_RIGHT
        self._send_key(VK_LWIN, 0)
        self._send_key(VK_CONTROL, 0)
        self._send_key(target_arrow, KEYEVENTF_EXTENDEDKEY)
        self._send_key(target_arrow, KEYEVENTF_KEYUP | KEYEVENTF_EXTENDEDKEY)
        self._send_key(VK_CONTROL, KEYEVENTF_KEYUP)
        self._send_key(VK_LWIN, KEYEVENTF_KEYUP)

    def trigger_task_view(self) -> None:
        """Open Windows Task View (Win + Tab)."""
        self._send_key(VK_LWIN, 0)
        self._send_key(VK_TAB, 0)
        self._send_key(VK_TAB, KEYEVENTF_KEYUP)
        self._send_key(VK_LWIN, KEYEVENTF_KEYUP)

    def zoom_delta(self, delta: int) -> None:
        """Inject Ctrl + MouseWheel for pinch-to-zoom."""
        if delta == 0:
            return
        self._send_key(VK_CONTROL, 0)
        self.scroll_vertical(delta)
        self._send_key(VK_CONTROL, KEYEVENTF_KEYUP)

    def send_unicode_text(self, text: str) -> None:
        """Type arbitrary Unicode text via Win32 KEYEVENTF_UNICODE."""
        if not text:
            return
        encoded = text.encode("utf-16-le")
        for i in range(0, len(encoded), 2):
            code_unit = int.from_bytes(encoded[i : i + 2], "little")
            for flags in (KEYEVENTF_UNICODE, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP):
                inp = INPUT()
                inp.type = INPUT_KEYBOARD
                inp.u.ki.wVk = 0
                inp.u.ki.wScan = wintypes.WORD(code_unit)
                inp.u.ki.dwFlags = wintypes.DWORD(flags)
                inp.u.ki.time = 0
                inp.u.ki.dwExtraInfo = None
                self._user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    def send_key_named(self, name: str) -> None:
        """Send a named navigation key or common shortcut combo."""
        key = name.strip().lower()
        combos = {
            "alt_tab": (VK_MENU, VK_TAB),
            "ctrl_c": (VK_CONTROL, VK_C),
            "ctrl_v": (VK_CONTROL, VK_V),
            "ctrl_x": (VK_CONTROL, VK_X),
            "ctrl_z": (VK_CONTROL, VK_Z),
            "ctrl_a": (VK_CONTROL, VK_A),
        }
        if key in combos:
            mod, target = combos[key]
            self._send_key(mod, 0)
            self._send_key(target, 0)
            self._send_key(target, KEYEVENTF_KEYUP)
            self._send_key(mod, KEYEVENTF_KEYUP)
            return

        vk = NAMED_KEY_MAP.get(key)
        if vk is not None:
            ext = KEYEVENTF_EXTENDEDKEY if vk in EXTENDED_VKS else 0
            self._send_key(vk, ext)
            self._send_key(vk, ext | KEYEVENTF_KEYUP)
