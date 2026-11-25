"""
One Euro (1€) Adaptive Filter.
Based on the CHI 2012 paper by Géry Casiez, Nicolas Roussel, and Daniel Vogel:
'1 € Filter: A Simple Speed-based Low-pass Filter for Noisy Input in Human-Computer Interaction'.

Dynamically adjusts cutoff frequency based on instantaneous speed to eliminate jitter
at low speeds (precise pointing) and minimize lag at high speeds (swipes and flicks).
"""

import math
from typing import Optional, Tuple


class LowPassFilter:
    """Standard first-order Exponential Moving Average (EMA) low-pass filter."""

    def __init__(self, alpha: float = 1.0):
        self.alpha = alpha
        self.hat_x_prev: Optional[float] = None

    def set_alpha(self, alpha: float) -> None:
        self.alpha = max(0.0, min(1.0, alpha))

    def filter(self, x: float) -> float:
        if self.hat_x_prev is None:
            self.hat_x_prev = x
            return x
        hat_x = self.alpha * x + (1.0 - self.alpha) * self.hat_x_prev
        self.hat_x_prev = hat_x
        return hat_x

    def reset(self) -> None:
        self.hat_x_prev = None


class OneEuroFilter1D:
    """1D One Euro Filter."""

    def __init__(
        self,
        min_cutoff: float = 0.8,
        beta: float = 0.05,
        d_cutoff: float = 1.0,
    ):
        """
        Args:
            min_cutoff: Minimum cutoff frequency in Hz when stationary (lower = less jitter).
            beta: Speed sensitivity coefficient (higher = less lag during fast movement).
            d_cutoff: Cutoff frequency for derivative smoothing in Hz.
        """
        self.min_cutoff = float(min_cutoff)
        self.beta = float(beta)
        self.d_cutoff = float(d_cutoff)

        self.x_filter = LowPassFilter()
        self.dx_filter = LowPassFilter()
        self.t_prev: Optional[float] = None
        self.x_prev: Optional[float] = None

    @staticmethod
    def _alpha(cutoff: float, dt: float) -> float:
        """Calculate smoothing factor alpha from cutoff frequency and delta time."""
        tau = 1.0 / (2.0 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / dt)

    def filter(self, x: float, timestamp: float) -> float:
        """
        Filter a new coordinate sample.

        Args:
            x: Input value.
            timestamp: Monotonic timestamp in seconds.

        Returns:
            Filtered value.
        """
        if self.t_prev is None:
            self.t_prev = timestamp
            self.x_prev = x
            return self.x_filter.filter(x)

        dt = timestamp - self.t_prev
        if dt <= 1e-6:
            # Guard against duplicate or infinitesimal dt timestamps
            return self.x_filter.hat_x_prev if self.x_filter.hat_x_prev is not None else x

        # 1. Estimate filtered derivative (speed)
        dx = (x - self.x_prev) / dt if self.x_prev is not None else 0.0
        alpha_d = self._alpha(self.d_cutoff, dt)
        self.dx_filter.set_alpha(alpha_d)
        hat_dx = self.dx_filter.filter(dx)

        # 2. Compute dynamic cutoff based on speed
        cutoff = self.min_cutoff + self.beta * abs(hat_dx)

        # 3. Filter position
        alpha = self._alpha(cutoff, dt)
        self.x_filter.set_alpha(alpha)
        hat_x = self.x_filter.filter(x)

        self.t_prev = timestamp
        self.x_prev = x
        return hat_x

    def reset(self) -> None:
        """Reset filter state on touch up or new gesture."""
        self.x_filter.reset()
        self.dx_filter.reset()
        self.t_prev = None
        self.x_prev = None


class OneEuroFilter2D:
    """2D One Euro Filter for (x, y) coordinates."""

    def __init__(
        self,
        min_cutoff: float = 0.8,
        beta: float = 0.06,
        d_cutoff: float = 1.0,
    ):
        self.fx = OneEuroFilter1D(min_cutoff, beta, d_cutoff)
        self.fy = OneEuroFilter1D(min_cutoff, beta, d_cutoff)

    def filter(self, x: float, y: float, timestamp: float) -> Tuple[float, float]:
        fx = self.fx.filter(x, timestamp)
        fy = self.fy.filter(y, timestamp)
        return fx, fy

    def reset(self) -> None:
        self.fx.reset()
        self.fy.reset()

    def update_params(self, min_cutoff: Optional[float] = None, beta: Optional[float] = None) -> None:
        if min_cutoff is not None:
            self.fx.min_cutoff = min_cutoff
            self.fy.min_cutoff = min_cutoff
        if beta is not None:
            self.fx.beta = beta
            self.fy.beta = beta
