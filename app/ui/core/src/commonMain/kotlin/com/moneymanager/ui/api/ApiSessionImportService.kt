@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.api

import com.moneymanager.database.ApiEntitySourceRecorder
import com.moneymanager.database.ApiImportSourceRecorder
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.model.*
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.AccountAttributeRepository
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
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

private const val COUNTERPARTY_ID_ATTR = "counterparty.id"

data class ApiSessionImportResult(
    val accountCount: Int,
    val transactionCount: Int,
    val personCount: Int = 0,
    val duplicateCount: Int = 0,
    val errorCount: Int = 0,
    val excludedCount: Int = 0,
)

data class ApiCounterpartySuggestion(
    val counterpartyId: String,
    val suggestedAccountName: String,
    val downloadedNames: List<String>,
)

data class ApiAccountsDownloadResult(
    val accountCount: Int,
    val skipped: Boolean = false,
)

data class ApiTransactionsDownloadResult(
    val accountCount: Int,
    val transactionResponseCount: Int,
)

data class ApiTransactionsDownloadProgress(
    val accountIndex: Int,
    val accountCount: Int,
    val page: Int,
    val downloadedResponsePageCount: Int,
)

/**
 * Downloads accounts from the configured API and stores them in the session traffic.
 * Incremental: if accounts have already been downloaded for this session, returns the existing
 * count without making a new API call.
 */
suspend fun downloadApiSessionAccounts(
    token: String,
    apiClient: ApiClient,
    apiSessionRepository: ApiSessionRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
): ApiAccountsDownloadResult {
    val accountsUrl = buildEndpointUrl(strategy.baseUrl, strategy.accountsEndpoint.path)
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
            return ApiAccountsDownloadResult(accountCount = parseAccounts(existingResponse.json, strategy).size, skipped = true)
        }
    }

    val response = fetchResponse(url = accountsUrl, token = token, apiClient = apiClient)
    val accounts = parseAccounts(response.body, strategy)
    return ApiAccountsDownloadResult(accountCount = accounts.size)
}

/**
 * Downloads transactions for all accounts stored in [accountsSessionId] (or [sessionId] if not
 * provided). Incremental: pages whose URL is already stored in [sessionId] are skipped.
 *
 * Call [downloadApiSessionAccounts] first so that an accounts response exists.
 */
suspend fun downloadApiSessionTransactions(
    token: String,
    apiClient: ApiClient,
    apiSessionRepository: ApiSessionRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    accountsSessionId: ApiSessionId? = null,
    onProgress: (ApiTransactionsDownloadProgress) -> Unit = {},
): ApiTransactionsDownloadResult {
    val existingRequests = apiSessionRepository.getRequestsBySession(sessionId)
    val existingResponses = apiSessionRepository.getResponsesBySession(sessionId)
    val existingResponsesByRequestId = existingResponses.associateBy { it.requestId }
    val existingRequestsByUrl = existingRequests.associateBy { it.url }

    // Read accounts from a separate accounts session if provided, otherwise from this session
    val accountsUrl = buildEndpointUrl(strategy.baseUrl, strategy.accountsEndpoint.path)
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
            .flatMap { response -> parseAccounts(response.json, strategy) }

    var transactionResponseCount = 0

    for ((index, monzoAccount) in monzoAccounts.withIndex()) {
        var before: Instant? = null
        var page = 1
        var hasTransactions: Boolean
        do {
            onProgress(
                ApiTransactionsDownloadProgress(
                    accountIndex = index + 1,
                    accountCount = monzoAccounts.size,
                    page = page,
                    downloadedResponsePageCount = transactionResponseCount,
                ),
            )
            val url = buildTransactionUrl(strategy, monzoAccount.id, before)

            // Incremental: reuse a stored response only when both request and response are present.
            // An orphan request (response missing from an interrupted prior run) is treated as a
            // cache miss so pagination can make progress on retry.
            val existingResponse = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }
            val transactions: List<ApiTransactionPageItem> =
                if (existingResponse != null) {
                    parseTransactionsWithPath(existingResponse.json, strategy)
                } else {
                    val response = fetchResponse(url = url, token = token, apiClient = apiClient)
                    transactionResponseCount += 1
                    parseTransactionsWithPath(response.body, strategy)
                }

            before = transactions.minOfOrNull { it.created }
            hasTransactions = transactions.isNotEmpty()
            onProgress(
                ApiTransactionsDownloadProgress(
                    accountIndex = index + 1,
                    accountCount = monzoAccounts.size,
                    page = page,
                    downloadedResponsePageCount = transactionResponseCount,
                ),
            )
            page += 1
        } while (hasTransactions)
    }

    return ApiTransactionsDownloadResult(
        accountCount = monzoAccounts.size,
        transactionResponseCount = transactionResponseCount,
    )
}

