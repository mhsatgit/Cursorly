"""Unit tests for One Euro (1€) Adaptive Filter."""

import unittest
from cursorly.engine.filter_one_euro import OneEuroFilter1D, OneEuroFilter2D


class TestOneEuroFilter(unittest.TestCase):
    def test_one_euro_filter_initialization(self):
        f = OneEuroFilter1D(min_cutoff=0.8, beta=0.05, d_cutoff=1.0)
        self.assertEqual(f.min_cutoff, 0.8)
        self.assertEqual(f.beta, 0.05)
        self.assertEqual(f.d_cutoff, 1.0)

    def test_filter_first_sample_passthrough(self):
        f = OneEuroFilter1D()
        val = f.filter(100.0, timestamp=1.0)
        self.assertEqual(val, 100.0)

    def test_stationary_jitter_suppression(self):
        """At low speed (stationary hand with noise), jitter should be heavily smoothed."""
        f = OneEuroFilter1D(min_cutoff=0.5, beta=0.01)
        t = 0.0
        dt = 0.008  # 125 Hz
        base_pos = 500.0

        for _ in range(20):
            t += dt
            f.filter(base_pos, t)

        filtered_vals = []
        for i in range(100):
            t += dt
            noise = 2.0 if (i % 2 == 0) else -2.0
            noisy_val = base_pos + noise
            out = f.filter(noisy_val, t)
            filtered_vals.append(out)

        raw_range = 4.0
        filt_range = max(filtered_vals) - min(filtered_vals)
        self.assertLess(filt_range, raw_range * 0.45)

    def test_high_speed_responsiveness(self):
        """During high-speed swipe, filter cutoff opens up and tracks closely with low lag."""
        f = OneEuroFilter1D(min_cutoff=0.8, beta=0.1)
        t = 0.0
        dt = 0.008
        pos = 0.0
        velocity = 2000.0  # 2000 px/sec swipe

        f.filter(pos, t)
        diffs = []
        for _ in range(50):
            t += dt
            pos += velocity * dt
            out = f.filter(pos, t)
            diffs.append(abs(pos - out))

        avg_lag = sum(diffs[-20:]) / 20.0
        self.assertLess(avg_lag, velocity * dt * 2.0)

    def test_2d_filter_vector(self):
        f2d = OneEuroFilter2D(min_cutoff=1.0, beta=0.05)
        fx, fy = f2d.filter(100.0, 200.0, timestamp=1.0)
        self.assertEqual(fx, 100.0)
        self.assertEqual(fy, 200.0)

        f2d.reset()
        self.assertIsNone(f2d.fx.t_prev)
        self.assertIsNone(f2d.fy.t_prev)


if __name__ == "__main__":
    unittest.main()
