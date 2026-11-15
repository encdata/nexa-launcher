package com.termux.gui.protocol.shared.v0

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.ProgressBar
import kotlin.math.min

class CircularProgressBar(context: Context, progressColor: Int, trackColor: Int) :
    ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = trackColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = progressColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isIndeterminate = false
        max = 100
    }

    override fun onDraw(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val stroke = diameter * 0.1f
        val inset = stroke / 2
        val left = (width - diameter) / 2 + inset
        val top = (height - diameter) / 2 + inset
        val bounds = RectF(left, top, left + diameter - stroke, top + diameter - stroke)
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        val fraction = progress.toFloat() / max.coerceAtLeast(1)
        if (fraction > 0f) canvas.drawArc(bounds, -90f, 360f * fraction, false, progressPaint)
    }
}
