@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.repository.ApiSessionRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

private const val MONZO_BASE_URL = "https://api.monzo.com"
private const val TRANSACTION_PAGE_LIMIT = 100

data class MonzoImportResult(
    val accountCount: Int,
    val transactionCount: Int,
)

suspend fun importMonzoData(
    token: String,
    sessionId: ApiSessionId,
    apiSessionRepository: ApiSessionRepository,
    apiClient: MonzoApiClient,
): MonzoImportResult {
    val accountIds =
        fetchAndStore(
            url = "$MONZO_BASE_URL/accounts",
            token = token,
            sessionId = sessionId,
            apiSessionRepository = apiSessionRepository,
            apiClient = apiClient,
        ).let { parseIds(it, "accounts") }

    var transactionCount = 0

    for (accountId in accountIds) {
        var sinceId: String? = null
        do {
            val url = buildTransactionUrl(accountId, sinceId)
            val body =
                fetchAndStore(
                    url = url,
                    token = token,
                    sessionId = sessionId,
                    apiSessionRepository = apiSessionRepository,
                    apiClient = apiClient,
                )
            val txIds = parseIds(body, "transactions")
            transactionCount += txIds.size
            sinceId = txIds.lastOrNull()
        } while (txIds.isNotEmpty())
    }

    return MonzoImportResult(
        accountCount = accountIds.size,
        transactionCount = transactionCount,
    )
}

private suspend fun fetchAndStore(
    url: String,
    token: String,
    sessionId: ApiSessionId,
    apiSessionRepository: ApiSessionRepository,
    apiClient: MonzoApiClient,
): String {
    apiSessionRepository.insertRequest(
        sessionId = sessionId,
        requestedAt = Clock.System.now(),
        json =
            buildJsonObject {
                put("method", "GET")
                put("url", url)
            }.toString(),
        headers = mapOf("Authorization" to "Bearer $token"),
    )

    val response = apiClient.get(url = url, bearerToken = token)

    apiSessionRepository.insertResponse(
        sessionId = sessionId,
        respondedAt = Clock.System.now(),
        json = response.body,
    )

    if (response.statusCode != 200) {
        throw MonzoApiException("HTTP ${response.statusCode}: ${response.body}")
    }

    return response.body
}

class MonzoApiException(
    message: String,
) : Exception(message)

private fun parseIds(
    json: String,
    arrayKey: String,
): List<String> =
    try {
        Json
            .parseToJsonElement(json)
            .jsonObject[arrayKey]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

private fun buildTransactionUrl(
    accountId: String,
    sinceId: String?,
): String {
    val base = "$MONZO_BASE_URL/transactions?account_id=$accountId&limit=$TRANSACTION_PAGE_LIMIT"
    return if (sinceId != null) "$base&since=$sinceId" else base
}
