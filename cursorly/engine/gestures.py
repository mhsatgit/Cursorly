"""
Multi-Touch Gesture Recognizer and State Machine.

Translates high-frequency unbuffered touch streams into smooth mouse actions:
- 1 Finger Move: Smooth 1€ filtered cursor motion.
- 1 Finger Quick Tap: Left Click.
- 1 Finger Tap-Hold-Drag: Left Click and Drag.
- 2 Finger Drag: High-definition smooth vertical & horizontal scrolling with kinetic inertia.
- 2 Finger Tap: Right Click.
- 3 Finger Tap: Middle Click.
- 3 Finger Horizontal Swipe: Switch Virtual Desktops (Win+Ctrl+Left/Right).
- 3 Finger Swipe Up: Task View (Win+Tab).

Supports Dual Orientation Modes:
- Portrait: Standard vertical orientation.
- Landscape: Horizontal orientation with 90° coordinate rotation for natural tracking.
"""

import time
import math
import threading
from enum import Enum
from typing import Dict, Optional, Tuple, Callable
from dataclasses import dataclass

from .filter_one_euro import OneEuroFilter2D
from .subpixel_accumulator import SubpixelAccumulator
from .ballistics import BallisticsEngine


class Orientation(str, Enum):
    PORTRAIT = "portrait"
    LANDSCAPE = "landscape"
    LANDSCAPE_INVERTED = "landscape_inverted"


@dataclass
class TouchPoint:
    id: int
    start_x: float
    start_y: float
    current_x: float
    current_y: float
    prev_x: float
    prev_y: float
    start_time: float
    last_time: float
    total_travel: float = 0.0


class GestureAction:
    MOVE = "move"
    LEFT_CLICK = "left_click"
    LEFT_DOWN = "left_down"
    LEFT_UP = "left_up"
    RIGHT_CLICK = "right_click"
    RIGHT_DOWN = "right_down"
    RIGHT_UP = "right_up"
    MIDDLE_CLICK = "middle_click"
    MIDDLE_DOWN = "middle_down"
    MIDDLE_UP = "middle_up"
    SCROLL = "scroll"
    ZOOM = "zoom"
    DESKTOP_SWITCH = "desktop_switch"
    TASK_VIEW = "task_view"
    KEY_TEXT = "key_text"
    KEY_PRESS = "key_press"


