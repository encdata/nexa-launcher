package com.termux.gui.protocol.shared.v0

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.termux.gui.ConnectionHandler
import org.json.JSONObject
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ElectronBridge {
    private const val MAX_PRELOAD_BYTES = 8 * 1024 * 1024
    private const val MAX_IPC_MESSAGE_CHARS = 1024 * 1024
    private const val MAX_IPC_MESSAGE_BYTES = 1024 * 1024
    private val ipcKindPattern = Regex("^\\s*\\{\\s*\\\"kind\\\"\\s*:\\s*\\\"(send|invoke)\\\"(?:\\s*,|\\s*\\})")
    private val invokeIdPattern = Regex("^\\s*\\{\\s*\\\"kind\\\"\\s*:\\s*\\\"invoke\\\"\\s*,\\s*\\\"id\\\"\\s*:\\s*(\\d+)")

    private data class State(
        val aid: Int,
        val viewId: Int,
        val eventQueue: LinkedBlockingQueue<ConnectionHandler.Event>,
        val ipcEndpoint: String?,
        val ipcToken: String?,
        val windowId: Int?,
        val ipcExecutor: ExecutorService?,
        var activeConnection: HttpURLConnection? = null,
        @Volatile
        var closed: Boolean = false,
        var paused: Boolean = false,
        var sendErrorReported: Boolean = false,
        val consoleErrorCounts: LinkedHashMap<Long, Int> = LinkedHashMap(),
        var replyProxy: JavaScriptReplyProxy? = null,
        var scriptHandler: ScriptHandler? = null
    )

    private val states = WeakHashMap<WebView, State>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetchPreload(preloadUrl: String, token: String): String {
        val url = Uri.parse(preloadUrl)
        if (url.scheme != "http" || url.host != "127.0.0.1" ||
            url.port !in 1..65535 || !Regex("^/__nexa/preload/[0-9]+$").matches(url.path.orEmpty()) ||
            url.query != null || url.fragment != null || url.userInfo != null || token.isBlank()) {
            throw IllegalArgumentException("electron preload URL is invalid")
        }
        val connection = (URL(preloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 10000
            instanceFollowRedirects = false
            setRequestProperty("X-Nexa-Token", token)
        }
        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("electron preload fetch failed (${connection.responseCode})")
            }
            val contentLength = connection.contentLength
            if (contentLength > MAX_PRELOAD_BYTES) {
                throw IllegalStateException("electron preload exceeds 8 MiB")
            }
            if (contentLength >= 0) {
                val bytes = ByteArray(contentLength)
                DataInputStream(connection.inputStream).use { it.readFully(bytes) }
                return String(bytes, Charsets.UTF_8)
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_PRELOAD_BYTES) {
                        throw IllegalStateException("electron preload exceeds 8 MiB")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    fun install(
        webView: WebView,
        origins: Set<String>,
        preload: String,
        aid: Int,
        viewId: Int,
        eventQueue: LinkedBlockingQueue<ConnectionHandler.Event>,
        ipcEndpoint: String?,
        ipcToken: String?,
        windowId: Int?
    ): String? {
        val normalizedOrigins = origins.mapNotNull { normalizeOrigin(Uri.parse(it)) }.toSet()
        if (origins.isEmpty() || origins.any { normalizeOrigin(Uri.parse(it)) == null }) {
            return "electron bridge needs exact http or https origins"
        }
        if (preload.isBlank()) return "electron bridge preload script is empty"
        if ((ipcEndpoint != null || ipcToken != null || windowId != null) &&
            (ipcEndpoint == null || ipcToken.isNullOrBlank() || windowId == null)) {
            return "electron ipc transport configuration is incomplete"
        }
        if (ipcEndpoint != null) {
            val endpoint = Uri.parse(ipcEndpoint)
            if (endpoint.scheme != "http" || endpoint.host != "127.0.0.1" ||
                endpoint.port !in 1..65535 || endpoint.path != "/__nexa/ipc" ||
                endpoint.query != null || endpoint.fragment != null || endpoint.userInfo != null) {
                return "electron ipc transport must use the local Nexa host"
            }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return "this Android WebView provider does not support the electron bridge"
        }

        val state = State(
            aid, viewId, eventQueue, ipcEndpoint, ipcToken, windowId,
            if (ipcEndpoint == null) null else ThreadPoolExecutor(
                0, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(8), { task ->
                    Thread(task, "nexa-electron-ipc-$viewId").apply { isDaemon = true }
                }
            )
        )
        synchronized(states) {
            states[webView] = state
        }
        WebViewCompat.addWebMessageListener(
            webView,
            "nexaElectronNative",
            normalizedOrigins
        ) { view, message: WebMessageCompat, sourceOrigin, isMainFrame, replyProxy ->
            val origin = normalizeOrigin(sourceOrigin)
            if (!isMainFrame || origin == null || origin !in normalizedOrigins) {
                return@addWebMessageListener
            }

            synchronized(states) {
                states[view]?.replyProxy = replyProxy
            }
            if (message.data == "{\"kind\":\"ready\"}") {
                enqueueBridgeMessage(aid, viewId, origin, message.data, eventQueue)
                return@addWebMessageListener
            }
            if (state.ipcExecutor != null) {
                if (message.data.length > MAX_IPC_MESSAGE_CHARS) {
                    rejectIpc(state, replyProxy, message.data,
                        "Electron IPC message exceeds 1 MiB")
                    return@addWebMessageListener
                }
                val data = message.data
                try {
                    state.ipcExecutor.execute {
                        val kind = ipcKindPattern.find(data)?.groupValues?.get(1)
                        if (kind != null) {
                            val requestId = if (kind == "invoke") {
                                invokeIdPattern.find(data)
                                    ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                            } else {
                                -1
                            }
                            dispatchIpc(state, replyProxy, data, kind,
                                requestId)
                        } else {
                            enqueueBridgeMessage(aid, viewId, origin, data, eventQueue)
                        }
                    }
                } catch (_: Exception) {
                    val message = if (state.closed) "Electron IPC transport is closed"
                        else "Electron IPC queue is full"
                    rejectIpc(state, replyProxy, data, message)
                }
                return@addWebMessageListener
            }
            enqueueBridgeMessage(aid, viewId, origin, message.data, eventQueue)
        }
        state.scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            preload + "\nif (window.nexaElectronNative) " +
                "window.nexaElectronNative.postMessage(JSON.stringify({kind: \"ready\"}));",
            normalizedOrigins
        )
        return null
    }

    fun reply(webView: WebView, message: String) {
        val proxy = synchronized(states) { states[webView]?.replyProxy }
        proxy?.postMessage(message)
    }

    fun sampleConsoleError(webView: WebView, message: String, source: String, line: Int): Int? {
        val state = synchronized(states) { states[webView] } ?: return 1
        val key = consoleErrorKey(message.take(1000), source.take(500), line)
        synchronized(state) {
            val count = (state.consoleErrorCounts[key] ?: 0) + 1
            if (key !in state.consoleErrorCounts && state.consoleErrorCounts.size >= 128) {
                state.consoleErrorCounts.remove(state.consoleErrorCounts.keys.first())
            }
            state.consoleErrorCounts[key] = count
            return count.takeIf { (it and (it - 1)) == 0 }
        }
    }

    private fun consoleErrorKey(message: String, source: String, line: Int): Long {
        var hash = hashConsoleErrorPart(-3750763034362895579L, message)
        hash = hashConsoleErrorPart(hash, source)
        hash = hashConsoleErrorPart(hash, line.toString())
        return hash
    }

    private fun hashConsoleErrorPart(seed: Long, value: String): Long {
        var hash = seed
        for (character in value) {
            hash = (hash xor character.code.toLong()) * 1099511628211L
        }
        return (hash xor 255L) * 1099511628211L
    }

    fun setVisible(webView: WebView, visible: Boolean) {
        val state = synchronized(states) { states[webView] }
        if (state != null) {
            synchronized(state) {
                if (state.closed || state.paused == !visible) return
                state.paused = !visible
            }
        }
        if (visible) webView.onResume() else webView.onPause()
    }

    fun remove(webView: WebView) {
        val state = synchronized(states) { states.remove(webView) } ?: return
        synchronized(state) {
            state.closed = true
            state.activeConnection?.disconnect()
            state.activeConnection = null
        }
        state.ipcExecutor?.shutdownNow()
        state.scriptHandler?.remove()
    }

    private fun dispatchIpc(
        state: State,
        replyProxy: JavaScriptReplyProxy,
        body: String,
        kind: String,
        requestId: Int
    ) {
        val requestBody = body.toByteArray(Charsets.UTF_8)
        if (requestBody.size > MAX_IPC_MESSAGE_BYTES) {
            finishIpc(state, replyProxy, requestId, kind,
                "Electron IPC message exceeds 1 MiB")
            return
        }
        val connection = try {
            (URL(state.ipcEndpoint!!).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 30000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        } catch (error: Exception) {
            finishIpc(state, replyProxy, requestId, kind, error.message)
            return
        }
        val active = synchronized(state) {
            if (state.closed) false
            else {
                state.activeConnection = connection
                true
            }
        }
        if (!active) {
            connection.disconnect()
            return
        }

        try {
            connection.setRequestProperty("X-Nexa-Token", state.ipcToken!!)
            connection.setRequestProperty("X-Nexa-Window-Id", state.windowId!!.toString())
            connection.setFixedLengthStreamingMode(requestBody.size)
            connection.outputStream.use { it.write(requestBody) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = JSONObject(response)
            val error = result.optString("error").takeIf { it.isNotEmpty() }
            if (status !in 200..299 && error == null) {
                finishIpc(state, replyProxy, requestId, kind, "Electron IPC failed ($status)")
            } else {
                if (error == null) {
                    synchronized(state) { state.sendErrorReported = false }
                }
                finishIpc(state, replyProxy, requestId, kind, error,
                    if (result.has("value")) result.opt("value") else JSONObject.NULL)
            }
        } catch (error: Exception) {
            finishIpc(state, replyProxy, requestId, kind, error.message)
        } finally {
            synchronized(state) {
                if (state.activeConnection === connection) state.activeConnection = null
            }
            connection.disconnect()
        }
    }

    private fun finishIpc(
        state: State,
        replyProxy: JavaScriptReplyProxy,
        requestId: Int,
        kind: String,
        error: String?,
        value: Any? = JSONObject.NULL
    ) {
        if (state.closed) return
        if (kind == "invoke") {
            val reply = JSONObject().put("kind", "reply").put("id", requestId)
            if (error == null) reply.put("value", value)
            else reply.put("error", error)
            val deliver = {
                if (synchronized(states) { states.values.any { it === state } }) {
                    try {
                        replyProxy.postMessage(reply.toString())
                    } catch (_: IllegalStateException) {
                        // the renderer can disappear while an ipc request is in flight
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) deliver()
            else mainHandler.post(deliver)
        } else if (error != null) {
            reportError(state, error)
        }
    }

    private fun rejectIpc(
        state: State,
        replyProxy: JavaScriptReplyProxy,
        data: String,
        error: String
    ) {
        val match = invokeIdPattern.find(data)
        val id = match?.groupValues?.get(1)?.toIntOrNull()
        if (id != null) finishIpc(state, replyProxy, id, "invoke", error)
        else reportError(state, error)
    }

    private fun enqueueBridgeMessage(
        aid: Int,
        viewId: Int,
        origin: String,
        data: String,
        eventQueue: LinkedBlockingQueue<ConnectionHandler.Event>
    ) {
        val event = mapOf("aid" to aid, "id" to viewId, "origin" to origin, "message" to data)
        eventQueue.offer(ConnectionHandler.Event(
            "webviewBridgeMessage", ConnectionHandler.gson.toJsonTree(event)
        ))
    }

    private fun reportError(state: State, error: String) {
        val shouldReport = synchronized(state) {
            if (state.closed || state.sendErrorReported) false
            else {
                state.sendErrorReported = true
                true
            }
        }
        if (!shouldReport) return
        val event = mapOf("aid" to state.aid, "id" to state.viewId, "error" to error)
        state.eventQueue.offer(ConnectionHandler.Event(
            "webviewBridgeError",
            ConnectionHandler.gson.toJsonTree(event)
        ))
    }

    private fun normalizeOrigin(uri: Uri): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if ('*' in host) return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.path?.isNotEmpty() == true && uri.path != "/") return null
        if (uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        val defaultPort = if (scheme == "https") 443 else 80
        return if (uri.port == -1 || uri.port == defaultPort) {
            "$scheme://$host"
        } else {
            "$scheme://$host:${uri.port}"
        }
    }
}
