package com.moneymanager.rest

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.repository.ApiSessionRepository
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
            response.call.attributes.getOrNull(apiResponseBodyKey)
                ?: response.bodyAsText()

        return ApiHttpResponse(
            statusCode = response.status.value,
            body = body,
            responseId = response.call.attributes[apiResponseIdKey],
            requestId = response.call.attributes[apiRequestIdKey],
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

fun createApiClient(trafficRecorder: ApiTrafficRecorder): ApiClient =
    createApiClient(
        trafficRecorder = trafficRecorder,
        httpClient = HttpClient(),
    )

internal fun createApiClient(
    trafficRecorder: ApiTrafficRecorder,
    httpClient: HttpClient,
): ApiClient {
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
        call.attributes.put(apiResponseBodyKey, responseBody)
        call.attributes.put(apiResponseIdKey, responseId)
        call.attributes.put(apiRequestIdKey, requestId)

        call
    }

    return ApiClient(httpClient)
}

class ApiSessionTrafficRecorder(
    private val sessionId: ApiSessionId,
    private val apiSessionRepository: ApiSessionRepository,
) : ApiTrafficRecorder {
    override suspend fun recordRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
    ): Long =
        apiSessionRepository
            .insertRequest(
                sessionId = sessionId,
                method = method,
                url = url,
                headers = headers,
            ).id

    override suspend fun recordResponse(
        requestId: Long,
        body: String,
    ): Long =
        apiSessionRepository
            .insertResponse(
                requestId = ApiRequestId(requestId),
                sessionId = sessionId,
                json = body,
            ).id
}

private val apiResponseBodyKey = AttributeKey<String>("ApiResponseBody")
private val apiResponseIdKey = AttributeKey<Long>("ApiResponseId")
private val apiRequestIdKey = AttributeKey<Long>("ApiRequestId")