suspend fun importApiSessionTransactions(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
    entitySourceQueries: EntitySourceQueries,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    attributeTypeRepository: AttributeTypeRepository,
    accountAttributeRepository: AccountAttributeRepository,
    deviceId: DeviceId,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId? = null,
    strategy: ApiImportStrategy,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    onProgress: (String) -> Unit = {},
): ApiSessionImportResult {
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

    // Build a map from external account ID to the accounts-list source (request + jsonPath)
    // so newly created accounts can be linked back to their API origin.
    val accountApiSourceByMonzoId =
        accountsResponses
            .flatMap { response ->
                val requestId = accountsRequestsById[response.requestId]?.id ?: return@flatMap emptyList()
                parseAccountsWithPaths(response.json, strategy).map { (account, jsonPath) ->
                    account.id to AccountApiSource(resolvedAccountsSessionId, requestId, jsonPath)
                }
            }.toMap()

    val monzoAccounts = accountsResponses.flatMap { response -> parseAccounts(response.json, strategy) }
    val monzoAccountsById = monzoAccounts.associateBy { it.id }

    val transactionResponses = responses.filter { requestsById[it.requestId]?.accountIdParameter(strategy) != null }
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

    val currencyCache = CurrencyCache(currencyRepository)
    val attributeTypeCache = AttributeTypeCache(attributeTypeRepository)
    val customTxFields = strategy.transactionMappings.customFields
    val uniqueIdTxFields = strategy.transactionMappings.uniqueIdentifierFields
    val counterpartyIdField = strategy.transactionMappings.counterpartyIdField
    val nameMappings = CounterpartyNameMappings.from(strategy)

    // Pre-create all attribute types we'll need before the concurrent section so that
    // no two coroutines race to write the same type, which causes SQLITE_BUSY.
    val counterpartyIdAttrTypeId: AttributeTypeId? =
        if (counterpartyIdField != null) attributeTypeCache.getOrCreate(COUNTERPARTY_ID_ATTR) else null
    for (fieldName in customTxFields.keys) attributeTypeCache.getOrCreate(fieldName)

    // Build the counterparty ID index (externalId → AccountId) from existing account attributes
    // in the serial section so concurrent coroutines never need to query the DB for it.
    val counterpartyIdIndex: MutableMap<String, AccountId> =
        if (counterpartyIdField != null) {
            loadCounterpartyIdIndex(
                accountRepository = accountRepository,
                accountAttributeRepository = accountAttributeRepository,
            ).toMutableMap()
        } else {
            mutableMapOf()
        }

    val accountCache =
        AccountCache(
            accountRepository = accountRepository,
            entitySourceQueries = entitySourceQueries,
            deviceId = deviceId,
            accountApiSourceByMonzoId = accountApiSourceByMonzoId,
            counterpartyIdIndex = counterpartyIdIndex,
            onAccountCreated = { isSourceAccount ->
                val message =
                    progressMutex.withLock {
                        if (isSourceAccount) ++sourceAccountsCreated else ++counterpartyAccountsCreated
                        progressMessage()
                    }
                onProgress(message)
            },
        )

    precreateCounterparties(
        transactionResponses = transactionResponses,
        sessionId = sessionId,
        requestsById = requestsById,
        strategy = strategy,
        accountCache = accountCache,
        counterpartyIdField = counterpartyIdField,
        counterpartyAccountNames = counterpartyAccountNames,
        nameMappings = nameMappings,
    )
    if (counterpartyIdAttrTypeId != null) {
        flushPendingCounterpartyAttributes(
            accountCache = accountCache,
            accountAttributeRepository = accountAttributeRepository,
            counterpartyIdAttrTypeId = counterpartyIdAttrTypeId,
        )
    }

    coroutineScope {
        transactionResponses
            .map { response ->
                async {
                    val request = requestsById[response.requestId] ?: return@async null
                    val monzoAccount = monzoAccountsById[request.accountIdParameter(strategy)] ?: return@async null
                    val monzoAccountId = accountCache.getOrCreateAccountId(monzoAccount.id, monzoAccount.localAccountName(strategy))
                    val pageResult =
                        importTransactionPage(
                            response = response.toApiHttpResponse(),
                            strategy = strategy,
                            monzoAccountId = monzoAccountId,
                            sessionId = sessionId,
                            deviceId = deviceId,
                            accountCache = accountCache,
                            currencyCache = currencyCache,
                            attributeTypeCache = attributeTypeCache,
                            customTxFields = customTxFields,
                            uniqueIdTxFields = uniqueIdTxFields,
                            counterpartyIdField = counterpartyIdField,
                            nameMappings = nameMappings,
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
            strategy = strategy,
            personRepository = personRepository,
            personAccountOwnershipRepository = personAccountOwnershipRepository,
        )

    // Flush deferred counterparty ID attributes now that all concurrent writes are done.
    // This avoids SQLITE_BUSY that would occur if we wrote them inside the concurrent section.
    if (counterpartyIdAttrTypeId != null) {
        flushPendingCounterpartyAttributes(
            accountCache = accountCache,
            accountAttributeRepository = accountAttributeRepository,
            counterpartyIdAttrTypeId = counterpartyIdAttrTypeId,
        )
    }

    // Store custom account field values as attributes
    val customAccountFields = strategy.accountMappings.customFields
    if (customAccountFields.isNotEmpty()) {
        for (monzoAccount in monzoAccountsById.values) {
            val rawJson = monzoAccount.rawJson ?: continue
            val accountId = accountCache.getOrCreateAccountId(monzoAccount.id, monzoAccount.localAccountName(strategy))
            val existingAttrTypes =
                accountAttributeRepository
                    .getByAccount(accountId)
                    .first()
                    .map { it.attributeType.name }
                    .toSet()
            for ((fieldName, jsonPath) in customAccountFields) {
                val value = rawJson.resolveJsonPath(jsonPath) ?: continue
                if (fieldName in existingAttrTypes) continue
                val typeId = attributeTypeCache.getOrCreate(fieldName)
                accountAttributeRepository.insert(accountId, typeId, value)
            }
        }
    }

    return ApiSessionImportResult(
        accountCount = monzoAccountsById.size,
        transactionCount = totalImported,
        personCount = totalPeople,
        duplicateCount = totalDuplicates,
        errorCount = totalErrors,
        excludedCount = totalExcluded,
    )
}

suspend fun discoverApiCounterpartiesToCreate(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
): List<ApiCounterpartySuggestion> {
    val counterpartyIdField = strategy.transactionMappings.counterpartyIdField ?: return emptyList()
    val existingCounterpartyIds =
        loadCounterpartyIdIndex(
            accountRepository = accountRepository,
            accountAttributeRepository = accountAttributeRepository,
        ).keys
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val transactionResponses =
        apiSessionRepository
            .getResponsesBySession(sessionId)
            .filter { requestsById[it.requestId]?.accountIdParameter(strategy) != null }

    return collectCounterpartiesFromResponses(
        responses = transactionResponses,
        strategy = strategy,
        counterpartyIdField = counterpartyIdField,
        nameMappings = CounterpartyNameMappings.from(strategy),
    ).filterKeys { it !in existingCounterpartyIds }
        .map { (counterpartyId, names) ->
            ApiCounterpartySuggestion(
                counterpartyId = counterpartyId,
                suggestedAccountName = strategy.counterpartyPrefix + suggestCounterpartyName(names),
                downloadedNames = names.distinct().sorted(),
            )
        }.sortedBy { it.suggestedAccountName }
}

private suspend fun precreateCounterparties(
    transactionResponses: List<ApiResponse>,
    sessionId: ApiSessionId,
    requestsById: Map<ApiRequestId, ApiRequest>,
    strategy: ApiImportStrategy,
    accountCache: AccountCache,
    counterpartyIdField: String?,
    counterpartyAccountNames: Map<String, String>,
    nameMappings: CounterpartyNameMappings,
) {
    if (counterpartyIdField == null) return

    val counterparties =
        transactionResponses
            .flatMap { response ->
                val request = requestsById[response.requestId] ?: return@flatMap emptyList()
                parseTransactionsWithPath(response.json, strategy).mapNotNull { item ->
                    val counterpartyId =
                        item.rawJson?.resolveCounterpartyIdentity(counterpartyIdField)
                            ?: return@mapNotNull null
                    val downloadedName = item.counterpartyName(nameMappings)
                    CounterpartyImportCandidate(
                        counterpartyId = counterpartyId,
                        downloadedName = downloadedName,
                        apiSource = AccountApiSource(sessionId, request.id, JsonPath("${item.jsonPath.value}.counterparty")),
                    )
                }
            }.groupBy { it.counterpartyId }

    for ((counterpartyId, candidates) in counterparties) {
        val accountName =
            counterpartyAccountNames[counterpartyId]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: (strategy.counterpartyPrefix + suggestCounterpartyName(candidates.map { it.downloadedName }))
        accountCache.getOrCreateCounterpartyAccountId(
            counterpartyId = counterpartyId,
            name = accountName,
            apiSource = candidates.first().apiSource,
        )
    }
}

private fun collectCounterpartiesFromResponses(
    responses: List<ApiResponse>,
    strategy: ApiImportStrategy,
    counterpartyIdField: String,
    nameMappings: CounterpartyNameMappings,
): Map<String, List<String>> =
    responses
        .flatMap { response ->
            parseTransactionsWithPath(response.json, strategy).mapNotNull { item ->
                val counterpartyId =
                    item.rawJson?.resolveCounterpartyIdentity(counterpartyIdField)
                        ?: return@mapNotNull null
                counterpartyId to item.counterpartyName(nameMappings)
            }
        }.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second },
        )

private suspend fun loadCounterpartyIdIndex(
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
): Map<String, AccountId> {
    val index = mutableMapOf<String, AccountId>()
    for (account in accountRepository.getAllAccounts().first()) {
        accountAttributeRepository
            .getByAccount(account.id)
            .first()
            .firstOrNull { it.attributeType.name == COUNTERPARTY_ID_ATTR }
            ?.let { index[it.value] = account.id }
    }
    return index
}

private suspend fun flushPendingCounterpartyAttributes(
    accountCache: AccountCache,
    accountAttributeRepository: AccountAttributeRepository,
    counterpartyIdAttrTypeId: AttributeTypeId,
) {
    val pending = accountCache.drainPendingCounterpartyAttributes()
    val seen = mutableSetOf<String>()
    for ((accountId, counterpartyId) in pending) {
        if (seen.add(counterpartyId)) {
            // runCatching handles the UNIQUE constraint for counterparties already
            // having this attribute from a previous import where index was stale.
            runCatching { accountAttributeRepository.insert(accountId, counterpartyIdAttrTypeId, counterpartyId) }
        }
    }
}

private fun suggestCounterpartyName(names: List<String>): String =
    names
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        ?.key
        ?: "Unknown"

private data class CounterpartyImportCandidate(
    val counterpartyId: String,
    val downloadedName: String,
    val apiSource: AccountApiSource,
)

private suspend fun importPeopleFromAccounts(
    monzoAccountsById: Map<String, ApiImportAccount>,
    accountCache: AccountCache,
    strategy: ApiImportStrategy,
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

        val accountId = accountCache.getOrCreateAccountId(monzoAccount.id, monzoAccount.localAccountName(strategy))
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
        throw ApiSessionImportException("HTTP ${response.statusCode}: ${response.body}")
    }
    return response
}

