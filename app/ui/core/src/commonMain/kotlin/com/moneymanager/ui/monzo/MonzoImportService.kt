@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.database.ApiImportSourceRecorder
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiHttpResponse
import io.ktor.http.URLBuilder
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Instant

private const val MONZO_BASE_URL = "https://api.monzo.com"
private const val TRANSACTION_PAGE_LIMIT = 100
private const val MONZO_ACCOUNT_PREFIX = "Monzo: "
private const val MONZO_COUNTERPARTY_PREFIX = "Monzo Counterparty: "

data class MonzoImportResult(
    val accountCount: Int,
    val transactionCount: Int,
    val duplicateCount: Int = 0,
    val errorCount: Int = 0,
)

data class MonzoDownloadResult(
    val accountCount: Int,
    val transactionResponseCount: Int,
)

data class MonzoImportProgress(
    val accountIndex: Int,
    val accountCount: Int,
    val page: Int,
    val importedTransactionCount: Int,
)

suspend fun downloadMonzoTransactions(
    token: String,
    apiClient: ApiClient,
    onProgress: (MonzoImportProgress) -> Unit = {},
): MonzoDownloadResult {
    val accountsResponse =
        fetchResponse(
            url = "$MONZO_BASE_URL/accounts",
            token = token,
            apiClient = apiClient,
        )
    val monzoAccounts = parseAccounts(accountsResponse.body)

    var transactionResponseCount = 0

    for ((index, monzoAccount) in monzoAccounts.withIndex()) {
        var before: Instant? = null
        var page = 1
        var hasTransactions: Boolean
        do {
            onProgress(
                MonzoImportProgress(
                    accountIndex = index + 1,
                    accountCount = monzoAccounts.size,
                    page = page,
                    importedTransactionCount = transactionResponseCount,
                ),
            )
            val url = buildTransactionUrl(monzoAccount.id, before)
            val response =
                fetchResponse(
                    url = url,
                    token = token,
                    apiClient = apiClient,
                )
            val transactions = parseTransactionsWithPath(response.body)
            transactionResponseCount += 1

            before = transactions.map { it.created }.minOrNull()
            hasTransactions = transactions.isNotEmpty()
            onProgress(
                MonzoImportProgress(
                    accountIndex = index + 1,
                    accountCount = monzoAccounts.size,
                    page = page,
                    importedTransactionCount = transactionResponseCount,
                ),
            )
            page += 1
        } while (hasTransactions)
    }

    return MonzoDownloadResult(
        accountCount = monzoAccounts.size,
        transactionResponseCount = transactionResponseCount,
    )
}

suspend fun importMonzoSessionTransactions(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
    deviceId: DeviceId,
    sessionId: ApiSessionId,
    onProgress: (String) -> Unit = {},
): MonzoImportResult {
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val responses = apiSessionRepository.getResponsesBySession(sessionId)
    val monzoAccounts = responses.flatMap { response -> parseAccounts(response.json) }
    val monzoAccountsById = monzoAccounts.associateBy { it.id }

    var transactionCount = 0
    var duplicateCount = 0
    var errorCount = 0
    val accountCache = AccountCache(accountRepository)
    val currencyCache = CurrencyCache(currencyRepository)

    responses.forEachIndexed { index, response ->
        onProgress(
            "Importing response ${index + 1}/${responses.size}: $transactionCount imported, $duplicateCount duplicate(s), $errorCount error(s).",
        )
        val request = requestsById[response.requestId] ?: return@forEachIndexed
        val monzoAccount = monzoAccountsById[request.accountIdParameter()] ?: return@forEachIndexed
        val monzoAccountId = accountCache.getOrCreateAccountId(monzoAccount.localAccountName())
        val pageResult =
            importTransactionPage(
                response = response.toApiHttpResponse(),
                monzoAccountId = monzoAccountId,
                sessionId = sessionId,
                deviceId = deviceId,
                accountCache = accountCache,
                currencyCache = currencyCache,
                apiSessionRepository = apiSessionRepository,
                transactionRepository = transactionRepository,
                transferSourceQueries = transferSourceQueries,
            )
        transactionCount += pageResult.importedCount
        duplicateCount += pageResult.duplicateCount
        errorCount += pageResult.errorCount
        onProgress(
            "Imported response ${index + 1}/${responses.size}: $transactionCount imported, $duplicateCount duplicate(s), $errorCount error(s).",
        )
    }

    return MonzoImportResult(
        accountCount = monzoAccountsById.size,
        transactionCount = transactionCount,
        duplicateCount = duplicateCount,
        errorCount = errorCount,
    )
}

