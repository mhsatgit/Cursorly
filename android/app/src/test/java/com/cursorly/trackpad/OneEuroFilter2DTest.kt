package com.cursorly.trackpad.input

import com.cursorly.trackpad.model.TrackpadOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneEuroFilter2DTest {

    @Test
    fun subpixelAccumulatorConservesFractionalMotion() {
        val acc = SubpixelAccumulator()
        var totalX = 0
        repeat(20) {
            val (dx, _) = acc.step(0.35, 0.0)
            totalX += dx
        }
        assertEquals(7, totalX) // 20 * 0.35 == 7.0 conserved with zero loss
    }

    @Test
    fun localHidGestureEngineSupportsTapScrollAndLandscape() {
        val engine = LocalHidGestureEngine()
        engine.sensitivity = 1.0f

        // 1-finger tap -> deferred until double-tap window elapses, then emits press + release
        engine.onTouchDown(1, 100f, 100f, 1.0)
        val tapUp = engine.onTouchUp(1, 101f, 100f, 1.05)
        assertTrue(tapUp.isEmpty())
        val flushed = engine.pollPendingActions(1.30)
        assertEquals(2, flushed.size)
        assertEquals(0x01, flushed[0].buttons)
        assertEquals(0x00, flushed[1].buttons)

        // Landscape rotation: moving +y on portrait coordinates moves +dx on PC
        engine.orientation = TrackpadOrientation.LANDSCAPE
        engine.onTouchDown(1, 100f, 100f, 2.0)
        val move = engine.onTouchMove(1, 100f, 140f, 2.05)
        assertTrue(move.isNotEmpty())
        assertTrue(move.sumOf { it.dx } > 0)
    }

    @Test
    fun localHidDoubleTapAndDragVsDoubleClickAndExplicitButtonWithMove() {
        val engine = LocalHidGestureEngine()
        engine.sensitivity = 1.0f

        // Double tap without moving -> 2 clicks (4 reports: down/up/down/up)
        engine.onTouchDown(1, 100f, 100f, 1.0)
        assertTrue(engine.onTouchUp(1, 100f, 100f, 1.05).isEmpty())
        assertTrue(engine.onTouchDown(1, 101f, 100f, 1.12).isEmpty())
        val dblUp = engine.onTouchUp(1, 101f, 100f, 1.17)
        assertEquals(4, dblUp.size)
        assertEquals(listOf(0x01, 0x00, 0x01, 0x00), dblUp.map { it.buttons })

        // Double tap and drag -> single left down, motion with button held, then release
        engine.onTouchDown(1, 200f, 200f, 2.0)
        engine.onTouchUp(1, 200f, 200f, 2.05)
        assertTrue(engine.onTouchDown(1, 200f, 200f, 2.12).isEmpty())
        val dragMove = engine.onTouchMove(1, 215f, 200f, 2.16) + engine.onTouchMove(1, 235f, 200f, 2.20)
        assertTrue(dragMove.isNotEmpty())
        assertTrue(dragMove.all { (it.buttons and 0x01) != 0 })
        val dragUp = engine.onTouchUp(1, 235f, 200f, 2.25)
        assertEquals(1, dragUp.size)
        assertEquals(0x00, dragUp[0].buttons)

        // Explicit right-button hold while moving pointer
        val rightDown = engine.setExplicitButton(0x02, true)
        assertEquals(0x02, rightDown.buttons)
        engine.onTouchDown(1, 100f, 100f, 3.0)
        val rightDragMove = engine.onTouchMove(1, 140f, 100f, 3.05)
        assertTrue(rightDragMove.isNotEmpty())
        assertTrue(rightDragMove.all { it.buttons == 0x02 && it.dx > 0 })
        val rightUp = engine.setExplicitButton(0x02, false)
        assertEquals(0x00, rightUp.buttons)
    }

    @Test
    fun localHidTwoFingerTapDoesNotCascadeAndSlowScrollAccumulates() {
        val engine = LocalHidGestureEngine()
        engine.onTouchDown(1, 100f, 100f, 1.0)
        engine.onTouchDown(2, 150f, 100f, 1.01)
        val up1 = engine.onTouchUp(1, 100f, 100f, 1.05)
        val up2 = engine.onTouchUp(2, 150f, 100f, 1.06)
        // Right click (press+release) on first lift, no extra left click on second lift
        assertEquals(2, up1.size)
        assertEquals(0x02, up1[0].buttons)
        assertEquals(0x00, up1[1].buttons)
        assertTrue(up2.isEmpty())

        // Slow 2-finger scroll accumulates sub-unit increments
        engine.onTouchDown(1, 100f, 100f, 2.0)
        engine.onTouchDown(2, 150f, 100f, 2.0)
        var totalWheel = 0
        for (i in 1..10) {
            val ev = engine.onTouchMove(1, 100f, 100f + i * 2f, 2.0 + i * 0.01)
            totalWheel += ev.sumOf { it.wheelV }
        }
        // Centroid moved 10dp -> 10 * 0.15 = 1.5 -> 2 wheel ticks total (with remainder preserved)
        assertTrue(totalWheel > 0)
    }
}
