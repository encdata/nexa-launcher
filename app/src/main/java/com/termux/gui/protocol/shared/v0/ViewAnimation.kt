package com.termux.gui.protocol.shared.v0

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

object ViewAnimation {
    private val active = ConcurrentHashMap<String, AnimatorSet>()

    fun start(view: View, aid: Int, id: Int, options: JsonObject, density: Float) {
        val key = "$aid:$id"
        active.remove(key)?.cancel()
        val properties = options.getAsJsonObject("properties")
            ?: throw IllegalArgumentException("animation needs properties")
        if (properties.size() == 0) return

        val duration = (options.get("duration")?.asLong ?: 220L).coerceAtLeast(0L)
        val delay = (options.get("delay")?.asLong ?: 0L).coerceAtLeast(0L)
        val repeat = options.get("repeat")?.asInt ?: 0
        if (repeat < -1) throw IllegalArgumentException("animation repeat must be -1 or greater")
        val reverse = options.get("reverse")?.asBoolean ?: false
        val interpolator = when (options.get("easing")?.asString ?: "ease") {
            "linear" -> LinearInterpolator()
            "ease_in" -> AccelerateInterpolator()
            "ease_out" -> DecelerateInterpolator()
            "ease" , "ease_in_out" -> AccelerateDecelerateInterpolator()
            "overshoot" -> OvershootInterpolator()
            "bounce" -> BounceInterpolator()
            else -> throw IllegalArgumentException("unsupported animation easing")
        }
        val animators = mutableListOf<Animator>()
        for ((name, value) in properties.entrySet()) {
            val target = value.asFloat
            if (name == "radius") {
                val drawable = view.background as? GradientDrawable
                    ?: throw IllegalArgumentException("radius animation needs a shaped background")
                val animator = ValueAnimator.ofFloat(drawable.cornerRadius, target * density)
                animator.addUpdateListener { drawable.cornerRadius = it.animatedValue as Float }
                configure(animator, duration, repeat, reverse, interpolator)
                animators += animator
                continue
            }
            if (name == "background") {
                val drawable = view.background as? GradientDrawable
                    ?: throw IllegalArgumentException("background animation needs a shaped background")
                if (drawable.colors != null) {
                    throw IllegalArgumentException("background animation needs a solid fill")
                }
                val from = drawable.color?.defaultColor ?: Color.TRANSPARENT
                val animator = ValueAnimator.ofArgb(from, value.asInt)
                animator.addUpdateListener { drawable.setColor(it.animatedValue as Int) }
                configure(animator, duration, repeat, reverse, interpolator)
                animators += animator
                continue
            }
            val dimension = name in setOf("x", "y", "translationX", "translationY", "elevation")
            if (name !in setOf("alpha", "x", "y", "translationX", "translationY",
                               "scaleX", "scaleY", "rotation", "rotationX", "rotationY", "elevation")) {
                throw IllegalArgumentException("unsupported animation property: $name")
            }
            val targetValue = if (dimension) target * density else target
            val animator = ObjectAnimator.ofFloat(view, name, targetValue)
            configure(animator, duration, repeat, reverse, interpolator)
            animators += animator
        }

        val set = AnimatorSet()
        set.playTogether(animators)
        set.startDelay = delay
        set.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                active.remove(key, set)
            }

            override fun onAnimationCancel(animation: Animator) {
                active.remove(key, set)
            }
        })
        active[key] = set
        set.start()
    }

    fun cancel(aid: Int, id: Int) {
        active.remove("$aid:$id")?.cancel()
    }

    fun cancelTree(aid: Int, view: View) {
        cancel(aid, view.id)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) cancelTree(aid, view.getChildAt(index))
        }
    }

    private fun configure(animator: ValueAnimator, duration: Long, repeat: Int,
                          reverse: Boolean, interpolator: android.animation.TimeInterpolator) {
        animator.duration = duration
        animator.repeatCount = repeat
        animator.repeatMode = if (reverse) ValueAnimator.REVERSE else ValueAnimator.RESTART
        animator.interpolator = interpolator
    }
}
