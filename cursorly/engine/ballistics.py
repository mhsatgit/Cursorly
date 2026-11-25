"""
Dynamic Ballistics and Acceleration Engine.

Transforms raw physical touch deltas into calibrated cursor movement:
- Raw Linear (1:1): Exact spatial displacement.
- Power Ballistics: Dynamic gain curve scaling with speed to allow both
  sub-pixel surgical aiming and effortless multi-monitor flick traversal.
- Precision / Sniper Mode: Instantly scales down sensitivity for micro-aiming,
  CAD drafting, and graphic design tasks.
"""

import math
from enum import Enum
from typing import Tuple


class BallisticsMode(str, Enum):
    LINEAR = "linear"
    DYNAMIC = "dynamic"


class BallisticsEngine:
    """Applies velocity-dependent pointer acceleration, sensitivity scaling, and precision dampening."""

    def __init__(
        self,
        mode: BallisticsMode = BallisticsMode.DYNAMIC,
        sensitivity: float = 0.5,
        gain_factor: float = 1.2,
        velocity_threshold: float = 300.0,
        power_exponent: float = 1.25,
        max_gain: float = 4.0,
        precision_multiplier: float = 0.35,
    ):
        """
        Args:
            mode: LINEAR or DYNAMIC.
            sensitivity: Master sensitivity multiplier (default: 0.5 for controlled tracking).
            gain_factor: Acceleration gain multiplier.
            velocity_threshold: Speed where acceleration begins ramping.
            power_exponent: Power curve exponent (gamma).
            max_gain: Ceiling for acceleration multiplier to prevent runaway cursor.
            precision_multiplier: Multiplier applied when precision/sniper mode is active.
        """
        self.mode = mode
        self.sensitivity = max(0.02, min(5.0, float(sensitivity)))
        self.gain_factor = float(gain_factor)
        self.velocity_threshold = max(1.0, float(velocity_threshold))
        self.power_exponent = float(power_exponent)
        self.max_gain = float(max_gain)
        self.precision_mode: bool = False
        self.precision_multiplier = float(precision_multiplier)

    def set_sensitivity(self, value: float) -> float:
        """Dynamically update base sensitivity, clamped between 0.02 and 5.0."""
        self.sensitivity = max(0.02, min(5.0, float(value)))
        return self.sensitivity

    def toggle_precision_mode(self) -> bool:
        """Toggle precision sniper mode on or off."""
        self.precision_mode = not self.precision_mode
        return self.precision_mode

    def get_effective_sensitivity(self) -> float:
        """Returns the actual sensitivity multiplier accounting for precision mode."""
        dampener = self.precision_multiplier if self.precision_mode else 1.0
        return self.sensitivity * dampener

    def apply(self, dx: float, dy: float, dt: float) -> Tuple[float, float]:
        """
        Apply ballistics curve to delta coordinates over time interval dt.

        Args:
            dx: Raw horizontal delta in screen units.
            dy: Raw vertical delta in screen units.
            dt: Time elapsed in seconds.

        Returns:
            Accelerated and sensitivity-scaled (scaled_dx, scaled_dy).
        """
        effective_sens = self.get_effective_sensitivity()

        if self.mode == BallisticsMode.LINEAR or dt <= 1e-6:
            return dx * effective_sens, dy * effective_sens

        # Calculate instantaneous speed in units/sec
        dist = math.hypot(dx, dy)
        speed = dist / dt

        # When precision mode is active, prevent acceleration spikes
        thresh = self.velocity_threshold * (1.5 if self.precision_mode else 1.0)

        if speed <= thresh:
            gain = 1.0
        else:
            ratio = (speed - thresh) / thresh
            gain = 1.0 + self.gain_factor * math.pow(ratio, self.power_exponent)
            gain = min(gain, self.max_gain)

        total_multiplier = gain * effective_sens
        return dx * total_multiplier, dy * total_multiplier
