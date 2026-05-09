@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.api

import com.moneymanager.database.ApiEntitySourceRecorder
import com.moneymanager.database.ApiImportSourceRecorder
import com.moneymanager.database.DatabaseConfig
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
import com.moneymanager.domain.repository.PersonAttributeRepository
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Instant

private val ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID = AttributeTypeId(DatabaseConfig.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID)
private val BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID = AttributeTypeId(DatabaseConfig.BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID)
private val PERSON_EXTERNAL_ID_ATTR_TYPE_ID = AttributeTypeId(DatabaseConfig.PERSON_EXTERNAL_ID_ATTR_TYPE_ID)

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
    val apiAccounts =
        accountsResponses
            .filter { it.requestId in accountsRequestIds }
            .flatMap { response -> parseAccounts(response.json, strategy) }

    var transactionResponseCount = 0

    for ((index, account) in apiAccounts.withIndex()) {
        var before: Instant? = null
        var page = 1
        var hasTransactions: Boolean
        do {
            onProgress(
                ApiTransactionsDownloadProgress(
                    accountIndex = index + 1,
                    accountCount = apiAccounts.size,
                    page = page,
                    downloadedResponsePageCount = transactionResponseCount,
                ),
            )
            val url = buildTransactionUrl(strategy, account.id, before)

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
                    accountCount = apiAccounts.size,
                    page = page,
                    downloadedResponsePageCount = transactionResponseCount,
                ),
            )
            page += 1
        } while (hasTransactions)
    }

    return ApiTransactionsDownloadResult(
        accountCount = apiAccounts.size,
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
    personAttributeRepository: PersonAttributeRepository,
    attributeTypeRepository: AttributeTypeRepository,
    accountAttributeRepository: AccountAttributeRepository,
    deviceId: DeviceId,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId? = null,
    strategy: ApiImportStrategy,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    onProgress: (String) -> Unit = {},
): ApiSessionImportResult {
    val setup =
        setupImportSession(
            apiSessionRepository,
            accountRepository,
            currencyRepository,
            transactionRepository,
            transferSourceQueries,
            entitySourceQueries,
            personRepository,
            personAccountOwnershipRepository,
            personAttributeRepository,
            attributeTypeRepository,
            accountAttributeRepository,
            deviceId,
            sessionId,
            accountsSessionId,
            strategy,
            onProgress,
        )
    precreateAndFlushCounterparties(setup, counterpartyAccountNames)
    importTransactionsConcurrently(setup)
    val totalPeople = importPeopleFromSession(setup)
    flushPostImportAttributes(setup)
    return ApiSessionImportResult(
        accountCount = setup.accountsById.size,
        transactionCount = setup.counts.totalImported,
        personCount = totalPeople,
        duplicateCount = setup.counts.totalDuplicates,
        errorCount = setup.counts.totalErrors,
        excludedCount = setup.counts.totalExcluded,
    )
}

/** Mutable progress counters shared between the setup, parallel import, and progress callback. */
private class ImportCounts(
    val totalResponses: Int,
) {
    var completedCount = 0
    var totalImported = 0
    var totalDuplicates = 0
    var totalErrors = 0
    var totalExcluded = 0
    var sourceAccountsCreated = 0
    var counterpartyAccountsCreated = 0

    fun progressMessage() =
        buildString {
            append("Imported $completedCount/$totalResponses responses")
            if (totalImported > 0) append(". $totalImported imported transaction(s)")
            if (totalDuplicates > 0) append(". $totalDuplicates duplicate(s)")
            if (totalErrors > 0) append(". $totalErrors error(s)")
            if (sourceAccountsCreated > 0) append(". $sourceAccountsCreated source account(s) created")
            if (counterpartyAccountsCreated > 0) append(". $counterpartyAccountsCreated counterparty account(s) created")
            append(".")
        }
}

/** All state created during setup that is shared across the five import steps. */
private data class ImportSetup(
    val strategy: ApiImportStrategy,
    val sessionId: ApiSessionId,
    val deviceId: DeviceId,
    val accountsById: Map<String, ApiImportAccount>,
    val transactionResponses: List<ApiResponse>,
    val requestsById: Map<ApiRequestId, ApiRequest>,
    val counterpartyIdField: String?,
    val nameMappings: CounterpartyNameMappings,
    val customTxFields: Map<String, String>,
    val uniqueIdTxFields: Set<String>,
    val accountCache: AccountCache,
    val currencyCache: CurrencyCache,
    val attributeTypeCache: AttributeTypeCache,
    val counts: ImportCounts,
    val progressMutex: Mutex,
    val onProgress: (String) -> Unit,
    val apiSessionRepository: ApiSessionRepository,
    val accountAttributeRepository: AccountAttributeRepository,
    val transactionRepository: TransactionRepository,
    val transferSourceQueries: TransferSourceQueries,
    val personRepository: PersonRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    val personAttributeRepository: PersonAttributeRepository,
)

