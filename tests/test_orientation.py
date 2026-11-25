"""Unit tests for Landscape and Portrait orientation modes in GestureEngine."""

import unittest
from cursorly.engine.gestures import GestureEngine, GestureAction, Orientation
from cursorly.engine.ballistics import BallisticsMode


class TestOrientationModes(unittest.TestCase):
    def test_default_orientation(self):
        engine = GestureEngine()
        self.assertEqual(engine.orientation, Orientation.PORTRAIT)

    def test_orientation_toggling(self):
        engine = GestureEngine()
        self.assertEqual(engine.orientation, Orientation.PORTRAIT)

        new_o = engine.toggle_orientation()
        self.assertEqual(new_o, Orientation.LANDSCAPE)
        self.assertEqual(engine.orientation, Orientation.LANDSCAPE)

        new_o = engine.toggle_orientation()
        self.assertEqual(new_o, Orientation.PORTRAIT)
        self.assertEqual(engine.orientation, Orientation.PORTRAIT)

    def test_delta_rotation_math(self):
        engine = GestureEngine()

        # Portrait: identity transformation
        engine.set_orientation(Orientation.PORTRAIT)
        dx, dy = engine._rotate_delta(15.0, 25.0)
        self.assertEqual((dx, dy), (15.0, 25.0))

        # Landscape (90 deg CCW):
        # Swiping physical right (dy_raw > 0) -> +X on PC
        # Swiping physical up (dx_raw > 0) -> -Y on PC
        engine.set_orientation(Orientation.LANDSCAPE)
        dx, dy = engine._rotate_delta(15.0, 25.0)
        self.assertEqual((dx, dy), (25.0, -15.0))

        # Inverted Landscape
        engine.set_orientation(Orientation.LANDSCAPE_INVERTED)
        dx, dy = engine._rotate_delta(15.0, 25.0)
        self.assertEqual((dx, dy), (-25.0, 15.0))

    def test_landscape_mouse_movement(self):
        engine = GestureEngine(orientation=Orientation.LANDSCAPE)
        engine.ballistics.mode = BallisticsMode.LINEAR
        engine.ballistics.set_sensitivity(1.0)

        t = 1.0
        # Start touch
        engine.on_touch_down(pointer_id=1, x=100.0, y=100.0, timestamp=t)

        # Move physically RIGHT on landscape phone (y increases from 100 to 140, x stays 100)
        events = engine.on_touch_move(pointer_id=1, x=100.0, y=140.0, timestamp=t + 0.05)

        move_events = [args for action, args in events if action == GestureAction.MOVE]
        self.assertGreater(len(move_events), 0)
        idx, idy = move_events[0]
        # Moving physical right should result in positive horizontal delta (idx > 0) and zero vertical (idy == 0)
        self.assertGreater(idx, 0)
        self.assertEqual(idy, 0)

    def test_landscape_scroll(self):
        engine = GestureEngine(orientation=Orientation.LANDSCAPE)
        t = 1.0
        engine.on_touch_down(pointer_id=1, x=100.0, y=100.0, timestamp=t)
        engine.on_touch_down(pointer_id=2, x=100.0, y=150.0, timestamp=t)

        # Two-finger drag physically DOWNWARDS on landscape phone (x increases towards left edge)
        # In landscape: physical DOWN swipe means x decreases (or dx_raw < 0)
        engine.on_touch_move(pointer_id=1, x=80.0, y=100.0, timestamp=t + 0.02)
        events = engine.on_touch_move(pointer_id=2, x=80.0, y=150.0, timestamp=t + 0.02)

        scroll_events = [args for action, args in events if action == GestureAction.SCROLL]
        self.assertGreater(len(scroll_events), 0)
        wheel_y, wheel_x = scroll_events[0]
        # Scroll downwards produces positive vertical wheel
        self.assertGreater(wheel_y, 0)


if __name__ == "__main__":
    unittest.main()
