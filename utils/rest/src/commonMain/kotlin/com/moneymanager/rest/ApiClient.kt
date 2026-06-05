package com.moneymanager.rest

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey

class ApiClient(
    private val httpClient: HttpClient,
) {
    suspend fun get(
        url: String,
        bearerToken: String,
    ): ApiHttpResponse {
        val response =
            httpClient.get(url) {
                bearerAuth(bearerToken)
            }
        val body =
            response.call.attributes.getOrNull(apiResponseBodyKey)
                ?: response.bodyAsText()
        val responseId = response.call.attributes[apiResponseIdKey]
        val requestId = response.call.attributes[apiRequestIdKey]

        return ApiHttpResponse(
            statusCode = response.status.value,
            body = body,
            responseId = responseId,
            requestId = requestId,
        )
    }
}

data class ApiHttpResponse(
    val statusCode: Int,
    val body: String,
    val responseId: Long,
    val requestId: Long,
)

interface ApiTrafficRecorder {
    suspend fun recordRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): Long

    suspend fun recordResponse(
        requestId: Long,
        body: String,
    ): Long
}

fun createApiClient(
    trafficRecorder: ApiTrafficRecorder,
    engine: HttpClientEngine?,
): ApiClient {
    val httpClient = if (engine != null) HttpClient(engine) else HttpClient()

    httpClient.plugin(HttpSend).intercept { request ->
        val requestId =
            trafficRecorder.recordRequest(
                method = request.method.value,
                url = request.url.buildString(),
                headers =
                    request.headers
                        .entries()
                        .associate { (key, values) -> key to values.joinToString(",") }
                        .filterKeys { it != HttpHeaders.Authorization },
            )

        val call = execute(request)
        val responseBody = call.response.bodyAsText()
        // Only persist non-blank bodies: the api_response.json column rejects empty values, and an
        // empty body (e.g. an error or no-content response) carries nothing importable. The caller
        // still sees the status code and can surface a meaningful error.
        val responseId = if (responseBody.isNotBlank()) trafficRecorder.recordResponse(requestId, responseBody) else NO_RESPONSE_ID
        call.attributes.put(apiResponseBodyKey, responseBody)
        call.attributes.put(apiResponseIdKey, responseId)
        call.attributes.put(apiRequestIdKey, requestId)

        call
    }

    return ApiClient(httpClient)
}

/** Sentinel response id used when an empty body is not persisted (see the traffic interceptor). */
const val NO_RESPONSE_ID: Long = -1L

private val apiResponseBodyKey = AttributeKey<String>("ApiResponseBody")
private val apiResponseIdKey = AttributeKey<Long>("ApiResponseId")
private val apiRequestIdKey = AttributeKey<Long>("ApiRequestId")
