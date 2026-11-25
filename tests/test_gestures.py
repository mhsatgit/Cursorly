"""Unit tests for Ballistics and Gesture Engine."""

import unittest
from cursorly.engine.ballistics import BallisticsEngine, BallisticsMode
from cursorly.engine.gestures import GestureEngine, GestureAction


class TestBallisticsAndGestures(unittest.TestCase):
    def test_ballistics_linear(self):
        b = BallisticsEngine(mode=BallisticsMode.LINEAR, sensitivity=1.5)
        dx, dy = b.apply(10.0, 20.0, dt=0.01)
        self.assertEqual(dx, 15.0)
        self.assertEqual(dy, 30.0)

    def test_ballistics_dynamic_acceleration(self):
        b = BallisticsEngine(mode=BallisticsMode.DYNAMIC, sensitivity=1.0, velocity_threshold=200.0)
        dx_slow, _ = b.apply(10.0, 0.0, dt=0.1)
        self.assertEqual(dx_slow, 10.0)

        dx_fast, _ = b.apply(50.0, 0.0, dt=0.01)
        self.assertGreater(dx_fast, 50.0)

    def test_single_tap_left_click(self):
        # Immediate mode (no drag window)
        engine = GestureEngine(tap_timeout_sec=0.2, tap_distance_threshold=15.0, double_tap_drag_window_sec=0.0)
        t = 1.0
        engine.on_touch_down(pointer_id=1, x=100.0, y=100.0, timestamp=t)
        engine.on_touch_move(pointer_id=1, x=102.0, y=101.0, timestamp=t + 0.05)
        events = engine.on_touch_up(pointer_id=1, x=102.0, y=101.0, timestamp=t + 0.10)
        self.assertTrue(any(action == GestureAction.LEFT_CLICK for action, _ in events))

        # Deferred disambiguation mode: single tap flushes after double_tap_drag_window
        deferred = GestureEngine(tap_timeout_sec=0.2, tap_distance_threshold=15.0, double_tap_drag_window_sec=0.20)
        deferred.on_touch_down(1, 100.0, 100.0, t)
        self.assertEqual(deferred.on_touch_up(1, 100.0, 100.0, t + 0.05), [])
        self.assertEqual(deferred.poll_pending_actions(t + 0.15), [])
        self.assertEqual(deferred.poll_pending_actions(t + 0.26), [(GestureAction.LEFT_CLICK, ())])

    def test_double_tap_vs_double_tap_and_drag(self):
        # 1. Double tap without moving -> emits exactly 2 LEFT_CLICKs on second lift (maximize/minimize)
        engine = GestureEngine(tap_timeout_sec=0.20, double_tap_drag_window_sec=0.20, drag_start_threshold=8.0)
        t = 1.0
        engine.on_touch_down(1, 100.0, 100.0, t)
        self.assertEqual(engine.on_touch_up(1, 100.0, 100.0, t + 0.05), [])
        # Second tap down does NOT prematurely emit LEFT_CLICK or LEFT_DOWN
        self.assertEqual(engine.on_touch_down(1, 101.0, 100.0, t + 0.12), [])
        # Small jitter (< 8dp) suppresses cursor drift during double-tap
        self.assertEqual(engine.on_touch_move(1, 103.0, 100.0, t + 0.14), [])
        up2 = engine.on_touch_up(1, 103.0, 100.0, t + 0.17)
        self.assertEqual(up2, [(GestureAction.LEFT_CLICK, ()), (GestureAction.LEFT_CLICK, ())])
        self.assertFalse(engine.is_dragging)

        # 2. Double tap and move -> cancels Tap 1 click, emits single LEFT_DOWN + MOVEs + LEFT_UP
        engine.reset_state()
        engine.on_touch_down(1, 200.0, 200.0, 5.0)
        self.assertEqual(engine.on_touch_up(1, 200.0, 200.0, 5.05), [])
        self.assertEqual(engine.on_touch_down(1, 200.0, 200.0, 5.12), [])
        drag_events = []
        drag_events.extend(engine.on_touch_move(1, 212.0, 200.0, 5.15))
        drag_events.extend(engine.on_touch_move(1, 235.0, 200.0, 5.18))
        drag_events.extend(engine.on_touch_up(1, 235.0, 200.0, 5.25))
        actions_only = [a for a, _ in drag_events]
        self.assertNotIn(GestureAction.LEFT_CLICK, actions_only)
        self.assertEqual(actions_only.count(GestureAction.LEFT_DOWN), 1)
        self.assertIn(GestureAction.MOVE, actions_only)
        self.assertEqual(actions_only[-1], GestureAction.LEFT_UP)
        self.assertLess(actions_only.index(GestureAction.LEFT_DOWN), actions_only.index(GestureAction.MOVE))

        # 3. Double tap and hold past tap_timeout -> promotes to LEFT_DOWN drag
        engine.reset_state()
        engine.on_touch_down(1, 300.0, 300.0, 10.0)
        engine.on_touch_up(1, 300.0, 300.0, 10.05)
        engine.on_touch_down(1, 300.0, 300.0, 10.12)
        self.assertEqual(engine.poll_pending_actions(10.35), [(GestureAction.LEFT_DOWN, ())])
        self.assertTrue(engine.is_dragging)
        self.assertEqual(engine.on_touch_up(1, 300.0, 300.0, 10.40), [(GestureAction.LEFT_UP, ())])

    def test_two_finger_scroll(self):
        engine = GestureEngine()
        t = 1.0
        engine.on_touch_down(pointer_id=1, x=100.0, y=100.0, timestamp=t)
        engine.on_touch_down(pointer_id=2, x=200.0, y=100.0, timestamp=t)

        engine.on_touch_move(pointer_id=1, x=100.0, y=130.0, timestamp=t + 0.02)
        events = engine.on_touch_move(pointer_id=2, x=200.0, y=130.0, timestamp=t + 0.02)

        scroll_events = [args for action, args in events if action == GestureAction.SCROLL]
        self.assertGreater(len(scroll_events), 0)
        wheel_y, _ = scroll_events[0]
        self.assertGreater(wheel_y, 0)

    def test_one_euro_filter_smooths_jitter_in_gesture_engine(self):
        engine = GestureEngine(min_cutoff=0.5, beta=0.01)
        engine.ballistics.mode = BallisticsMode.LINEAR
        engine.ballistics.set_sensitivity(1.0)

        t = 1.0
        dt = 0.008
        engine.on_touch_down(pointer_id=1, x=100.0, y=100.0, timestamp=t)
        emitted_x = []
        for i in range(40):
            t += dt
            x = 100.0 + (2.0 if i % 2 == 0 else -2.0)
            for action, args in engine.on_touch_move(pointer_id=1, x=x, y=100.0, timestamp=t):
                if action == GestureAction.MOVE:
                    emitted_x.append(args[0])

        # With active 1€ filtering, alternating +/-2px 125Hz jitter is suppressed (even down to 0px emitted)
        total_jitter = sum(abs(dx) for dx in emitted_x)
        self.assertLess(total_jitter, 20)

        # Steady intentional motion still tracks accurately
        engine.on_touch_up(pointer_id=1, x=100.0, y=100.0, timestamp=t + dt)
        t += 0.5
        engine.on_touch_down(pointer_id=1, x=100.0, y=100.0, timestamp=t)
        total_move = 0
        for step in range(1, 31):
            t += dt
            for action, args in engine.on_touch_move(pointer_id=1, x=100.0 + step * 5.0, y=100.0, timestamp=t):
                if action == GestureAction.MOVE:
                    total_move += args[0]
        self.assertGreater(total_move, 120)

    def test_two_finger_scroll_no_double_count_and_subpixel_accumulation(self):
        engine = GestureEngine(scroll_sensitivity=0.5)
        t = 1.0
        engine.on_touch_down(pointer_id=1, x=100.0, y=100.0, timestamp=t)
        engine.on_touch_down(pointer_id=2, x=200.0, y=100.0, timestamp=t)

        # Move only finger 1 by +20px in 2 steps; finger 2 stays stationary (must not replay stale delta)
        total_wheel_y = 0
        for step in range(1, 5):
            for action, args in engine.on_touch_move(1, 100.0, 100.0 + step * 5.0, t + step * 0.01):
                if action == GestureAction.SCROLL:
                    total_wheel_y += args[0]
        # Centroid moved 10px: 10 * 0.5 * 12 = 60
        self.assertEqual(total_wheel_y, 60)

        # Slow sub-unit scroll increments accumulate instead of truncating to 0
        total_slow = 0
        for step in range(1, 11):
            for action, args in engine.on_touch_move(1, 100.0, 120.0 + step * 0.1, t + 0.1 + step * 0.01):
                if action == GestureAction.SCROLL:
                    total_slow += args[0]
        self.assertGreater(total_slow, 0)

    def test_multi_finger_tap_does_not_cascade_to_left_click(self):
        engine = GestureEngine()
        t = 1.0
        engine.on_touch_down(1, 100.0, 100.0, t)
        engine.on_touch_down(2, 150.0, 100.0, t + 0.01)
        events1 = engine.on_touch_up(1, 100.0, 100.0, t + 0.05)
        events2 = engine.on_touch_up(2, 150.0, 100.0, t + 0.06)

        all_actions = [a for a, _ in (events1 + events2)]
        self.assertEqual(all_actions, [GestureAction.RIGHT_CLICK])

        # 3-finger tap emits only MIDDLE_CLICK
        engine.on_touch_down(1, 100.0, 100.0, 2.0)
        engine.on_touch_down(2, 140.0, 100.0, 2.0)
        engine.on_touch_down(3, 180.0, 100.0, 2.0)
        e3 = (
            engine.on_touch_up(1, 100.0, 100.0, 2.05)
            + engine.on_touch_up(2, 140.0, 100.0, 2.06)
            + engine.on_touch_up(3, 180.0, 100.0, 2.07)
        )
        self.assertEqual([a for a, _ in e3], [GestureAction.MIDDLE_CLICK])

    def test_tap_to_click_disabled_and_negative_timestamp_jump(self):
        engine = GestureEngine()
        engine.on_touch_down(1, 100.0, 100.0, 500.0)
        engine.on_touch_up(1, 100.0, 100.0, 500.05)
        # Next client reconnects with smaller timestamp base: must NOT trigger drag
        down_events = engine.on_touch_down(1, 100.0, 100.0, 1.0)
        self.assertEqual(down_events, [])
        self.assertFalse(engine.is_dragging)

        engine.reset_state()
        engine.set_tap_to_click(False)
        engine.on_touch_down(1, 100.0, 100.0, 2.0)
        up_events = engine.on_touch_up(1, 100.0, 100.0, 2.05)
        self.assertEqual(up_events, [])


if __name__ == "__main__":
    unittest.main()
