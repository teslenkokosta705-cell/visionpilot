package com.screenpilot.ai.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

class TargetIndicatorView(context: Context) : View(context) {

    private var normX = -1f
    private var normY = -1f
    private var normEndX = -1f
    private var normEndY = -1f
    private var hasVector = false

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 59, 130, 246)
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60A5FA")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    fun showTarget(x: Int, y: Int, endX: Int? = null, endY: Int? = null) {
        normX = x.coerceIn(0, 1000) / 1000f
        normY = y.coerceIn(0, 1000) / 1000f
        if (endX != null && endY != null && (endX != x || endY != y)) {
            normEndX = endX.coerceIn(0, 1000) / 1000f
            normEndY = endY.coerceIn(0, 1000) / 1000f
            hasVector = true
        } else {
            hasVector = false
        }
        visibility = VISIBLE
        invalidate()
    }

    fun clearTarget() {
        visibility = GONE
        normX = -1f
        normY = -1f
        hasVector = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (normX < 0f || normY < 0f) return

        val cx = normX * width
        val cy = normY * height

        if (hasVector) {
            val ex = normEndX * width
            val ey = normEndY * height
            canvas.drawLine(cx, cy, ex, ey, vectorPaint)
            canvas.drawCircle(ex, ey, 14f, fillPaint)
        }

        canvas.drawCircle(cx, cy, 44f, fillPaint)
        canvas.drawCircle(cx, cy, 44f, ringPaint)
        canvas.drawCircle(cx, cy, 9f, dotPaint)
    }
}
