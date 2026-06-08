@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.api

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.database.DatabaseConfig
import com.moneymanager.domain.ApiEntitySourceRecord
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.*
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.model.apistrategy.PredicateOp
import com.moneymanager.domain.model.apistrategy.RulePredicate
import com.moneymanager.domain.model.apistrategy.RuleSign
import com.moneymanager.domain.repository.AccountAttributeCreateInput
import com.moneymanager.domain.repository.AccountAttributeRepository
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiResponseTransactionInsert
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiHttpResponse
import com.moneymanager.rest.ScaParams
import com.moneymanager.ui.screens.transactions.logger
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
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
private val ACCOUNT_SORT_CODE_ATTR_TYPE_ID = AttributeTypeId(DatabaseConfig.ACCOUNT_SORT_CODE_ATTR_TYPE_ID)
private val ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID = AttributeTypeId(DatabaseConfig.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID)
private const val API_IMPORT_TRANSACTION_WRITE_BATCH_SIZE = 100

data class ApiSessionImportResult(
    val accountCount: Int,
    val transactionCount: Int,
    val personCount: Int = 0,
    val duplicateCount: Int = 0,
    val errorCount: Int = 0,
    val excludedCount: Int = 0,
)

data class ApiSessionImportProgress(
    val detail: String,
    val progress: Float? = null,
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

data class ApiPeopleDownloadResult(
    val personCount: Int,
    val skipped: Boolean = false,
)

data class ApiPeopleImportResult(
    val personCount: Int,
    val ownershipCount: Int,
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
    sca: ScaParams? = null,
): ApiAccountsDownloadResult {
    val existingRequests = apiSessionRepository.getRequestsBySession(sessionId)
    val existingResponses = apiSessionRepository.getResponsesBySession(sessionId)
    val existingResponsesByRequestId = existingResponses.associateBy { it.requestId }
    val existingRequestsByUrl = existingRequests.associateBy { it.url }

    // Resolve ancestor resources (e.g. Wise profiles) first; for a flat API (Monzo) this is a
    // single empty context so the accounts endpoint is fetched exactly once.
    val ancestorContexts =
        fetchAncestorContexts(token, apiClient, strategy, existingRequestsByUrl, existingResponsesByRequestId, sca)

    var totalAccounts = 0
    var anyFetched = false
    for (ancestors in ancestorContexts) {
        val url = buildAccountsUrl(strategy, ImportUrlContext(ancestorItems = ancestors))
        // Incremental: reuse a stored response only when both request and response are present.
        val existingResponse = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }
        val json =
            if (existingResponse != null) {
                existingResponse.json
            } else {
                anyFetched = true
                fetchResponse(url = url, token = token, apiClient = apiClient, sca = sca).body
            }
        totalAccounts += parseAccounts(json, strategy).size
    }
    return ApiAccountsDownloadResult(accountCount = totalAccounts, skipped = !anyFetched)
}

/**
 * Fetches each configured ancestor endpoint in order, expanding the context tree so every leaf is
 * a full chain of ancestor items used to template the accounts/transactions endpoints. Returns a
 * single empty chain when the strategy has no ancestor endpoints.
 */
private suspend fun fetchAncestorContexts(
    token: String,
    apiClient: ApiClient,
    strategy: ApiImportStrategy,
    existingRequestsByUrl: Map<String, ApiRequest>,
    existingResponsesByRequestId: Map<ApiRequestId, ApiResponse>,
    sca: ScaParams?,
): List<List<JsonObject>> {
    var contexts: List<List<JsonObject>> = listOf(emptyList())
    strategy.ancestorEndpoints.forEach { endpoint ->
        val next = mutableListOf<List<JsonObject>>()
        for (ancestors in contexts) {
            val url = buildEndpointRequestUrl(strategy.baseUrl, endpoint, ImportUrlContext(ancestorItems = ancestors))
            val existingResponse = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }
            val json = existingResponse?.json ?: fetchResponse(url = url, token = token, apiClient = apiClient, sca = sca).body
            for (item in responseItemsArray(json, endpoint.responseArrayKey).orEmpty()) {
                (item as? JsonObject)?.let { next += ancestors + it }
            }
        }
        contexts = next
    }
    return contexts
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
    sca: ScaParams? = null,
    onProgress: (ApiTransactionsDownloadProgress) -> Unit = {},
): ApiTransactionsDownloadResult {
    val existingRequests = apiSessionRepository.getRequestsBySession(sessionId)
    val existingResponses = apiSessionRepository.getResponsesBySession(sessionId)
    val existingResponsesByRequestId = existingResponses.associateBy { it.requestId }
    val existingRequestsByUrl = existingRequests.associateBy { it.url }

    // Read accounts from a separate accounts session if provided, otherwise from this session.
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
    val accountsRequestsById = accountsRequests.associateBy { it.id }

    // Each account is paired with the ancestor context (e.g. profile id) recovered from the path of
    // the accounts request that produced it, so its transaction URLs can be templated.
    val accountEntries: List<Pair<ApiImportAccount, Map<String, String>>> =
        accountsResponses.flatMap { response ->
            val request = accountsRequestsById[response.requestId] ?: return@flatMap emptyList()
            if (!request.isAccountsRequest(strategy)) return@flatMap emptyList()
            val ancestorVars = request.ancestorVars(strategy)
            parseAccounts(response.json, strategy).map { it to ancestorVars }
        }

    var transactionResponseCount = 0
    val pagination = strategy.transactionsEndpoint.pagination
    val now = Clock.System.now()

    accountEntries.forEachIndexed { index, (account, ancestorVars) ->
        if (pagination?.mode == PaginationMode.DATE_WINDOW) {
            dateWindows(pagination, now).forEachIndexed { windowIndex, window ->
                val ctx =
                    ImportUrlContext(
                        account = account,
                        ancestorVars = ancestorVars,
                        windowStart = window.start.toString(),
                        windowEnd = window.end.toString(),
                    )
                val url = buildDateWindowTransactionUrl(strategy, pagination, ctx, window)
                onProgress(
                    ApiTransactionsDownloadProgress(
                        accountIndex = index + 1,
                        accountCount = accountEntries.size,
                        page = windowIndex + 1,
                        downloadedResponsePageCount = transactionResponseCount,
                    ),
                )
                val existingResponse = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }
                if (existingResponse == null) {
                    fetchResponse(url = url, token = token, apiClient = apiClient, sca = sca)
                    transactionResponseCount += 1
                }
            }
        } else {
            var before: Instant? = null
            var page = 1
            var hasTransactions: Boolean
            do {
                val ctx = ImportUrlContext(account = account, ancestorVars = ancestorVars)
                onProgress(
                    ApiTransactionsDownloadProgress(
                        accountIndex = index + 1,
                        accountCount = accountEntries.size,
                        page = page,
                        downloadedResponsePageCount = transactionResponseCount,
                    ),
                )
                val url = buildCursorTransactionUrl(strategy, ctx, before)

                // Incremental: reuse a stored response only when both request and response are present.
                // An orphan request (response missing from an interrupted prior run) is treated as a
                // cache miss so pagination can make progress on retry.
                val existingResponse = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }
                val transactions: List<ApiTransactionPageItem> =
                    if (existingResponse != null) {
                        parseTransactionsWithPath(existingResponse.json, strategy)
                    } else {
                        val response = fetchResponse(url = url, token = token, apiClient = apiClient, sca = sca)
                        transactionResponseCount += 1
                        parseTransactionsWithPath(response.body, strategy)
                    }

                before = transactions.minOfOrNull { it.created }
                hasTransactions = transactions.isNotEmpty()
                page += 1
                // A null pagination config means the endpoint returns the whole feed in one response
                // (e.g. Starling); fetch exactly one page. Cursor mode keeps paging until a page is
                // empty. Without this guard a non-paginating endpoint that ignores the cursor would
                // return the same items forever and loop indefinitely.
            } while (hasTransactions && pagination != null)
        }
    }

    return ApiTransactionsDownloadResult(
        accountCount = accountEntries.size,
        transactionResponseCount = transactionResponseCount,
    )
}

/**
 * Downloads the people/profiles endpoint (the account holder identity) into [sessionId]. Only
 * applies to strategies with [com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig]
 * configured (e.g. Wise); a no-op otherwise.
 */
suspend fun downloadApiSessionPeople(
    token: String,
    apiClient: ApiClient,
    apiSessionRepository: ApiSessionRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    sca: ScaParams? = null,
): ApiPeopleDownloadResult {
    val config = strategy.peopleDownload ?: return ApiPeopleDownloadResult(personCount = 0, skipped = true)
    val existingRequestsByUrl = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.url }
    val existingResponsesByRequestId = apiSessionRepository.getResponsesBySession(sessionId).associateBy { it.requestId }

    val url = buildEndpointRequestUrl(strategy.baseUrl, config.endpoint, ImportUrlContext())
    val existingResponse = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }
    val json = existingResponse?.json ?: fetchResponse(url = url, token = token, apiClient = apiClient, sca = sca).body
    val count = responseItemsArray(json, config.endpoint.responseArrayKey)?.count { it is JsonObject } ?: 0
    return ApiPeopleDownloadResult(personCount = count, skipped = existingResponse != null)
}

/**
 * Imports people from a people-download session: creates a [Person] for each profile holder and, when
 * [accountsSessionId] is provided, links each person as an owner of the accounts fetched under their
 * profile (via [ApiPersonImportConfig.accountOwnerAncestorExpr]).
 */
suspend fun importApiSessionPeople(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
    attributeTypeRepository: AttributeTypeRepository,
    entitySource: EntitySource,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    accountsSessionId: ApiSessionId? = null,
): ApiPeopleImportResult {
    val config = strategy.peopleDownload ?: return ApiPeopleImportResult(personCount = 0, ownershipCount = 0)
    val externalIdAttributeTypeId = strategy.personExternalIdAttribute?.let { attributeTypeRepository.getOrCreate(it) }
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val peopleResponses =
        apiSessionRepository.getResponsesBySession(sessionId).filter { response ->
            val path = requestsById[response.requestId]?.encodedPath() ?: return@filter false
            extractPathVariables(config.endpoint.path, path) != null
        }
    val ownedAccountsByProfile =
        buildProfileAccountMap(apiSessionRepository, accountRepository, accountAttributeRepository, accountsSessionId, strategy, config)
    // Flat providers with a single global account holder (no ancestor hierarchy) link that holder to
    // every account imported in the session.
    val allSessionAccountIds =
        if (config.ownsAllAccounts) {
            loadSessionAccountIds(apiSessionRepository, accountRepository, accountAttributeRepository, accountsSessionId, strategy)
        } else {
            emptyList()
        }
    // Ownership links can only be created against accounts that already exist. When people are
    // imported before their accounts (no accounts session, or accounts not yet imported), the
    // profile→account map is empty and people are created without ownerships; flag that so the
    // empty-ownership outcome isn't mistaken for a successful link.
    val noLinkableAccounts =
        if (config.ownsAllAccounts) allSessionAccountIds.isEmpty() else ownedAccountsByProfile.isEmpty()
    if (config.accountOwnerAncestorExpr != null || config.ownsAllAccounts) {
        if (noLinkableAccounts) {
            logger.warn { "Importing people with no imported accounts to link; ownerships will not be created. Import accounts first." }
        }
    }
    val peopleIndex = loadPeopleIndex(personRepository, personAttributeRepository, externalIdAttributeTypeId)

    var personCount = 0
    var ownershipCount = 0
    for (response in peopleResponses) {
        val requestId = requestsById[response.requestId]?.id
        responseItemsArray(response.json, config.endpoint.responseArrayKey)
            ?.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val owner = obj.toPersonOwner(config, index) ?: return@forEachIndexed
                val ownedAccounts =
                    if (config.ownsAllAccounts) allSessionAccountIds else ownedAccountsByProfile[owner.userId].orEmpty()
                if (ownedAccounts.isEmpty()) {
                    val created =
                        resolveOrCreatePerson(
                            owner = owner,
                            peopleIndex = peopleIndex,
                            personRepository = personRepository,
                            personAttributeRepository = personAttributeRepository,
                            externalIdAttributeTypeId = externalIdAttributeTypeId,
                            entitySource = entitySource,
                            sessionId = sessionId,
                            requestId = requestId,
                        )?.second == true
                    if (created) personCount++
                } else {
                    for (accountId in ownedAccounts) {
                        val counts =
                            importOwnersForAccount(
                                owners = listOf(owner),
                                accountId = accountId,
                                peopleIndex = peopleIndex,
                                personRepository = personRepository,
                                personAccountOwnershipRepository = personAccountOwnershipRepository,
                                personAttributeRepository = personAttributeRepository,
                                externalIdAttributeTypeId = externalIdAttributeTypeId,
                                entitySource = entitySource,
                                sessionId = sessionId,
                                requestId = requestId,
                            )
                        personCount += counts.newPeople
                        ownershipCount += counts.newOwnerships
                    }
                }
            }
    }
    return ApiPeopleImportResult(personCount = personCount, ownershipCount = ownershipCount)
}

