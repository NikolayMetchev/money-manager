package com.moneymanager.rest

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey

/**
 * Strong Customer Authentication challenge-signing parameters. When a request is rejected with
 * [triggerStatus] and carries a [challengeHeader] one-time token, the token is signed via [sign] and
 * the request is retried once with the challenge echoed back plus the signature in [signatureHeader].
 * This is generic; the header names and status come from the provider's strategy config.
 */
class ScaParams(
    val challengeHeader: String,
    val signatureHeader: String,
    val triggerStatus: Int,
    val sign: (oneTimeToken: String) -> String,
)

class ApiClient(
    private val httpClient: HttpClient,
) {
    suspend fun get(
        url: String,
        bearerToken: String,
        sca: ScaParams? = null,
    ): ApiHttpResponse {
        val response =
            httpClient.get(url) {
                bearerAuth(bearerToken)
            }
        if (sca != null && response.status.value == sca.triggerStatus) {
            val oneTimeToken = response.headers[sca.challengeHeader]
            if (!oneTimeToken.isNullOrBlank()) {
                val signature = sca.sign(oneTimeToken)
                val signed =
                    httpClient.get(url) {
                        bearerAuth(bearerToken)
                        header(sca.challengeHeader, oneTimeToken)
                        header(sca.signatureHeader, signature)
                    }
                return signed.toApiHttpResponse()
            }
        }
        return response.toApiHttpResponse()
    }

    private suspend fun HttpResponse.toApiHttpResponse(): ApiHttpResponse =
        ApiHttpResponse(
            statusCode = status.value,
            body = call.attributes.getOrNull(apiResponseBodyKey) ?: bodyAsText(),
            responseId = call.attributes[apiResponseIdKey],
            requestId = call.attributes[apiRequestIdKey],
        )
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
