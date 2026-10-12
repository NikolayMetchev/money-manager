package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A minimal localhost HTTP listener that captures the OAuth authorization code redirected to
 * `http://127.0.0.1:<port>` after the user consents in their browser. Loopback is the only redirect a
 * Google "Desktop app" OAuth client permits. It reads a single request line over Ktor's raw sockets,
 * so the same code runs on JVM, Android and native.
 *
 * Usage: [bind] to learn the redirect URI, open the browser, then [awaitCode]. Always [close] (a `use {}`
 * block) so the socket is released.
 */
internal class LoopbackRedirectReceiver private constructor(
    private val selector: SelectorManager,
    private val serverSocket: ServerSocket,
) : AutoCloseable {
    /** The `redirect_uri` to register with Google for this attempt (a freshly bound loopback port). */
    val redirectUri: String = "http://$LOOPBACK_HOST:${(serverSocket.localAddress as InetSocketAddress).port}"

    /**
     * Waits (up to [timeout]) for the browser to hit the loopback redirect, then returns the `code`
     * query parameter after checking the callback's `state` equals [expectedState]. Throws
     * `RemoteAuthException` on an OAuth `error`, a timeout, a state mismatch, or a malformed request.
     */
    suspend fun awaitCode(
        expectedState: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): String {
        val requestTarget =
            withTimeoutOrNull(timeout) { acceptRequestTarget() }
                ?: throw RemoteAuthException("Timed out waiting for Google sign-in to complete")
        return extractCode(requestTarget, expectedState)
    }

    private suspend fun acceptRequestTarget(): String =
        serverSocket.accept().use { socket ->
            val requestLine = withTimeoutOrNull(REQUEST_READ_TIMEOUT) { socket.openReadChannel().readLine() }.orEmpty()
            socket.openWriteChannel().apply {
                writeStringUtf8(RESPONSE_BODY)
                flushAndClose()
            }
            // Request line looks like: "GET /?code=...&scope=... HTTP/1.1"
            requestLine.split(' ').getOrNull(1).orEmpty()
        }

    private fun extractCode(
        requestTarget: String,
        expectedState: String,
    ): String {
        val params = parseQueryString(requestTarget.substringAfter('?', ""))
        rejectErrorOrBadState(params, expectedState)
        return params["code"] ?: throw RemoteAuthException("Google sign-in did not return an authorization code")
    }

    private fun rejectErrorOrBadState(
        params: Parameters,
        expectedState: String,
    ) {
        params["error"]?.let { throw RemoteAuthException("Google sign-in was denied: $it") }
        if (params["state"] != expectedState) {
            throw RemoteAuthException("Google sign-in returned an unexpected state; aborting to prevent CSRF")
        }
    }

    override fun close() {
        serverSocket.close()
        selector.close()
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private val REQUEST_READ_TIMEOUT = 10.seconds
        private val DEFAULT_TIMEOUT = 5.minutes
        private val RESPONSE_BODY =
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

        /** Binds a fresh loopback port for one sign-in attempt. */
        suspend fun bind(): LoopbackRedirectReceiver {
            val selector = SelectorManager(Dispatchers.IO)
            val serverSocket =
                runCatching { aSocket(selector).tcp().bind(LOOPBACK_HOST, 0) }
                    .onFailure { selector.close() }
                    .getOrThrow()
            return LoopbackRedirectReceiver(selector, serverSocket)
        }
    }
}
