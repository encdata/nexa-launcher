package com.termux.gui.views

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.roundToInt

class FlexLayout(context: Context) : ViewGroup(context) {
    class LayoutParams : MarginLayoutParams {
        var flexGrow = 0f
        var alignSelf: String? = null

        constructor(width: Int, height: Int, flexGrow: Float = 0f) : super(width, height) {
            this.flexGrow = flexGrow
        }

        constructor(source: ViewGroup.LayoutParams) : super(source)
    }

    private data class Line(val children: MutableList<View> = mutableListOf(), var width: Int = 0,
                            var height: Int = 0)

    var itemGap = 0
    var lineGap = 0
    var justify = "start"
    var alignItems = "start"
    private val lines = mutableListOf<Line>()

    override fun generateDefaultLayoutParams() =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(source: ViewGroup.LayoutParams) = LayoutParams(source)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams) = params is LayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        lines.clear()
        var line = Line()
        lines.add(line)

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val params = child.layoutParams as LayoutParams
            val occupied = child.measuredWidth + params.leftMargin + params.rightMargin
            val nextWidth = line.width + if (line.children.isEmpty()) occupied else itemGap + occupied
            if (line.children.isNotEmpty() && nextWidth > availableWidth) {
                line = Line()
                lines.add(line)
            }
            if (line.children.isNotEmpty()) line.width += itemGap
            line.children.add(child)
            line.width += occupied
        }
        if (lines.size > 1 && lines.last().children.isEmpty()) lines.removeAt(lines.lastIndex)

        for (line in lines) {
            val growTotal = line.children.sumOf { (it.layoutParams as LayoutParams).flexGrow.toDouble() }
                .toFloat()
            val free = if (availableWidth == Int.MAX_VALUE) 0
            else (availableWidth - line.width).coerceAtLeast(0)
            if (growTotal > 0f && free > 0) {
                var assigned = 0
                val lastGrowingIndex = line.children.indexOfLast {
                    (it.layoutParams as LayoutParams).flexGrow > 0f
                }
                line.children.forEachIndexed { index, child ->
                    val params = child.layoutParams as LayoutParams
                    if (params.flexGrow <= 0f) return@forEachIndexed
                    val extra = if (index == lastGrowingIndex) free - assigned
                    else (free * params.flexGrow / growTotal).roundToInt()
                    assigned += extra
                    val targetWidth = child.measuredWidth + extra
                    val childHeightSpec = getChildMeasureSpec(
                        heightMeasureSpec, paddingTop + paddingBottom + params.topMargin + params.bottomMargin,
                        params.height)
                    child.measure(MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                                  childHeightSpec)
                }
            }
            line.width = 0
            line.height = 0
            line.children.forEachIndexed { index, child ->
                val params = child.layoutParams as LayoutParams
                if (index > 0) line.width += itemGap
                line.width += child.measuredWidth + params.leftMargin + params.rightMargin
                line.height = max(line.height, child.measuredHeight + params.topMargin + params.bottomMargin)
            }
        }

        for (line in lines) {
            for (child in line.children) {
                val params = child.layoutParams as LayoutParams
                if ((params.alignSelf ?: alignItems) != "stretch"
                    || params.height != ViewGroup.LayoutParams.WRAP_CONTENT) continue
                val targetHeight = (line.height - params.topMargin - params.bottomMargin).coerceAtLeast(0)
                val widthSpec = MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY)
                child.measure(widthSpec, MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY))
            }
        }

        val contentWidth = lines.maxOfOrNull { it.width } ?: 0
        val contentHeight = lines.sumOf { it.height } + max(0, lines.size - 1) * lineGap
        val measuredWidth = resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val measuredHeight = resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        var y = paddingTop
        for (line in lines) {
            val free = (availableWidth - line.width).coerceAtLeast(0)
            val extraGap = when (justify) {
                "center" -> 0
                "end" -> 0
                "space_between" -> if (line.children.size > 1) free / (line.children.size - 1) else 0
                "space_around" -> if (line.children.isNotEmpty()) free / line.children.size else 0
                "space_evenly" -> free / (line.children.size + 1)
                else -> 0
            }
            var x = paddingLeft + when (justify) {
                "center" -> free / 2
                "end" -> free
                "space_around" -> extraGap / 2
                "space_evenly" -> extraGap
                else -> 0
            }
            for ((index, child) in line.children.withIndex()) {
                val params = child.layoutParams as LayoutParams
                x += params.leftMargin
                val childTop = when (params.alignSelf ?: alignItems) {
                    "center" -> y + (line.height - child.measuredHeight + params.topMargin - params.bottomMargin) / 2
                    "end" -> y + line.height - child.measuredHeight - params.bottomMargin
                    else -> y + params.topMargin
                }
                child.layout(x, childTop, x + child.measuredWidth, childTop + child.measuredHeight)
                x += child.measuredWidth + params.rightMargin
                if (index < line.children.lastIndex) x += itemGap + extraGap
            }
            y += line.height + lineGap
        }
    }
}
