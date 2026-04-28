@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

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

        return MonzoHttpResponse(
            statusCode = response.status.value,
            body = body,
        )
    }
}

data class MonzoHttpResponse(
    val statusCode: Int,
    val body: String,
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

        apiSessionRepository.insertResponse(
            requestId = requestId,
            sessionId = sessionId,
            json = responseBody,
        )
        call.attributes.put(MonzoApiResponseBodyKey, responseBody)

        call
    }

    return MonzoApiClient(httpClient)
}

private val MonzoApiResponseBodyKey = AttributeKey<String>("MonzoApiResponseBody")
