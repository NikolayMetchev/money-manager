@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
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

class MonzoApiClient(
    private val httpClient: HttpClient,
) {
    suspend fun get(
        url: String,
        bearerToken: String,
    ): MonzoHttpResponse {
        val response =
            httpClient.get(url) {
                bearerAuth(bearerToken)
            }
        val body =
            response.call.attributes.getOrNull(MonzoApiResponseBodyKey)
                ?: response.bodyAsText()
        val responseId = response.call.attributes.getOrNull(MonzoApiResponseIdKey)
        val requestId = response.call.attributes.getOrNull(MonzoApiRequestIdKey)

        return MonzoHttpResponse(
            statusCode = response.status.value,
            body = body,
            responseId = responseId,
            requestId = requestId,
        )
    }
}

data class MonzoHttpResponse(
    val statusCode: Int,
    val body: String,
    val responseId: ApiResponseId? = null,
    val requestId: ApiRequestId? = null,
)

fun createMonzoApiClient(
    sessionId: ApiSessionId,
    apiSessionRepository: ApiSessionRepository,
): MonzoApiClient {
    val httpClient = HttpClient()

    httpClient.plugin(HttpSend).intercept { request ->
        val requestId =
            apiSessionRepository.insertRequest(
                sessionId = sessionId,
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

        val responseId =
            apiSessionRepository.insertResponse(
                requestId = requestId,
                sessionId = sessionId,
                json = responseBody,
            )
        call.attributes.put(MonzoApiResponseBodyKey, responseBody)
        call.attributes.put(MonzoApiResponseIdKey, responseId)
        call.attributes.put(MonzoApiRequestIdKey, requestId)

        call
    }

    return MonzoApiClient(httpClient)
}

private val MonzoApiResponseBodyKey = AttributeKey<String>("MonzoApiResponseBody")
private val MonzoApiResponseIdKey = AttributeKey<ApiResponseId>("MonzoApiResponseId")
private val MonzoApiRequestIdKey = AttributeKey<ApiRequestId>("MonzoApiRequestId")
