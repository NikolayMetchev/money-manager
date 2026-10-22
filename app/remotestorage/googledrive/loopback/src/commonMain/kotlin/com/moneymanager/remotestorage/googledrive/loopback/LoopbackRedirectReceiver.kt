package com.moneymanager.remotestorage.googledrive.loopback

import com.moneymanager.coroutines.ioDispatcher
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
     * query parameter. Requests whose `state` isn't [expectedState] (another local client, a stray probe,
     * a forged redirect) are answered and ignored, so they can neither end nor hijack the sign-in. Throws
     * `RemoteAuthException` on a timeout, or when the genuine callback reports an OAuth `error` or no code.
     */
    suspend fun awaitCode(
        expectedState: String,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): String =
        withTimeoutOrNull(timeout) { acceptCallback(expectedState) }
            ?: throw RemoteAuthException("Timed out waiting for Google sign-in to complete")

    private suspend fun acceptCallback(expectedState: String): String {
        while (true) {
            val outcome =
                serverSocket.accept().use { socket ->
                    val requestLine =
                        withTimeoutOrNull(REQUEST_READ_TIMEOUT) { socket.openReadChannel().readLine() }.orEmpty()
                    // Request line looks like: "GET /?code=...&state=... HTTP/1.1"
                    val params =
                        parseQueryString(
                            requestLine
                                .split(' ')
                                .getOrNull(1)
                                .orEmpty()
                                .substringAfter('?', ""),
                        )
                    val outcome = if (params["state"] == expectedState) callbackOutcome(params) else null
                    socket.openWriteChannel().apply {
                        writeStringUtf8(response(outcome))
                        flushAndClose()
                    }
                    outcome
                }
            if (outcome != null) return outcome.getOrThrow()
        }
    }

    private fun callbackOutcome(params: Parameters): Result<String> =
        runCatching {
            params["error"]?.let { throw RemoteAuthException("Google sign-in was denied: $it") }
            params["code"] ?: throw RemoteAuthException("Google sign-in did not return an authorization code")
        }

    override fun close() {
        serverSocket.close()
        selector.close()
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private val REQUEST_READ_TIMEOUT = 10.seconds
        private val DEFAULT_TIMEOUT = 5.minutes
        private const val SIGNED_IN_PAGE = "Google Drive is connected. You can close this tab and return to the app."
        private const val FAILED_PAGE = "Google sign-in didn't complete. You can close this tab and return to the app."

        // A request that isn't the expected callback gets a 404, so nothing but the real redirect sees a page.
        private fun response(outcome: Result<String>?): String {
            val (status, message) =
                when {
                    outcome == null -> "404 Not Found" to "Not found."
                    outcome.isSuccess -> "200 OK" to SIGNED_IN_PAGE
                    else -> "200 OK" to FAILED_PAGE
                }
            val page = "<html><body style=\"font-family:sans-serif\"><h2>Money Manager</h2><p>$message</p></body></html>"
            return "HTTP/1.1 $status\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${page.encodeToByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                page
        }

        /** Binds a fresh loopback port for one sign-in attempt. */
        suspend fun bind(): LoopbackRedirectReceiver {
            val selector = SelectorManager(ioDispatcher)
            val serverSocket =
                runCatching { aSocket(selector).tcp().bind(LOOPBACK_HOST, 0) }
                    .onFailure { selector.close() }
                    .getOrThrow()
            return LoopbackRedirectReceiver(selector, serverSocket)
        }
    }
}
