package com.moneymanager.rest

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApiClientTest {
    @Test
    fun `get records request and response traffic`() =
        runTest {
            val recorder = FakeTrafficRecorder()
            val httpClient =
                HttpClient(
                    MockEngine {
                        respond(
                            content = """{"ok":true}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val client = createApiClient(trafficRecorder = recorder, httpClient = httpClient)

            val response = client.get(url = "https://example.test/transactions", bearerToken = "secret-token")

            assertEquals(200, response.statusCode)
            assertEquals("""{"ok":true}""", response.body)
            assertEquals(1L, response.requestId)
            assertEquals(2L, response.responseId)
            assertEquals("GET", recorder.requestMethod)
            assertEquals("https://example.test/transactions", recorder.requestUrl)
            assertFalse(HttpHeaders.Authorization in recorder.requestHeaders)
            assertEquals("""{"ok":true}""", recorder.responseBody)
        }

    private class FakeTrafficRecorder : ApiTrafficRecorder {
        var requestMethod: String? = null
        var requestUrl: String? = null
        var requestHeaders: Map<String, String> = emptyMap()
        var responseBody: String? = null

        override suspend fun recordRequest(
            method: String,
            url: String,
            headers: Map<String, String>,
        ): Long {
            requestMethod = method
            requestUrl = url
            requestHeaders = headers
            return 1L
        }

        override suspend fun recordResponse(
            requestId: Long,
            body: String,
        ): Long {
            assertEquals(1L, requestId)
            responseBody = body
            return 2L
        }
    }
}