/** Builds a map of profile external id → the [AccountId]s fetched under that profile. */
private suspend fun buildProfileAccountMap(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
    accountsSessionId: ApiSessionId?,
    strategy: ApiImportStrategy,
    config: ApiPersonImportConfig,
): Map<String, List<AccountId>> {
    val ancestorExpr = config.accountOwnerAncestorExpr ?: return emptyMap()
    if (accountsSessionId == null) return emptyMap()
    val accountIdByExternalId = loadAccountExternalIdIndex(accountRepository, accountAttributeRepository)
    val requestsById = apiSessionRepository.getRequestsBySession(accountsSessionId).associateBy { it.id }
    val result = mutableMapOf<String, MutableList<AccountId>>()
    for (response in apiSessionRepository.getResponsesBySession(accountsSessionId)) {
        val profileId =
            requestsById[response.requestId]
                ?.takeIf { it.isAccountsRequest(strategy) }
                ?.ancestorVars(strategy)
                ?.get(ancestorExpr)
        if (profileId != null) {
            for (account in parseAccounts(response.json, strategy)) {
                accountIdByExternalId[account.id]?.let { result.getOrPut(profileId) { mutableListOf() }.add(it) }
            }
        }
    }
    return result
}

/** Returns every [AccountId] imported under [accountsSessionId] (used for [ApiPersonImportConfig.ownsAllAccounts]). */
private suspend fun loadSessionAccountIds(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
    accountsSessionId: ApiSessionId?,
    strategy: ApiImportStrategy,
): List<AccountId> {
    if (accountsSessionId == null) return emptyList()
    val accountIdByExternalId = loadAccountExternalIdIndex(accountRepository, accountAttributeRepository)
    val requestsById = apiSessionRepository.getRequestsBySession(accountsSessionId).associateBy { it.id }
    val result = mutableListOf<AccountId>()
    for (response in apiSessionRepository.getResponsesBySession(accountsSessionId)) {
        val isAccountsResponse = requestsById[response.requestId]?.isAccountsRequest(strategy) == true
        if (isAccountsResponse) {
            for (account in parseAccounts(response.json, strategy)) {
                accountIdByExternalId[account.id]?.let { result.add(it) }
            }
        }
    }
    return result.distinct()
}

/** Maps a profile object to an owner; returns null when no usable name can be derived. */
private fun JsonObject.toPersonOwner(
    config: ApiPersonImportConfig,
    index: Int,
): ApiImportAccountOwner? {
    val externalId = resolveJsonPath(config.externalIdField)?.takeIf { it.isNotBlank() }
    val first =
        config.preferredNameField?.let { resolveJsonPath(it) }?.takeIf { it.isNotBlank() }
            ?: resolveJsonPath(config.firstNameField)?.takeIf { it.isNotBlank() }
    val last = config.lastNameField?.let { resolveJsonPath(it) }?.takeIf { it.isNotBlank() }
    val fallback = config.fallbackNameField?.let { resolveJsonPath(it) }?.takeIf { it.isNotBlank() }
    val name = listOfNotNull(first, last).joinToString(" ").ifBlank { fallback ?: return null }
    return ApiImportAccountOwner(
        userId = externalId.orEmpty(),
        preferredName = name,
        sortCode = null,
        accountNumber = null,
        jsonPath = arrayItemJsonPath(config.endpoint.responseArrayKey, index),
    )
}

