package com.moneymanager.remotestorage.googledrive

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
            val (code, page) = redirect("code=a%2Fb&state=$STATE&scope=x")
            assertEquals("a/b", code)
            assertTrue("Google Drive is connected" in page, page)
        }

    @Test
    fun rejectsAMismatchedState() =
        runTest {
            val failure = assertFailsWith<RemoteAuthException> { redirect("code=c&state=forged") }
            assertTrue("unexpected state" in failure.message.orEmpty(), failure.message)
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

    private suspend fun redirect(query: String): Pair<String, String> =
        withContext(Dispatchers.Default) {
            LoopbackRedirectReceiver.bind().use { receiver ->
                coroutineScope {
                    // Held as a Result so a rejected callback doesn't cancel the request still reading the
                    // response: Curl's cancellation handler throws on native.
                    val code = async { runCatching { receiver.awaitCode(STATE, timeout = 30.seconds) } }
                    val page = platformHttpClient().use { it.get("${receiver.redirectUri}/?$query").bodyAsText() }
                    code.await().getOrThrow() to page
                }
            }
        }

    private companion object {
        const val STATE = "s1"
    }
}
