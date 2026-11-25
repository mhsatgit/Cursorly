package com.cursorly.trackpad.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cursorly.trackpad.protocol.PacketCodec
import kotlin.math.roundToInt

/**
 * Low-latency raw MotionEvent surface that requests unbuffered dispatch and replays
 * every coalesced historical touch sample at native digitizer frequency (120Hz-360Hz+).
 */
class TouchSurfaceView(
    context: Context,
    var onTouchSample: (eventType: Int, pointerId: Int, xDp: Float, yDp: Float, timestampSec: Double) -> Unit,
) : View(context) {

    private data class ActiveFinger(var xPx: Float, var yPx: Float)

    private val activeFingers = LinkedHashMap<Int, ActiveFinger>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A94A3B8
        style = Paint.Style.FILL
    }
    private val fingerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5538BDF8
        style = Paint.Style.FILL
    }
    private val fingerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44F1F5F9
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5594A3B8
        textAlign = Paint.Align.CENTER
    }

    init {
        isFocusable = true
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val radius = (maxOf(w, h) * 0.65f).coerceAtLeast(1f)
            bgPaint.shader = RadialGradient(
                w / 2f,
                h / 2f,
                radius,
                intArrayOf(0xFF1E293B.toInt(), 0xFF0F172A.toInt(), 0xFF0B0F19.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Subtle tactile dot grid
        val spacing = 48f * resources.displayMetrics.density
        if (spacing > 8f) {
            var y = spacing / 2f
            while (y < height) {
                var x = spacing / 2f
                while (x < width) {
                    canvas.drawCircle(x, y, 2f, gridPaint)
                    x += spacing
                }
                y += spacing
            }
        }

        // Subtle centered gesture legend (drawn directly on canvas so no overlay intercepts touches)
        val density = resources.displayMetrics.density
        titlePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
        guidePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        val lineStep = 17f * density
        val centerX = width / 2f
        val startY = height / 2f - lineStep * 1.2f
        canvas.drawText("Cursorly", centerX, startY, titlePaint)
        val lines = listOf(
            "1 Finger: Move & Tap · Double-Tap & Drag",
            "2 Fingers: Scroll, Pinch Zoom & Right Click",
            "3 Fingers: Middle Click & Desktop Switch",
        )
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, centerX, startY + (i + 1.35f) * lineStep, guidePaint)
        }

        // Draw active finger rings
        val ringRadius = 26f * density
        for (finger in activeFingers.values) {
            canvas.drawCircle(finger.xPx, finger.yPx, ringRadius, fingerFillPaint)
            canvas.drawCircle(finger.xPx, finger.yPx, ringRadius, fingerStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Disable VSYNC touch batching for sub-frame hardware digitizer rate
                requestUnbufferedDispatch(event)
                parent?.requestDisallowInterceptTouchEvent(true)
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                activeFingers[pid] = ActiveFinger(x, y)
                onTouchSample(PacketCodec.EVENT_DOWN, pid, x / density, y / density, event.eventTime / 1000.0)
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                activeFingers[pid] = ActiveFinger(x, y)
                onTouchSample(PacketCodec.EVENT_DOWN, pid, x / density, y / density, event.eventTime / 1000.0)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val historySize = event.historySize
                val pointerCount = event.pointerCount
                for (h in 0 until historySize) {
                    val tSec = event.getHistoricalEventTime(h) / 1000.0
                    for (p in 0 until pointerCount) {
                        val pid = event.getPointerId(p)
                        val hx = event.getHistoricalX(p, h)
                        val hy = event.getHistoricalY(p, h)
                        onTouchSample(PacketCodec.EVENT_MOVE, pid, hx / density, hy / density, tSec)
                    }
                }
                val nowSec = event.eventTime / 1000.0
                for (p in 0 until pointerCount) {
                    val pid = event.getPointerId(p)
                    val x = event.getX(p)
                    val y = event.getY(p)
                    activeFingers[pid]?.let {
                        it.xPx = x
                        it.yPx = y
                    } ?: run {
                        activeFingers[pid] = ActiveFinger(x, y)
                    }
                    onTouchSample(PacketCodec.EVENT_MOVE, pid, x / density, y / density, nowSec)
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                activeFingers.remove(pid)
                onTouchSample(PacketCodec.EVENT_UP, pid, x / density, y / density, event.eventTime / 1000.0)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    activeFingers.clear()
                }
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                val nowSec = event.eventTime / 1000.0
                for (p in 0 until event.pointerCount) {
                    val pid = event.getPointerId(p)
                    onTouchSample(
                        PacketCodec.EVENT_UP,
                        pid,
                        event.getX(p) / density,
                        event.getY(p) / density,
                        nowSec,
                    )
                }
                activeFingers.clear()
                invalidate()
            }
        }
        return true
    }
}

/**
 * Native tactile mouse button supporting independent multi-touch press-and-hold
 * simultaneously with pointer movement on [TouchSurfaceView].
 */
