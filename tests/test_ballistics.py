"""Unit tests for Ballistics Engine sensitivity and precision modes."""

import unittest
from cursorly.engine.ballistics import BallisticsEngine, BallisticsMode


class TestBallisticsSensitivity(unittest.TestCase):
    def test_default_sensitivity(self):
        b = BallisticsEngine()
        self.assertEqual(b.sensitivity, 0.5)
        self.assertEqual(b.get_effective_sensitivity(), 0.5)

    def test_set_sensitivity(self):
        b = BallisticsEngine()
        b.set_sensitivity(0.25)
        self.assertEqual(b.sensitivity, 0.25)
        self.assertEqual(b.get_effective_sensitivity(), 0.25)

        # Clamping upper and lower bounds
        b.set_sensitivity(10.0)
        self.assertEqual(b.sensitivity, 5.0)

        b.set_sensitivity(-1.0)
        self.assertEqual(b.sensitivity, 0.02)

    def test_precision_mode_dampening(self):
        b = BallisticsEngine(mode=BallisticsMode.LINEAR, sensitivity=0.5, precision_multiplier=0.35)
        self.assertFalse(b.precision_mode)
        dx_normal, dy_normal = b.apply(10.0, 20.0, dt=0.01)
        self.assertEqual(dx_normal, 5.0)
        self.assertEqual(dy_normal, 10.0)

        # Toggle Precision / Sniper Mode
        is_active = b.toggle_precision_mode()
        self.assertTrue(is_active)
        self.assertTrue(b.precision_mode)
        self.assertAlmostEqual(b.get_effective_sensitivity(), 0.5 * 0.35)

        dx_sniper, dy_sniper = b.apply(10.0, 20.0, dt=0.01)
        self.assertAlmostEqual(dx_sniper, 5.0 * 0.35)
        self.assertAlmostEqual(dy_sniper, 10.0 * 0.35)

    def test_low_sensitivity_power_ballistics(self):
        b = BallisticsEngine(mode=BallisticsMode.DYNAMIC, sensitivity=0.2, velocity_threshold=200.0)
        dx_slow, _ = b.apply(10.0, 0.0, dt=0.1)
        self.assertAlmostEqual(dx_slow, 2.0)  # 10 * 0.2


if __name__ == "__main__":
    unittest.main()