private suspend fun setupImportSession(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
    entitySourceQueries: EntitySourceQueries,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
    attributeTypeRepository: AttributeTypeRepository,
    accountAttributeRepository: AccountAttributeRepository,
    deviceId: DeviceId,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId?,
    strategy: ApiImportStrategy,
    onProgress: (String) -> Unit,
): ImportSetup {
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
    val accountApiSourceByExternalId =
        accountsResponses
            .flatMap { response ->
                val requestId = accountsRequestsById[response.requestId]?.id ?: return@flatMap emptyList()
                parseAccountsWithPaths(response.json, strategy).map { (account, jsonPath) ->
                    account.id to AccountApiSource(resolvedAccountsSessionId, requestId, jsonPath)
                }
            }.toMap()

    val accountsById =
        accountsResponses
            .flatMap { response -> parseAccounts(response.json, strategy) }
            .associateBy { it.id }
    val transactionResponses = responses.filter { requestsById[it.requestId]?.accountIdParameter(strategy) != null }

    val currencyCache = CurrencyCache(currencyRepository)
    val attributeTypeCache = AttributeTypeCache(attributeTypeRepository)
    val customTxFields = strategy.transactionMappings.customFields
    val uniqueIdTxFields = strategy.transactionMappings.uniqueIdentifierFields
    val counterpartyIdField = strategy.transactionMappings.counterpartyIdField
    val nameMappings = CounterpartyNameMappings.from(strategy)

    // Pre-create custom transaction attribute types before the concurrent section so that
    // no two coroutines race to write the same type, which causes SQLITE_BUSY.
    // The well-known types (account-external-id, built-in type) are seeded with stable IDs
    // and do not need to be looked up.
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
    val builtInTypeIndex: MutableMap<BuiltInCounterpartyType, AccountId> =
        loadBuiltInTypeIndex(
            accountRepository = accountRepository,
            accountAttributeRepository = accountAttributeRepository,
        ).toMutableMap()

    val counts = ImportCounts(transactionResponses.size)
    val progressMutex = Mutex()
    val accountCache =
        AccountCache(
            accountRepository = accountRepository,
            entitySourceQueries = entitySourceQueries,
            deviceId = deviceId,
            accountApiSourceByExternalId = accountApiSourceByExternalId,
            counterpartyIdIndex = counterpartyIdIndex,
            builtInTypeIndex = builtInTypeIndex,
            onAccountCreated = { isSourceAccount ->
                val message =
                    progressMutex.withLock {
                        if (isSourceAccount) ++counts.sourceAccountsCreated else ++counts.counterpartyAccountsCreated
                        counts.progressMessage()
                    }
                onProgress(message)
            },
        )

    onProgress(counts.progressMessage())

    return ImportSetup(
        strategy = strategy,
        sessionId = sessionId,
        deviceId = deviceId,
        accountsById = accountsById,
        transactionResponses = transactionResponses,
        requestsById = requestsById,
        counterpartyIdField = counterpartyIdField,
        nameMappings = nameMappings,
        customTxFields = customTxFields,
        uniqueIdTxFields = uniqueIdTxFields,
        accountCache = accountCache,
        currencyCache = currencyCache,
        attributeTypeCache = attributeTypeCache,
        counts = counts,
        progressMutex = progressMutex,
        onProgress = onProgress,
        apiSessionRepository = apiSessionRepository,
        accountAttributeRepository = accountAttributeRepository,
        transactionRepository = transactionRepository,
        transferSourceQueries = transferSourceQueries,
        personRepository = personRepository,
        personAccountOwnershipRepository = personAccountOwnershipRepository,
        personAttributeRepository = personAttributeRepository,
    )
}

