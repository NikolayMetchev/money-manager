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
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
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
    val personCount: Int = 0,
    val duplicateCount: Int = 0,
    val errorCount: Int = 0,
    val excludedCount: Int = 0,
)

data class MonzoAccountsDownloadResult(
    val accountCount: Int,
    val skipped: Boolean = false,
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

/**
 * Downloads accounts from the Monzo API and stores them in the session traffic.
 * Incremental: if accounts have already been downloaded for this session, returns the existing
 * count without making a new API call.
 */
suspend fun downloadMonzoAccounts(
    token: String,
    apiClient: ApiClient,
    apiSessionRepository: ApiSessionRepository,
    sessionId: ApiSessionId,
): MonzoAccountsDownloadResult {
    val accountsUrl = "$MONZO_BASE_URL/accounts"
    val existingRequests = apiSessionRepository.getRequestsBySession(sessionId)

    // Incremental: skip only when both the request and its response are already stored.
    // If the request exists but has no response (interrupted prior run), fall through and re-fetch.
    val existingAccountsRequest = existingRequests.firstOrNull { it.url == accountsUrl }
    if (existingAccountsRequest != null) {
        val existingResponse =
            apiSessionRepository
                .getResponsesBySession(sessionId)
                .firstOrNull { it.requestId == existingAccountsRequest.id }
        if (existingResponse != null) {
            return MonzoAccountsDownloadResult(accountCount = parseAccounts(existingResponse.json).size, skipped = true)
        }
    }

    val response = fetchResponse(url = accountsUrl, token = token, apiClient = apiClient)
    val accounts = parseAccounts(response.body)
    return MonzoAccountsDownloadResult(accountCount = accounts.size)
}

/**
 * Downloads transactions for all accounts stored in [accountsSessionId] (or [sessionId] if not
 * provided). Incremental: pages whose URL is already stored in [sessionId] are skipped.
 *
 * Call [downloadMonzoAccounts] first so that an accounts response exists.
 */
suspend fun downloadMonzoTransactions(
    token: String,
    apiClient: ApiClient,
    apiSessionRepository: ApiSessionRepository,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId? = null,
    onProgress: (MonzoDownloadProgress) -> Unit = {},
): MonzoDownloadResult {
    val existingRequests = apiSessionRepository.getRequestsBySession(sessionId)
    val existingResponses = apiSessionRepository.getResponsesBySession(sessionId)
    val existingResponsesByRequestId = existingResponses.associateBy { it.requestId }
    val existingRequestsByUrl = existingRequests.associateBy { it.url }

    // Read accounts from a separate accounts session if provided, otherwise from this session
    val accountsUrl = "$MONZO_BASE_URL/accounts"
    val resolvedAccountsSessionId = accountsSessionId ?: sessionId
    val accountsRequests =
        if (accountsSessionId != null && accountsSessionId != sessionId) {
            apiSessionRepository.getRequestsBySession(resolvedAccountsSessionId)
        } else {
            existingRequests
        }
    val accountsResponses =
        if (accountsSessionId != null && accountsSessionId != sessionId) {
            apiSessionRepository.getResponsesBySession(resolvedAccountsSessionId)
        } else {
            existingResponses
        }
    val accountsRequestIds = accountsRequests.filter { it.url == accountsUrl }.map { it.id }.toSet()
    val monzoAccounts =
        accountsResponses
            .filter { it.requestId in accountsRequestIds }
            .flatMap { response -> parseAccounts(response.json) }

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

            // Incremental: reuse a stored response only when both request and response are present.
            // An orphan request (response missing from an interrupted prior run) is treated as a
            // cache miss so pagination can make progress on retry.
            val existingResponse = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }
            val transactions: List<MonzoTransactionPageItem> =
                if (existingResponse != null) {
                    parseTransactionsWithPath(existingResponse.json)
                } else {
                    val response = fetchResponse(url = url, token = token, apiClient = apiClient)
                    transactionResponseCount += 1
                    parseTransactionsWithPath(response.body)
                }

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
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    deviceId: DeviceId,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId? = null,
    onProgress: (String) -> Unit = {},
): MonzoImportResult {
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val responses = apiSessionRepository.getResponsesBySession(sessionId)

    // When accounts were downloaded into a separate session, load them from there
    val resolvedAccountsSessionId = accountsSessionId ?: sessionId
    val accountsRequestsById =
        if (accountsSessionId != null && accountsSessionId != sessionId) {
            apiSessionRepository.getRequestsBySession(resolvedAccountsSessionId).associateBy { it.id }
        } else {
            requestsById
        }
    val accountsResponses =
        if (accountsSessionId != null && accountsSessionId != sessionId) {
            apiSessionRepository.getResponsesBySession(resolvedAccountsSessionId)
        } else {
            responses
        }

    // Build a map from Monzo account ID to the accounts-list source (request + jsonPath)
    // so newly created accounts can be linked back to their API origin.
    val accountApiSourceByMonzoId =
        accountsResponses
            .flatMap { response ->
                val requestId = accountsRequestsById[response.requestId]?.id ?: return@flatMap emptyList()
                parseAccountsWithPaths(response.json).map { (account, jsonPath) ->
                    account.id to AccountApiSource(resolvedAccountsSessionId, requestId, jsonPath)
                }
            }.toMap()

    val monzoAccounts = accountsResponses.flatMap { response -> parseAccounts(response.json) }
    val monzoAccountsById = monzoAccounts.associateBy { it.id }

    val transactionResponses = responses.filter { requestsById[it.requestId]?.accountIdParameter() != null }
    var completedCount = 0
    var totalImported = 0
    var totalDuplicates = 0
    var totalErrors = 0
    var totalExcluded = 0
    var sourceAccountsCreated = 0
    var counterpartyAccountsCreated = 0
    val progressMutex = Mutex()

    fun progressMessage() =
        buildString {
            append("Imported $completedCount/${transactionResponses.size} responses")
            if (totalImported > 0) append(". $totalImported imported transaction(s)")
            if (totalDuplicates > 0) append(". $totalDuplicates duplicate(s)")
            if (totalErrors > 0) append(". $totalErrors error(s)")
            if (sourceAccountsCreated > 0) append(". $sourceAccountsCreated source account(s) created")
            if (counterpartyAccountsCreated > 0) append(". $counterpartyAccountsCreated counterparty account(s) created")
            append(".")
        }

    onProgress(progressMessage())

    val accountCache =
        AccountCache(
            accountRepository = accountRepository,
            entitySourceQueries = entitySourceQueries,
            deviceId = deviceId,
            accountApiSourceByMonzoId = accountApiSourceByMonzoId,
            onAccountCreated = { isSourceAccount ->
                val message =
                    progressMutex.withLock {
                        if (isSourceAccount) ++sourceAccountsCreated else ++counterpartyAccountsCreated
                        progressMessage()
                    }
                onProgress(message)
            },
        )
    val currencyCache = CurrencyCache(currencyRepository)
    coroutineScope {
        transactionResponses
            .map { response ->
                async {
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
                    val progressMessage =
                        progressMutex.withLock {
                            totalImported += pageResult.importedCount
                            totalDuplicates += pageResult.duplicateCount
                            totalErrors += pageResult.errorCount
                            totalExcluded += pageResult.excludedCount
                            ++completedCount
                            progressMessage()
                        }
                    onProgress(progressMessage)
                    pageResult
                }
            }.awaitAll()
    }

    val totalPeople =
        importPeopleFromAccounts(
            monzoAccountsById = monzoAccountsById,
            accountCache = accountCache,
            personRepository = personRepository,
            personAccountOwnershipRepository = personAccountOwnershipRepository,
        )

    return MonzoImportResult(
        accountCount = monzoAccountsById.size,
        transactionCount = totalImported,
        personCount = totalPeople,
        duplicateCount = totalDuplicates,
        errorCount = totalErrors,
        excludedCount = totalExcluded,
    )
}

