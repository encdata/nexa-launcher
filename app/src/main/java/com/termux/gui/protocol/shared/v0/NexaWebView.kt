package com.termux.gui.protocol.shared.v0

import android.content.Context
import android.view.View
import android.webkit.WebView
import java.util.WeakHashMap

class NexaWebView(context: Context) : WebView(context) {
    init {
        WebViewTimerPolicy.register(this)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        WebViewTimerPolicy.update()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        WebViewTimerPolicy.update()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WebViewTimerPolicy.update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        WebViewTimerPolicy.update()
    }

    override fun destroy() {
        WebViewTimerPolicy.remove(this)
        super.destroy()
    }

    companion object {
        fun pauseAllTimers() = WebViewTimerPolicy.setPaused(true)
        fun resumeAllTimers() = WebViewTimerPolicy.setPaused(false)
    }
}

private object WebViewTimerPolicy {
    private val views = WeakHashMap<NexaWebView, Unit>()
    private var forcedPause: Boolean? = null
    private var appliedPause: Boolean? = null

    fun register(view: NexaWebView) {
        views[view] = Unit
        update()
    }

    fun remove(view: NexaWebView) {
        views.remove(view)
        update(view)
    }

    fun setPaused(value: Boolean) {
        forcedPause = value
        update()
    }

    fun update(fallback: NexaWebView? = null) {
        val activeViews = views.keys.toList()
        val shouldPause = forcedPause
            ?: (activeViews.isNotEmpty() && activeViews.none { it.isShown })
        if (appliedPause == shouldPause) return
        val view = activeViews.firstOrNull() ?: fallback ?: return
        if (shouldPause) view.pauseTimers() else view.resumeTimers()
        appliedPause = shouldPause
    }
}
