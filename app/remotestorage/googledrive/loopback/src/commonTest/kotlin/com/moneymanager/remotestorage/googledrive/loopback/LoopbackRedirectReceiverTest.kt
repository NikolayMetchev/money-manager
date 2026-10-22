package com.moneymanager.remotestorage.googledrive.loopback

import com.moneymanager.httpclient.platformHttpClient
import com.moneymanager.remotestorage.RemoteAuthException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Real sockets and a real HTTP client on the platform engine, so this also checks each engine end to end.
// The work runs on Dispatchers.Default because runTest's virtual clock would fire the receiver's timeouts
// before a real request could arrive.
class LoopbackRedirectReceiverTest {
    @Test
    fun returnsTheUrlDecodedAuthorizationCode() =
        runTest {
            val (code, pages) = redirect("code=a%2Fb&state=$STATE&scope=x")
            assertEquals("a/b", code)
            assertTrue("Google Drive is connected" in pages.single(), pages.single())
        }

    @Test
    fun ignoresRequestsWithoutTheExpectedStateAndKeepsListening() =
        runTest {
            val (code, pages) = redirect("code=forged&state=forged", "favicon.ico", "code=real&state=$STATE")
            assertEquals("real", code)
            assertTrue("Not found" in pages[0], pages[0])
            assertTrue("Not found" in pages[1], pages[1])
            assertTrue("Google Drive is connected" in pages[2], pages[2])
        }

    @Test
    fun reportsAnOAuthError() =
        runTest {
            val failure = assertFailsWith<RemoteAuthException> { redirect("error=access_denied&state=$STATE") }
            assertTrue("access_denied" in failure.message.orEmpty(), failure.message)
        }

    @Test
    fun timesOutWhenTheBrowserNeverCallsBack() =
        runTest {
            val failure =
                assertFailsWith<RemoteAuthException> {
                    withContext(Dispatchers.Default) {
                        LoopbackRedirectReceiver.bind().use { it.awaitCode(STATE, timeout = 200.milliseconds) }
                    }
                }
            assertTrue("Timed out" in failure.message.orEmpty(), failure.message)
        }

    // Sends each request in turn (a bare path or a `?` query) and returns the code plus every page served.
    private suspend fun redirect(vararg requests: String): Pair<String, List<String>> =
        withContext(Dispatchers.Default) {
            LoopbackRedirectReceiver.bind().use { receiver ->
                coroutineScope {
                    // Held as a Result so a rejected callback doesn't cancel the request still reading the
                    // response: Curl's cancellation handler throws on native.
                    val code = async { runCatching { receiver.awaitCode(STATE, timeout = 30.seconds) } }
                    val pages =
                        platformHttpClient().use { client ->
                            requests.map { request ->
                                val path = if ('=' in request) "?$request" else request
                                client.get("${receiver.redirectUri}/$path").bodyAsText()
                            }
                        }
                    code.await().getOrThrow() to pages
                }
            }
        }

    private companion object {
        const val STATE = "s1"
    }
}