private suspend fun precreateAndFlushCounterparties(
    setup: ImportSetup,
    counterpartyAccountNames: Map<String, String>,
) {
    precreateCounterparties(
        transactionResponses = setup.transactionResponses,
        sessionId = setup.sessionId,
        requestsById = setup.requestsById,
        strategy = setup.strategy,
        accountCache = setup.accountCache,
        counterpartyIdField = setup.counterpartyIdField,
        counterpartyAccountNames = counterpartyAccountNames,
        nameMappings = setup.nameMappings,
    )
    if (setup.counterpartyIdField != null) {
        flushPendingCounterpartyAttributes(
            accountCache = setup.accountCache,
            accountAttributeRepository = setup.accountAttributeRepository,
        )
    }
}

private suspend fun importTransactionsConcurrently(setup: ImportSetup) {
    coroutineScope {
        setup.transactionResponses
            .map { response ->
                async {
                    val request = setup.requestsById[response.requestId] ?: return@async null
                    val account = setup.accountsById[request.accountIdParameter(setup.strategy)] ?: return@async null
                    val ownAccountId = setup.accountCache.getOrCreateAccountId(account.id, account.displayName(setup.strategy))
                    val pageResult =
                        importTransactionPage(
                            response = response.toApiHttpResponse(),
                            strategy = setup.strategy,
                            ownAccountId = ownAccountId,
                            sessionId = setup.sessionId,
                            deviceId = setup.deviceId,
                            accountCache = setup.accountCache,
                            currencyCache = setup.currencyCache,
                            attributeTypeCache = setup.attributeTypeCache,
                            customTxFields = setup.customTxFields,
                            uniqueIdTxFields = setup.uniqueIdTxFields,
                            counterpartyIdField = setup.counterpartyIdField,
                            nameMappings = setup.nameMappings,
                            apiSessionRepository = setup.apiSessionRepository,
                            transactionRepository = setup.transactionRepository,
                            transferSourceQueries = setup.transferSourceQueries,
                        )
                    val progressMessage =
                        setup.progressMutex.withLock {
                            setup.counts.totalImported += pageResult.importedCount
                            setup.counts.totalDuplicates += pageResult.duplicateCount
                            setup.counts.totalErrors += pageResult.errorCount
                            setup.counts.totalExcluded += pageResult.excludedCount
                            ++setup.counts.completedCount
                            setup.counts.progressMessage()
                        }
                    setup.onProgress(progressMessage)
                    pageResult
                }
            }.awaitAll()
    }
}

private suspend fun importPeopleFromSession(setup: ImportSetup): Int =
    importPeopleFromAccounts(
        accountsById = setup.accountsById,
        accountCache = setup.accountCache,
        strategy = setup.strategy,
        personRepository = setup.personRepository,
        personAccountOwnershipRepository = setup.personAccountOwnershipRepository,
        personAttributeRepository = setup.personAttributeRepository,
    )

private suspend fun flushPostImportAttributes(setup: ImportSetup) {
    // Flush deferred account attributes now that all concurrent writes are done.
    // This avoids SQLITE_BUSY that would occur if we wrote them inside the concurrent section.
    if (setup.counterpartyIdField != null) {
        flushPendingCounterpartyAttributes(
            accountCache = setup.accountCache,
            accountAttributeRepository = setup.accountAttributeRepository,
        )
    }
    flushPendingBuiltInTypeAttributes(
        accountCache = setup.accountCache,
        accountAttributeRepository = setup.accountAttributeRepository,
    )

    // Store custom account field values as attributes
    val customAccountFields = setup.strategy.accountMappings.customFields
    if (customAccountFields.isNotEmpty()) {
        for (account in setup.accountsById.values) {
            val rawJson = account.rawJson ?: continue
            val accountId = setup.accountCache.getOrCreateAccountId(account.id, account.displayName(setup.strategy))
            val existingAttrTypes =
                setup.accountAttributeRepository
                    .getByAccount(accountId)
                    .first()
                    .map { it.attributeType.name }
                    .toSet()
            for ((fieldName, jsonPath) in customAccountFields) {
                val value = rawJson.resolveJsonPath(jsonPath)
                if (value == null || fieldName in existingAttrTypes) continue
                val typeId = setup.attributeTypeCache.getOrCreate(fieldName)
                setup.accountAttributeRepository.insert(accountId, typeId, value)
            }
        }
    }
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
                    val downloadedName = item.cleanCounterpartyName(nameMappings) ?: item.counterpartyName(nameMappings)
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
            builtInType = null,
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
                // Skip transactions handled by built-in type logic — their counterpartyId is
                // irrelevant because they all route to a single built-in account.
                if (item.rawJson?.resolveBuiltInCounterpartyType(item.amountMinorUnits) != null) {
                    return@mapNotNull null
                }
                val counterpartyId =
                    item.rawJson?.resolveCounterpartyIdentity(counterpartyIdField)
                        ?: return@mapNotNull null
                // Use only the proper name (merchant/counterparty name field), not the
                // description fallback. Description-based names are per-transaction references
                // that would corrupt the name suggestion when the same counterpartyId appears
                // in transactions with and without a proper name.
                counterpartyId to item.cleanCounterpartyName(nameMappings)
            }
        }.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second },
        ).mapValues { (_, names) -> names.filterNotNull() }

