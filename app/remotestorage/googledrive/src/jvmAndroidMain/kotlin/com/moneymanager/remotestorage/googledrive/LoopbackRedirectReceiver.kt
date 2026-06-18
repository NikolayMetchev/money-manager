package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A minimal localhost HTTP listener that captures the OAuth authorization code redirected to
 * `http://127.0.0.1:<port>` after the user consents in their browser. Loopback is the only redirect a
 * Google "Desktop app" OAuth client permits, and a raw [ServerSocket] works on both JVM and Android
 * (no Jetty), so this is the one redirect path shared by both platforms.
 *
 * Usage: [start] to bind and learn the redirect URI, open the browser, then [awaitCode]. Always
 * [close] (a `use {}` block) so the socket is released.
 */
class LoopbackRedirectReceiver : AutoCloseable {
    private val serverSocket: ServerSocket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))

    /** The `redirect_uri` to register with Google for this attempt (a freshly bound loopback port). */
    val redirectUri: String = "http://$LOOPBACK_HOST:${serverSocket.localPort}"

    /**
     * Waits (up to [timeout]) for the browser to hit the loopback redirect, then returns the `code`
     * query parameter. Throws [RemoteAuthException] on an OAuth `error`, a timeout, or a malformed
     * request.
     */
    suspend fun awaitCode(timeout: Duration = DEFAULT_TIMEOUT): String =
        withContext(Dispatchers.IO) {
            val requestTarget =
                withTimeoutOrNull(timeout) { acceptRequestTarget() }
                    ?: throw RemoteAuthException("Timed out waiting for Google sign-in to complete")
            extractCode(requestTarget)
        }

    private fun acceptRequestTarget(): String? =
        serverSocket.accept().use { socket ->
            val requestLine =
                socket
                    .getInputStream()
                    .bufferedReader()
                    .readLine()
                    .orEmpty()
            socket.getOutputStream().writer().apply {
                write(RESPONSE_BODY)
                flush()
            }
            // Request line looks like: "GET /?code=...&scope=... HTTP/1.1"
            requestLine.split(' ').getOrNull(1)
        }

    private fun extractCode(requestTarget: String?): String {
        val query = requestTarget?.substringAfter('?', "").orEmpty()
        val params =
            query
                .split('&')
                .mapNotNull { pair ->
                    val key = pair.substringBefore('=', "")
                    if (key.isEmpty()) null else key to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                }.toMap()
        params["error"]?.let { throw RemoteAuthException("Google sign-in was denied: $it") }
        return params["code"] ?: throw RemoteAuthException("Google sign-in did not return an authorization code")
    }

    override fun close() {
        serverSocket.close()
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        val DEFAULT_TIMEOUT = 5.minutes
        val RESPONSE_BODY =
            buildString {
                val page =
                    "<html><body style=\"font-family:sans-serif\">" +
                        "<h2>Money Manager</h2><p>Google Drive is connected. You can close this tab " +
                        "and return to the app.</p></body></html>"
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${page.encodeToByteArray().size}\r\n")
                append("Connection: close\r\n\r\n")
                append(page)
            }
    }
}
