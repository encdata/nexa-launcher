package com.termux.gui.protocol.shared.v0

import android.webkit.ConsoleMessage
import android.webkit.WebView

interface WebEventListener {
    fun onNavigation(url: String)
    fun onNavigation(url: String, isMainFrame: Boolean?) = onNavigation(url)
    fun onPageStarted(url: String) {}
    fun onPageCommitVisible(url: String) {}
    fun onPageFinished(url: String) {}
    fun onReceivedTitle(title: String) {}
    fun onHTTPError(url: String, code: Int)
    fun onReceivedError(url: String, description: String, code: Int)
    fun onReceivedError(url: String, description: String, code: Int, isMainFrame: Boolean?) {
        onReceivedError(url, description, code)
    }
    fun onRenderProcessGone(v: WebView)
    fun onRenderProcessGone(v: WebView, crashed: Boolean) = onRenderProcessGone(v)
    
    
    fun onProgressChanged(progress: Int)
    fun onConsoleMessage(m: ConsoleMessage)
}