private suspend fun loadCounterpartyIdIndex(
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
): Map<String, AccountId> {
    val index = mutableMapOf<String, AccountId>()
    for (account in accountRepository.getAllAccounts().first()) {
        accountAttributeRepository
            .getByAccount(account.id)
            .first()
            .firstOrNull { it.attributeType.id == ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID }
            ?.let { index[it.value] = account.id }
    }
    return index
}

private suspend fun loadBuiltInTypeIndex(
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
): Map<BuiltInCounterpartyType, AccountId> {
    val index = mutableMapOf<BuiltInCounterpartyType, AccountId>()
    for (account in accountRepository.getAllAccounts().first()) {
        accountAttributeRepository
            .getByAccount(account.id)
            .first()
            .firstOrNull { it.attributeType.id == BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID }
            ?.let { attribute ->
                BuiltInCounterpartyType.fromAttributeValue(attribute.value)?.let { index[it] = account.id }
            }
    }
    return index
}

private suspend fun flushPendingCounterpartyAttributes(
    accountCache: AccountCache,
    accountAttributeRepository: AccountAttributeRepository,
) {
    val pending = accountCache.drainPendingCounterpartyAttributes()
    val seen = mutableSetOf<String>()
    for ((accountId, counterpartyId) in pending) {
        if (seen.add(counterpartyId)) {
            // runCatching handles the UNIQUE constraint for counterparties already
            // having this attribute from a previous import where index was stale.
            runCatching {
                accountAttributeRepository.insertInCreationMode(accountId, ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID, counterpartyId)
            }
        }
    }
}

