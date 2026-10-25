package com.pixel8.ultra50mp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val AF_PASSIVE = 0
        const val AF_SCANNING = 1
        const val AF_LOCKED = 2
    }

    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var rollAngle: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var afState: Int = AF_PASSIVE
        set(value) {
            field = value
            invalidate()
        }

    private var focusX: Float = -1f
    private var focusY: Float = -1f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val levelLockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    fun setFocusPoint(x: Float, y: Float) {
        focusX = x
        focusY = y
        invalidate()
    }

    fun resetFocusToCenter() {
        focusX = -1f
        focusY = -1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Rule of Thirds Grid
        if (showGrid) {
            val oneThirdW = w / 3f
            val twoThirdW = w * 2f / 3f
            val oneThirdH = h / 3f
            val twoThirdH = h * 2f / 3f

            canvas.drawLine(oneThirdW, 0f, oneThirdW, h, gridPaint)
            canvas.drawLine(twoThirdW, 0f, twoThirdW, h, gridPaint)
            canvas.drawLine(0f, oneThirdH, w, oneThirdH, gridPaint)
            canvas.drawLine(0f, twoThirdH, w, twoThirdH, gridPaint)
        }

        // 2. Dual-Wing Horizon Leveler in Center
        val centerX = w / 2f
        val centerY = h / 2f
        val isLevel = abs(rollAngle) < 1.0f
        val activeLevelPaint = if (isLevel) levelLockedPaint else levelPaint

        canvas.save()
        canvas.rotate(-rollAngle, centerX, centerY)
        canvas.drawLine(centerX - 140f, centerY, centerX - 65f, centerY, activeLevelPaint)
        canvas.drawLine(centerX + 65f, centerY, centerX + 140f, centerY, activeLevelPaint)
        canvas.restore()

        // 3. Dedicated Pro Autofocus Crosshair Reticle
        val cx = if (focusX >= 0f) focusX else centerX
        val cy = if (focusY >= 0f) focusY else centerY

        crosshairPaint.color = when (afState) {
            AF_LOCKED -> Color.parseColor("#00E676")   // Neon Green when focus locked
            AF_SCANNING -> Color.parseColor("#FFD600") // Cyber Amber while focusing
            else -> Color.parseColor("#EEFFFFFF")      // Crisp White in continuous ready mode
        }

        val boxHalf = if (afState == AF_SCANNING) 54f else 44f
        val cornerLen = 18f

        // Top-Left Bracket
        canvas.drawLine(cx - boxHalf, cy - boxHalf, cx - boxHalf + cornerLen, cy - boxHalf, crosshairPaint)
        canvas.drawLine(cx - boxHalf, cy - boxHalf, cx - boxHalf, cy - boxHalf + cornerLen, crosshairPaint)

        // Top-Right Bracket
        canvas.drawLine(cx + boxHalf, cy - boxHalf, cx + boxHalf - cornerLen, cy - boxHalf, crosshairPaint)
        canvas.drawLine(cx + boxHalf, cy - boxHalf, cx + boxHalf, cy - boxHalf + cornerLen, crosshairPaint)

        // Bottom-Left Bracket
        canvas.drawLine(cx - boxHalf, cy + boxHalf, cx - boxHalf + cornerLen, cy + boxHalf, crosshairPaint)
        canvas.drawLine(cx - boxHalf, cy + boxHalf, cx - boxHalf, cy + boxHalf - cornerLen, crosshairPaint)

        // Bottom-Right Bracket
        canvas.drawLine(cx + boxHalf, cy + boxHalf, cx + boxHalf - cornerLen, cy + boxHalf, crosshairPaint)
        canvas.drawLine(cx + boxHalf, cy + boxHalf, cx + boxHalf, cy + boxHalf - cornerLen, crosshairPaint)

        // Inner Crosshair Ticks & Center Target Dot
        canvas.drawLine(cx - 14f, cy, cx - 5f, cy, crosshairPaint)
        canvas.drawLine(cx + 5f, cy, cx + 14f, cy, crosshairPaint)
        canvas.drawLine(cx, cy - 14f, cx, cy - 5f, crosshairPaint)
        canvas.drawLine(cx, cy + 5f, cx, cy + 14f, crosshairPaint)
        canvas.drawCircle(cx, cy, 2.5f, crosshairPaint)
    }
}