suspend fun importApiSessionTransactions(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    entitySource: EntitySource,
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
    onProgress: (ApiSessionImportProgress) -> Unit = {},
): ApiSessionImportResult {
    val setup =
        setupImportSession(
            apiSessionRepository,
            accountRepository,
            currencyRepository,
            transactionRepository,
            entitySource,
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
    onProgress(ApiSessionImportProgress(detail = "Preparing import session...", progress = 0.05f))
    ensureSourceAccounts(setup)
    precreateAndFlushCounterparties(setup, counterpartyAccountNames)
    onProgress(ApiSessionImportProgress(detail = "Counterparties prepared.", progress = 0.2f))
    importTransactionsConcurrently(setup)
    onProgress(ApiSessionImportProgress(detail = "Transactions imported. Processing people...", progress = 0.82f))
    flushPostTransactionAttributes(setup)
    val totalPeople = importPeopleFromSession(setup)
    onProgress(ApiSessionImportProgress(detail = "People imported. Finalizing...", progress = 0.92f))
    flushPostImportAttributes(setup)
    onProgress(ApiSessionImportProgress(detail = "Import finalized.", progress = 0.98f))
    return ApiSessionImportResult(
        accountCount = setup.accountsById.size,
        transactionCount = setup.counts.totalImported,
        personCount = totalPeople,
        duplicateCount = setup.counts.totalDuplicates,
        errorCount = setup.counts.totalErrors,
        excludedCount = setup.counts.totalExcluded,
    )
}

/**
 * Materialises an [com.moneymanager.domain.model.Account] for every downloaded account up front, so
 * accounts exist even when they have no owners and no transactions (e.g. Wise balances). Account
 * creation is otherwise only a side effect of importing transactions or owners. Idempotent.
 */
private suspend fun ensureSourceAccounts(setup: ImportSetup) {
    for (account in setup.accountsById.values) {
        setup.accountCache.getOrCreateAccountId(account.id, account.displayName(setup.strategy))
    }
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

    fun detailMessage() =
        buildString {
            if (totalResponses > 0) {
                append("Importing transaction responses: $completedCount/$totalResponses")
            } else {
                append("Importing accounts and related data")
            }
            if (totalImported > 0) append(". $totalImported imported")
            if (totalDuplicates > 0) append(". $totalDuplicates duplicate(s)")
            if (totalErrors > 0) append(". $totalErrors error(s)")
            if (sourceAccountsCreated > 0) append(". $sourceAccountsCreated source account(s) created")
            if (counterpartyAccountsCreated > 0) append(". $counterpartyAccountsCreated counterparty account(s) created")
            append(".")
        }

    fun progressFraction(): Float? =
        if (totalResponses <= 0) {
            null
        } else {
            // Keep most of the bar for transaction-page import itself.
            val responseFraction = completedCount.toFloat() / totalResponses.toFloat()
            0.2f + (responseFraction * 0.6f)
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
    val onProgress: (ApiSessionImportProgress) -> Unit,
    val apiSessionRepository: ApiSessionRepository,
    val accountAttributeRepository: AccountAttributeRepository,
    val transactionRepository: TransactionRepository,
    val entitySource: EntitySource,
    val personRepository: PersonRepository,
    val personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    val personAttributeRepository: PersonAttributeRepository,
)

private suspend fun setupImportSession(
    apiSessionRepository: ApiSessionRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    entitySource: EntitySource,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
    attributeTypeRepository: AttributeTypeRepository,
    accountAttributeRepository: AccountAttributeRepository,
    deviceId: DeviceId,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId?,
    strategy: ApiImportStrategy,
    onProgress: (ApiSessionImportProgress) -> Unit,
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

    // Only responses produced by the accounts endpoint are parsed as accounts; ancestor responses
    // (e.g. Wise profiles) and transaction responses are excluded.
    val accountOnlyResponses =
        accountsResponses.filter { accountsRequestsById[it.requestId]?.isAccountsRequest(strategy) == true }

    // Build a map from external account ID to the accounts-list source (request + jsonPath)
    // so newly created accounts can be linked back to their API origin.
    val accountApiSourceByExternalId =
        accountOnlyResponses
            .flatMap { response ->
                val requestId = accountsRequestsById[response.requestId]?.id ?: return@flatMap emptyList()
                parseAccountsWithPaths(response.json, strategy).map { (account, jsonPath) ->
                    account.id to AccountApiSource(resolvedAccountsSessionId, requestId, jsonPath)
                }
            }.toMap()

    val accountsById =
        accountOnlyResponses
            .flatMap { response -> parseAccounts(response.json, strategy) }
            .associateBy { it.id }
    val transactionResponses = responses.filter { requestsById[it.requestId]?.resolveAccountExternalId(strategy) != null }

    val currencyCache = CurrencyCache(currencyRepository)
    val attributeTypeCache = AttributeTypeCache(attributeTypeRepository)
    val customTxFields = strategy.transactionMappings.customFields
    val uniqueIdTxFields = strategy.transactionMappings.uniqueIdentifierFields
    val counterpartyIdField = strategy.transactionMappings.counterpartyIdField
    val nameMappings = CounterpartyNameMappings.from(strategy)

    // Pre-create transaction attribute types before the concurrent section so that
    // no two coroutines race to write the same type, which causes SQLITE_BUSY.
    // Pre-create configured attribute types before concurrent import starts.
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
    val sourceAccountExternalIdIndex =
        loadAccountExternalIdIndex(
            accountRepository = accountRepository,
            accountAttributeRepository = accountAttributeRepository,
        ).toMutableMap()
    val builtInTypeIndex: MutableMap<String, AccountId> =
        loadBuiltInTypeIndex(
            accountRepository = accountRepository,
            accountAttributeRepository = accountAttributeRepository,
        ).toMutableMap()
    val personalCounterpartyKeyIndex: MutableMap<String, AccountId> =
        loadPersonalCounterpartyKeyIndex(
            accountRepository = accountRepository,
            accountAttributeRepository = accountAttributeRepository,
        ).toMutableMap()

    val counts = ImportCounts(transactionResponses.size)
    val progressMutex = Mutex()
    val accountCache =
        AccountCache(
            accountRepository = accountRepository,
            accountAttributeRepository = accountAttributeRepository,
            entitySource = entitySource,
            accountApiSourceByExternalId = accountApiSourceByExternalId,
            sourceAccountExternalIdIndex = sourceAccountExternalIdIndex,
            counterpartyIdIndex = counterpartyIdIndex,
            personalCounterpartyKeyIndex = personalCounterpartyKeyIndex,
            builtInTypeIndex = builtInTypeIndex,
            onAccountCreated = { isSourceAccount ->
                val message =
                    progressMutex.withLock {
                        if (isSourceAccount) ++counts.sourceAccountsCreated else ++counts.counterpartyAccountsCreated
                        counts.detailMessage()
                    }
                onProgress(ApiSessionImportProgress(detail = message, progress = counts.progressFraction()))
            },
        )

    onProgress(ApiSessionImportProgress(detail = counts.detailMessage(), progress = counts.progressFraction()))

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
        entitySource = entitySource,
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
    val pageContexts = mutableListOf<ApiImportPageContext>()
    setup.transactionResponses.forEachIndexed { index, response ->
        val request = setup.requestsById[response.requestId] ?: return@forEachIndexed
        val account = setup.accountsById[request.resolveAccountExternalId(setup.strategy)] ?: return@forEachIndexed
        val ownAccountId = setup.accountCache.getOrCreateAccountId(account.id, account.displayName(setup.strategy))
        pageContexts +=
            ApiImportPageContext(
                index = index,
                response = response.toApiHttpResponse(),
                requestId = request.id,
                ownAccountId = ownAccountId,
            )
    }

    val pageResults = importTransactionPages(pageContexts, setup)
    pageResults.forEach { pageResult ->
        val progressUpdate =
            setup.progressMutex.withLock {
                val previousProgress = 0.8f
                setup.counts.totalImported += pageResult.importedCount
                setup.counts.totalDuplicates += pageResult.duplicateCount
                setup.counts.totalErrors += pageResult.errorCount
                setup.counts.totalExcluded += pageResult.excludedCount
                ++setup.counts.completedCount
                val newProgress = maxOf(previousProgress, setup.counts.progressFraction() ?: previousProgress)
                ApiSessionImportProgress(
                    detail = setup.counts.detailMessage(),
                    progress = newProgress,
                )
            }
        setup.onProgress(progressUpdate)
    }
}

private suspend fun importPeopleFromSession(setup: ImportSetup): Int {
    val externalIdAttributeTypeId =
        setup.strategy.personExternalIdAttribute?.let { setup.attributeTypeCache.getOrCreate(it) }
    return importPeopleFromAccounts(
        accountsById = setup.accountsById,
        accountCache = setup.accountCache,
        strategy = setup.strategy,
        personRepository = setup.personRepository,
        personAccountOwnershipRepository = setup.personAccountOwnershipRepository,
        personAttributeRepository = setup.personAttributeRepository,
        accountAttributeRepository = setup.accountAttributeRepository,
        entitySource = setup.entitySource,
        accountApiSourceByExternalId = setup.accountCache.accountApiSourceByExternalId,
        sessionId = setup.sessionId,
        externalIdAttributeTypeId = externalIdAttributeTypeId,
    ) +
        importPeopleFromCounterparties(
            transactionResponses = setup.transactionResponses,
            sessionId = setup.sessionId,
            requestsById = setup.requestsById,
            strategy = setup.strategy,
            accountCache = setup.accountCache,
            counterpartyIdField = setup.counterpartyIdField,
            nameMappings = setup.nameMappings,
            accountAttributeRepository = setup.accountAttributeRepository,
            entitySource = setup.entitySource,
            personRepository = setup.personRepository,
            personAccountOwnershipRepository = setup.personAccountOwnershipRepository,
            personAttributeRepository = setup.personAttributeRepository,
            externalIdAttributeTypeId = externalIdAttributeTypeId,
        )
}

private suspend fun flushPostTransactionAttributes(setup: ImportSetup) {
    if (setup.counterpartyIdField != null) {
        flushPendingCounterpartyAttributes(
            accountCache = setup.accountCache,
            accountAttributeRepository = setup.accountAttributeRepository,
        )
    }
    flushPendingPersonalCounterpartyAttributes(
        accountCache = setup.accountCache,
        accountAttributeRepository = setup.accountAttributeRepository,
    )
    flushPendingBuiltInTypeAttributes(
        accountCache = setup.accountCache,
        accountAttributeRepository = setup.accountAttributeRepository,
    )
}

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
                setup.accountAttributeRepository.insertInCreationMode(accountId, typeId, value)
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
            .filter { requestsById[it.requestId]?.resolveAccountExternalId(strategy) != null }

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
        coroutineScope {
            transactionResponses
                .map { response ->
                    async {
                        val request = requestsById[response.requestId] ?: return@async emptyList()
                        parseTransactionsWithPath(response.json, strategy).mapNotNull { item ->
                            if (item.rawJson?.resolveBuiltInCounterpartyType(strategy.builtInCounterpartyRules, item.amountSign) != null) {
                                return@mapNotNull null
                            }
                            val counterpartyId =
                                item.rawJson?.resolveCounterpartyIdentity(counterpartyIdField, strategy.peopleMappings)
                                    ?: return@mapNotNull null
                            val downloadedName = item.cleanCounterpartyName(nameMappings) ?: item.counterpartyName(nameMappings)
                            CounterpartyImportCandidate(
                                counterpartyId = counterpartyId,
                                downloadedName = downloadedName,
                                apiSource = AccountApiSource(sessionId, request.id, JsonPath("${item.jsonPath.value}.counterparty")),
                            )
                        }
                    }
                }.awaitAll()
                .flatten()
                .groupBy { it.counterpartyId }
        }

    coroutineScope {
        val requests =
            counterparties.map { (counterpartyId, candidates) ->
                val accountName =
                    counterpartyAccountNames[counterpartyId]
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: (strategy.counterpartyPrefix + suggestCounterpartyName(candidates.map { it.downloadedName }))
                CounterpartyBatchCreateRequest(
                    counterpartyId = counterpartyId,
                    name = accountName,
                    apiSource = candidates.first().apiSource,
                )
            }
        accountCache.precreateCounterpartyAccountsBatch(requests)
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
                if (item.rawJson?.resolveBuiltInCounterpartyType(strategy.builtInCounterpartyRules, item.amountSign) != null) {
                    return@mapNotNull null
                }
                val counterpartyId =
                    item.rawJson?.resolveCounterpartyIdentity(counterpartyIdField, strategy.peopleMappings)
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
): Map<String, AccountId> = loadAccountExternalIdIndex(accountRepository, accountAttributeRepository)

private suspend fun loadAccountExternalIdIndex(
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
): Map<String, AccountId> {
    val index = mutableMapOf<String, AccountId>()
    for (account in accountRepository.getAllAccounts().first()) {
        accountAttributeRepository
            .getByAccount(account.id)
            .first()
            .firstOrNull { it.attributeType.id == BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID }
            ?.let { attribute ->
                index[attribute.value] = account.id
            }
    }
    return index
}

private suspend fun loadPersonalCounterpartyKeyIndex(
    accountRepository: AccountRepository,
    accountAttributeRepository: AccountAttributeRepository,
): Map<String, AccountId> {
    val index = mutableMapOf<String, AccountId>()
    for (account in accountRepository.getAllAccounts().first()) {
        val attributes = accountAttributeRepository.getByAccount(account.id).first()
        val sortCode = attributes.firstOrNull { it.attributeType.id == ACCOUNT_SORT_CODE_ATTR_TYPE_ID }?.value
        val accountNumber = attributes.firstOrNull { it.attributeType.id == ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID }?.value
        if (!sortCode.isNullOrBlank() && !accountNumber.isNullOrBlank()) {
            index["${account.name.lowercase()}|$sortCode|$accountNumber"] = account.id
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

private suspend fun flushPendingPersonalCounterpartyAttributes(
    accountCache: AccountCache,
    accountAttributeRepository: AccountAttributeRepository,
) {
    val pending = accountCache.drainPendingPersonalCounterpartyAttributes()
    for ((accountId, identity) in pending) {
        accountAttributeRepository.ensureCounterpartyPersonalAttributesInCreationMode(
            accountId = accountId,
            sortCode = identity.sortCode,
            accountNumber = identity.accountNumber,
        )
    }
}

private suspend fun flushPendingBuiltInTypeAttributes(
    accountCache: AccountCache,
    accountAttributeRepository: AccountAttributeRepository,
) {
    val pending = accountCache.drainPendingBuiltInTypeAttributes()
    val seen = mutableSetOf<String>()
    for ((accountId, builtInType) in pending) {
        if (seen.add(builtInType)) {
            runCatching {
                accountAttributeRepository.insertInCreationMode(
                    accountId = accountId,
                    attributeTypeId = BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID,
                    value = builtInType,
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

private data class CounterpartyBatchCreateRequest(
    val counterpartyId: String,
    val name: String,
    val apiSource: AccountApiSource,
)

private suspend fun importPeopleFromAccounts(
    accountsById: Map<String, ApiImportAccount>,
    accountCache: AccountCache,
    strategy: ApiImportStrategy,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
    accountAttributeRepository: AccountAttributeRepository,
    entitySource: EntitySource? = null,
    accountApiSourceByExternalId: Map<String, AccountApiSource> = emptyMap(),
    sessionId: ApiSessionId? = null,
    externalIdAttributeTypeId: AttributeTypeId? = null,
): Int {
    val peopleIndex = loadPeopleIndex(personRepository, personAttributeRepository, externalIdAttributeTypeId)
    var newPeopleCount = 0

    for (account in accountsById.values) {
        if (account.owners.isEmpty()) continue

        val accountId = accountCache.getOrCreateAccountId(account.id, account.displayName(strategy))
        val sortCode =
            account.rawJson?.stringOrNull(strategy.accountMappings.sortCodeField)?.takeIf { it.isNotBlank() }
                ?: account.owners.firstOrNull { !it.sortCode.isNullOrBlank() }?.sortCode
        val accountNumber =
            account.rawJson?.stringOrNull(strategy.accountMappings.accountNumberField)?.takeIf { it.isNotBlank() }
                ?: account.owners.firstOrNull { !it.accountNumber.isNullOrBlank() }?.accountNumber
        if (sortCode != null || accountNumber != null) {
            accountAttributeRepository.ensureCounterpartyPersonalAttributesInCreationMode(
                accountId = accountId,
                sortCode = sortCode,
                accountNumber = accountNumber,
            )
        }
        newPeopleCount +=
            importOwnersForAccount(
                owners = account.owners,
                accountId = accountId,
                peopleIndex = peopleIndex,
                personRepository = personRepository,
                personAccountOwnershipRepository = personAccountOwnershipRepository,
                personAttributeRepository = personAttributeRepository,
                externalIdAttributeTypeId = externalIdAttributeTypeId,
                entitySource = entitySource,
                sessionId = sessionId,
                requestId = accountApiSourceByExternalId[account.id]?.requestId,
                jsonPath = accountApiSourceByExternalId[account.id]?.jsonPath,
            ).newPeople
    }

    return newPeopleCount
}

private suspend fun importPeopleFromCounterparties(
    transactionResponses: List<ApiResponse>,
    sessionId: ApiSessionId,
    requestsById: Map<ApiRequestId, ApiRequest>,
    strategy: ApiImportStrategy,
    accountCache: AccountCache,
    counterpartyIdField: String?,
    nameMappings: CounterpartyNameMappings,
    accountAttributeRepository: AccountAttributeRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
    entitySource: EntitySource,
    externalIdAttributeTypeId: AttributeTypeId?,
): Int {
    val peopleIndex = loadPeopleIndex(personRepository, personAttributeRepository, externalIdAttributeTypeId)
    var newPeopleCount = 0

    for (response in transactionResponses) {
        val request = requestsById[response.requestId] ?: continue
        val personalCounterpartyItems = mutableListOf<Triple<ApiTransactionPageItem, ApiImportAccountOwner, PersonalCounterpartyIdentity>>()
        for (item in parseTransactionsWithPath(response.json, strategy)) {
            val isBuiltIn = item.rawJson?.resolveBuiltInCounterpartyType(strategy.builtInCounterpartyRules, item.amountSign) != null
            if (!item.isZeroAmount && !isBuiltIn) {
                val owner = item.personalCounterpartyOwner(strategy.peopleMappings)
                val personalCounterpartyIdentity = owner?.personalCounterpartyIdentity()
                if (owner != null && personalCounterpartyIdentity != null) {
                    personalCounterpartyItems.add(Triple(item, owner, personalCounterpartyIdentity))
                }
            }
        }
        for ((item, owner, personalCounterpartyIdentity) in personalCounterpartyItems) {
            val counterpartyAccountId =
                accountCache.getOrCreateCounterpartyAccountId(
                    counterpartyId = item.rawJson?.resolveCounterpartyIdentity(counterpartyIdField, strategy.peopleMappings),
                    builtInType = null,
                    name = nameMappings.counterpartyPrefix + personalCounterpartyIdentity.name,
                    dedupeKey = personalCounterpartyIdentity.dedupeKey,
                    apiSource = AccountApiSource(sessionId, request.id, JsonPath("${item.jsonPath.value}.counterparty")),
                    personalIdentity = personalCounterpartyIdentity,
                )
            accountAttributeRepository.ensureCounterpartyPersonalAttributes(
                accountId = counterpartyAccountId,
                sortCode = personalCounterpartyIdentity.sortCode,
                accountNumber = personalCounterpartyIdentity.accountNumber,
            )
            newPeopleCount +=
                importOwnersForAccount(
                    owners = listOf(owner),
                    accountId = counterpartyAccountId,
                    peopleIndex = peopleIndex,
                    personRepository = personRepository,
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    personAttributeRepository = personAttributeRepository,
                    externalIdAttributeTypeId = externalIdAttributeTypeId,
                    entitySource = entitySource,
                    sessionId = sessionId,
                    requestId = request.id,
                ).newPeople
        }
    }

    return newPeopleCount
}

private suspend fun importOwnersForAccount(
    owners: List<ApiImportAccountOwner>,
    accountId: AccountId,
    peopleIndex: MutablePeopleIndex,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
    externalIdAttributeTypeId: AttributeTypeId?,
    entitySource: EntitySource? = null,
    sessionId: ApiSessionId? = null,
    requestId: ApiRequestId? = null,
    jsonPath: JsonPath? = null,
): OwnerImportCounts {
    val existingOwnerPersonIds =
        personAccountOwnershipRepository
            .getOwnershipsByAccount(accountId)
            .first()
            .mapTo(mutableSetOf()) { it.personId }
    var newPeopleCount = 0
    var newOwnershipCount = 0

    for (owner in owners) {
        val (person, wasCreated) =
            resolveOrCreatePerson(
                owner = owner,
                peopleIndex = peopleIndex,
                personRepository = personRepository,
                personAttributeRepository = personAttributeRepository,
                externalIdAttributeTypeId = externalIdAttributeTypeId,
                entitySource = entitySource,
                sessionId = sessionId,
                requestId = requestId,
            ) ?: continue

        if (person.id !in existingOwnerPersonIds) {
            val ownershipId = personAccountOwnershipRepository.createOwnership(person.id, accountId)
            // Track the new link so duplicate owner entries in the same payload don't re-insert it.
            existingOwnerPersonIds += person.id
            newOwnershipCount++
            if (entitySource != null && sessionId != null && requestId != null) {
                entitySource.recordFromApi(
                    ApiEntitySourceRecord(
                        entityType = EntityType.PERSON_ACCOUNT_OWNERSHIP,
                        entityId = ownershipId,
                        revisionId = 1L,
                        sessionId = sessionId,
                        requestId = requestId,
                        jsonPath =
                            (
                                jsonPath
                                    ?: owner.jsonPath
                            ),
                    ),
                )
            }
        }
        if (wasCreated) {
            newPeopleCount++
        }
    }

    return OwnerImportCounts(newPeople = newPeopleCount, newOwnerships = newOwnershipCount)
}

/** Counts of newly created people and account ownerships produced by [importOwnersForAccount]. */
private data class OwnerImportCounts(
    val newPeople: Int,
    val newOwnerships: Int,
)

private suspend fun resolveOrCreatePerson(
    owner: ApiImportAccountOwner,
    peopleIndex: MutablePeopleIndex,
    personRepository: PersonRepository,
    personAttributeRepository: PersonAttributeRepository,
    externalIdAttributeTypeId: AttributeTypeId?,
    entitySource: EntitySource? = null,
    sessionId: ApiSessionId? = null,
    requestId: ApiRequestId? = null,
): Pair<Person, Boolean>? {
    val bankKey = owner.bankKey()
    val name = owner.preferredName?.trim().orEmpty()
    val rawExternalId = owner.userId.trim().ifBlank { null }
    val displayName = name.ifBlank { bankKey ?: rawExternalId ?: return null }
    // Only match/store the provider id when this provider declares an attribute for it.
    val externalId = if (externalIdAttributeTypeId != null) rawExternalId else null

    peopleIndex.find(externalId = externalId, bankKey = bankKey, fullName = displayName)?.let { existing ->
        // Matched (typically by name across providers): backfill this provider's external id if absent.
        if (externalIdAttributeTypeId != null && externalId != null && existing.externalId == null) {
            logger.info { "Backfilling external id for imported person '${existing.fullName}'" }
            runCatching {
                personAttributeRepository.insert(existing.person.id, externalIdAttributeTypeId, externalId)
            }
            peopleIndex.replace(existing.person, externalId)
            return existing.person to false
        }
        return existing.person to false
    }

    val nameParts = displayName.split(" ", limit = 2)
    val person =
        Person(
            id = PersonId(0L),
            firstName = nameParts[0],
            middleName = null,
            lastName = nameParts.getOrNull(1)?.ifBlank { null },
        )
    val newId = personRepository.createPerson(person)
    val createdPerson = person.copy(id = newId)
    if (externalIdAttributeTypeId != null && externalId != null) {
        personAttributeRepository.insertInCreationMode(createdPerson.id, externalIdAttributeTypeId, externalId)
    }
    if (entitySource != null && sessionId != null && requestId != null) {
        entitySource.recordFromApi(
            ApiEntitySourceRecord(
                entityType = EntityType.PERSON,
                entityId = createdPerson.id.id,
                revisionId = 1L,
                sessionId = sessionId,
                requestId = requestId,
                jsonPath = owner.jsonPath,
            ),
        )
    }
    peopleIndex.add(createdPerson, externalId, bankKey)
    return createdPerson to true
}

private suspend fun loadPeopleIndex(
    personRepository: PersonRepository,
    personAttributeRepository: PersonAttributeRepository,
    externalIdAttributeTypeId: AttributeTypeId?,
): MutablePeopleIndex {
    val people = personRepository.getAllPeople().first()
    // External ids are loaded for the current provider only, so cross-provider matches fall through
    // to name matching and the missing provider id is backfilled.
    val externalIdsByPersonId =
        people.associate { person ->
            person.id to
                externalIdAttributeTypeId?.let { typeId ->
                    personAttributeRepository
                        .getByPerson(person.id)
                        .first()
                        .firstOrNull { it.attributeType.id == typeId }
                        ?.value
                }
        }
    return MutablePeopleIndex.from(people, externalIdsByPersonId)
}

private data class IndexedPerson(
    val person: Person,
    val externalId: String?,
    val bankKey: String? = null,
) {
    val fullName: String
        get() = person.fullName

    val importNameKey: String?
        get() = person.importNameKey()
}

private class MutablePeopleIndex private constructor(
    private val peopleByExternalId: MutableMap<String, IndexedPerson>,
    private val peopleByBankKey: MutableMap<String, IndexedPerson>,
    private val peopleByImportNameKey: MutableMap<String, MutableList<IndexedPerson>>,
) {
    fun find(
        externalId: String?,
        bankKey: String?,
        fullName: String,
    ): IndexedPerson? {
        val nameKey = fullName.importNameKey()
        return externalId?.let { peopleByExternalId[it] }
            ?: bankKey?.let { peopleByBankKey[it] }
            // Name matching is a heuristic across providers, so only accept it when it's
            // unambiguous. If two distinct people share a first+last name, picking one arbitrarily
            // would merge them and backfill the wrong provider id, so treat it as no match and let
            // the caller create a fresh person instead.
            ?: nameKey?.let { key ->
                val candidates = peopleByImportNameKey[key]
                when {
                    candidates.isNullOrEmpty() -> null
                    candidates.size == 1 -> candidates.single()
                    else -> {
                        logger.warn { "Ambiguous name match for '$fullName' (${candidates.size} people); not merging" }
                        null
                    }
                }
            }
    }

    fun add(
        person: Person,
        externalId: String?,
        bankKey: String? = null,
    ) {
        val indexedPerson = IndexedPerson(person, externalId, bankKey)
        externalId?.let { peopleByExternalId[it] = indexedPerson }
        bankKey?.let { peopleByBankKey[it] = indexedPerson }
        person.importNameKey()?.let { peopleByImportNameKey.getOrPut(it) { mutableListOf() }.add(indexedPerson) }
    }

    fun replace(
        person: Person,
        externalId: String?,
    ) {
        val nameKey = person.importNameKey() ?: return
        val existing = peopleByImportNameKey[nameKey]?.firstOrNull { it.person.id == person.id }
        val indexedPerson = IndexedPerson(person, externalId, existing?.bankKey)
        externalId?.let { peopleByExternalId[it] = indexedPerson }
        existing?.bankKey?.let { peopleByBankKey[it] = indexedPerson }
        val people = peopleByImportNameKey[nameKey] ?: return
        val index = people.indexOfFirst { it.person.id == person.id }
        if (index >= 0) {
            people[index] = indexedPerson
        }
    }

    companion object {
        fun from(
            people: List<Person>,
            externalIdsByPersonId: Map<PersonId, String?>,
        ): MutablePeopleIndex {
            val indexedPeople = people.map { person -> IndexedPerson(person, externalIdsByPersonId[person.id]) }
            val byExternalId =
                mutableMapOf<String, IndexedPerson>().apply {
                    indexedPeople.forEach { person ->
                        person.externalId?.let { put(it, person) }
                    }
                }
            val byBankKey = mutableMapOf<String, IndexedPerson>()
            val byImportNameKey =
                indexedPeople
                    .mapNotNull { person -> person.importNameKey?.let { it to person } }
                    .groupByTo(mutableMapOf(), keySelector = { it.first }, valueTransform = { it.second })
                    .mapValuesTo(mutableMapOf()) { (_, group) -> group.toMutableList() }
            return MutablePeopleIndex(byExternalId, byBankKey, byImportNameKey)
        }
    }
}

private fun Person.importNameKey(): String? = fullName.importNameKey()

private fun String.importNameKey(): String? {
    val parts = trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return null
    return if (parts.size == 1) parts.single() else "${parts.first()} ${parts.last()}"
}

private suspend fun fetchResponse(
    url: String,
    token: String,
    apiClient: ApiClient,
    sca: ScaParams? = null,
): ApiHttpResponse {
    val response = apiClient.get(url = url, bearerToken = token, sca = sca)
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
    val source: AccountApiSource? = null,
    val sortCode: String? = null,
    val accountNumber: String? = null,
    val rawJson: JsonObject? = null,
)

private data class ApiImportAccountOwner(
    val userId: String,
    val preferredName: String?,
    val sortCode: String?,
    val accountNumber: String?,
    val jsonPath: JsonPath,
)

private data class PersonalCounterpartyIdentity(
    val name: String,
    val sortCode: String,
    val accountNumber: String,
) {
    val dedupeKey: String
        get() = "$sortCode|$accountNumber"
}

private fun ApiImportAccountOwner.personalCounterpartyIdentity(): PersonalCounterpartyIdentity? {
    val name = preferredName?.trim().orEmpty()
    val sortCode = sortCode?.trim().orEmpty()
    val accountNumber = accountNumber?.trim().orEmpty()
    if (name.isBlank() || sortCode.isBlank() || accountNumber.isBlank()) return null
    return PersonalCounterpartyIdentity(name = name, sortCode = sortCode, accountNumber = accountNumber)
}

private fun ApiImportAccountOwner.bankKey(): String? {
    val identity = personalCounterpartyIdentity() ?: return null
    return identity.dedupeKey
}

private suspend fun AccountAttributeRepository.ensureCounterpartyPersonalAttributes(
    accountId: AccountId,
    sortCode: String?,
    accountNumber: String?,
) {
    val currentAttributes = getByAccount(accountId).first()
    upsertAccountAttribute(currentAttributes, accountId, ACCOUNT_SORT_CODE_ATTR_TYPE_ID, sortCode)
    upsertAccountAttribute(currentAttributes, accountId, ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID, accountNumber)
}

private suspend fun AccountAttributeRepository.ensureCounterpartyPersonalAttributesInCreationMode(
    accountId: AccountId,
    sortCode: String?,
    accountNumber: String?,
) {
    val currentAttributes = getByAccount(accountId).first()
    upsertAccountAttributeInCreationMode(currentAttributes, accountId, ACCOUNT_SORT_CODE_ATTR_TYPE_ID, sortCode)
    upsertAccountAttributeInCreationMode(currentAttributes, accountId, ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID, accountNumber)
}

private suspend fun AccountAttributeRepository.upsertAccountAttribute(
    currentAttributes: List<AccountAttribute>,
    accountId: AccountId,
    attributeTypeId: AttributeTypeId,
    value: String?,
) {
    val existing = currentAttributes.firstOrNull { it.attributeType.id == attributeTypeId }
    when {
        value == null && existing != null -> delete(existing.id)
        value != null && existing == null -> insert(accountId, attributeTypeId, value)
        value != null && existing != null && existing.value != value -> updateValue(existing.id, value)
    }
}

private suspend fun AccountAttributeRepository.upsertAccountAttributeInCreationMode(
    currentAttributes: List<AccountAttribute>,
    accountId: AccountId,
    attributeTypeId: AttributeTypeId,
    value: String?,
) {
    val existing = currentAttributes.firstOrNull { it.attributeType.id == attributeTypeId }
    when {
        value == null && existing != null -> delete(existing.id)
        value != null && existing == null -> insertInCreationMode(accountId, attributeTypeId, value)
        value != null && existing != null && existing.value != value -> updateValue(existing.id, value)
    }
}

private fun ApiTransactionPageItem.personalCounterpartyOwner(peopleMappings: ApiPeopleMappings): ApiImportAccountOwner? {
    val counterparty = rawJson?.resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val beneficiaryAccountType = counterparty.stringOrNull(peopleMappings.beneficiaryAccountTypeField)
    if (beneficiaryAccountType?.equals(peopleMappings.personalBeneficiaryAccountTypeValue, ignoreCase = true) != true) return null

    val name = counterparty.stringOrNull(peopleMappings.counterpartyNameField)?.takeIf { it.isNotBlank() }
    val userId = counterparty.stringOrNull(peopleMappings.counterpartyUserIdField)?.takeIf { it.isNotBlank() }.orEmpty()
    val sortCode = counterparty.stringOrNull(peopleMappings.counterpartySortCodeField)?.takeIf { it.isNotBlank() }
    val accountNumber = counterparty.stringOrNull(peopleMappings.counterpartyAccountNumberField)?.takeIf { it.isNotBlank() }
    if (name == null && sortCode == null && accountNumber == null && userId.isBlank()) return null
    return ApiImportAccountOwner(
        userId = userId,
        preferredName = name,
        sortCode = sortCode,
        accountNumber = accountNumber,
        jsonPath = JsonPath("${jsonPath.value}.counterparty"),
    )
}

private fun JsonObject.personalCounterpartyIdentity(peopleMappings: ApiPeopleMappings): PersonalCounterpartyIdentity? {
    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val beneficiaryAccountType = counterparty.stringOrNull(peopleMappings.beneficiaryAccountTypeField)
    if (beneficiaryAccountType?.equals(peopleMappings.personalBeneficiaryAccountTypeValue, ignoreCase = true) != true) return null
    val sortCode = counterparty.stringOrNull(peopleMappings.counterpartySortCodeField)?.takeIf { it.isNotBlank() } ?: return null
    val accountNumber = counterparty.stringOrNull(peopleMappings.counterpartyAccountNumberField)?.takeIf { it.isNotBlank() } ?: return null
    val name = counterparty.stringOrNull(peopleMappings.counterpartyNameField)?.takeIf { it.isNotBlank() } ?: return null
    return PersonalCounterpartyIdentity(name = name, sortCode = sortCode, accountNumber = accountNumber)
}

private fun parseAccounts(
    json: String,
    strategy: ApiImportStrategy,
): List<ApiImportAccount> =
    try {
        responseItemsArray(json, strategy.accountsEndpoint.responseArrayKey)
            ?.mapIndexedNotNull { index, element ->
                val account = element as? JsonObject ?: return@mapIndexedNotNull null
                parseAccount(account, strategy, accountsItemJsonPath(strategy, index))
            }
            ?: emptyList()
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to parse accounts response for strategy '${strategy.name}'" }
        emptyList()
    }

private fun accountsItemJsonPath(
    strategy: ApiImportStrategy,
    index: Int,
): JsonPath = arrayItemJsonPath(strategy.accountsEndpoint.responseArrayKey, index)

private fun parseAccount(
    account: JsonObject,
    strategy: ApiImportStrategy,
    accountJsonPath: JsonPath,
): ApiImportAccount? {
    val mappings = strategy.accountMappings
    val id = account.resolveJsonPath(mappings.idField) ?: return null
    return ApiImportAccount(
        id = id,
        description = account.resolveJsonPath(mappings.descriptionField).orEmpty(),
        owners = parseAccountOwners(account, strategy, accountJsonPath),
        sortCode = account.stringOrNull(mappings.sortCodeField),
        accountNumber = account.stringOrNull(mappings.accountNumberField),
        rawJson = account,
    )
}

private fun parseAccountOwners(
    account: JsonObject,
    strategy: ApiImportStrategy,
    accountJsonPath: JsonPath,
): List<ApiImportAccountOwner> {
    val mappings = strategy.accountMappings
    val ownersField = mappings.ownersArrayField ?: return emptyList()
    return account[ownersField]
        ?.jsonArray
        ?.mapNotNull { element ->
            val owner = element as? JsonObject ?: return@mapNotNull null
            val userId = owner.stringOrNull(mappings.ownerUserIdField) ?: return@mapNotNull null
            val preferredName =
                mappings.ownerNameField
                    ?.let { owner.resolveJsonPath(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: owner.stringOrNull(mappings.ownerNameFallbackField)?.takeIf { it.isNotBlank() }
            val sortCode =
                owner.stringOrNull(mappings.sortCodeField)?.takeIf { it.isNotBlank() }
                    ?: account.stringOrNull(mappings.sortCodeField)?.takeIf { it.isNotBlank() }
            val accountNumber =
                owner.stringOrNull(mappings.accountNumberField)?.takeIf { it.isNotBlank() }
                    ?: account.stringOrNull(mappings.accountNumberField)?.takeIf { it.isNotBlank() }
            ApiImportAccountOwner(
                userId = userId,
                preferredName = preferredName,
                sortCode = sortCode,
                accountNumber = accountNumber,
                jsonPath = JsonPath("${accountJsonPath.value}.$ownersField"),
            )
        }
        ?: emptyList()
}

private fun parseAccountsWithPaths(
    json: String,
    strategy: ApiImportStrategy,
): List<Pair<ApiImportAccount, JsonPath>> =
    responseItemsArray(json, strategy.accountsEndpoint.responseArrayKey)
        ?.mapIndexedNotNull { index, element ->
            val account = element as? JsonObject ?: return@mapIndexedNotNull null
            val jsonPath = accountsItemJsonPath(strategy, index)
            parseAccount(account, strategy, jsonPath)?.let { it to jsonPath }
        }
        ?: emptyList()

/**
 * A parsed transaction page item. The amount is normalized into exactly one of [amountMinorUnits]
 * (integer minor-units format) or [amountDecimalMajor] (decimal major-units format, magnitude only),
 * with [amountSign] (-1/0/1) carrying the direction independently of how the amount was encoded.
 */
private data class ApiTransactionPageItem(
    val amountMinorUnits: Long?,
    val amountDecimalMajor: BigDecimal?,
    val amountSign: Int,
    val created: Instant,
    val currencyCode: String,
    val description: String,
    val jsonPath: JsonPath,
    val merchantName: String?,
    val counterpartyName: String?,
    val declineReason: String?,
    val rawJson: JsonObject? = null,
    val localAmountMinorUnits: Long? = null,
    val localCurrencyCode: String? = null,
) {
    val isZeroAmount: Boolean get() = amountSign == 0
    val isIncoming: Boolean get() = amountSign > 0
}

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

private data class ApiImportPageContext(
    val index: Int,
    val response: ApiHttpResponse,
    val requestId: ApiRequestId,
    val ownAccountId: AccountId,
)

private data class ApiImportPageResult(
    val importedCount: Int,
    val duplicateCount: Int,
    val errorCount: Int,
    val excludedCount: Int,
)

private data class PreparedApiTransaction(
    val pageIndex: Int,
    val itemIndex: Int,
    val responseId: ApiResponseId,
    val requestId: ApiRequestId,
    val ownAccountId: AccountId,
    val item: ApiTransactionPageItem,
    val transfer: Transfer,
    val attributes: List<NewAttribute>,
    val transactionApiId: String?,
    val uniqueKey: Map<String, String>?,
)

private data class ApiTransactionSource(
    val requestId: ApiRequestId,
    val jsonPath: JsonPath,
)

private data class ResponseTransactionImportRecord(
    val pageIndex: Int,
    val itemIndex: Int,
    val responseId: ApiResponseId,
    val jsonPath: JsonPath,
    val state: ApiResponseTransactionState,
    val transactionId: TransferId?,
    val errorMessage: String?,
    val excludedFromBalances: Boolean = false,
) {
    fun resolve(generatedIdsByTempId: Map<TransferId, TransferId>): ResponseTransactionImportRecord =
        copy(transactionId = transactionId?.let { generatedIdsByTempId[it] ?: it })

    fun toInsert(): ApiResponseTransactionInsert =
        ApiResponseTransactionInsert(
            responseId = responseId,
            jsonPath = jsonPath,
            state = state,
            transactionId = transactionId,
            errorMessage = errorMessage,
        )
}

private sealed class ApiTransactionPreparation {
    data class Prepared(
        val transaction: PreparedApiTransaction,
    ) : ApiTransactionPreparation()

    data class Failed(
        val record: ResponseTransactionImportRecord,
    ) : ApiTransactionPreparation()
}

private class OrderedApiImportSourceRecorder(
    private val entitySource: EntitySource,
    private val sessionId: ApiSessionId,
    private val sources: List<ApiTransactionSource>,
) : SourceRecorder {
    private var nextIndex = 0

    override fun insert(transfer: Transfer) {
        val source =
            sources.getOrNull(nextIndex++)
                ?: error("Missing API source metadata for imported transfer ${transfer.id}")
        entitySource
            .apiImportRecorder(
                sessionId = sessionId,
                requestId = source.requestId,
                jsonPath = source.jsonPath,
            ).insert(transfer)
    }
}

private suspend fun importTransactionPages(
    pageContexts: List<ApiImportPageContext>,
    setup: ImportSetup,
): List<ApiImportPageResult> {
    val pageItems =
        pageContexts.map { context ->
            context to parseTransactionsWithPath(context.response.body, setup.strategy)
        }
    val preparations =
        coroutineScope {
            pageItems
                .flatMap { (context, transactions) ->
                    transactions.mapIndexed { itemIndex, item ->
                        async {
                            prepareTransactionItem(
                                context = context,
                                itemIndex = itemIndex,
                                item = item,
                                setup = setup,
                            )
                        }
                    }
                }.awaitAll()
        }

    val responseRecords = mutableListOf<ResponseTransactionImportRecord>()
    val preparedTransactions = mutableListOf<PreparedApiTransaction>()
    preparations.forEach { preparation ->
        when (preparation) {
            is ApiTransactionPreparation.Prepared -> preparedTransactions += preparation.transaction
            is ApiTransactionPreparation.Failed -> responseRecords += preparation.record
        }
    }

    val transactionIdAttributeName =
        setup.customTxFields.entries
            .firstOrNull { it.value == "id" }
            ?.key
    val importsToCreate = mutableListOf<PreparedApiTransaction>()
    var nextTemporaryTransferId = -1L

    preparedTransactions
        .sortedWith(compareBy<PreparedApiTransaction> { it.pageIndex }.thenBy { it.itemIndex })
        .groupBy { it.ownAccountId }
        .forEach { (ownAccountId, accountTransactions) ->
            val existingTransfers =
                setup.transactionRepository
                    .getTransactionsByAccount(ownAccountId)
                    .first()
                    .toMutableList()
            val existingTransfersByApiId = existingTransfers.indexByApiTransactionId(transactionIdAttributeName)
            val existingByUniqueId = existingTransfers.indexByUniqueTransactionFields(setup.uniqueIdTxFields)

            accountTransactions.forEach { transaction ->
                val duplicateTransferId =
                    transaction.transactionApiId?.let { existingTransfersByApiId[it]?.id }
                        ?: transaction.uniqueKey?.let { existingByUniqueId[it] }
                        ?: existingTransfers.firstOrNull { it.matches(transaction.transfer) }?.id

                if (duplicateTransferId != null) {
                    responseRecords +=
                        transaction.responseRecord(
                            state = ApiResponseTransactionState.DUPLICATE,
                            transactionId = duplicateTransferId,
                        )
                    return@forEach
                }

                val tempTransferId = TransferId(nextTemporaryTransferId--)
                val importTransaction = transaction.copy(transfer = transaction.transfer.copy(id = tempTransferId))
                importsToCreate += importTransaction
                existingTransfers += importTransaction.transfer
                transaction.transactionApiId?.let { existingTransfersByApiId[it] = importTransaction.transfer }
                transaction.uniqueKey?.let { existingByUniqueId[it] = tempTransferId }
                responseRecords +=
                    importTransaction.responseRecord(
                        state = ApiResponseTransactionState.IMPORTED,
                        transactionId = tempTransferId,
                        excludedFromBalances = !transaction.item.declineReason.isNullOrBlank(),
                    )
            }
        }

    val orderedImports = importsToCreate.sortedWith(compareBy<PreparedApiTransaction> { it.pageIndex }.thenBy { it.itemIndex })
    val generatedIds =
        if (orderedImports.isEmpty()) {
            emptyList()
        } else {
            setup.transactionRepository.createTransfers(
                transfers = orderedImports.map { it.transfer },
                newAttributes =
                    orderedImports
                        .filter { it.attributes.isNotEmpty() }
                        .associate { it.transfer.id to it.attributes },
                batchSize = minOf(API_IMPORT_TRANSACTION_WRITE_BATCH_SIZE, orderedImports.size).coerceAtLeast(1),
                sourceRecorder =
                    OrderedApiImportSourceRecorder(
                        entitySource = setup.entitySource,
                        sessionId = setup.sessionId,
                        sources = orderedImports.map { ApiTransactionSource(it.requestId, it.item.jsonPath) },
                    ),
                onProgress = { created, total ->
                    val writeProgress = if (total <= 0) 0f else created.toFloat() / total.toFloat()
                    setup.onProgress(
                        ApiSessionImportProgress(
                            detail = "Writing imported transactions: $created/$total",
                            progress = 0.2f + (writeProgress * 0.6f),
                        ),
                    )
                },
            )
        }

    check(generatedIds.size == orderedImports.size) {
        "Expected ${orderedImports.size} generated transfer IDs, got ${generatedIds.size}"
    }

    val generatedIdsByTempId =
        orderedImports.zip(generatedIds).associate { (transaction, generatedId) ->
            transaction.transfer.id to generatedId
        }
    setup.apiSessionRepository.insertResponseTransactions(
        responseRecords
            .sortedWith(compareBy<ResponseTransactionImportRecord> { it.pageIndex }.thenBy { it.itemIndex })
            .map { it.resolve(generatedIdsByTempId).toInsert() },
    )

    val recordsByPage = responseRecords.groupBy { it.pageIndex }
    return pageContexts.map { context ->
        val pageRecords = recordsByPage[context.index].orEmpty()
        ApiImportPageResult(
            importedCount = pageRecords.count { it.state == ApiResponseTransactionState.IMPORTED },
            duplicateCount = pageRecords.count { it.state == ApiResponseTransactionState.DUPLICATE },
            errorCount = pageRecords.count { it.state == ApiResponseTransactionState.ERROR },
            excludedCount = pageRecords.count { it.excludedFromBalances },
        )
    }
}

private suspend fun prepareTransactionItem(
    context: ApiImportPageContext,
    itemIndex: Int,
    item: ApiTransactionPageItem,
    setup: ImportSetup,
): ApiTransactionPreparation {
    val responseId = ApiResponseId(context.response.responseId)
    val currency =
        setup.currencyCache.getCurrency(item.currencyCode)
            ?: return ApiTransactionPreparation.Failed(
                record =
                    item.errorRecord(
                        pageIndex = context.index,
                        itemIndex = itemIndex,
                        responseId = responseId,
                        message = "Currency not found: ${item.currencyCode}",
                    ),
            )

    return try {
        val prepared =
            prepareValidTransactionItem(
                context = context,
                itemIndex = itemIndex,
                item = item,
                currency = currency,
                responseId = responseId,
                setup = setup,
            )
        ApiTransactionPreparation.Prepared(prepared)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        logger.error(expected) { "Error importing API transaction: ${expected.message}" }
        ApiTransactionPreparation.Failed(
            record =
                item.errorRecord(
                    pageIndex = context.index,
                    itemIndex = itemIndex,
                    responseId = responseId,
                    message = expected.message ?: "Unknown import error",
                ),
        )
    }
}

private suspend fun prepareValidTransactionItem(
    context: ApiImportPageContext,
    itemIndex: Int,
    item: ApiTransactionPageItem,
    currency: Currency,
    responseId: ApiResponseId,
    setup: ImportSetup,
): PreparedApiTransaction {
    val counterpartyApiSource =
        AccountApiSource(
            sessionId = setup.sessionId,
            requestId = context.requestId,
            jsonPath = JsonPath("${item.jsonPath.value}.counterparty"),
        )
    val counterpartyAccountId =
        if (item.isZeroAmount) {
            setup.accountCache.getOrCreateAccountId(
                name = setup.nameMappings.counterpartyPrefix + "Void",
                transactionApiSource = counterpartyApiSource,
            )
        } else {
            val builtInCounterpartyType =
                item.rawJson?.resolveBuiltInCounterpartyType(setup.strategy.builtInCounterpartyRules, item.amountSign)
            val counterpartyId = item.rawJson?.resolveCounterpartyIdentity(setup.counterpartyIdField, setup.strategy.peopleMappings)
            val personalCounterpartyIdentity = item.rawJson?.personalCounterpartyIdentity(setup.strategy.peopleMappings)
            setup.accountCache.getOrCreateCounterpartyAccountId(
                counterpartyId = counterpartyId,
                dedupeKey = personalCounterpartyIdentity?.dedupeKey,
                builtInType = builtInCounterpartyType,
                name =
                    setup.nameMappings.counterpartyPrefix +
                        (builtInCounterpartyType ?: item.counterpartyName(setup.nameMappings)),
                apiSource = counterpartyApiSource,
                personalIdentity = personalCounterpartyIdentity,
            )
        }
    val transfer = item.toTransfer(context.ownAccountId, counterpartyAccountId, currency)
    val transactionApiId = item.rawJson?.resolveJsonPath(setup.strategy.transactionMappings.idField)?.takeIf { it.isNotBlank() }
    val uniqueKey =
        if (setup.uniqueIdTxFields.isNotEmpty() && item.rawJson != null) {
            setup.uniqueIdTxFields.associateWith { fieldName ->
                setup.customTxFields[fieldName]?.let { item.rawJson.resolveJsonPath(it) } ?: ""
            }
        } else {
            null
        }

    return PreparedApiTransaction(
        pageIndex = context.index,
        itemIndex = itemIndex,
        responseId = responseId,
        requestId = context.requestId,
        ownAccountId = context.ownAccountId,
        item = item,
        transfer = transfer,
        attributes =
            buildApiTransferAttributes(
                item = item,
                customTxFields = setup.customTxFields,
                attributeTypeCache = setup.attributeTypeCache,
            ),
        transactionApiId = transactionApiId,
        uniqueKey = uniqueKey,
    )
}

private suspend fun buildApiTransferAttributes(
    item: ApiTransactionPageItem,
    customTxFields: Map<String, String>,
    attributeTypeCache: AttributeTypeCache,
): List<NewAttribute> =
    buildList {
        if (!item.declineReason.isNullOrBlank()) {
            add(NewAttribute(typeId = AttributeTypeId(-1), value = "declined: ${item.declineReason}"))
        }
        if (customTxFields.isNotEmpty() && item.rawJson != null) {
            for ((fieldName, jsonPath) in customTxFields) {
                if (jsonPath != "local_amount" && jsonPath != "local_currency") {
                    val value = item.rawJson.resolveJsonPath(jsonPath) ?: continue
                    add(NewAttribute(typeId = attributeTypeCache.getOrCreate(fieldName), value = value))
                }
            }
        }
        val localAmountAttributeName = strategyAttributeNameForJsonPath(customTxFields, strategyPath = "local_amount")
        if (item.localAmountMinorUnits != null && localAmountAttributeName != null) {
            add(
                NewAttribute(
                    typeId = attributeTypeCache.getOrCreate(localAmountAttributeName),
                    value = item.localAmountMinorUnits.toString(),
                ),
            )
        }
        val localCurrencyAttributeName = strategyAttributeNameForJsonPath(customTxFields, strategyPath = "local_currency")
        if (!item.localCurrencyCode.isNullOrBlank() && localCurrencyAttributeName != null) {
            add(NewAttribute(typeId = attributeTypeCache.getOrCreate(localCurrencyAttributeName), value = item.localCurrencyCode))
        }
    }

private fun List<Transfer>.indexByApiTransactionId(transactionIdAttributeName: String?): MutableMap<String, Transfer> =
    mapNotNull { transfer ->
        transactionIdAttributeName
            ?.let { attributeName ->
                transfer.attributes.firstOrNull { it.attributeType.name == attributeName }?.value
            }?.let { apiId ->
                apiId to transfer
            }
    }.toMap().toMutableMap()

private fun List<Transfer>.indexByUniqueTransactionFields(uniqueIdTxFields: Set<String>): MutableMap<Map<String, String>, TransferId> =
    if (uniqueIdTxFields.isEmpty()) {
        mutableMapOf()
    } else {
        mapNotNull { transfer ->
            val key =
                uniqueIdTxFields.associateWith { fieldName ->
                    transfer.attributes.firstOrNull { it.attributeType.name == fieldName }?.value ?: return@mapNotNull null
                }
            key to transfer.id
        }.toMap().toMutableMap()
    }

private fun PreparedApiTransaction.responseRecord(
    state: ApiResponseTransactionState,
    transactionId: TransferId,
    excludedFromBalances: Boolean = false,
): ResponseTransactionImportRecord =
    ResponseTransactionImportRecord(
        pageIndex = pageIndex,
        itemIndex = itemIndex,
        responseId = responseId,
        jsonPath = item.jsonPath,
        state = state,
        transactionId = transactionId,
        errorMessage = null,
        excludedFromBalances = excludedFromBalances,
    )

private fun ApiTransactionPageItem.errorRecord(
    pageIndex: Int,
    itemIndex: Int,
    responseId: ApiResponseId,
    message: String,
): ResponseTransactionImportRecord =
    ResponseTransactionImportRecord(
        pageIndex = pageIndex,
        itemIndex = itemIndex,
        responseId = responseId,
        jsonPath = jsonPath,
        state = ApiResponseTransactionState.ERROR,
        transactionId = null,
        errorMessage = message,
    )

private fun parseTransactionsWithPath(
    json: String,
    strategy: ApiImportStrategy,
): List<ApiTransactionPageItem> =
    try {
        val mappings = strategy.transactionMappings
        responseItemsArray(json, strategy.transactionsEndpoint.responseArrayKey)
            ?.mapIndexedNotNull { index, element ->
                val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                val created = obj.resolveJsonPath(mappings.timestampField)?.let { Instant.parse(it) }
                val amount = parseAmount(obj, mappings)
                val currency = obj.resolveJsonPath(mappings.currencyField)
                val declineReason = mappings.declineReasonField?.let { obj.resolveJsonPath(it) }
                val localAmount = mappings.localAmountField?.let { obj.resolveJsonPath(it) }?.toLongOrNull()
                val localCurrency = mappings.localCurrencyField?.let { obj.resolveJsonPath(it) }
                if (created != null && amount != null && currency != null) {
                    ApiTransactionPageItem(
                        amountMinorUnits = amount.minorUnits,
                        amountDecimalMajor = amount.decimalMajor,
                        amountSign = amount.sign,
                        created = created,
                        currencyCode = currency.uppercase(),
                        description = obj.resolveJsonPath(mappings.descriptionField).orEmpty(),
                        jsonPath = arrayItemJsonPath(strategy.transactionsEndpoint.responseArrayKey, index),
                        merchantName = mappings.merchantNameField?.let { obj.resolveJsonPath(it) },
                        counterpartyName = mappings.counterpartyNameField?.let { obj.resolveJsonPath(it) },
                        declineReason = declineReason,
                        rawJson = obj,
                        localAmountMinorUnits = localAmount,
                        localCurrencyCode = localCurrency?.uppercase(),
                    )
                } else {
                    logger.error {
                        "Skipping API transaction at index $index: missing required fields " +
                            "(created=$created, amount=$amount, currency=$currency)"
                    }
                    null
                }
            }
            ?: emptyList()
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to parse transactions response for strategy '${strategy.name}'" }
        emptyList()
    }

/** Normalized amount: exactly one of [minorUnits]/[decimalMajor] is set; [sign] is the direction. */
private data class ParsedAmount(
    val minorUnits: Long?,
    val decimalMajor: BigDecimal?,
    val sign: Int,
)

/** Parses a transaction amount per the strategy's amount format and sign source. */
private fun parseAmount(
    obj: JsonObject,
    mappings: ApiTransactionMappings,
): ParsedAmount? {
    val raw = obj.resolveJsonPath(mappings.amountField) ?: return null
    return when (mappings.amountFormat) {
        ApiAmountFormat.MINOR_UNITS_INTEGER -> {
            val value = raw.toLongOrNull() ?: return null
            val magnitudeSign = value.compareTo(0L).coerceIn(-1, 1)
            val sign = resolveSign(obj, mappings, magnitudeSign) ?: return null
            ParsedAmount(minorUnits = value, decimalMajor = null, sign = sign)
        }
        ApiAmountFormat.DECIMAL_MAJOR_UNITS -> {
            val decimal = runCatching { BigDecimal(raw) }.getOrNull() ?: return null
            val magnitudeSign = decimal.compareTo(BigDecimal.ZERO).coerceIn(-1, 1)
            val sign = resolveSign(obj, mappings, magnitudeSign) ?: return null
            ParsedAmount(minorUnits = null, decimalMajor = decimal.abs(), sign = sign)
        }
    }
}

/**
 * Resolves the transaction sign (-1/0/1) from either the amount magnitude or a dedicated field.
 * Returns null when [ApiSignSource.FIELD] is configured but the sign field is absent: a missing
 * direction indicator is unexpected data, so the caller skips the item rather than silently
 * defaulting it to a debit. A present value that is simply not in [creditValues] is a debit.
 */
private fun resolveSign(
    obj: JsonObject,
    mappings: ApiTransactionMappings,
    magnitudeSign: Int,
): Int? =
    when (mappings.signSource) {
        ApiSignSource.EMBEDDED -> magnitudeSign
        ApiSignSource.FIELD -> {
            if (magnitudeSign == 0) {
                0
            } else {
                when (val signValue = mappings.signField?.let { obj.resolveJsonPath(it) }) {
                    null -> {
                        logger.warn { "Sign field '${mappings.signField}' missing from transaction; skipping item" }
                        null
                    }
                    in mappings.creditValues -> 1
                    else -> -1
                }
            }
        }
    }

/** JSON path for the [index]-th item of a response array, accounting for a blank (root-array) key. */
private fun arrayItemJsonPath(
    responseArrayKey: String,
    index: Int,
): JsonPath = if (responseArrayKey.isBlank()) JsonPath("$[$index]") else JsonPath("$.$responseArrayKey[$index]")

/**
 * Extracts the items array from a response body; a blank [responseArrayKey] means the body is the
 * array. A bare JSON object body (e.g. a single-resource endpoint like Starling's account holder) is
 * wrapped as a one-element array so single-object responses parse like any other.
 */
private fun responseItemsArray(
    json: String,
    responseArrayKey: String,
): JsonArray? =
    try {
        val root = Json.parseToJsonElement(json)
        if (responseArrayKey.isBlank()) {
            when (root) {
                is JsonArray -> root
                is JsonObject -> JsonArray(listOf(root))
                else -> null
            }
        } else {
            (root as? JsonObject)?.get(responseArrayKey)?.jsonArray
        }
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to parse API response array (key='$responseArrayKey')" }
        null
    }

private const val MILLIS_PER_DAY = 86_400_000L

// '}' is matched via a character class because Android's regex engine rejects a bare '}'
// while the JVM flags an escaped '\}' as redundant.
private val PATH_TEMPLATE_REGEX = Regex("\\{([^}]+)[}]")

/**
 * Resolves [ApiQueryParam.dynamicSource] / path-template expressions against the data available
 * while building a request URL. Supported expressions are documented on [ApiQueryParam.dynamicSource].
 */
private class ImportUrlContext(
    private val account: ApiImportAccount? = null,
    private val ancestorItems: List<JsonObject>? = null,
    private val ancestorVars: Map<String, String> = emptyMap(),
    private val windowStart: String? = null,
    private val windowEnd: String? = null,
) {
    fun resolve(expr: String): String? =
        when {
            expr == "account.id" -> account?.id
            expr.startsWith("account.") -> account?.rawJson?.resolveJsonPath(expr.removePrefix("account."))
            expr == "window.start" -> windowStart
            expr == "window.end" -> windowEnd
            expr == "parent.id" ->
                ancestorVars.entries.lastOrNull { it.key.endsWith(".id") }?.value
                    ?: ancestorItems?.lastOrNull()?.resolveJsonPath("id")
            expr.startsWith("ancestor[") -> resolveAncestor(expr)
            else -> ancestorVars[expr]
        }

    private fun resolveAncestor(expr: String): String? {
        ancestorVars[expr]?.let { return it }
        val index = expr.substringAfter('[').substringBefore(']').toIntOrNull() ?: return null
        val field = expr.substringAfter("].", missingDelimiterValue = "id")
        return ancestorItems?.getOrNull(index)?.resolveJsonPath(field)
    }
}

/** Substitutes `{expression}` placeholders in [path] using [ctx]; unresolved placeholders are kept. */
private fun applyPathTemplate(
    path: String,
    ctx: ImportUrlContext,
): String = PATH_TEMPLATE_REGEX.replace(path) { match -> ctx.resolve(match.groupValues[1]) ?: match.value }

/**
 * Inverse of [applyPathTemplate]: matches a concrete [actualPath] against a [template] and returns
 * each placeholder expression mapped to its captured value, or null when the path does not match.
 */
private fun extractPathVariables(
    template: String,
    actualPath: String,
): Map<String, String>? {
    val exprs = mutableListOf<String>()
    val pattern =
        buildString {
            append('^')
            var last = 0
            for (match in PATH_TEMPLATE_REGEX.findAll(template)) {
                append(Regex.escape(template.substring(last, match.range.first)))
                append("([^/]+)")
                exprs += match.groupValues[1]
                last = match.range.last + 1
            }
            append(Regex.escape(template.substring(last)))
            append('$')
        }
    val match = Regex(pattern).matchEntire(actualPath) ?: return null
    return exprs.mapIndexed { index, expr -> expr to match.groupValues[index + 1] }.toMap()
}

/** Appends static and dynamic [queryParams] to this URL builder, resolving each against [ctx]. */
private fun URLBuilder.appendQueryParams(
    queryParams: List<ApiQueryParam>,
    ctx: ImportUrlContext,
) {
    for (queryParam in queryParams) {
        val value = queryParam.value ?: queryParam.dynamicSource?.let { ctx.resolve(it) }
        if (value != null) parameters.append(queryParam.name, value)
    }
}

/** Builds a fully-templated URL for [endpoint] (path placeholders + query params) using [ctx]. */
private fun buildEndpointRequestUrl(
    baseUrl: String,
    endpoint: ApiEndpointConfig,
    ctx: ImportUrlContext,
): String =
    URLBuilder(buildEndpointUrl(baseUrl, applyPathTemplate(endpoint.path, ctx)))
        .apply { appendQueryParams(endpoint.queryParams, ctx) }
        .buildString()

/** Builds the accounts-endpoint URL for the given ancestor [ctx]. */
private fun buildAccountsUrl(
    strategy: ApiImportStrategy,
    ctx: ImportUrlContext,
): String = buildEndpointRequestUrl(strategy.baseUrl, strategy.accountsEndpoint, ctx)

/** Builds a before-cursor transaction page URL (Monzo-style). */
private fun buildCursorTransactionUrl(
    strategy: ApiImportStrategy,
    ctx: ImportUrlContext,
    before: Instant?,
): String =
    URLBuilder(buildEndpointUrl(strategy.baseUrl, applyPathTemplate(strategy.transactionsEndpoint.path, ctx)))
        .apply {
            appendQueryParams(strategy.transactionsEndpoint.queryParams, ctx)
            strategy.transactionsEndpoint.pagination?.let { pagination ->
                parameters.append(pagination.limitParam, pagination.limitValue.toString())
                if (before != null) parameters.append(pagination.cursorParam, before.toString())
            }
        }.buildString()

/** Builds a date-window transaction URL bounded by the window carried in [ctx]. */
private fun buildDateWindowTransactionUrl(
    strategy: ApiImportStrategy,
    pagination: ApiPaginationConfig,
    ctx: ImportUrlContext,
    window: ApiDateWindow,
): String =
    URLBuilder(buildEndpointUrl(strategy.baseUrl, applyPathTemplate(strategy.transactionsEndpoint.path, ctx)))
        .apply {
            appendQueryParams(strategy.transactionsEndpoint.queryParams, ctx)
            appendQueryParams(pagination.extraParams, ctx)
            parameters.append(pagination.startParam, window.start.toString())
            parameters.append(pagination.endParam, window.end.toString())
        }.buildString()

private data class ApiDateWindow(
    val start: Instant,
    val end: Instant,
)

/**
 * Produces the date windows to fetch, anchored to fixed epoch boundaries so earlier windows yield
 * stable, cacheable URLs across re-imports; only the final window (ending [now]) shifts.
 */
private fun dateWindows(
    pagination: ApiPaginationConfig,
    now: Instant,
): List<ApiDateWindow> {
    val windowMillis = pagination.windowDays.toLong() * MILLIS_PER_DAY
    if (windowMillis <= 0L) return emptyList()
    val nowMillis = now.toEpochMilliseconds()
    val rawStart = nowMillis - pagination.lookbackDays.toLong() * MILLIS_PER_DAY
    var start = (rawStart / windowMillis) * windowMillis
    val windows = mutableListOf<ApiDateWindow>()
    while (start < nowMillis) {
        val end = minOf(start + windowMillis, nowMillis)
        windows += ApiDateWindow(Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end))
        start += windowMillis
    }
    return windows
}

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

private fun ApiRequest.encodedPath(): String = runCatching { URLBuilder(url).encodedPath }.getOrNull().orEmpty()

/**
 * Recovers the current account's external id from a stored transaction request. Uses the
 * `account.id` query parameter when the strategy injects the id that way (Monzo); otherwise matches
 * the request path against the transactions-endpoint path template and captures the `{account.id}`
 * segment (Wise). Returns null for requests that are not transaction requests.
 */
private fun ApiRequest.resolveAccountExternalId(strategy: ApiImportStrategy): String? {
    val accountIdParamName =
        strategy.transactionsEndpoint.queryParams
            .firstOrNull { it.dynamicSource == "account.id" }
            ?.name
    if (accountIdParamName != null) {
        return runCatching { URLBuilder(url).parameters[accountIdParamName] }.getOrNull()
    }
    return extractPathVariables(strategy.transactionsEndpoint.path, encodedPath())?.get("account.id")
}

/** True when this request targets the accounts endpoint (path matches the accounts path template). */
private fun ApiRequest.isAccountsRequest(strategy: ApiImportStrategy): Boolean =
    extractPathVariables(strategy.accountsEndpoint.path, encodedPath()) != null &&
        resolveAccountExternalId(strategy) == null

/** Ancestor placeholder values substituted into this accounts request's path (e.g. profile id). */
private fun ApiRequest.ancestorVars(strategy: ApiImportStrategy): Map<String, String> =
    extractPathVariables(strategy.accountsEndpoint.path, encodedPath()).orEmpty()

private data class AccountApiSource(
    val sessionId: ApiSessionId,
    val requestId: ApiRequestId,
    val jsonPath: JsonPath,
)

private class AccountCache(
    private val accountRepository: AccountRepository,
    private val accountAttributeRepository: AccountAttributeRepository,
    private val entitySource: EntitySource,
    val accountApiSourceByExternalId: Map<String, AccountApiSource>,
    private val sourceAccountExternalIdIndex: MutableMap<String, AccountId>,
    // Pre-built by the caller in the serial section before concurrent import starts
    private val counterpartyIdIndex: MutableMap<String, AccountId>,
    private val personalCounterpartyKeyIndex: MutableMap<String, AccountId>,
    private val builtInTypeIndex: MutableMap<String, AccountId>,
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
    private val pendingPersonalCounterpartyAttributes: MutableList<Pair<AccountId, PersonalCounterpartyIdentity>> = mutableListOf()
    private val pendingBuiltInTypeAttributes: MutableList<Pair<AccountId, String>> = mutableListOf()

    suspend fun drainPendingCounterpartyAttributes(): List<Pair<AccountId, String>> =
        mutex.withLock {
            pendingCounterpartyAttributes.toList().also { pendingCounterpartyAttributes.clear() }
        }

    suspend fun drainPendingPersonalCounterpartyAttributes(): List<Pair<AccountId, PersonalCounterpartyIdentity>> =
        mutex.withLock {
            pendingPersonalCounterpartyAttributes.toList().also { pendingPersonalCounterpartyAttributes.clear() }
        }

    suspend fun drainPendingBuiltInTypeAttributes(): List<Pair<AccountId, String>> =
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
            if (externalId != null) {
                sourceAccountExternalIdIndex[externalId]?.let { return@withLock it }
            }
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
        dedupeKey: String? = null,
        builtInType: String?,
        name: String,
        apiSource: AccountApiSource,
        personalIdentity: PersonalCounterpartyIdentity? = null,
    ): AccountId =
        mutex.withLock {
            if (builtInType != null) {
                // Built-in type transactions all share one account; counterpartyId is ignored so
                // that ATM withdrawals from different locations always consolidate into one account.
                builtInTypeIndex[builtInType]?.let { return@withLock it }
                findBuiltInTypeAccountId(builtInType)?.let {
                    builtInTypeIndex[builtInType] = it
                    return@withLock it
                }
                val normalizedName = name.ifBlank { "Unknown" }
                val accountId = loadAccounts()[normalizedName]?.id ?: createAccount(null, normalizedName, apiSource)
                if (accountAttributeRepository.getByAccount(accountId).first().none {
                        it.attributeType.id ==
                            ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID
                    }
                ) {
                    accountAttributeRepository.insertInCreationMode(
                        accountId,
                        ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID,
                        counterpartyId ?: return@withLock accountId,
                    )
                }
                builtInTypeIndex[builtInType] = accountId
                pendingBuiltInTypeAttributes.add(accountId to builtInType)
                return@withLock accountId
            }
            if (counterpartyId != null) {
                counterpartyIdIndex[counterpartyId]?.let { return@withLock it }
            }
            if (dedupeKey != null) {
                personalCounterpartyKeyIndex[dedupeKey]?.let { return@withLock it }
            }
            val normalizedName = name.ifBlank { "Unknown" }
            val accountId = loadAccounts()[normalizedName]?.id ?: createAccount(null, normalizedName, apiSource)
            if (counterpartyId != null) {
                counterpartyIdIndex[counterpartyId] = accountId
                pendingCounterpartyAttributes.add(accountId to counterpartyId)
            }
            if (dedupeKey != null) {
                personalCounterpartyKeyIndex[dedupeKey] = accountId
                if (personalIdentity != null) {
                    pendingPersonalCounterpartyAttributes.add(accountId to personalIdentity)
                }
            }
            accountId
        }

    suspend fun precreateCounterpartyAccountsBatch(requests: List<CounterpartyBatchCreateRequest>) {
        if (requests.isEmpty()) return

        var createdCount = 0
        mutex.withLock {
            val accountMap = loadAccounts().toMutableMap()
            val toCreate = mutableListOf<CounterpartyBatchCreateRequest>()

            for (request in requests) {
                val existingByCounterpartyId = counterpartyIdIndex[request.counterpartyId]
                if (existingByCounterpartyId != null) {
                    continue
                }
                val existingByName = accountMap[request.name]
                val shouldCreate =
                    if (existingByName != null) {
                        val existingExternalIdForAccount =
                            counterpartyIdIndex.entries.firstOrNull { it.value == existingByName.id }?.key
                        if (existingExternalIdForAccount == request.counterpartyId) {
                            counterpartyIdIndex[request.counterpartyId] = existingByName.id
                            false
                        } else {
                            true
                        }
                    } else {
                        true
                    }
                if (shouldCreate) {
                    toCreate += request
                }
            }

            if (toCreate.isEmpty()) return@withLock

            val now = Clock.System.now()
            val accountsToCreate =
                toCreate.map { request ->
                    Account(id = AccountId(0L), name = request.name.ifBlank { "Unknown" }, openingDate = now)
                }
            val createdIds = accountRepository.createAccountsBatch(accountsToCreate)
            val counterpartyAttributeWrites = mutableListOf<AccountAttributeCreateInput>()
            val counterpartySourceWrites = mutableListOf<ApiEntitySourceRecord>()

            toCreate.zip(createdIds).forEach { (request, accountId) ->
                counterpartyIdIndex[request.counterpartyId] = accountId
                val createdAccount = Account(id = accountId, name = request.name.ifBlank { "Unknown" }, openingDate = now)
                accountMap[createdAccount.name] = createdAccount
                counterpartyAttributeWrites +=
                    AccountAttributeCreateInput(
                        accountId = accountId,
                        attributeTypeId = ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID,
                        value = request.counterpartyId,
                    )
                counterpartySourceWrites +=
                    ApiEntitySourceRecord(
                        entityType = EntityType.ACCOUNT,
                        entityId = accountId.id,
                        revisionId = 1L,
                        sessionId = request.apiSource.sessionId,
                        requestId = request.apiSource.requestId,
                        jsonPath = request.apiSource.jsonPath,
                    )
            }
            accountAttributeRepository.insertInCreationModeBatch(counterpartyAttributeWrites)
            entitySource.recordFromApiBatch(counterpartySourceWrites)
            accountsByName = accountMap
            createdCount = toCreate.size
        }

        repeat(createdCount) { onAccountCreated(false) }
    }

    private suspend fun findBuiltInTypeAccountId(builtInType: String): AccountId? {
        val accounts = accountRepository.getAllAccounts().first()
        for (account in accounts) {
            val attributes = accountAttributeRepository.getByAccount(account.id).first()
            if (
                attributes.any {
                    it.attributeType.id == BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID &&
                        it.value == builtInType
                }
            ) {
                return account.id
            }
        }
        return null
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
        if (externalId != null) {
            accountAttributeRepository.insertInCreationMode(newId, ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID, externalId)
            sourceAccountExternalIdIndex[externalId] = newId
        }
        val resolvedSource = externalId?.let { accountApiSourceByExternalId[it] } ?: apiSource
        if (resolvedSource != null) {
            entitySource.recordFromApi(
                ApiEntitySourceRecord(
                    entityType = EntityType.ACCOUNT,
                    entityId = newId.id,
                    revisionId = 1L,
                    sessionId = resolvedSource.sessionId,
                    requestId = resolvedSource.requestId,
                    jsonPath = resolvedSource.jsonPath,
                ),
            )
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
    val money =
        when {
            amountMinorUnits != null -> Money(amountMinorUnits.absoluteValue, currency)
            amountDecimalMajor != null -> Money.fromDisplayValue(amountDecimalMajor, currency)
            else -> Money(0L, currency)
        }
    val isIncoming = this.isIncoming
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
        amount == other.amount &&
        (
            (sourceAccountId == other.sourceAccountId && targetAccountId == other.targetAccountId) ||
                (sourceAccountId == other.targetAccountId && targetAccountId == other.sourceAccountId)
        )

private fun strategyAttributeNameForJsonPath(
    customTxFields: Map<String, String>,
    strategyPath: String,
): String? = customTxFields.entries.firstOrNull { it.value == strategyPath }?.key

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** Resolves a dot-notation path (e.g. "merchant.name") against this JSON object. */
private fun JsonObject.resolveJsonPath(dotPath: String): String? {
    var current: JsonElement = this
    for (part in dotPath.split(".")) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return (current as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.resolveCounterpartyIdentity(
    counterpartyIdField: String?,
    peopleMappings: ApiPeopleMappings,
): String? {
    if (counterpartyIdField == null) return null

    resolveJsonPath(counterpartyIdField)?.takeIf { it.isNotBlank() }?.let { return it }

    if (counterpartyIdField.endsWith(".id")) {
        val counterpartyIdSuffix = "." + peopleMappings.counterpartyUserIdField.substringAfterLast(".")
        val accountIdField =
            if (counterpartyIdField.endsWith(counterpartyIdSuffix)) {
                counterpartyIdField.removeSuffix(counterpartyIdSuffix) + peopleMappings.fallbackCounterpartyAccountIdSuffix
            } else {
                counterpartyIdField + peopleMappings.fallbackCounterpartyAccountIdSuffix
            }
        resolveJsonPath(accountIdField)?.takeIf { it.isNotBlank() }?.let { return it }
    }

    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val sortCode = counterparty.stringOrNull(peopleMappings.counterpartySortCodeField)?.takeIf { it.isNotBlank() }
    val accountNumber = counterparty.stringOrNull(peopleMappings.counterpartyAccountNumberField)?.takeIf { it.isNotBlank() }
    if (sortCode != null && accountNumber != null) {
        return "bank:$sortCode:$accountNumber"
    }

    val serviceUserNumber = counterparty.stringOrNull(peopleMappings.counterpartyServiceUserNumberField)?.takeIf { it.isNotBlank() }
    if (serviceUserNumber != null) {
        return "service_user:$serviceUserNumber"
    }

    val userId = counterparty.stringOrNull(peopleMappings.counterpartyUserIdField)?.takeIf { it.isNotBlank() }
    if (userId != null) {
        return "user:$userId"
    }

    return null
}

private fun JsonObject.resolveJsonObjectPath(dotPath: String): JsonObject? {
    var current: JsonElement = this
    for (part in dotPath.split(".")) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return current as? JsonObject
}

/**
 * Evaluates the strategy's declarative [rules] against this transaction's raw JSON, returning the
 * name of the first matching built-in counterparty type (e.g. "ATM"), or null when none match.
 * [amountSign] (-1/0/1) drives each rule's sign gate.
 */
private fun JsonObject.resolveBuiltInCounterpartyType(
    rules: List<BuiltInCounterpartyRule>,
    amountSign: Int,
): String? {
    for (rule in rules) {
        if (!rule.onlyWhenSign.matches(amountSign)) continue
        if (rule.predicates.all { evaluatePredicate(it) }) return rule.name
    }
    return null
}

private fun RuleSign.matches(sign: Int): Boolean =
    when (this) {
        RuleSign.ANY -> true
        RuleSign.NEGATIVE -> sign < 0
        RuleSign.POSITIVE -> sign > 0
    }

private fun JsonObject.evaluatePredicate(predicate: RulePredicate): Boolean {
    val operand = predicate.value.orEmpty()
    return when (predicate.op) {
        PredicateOp.EXISTS -> resolveJsonElementPath(predicate.path) != null
        PredicateOp.EQUALS -> resolveJsonPath(predicate.path) == predicate.value
        PredicateOp.EQUALS_IGNORE_CASE -> resolveJsonPath(predicate.path)?.equals(predicate.value, ignoreCase = true) == true
        PredicateOp.STARTS_WITH -> resolveJsonPath(predicate.path)?.startsWith(operand) == true
        PredicateOp.ARRAY_ANY_STARTS_WITH ->
            (resolveJsonElementPath(predicate.path) as? JsonArray)?.any { element ->
                element.jsonPrimitive.contentOrNull?.startsWith(operand, ignoreCase = true) == true
            } == true
        PredicateOp.OBJECT_EMPTY -> resolveJsonObjectPath(predicate.path).isNullOrEmpty()
        PredicateOp.OBJECT_NON_EMPTY -> resolveJsonObjectPath(predicate.path)?.isNotEmpty() == true
    }
}

/** Resolves a dot-notation path to its [JsonElement] (object, array, or primitive), or null. */
private fun JsonObject.resolveJsonElementPath(dotPath: String): JsonElement? {
    var current: JsonElement = this
    for (part in dotPath.split(".")) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return current
}

private class AttributeTypeCache(
    private val repo: AttributeTypeRepository,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, AttributeTypeId>()

    suspend fun getOrCreate(name: String): AttributeTypeId = mutex.withLock { cache.getOrPut(name) { repo.getOrCreate(name) } }
}
