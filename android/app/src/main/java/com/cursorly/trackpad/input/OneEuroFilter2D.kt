package com.cursorly.trackpad.input

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

class LowPassFilter(private var alpha: Double = 1.0) {
    var hatXPrev: Double? = null
        private set

    fun setAlpha(alpha: Double) {
        this.alpha = alpha.coerceIn(0.0, 1.0)
    }

    fun filter(x: Double): Double {
        val prev = hatXPrev
        if (prev == null) {
            hatXPrev = x
            return x
        }
        val hatX = alpha * x + (1.0 - alpha) * prev
        hatXPrev = hatX
        return hatX
    }

    fun reset() {
        hatXPrev = null
    }
}

class OneEuroFilter1D(
    var minCutoff: Double = 0.8,
    var beta: Double = 0.06,
    var dCutoff: Double = 1.0,
) {
    private val xFilter = LowPassFilter()
    private val dxFilter = LowPassFilter()
    private var tPrev: Double? = null
    private var xPrev: Double? = null

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun filter(x: Double, timestampSec: Double): Double {
        val prevT = tPrev
        if (prevT == null) {
            tPrev = timestampSec
            xPrev = x
            return xFilter.filter(x)
        }

        val dt = timestampSec - prevT
        if (dt <= 1e-6) {
            return xFilter.hatXPrev ?: x
        }

        val dx = ((x - (xPrev ?: x)) / dt)
        dxFilter.setAlpha(alpha(dCutoff, dt))
        val hatDx = dxFilter.filter(dx)

        val cutoff = minCutoff + beta * abs(hatDx)
        xFilter.setAlpha(alpha(cutoff, dt))
        val hatX = xFilter.filter(x)

        tPrev = timestampSec
        xPrev = x
        return hatX
    }

    fun reset() {
        xFilter.reset()
        dxFilter.reset()
        tPrev = null
        xPrev = null
    }
}

class OneEuroFilter2D(
    minCutoff: Double = 0.8,
    beta: Double = 0.06,
    dCutoff: Double = 1.0,
) {
    private val fx = OneEuroFilter1D(minCutoff, beta, dCutoff)
    private val fy = OneEuroFilter1D(minCutoff, beta, dCutoff)

    fun filter(x: Double, y: Double, timestampSec: Double): Pair<Double, Double> =
        fx.filter(x, timestampSec) to fy.filter(y, timestampSec)

    fun reset() {
        fx.reset()
        fy.reset()
    }
}

class SubpixelAccumulator {
    private var remX = 0.0
    private var remY = 0.0

    fun step(dx: Double, dy: Double): Pair<Int, Int> {
        remX += dx
        remY += dy
        val intX = remX.roundToInt()
        val intY = remY.roundToInt()
        remX -= intX
        remY -= intY
        return intX to intY
    }

    fun reset() {
        remX = 0.0
        remY = 0.0
    }
}