private suspend fun importPeopleFromAccounts(
    monzoAccountsById: Map<String, MonzoAccount>,
    accountCache: AccountCache,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
): Int {
    val peopleByFullName =
        personRepository
            .getAllPeople()
            .first()
            .associateBy { it.fullName }
            .toMutableMap()
    var newPeopleCount = 0

    for (monzoAccount in monzoAccountsById.values) {
        if (monzoAccount.owners.isEmpty()) continue

        val accountId = accountCache.getOrCreateAccountId(monzoAccount.id, monzoAccount.localAccountName())
        val existingOwnerPersonIds =
            personAccountOwnershipRepository
                .getOwnershipsByAccount(accountId)
                .first()
                .map { it.personId }
                .toSet()

        for (owner in monzoAccount.owners) {
            val name = owner.preferredName.trim()
            if (name.isBlank()) continue

            val personId =
                peopleByFullName[name]?.id
                    ?: run {
                        // Split on first space: "John Doe" → firstName="John", lastName="Doe".
                        // For single-word names the lastName is null.
                        // Note: this is a simplified Western-centric split; users can edit names after import.
                        val nameParts = name.split(" ", limit = 2)
                        val person =
                            Person(
                                id = PersonId(0L),
                                firstName = nameParts[0],
                                middleName = null,
                                lastName = nameParts.getOrNull(1)?.ifBlank { null },
                            )
                        val newId = personRepository.createPerson(person)
                        peopleByFullName[name] = person.copy(id = newId)
                        newPeopleCount++
                        newId
                    }

            if (personId !in existingOwnerPersonIds) {
                personAccountOwnershipRepository.createOwnership(personId, accountId)
            }
        }
    }

    return newPeopleCount
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
    val owners: List<MonzoAccountOwner> = emptyList(),
)

private data class MonzoAccountOwner(
    val userId: String,
    val preferredName: String,
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
                    owners = parseAccountOwners(account),
                )
            }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

private fun parseAccountOwners(account: JsonObject): List<MonzoAccountOwner> =
    account["owners"]
        ?.jsonArray
        ?.mapNotNull { element ->
            val owner = element as? JsonObject ?: return@mapNotNull null
            val userId = owner["user_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val preferredName = owner["preferred_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            MonzoAccountOwner(userId = userId, preferredName = preferredName)
        }
        ?: emptyList()

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
                owners = parseAccountOwners(account),
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
    val excludedCount: Int,
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
        excludedCount =
            transactions.zip(states).count { (item, state) ->
                state == ApiResponseTransactionState.IMPORTED && !item.declineReason.isNullOrBlank()
            },
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
            declineReason = item.declineReason,
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
    declineReason: String? = null,
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
    val excludedAttributes =
        if (!declineReason.isNullOrBlank()) {
            mapOf(transfer.id to listOf(NewAttribute(typeId = AttributeTypeId(-1), value = "declined: $declineReason")))
        } else {
            emptyMap()
        }
    transactionRepository.createTransfers(
        transfers = listOf(transfer),
        newAttributes = excludedAttributes,
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
    private val onAccountCreated: suspend (isSourceAccount: Boolean) -> Unit = {},
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
            onAccountCreated(monzoAccountId != null)

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