private suspend fun flushPendingBuiltInTypeAttributes(
    accountCache: AccountCache,
    accountAttributeRepository: AccountAttributeRepository,
) {
    val pending = accountCache.drainPendingBuiltInTypeAttributes()
    val seen = mutableSetOf<BuiltInCounterpartyType>()
    for ((accountId, builtInType) in pending) {
        if (seen.add(builtInType)) {
            runCatching {
                accountAttributeRepository.insertInCreationMode(
                    accountId = accountId,
                    attributeTypeId = BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID,
                    value = builtInType.attributeValue,
                )
            }
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
    accountsById: Map<String, ApiImportAccount>,
    accountCache: AccountCache,
    strategy: ApiImportStrategy,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
): Int {
    fun normalizedExternalId(value: String?): String? = value?.trim()?.ifBlank { null }

    val existingPeople = personRepository.getAllPeople().first()
    val peopleByFullName = existingPeople.associateBy { it.fullName }.toMutableMap()
    val peopleByExternalId =
        existingPeople
            .mapNotNull { person ->
                val externalId =
                    personAttributeRepository
                        .getByPerson(person.id)
                        .first()
                        .firstOrNull { it.attributeType.id == PERSON_EXTERNAL_ID_ATTR_TYPE_ID }
                        ?.value
                        .let(::normalizedExternalId)
                if (externalId != null) {
                    externalId to person
                } else {
                    null
                }
            }.toMap()
            .toMutableMap()
    var newPeopleCount = 0

    for (account in accountsById.values) {
        if (account.owners.isEmpty()) continue

        val accountId = accountCache.getOrCreateAccountId(account.id, account.displayName(strategy))
        val existingOwnerPersonIds =
            personAccountOwnershipRepository
                .getOwnershipsByAccount(accountId)
                .first()
                .map { it.personId }
                .toSet()

        for (owner in account.owners) {
            val externalId = normalizedExternalId(owner.userId)
            val name = owner.preferredName.trim()
            if (name.isBlank()) continue

            val matchedPerson =
                externalId
                    ?.let { peopleByExternalId[it] }
                    ?: peopleByFullName[name]
            val personId =
                matchedPerson?.id
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
                        val storedPerson = person.copy(id = newId)
                        peopleByFullName[name] = storedPerson
                        if (externalId != null) {
                            personAttributeRepository.insertInCreationMode(
                                personId = newId,
                                attributeTypeId = PERSON_EXTERNAL_ID_ATTR_TYPE_ID,
                                value = externalId,
                            )
                            peopleByExternalId[externalId] = storedPerson
                        }
                        newPeopleCount++
                        newId
                    }

            if (externalId != null && externalId !in peopleByExternalId) {
                personAttributeRepository.insertInCreationMode(
                    personId = personId,
                    attributeTypeId = PERSON_EXTERNAL_ID_ATTR_TYPE_ID,
                    value = externalId,
                )
                val existingPerson =
                    requireNotNull(matchedPerson) {
                        "Expected matched person when backfilling external ID attribute for personId=${personId.id}"
                    }
                peopleByExternalId[externalId] = existingPerson
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
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to parse accounts response for strategy '${strategy.name}'" }
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
    ownAccountId: AccountId,
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
    val existingTransfers = transactionRepository.getTransactionsByAccount(ownAccountId).first().toMutableList()

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
                ownAccountId = ownAccountId,
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
    ownAccountId: AccountId,
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
            ownAccountId = ownAccountId,
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
    ownAccountId: AccountId,
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
            val builtInCounterpartyType = item.rawJson?.resolveBuiltInCounterpartyType(item.amountMinorUnits)
            val counterpartyId = item.rawJson?.resolveCounterpartyIdentity(counterpartyIdField)
            accountCache.getOrCreateCounterpartyAccountId(
                counterpartyId = counterpartyId,
                builtInType = builtInCounterpartyType,
                name =
                    nameMappings.counterpartyPrefix +
                        (builtInCounterpartyType?.defaultCounterpartyName ?: item.counterpartyName(nameMappings)),
                apiSource = counterpartyApiSource,
            )
        }
    val transfer = item.toTransfer(ownAccountId, counterpartyAccountId, currency)

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
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to parse transactions response for strategy '${strategy.name}'" }
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
    private val accountApiSourceByExternalId: Map<String, AccountApiSource>,
    // Pre-built by the caller in the serial section before concurrent import starts
    private val counterpartyIdIndex: MutableMap<String, AccountId>,
    private val builtInTypeIndex: MutableMap<BuiltInCounterpartyType, AccountId>,
    private val onAccountCreated: suspend (isSourceAccount: Boolean) -> Unit = {},
) {
    private val mutex = Mutex()
    private var accountsByName: Map<String, Account>? = null

    /**
     * Counterparty accounts whose account-external-id attribute still needs to be written to
     * the DB. Populated inside the concurrent section (mutex-protected) but flushed in the
     * serial section afterwards to avoid concurrent DB writes that cause SQLITE_BUSY.
     */
    private val pendingCounterpartyAttributes: MutableList<Pair<AccountId, String>> = mutableListOf()
    private val pendingBuiltInTypeAttributes: MutableList<Pair<AccountId, BuiltInCounterpartyType>> = mutableListOf()

    suspend fun drainPendingCounterpartyAttributes(): List<Pair<AccountId, String>> =
        mutex.withLock {
            pendingCounterpartyAttributes.toList().also { pendingCounterpartyAttributes.clear() }
        }

    suspend fun drainPendingBuiltInTypeAttributes(): List<Pair<AccountId, BuiltInCounterpartyType>> =
        mutex.withLock {
            pendingBuiltInTypeAttributes.toList().also { pendingBuiltInTypeAttributes.clear() }
        }

    suspend fun getOrCreateAccountId(
        externalId: String?,
        name: String,
        explicitApiSource: AccountApiSource? = null,
    ): AccountId =
        mutex.withLock {
            val normalizedName = name.ifBlank { "Unknown" }
            loadAccounts()[normalizedName]?.let { return@withLock it.id }
            createAccount(externalId, normalizedName, explicitApiSource)
        }

    /**
     * Finds or creates a counterparty account.
     * When [counterpartyId] is provided it is used as the primary key (stored as the
     * account-external-id attribute) so the account survives name changes across imports.
     * Falls back to name-only lookup when id is null.
     * The actual attribute DB write is deferred to [pendingCounterpartyAttributes] to avoid
     * concurrent writes racing with [transactionRepository.createTransfers].
     */
    suspend fun getOrCreateCounterpartyAccountId(
        counterpartyId: String?,
        builtInType: BuiltInCounterpartyType?,
        name: String,
        apiSource: AccountApiSource,
    ): AccountId =
        mutex.withLock {
            if (builtInType != null) {
                // Built-in type transactions all share one account; counterpartyId is ignored so
                // that ATM withdrawals from different locations always consolidate into one account.
                builtInTypeIndex[builtInType]?.let { return@withLock it }
                val normalizedName = name.ifBlank { "Unknown" }
                val accountId = loadAccounts()[normalizedName]?.id ?: createAccount(null, normalizedName, apiSource)
                builtInTypeIndex[builtInType] = accountId
                pendingBuiltInTypeAttributes.add(accountId to builtInType)
                return@withLock accountId
            }
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
        externalId: String?,
        normalizedName: String,
        apiSource: AccountApiSource?,
    ): AccountId {
        val now = Clock.System.now()
        val newId =
            accountRepository.createAccount(
                Account(id = AccountId(0L), name = normalizedName, openingDate = now),
            )
        accountsByName = (accountsByName ?: emptyMap()) + (normalizedName to Account(id = newId, name = normalizedName, openingDate = now))
        onAccountCreated(externalId != null)
        val resolvedSource = externalId?.let { accountApiSourceByExternalId[it] } ?: apiSource
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

private fun ApiImportAccount.displayName(strategy: ApiImportStrategy): String = strategy.accountNamePrefix + description.ifBlank { id }

private fun ApiTransactionPageItem.counterpartyName(nameMappings: CounterpartyNameMappings): String =
    cleanCounterpartyName(nameMappings) ?: description.ifBlank { "Unknown" }

/**
 * Returns the counterparty's proper name (merchant or counterparty field), or null if no
 * configured name field has a value. Unlike [counterpartyName], this never falls back to the
 * transaction description, so it is safe to use when building name suggestions for the
 * counterparty-confirmation dialog — description-based fallbacks are often per-transaction
 * references that should not be used as account names.
 */
private fun ApiTransactionPageItem.cleanCounterpartyName(nameMappings: CounterpartyNameMappings): String? =
    rawJson?.let { rawJson ->
        nameMappings.merchantNameField
            ?.let { rawJson.resolveJsonPath(it) }
            ?.takeIf { it.isNotBlank() }
            ?: nameMappings.counterpartyNameField
                ?.let { rawJson.resolveJsonPath(it) }
                ?.takeIf { it.isNotBlank() }
    } ?: merchantName?.takeIf { it.isNotBlank() }
        ?: counterpartyName?.takeIf { it.isNotBlank() }

private fun ApiTransactionPageItem.toTransfer(
    ownAccountId: AccountId,
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
        sourceAccountId = if (isIncoming) counterpartyAccountId else ownAccountId,
        targetAccountId = if (isIncoming) ownAccountId else counterpartyAccountId,
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

private enum class BuiltInCounterpartyType(
    val attributeValue: String,
    val defaultCounterpartyName: String,
) {
    ATM(
        attributeValue = "ATM",
        defaultCounterpartyName = "ATM",
    ),
    ;

    companion object {
        fun fromAttributeValue(value: String): BuiltInCounterpartyType? = entries.firstOrNull { it.attributeValue == value }
    }
}

private fun JsonObject.resolveBuiltInCounterpartyType(amountMinorUnits: Long): BuiltInCounterpartyType? {
    if (amountMinorUnits >= 0L) return null
    if (this["atm_fees_detailed"] is JsonObject) return BuiltInCounterpartyType.ATM
    val hasAtmLabel =
        this["labels"]
            ?.let { it as? JsonArray }
            ?.any { label ->
                label
                    .jsonPrimitive
                    .contentOrNull
                    ?.startsWith("withdrawal.atm", ignoreCase = true) == true
            } == true
    if (hasAtmLabel) return BuiltInCounterpartyType.ATM
    val mcc = (this["metadata"] as? JsonObject)?.stringOrNull("mcc")
    if (mcc == "6011") return BuiltInCounterpartyType.ATM
    if (stringOrNull("category")?.equals("cash", ignoreCase = true) == true) {
        val hasMerchant = (this["merchant"] as? JsonObject)?.isNotEmpty() == true
        val hasCounterpartyDetails = (this["counterparty"] as? JsonObject)?.isNotEmpty() == true
        if (!hasMerchant && !hasCounterpartyDetails) {
            return BuiltInCounterpartyType.ATM
        }
    }
    return null
}

private class AttributeTypeCache(
    private val repo: AttributeTypeRepository,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, AttributeTypeId>()

    suspend fun getOrCreate(name: String): AttributeTypeId = mutex.withLock { cache.getOrPut(name) { repo.getOrCreate(name) } }
}
