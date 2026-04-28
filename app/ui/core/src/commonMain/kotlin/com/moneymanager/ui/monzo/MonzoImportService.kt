@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.repository.ApiSessionRepository
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

private const val MONZO_BASE_URL = "https://api.monzo.com"
private const val TRANSACTION_PAGE_LIMIT = 100

data class MonzoImportResult(
    val accountCount: Int,
    val transactionCount: Int,
    val duplicateCount: Int = 0,
    val errorCount: Int = 0,
)

data class MonzoImportProgress(
    val accountIndex: Int,
    val accountCount: Int,
    val page: Int,
    val importedTransactionCount: Int,
)

suspend fun importMonzoData(
    token: String,
    apiClient: MonzoApiClient,
    apiSessionRepository: ApiSessionRepository,
    onProgress: (MonzoImportProgress) -> Unit = {},
): MonzoImportResult {
    val accountsResponse =
        fetchResponse(
            url = "$MONZO_BASE_URL/accounts",
            token = token,
            apiClient = apiClient,
        )
    val accountIds = parseIds(accountsResponse.body, "accounts")

    var transactionCount = 0

    for ((index, accountId) in accountIds.withIndex()) {
        var before: Instant? = null
        var page = 1
        do {
            onProgress(
                MonzoImportProgress(
                    accountIndex = index + 1,
                    accountCount = accountIds.size,
                    page = page,
                    importedTransactionCount = transactionCount,
                ),
            )
            val url = buildTransactionUrl(accountId, before)
            val response =
                fetchResponse(
                    url = url,
                    token = token,
                    apiClient = apiClient,
                )
            val transactions = parseTransactionsWithPath(response.body)
            transactionCount += transactions.size

            // Record each transaction's state in api_response_transaction when we have a response ID
            val responseId = response.responseId
            if (responseId != null) {
                transactions.forEach { item ->
                    apiSessionRepository.insertResponseTransaction(
                        responseId = responseId,
                        jsonPath = item.jsonPath,
                        state = ApiResponseTransactionState.IMPORTED,
                        transactionId = null,
                        duplicateOfTransactionId = null,
                        errorMessage = null,
                    )
                }
            }

            before = transactions.map { it.created }.minOrNull()
            onProgress(
                MonzoImportProgress(
                    accountIndex = index + 1,
                    accountCount = accountIds.size,
                    page = page,
                    importedTransactionCount = transactionCount,
                ),
            )
            page += 1
        } while (transactions.isNotEmpty())
    }

    return MonzoImportResult(
        accountCount = accountIds.size,
        transactionCount = transactionCount,
    )
}

private suspend fun fetchResponse(
    url: String,
    token: String,
    apiClient: MonzoApiClient,
): MonzoHttpResponse {
    val response = apiClient.get(url = url, bearerToken = token)
    if (response.statusCode != 200) {
        throw MonzoApiException("HTTP ${response.statusCode}: ${response.body}")
    }
    return response
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
    val jsonPath: String,
)

private fun parseTransactionsWithPath(json: String): List<MonzoTransactionPageItem> =
    try {
        Json
            .parseToJsonElement(json)
            .jsonObject["transactions"]
            ?.jsonArray
            ?.mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val created = obj["created"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it) }
                created?.let {
                    MonzoTransactionPageItem(
                        created = it,
                        jsonPath = "$.transactions[$index]",
                    )
                }
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
