"""Unit tests for Fractional Subpixel Accumulator."""

import unittest
from cursorly.engine.subpixel_accumulator import SubpixelAccumulator


class TestSubpixelAccumulator(unittest.TestCase):
    def test_subpixel_accumulation_conservation(self):
        """Small fractional increments (e.g. 0.35px) must accumulate into exact whole pixels without loss."""
        acc = SubpixelAccumulator()
        total_integer_emitted_x = 0
        total_integer_emitted_y = 0

        step_x = 0.35
        step_y = 0.70
        steps = 100

        for _ in range(steps):
            ix, iy = acc.step(step_x, step_y)
            total_integer_emitted_x += ix
            total_integer_emitted_y += iy

        expected_total_x = round(step_x * steps)  # 35
        expected_total_y = round(step_y * steps)  # 70

        self.assertEqual(total_integer_emitted_x, expected_total_x)
        self.assertEqual(total_integer_emitted_y, expected_total_y)
        self.assertLess(abs(acc.remainder_x), 0.5)
        self.assertLess(abs(acc.remainder_y), 0.5)

    def test_subpixel_negative_motion(self):
        acc = SubpixelAccumulator()
        total_x = 0
        step = -0.4

        for _ in range(10):
            ix, _ = acc.step(step, 0.0)
            total_x += ix

        self.assertEqual(total_x, -4)

    def test_accumulator_reset(self):
        acc = SubpixelAccumulator()
        acc.step(0.49, 0.49)
        self.assertGreater(acc.remainder_x, 0.4)
        acc.reset()
        self.assertEqual(acc.remainder_x, 0.0)
        self.assertEqual(acc.remainder_y, 0.0)


if __name__ == "__main__":
    unittest.main()
