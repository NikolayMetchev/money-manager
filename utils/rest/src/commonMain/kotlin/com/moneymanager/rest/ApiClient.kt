package com.moneymanager.rest

import io.ktor.client.HttpClient
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
            response.call.attributes.getOrNull(ApiResponseBodyKey)
                ?: response.bodyAsText()
        val responseId = response.call.attributes.getOrNull(ApiResponseIdKey)
        val requestId = response.call.attributes.getOrNull(ApiRequestIdKey)

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
    val responseId: Long? = null,
    val requestId: Long? = null,
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

fun createApiClient(trafficRecorder: ApiTrafficRecorder? = null): ApiClient {
    val httpClient = HttpClient()

    if (trafficRecorder != null) {
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
            val responseId = trafficRecorder.recordResponse(requestId, responseBody)
            call.attributes.put(ApiResponseBodyKey, responseBody)
            call.attributes.put(ApiResponseIdKey, responseId)
            call.attributes.put(ApiRequestIdKey, requestId)

            call
        }
    }

    return ApiClient(httpClient)
}

private val ApiResponseBodyKey = AttributeKey<String>("ApiResponseBody")
private val ApiResponseIdKey = AttributeKey<Long>("ApiResponseId")
private val ApiRequestIdKey = AttributeKey<Long>("ApiRequestId")
