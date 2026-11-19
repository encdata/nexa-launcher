@file:Suppress("OverrideDeprecatedMigration")

package com.termux.gui.protocol.shared.v0

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.*

@Suppress("OVERRIDE_DEPRECATION")
class GUIWebViewClient(
    private val l: WebEventListener,
    var allowNavigation: Boolean = false,
    private val mainFrameErrorsOnly: Boolean = false
) : WebViewClient() {
    @Suppress("Deprecation")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url != null)
            l.onNavigation(url, null)
        return ! allowNavigation
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request != null)
            l.onNavigation(request.url.toString(), request.isForMainFrame)
        return ! allowNavigation
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url != null) l.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url != null) l.onPageFinished(url)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        super.onLoadResource(view, url)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (url != null) l.onPageCommitVisible(url)
    }

    
    @Suppress("Deprecation")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        return super.shouldInterceptRequest(view, url)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        ElectronBridge.interceptWebRequest(view, request)?.let { return it }
        return super.shouldInterceptRequest(view, request)
    }


    @Suppress("Deprecation")
    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
        if (!mainFrameErrorsOnly && failingUrl != null && description != null)
            l.onReceivedError(failingUrl, description, errorCode)
    }

    @Suppress("Deprecation")
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (error != null && request != null && (!mainFrameErrorsOnly || request.isForMainFrame))
            l.onReceivedError(request.url.toString(), error.description.toString(),
                error.errorCode, request.isForMainFrame)
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        if (request != null && errorResponse != null && (!mainFrameErrorsOnly || request.isForMainFrame))
            l.onHTTPError(request.url.toString(), errorResponse.statusCode)
    }

    override fun onFormResubmission(view: WebView?, dontResend: Message?, resend: Message?) {
        super.onFormResubmission(view, dontResend, resend)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
    }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
        super.onReceivedClientCertRequest(view, request)
    }

    override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun shouldOverrideKeyEvent(view: WebView?, event: KeyEvent?): Boolean {
        return super.shouldOverrideKeyEvent(view, event)
    }

    override fun onUnhandledKeyEvent(view: WebView?, event: KeyEvent?) {
        super.onUnhandledKeyEvent(view, event)
    }

    override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
        super.onScaleChanged(view, oldScale, newScale)
    }

    override fun onReceivedLoginRequest(view: WebView?, realm: String?, account: String?, args: String?) {
        super.onReceivedLoginRequest(view, realm, account, args)
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            l.onRenderProcessGone(view, detail?.didCrash() == true)
            view.destroy()
        }
        return true
    }

    override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
        super.onSafeBrowsingHit(view, request, threatType, callback)
    }
}