class GestureEngine:
    def __init__(
        self,
        tap_timeout_sec: float = 0.20,
        tap_distance_threshold: float = 18.0,
        scroll_sensitivity: float = 0.45,
        double_tap_drag_window_sec: float = 0.20,
        drag_start_threshold: float = 8.0,
        min_cutoff: float = 0.8,
        beta: float = 0.06,
        orientation: Orientation = Orientation.PORTRAIT,
        natural_scroll: bool = False,
        tap_to_click: bool = True,
        on_pending_changed: Optional[Callable[[], None]] = None,
    ):
        self.tap_timeout = tap_timeout_sec
        self.tap_threshold = tap_distance_threshold
        self.scroll_sensitivity = scroll_sensitivity
        self.double_tap_drag_window = double_tap_drag_window_sec
        self.drag_start_threshold = drag_start_threshold
        self.orientation: Orientation = orientation
        self.natural_scroll: bool = natural_scroll
        self.tap_to_click: bool = tap_to_click
        self.on_pending_changed = on_pending_changed

        self.filter = OneEuroFilter2D(min_cutoff=min_cutoff, beta=beta)
        self.accumulator = SubpixelAccumulator()
        self.scroll_accumulator = SubpixelAccumulator()
        self.ballistics = BallisticsEngine()
        self._lock = threading.RLock()

        self.touches: Dict[int, TouchPoint] = {}
        self.last_tap_time: float = 0.0
        self.last_tap_pos: Tuple[float, float] = (0.0, 0.0)
        self.is_dragging: bool = False

        # Deferred tap & double-tap-drag disambiguation state
        self._pending_tap: bool = False
        self._pending_tap_deadline_ts: Optional[float] = None
        self._pending_tap_deadline_host: Optional[float] = None
        self._pending_drag_candidate: bool = False
        self._pending_drag_deadline_ts: Optional[float] = None
        self._pending_drag_deadline_host: Optional[float] = None

        # Virtual filtered position for continuous smoothing
        self.virtual_pos_x: float = 0.0
        self.virtual_pos_y: float = 0.0
        self.prev_filtered_x: float = 0.0
        self.prev_filtered_y: float = 0.0

        # Multi-finger gesture tracking
        self._three_finger_triggered: bool = False
        self._prev_pinch_dist: Optional[float] = None
        self._max_fingers_in_stroke: int = 0
        self._multi_finger_tap_fired: bool = False

    def _clear_pending(self) -> None:
        self._pending_tap = False
        self._pending_tap_deadline_ts = None
        self._pending_tap_deadline_host = None
        self._pending_drag_candidate = False
        self._pending_drag_deadline_ts = None
        self._pending_drag_deadline_host = None

    def _notify_pending(self) -> None:
        cb = self.on_pending_changed
        if cb:
            cb()

    def next_pending_delay(self) -> Optional[float]:
        """Return seconds until the next pending tap or drag-promotion deadline, or None."""
        with self._lock:
            deadlines = [
                d
                for d in (self._pending_tap_deadline_host, self._pending_drag_deadline_host)
                if d is not None
            ]
            if not deadlines:
                return None
            return max(0.0, min(deadlines) - time.monotonic())

    def poll_pending_actions(self, timestamp: Optional[float] = None) -> list[Tuple[str, tuple]]:
        """Flush any deferred single-tap click or promote a held second tap into drag."""
        with self._lock:
            if self._pending_tap:
                expired = (
                    timestamp >= self._pending_tap_deadline_ts
                    if (timestamp is not None and self._pending_tap_deadline_ts is not None)
                    else (
                        self._pending_tap_deadline_host is not None
                        and time.monotonic() >= self._pending_tap_deadline_host
                    )
                )
                if expired:
                    self._clear_pending()
                    return [(GestureAction.LEFT_CLICK, ())]

            if self._pending_drag_candidate:
                expired = (
                    timestamp >= self._pending_drag_deadline_ts
                    if (timestamp is not None and self._pending_drag_deadline_ts is not None)
                    else (
                        self._pending_drag_deadline_host is not None
                        and time.monotonic() >= self._pending_drag_deadline_host
                    )
                )
                if expired and len(self.touches) == 1 and not self.is_dragging:
                    self._clear_pending()
                    self.is_dragging = True
                    self.virtual_pos_x = 0.0
                    self.virtual_pos_y = 0.0
                    self.prev_filtered_x = 0.0
                    self.prev_filtered_y = 0.0
                    self.accumulator.reset()
                    return [(GestureAction.LEFT_DOWN, ())]
            return []

    def set_natural_scroll(self, enabled: bool) -> bool:
        """Toggle natural (reverse) scroll direction."""
        with self._lock:
            self.natural_scroll = bool(enabled)
            return self.natural_scroll

    def set_tap_to_click(self, enabled: bool) -> bool:
        """Enable or disable tap-to-click and double-tap-drag gestures."""
        with self._lock:
            self.tap_to_click = bool(enabled)
            if not self.tap_to_click:
                self._clear_pending()
            return self.tap_to_click

    def reset_state(self) -> None:
        """Reset active touch, filter, and drag state (e.g. on client disconnect)."""
        with self._lock:
            self.touches.clear()
            self.is_dragging = False
            self._clear_pending()
            self.virtual_pos_x = 0.0
            self.virtual_pos_y = 0.0
            self.prev_filtered_x = 0.0
            self.prev_filtered_y = 0.0
            self.accumulator.reset()
            self.scroll_accumulator.reset()
            self.filter.reset()
            self._prev_pinch_dist = None
            self._three_finger_triggered = False
            self._max_fingers_in_stroke = 0
            self._multi_finger_tap_fired = False

    def set_orientation(self, orientation: Orientation) -> Orientation:
        """Set the active orientation mode."""
        with self._lock:
            if isinstance(orientation, str):
                orientation = Orientation(orientation)
            self.orientation = orientation
            return self.orientation

    def toggle_orientation(self) -> Orientation:
        """Toggle between Portrait and Landscape modes."""
        with self._lock:
            if self.orientation == Orientation.PORTRAIT:
                self.orientation = Orientation.LANDSCAPE
            else:
                self.orientation = Orientation.PORTRAIT
            return self.orientation

    def _rotate_delta(self, dx: float, dy: float) -> Tuple[float, float]:
        """Rotates relative motion deltas according to the active orientation mode."""
        if self.orientation == Orientation.LANDSCAPE:
            # 90 deg counter-clockwise rotation:
            # Swiping physically Right (downwards in portrait coordinates) -> +X
            # Swiping physically Up (rightwards in portrait coordinates) -> -Y
            return dy, -dx
        elif self.orientation == Orientation.LANDSCAPE_INVERTED:
            # 270 deg rotation:
            return -dy, dx
        return dx, dy

    def on_touch_down(self, pointer_id: int, x: float, y: float, timestamp: float) -> list[Tuple[str, tuple]]:
        with self._lock:
            events, should_notify = self._on_touch_down_locked(pointer_id, x, y, timestamp)
        if should_notify:
            self._notify_pending()
        return events

    def _on_touch_down_locked(
        self, pointer_id: int, x: float, y: float, timestamp: float
    ) -> Tuple[list[Tuple[str, tuple]], bool]:
        events = []
        should_notify = False
        now = timestamp if timestamp > 0 else time.monotonic()

        pt = TouchPoint(
            id=pointer_id,
            start_x=x,
            start_y=y,
            current_x=x,
            current_y=y,
            prev_x=x,
            prev_y=y,
            start_time=now,
            last_time=now,
        )
        self.touches[pointer_id] = pt
        self._max_fingers_in_stroke = max(self._max_fingers_in_stroke, len(self.touches))

        if len(self.touches) == 1:
            self._max_fingers_in_stroke = 1
            self._multi_finger_tap_fired = False
            dt_tap = now - self.last_tap_time
            dist_tap = math.hypot(x - self.last_tap_pos[0], y - self.last_tap_pos[1])
            if self._pending_tap:
                if (
                    self.tap_to_click
                    and 0.0 <= dt_tap < self.double_tap_drag_window
                    and dist_tap < self.tap_threshold * 1.5
                ):
                    # Second touch down within double-tap window: hold off click/drag until move or lift
                    self._pending_tap = False
                    self._pending_tap_deadline_ts = None
                    self._pending_tap_deadline_host = None
                    self._pending_drag_candidate = True
                    self._pending_drag_deadline_ts = now + self.tap_timeout
                    self._pending_drag_deadline_host = time.monotonic() + self.tap_timeout
                    should_notify = True
                else:
                    # Prior pending tap expired or far away (ignore stale tap if clock jumped backwards)
                    self._clear_pending()
                    if self.tap_to_click and dt_tap >= 0.0:
                        events.append((GestureAction.LEFT_CLICK, ()))
                    should_notify = True

            # Reset virtual position and filter for new stroke
            self.virtual_pos_x = 0.0
            self.virtual_pos_y = 0.0
            self.prev_filtered_x = 0.0
            self.prev_filtered_y = 0.0
            self.filter.reset()
            self.filter.filter(0.0, 0.0, now)
        elif len(self.touches) >= 2:
            if self._pending_tap or self._pending_drag_candidate:
                if self._pending_tap and self.tap_to_click:
                    events.append((GestureAction.LEFT_CLICK, ()))
                self._clear_pending()
                should_notify = True
            if len(self.touches) == 2:
                pts = list(self.touches.values())
                self._prev_pinch_dist = math.hypot(pts[1].current_x - pts[0].current_x, pts[1].current_y - pts[0].current_y)
            elif len(self.touches) == 3:
                self._three_finger_triggered = False

        return events, should_notify

    def on_touch_move(self, pointer_id: int, x: float, y: float, timestamp: float) -> list[Tuple[str, tuple]]:
        with self._lock:
            events, should_notify = self._on_touch_move_locked(pointer_id, x, y, timestamp)
        if should_notify:
            self._notify_pending()
        return events

    def _on_touch_move_locked(
        self, pointer_id: int, x: float, y: float, timestamp: float
    ) -> Tuple[list[Tuple[str, tuple]], bool]:
        events = []
        should_notify = False
        now = timestamp if timestamp > 0 else time.monotonic()

        if pointer_id not in self.touches:
            return events, False

        pt = self.touches[pointer_id]
        dx_raw = x - pt.current_x
        dy_raw = y - pt.current_y
        dt = max(1e-4, now - pt.last_time)

        pt.prev_x = pt.current_x
        pt.prev_y = pt.current_y
        pt.current_x = x
        pt.current_y = y
        pt.last_time = now
        pt.total_travel += math.hypot(dx_raw, dy_raw)

        finger_count = len(self.touches)

        if finger_count == 1:
            if self._pending_drag_candidate:
                if pt.total_travel >= self.drag_start_threshold or (now - pt.start_time) >= self.tap_timeout:
                    # User moved or held the second touch: commit to single LEFT_DOWN drag (no double-click!)
                    self._clear_pending()
                    self.is_dragging = True
                    events.append((GestureAction.LEFT_DOWN, ()))
                    self.virtual_pos_x = 0.0
                    self.virtual_pos_y = 0.0
                    self.prev_filtered_x = 0.0
                    self.prev_filtered_y = 0.0
                    self.filter.reset()
                    self.filter.filter(0.0, 0.0, pt.start_time if pt.start_time < now else now - 1e-3)
                    self.accumulator.reset()
                    should_notify = True
                else:
                    # Keep cursor stationary during initial double-tap disambiguation
                    return events, False

            # Rotate raw delta based on orientation
            dx_rot, dy_rot = self._rotate_delta(dx_raw, dy_raw)

            # Apply 1€ Adaptive Filter on rotated cumulative position
            self.virtual_pos_x += dx_rot
            self.virtual_pos_y += dy_rot
            fx, fy = self.filter.filter(self.virtual_pos_x, self.virtual_pos_y, now)

            dx_filt = fx - self.prev_filtered_x
            dy_filt = fy - self.prev_filtered_y
            self.prev_filtered_x = fx
            self.prev_filtered_y = fy

            # Apply velocity ballistics to filtered delta
            ax, ay = self.ballistics.apply(dx_filt, dy_filt, dt)

            # Sub-pixel integer accumulation
            idx, idy = self.accumulator.step(ax, ay)
            if idx != 0 or idy != 0:
                events.append((GestureAction.MOVE, (idx, idy)))

        elif finger_count == 2:
            pts = list(self.touches.values())
            p0, p1 = pts[0], pts[1]
            v0x, v0y = p0.current_x - p0.prev_x, p0.current_y - p0.prev_y
            v1x, v1y = p1.current_x - p1.prev_x, p1.current_y - p1.prev_y
            cur_dist = math.hypot(p1.current_x - p0.current_x, p1.current_y - p0.current_y)
            prev_dist = self._prev_pinch_dist if self._prev_pinch_dist is not None else cur_dist
            d_dist = cur_dist - prev_dist
            self._prev_pinch_dist = cur_dist

            # Pinch-to-zoom only when fingers move appreciably in opposing directions
            m0 = math.hypot(v0x, v0y)
            m1 = math.hypot(v1x, v1y)
            dot = (v0x * v1x + v0y * v1y) / (m0 * m1) if (m0 > 0.5 and m1 > 0.5) else 1.0
            if dot < -0.5 and abs(d_dist) > 2.0:
                zoom_delta = int(d_dist * 4.0)
                if zoom_delta != 0:
                    self._multi_finger_tap_fired = True
                    events.append((GestureAction.ZOOM, (zoom_delta,)))
            else:
                # 2-Finger: Smooth 2D scrolling using only this pointer's centroid contribution
                raw_dx = dx_raw / 2.0
                raw_dy = dy_raw / 2.0

                # Rotate scrolling deltas so vertical drag on landscape scrolls vertically
                scroll_dx, scroll_dy = self._rotate_delta(raw_dx, raw_dy)
                if self.natural_scroll:
                    scroll_dx, scroll_dy = -scroll_dx, -scroll_dy

                # Standard Windows wheel detent is 120; accumulate sub-unit increments
                wheel_y, wheel_x = self.scroll_accumulator.step(
                    scroll_dy * self.scroll_sensitivity * 12.0,
                    scroll_dx * self.scroll_sensitivity * 12.0,
                )
                if wheel_y != 0 or wheel_x != 0:
                    events.append((GestureAction.SCROLL, (wheel_y, wheel_x)))

        elif finger_count == 3 and not self._three_finger_triggered:
            pts = list(self.touches.values())
            avg_dx = sum(t.current_x - t.start_x for t in pts) / 3.0
            avg_dy = sum(t.current_y - t.start_y for t in pts) / 3.0
            rot_dx, rot_dy = self._rotate_delta(avg_dx, avg_dy)
            if abs(rot_dx) > 65.0 and abs(rot_dx) > abs(rot_dy) * 1.4:
                self._three_finger_triggered = True
                self._multi_finger_tap_fired = True
                direction = 1 if rot_dx > 0 else -1
                events.append((GestureAction.DESKTOP_SWITCH, (direction,)))
            elif rot_dy < -65.0 and abs(rot_dy) > abs(rot_dx) * 1.4:
                self._three_finger_triggered = True
                self._multi_finger_tap_fired = True
                events.append((GestureAction.TASK_VIEW, ()))

        return events, should_notify

    def on_touch_up(self, pointer_id: int, x: float, y: float, timestamp: float) -> list[Tuple[str, tuple]]:
        with self._lock:
            events, should_notify = self._on_touch_up_locked(pointer_id, x, y, timestamp)
        if should_notify:
            self._notify_pending()
        return events

    def _on_touch_up_locked(
        self, pointer_id: int, x: float, y: float, timestamp: float
    ) -> Tuple[list[Tuple[str, tuple]], bool]:
        events = []
        should_notify = False
        now = timestamp if timestamp > 0 else time.monotonic()

        if pointer_id not in self.touches:
            return events, False

        pt = self.touches[pointer_id]
        duration = now - pt.start_time
        dist = pt.total_travel + math.hypot(x - pt.current_x, y - pt.current_y)
        was_multi_finger = len(self.touches)

        del self.touches[pointer_id]

        if self.is_dragging:
            if len(self.touches) == 0:
                self.is_dragging = False
                events.append((GestureAction.LEFT_UP, ()))
        elif self._pending_drag_candidate and len(self.touches) == 0:
            self._clear_pending()
            should_notify = True
            if self.tap_to_click:
                if 0.0 <= duration < self.tap_timeout and dist < self.tap_threshold:
                    # Clean double-tap -> emit double left click
                    events.append((GestureAction.LEFT_CLICK, ()))
                    events.append((GestureAction.LEFT_CLICK, ()))
                    self.last_tap_time = 0.0
                else:
                    events.append((GestureAction.LEFT_CLICK, ()))
        elif self.tap_to_click and 0.0 <= duration < self.tap_timeout and dist < self.tap_threshold:
            if was_multi_finger == 1 and self._max_fingers_in_stroke == 1:
                # 1-finger tap -> defer briefly if double-tap-drag is enabled
                if self.double_tap_drag_window > 0.0:
                    self._pending_tap = True
                    self._pending_tap_deadline_ts = now + self.double_tap_drag_window
                    self._pending_tap_deadline_host = time.monotonic() + self.double_tap_drag_window
                    self.last_tap_time = now
                    self.last_tap_pos = (x, y)
                    should_notify = True
                else:
                    events.append((GestureAction.LEFT_CLICK, ()))
                    self.last_tap_time = now
                    self.last_tap_pos = (x, y)
            elif was_multi_finger == 2 and self._max_fingers_in_stroke == 2 and not self._multi_finger_tap_fired:
                # 2-finger tap -> Right Click (fires once per multi-finger tap)
                self._multi_finger_tap_fired = True
                events.append((GestureAction.RIGHT_CLICK, ()))
            elif was_multi_finger == 3 and not self._multi_finger_tap_fired:
                # 3-finger tap -> Middle Click (fires once per multi-finger tap)
                self._multi_finger_tap_fired = True
                events.append((GestureAction.MIDDLE_CLICK, ()))

        # Reset filter anchor when dropping from multi-finger back to 1 finger
        if len(self.touches) == 1 and was_multi_finger > 1:
            self.virtual_pos_x = 0.0
            self.virtual_pos_y = 0.0
            self.prev_filtered_x = 0.0
            self.prev_filtered_y = 0.0
            self.filter.reset()
            self.filter.filter(0.0, 0.0, now)

        # Clean accumulators and stroke state on full release
        if len(self.touches) == 0:
            self.accumulator.reset()
            self.scroll_accumulator.reset()
            self.filter.reset()
            self._prev_pinch_dist = None
            self._three_finger_triggered = False
            self._max_fingers_in_stroke = 0
            self._multi_finger_tap_fired = False

        return events, should_notify
