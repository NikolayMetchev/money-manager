@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import io.ktor.http.URLBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

private const val MONZO_BASE_URL = "https://api.monzo.com"
private const val TRANSACTION_PAGE_LIMIT = 100

data class MonzoImportResult(
    val accountCount: Int,
    val transactionCount: Int,
)

suspend fun importMonzoData(
    token: String,
    apiClient: MonzoApiClient,
): MonzoImportResult {
    val accountIds =
        fetch(
            url = "$MONZO_BASE_URL/accounts",
            token = token,
            apiClient = apiClient,
        ).let { parseIds(it, "accounts") }

    var transactionCount = 0

    for (accountId in accountIds) {
        var before: Instant? = null
        do {
            val url = buildTransactionUrl(accountId, before)
            val body =
                fetch(
                    url = url,
                    token = token,
                    apiClient = apiClient,
                )
            val transactions = parseTransactions(body)
            transactionCount += transactions.size
            before = transactions.map { it.created }.minOrNull()
        } while (transactions.isNotEmpty())
    }

    return MonzoImportResult(
        accountCount = accountIds.size,
        transactionCount = transactionCount,
    )
}

private suspend fun fetch(
    url: String,
    token: String,
    apiClient: MonzoApiClient,
): String {
    val response = apiClient.get(url = url, bearerToken = token)
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

private data class MonzoTransactionPageItem(
    val created: Instant,
)

private fun parseTransactions(json: String): List<MonzoTransactionPageItem> =
    try {
        Json
            .parseToJsonElement(json)
            .jsonObject["transactions"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val obj = element.jsonObject
                val created = obj["created"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it) }

                created?.let { MonzoTransactionPageItem(created = it) }
            }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

private fun buildTransactionUrl(
    accountId: String,
    before: Instant?,
): String =
    URLBuilder("$MONZO_BASE_URL/transactions")
        .apply {
            parameters.append("account_id", accountId)
            parameters.append("limit", TRANSACTION_PAGE_LIMIT.toString())
            if (before != null) {
                parameters.append("before", before.toString())
            }
        }.buildString()
