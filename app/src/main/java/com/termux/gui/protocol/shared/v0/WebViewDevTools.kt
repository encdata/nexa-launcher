package com.termux.gui.protocol.shared.v0

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.webkit.WebView
import org.json.JSONArray
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

object WebViewDevTools {
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "nexa-webview-devtools").apply { isDaemon = true }
    }
    @Volatile private var server: ServerSocket? = null
    @Volatile private var token: String? = null
    @Volatile private var startupError: Exception? = null
    private val leases = mutableSetOf<Int>()

    @Synchronized
    fun enable(viewId: Int) {
        WebView.setWebContentsDebuggingEnabled(true)
        leases.add(viewId)
    }

    @Synchronized
    fun release(viewId: Int) {
        leases.remove(viewId)
        if (leases.isNotEmpty()) return
        WebView.setWebContentsDebuggingEnabled(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        token = null
        startupError = null
    }

    @Synchronized
    private fun startProxy() {
        if (server != null) return
        startupError?.let { throw IllegalStateException("WebView DevTools proxy failed", it) }
        val accessToken = UUID.randomUUID().toString().replace("-", "")
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        token = accessToken
        server = listener
        workers.execute {
            try {
                while (!listener.isClosed) {
                    val client = listener.accept()
                    workers.execute { proxy(client, accessToken) }
                }
            } catch (error: Exception) {
                if (!listener.isClosed) startupError = error
            }
        }
    }

    fun frontendUrl(viewId: Int, url: String): String {
        val (listener, accessToken) = synchronized(this) {
            if (viewId !in leases) {
                throw IllegalStateException("WebView DevTools is not enabled")
            }
            startProxy()
            Pair(
                server ?: throw IllegalStateException("WebView DevTools proxy is unavailable"),
                token ?: throw IllegalStateException("WebView DevTools token is unavailable")
            )
        }
        val endpoint = URL("http://127.0.0.1:${listener.localPort}/__nexa/$accessToken/json/list")
        val connection = endpoint.openConnection()
        connection.connectTimeout = 3000
        connection.readTimeout = 5000
        val targets = connection.getInputStream().bufferedReader(Charsets.UTF_8).use {
            JSONArray(it.readText())
        }
        val matching = (0 until targets.length()).mapNotNull { index ->
            targets.optJSONObject(index)?.takeIf {
                it.optString("type") == "page" && it.optString("url") == url
            }
        }
        if (matching.size != 1) {
            throw IllegalStateException(
                if (matching.isEmpty()) "WebView page is not available to inspect"
                else "multiple WebView pages share this URL; target selection is required"
            )
        }
        val target = matching.single()
        val frontend = target.optString("devtoolsFrontendUrl")
        val targetId = target.optString("id")
        if (frontend.isBlank() || targetId.isBlank()) {
            throw IllegalStateException("WebView provider did not expose a DevTools target")
        }
        val websocket = "127.0.0.1:${listener.localPort}/__nexa/$accessToken/devtools/page/$targetId"
        val wsParameter = Regex("([?&]ws=)[^&]*")
        if (!wsParameter.containsMatchIn(frontend)) {
            throw IllegalStateException("WebView provider returned an invalid DevTools URL")
        }
        return wsParameter.replace(frontend) { match -> match.groupValues[1] + websocket }
    }

    private fun proxy(client: Socket, accessToken: String) {
        var upstream: LocalSocket? = null
        try {
            client.soTimeout = 5000
            val input = client.getInputStream()
            val requestLine = readLine(input)
            client.soTimeout = 0
            val parts = requestLine.split(' ', limit = 3)
            val prefix = "/__nexa/$accessToken"
            if (parts.size != 3 || !parts[1].startsWith("$prefix/")) {
                client.getOutputStream().write(
                    "HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                return
            }
            val targetPath = parts[1].removePrefix(prefix)
            val remote = LocalSocket().apply {
                connect(LocalSocketAddress(
                    "webview_devtools_remote_${Process.myPid()}",
                    LocalSocketAddress.Namespace.ABSTRACT
                ))
            }
            upstream = remote
            remote.outputStream.write(
                "${parts[0]} $targetPath ${parts[2]}\r\n".toByteArray(Charsets.US_ASCII)
            )
            remote.outputStream.flush()
            val toRemote = workers.submit {
                input.copyTo(remote.outputStream)
                remote.shutdownOutput()
            }
            client.getOutputStream().use { output -> remote.inputStream.copyTo(output) }
            toRemote.cancel(true)
        } catch (_: Exception) {
            // The frontend closes websocket connections while navigating between panels.
        } finally {
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: java.io.InputStream): String {
        val bytes = ArrayList<Byte>(256)
        var previous = -1
        while (bytes.size < 8192) {
            val value = input.read()
            if (value < 0) throw IllegalStateException("DevTools connection closed before request")
            if (previous == '\r'.code && value == '\n'.code) {
                bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes.add(value.toByte())
            previous = value
        }
        throw IllegalStateException("DevTools request line is too long")
    }
}
