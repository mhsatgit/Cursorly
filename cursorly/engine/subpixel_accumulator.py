"""
Fractional Sub-Pixel Accumulator (Error Diffusion Engine).

Windows SendInput takes integer coordinate movements (LONG dx, dy).
When mouse movements are fractional (e.g. 0.35 pixels per sample),
casting or truncating to integers results in lost motion and stutter.
This accumulator retains fractional remainders and diffuses them across
subsequent ticks, guaranteeing 100% motion conservation with zero drift.
"""

from typing import Tuple


class SubpixelAccumulator:
    """Conserves fractional pointer deltas across discrete integer SendInput calls."""

    def __init__(self):
        self.remainder_x: float = 0.0
        self.remainder_y: float = 0.0

    def step(self, delta_x: float, delta_y: float) -> Tuple[int, int]:
        """
        Accumulates fractional deltas and returns discrete integer pixels to move.

        Args:
            delta_x: Exact floating-point horizontal movement.
            delta_y: Exact floating-point vertical movement.

        Returns:
            Tuple of (int_dx, int_dy) to pass to Win32 SendInput.
        """
        self.remainder_x += delta_x
        self.remainder_y += delta_y

        # Round to nearest integer with symmetrical round-half-to-even or standard round
        int_dx = int(round(self.remainder_x))
        int_dy = int(round(self.remainder_y))

        # Subtract only the integer part actually emitted
        self.remainder_x -= int_dx
        self.remainder_y -= int_dy

        return int_dx, int_dy

    def reset(self) -> None:
        """Clear remainders (e.g. when touch lifted or new drag begins)."""
        self.remainder_x = 0.0
        self.remainder_y = 0.0
