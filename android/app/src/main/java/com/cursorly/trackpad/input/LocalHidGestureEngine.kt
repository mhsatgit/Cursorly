package com.cursorly.trackpad.input

import com.cursorly.trackpad.model.TrackpadOrientation
import kotlin.math.hypot
import kotlin.math.roundToInt

data class HidReportAction(
    val buttons: Int,
    val dx: Int = 0,
    val dy: Int = 0,
    val wheelV: Int = 0,
    val wheelH: Int = 0,
)

/**
 * Local gesture engine used when running in Standalone Bluetooth HID Mode
 * (where Android talks directly to Windows Bluetooth stack without the Python host).
 */
class LocalHidGestureEngine(
    private val tapTimeoutSec: Double = 0.20,
    private val tapDistanceThreshold: Float = 18f,
    private val doubleTapDragWindowSec: Double = 0.20,
    private val dragStartThreshold: Float = 8f,
) {
    private data class TouchPoint(
        val id: Int,
        val startX: Float,
        val startY: Float,
        var currentX: Float,
        var currentY: Float,
        var prevX: Float,
        var prevY: Float,
        val startTime: Double,
        var lastTime: Double,
        var totalTravel: Float = 0f,
    )

    private val touches = LinkedHashMap<Int, TouchPoint>()
    private val filter = OneEuroFilter2D(minCutoff = 0.8, beta = 0.06)
    private val accumulator = SubpixelAccumulator()
    private val scrollAccumulator = SubpixelAccumulator()

    private var virtualX = 0.0
    private var virtualY = 0.0
    private var prevFilteredX = 0.0
    private var prevFilteredY = 0.0

    private var lastTapTime = 0.0
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var isDragging = false
    private var explicitButtonMask = 0
    private var maxFingersInStroke = 0
    private var multiFingerTapFired = false

    private var pendingTap = false
    private var pendingTapDeadlineTs: Double? = null
    private var pendingTapDeadlineHostMs: Long? = null
    private var pendingDragCandidate = false
    private var pendingDragDeadlineTs: Double? = null
    private var pendingDragDeadlineHostMs: Long? = null

    var sensitivity: Float = 0.50f
    var precisionMode: Boolean = false
    var orientation: TrackpadOrientation = TrackpadOrientation.PORTRAIT
    var naturalScroll: Boolean = false
    var tapToClick: Boolean = true
        set(value) {
            field = value
            if (!value) clearPending()
        }

    private fun clearPending() {
        pendingTap = false
        pendingTapDeadlineTs = null
        pendingTapDeadlineHostMs = null
        pendingDragCandidate = false
        pendingDragDeadlineTs = null
        pendingDragDeadlineHostMs = null
    }

    fun nextPendingDelayMs(): Long? {
        val nowMs = System.currentTimeMillis()
        val deadlines = listOfNotNull(pendingTapDeadlineHostMs, pendingDragDeadlineHostMs)
        val earliest = deadlines.minOrNull() ?: return null
        return (earliest - nowMs).coerceAtLeast(0L)
    }

    fun pollPendingActions(timestampSec: Double? = null): List<HidReportAction> {
        val nowMs = System.currentTimeMillis()
        if (pendingTap) {
            val expired = if (timestampSec != null && pendingTapDeadlineTs != null) {
                timestampSec >= pendingTapDeadlineTs!!
            } else {
                pendingTapDeadlineHostMs != null && nowMs >= pendingTapDeadlineHostMs!!
            }
            if (expired) {
                clearPending()
                return listOf(
                    HidReportAction(buttons = currentButtonMask() or 0x01),
                    HidReportAction(buttons = currentButtonMask()),
                )
            }
        }
        if (pendingDragCandidate) {
            val expired = if (timestampSec != null && pendingDragDeadlineTs != null) {
                timestampSec >= pendingDragDeadlineTs!!
            } else {
                pendingDragDeadlineHostMs != null && nowMs >= pendingDragDeadlineHostMs!!
            }
            if (expired && touches.size == 1 && !isDragging) {
                clearPending()
                isDragging = true
                virtualX = 0.0
                virtualY = 0.0
                prevFilteredX = 0.0
                prevFilteredY = 0.0
                accumulator.reset()
                return listOf(HidReportAction(buttons = currentButtonMask()))
            }
        }
        return emptyList()
    }

    fun reset() {
        touches.clear()
        clearPending()
        virtualX = 0.0
        virtualY = 0.0
        prevFilteredX = 0.0
        prevFilteredY = 0.0
        isDragging = false
        explicitButtonMask = 0
        maxFingersInStroke = 0
        multiFingerTapFired = false
        accumulator.reset()
        scrollAccumulator.reset()
        filter.reset()
    }

    private fun currentButtonMask(): Int =
        explicitButtonMask or (if (isDragging) 0x01 else 0x00)

    private fun rotateDelta(dx: Float, dy: Float): Pair<Float, Float> = when (orientation) {
        TrackpadOrientation.PORTRAIT -> dx to dy
        TrackpadOrientation.LANDSCAPE -> dy to -dx
    }

    private fun effectiveSensitivity(): Float =
        sensitivity * (if (precisionMode) 0.35f else 1.0f)

    fun setExplicitButton(maskBit: Int, pressed: Boolean): HidReportAction {
        explicitButtonMask = if (pressed) {
            explicitButtonMask or maskBit
        } else {
            explicitButtonMask and maskBit.inv()
        }
        return HidReportAction(buttons = currentButtonMask())
    }

    fun onTouchDown(pointerId: Int, x: Float, y: Float, timestampSec: Double): List<HidReportAction> {
        val actions = mutableListOf<HidReportAction>()
        touches[pointerId] = TouchPoint(
            id = pointerId,
            startX = x,
            startY = y,
            currentX = x,
            currentY = y,
            prevX = x,
            prevY = y,
            startTime = timestampSec,
            lastTime = timestampSec,
        )
        if (touches.size > maxFingersInStroke) {
            maxFingersInStroke = touches.size
        }

        if (touches.size == 1) {
            maxFingersInStroke = 1
            multiFingerTapFired = false
            val dtTap = timestampSec - lastTapTime
            val distTap = hypot(x - lastTapX, y - lastTapY)
            if (pendingTap) {
                if (tapToClick && dtTap >= 0.0 && dtTap < doubleTapDragWindowSec && distTap < tapDistanceThreshold * 1.5f) {
                    pendingTap = false
                    pendingTapDeadlineTs = null
                    pendingTapDeadlineHostMs = null
                    pendingDragCandidate = true
                    pendingDragDeadlineTs = timestampSec + tapTimeoutSec
                    pendingDragDeadlineHostMs = System.currentTimeMillis() + (tapTimeoutSec * 1000.0).toLong()
                } else {
                    clearPending()
                    if (tapToClick && dtTap >= 0.0) {
                        actions += HidReportAction(buttons = currentButtonMask() or 0x01)
                        actions += HidReportAction(buttons = currentButtonMask())
                    }
                }
            }
            virtualX = 0.0
            virtualY = 0.0
            prevFilteredX = 0.0
            prevFilteredY = 0.0
            filter.reset()
            filter.filter(0.0, 0.0, timestampSec)
        } else if (touches.size >= 2) {
            if (pendingTap || pendingDragCandidate) {
                if (pendingTap && tapToClick) {
                    actions += HidReportAction(buttons = currentButtonMask() or 0x01)
                    actions += HidReportAction(buttons = currentButtonMask())
                }
                clearPending()
            }
        }
        return actions
    }

    fun onTouchMove(pointerId: Int, x: Float, y: Float, timestampSec: Double): List<HidReportAction> {
        val pt = touches[pointerId] ?: return emptyList()
        val dxRaw = x - pt.currentX
        val dyRaw = y - pt.currentY
        pt.prevX = pt.currentX
        pt.prevY = pt.currentY
        pt.currentX = x
        pt.currentY = y
        pt.lastTime = timestampSec
        pt.totalTravel += hypot(dxRaw, dyRaw)

        val actions = mutableListOf<HidReportAction>()
        if (touches.size == 1) {
            if (pendingDragCandidate) {
                if (pt.totalTravel >= dragStartThreshold || (timestampSec - pt.startTime) >= tapTimeoutSec) {
                    clearPending()
                    isDragging = true
                    actions += HidReportAction(buttons = currentButtonMask())
                    virtualX = 0.0
                    virtualY = 0.0
                    prevFilteredX = 0.0
                    prevFilteredY = 0.0
                    filter.reset()
                    filter.filter(0.0, 0.0, if (pt.startTime < timestampSec) pt.startTime else timestampSec - 1e-3)
                    accumulator.reset()
                } else {
                    return actions
                }
            }

            val (dxRot, dyRot) = rotateDelta(dxRaw, dyRaw)
            virtualX += dxRot
            virtualY += dyRot
            val (fx, fy) = filter.filter(virtualX, virtualY, timestampSec)
            val dxFilt = fx - prevFilteredX
            val dyFilt = fy - prevFilteredY
            prevFilteredX = fx
            prevFilteredY = fy

            val scale = effectiveSensitivity().toDouble() * 1.6
            val (ix, iy) = accumulator.step(dxFilt * scale, dyFilt * scale)
            if (ix != 0 || iy != 0) {
                emitSplitMotion(ix, iy, actions)
            }
        } else if (touches.size == 2) {
            val avgDx = dxRaw / 2f
            val avgDy = dyRaw / 2f
            var (sdx, sdy) = rotateDelta(avgDx, avgDy)
            if (naturalScroll) {
                sdx = -sdx
                sdy = -sdy
            }
            val (stepV, stepH) = scrollAccumulator.step(sdy * 0.15, sdx * 0.15)
            val wheelV = stepV.coerceIn(-127, 127)
            val wheelH = stepH.coerceIn(-127, 127)
            if (wheelV != 0 || wheelH != 0) {
                actions += HidReportAction(buttons = currentButtonMask(), wheelV = wheelV, wheelH = wheelH)
            }
        }
        return actions
    }

    fun onTouchUp(pointerId: Int, x: Float, y: Float, timestampSec: Double): List<HidReportAction> {
        val pt = touches[pointerId] ?: return emptyList()
        val duration = timestampSec - pt.startTime
        val dist = pt.totalTravel + hypot(x - pt.currentX, y - pt.currentY)
        val wasCount = touches.size
        touches.remove(pointerId)

        val actions = mutableListOf<HidReportAction>()
        if (isDragging) {
            if (touches.isEmpty()) {
                isDragging = false
                actions += HidReportAction(buttons = currentButtonMask())
            }
        } else if (pendingDragCandidate && touches.isEmpty()) {
            clearPending()
            if (tapToClick) {
                if (duration >= 0.0 && duration < tapTimeoutSec && dist < tapDistanceThreshold) {
                    actions += HidReportAction(buttons = currentButtonMask() or 0x01)
                    actions += HidReportAction(buttons = currentButtonMask())
                    actions += HidReportAction(buttons = currentButtonMask() or 0x01)
                    actions += HidReportAction(buttons = currentButtonMask())
                    lastTapTime = 0.0
                } else {
                    actions += HidReportAction(buttons = currentButtonMask() or 0x01)
                    actions += HidReportAction(buttons = currentButtonMask())
                }
            }
        } else if (tapToClick && duration >= 0.0 && duration < tapTimeoutSec && dist < tapDistanceThreshold) {
            when (wasCount) {
                1 -> if (maxFingersInStroke == 1) {
                    if (doubleTapDragWindowSec > 0.0) {
                        pendingTap = true
                        pendingTapDeadlineTs = timestampSec + doubleTapDragWindowSec
                        pendingTapDeadlineHostMs = System.currentTimeMillis() + (doubleTapDragWindowSec * 1000.0).toLong()
                        lastTapTime = timestampSec
                        lastTapX = x
                        lastTapY = y
                    } else {
                        actions += HidReportAction(buttons = currentButtonMask() or 0x01)
                        actions += HidReportAction(buttons = currentButtonMask())
                        lastTapTime = timestampSec
                        lastTapX = x
                        lastTapY = y
                    }
                }
                2 -> if (maxFingersInStroke == 2 && !multiFingerTapFired) {
                    multiFingerTapFired = true
                    actions += HidReportAction(buttons = currentButtonMask() or 0x02)
                    actions += HidReportAction(buttons = currentButtonMask())
                }
                3 -> if (!multiFingerTapFired) {
                    multiFingerTapFired = true
                    actions += HidReportAction(buttons = currentButtonMask() or 0x04)
                    actions += HidReportAction(buttons = currentButtonMask())
                }
            }
        }

        if (touches.size == 1 && wasCount > 1) {
            virtualX = 0.0
            virtualY = 0.0
            prevFilteredX = 0.0
            prevFilteredY = 0.0
            filter.reset()
            filter.filter(0.0, 0.0, timestampSec)
        }

        if (touches.isEmpty()) {
            accumulator.reset()
            scrollAccumulator.reset()
            filter.reset()
            maxFingersInStroke = 0
            multiFingerTapFired = false
        }
        return actions
    }

    private fun emitSplitMotion(totalDx: Int, totalDy: Int, out: MutableList<HidReportAction>) {
        var rx = totalDx
        var ry = totalDy
        while (rx != 0 || ry != 0) {
            val stepX = rx.coerceIn(-127, 127)
            val stepY = ry.coerceIn(-127, 127)
            out += HidReportAction(buttons = currentButtonMask(), dx = stepX, dy = stepY)
            rx -= stepX
            ry -= stepY
        }
    }
}
