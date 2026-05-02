package com.moneymanager.rest

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApiClientTest {
    private val testBody = """{"ok":true}"""
    private val testUrl = "https://example.test/data"
    private val testToken = "test-bearer-token"
    private val testResponseId = 42L
    private val testRequestId = 7L

    @Test
    fun `get returns status code and body from response`() =
        runTest {
            val recorder = FakeTrafficRecorder(testRequestId, testResponseId)
            val engine =
                MockEngine { _ ->
                    respond(
                        content = testBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = createApiClient(recorder, engine)

            val result = client.get(testUrl, testToken)

            assertEquals(200, result.statusCode)
            assertEquals(testBody, result.body)
        }

    @Test
    fun `get records request and response via trafficRecorder`() =
        runTest {
            val recorder = FakeTrafficRecorder(testRequestId, testResponseId)
            val engine =
                MockEngine { _ ->
                    respond(
                        content = testBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = createApiClient(recorder, engine)

            client.get(testUrl, testToken)

            assertNotNull(recorder.lastRecordedRequest)
            assertEquals(testUrl, recorder.lastRecordedRequest!!.url)
            assertEquals("GET", recorder.lastRecordedRequest!!.method)
            assertEquals(testBody, recorder.lastRecordedResponseBody)
        }

    @Test
    fun `get sets responseId and requestId from trafficRecorder`() =
        runTest {
            val recorder = FakeTrafficRecorder(testRequestId, testResponseId)
            val engine =
                MockEngine { _ ->
                    respond(
                        content = testBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = createApiClient(recorder, engine)

            val result = client.get(testUrl, testToken)

            assertEquals(testResponseId, result.responseId)
            assertEquals(testRequestId, result.requestId)
        }

    @Test
    fun `get does not record Authorization header`() =
        runTest {
            val recorder = FakeTrafficRecorder(testRequestId, testResponseId)
            val engine =
                MockEngine { _ ->
                    respond(
                        content = testBody,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = createApiClient(recorder, engine)

            client.get(testUrl, testToken)

            val recordedHeaders = recorder.lastRecordedRequest?.headers ?: emptyMap()
            assertEquals(false, recordedHeaders.containsKey(HttpHeaders.Authorization))
        }
}

private data class RecordedRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
)

private class FakeTrafficRecorder(
    private val requestIdToReturn: Long,
    private val responseIdToReturn: Long,
) : ApiTrafficRecorder {
    var lastRecordedRequest: RecordedRequest? = null
    var lastRecordedResponseBody: String? = null

    override suspend fun recordRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): Long {
        lastRecordedRequest = RecordedRequest(method, url, headers)
        return requestIdToReturn
    }

    override suspend fun recordResponse(
        requestId: Long,
        body: String,
    ): Long {
        lastRecordedResponseBody = body
        return responseIdToReturn
    }
}