class ApiSessionImportException(
    message: String,
) : Exception(message)

private data class ApiImportAccount(
    val id: String,
    val description: String,
    val owners: List<ApiImportAccountOwner> = emptyList(),
    val rawJson: JsonObject? = null,
)

private data class ApiImportAccountOwner(
    val userId: String,
    val preferredName: String,
)

private fun parseAccounts(
    json: String,
    strategy: ApiImportStrategy,
): List<ApiImportAccount> =
    try {
        Json
            .parseToJsonElement(json)
            .jsonObject[strategy.accountsEndpoint.responseArrayKey]
            ?.jsonArray
            ?.mapNotNull { element ->
                val account = element.jsonObject
                val id = account.resolveJsonPath(strategy.accountMappings.idField) ?: return@mapNotNull null
                ApiImportAccount(
                    id = id,
                    description = account.resolveJsonPath(strategy.accountMappings.descriptionField).orEmpty(),
                    owners = parseAccountOwners(account, strategy),
                    rawJson = account,
                )
            }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

private fun parseAccountOwners(
    account: JsonObject,
    strategy: ApiImportStrategy,
): List<ApiImportAccountOwner> =
    account["owners"]
        ?.jsonArray
        ?.mapNotNull { element ->
            val owner = element as? JsonObject ?: return@mapNotNull null
            val userId = owner["user_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val preferredName =
                strategy.accountMappings.ownerNameField
                    ?.let { owner.resolveJsonPath(it) }
                    .orEmpty()
            ApiImportAccountOwner(userId = userId, preferredName = preferredName)
        }
        ?: emptyList()

private fun parseAccountsWithPaths(
    json: String,
    strategy: ApiImportStrategy,
): List<Pair<ApiImportAccount, JsonPath>> =
    Json
        .parseToJsonElement(json)
        .jsonObject[strategy.accountsEndpoint.responseArrayKey]
        ?.jsonArray
        ?.mapIndexedNotNull { index, element ->
            val account = element.jsonObject
            val id = account.resolveJsonPath(strategy.accountMappings.idField) ?: return@mapIndexedNotNull null
            ApiImportAccount(
                id = id,
                description = account.resolveJsonPath(strategy.accountMappings.descriptionField).orEmpty(),
                owners = parseAccountOwners(account, strategy),
            ).copy(rawJson = account) to JsonPath("$.${strategy.accountsEndpoint.responseArrayKey}[$index]")
        }
        ?: emptyList()

private data class ApiTransactionPageItem(
    val amountMinorUnits: Long,
    val created: Instant,
    val currencyCode: String,
    val description: String,
    val jsonPath: JsonPath,
    val merchantName: String?,
    val counterpartyName: String?,
    val declineReason: String?,
    val rawJson: JsonObject? = null,
)

private data class ApiImportPageResult(
    val importedCount: Int,
    val duplicateCount: Int,
    val errorCount: Int,
    val excludedCount: Int,
    val before: Instant?,
    val hasTransactions: Boolean,
)

private data class CounterpartyNameMappings(
    val merchantNameField: String?,
    val counterpartyNameField: String?,
    val counterpartyPrefix: String,
) {
    companion object {
        fun from(strategy: ApiImportStrategy): CounterpartyNameMappings =
            CounterpartyNameMappings(
                merchantNameField = strategy.transactionMappings.merchantNameField,
                counterpartyNameField = strategy.transactionMappings.counterpartyNameField,
                counterpartyPrefix = strategy.counterpartyPrefix,
            )
    }
}

private suspend fun importTransactionPage(
    response: ApiHttpResponse,
    strategy: ApiImportStrategy,
    monzoAccountId: AccountId,
    sessionId: ApiSessionId,
    deviceId: DeviceId,
    accountCache: AccountCache,
    currencyCache: CurrencyCache,
    attributeTypeCache: AttributeTypeCache,
    customTxFields: Map<String, String>,
    uniqueIdTxFields: Set<String>,
    counterpartyIdField: String?,
    nameMappings: CounterpartyNameMappings,
    apiSessionRepository: ApiSessionRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
): ApiImportPageResult {
    val transactions = parseTransactionsWithPath(response.body, strategy)
    val responseId = ApiResponseId(response.responseId)
    val requestId = ApiRequestId(response.requestId)
    val existingTransfers = transactionRepository.getTransactionsByAccount(monzoAccountId).first().toMutableList()

    // Index existing transfers by their unique-identifier attribute values for O(1) lookup
    val existingByUniqueId: Map<Map<String, String>, TransferId> =
        if (uniqueIdTxFields.isNotEmpty()) {
            existingTransfers
                .mapNotNull { t ->
                    val key =
                        uniqueIdTxFields.associateWith { fieldName ->
                            t.attributes.firstOrNull { it.attributeType.name == fieldName }?.value ?: return@mapNotNull null
                        }
                    key to t.id
                }.toMap()
        } else {
            emptyMap()
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
                attributeTypeCache = attributeTypeCache,
                customTxFields = customTxFields,
                uniqueIdTxFields = uniqueIdTxFields,
                counterpartyIdField = counterpartyIdField,
                nameMappings = nameMappings,
                existingByUniqueId = existingByUniqueId,
                existingTransfers = existingTransfers,
                apiSessionRepository = apiSessionRepository,
                transactionRepository = transactionRepository,
                transferSourceQueries = transferSourceQueries,
            )
        }

    return ApiImportPageResult(
        importedCount = states.count { it == ApiResponseTransactionState.IMPORTED },
        duplicateCount = states.count { it == ApiResponseTransactionState.DUPLICATE },
        errorCount = states.count { it == ApiResponseTransactionState.ERROR },
        excludedCount =
            transactions.zip(states).count { (item, state) ->
                state == ApiResponseTransactionState.IMPORTED && !item.declineReason.isNullOrBlank()
            },
        before = transactions.minOfOrNull { it.created },
        hasTransactions = transactions.isNotEmpty(),
    )
}

private suspend fun importTransactionItem(
    item: ApiTransactionPageItem,
    monzoAccountId: AccountId,
    responseId: ApiResponseId,
    requestId: ApiRequestId,
    sessionId: ApiSessionId,
    deviceId: DeviceId,
    accountCache: AccountCache,
    currencyCache: CurrencyCache,
    attributeTypeCache: AttributeTypeCache,
    customTxFields: Map<String, String>,
    uniqueIdTxFields: Set<String>,
    counterpartyIdField: String?,
    nameMappings: CounterpartyNameMappings,
    existingByUniqueId: Map<Map<String, String>, TransferId>,
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
            attributeTypeCache = attributeTypeCache,
            customTxFields = customTxFields,
            uniqueIdTxFields = uniqueIdTxFields,
            counterpartyIdField = counterpartyIdField,
            nameMappings = nameMappings,
            existingByUniqueId = existingByUniqueId,
            existingTransfers = existingTransfers,
            apiSessionRepository = apiSessionRepository,
            transactionRepository = transactionRepository,
            transferSourceQueries = transferSourceQueries,
            declineReason = item.declineReason,
        )
    } catch (expected: Exception) {
        logger.error(expected) { "Error importing API transaction: ${expected.message}" }
        apiSessionRepository.recordTransactionError(
            responseId = responseId,
            jsonPath = item.jsonPath,
            message = expected.message ?: "Unknown import error",
        )
        ApiResponseTransactionState.ERROR
    }
}

