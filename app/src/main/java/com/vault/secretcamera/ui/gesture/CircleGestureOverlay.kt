package com.vault.secretcamera.ui.gesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vault.secretcamera.security.SecurityPreferences
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class CircleGestureOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = mutableListOf<PointF>()
    private val drawPath = Path()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9900E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var onCircleDetected: (() -> Unit)?
        get() = onGestureDetected
        set(value) { onGestureDetected = value }

    var onGestureDetected: (() -> Unit)? = null
    var isStealthMode: Boolean = true // Invisible by default! No drawn lines show
    var expectedShape: String = SecurityPreferences.SHAPE_CIRCLE

    // Multi-tap tracking (e.g. 4 rapid taps)
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private val tapTimestamps = mutableListOf<Long>()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downX = x
                downY = y

                points.clear()
                points.add(PointF(x, y))
                drawPath.reset()
                drawPath.moveTo(x, y)
                if (!isStealthMode) invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                points.add(PointF(x, y))
                drawPath.lineTo(x, y)
                if (!isStealthMode) invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val upTime = System.currentTimeMillis()
                val moveDist = hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()

                // Check if this was a quick tap (< 400ms, move < 60px)
                if (upTime - downTime < 400 && moveDist < 60f) {
                    val now = System.currentTimeMillis()
                    tapTimestamps.removeAll { now - it > 1800 }
                    tapTimestamps.add(now)

                    // 3 or 4 rapid taps trigger
                    if (tapTimestamps.size >= 3) {
                        tapTimestamps.clear()
                        triggerHaptic()
                        onGestureDetected?.invoke()
                        points.clear()
                        drawPath.reset()
                        if (!isStealthMode) invalidate()
                        return true
                    }
                }

                // Check drawn gesture if more than 5 points
                if (points.size >= 5) {
                    val matched = evaluateGesture(points, expectedShape)
                    if (matched) {
                        triggerHaptic()
                        onGestureDetected?.invoke()
                    }
                }

                // Clear path
                postDelayed({
                    points.clear()
                    drawPath.reset()
                    if (!isStealthMode) invalidate()
                }, 100)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isStealthMode && points.isNotEmpty()) {
            canvas.drawPath(drawPath, strokePaint)
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(70)
            }
        } catch (_: Exception) {}
    }

    /**
     * Evaluates gesture points based on selected expectedShape.
     */
    private fun evaluateGesture(pts: List<PointF>, shape: String): Boolean {
        if (pts.size < 5) return false

        return when (shape) {
            SecurityPreferences.SHAPE_CIRCLE -> evaluateCircleGesture(pts)
            SecurityPreferences.SHAPE_TRIANGLE -> evaluateTriangleGesture(pts)
            SecurityPreferences.SHAPE_SQUARE -> evaluateSquareGesture(pts)
            SecurityPreferences.SHAPE_Z -> evaluateZGesture(pts)
            SecurityPreferences.SHAPE_MULTI_TAP -> false // Handled via tap counter
            else -> evaluateCircleGesture(pts)
        }
    }

    /**
     * Highly responsive & forgiving Circle Recognition Algorithm:
     * Accepts circular and oval loops, sweeping curves, or closed loops drawn quickly by finger.
     */
    private fun evaluateCircleGesture(pts: List<PointF>): Boolean {
        if (pts.size < 5) return false

        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var sumX = 0f
        var sumY = 0f

        for (p in pts) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
            sumX += p.x
            sumY += p.y
        }

        val width = maxX - minX
        val height = maxY - minY
        val maxDim = max(width, height)
        if (maxDim < 50f) return false // Ignore tiny accidental touches

        val startP = pts.first()
        val endP = pts.last()
        val endDistance = hypot((endP.x - startP.x).toDouble(), (endP.y - startP.y).toDouble()).toFloat()
        val isLooped = endDistance < maxDim * 0.75f

        val cx = sumX / pts.size
        val cy = sumY / pts.size

        var totalAngle = 0.0
        var prevAngle = atan2((pts[0].y - cy).toDouble(), (pts[0].x - cx).toDouble())

        for (i in 1 until pts.size) {
            val currentAngle = atan2((pts[i].y - cy).toDouble(), (pts[i].x - cx).toDouble())
            var delta = currentAngle - prevAngle
            while (delta > Math.PI) delta -= 2 * Math.PI
            while (delta < -Math.PI) delta += 2 * Math.PI
            totalAngle += delta
            prevAngle = currentAngle
        }

        val traversedDegrees = Math.toDegrees(abs(totalAngle))

        // 1. If it circled at least ~160 degrees and closed/looped
        if (traversedDegrees >= 150.0 && isLooped) return true

        // 2. If it did a full revolution around center (>= 240 deg)
        if (traversedDegrees >= 240.0) return true

        // 3. If it's a closed loop with sufficient perimeter
        if (isLooped && pts.size >= 8) return true

        return false
    }

    /**
     * Triangle Recognition Algorithm:
     * Closed loop, simplified with RDP algorithm down to 3-5 vertices, ~360 deg total turn.
     */
    private fun evaluateTriangleGesture(pts: List<PointF>): Boolean {
        if (pts.size < 10) return false

        val bbox = calculateBoundingBox(pts)
        val maxDim = max(bbox.width, bbox.height)
        if (maxDim < 80f) return false

        // Must be closed loop
        val endDist = hypot((pts.last().x - pts.first().x).toDouble(), (pts.last().y - pts.first().y).toDouble()).toFloat()
        if (endDist > maxDim * 0.48f) return false

        // Douglas-Peucker simplification with 12% epsilon
        val simplified = douglasPeucker(pts, maxDim * 0.12f)
        // A triangle typically simplifies to 3 vertices + return point (4 or 5 points)
        return simplified.size in 4..6
    }

    /**
     * Square / Rectangle Recognition Algorithm:
     * Closed loop, aspect ratio between 0.4 and 2.5, simplifies down to 4-6 vertices.
     */
    private fun evaluateSquareGesture(pts: List<PointF>): Boolean {
        if (pts.size < 12) return false

        val bbox = calculateBoundingBox(pts)
        val maxDim = max(bbox.width, bbox.height)
        if (maxDim < 80f) return false

        val ratio = bbox.width / bbox.height
        if (ratio < 0.4f || ratio > 2.5f) return false

        // Must be closed loop
        val endDist = hypot((pts.last().x - pts.first().x).toDouble(), (pts.last().y - pts.first().y).toDouble()).toFloat()
        if (endDist > maxDim * 0.48f) return false

        // Douglas-Peucker simplification with 10% epsilon
        val simplified = douglasPeucker(pts, maxDim * 0.10f)
        // A square simplifies to 4 vertices + return point (5 to 7 points)
        return simplified.size in 5..7
    }

    /**
     * Z-Shape Recognition Algorithm:
     * Open gesture (start and end distant), 3 strokes:
     * Stroke 1: moves right (dx > 0)
     * Stroke 2: diagonal down-left (dx < 0, dy > 0)
     * Stroke 3: moves right (dx > 0)
     */
    private fun evaluateZGesture(pts: List<PointF>): Boolean {
        if (pts.size < 10) return false

        val bbox = calculateBoundingBox(pts)
        val maxDim = max(bbox.width, bbox.height)
        if (maxDim < 80f) return false

        // Must NOT be closed loop
        val endDist = hypot((pts.last().x - pts.first().x).toDouble(), (pts.last().y - pts.first().y).toDouble()).toFloat()
        if (endDist < maxDim * 0.35f) return false

        // Simplify polyline
        val simplified = douglasPeucker(pts, maxDim * 0.12f)
        if (simplified.size !in 4..6) return false

        val p0 = simplified.first()
        val p1 = simplified[1]
        val p2 = simplified[simplified.size - 2]
        val pEnd = simplified.last()

        // Check Z characteristics:
        // Stroke 1: p0 to p1 moves right
        val s1Dx = p1.x - p0.x
        // Stroke 2: p1 to p2 moves left and down
        val s2Dx = p2.x - p1.x
        val s2Dy = p2.y - p1.y
        // Stroke 3: p2 to pEnd moves right
        val s3Dx = pEnd.x - p2.x

        return (s1Dx > 0 && s2Dx < 0 && s2Dy > 0 && s3Dx > 0)
    }

    private data class BoundingBox(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
        val width = maxX - minX
        val height = maxY - minY
    }

    private fun calculateBoundingBox(pts: List<PointF>): BoundingBox {
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (p in pts) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }
        return BoundingBox(minX, maxX, minY, maxY)
    }

    /**
     * Ramer-Douglas-Peucker Algorithm for line simplification
     */
    private fun douglasPeucker(pointList: List<PointF>, epsilon: Float): List<PointF> {
        if (pointList.size < 3) return pointList

        var dmax = 0f
        var index = 0
        val first = pointList.first()
        val last = pointList.last()

        for (i in 1 until pointList.size - 1) {
            val d = perpendicularDistance(pointList[i], first, last)
            if (d > dmax) {
                index = i
                dmax = d
            }
        }

        return if (dmax > epsilon) {
            val rec1 = douglasPeucker(pointList.subList(0, index + 1), epsilon)
            val rec2 = douglasPeucker(pointList.subList(index, pointList.size), epsilon)
            rec1.dropLast(1) + rec2
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDistance(p: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val mag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (mag == 0f) return hypot((p.x - lineStart.x).toDouble(), (p.y - lineStart.y).toDouble()).toFloat()
        return abs(dy * p.x - dx * p.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x) / mag
    }
}
