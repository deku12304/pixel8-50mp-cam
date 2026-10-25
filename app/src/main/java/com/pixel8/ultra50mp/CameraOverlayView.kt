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

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val levelLockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50") // Turns green when perfectly level
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw Rule of Thirds Grid
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

        // 2. Draw Horizon Level Indicator in Center
        val centerX = w / 2f
        val centerY = h / 2f
        val lineLength = w * 0.22f
        val isLevel = abs(rollAngle) < 1.0f
        val activePaint = if (isLevel) levelLockedPaint else levelPaint

        canvas.save()
        canvas.rotate(-rollAngle, centerX, centerY)
        canvas.drawLine(centerX - lineLength / 2f, centerY, centerX + lineLength / 2f, centerY, activePaint)
        canvas.restore()
    }
}
