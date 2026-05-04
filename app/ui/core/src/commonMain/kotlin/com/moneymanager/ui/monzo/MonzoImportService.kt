@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.monzo

import com.moneymanager.database.ApiEntitySourceRecorder
import com.moneymanager.database.ApiImportSourceRecorder
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.*
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiHttpResponse
import com.moneymanager.ui.screens.transactions.logger
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Instant

private const val MONZO_BASE_URL = "https://api.monzo.com"
private const val TRANSACTION_PAGE_LIMIT = 100
private const val MONZO_ACCOUNT_PREFIX = "Monzo: "
private const val MONZO_COUNTERPARTY_PREFIX = "Monzo Counterparty: "
private const val MONZO_VOID_COUNTERPARTY = "Monzo Counterparty: Void"

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

data class MonzoDownloadProgress(
    val accountIndex: Int,
    val accountCount: Int,
    val page: Int,
    val downloadedResponsePageCount: Int,
)

suspend fun downloadMonzoTransactions(
    token: String,
    apiClient: ApiClient,
    onProgress: (MonzoDownloadProgress) -> Unit = {},
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
                MonzoDownloadProgress(
                    accountIndex = index + 1,
                    accountCount = monzoAccounts.size,
                    page = page,
                    downloadedResponsePageCount = transactionResponseCount,
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
                MonzoDownloadProgress(
                    accountIndex = index + 1,
                    accountCount = monzoAccounts.size,
                    page = page,
                    downloadedResponsePageCount = transactionResponseCount,
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
    entitySourceQueries: EntitySourceQueries,
    deviceId: DeviceId,
    sessionId: ApiSessionId,
    onProgress: (String) -> Unit = {},
): MonzoImportResult {
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val responses = apiSessionRepository.getResponsesBySession(sessionId)

    // Build a map from Monzo account ID to the accounts-list source (request + jsonPath)
    // so newly created accounts can be linked back to their API origin.
    val accountApiSourceByMonzoId =
        responses
            .flatMap { response ->
                val requestId = requestsById[response.requestId]?.id ?: return@flatMap emptyList()
                parseAccountsWithPaths(response.json).map { (account, jsonPath) ->
                    account.id to AccountApiSource(sessionId, requestId, jsonPath)
                }
            }.toMap()

    val monzoAccounts = responses.flatMap { response -> parseAccounts(response.json) }
    val monzoAccountsById = monzoAccounts.associateBy { it.id }

    val accountCache =
        AccountCache(
            accountRepository = accountRepository,
            entitySourceQueries = entitySourceQueries,
            deviceId = deviceId,
            accountApiSourceByMonzoId = accountApiSourceByMonzoId,
        )
    val currencyCache = CurrencyCache(currencyRepository)

    val pageResults =
        coroutineScope {
            responses.mapIndexed { index, response ->
                async {
                    onProgress("Importing response ${index + 1}/${responses.size}.")
                    val request = requestsById[response.requestId] ?: return@async null
                    val monzoAccount = monzoAccountsById[request.accountIdParameter()] ?: return@async null
                    val monzoAccountId = accountCache.getOrCreateAccountId(monzoAccount.id, monzoAccount.localAccountName())
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
                    onProgress(
                        "Imported response ${index + 1}/${responses.size}: ${pageResult.importedCount} imported, ${pageResult.duplicateCount} duplicate(s), ${pageResult.errorCount} error(s).",
                    )
                    pageResult
                }
            }.awaitAll()
        }

    val successfulPageResults = pageResults.filterNotNull()
    return MonzoImportResult(
        accountCount = monzoAccountsById.size,
        transactionCount = successfulPageResults.sumOf { it.importedCount },
        duplicateCount = successfulPageResults.sumOf { it.duplicateCount },
        errorCount = successfulPageResults.sumOf { it.errorCount },
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

private fun parseAccountsWithPaths(json: String): List<Pair<MonzoAccount, JsonPath>> =
    Json
        .parseToJsonElement(json)
        .jsonObject["accounts"]
        ?.jsonArray
        ?.mapIndexedNotNull { index, element ->
            val account = element.jsonObject
            val id = account["id"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            MonzoAccount(
                id = id,
                description = account["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ) to JsonPath("$.accounts[$index]")
        }
        ?: emptyList()

private data class MonzoTransactionPageItem(
    val amountMinorUnits: Long,
    val created: Instant,
    val currencyCode: String,
    val description: String,
    val jsonPath: JsonPath,
    val merchantName: String?,
    val counterpartyName: String?,
    val declineReason: String?,
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
    val responseId = ApiResponseId(response.responseId)
    val requestId = ApiRequestId(response.requestId)
    val existingTransfers = transactionRepository.getTransactionsByAccount(monzoAccountId).first().toMutableList()

    val states =
        transactions.filter { it.declineReason.isNullOrBlank() }.map { item ->
            importTransactionItem(
                item = item,
                monzoAccountId = monzoAccountId,
                responseId = responseId,
                requestId = requestId,
                sessionId = sessionId,
                deviceId = deviceId,
                accountCache = accountCache,
                currencyCache = currencyCache,
                existingTransfers = existingTransfers,
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
    existingTransfers: MutableList<Transfer>,
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
            existingTransfers = existingTransfers,
            apiSessionRepository = apiSessionRepository,
            transactionRepository = transactionRepository,
            transferSourceQueries = transferSourceQueries,
        )
    } catch (expected: Exception) {
        logger.error(expected) { "Error importing Monzo transaction: ${expected.message}" }
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
    existingTransfers: MutableList<Transfer>,
    apiSessionRepository: ApiSessionRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
): ApiResponseTransactionState {
    val counterpartyAccountId =
        accountCache.getOrCreateAccountId(
            name = if (item.amountMinorUnits == 0L) MONZO_VOID_COUNTERPARTY else MONZO_COUNTERPARTY_PREFIX + item.counterpartyName(),
            transactionApiSource = AccountApiSource(sessionId, requestId, JsonPath("${item.jsonPath.value}.counterparty")),
        )
    val transfer = item.toTransfer(monzoAccountId, counterpartyAccountId, currency)
    val duplicateTransferId =
        existingTransfers
            .firstOrNull { it.matches(transfer) }
            ?.id

    if (duplicateTransferId != null) {
        apiSessionRepository.insertResponseTransaction(
            responseId = responseId,
            jsonPath = item.jsonPath,
            state = ApiResponseTransactionState.DUPLICATE,
            transactionId = duplicateTransferId,
            errorMessage = null,
        )
        return ApiResponseTransactionState.DUPLICATE
    }

    val sourceRecorder =
        ApiImportSourceRecorder(
            queries = transferSourceQueries,
            deviceId = deviceId,
            sessionId = sessionId,
            requestId = requestId,
            jsonPath = item.jsonPath,
        )
    transactionRepository.createTransfers(
        transfers = listOf(transfer),
        sourceRecorder = sourceRecorder,
    )
    val importedTransferId = sourceRecorder.insertedTransferId ?: transfer.id
    existingTransfers.add(transfer.copy(id = importedTransferId))
    apiSessionRepository.insertResponseTransaction(
        responseId = responseId,
        jsonPath = item.jsonPath,
        state = ApiResponseTransactionState.IMPORTED,
        transactionId = importedTransferId,
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
                val declineReason = obj["decline_reason"]?.jsonPrimitive?.contentOrNull
                if (created != null && amount != null && currency != null) {
                    MonzoTransactionPageItem(
                        amountMinorUnits = amount,
                        created = created,
                        currencyCode = currency.uppercase(),
                        description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        jsonPath = JsonPath("$.transactions[$index]"),
                        merchantName = obj.jsonObjectOrNull("merchant")?.stringOrNull("name"),
                        counterpartyName = obj.jsonObjectOrNull("counterparty")?.stringOrNull("name"),
                        declineReason = declineReason,
                    )
                } else {
                    logger.error {
                        "Skipping Monzo transaction at index $index: missing required fields (created=$created, amount=$amount, currency=$currency)"
                    }
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

private data class AccountApiSource(
    val sessionId: ApiSessionId,
    val requestId: ApiRequestId,
    val jsonPath: JsonPath,
)

private class AccountCache(
    private val accountRepository: AccountRepository,
    private val entitySourceQueries: EntitySourceQueries,
    private val deviceId: DeviceId,
    private val accountApiSourceByMonzoId: Map<String, AccountApiSource>,
) {
    private val mutex = Mutex()
    private var accountsByName: Map<String, Account>? = null

    suspend fun getOrCreateAccountId(
        monzoAccountId: String?,
        name: String,
        explicitApiSource: AccountApiSource? = null,
    ): AccountId =
        mutex.withLock {
            val normalizedName = name.ifBlank { "Unknown" }
            val existing = loadAccounts()[normalizedName]
            if (existing != null) {
                return@withLock existing.id
            }

            val now = Clock.System.now()
            val newId =
                accountRepository.createAccount(
                    Account(
                        id = AccountId(0L),
                        name = normalizedName,
                        openingDate = now,
                        categoryId = Category.UNCATEGORIZED_ID,
                    ),
                )
            accountsByName =
                (accountsByName ?: emptyMap()) +
                (
                    normalizedName to
                        Account(
                            id = newId,
                            name = normalizedName,
                            openingDate = now,
                        )
                )

            val apiSource = monzoAccountId?.let { accountApiSourceByMonzoId[it] } ?: explicitApiSource
            if (apiSource != null) {
                ApiEntitySourceRecorder(
                    queries = entitySourceQueries,
                    deviceId = deviceId,
                    sessionId = apiSource.sessionId,
                    requestId = apiSource.requestId,
                    jsonPath = apiSource.jsonPath,
                ).insert(EntityType.ACCOUNT, newId.id, 1L)
            }
            newId
        }

    // Used when creating counterparty accounts — pass the transaction's API source so the
    // counterparty account's audit history records where it was first discovered.
    suspend fun getOrCreateAccountId(
        name: String,
        transactionApiSource: AccountApiSource,
    ): AccountId = getOrCreateAccountId(null, name, explicitApiSource = transactionApiSource)

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
    private val mutex = Mutex()
    private var currenciesByCode: Map<String, Currency>? = null

    suspend fun getCurrency(code: String): Currency? = loadCurrencies()[code.uppercase()]

    private suspend fun loadCurrencies(): Map<String, Currency> =
        mutex.withLock {
            val currencies = currenciesByCode
            if (currencies != null) return@withLock currencies

            currencyRepository.getAllCurrencies().first().associateBy { it.code.uppercase() }.also {
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
    jsonPath: JsonPath,
    message: String,
) {
    insertResponseTransaction(
        responseId = responseId,
        jsonPath = jsonPath,
        state = ApiResponseTransactionState.ERROR,
        transactionId = null,
        errorMessage = message,
    )
}

private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? = this[key]?.let { element -> element as? JsonObject }

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
