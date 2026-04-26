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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

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
        apiSessionRepository.insertRequest(
            sessionId = sessionId,
            requestedAt = Clock.System.now(),
            json =
                buildJsonObject {
                    put("method", request.method.value)
                    put("url", request.url.buildString())
                }.toString(),
            headers =
                request.headers
                    .entries()
                    .associate { (key, values) -> key to values.joinToString(",") }
                    .filterKeys { it != HttpHeaders.Authorization },
        )

        val call = execute(request)
        val responseBody = call.response.bodyAsText()

        apiSessionRepository.insertResponse(
            sessionId = sessionId,
            respondedAt = Clock.System.now(),
            json = responseBody,
        )
        call.attributes.put(MonzoApiResponseBodyKey, responseBody)

        call
    }

    return MonzoApiClient(httpClient)
}

private val MonzoApiResponseBodyKey = AttributeKey<String>("MonzoApiResponseBody")
