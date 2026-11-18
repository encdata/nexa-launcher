package com.termux.gui.protocol.shared.v0

import android.content.Context
import android.webkit.WebView
import java.util.WeakHashMap

class NexaWebView(context: Context) : WebView(context) {
    init {
        WebViewTimerPolicy.register(this)
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
    private var paused = false

    fun register(view: NexaWebView) {
        views[view] = Unit
        if (paused) view.pauseTimers()
    }

    fun remove(view: NexaWebView) {
        views.remove(view)
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        val view = views.keys.firstOrNull() ?: return
        if (paused) view.pauseTimers() else view.resumeTimers()
    }
}