private suspend fun importValidTransactionItem(
    item: ApiTransactionPageItem,
    monzoAccountId: AccountId,
    currency: Currency,
    responseId: ApiResponseId,
    requestId: ApiRequestId,
    sessionId: ApiSessionId,
    deviceId: DeviceId,
    accountCache: AccountCache,
    attributeTypeCache: AttributeTypeCache,
    customTxFields: Map<String, String>,
    uniqueIdTxFields: Set<String>,
    counterpartyIdField: String?,
    nameMappings: CounterpartyNameMappings,
    existingByUniqueId: Map<Map<String, String>, TransferId>,
    existingTransfers: MutableList<Transfer>,
    apiSessionRepository: ApiSessionRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
    declineReason: String? = null,
): ApiResponseTransactionState {
    val counterpartyApiSource = AccountApiSource(sessionId, requestId, JsonPath("${item.jsonPath.value}.counterparty"))
    val counterpartyAccountId =
        if (item.amountMinorUnits == 0L) {
            accountCache.getOrCreateAccountId(name = nameMappings.counterpartyPrefix + "Void", transactionApiSource = counterpartyApiSource)
        } else {
            val counterpartyId = item.rawJson?.resolveCounterpartyIdentity(counterpartyIdField)
            accountCache.getOrCreateCounterpartyAccountId(
                counterpartyId = counterpartyId,
                name = nameMappings.counterpartyPrefix + item.counterpartyName(nameMappings),
                apiSource = counterpartyApiSource,
            )
        }
    val transfer = item.toTransfer(monzoAccountId, counterpartyAccountId, currency)

    // Prefer unique-identifier lookup when configured; fall back to full-field match
    val duplicateTransferId: TransferId? =
        if (uniqueIdTxFields.isNotEmpty() && item.rawJson != null) {
            val key =
                uniqueIdTxFields.associateWith { fieldName ->
                    customTxFields[fieldName]?.let { item.rawJson.resolveJsonPath(it) } ?: ""
                }
            existingByUniqueId[key]
        } else {
            existingTransfers.firstOrNull { it.matches(transfer) }?.id
        }

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
    val attributes =
        buildList {
            if (!declineReason.isNullOrBlank()) {
                add(NewAttribute(typeId = AttributeTypeId(-1), value = "declined: $declineReason"))
            }
            if (customTxFields.isNotEmpty() && item.rawJson != null) {
                for ((fieldName, jsonPath) in customTxFields) {
                    val value = item.rawJson.resolveJsonPath(jsonPath) ?: continue
                    add(NewAttribute(typeId = attributeTypeCache.getOrCreate(fieldName), value = value))
                }
            }
        }
    transactionRepository.createTransfers(
        transfers = listOf(transfer),
        newAttributes = if (attributes.isNotEmpty()) mapOf(transfer.id to attributes) else emptyMap(),
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

private fun parseTransactionsWithPath(
    json: String,
    strategy: ApiImportStrategy,
): List<ApiTransactionPageItem> =
    try {
        val mappings = strategy.transactionMappings
        Json
            .parseToJsonElement(json)
            .jsonObject[strategy.transactionsEndpoint.responseArrayKey]
            ?.jsonArray
            ?.mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val created = obj.resolveJsonPath(mappings.timestampField)?.let { Instant.parse(it) }
                val amount = obj.resolveJsonPath(mappings.amountField)?.toLongOrNull()
                val currency = obj.resolveJsonPath(mappings.currencyField)
                val declineReason = mappings.declineReasonField?.let { obj.resolveJsonPath(it) }
                if (created != null && amount != null && currency != null) {
                    ApiTransactionPageItem(
                        amountMinorUnits = amount,
                        created = created,
                        currencyCode = currency.uppercase(),
                        description = obj.resolveJsonPath(mappings.descriptionField).orEmpty(),
                        jsonPath = JsonPath("$.${strategy.transactionsEndpoint.responseArrayKey}[$index]"),
                        merchantName = mappings.merchantNameField?.let { obj.resolveJsonPath(it) },
                        counterpartyName = mappings.counterpartyNameField?.let { obj.resolveJsonPath(it) },
                        declineReason = declineReason,
                        rawJson = obj,
                    )
                } else {
                    logger.error {
                        "Skipping API transaction at index $index: missing required fields (created=$created, amount=$amount, currency=$currency)"
                    }
                    null
                }
            }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

private fun buildTransactionUrl(
    strategy: ApiImportStrategy,
    accountId: String,
    before: Instant?,
): String =
    URLBuilder(buildEndpointUrl(strategy.baseUrl, strategy.transactionsEndpoint.path))
        .apply {
            for (queryParam in strategy.transactionsEndpoint.queryParams) {
                val value =
                    when (queryParam.dynamicSource) {
                        "account.id" -> accountId
                        null -> queryParam.value
                        else -> null
                    }
                if (value != null) {
                    parameters.append(queryParam.name, value)
                }
            }
            strategy.transactionsEndpoint.pagination?.let { pagination ->
                parameters.append(pagination.limitParam, pagination.limitValue.toString())
                if (before != null) {
                    parameters.append(pagination.cursorParam, before.toString())
                }
            }
        }.buildString()

private fun buildEndpointUrl(
    baseUrl: String,
    path: String,
): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

private fun ApiResponse.toApiHttpResponse(): ApiHttpResponse =
    ApiHttpResponse(
        statusCode = 200,
        body = json,
        responseId = id.id,
        requestId = requestId.id,
    )

private fun ApiRequest.accountIdParameter(strategy: ApiImportStrategy): String? {
    val accountIdParamName =
        strategy.transactionsEndpoint.queryParams
            .firstOrNull { it.dynamicSource == "account.id" }
            ?.name
            ?: return null
    return runCatching { URLBuilder(url).parameters[accountIdParamName] }.getOrNull()
}

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
    // Pre-built by the caller in the serial section before concurrent import starts
    private val counterpartyIdIndex: MutableMap<String, AccountId>,
    private val onAccountCreated: suspend (isSourceAccount: Boolean) -> Unit = {},
) {
    private val mutex = Mutex()
    private var accountsByName: Map<String, Account>? = null

    /**
     * Counterparty accounts whose [COUNTERPARTY_ID_ATTR] attribute still needs to be written to
     * the DB. Populated inside the concurrent section (mutex-protected) but flushed in the
     * serial section afterwards to avoid concurrent DB writes that cause SQLITE_BUSY.
     */
    private val pendingCounterpartyAttributes: MutableList<Pair<AccountId, String>> = mutableListOf()

    suspend fun drainPendingCounterpartyAttributes(): List<Pair<AccountId, String>> =
        mutex.withLock {
            pendingCounterpartyAttributes.toList().also { pendingCounterpartyAttributes.clear() }
        }

    suspend fun getOrCreateAccountId(
        monzoAccountId: String?,
        name: String,
        explicitApiSource: AccountApiSource? = null,
    ): AccountId =
        mutex.withLock {
            val normalizedName = name.ifBlank { "Unknown" }
            loadAccounts()[normalizedName]?.let { return@withLock it.id }
            createAccount(monzoAccountId, normalizedName, explicitApiSource)
        }

    /**
     * Finds or creates a counterparty account.
     * When [counterpartyId] is provided it is used as the primary key (stored as
     * [COUNTERPARTY_ID_ATTR]) so the account survives name changes across imports.
     * Falls back to name-only lookup when id is null.
     * The actual attribute DB write is deferred to [pendingCounterpartyAttributes] to avoid
     * concurrent writes racing with [transactionRepository.createTransfers].
     */
    suspend fun getOrCreateCounterpartyAccountId(
        counterpartyId: String?,
        name: String,
        apiSource: AccountApiSource,
    ): AccountId =
        mutex.withLock {
            if (counterpartyId != null) {
                counterpartyIdIndex[counterpartyId]?.let { return@withLock it }
            }
            val normalizedName = name.ifBlank { "Unknown" }
            val accountId = loadAccounts()[normalizedName]?.id ?: createAccount(null, normalizedName, apiSource)
            if (counterpartyId != null) {
                counterpartyIdIndex[counterpartyId] = accountId
                pendingCounterpartyAttributes.add(accountId to counterpartyId)
            }
            accountId
        }

    suspend fun getOrCreateAccountId(
        name: String,
        transactionApiSource: AccountApiSource,
    ): AccountId = getOrCreateAccountId(null, name, explicitApiSource = transactionApiSource)

    private suspend fun createAccount(
        monzoAccountId: String?,
        normalizedName: String,
        apiSource: AccountApiSource?,
    ): AccountId {
        val now = Clock.System.now()
        val newId =
            accountRepository.createAccount(
                Account(id = AccountId(0L), name = normalizedName, openingDate = now),
            )
        accountsByName = (accountsByName ?: emptyMap()) + (normalizedName to Account(id = newId, name = normalizedName, openingDate = now))
        onAccountCreated(monzoAccountId != null)
        val resolvedSource = monzoAccountId?.let { accountApiSourceByMonzoId[it] } ?: apiSource
        if (resolvedSource != null) {
            ApiEntitySourceRecorder(
                queries = entitySourceQueries,
                deviceId = deviceId,
                sessionId = resolvedSource.sessionId,
                requestId = resolvedSource.requestId,
                jsonPath = resolvedSource.jsonPath,
            ).insert(EntityType.ACCOUNT, newId.id, 1L)
        }
        return newId
    }

    private suspend fun loadAccounts(): Map<String, Account> {
        accountsByName?.let { return it }
        return accountRepository
            .getAllAccounts()
            .first()
            .associateBy { it.name }
            .also { accountsByName = it }
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

private fun ApiImportAccount.localAccountName(strategy: ApiImportStrategy): String = strategy.accountNamePrefix + description.ifBlank { id }

private fun ApiTransactionPageItem.counterpartyName(nameMappings: CounterpartyNameMappings): String =
    rawJson?.let { rawJson ->
        nameMappings.merchantNameField
            ?.let { rawJson.resolveJsonPath(it) }
            ?.takeIf { it.isNotBlank() }
            ?: nameMappings.counterpartyNameField
                ?.let { rawJson.resolveJsonPath(it) }
                ?.takeIf { it.isNotBlank() }
    } ?: merchantName?.takeIf { it.isNotBlank() }
        ?: counterpartyName?.takeIf { it.isNotBlank() }
        ?: description.ifBlank { "Unknown" }

private fun ApiTransactionPageItem.toTransfer(
    monzoAccountId: AccountId,
    counterpartyAccountId: AccountId,
    currency: Currency,
): Transfer {
    val money = Money(amountMinorUnits.absoluteValue, currency)
    val isIncoming = amountMinorUnits > 0
    return Transfer(
        id = TransferId(0L),
        timestamp = created,
        description =
            description.ifBlank {
                merchantName?.takeIf { it.isNotBlank() } ?: counterpartyName?.takeIf { it.isNotBlank() }
                    ?: "Unknown"
            },
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

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** Resolves a dot-notation path (e.g. "merchant.name") against this JSON object. */
private fun JsonObject.resolveJsonPath(dotPath: String): String? {
    var current: JsonElement = this
    for (part in dotPath.split(".")) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return (current as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.resolveCounterpartyIdentity(counterpartyIdField: String?): String? {
    if (counterpartyIdField == null) return null

    resolveJsonPath(counterpartyIdField)?.takeIf { it.isNotBlank() }?.let { return it }

    if (counterpartyIdField.endsWith(".id")) {
        val accountIdField = counterpartyIdField.removeSuffix(".id") + ".account_id"
        resolveJsonPath(accountIdField)?.takeIf { it.isNotBlank() }?.let { return it }
    }

    val counterparty = counterpartyObject(counterpartyIdField) ?: return null
    val sortCode = counterparty.stringOrNull("sort_code")?.takeIf { it.isNotBlank() }
    val accountNumber = counterparty.stringOrNull("account_number")?.takeIf { it.isNotBlank() }
    if (sortCode != null && accountNumber != null) {
        return "bank:$sortCode:$accountNumber"
    }

    val serviceUserNumber = counterparty.stringOrNull("service_user_number")?.takeIf { it.isNotBlank() }
    if (serviceUserNumber != null) {
        return "service_user:$serviceUserNumber"
    }

    val userId = counterparty.stringOrNull("user_id")?.takeIf { it.isNotBlank() }
    if (userId != null) {
        return "user:$userId"
    }

    return null
}

private fun JsonObject.counterpartyObject(counterpartyIdField: String): JsonObject? {
    val parts = counterpartyIdField.split(".")
    if (parts.size <= 1) return this

    var current: JsonElement = this
    for (part in parts.dropLast(1)) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return current as? JsonObject
}

private class AttributeTypeCache(
    private val repo: AttributeTypeRepository,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, AttributeTypeId>()

    suspend fun getOrCreate(name: String): AttributeTypeId = mutex.withLock { cache.getOrPut(name) { repo.getOrCreate(name) } }
}