private class MouseHoldButtonView(
    context: Context,
    label: String,
    textSizeSp: Float,
    private val idleBgColor: Int,
    private val activeBgColor: Int = 0xFF38BDF8.toInt(),
    private val idleTextColor: Int,
    private val activeTextColor: Int = 0xFF0B0F19.toInt(),
    private val sideBorderColor: Int? = null,
) : TextView(context) {

    var hapticsEnabled: Boolean = true
    var onPressedChange: (Boolean) -> Unit = {}

    private val activePointers = HashSet<Int>()
    private var isHeld = false
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    init {
        text = label
        textSize = textSizeSp
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setBackgroundColor(idleBgColor)
        setTextColor(idleTextColor)
        isClickable = true
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bColor = sideBorderColor ?: return
        borderPaint.color = bColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
    }

    private fun updateHeld(held: Boolean) {
        if (isHeld == held) return
        isHeld = held
        setBackgroundColor(if (held) activeBgColor else idleBgColor)
        setTextColor(if (held) activeTextColor else idleTextColor)
        if (held && hapticsEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        onPressedChange(held)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointers.add(event.getPointerId(event.actionIndex))
                updateHeld(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers.add(event.getPointerId(event.actionIndex))
                updateHeld(true)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointers.remove(event.getPointerId(event.actionIndex))
                if (activePointers.isEmpty()) {
                    updateHeld(false)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers.clear()
                updateHeld(false)
                return true
            }
        }
        return true
    }
}

/**
 * Combined native split-touch ViewGroup housing [TouchSurfaceView] and the bottom
 * mouse buttons bar so users can hold Left/Mid/Right Click while moving the pointer.
 */
class TrackpadPanelView(context: Context) : LinearLayout(context) {
    val touchSurface: TouchSurfaceView
    private val leftBtn: MouseHoldButtonView
    private val midBtn: MouseHoldButtonView
    private val rightBtn: MouseHoldButtonView

    var onTouchSample: (Int, Int, Float, Float, Double) -> Unit = { _, _, _, _, _ -> }
    var onLeftChange: (Boolean) -> Unit = {}
    var onMiddleChange: (Boolean) -> Unit = {}
    var onRightChange: (Boolean) -> Unit = {}
    var hapticsEnabled: Boolean = true
        set(value) {
            field = value
            leftBtn.hapticsEnabled = value
            midBtn.hapticsEnabled = value
            rightBtn.hapticsEnabled = value
        }

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        val density = resources.displayMetrics.density

        touchSurface = TouchSurfaceView(context) { t, pid, x, y, ts ->
            onTouchSample(t, pid, x, y, ts)
        }
        addView(touchSurface, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val divider = View(context).apply {
            setBackgroundColor(0xFF1E293B.toInt())
        }
        addView(divider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, density.roundToInt().coerceAtLeast(1)))

        val buttonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
            setBackgroundColor(0xFF111827.toInt())
        }

        leftBtn = MouseHoldButtonView(
            context = context,
            label = "Left Click",
            textSizeSp = 14f,
            idleBgColor = 0xFF1E293B.toInt(),
            idleTextColor = 0xFFCBD5E1.toInt(),
        ).apply {
            onPressedChange = { onLeftChange(it) }
        }

        midBtn = MouseHoldButtonView(
            context = context,
            label = "Mid",
            textSizeSp = 12f,
            idleBgColor = 0xFF172033.toInt(),
            idleTextColor = 0xFF94A3B8.toInt(),
            sideBorderColor = 0xFF334155.toInt(),
        ).apply {
            onPressedChange = { onMiddleChange(it) }
        }

        rightBtn = MouseHoldButtonView(
            context = context,
            label = "Right Click",
            textSizeSp = 14f,
            idleBgColor = 0xFF1E293B.toInt(),
            idleTextColor = 0xFFCBD5E1.toInt(),
        ).apply {
            onPressedChange = { onRightChange(it) }
        }

        buttonRow.addView(leftBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        buttonRow.addView(midBtn, LayoutParams((72 * density).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        buttonRow.addView(rightBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        addView(buttonRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (74 * density).roundToInt()))
    }
}

@Composable
fun TrackpadWithButtonsPanel(
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean,
    onTouchSample: (eventType: Int, pointerId: Int, xDp: Float, yDp: Float, timestampSec: Double) -> Unit,
    onLeftChange: (Boolean) -> Unit,
    onMiddleChange: (Boolean) -> Unit,
    onRightChange: (Boolean) -> Unit,
) {
    val curTouch by rememberUpdatedState(onTouchSample)
    val curLeft by rememberUpdatedState(onLeftChange)
    val curMiddle by rememberUpdatedState(onMiddleChange)
    val curRight by rememberUpdatedState(onRightChange)
    val curHaptics by rememberUpdatedState(hapticsEnabled)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TrackpadPanelView(context).apply {
                this.hapticsEnabled = curHaptics
                this.onTouchSample = { t, pid, x, y, ts -> curTouch(t, pid, x, y, ts) }
                this.onLeftChange = { curLeft(it) }
                this.onMiddleChange = { curMiddle(it) }
                this.onRightChange = { curRight(it) }
            }
        },
        update = { view ->
            view.hapticsEnabled = curHaptics
            view.onTouchSample = { t, pid, x, y, ts -> curTouch(t, pid, x, y, ts) }
            view.onLeftChange = { curLeft(it) }
            view.onMiddleChange = { curMiddle(it) }
            view.onRightChange = { curRight(it) }
        },
    )
}

@Composable
fun TouchCaptureSurface(
    modifier: Modifier = Modifier,
    onTouchSample: (eventType: Int, pointerId: Int, xDp: Float, yDp: Float, timestampSec: Double) -> Unit,
) {
    val currentOnTouchSample by rememberUpdatedState(onTouchSample)
    AndroidView(
        modifier = modifier,
        factory = { context -> TouchSurfaceView(context) { t, pid, x, y, ts -> currentOnTouchSample(t, pid, x, y, ts) } },
        update = { view -> view.onTouchSample = { t, pid, x, y, ts -> currentOnTouchSample(t, pid, x, y, ts) } },
    )
}