private suspend fun fetchResponse(
    url: String,
    token: String,
    apiClient: ApiClient,
): ApiHttpResponse {
    val response = apiClient.get(url = url, bearerToken = token)
    if (response.statusCode != 200) {
        throw MonzoApiException("HTTP ${response.statusCode}: ${response.body}")
    }
    return response
}

class MonzoApiException(
    message: String,
) : Exception(message)

private data class MonzoAccount(
    val id: String,
    val description: String,
)

private fun parseAccounts(json: String): List<MonzoAccount> =
    try {
        Json
            .parseToJsonElement(json)
            .jsonObject["accounts"]
            ?.jsonArray
            ?.mapNotNull { element ->
                val account = element.jsonObject
                val id = account["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                MonzoAccount(
                    id = id,
                    description = account["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

private data class MonzoTransactionPageItem(
    val amountMinorUnits: Long,
    val created: Instant,
    val currencyCode: String,
    val description: String,
    val jsonPath: String,
    val merchantName: String?,
    val counterpartyName: String?,
)

private data class MonzoImportPageResult(
    val importedCount: Int,
    val duplicateCount: Int,
    val errorCount: Int,
    val before: Instant?,
    val hasTransactions: Boolean,
)

private suspend fun importTransactionPage(
    response: ApiHttpResponse,
    monzoAccountId: AccountId,
    sessionId: ApiSessionId,
    deviceId: DeviceId,
    accountCache: AccountCache,
    currencyCache: CurrencyCache,
    apiSessionRepository: ApiSessionRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
): MonzoImportPageResult {
    val transactions = parseTransactionsWithPath(response.body)
    val responseId = response.responseId?.let(::ApiResponseId)
    val requestId = response.requestId?.let(::ApiRequestId)

    if (responseId == null || requestId == null) {
        return MonzoImportPageResult(
            importedCount = 0,
            duplicateCount = 0,
            errorCount = transactions.size,
            before = transactions.map { it.created }.minOrNull(),
            hasTransactions = transactions.isNotEmpty(),
        )
    }

    val states =
        transactions.map { item ->
            importTransactionItem(
                item = item,
                monzoAccountId = monzoAccountId,
                responseId = responseId,
                requestId = requestId,
                sessionId = sessionId,
                deviceId = deviceId,
                accountCache = accountCache,
                currencyCache = currencyCache,
                apiSessionRepository = apiSessionRepository,
                transactionRepository = transactionRepository,
                transferSourceQueries = transferSourceQueries,
            )
        }

    return MonzoImportPageResult(
        importedCount = states.count { it == ApiResponseTransactionState.IMPORTED },
        duplicateCount = states.count { it == ApiResponseTransactionState.DUPLICATE },
        errorCount = states.count { it == ApiResponseTransactionState.ERROR },
        before = transactions.map { it.created }.minOrNull(),
        hasTransactions = transactions.isNotEmpty(),
    )
}

private suspend fun importTransactionItem(
    item: MonzoTransactionPageItem,
    monzoAccountId: AccountId,
    responseId: ApiResponseId,
    requestId: ApiRequestId,
    sessionId: ApiSessionId,
    deviceId: DeviceId,
    accountCache: AccountCache,
    currencyCache: CurrencyCache,
    apiSessionRepository: ApiSessionRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
): ApiResponseTransactionState {
    val currency = currencyCache.getCurrency(item.currencyCode)
    if (currency == null) {
        apiSessionRepository.recordTransactionError(
            responseId = responseId,
            jsonPath = item.jsonPath,
            message = "Currency not found: ${item.currencyCode}",
        )
        return ApiResponseTransactionState.ERROR
    }

    return try {
        importValidTransactionItem(
            item = item,
            monzoAccountId = monzoAccountId,
            currency = currency,
            responseId = responseId,
            requestId = requestId,
            sessionId = sessionId,
            deviceId = deviceId,
            accountCache = accountCache,
            apiSessionRepository = apiSessionRepository,
            transactionRepository = transactionRepository,
            transferSourceQueries = transferSourceQueries,
        )
    } catch (expected: Exception) {
        apiSessionRepository.recordTransactionError(
            responseId = responseId,
            jsonPath = item.jsonPath,
            message = expected.message ?: "Unknown import error",
        )
        ApiResponseTransactionState.ERROR
    }
}

private suspend fun importValidTransactionItem(
    item: MonzoTransactionPageItem,
    monzoAccountId: AccountId,
    currency: Currency,
    responseId: ApiResponseId,
    requestId: ApiRequestId,
    sessionId: ApiSessionId,
    deviceId: DeviceId,
    accountCache: AccountCache,
    apiSessionRepository: ApiSessionRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
): ApiResponseTransactionState {
    val counterpartyAccountId =
        accountCache.getOrCreateAccountId(
            MONZO_COUNTERPARTY_PREFIX + item.counterpartyName(),
        )
    val transfer = item.toTransfer(monzoAccountId, counterpartyAccountId, currency)
    val duplicateTransferId =
        transactionRepository
            .getTransactionsByAccount(monzoAccountId)
            .first()
            .firstOrNull { it.matches(transfer) }
            ?.id

    if (duplicateTransferId != null) {
        apiSessionRepository.insertResponseTransaction(
            responseId = responseId,
            jsonPath = item.jsonPath,
            state = ApiResponseTransactionState.DUPLICATE,
            referencedTransactionId = duplicateTransferId.id,
            errorMessage = null,
        )
        return ApiResponseTransactionState.DUPLICATE
    }

    var createdTransferId: TransferId? = null
    transactionRepository.createTransfers(
        transfers = listOf(transfer),
        sourceRecorder =
            ApiImportSourceRecorder(
                queries = transferSourceQueries,
                deviceId = deviceId,
                sessionId = sessionId,
                requestId = requestId,
                jsonPathForTransfer = { generatedTransferId ->
                    createdTransferId = generatedTransferId
                    item.jsonPath
                },
            ),
    )
    val importedTransferId = createdTransferId ?: transfer.id
    apiSessionRepository.insertResponseTransaction(
        responseId = responseId,
        jsonPath = item.jsonPath,
        state = ApiResponseTransactionState.IMPORTED,
        referencedTransactionId = importedTransferId.id,
        errorMessage = null,
    )
    return ApiResponseTransactionState.IMPORTED
}

private fun parseTransactionsWithPath(json: String): List<MonzoTransactionPageItem> =
    try {
        Json
            .parseToJsonElement(json)
            .jsonObject["transactions"]
            ?.jsonArray
            ?.mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val created = obj["created"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it) }
                val amount = obj["amount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val currency = obj["currency"]?.jsonPrimitive?.contentOrNull
                if (created != null && amount != null && currency != null) {
                    MonzoTransactionPageItem(
                        amountMinorUnits = amount,
                        created = created,
                        currencyCode = currency.uppercase(),
                        description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        jsonPath = "$.transactions[$index]",
                        merchantName = obj.jsonObjectOrNull("merchant")?.stringOrNull("name"),
                        counterpartyName = obj.jsonObjectOrNull("counterparty")?.stringOrNull("name"),
                    )
                } else {
                    null
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

private fun ApiResponse.toApiHttpResponse(): ApiHttpResponse =
    ApiHttpResponse(
        statusCode = 200,
        body = json,
        responseId = id.id,
        requestId = requestId.id,
    )

private fun ApiRequest.accountIdParameter(): String? = runCatching { URLBuilder(url).parameters["account_id"] }.getOrNull()

private class AccountCache(
    private val accountRepository: AccountRepository,
) {
    private var accountsByName: Map<String, Account>? = null

    suspend fun getOrCreateAccountId(name: String): AccountId {
        val normalizedName = name.ifBlank { "Unknown" }
        val existing = loadAccounts()[normalizedName]
        if (existing != null) return existing.id

        val now = Clock.System.now()
        val accountId =
            accountRepository.createAccount(
                Account(
                    id = AccountId(0L),
                    name = normalizedName,
                    openingDate = now,
                    categoryId = Category.UNCATEGORIZED_ID,
                ),
            )
        accountsByName =
            loadAccounts() +
            (
                normalizedName to
                    Account(
                        id = accountId,
                        name = normalizedName,
                        openingDate = now,
                    )
            )
        return accountId
    }

    private suspend fun loadAccounts(): Map<String, Account> {
        val accounts = accountsByName
        if (accounts != null) return accounts

        return accountRepository.getAllAccounts().first().associateBy { it.name }.also {
            accountsByName = it
        }
    }
}

private class CurrencyCache(
    private val currencyRepository: CurrencyRepository,
) {
    private var currenciesByCode: Map<String, Currency>? = null

    suspend fun getCurrency(code: String): Currency? = loadCurrencies()[code.uppercase()]

    private suspend fun loadCurrencies(): Map<String, Currency> {
        val currencies = currenciesByCode
        if (currencies != null) return currencies

        return currencyRepository.getAllCurrencies().first().associateBy { it.code.uppercase() }.also {
            currenciesByCode = it
        }
    }
}

private fun MonzoAccount.localAccountName(): String = MONZO_ACCOUNT_PREFIX + description.ifBlank { id }

private fun MonzoTransactionPageItem.counterpartyName(): String =
    merchantName?.takeIf { it.isNotBlank() }
        ?: counterpartyName?.takeIf { it.isNotBlank() }
        ?: description.ifBlank { "Unknown" }

private fun MonzoTransactionPageItem.toTransfer(
    monzoAccountId: AccountId,
    counterpartyAccountId: AccountId,
    currency: Currency,
): Transfer {
    require(amountMinorUnits != 0L) { "Monzo transaction amount is zero" }
    val money = Money(amountMinorUnits.absoluteValue, currency)
    val isIncoming = amountMinorUnits > 0
    return Transfer(
        id = TransferId(0L),
        timestamp = created,
        description = description.ifBlank { counterpartyName() },
        sourceAccountId = if (isIncoming) counterpartyAccountId else monzoAccountId,
        targetAccountId = if (isIncoming) monzoAccountId else counterpartyAccountId,
        amount = money,
    )
}

private fun Transfer.matches(other: Transfer): Boolean =
    timestamp == other.timestamp &&
        description == other.description &&
        sourceAccountId == other.sourceAccountId &&
        targetAccountId == other.targetAccountId &&
        amount == other.amount

private suspend fun ApiSessionRepository.recordTransactionError(
    responseId: ApiResponseId,
    jsonPath: String,
    message: String,
) {
    insertResponseTransaction(
        responseId = responseId,
        jsonPath = jsonPath,
        state = ApiResponseTransactionState.ERROR,
        referencedTransactionId = null,
        errorMessage = message,
    )
}

private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? = this[key]?.let { element -> element as? JsonObject }

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
