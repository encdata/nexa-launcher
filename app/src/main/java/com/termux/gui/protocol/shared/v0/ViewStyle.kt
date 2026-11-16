package com.termux.gui.protocol.shared.v0

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import com.google.gson.JsonObject
import kotlin.math.roundToInt

object ViewStyle {
    private val orientations = mapOf(
        "top_bottom" to GradientDrawable.Orientation.TOP_BOTTOM,
        "top_right_bottom_left" to GradientDrawable.Orientation.TR_BL,
        "right_left" to GradientDrawable.Orientation.RIGHT_LEFT,
        "bottom_right_top_left" to GradientDrawable.Orientation.BR_TL,
        "bottom_top" to GradientDrawable.Orientation.BOTTOM_TOP,
        "bottom_left_top_right" to GradientDrawable.Orientation.BL_TR,
        "left_right" to GradientDrawable.Orientation.LEFT_RIGHT,
        "top_left_bottom_right" to GradientDrawable.Orientation.TL_BR,
    )

    fun apply(view: View, style: JsonObject, density: Float) {
        val gradient = style.getAsJsonObject("gradient")
        val background = style.get("background")?.asInt
        val radius = style.get("radius")?.asFloat
        val border = style.getAsJsonObject("border")
        if (gradient != null || background != null || radius != null || border != null) {
            val drawable = GradientDrawable()
            if (gradient != null) {
                val colors = gradient.getAsJsonArray("colors").map { it.asInt }.toIntArray()
                val orientationName = gradient.get("orientation")?.asString ?: "top_bottom"
                drawable.orientation = orientations[orientationName]
                    ?: throw IllegalArgumentException("unsupported gradient orientation: $orientationName")
                drawable.colors = colors
            } else if (background != null) {
                drawable.setColor(background)
            } else {
                drawable.setColor(android.graphics.Color.TRANSPARENT)
            }
            if (radius != null) drawable.cornerRadius = radius * density
            if (border != null) {
                val width = border.get("width")?.asFloat ?: 1f
                val color = border.get("color")?.asInt
                    ?: throw IllegalArgumentException("border requires a color")
                if (width > 0f) drawable.setStroke((width * density).roundToInt().coerceAtLeast(1), color)
            }
            view.background = drawable
        }
        style.get("shadow")?.asFloat?.let { view.elevation = it * density }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            style.get("shadowColor")?.asInt?.let {
                view.outlineAmbientShadowColor = it
                view.outlineSpotShadowColor = it
            }
        }
        style.get("clipToOutline")?.asBoolean?.let { view.clipToOutline = it }
    }
}
