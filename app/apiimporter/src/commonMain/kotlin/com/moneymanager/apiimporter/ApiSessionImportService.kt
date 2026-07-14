@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.apiimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionInsert
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
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
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.insertApiResponseTransactions
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.personalCounterpartyKey
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiHttpResponse
import com.moneymanager.rest.ScaParams
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.lighthousegames.logging.logging
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val logger = logging()

private val ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID = AttributeTypeId(WellKnownIds.ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID)
private val BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID = AttributeTypeId(WellKnownIds.BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID)
private val ACCOUNT_SORT_CODE_ATTR_TYPE_ID = AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID)
private val ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID = AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID)
private val ACCOUNT_COUNTERPARTY_NAME_KEY_ATTR_TYPE_ID = AttributeTypeId(WellKnownIds.ACCOUNT_COUNTERPARTY_NAME_KEY_ATTR_TYPE_ID)

// How far apart two providers may timestamp the same real movement and still be reconciled as one
// (a Faster Payment is near-instant; this absorbs send-vs-settle timing differences between banks).
// Reconciliation is additionally restricted to cross-source matches, so this window does not collapse
// genuine repeat transfers from the same provider.
private val RECONCILE_WINDOW = 5.minutes

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

data class ApiAccountIdentifiersDownloadResult(
    val accountCount: Int,
    val skipped: Boolean = false,
)

data class ApiPeopleDownloadResult(
    val personCount: Int,
    val skipped: Boolean = false,
)

/**
 * The combined outcome of one download: accounts, plus transactions (null when region-blocked) and
 * people (null when the strategy has no people-download endpoint), all into a single session.
 */
data class ApiSessionDownloadResult(
    val accounts: ApiAccountsDownloadResult,
    val transactions: ApiTransactionsDownloadResult?,
    val people: ApiPeopleDownloadResult?,
)

fun ApiAccountsDownloadResult.displaySummary(): String =
    if (skipped) {
        "Accounts already downloaded: $accountCount account(s) (skipped)."
    } else {
        "Accounts downloaded: $accountCount account(s)."
    }

fun ApiTransactionsDownloadResult.displaySummary(): String =
    "Transactions downloaded: $accountCount account(s), $transactionResponseCount new response page(s)."

fun ApiPeopleDownloadResult.displaySummary(): String =
    if (skipped) {
        "People already downloaded: $personCount person(s) (skipped)."
    } else {
        "People downloaded: $personCount person(s)."
    }

fun ApiSessionDownloadResult.displaySummary(): String =
    buildList {
        add(accounts.displaySummary())
        transactions?.let { add(it.displaySummary()) }
        people?.let { add(it.displaySummary()) }
    }.joinToString("\n")

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
    apiSessionRepository: ApiSessionReadRepository,
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
    apiSessionRepository: ApiSessionReadRepository,
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
 * Downloads the per-account identifiers endpoint (the account's own sort code + account number) into
 * [sessionId], one request per account. Only applies to strategies that configure
 * [com.moneymanager.domain.model.apistrategy.ApiImportStrategy.accountIdentifiersEndpoint] (e.g.
 * Starling, whose `/accounts` response omits bank details); a no-op otherwise. Incremental: an account
 * whose identifiers URL is already stored is skipped.
 *
 * Call [downloadApiSessionAccounts] first so that an accounts response exists.
 */
suspend fun downloadApiSessionAccountIdentifiers(
    token: String,
    apiClient: ApiClient,
    apiSessionRepository: ApiSessionReadRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    accountsSessionId: ApiSessionId? = null,
    sca: ScaParams? = null,
): ApiAccountIdentifiersDownloadResult {
    val endpoint = strategy.accountIdentifiersEndpoint ?: return ApiAccountIdentifiersDownloadResult(0, skipped = true)

    val existingRequests = apiSessionRepository.getRequestsBySession(sessionId)
    val existingResponsesByRequestId = apiSessionRepository.getResponsesBySession(sessionId).associateBy { it.requestId }
    val existingRequestsByUrl = existingRequests.associateBy { it.url }

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
            apiSessionRepository.getResponsesBySession(sessionId)
        }
    val accountsRequestsById = accountsRequests.associateBy { it.id }

    val accountEntries =
        accountsResponses.flatMap { response ->
            val request = accountsRequestsById[response.requestId] ?: return@flatMap emptyList()
            if (!request.isAccountsRequest(strategy)) return@flatMap emptyList()
            val ancestorVars = request.ancestorVars(strategy)
            parseAccounts(response.json, strategy).map { it to ancestorVars }
        }

    var fetched = 0
    for ((account, ancestorVars) in accountEntries) {
        val ctx = ImportUrlContext(account = account, ancestorVars = ancestorVars)
        val url = buildEndpointRequestUrl(strategy.baseUrl, endpoint, ctx)
        val alreadyStored = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] } != null
        if (!alreadyStored) {
            fetchResponse(url = url, token = token, apiClient = apiClient, sca = sca)
            fetched += 1
        }
    }
    return ApiAccountIdentifiersDownloadResult(accountCount = accountEntries.size, skipped = fetched == 0)
}

/**
 * Downloads the people/profiles endpoint (the account holder identity) into [sessionId]. Only
 * applies to strategies with [com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig]
 * configured (e.g. Wise); a no-op otherwise.
 */
suspend fun downloadApiSessionPeople(
    token: String,
    apiClient: ApiClient,
    apiSessionRepository: ApiSessionReadRepository,
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
 * The two ownership-linking strategies are mutually exclusive: [ApiPersonImportConfig.ownsAllAccounts]
 * links a single global holder to every account, while [ApiPersonImportConfig.accountOwnerAncestorExpr]
 * derives ownership from the resource hierarchy. Allowing both would silently prefer ownsAllAccounts and
 * persist wrong links, so every code path that acts on the config validates it first.
 */
private fun validatePeopleOwnershipConfig(config: ApiPersonImportConfig) {
    require(!(config.ownsAllAccounts && config.accountOwnerAncestorExpr != null)) {
        "Invalid peopleDownload config: ownsAllAccounts and accountOwnerAncestorExpr are mutually exclusive."
    }
}

/**
 * Imports people from a people-download session: creates a [Person] for each profile holder and, when
 * [accountsSessionId] is provided, links each person as an owner of the accounts fetched under their
 * profile (via [ApiPersonImportConfig.accountOwnerAncestorExpr]).
 */
suspend fun importApiSessionPeople(
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    importEngine: ImportEngine,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    accountsSessionId: ApiSessionId? = null,
): ApiPeopleImportResult {
    val config = strategy.peopleDownload ?: return ApiPeopleImportResult(personCount = 0, ownershipCount = 0)
    validatePeopleOwnershipConfig(config)
    val externalIdAttributeTypeId = strategy.personExternalIdAttribute?.let { importEngine.getOrCreateAttributeType(it) }
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val peopleResponses =
        apiSessionRepository.getResponsesBySession(sessionId).filter { response ->
            val path = requestsById[response.requestId]?.encodedPath() ?: return@filter false
            extractPathVariables(config.endpoint.path, path) != null
        }
    // The own accounts these people own already exist in the DB; resolve their ids by external id (a
    // read, not a write) so they can be referenced via AccountRef.Existing.
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

    val peopleResolver = BatchPeopleResolver()
    for (response in peopleResponses) {
        val requestId = requestsById[response.requestId]?.id
        responseItemsArray(response.json, config.endpoint.responseArrayKey)
            ?.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val owner = obj.toPersonOwner(config, index) ?: return@forEachIndexed
                val ownerSource = Source.Api(sessionId, requestId, owner.jsonPath)
                val ownedAccounts =
                    if (config.ownsAllAccounts) allSessionAccountIds else ownedAccountsByProfile[owner.userId].orEmpty()
                if (ownedAccounts.isEmpty()) {
                    peopleResolver.linkPersonOnly(owner, externalIdAttributeTypeId, ownerSource)
                } else {
                    for (accountId in ownedAccounts) {
                        peopleResolver.linkOwner(owner, AccountRef.Existing(accountId), externalIdAttributeTypeId, ownerSource)
                    }
                }
            }
    }

    val people = peopleResolver.intents()
    if (people.isEmpty()) return ApiPeopleImportResult(personCount = 0, ownershipCount = 0)
    val batch =
        ImportBatch(
            transfers = emptyList(),
            dedupePolicy = DedupePolicy.None,
            peopleToCreate = people,
            ownerships = peopleResolver.ownershipIntents(),
        )
    val result = importEngine.import(batch)
    return ApiPeopleImportResult(personCount = result.peopleCreated, ownershipCount = result.ownershipsCreated)
}

/** Builds a map of profile external id → the [AccountId]s fetched under that profile. */
private suspend fun buildProfileAccountMap(
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
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
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
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
    apiSessionRepository: ApiSessionReadRepository,
    currencyRepository: CurrencyReadRepository,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId? = null,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (ApiSessionImportProgress) -> Unit = {},
): ApiSessionImportResult {
    val setup =
        setupImportSession(
            apiSessionRepository = apiSessionRepository,
            currencyRepository = currencyRepository,
            sessionId = sessionId,
            accountsSessionId = accountsSessionId,
            strategy = strategy,
            importEngine = importEngine,
            onProgress = onProgress,
            passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) },
        )
    onProgress(ApiSessionImportProgress(detail = "Preparing import session...", progress = 0.05f))
    // Build the import model purely (no DB writes): allocate account/counterparty/people/ownership
    // intents and the transfers, then hand the whole batch to the engine, which performs every write.
    ensureSourceAccounts(setup)
    precreateCounterparties(
        transactionResponses = setup.transactionResponses,
        sessionId = setup.sessionId,
        requestsById = setup.requestsById,
        strategy = setup.strategy,
        accountResolver = setup.accountResolver,
        counterpartyIdField = setup.counterpartyIdField,
        counterpartyAccountNames = counterpartyAccountNames,
        nameMappings = setup.nameMappings,
    )
    onProgress(ApiSessionImportProgress(detail = "Counterparties prepared.", progress = 0.2f))
    val preparedTransfers = prepareTransactionTransfers(setup)
    onProgress(ApiSessionImportProgress(detail = "Transactions prepared. Processing people...", progress = 0.6f))
    addCustomAccountFieldAttributes(setup)
    buildPeopleAndOwnershipIntents(setup)
    onProgress(ApiSessionImportProgress(detail = "Importing...", progress = 0.7f))
    val importResult = runImportEngine(setup, preparedTransfers)
    onProgress(ApiSessionImportProgress(detail = "Import finalized.", progress = 0.98f))
    return ApiSessionImportResult(
        accountCount = setup.accountsById.size,
        transactionCount = importResult.transfersImported,
        personCount = importResult.peopleCreated,
        duplicateCount = importResult.duplicates,
        errorCount = preparedTransfers.errorCount,
        excludedCount = importResult.excluded,
    )
}

/**
 * Allocates an [ImportAccountIntent] for every downloaded account up front, so an account is created
 * even when it has no owners and no transactions (e.g. Wise balances). Its own bank details (e.g. from
 * Starling's identifiers endpoint) are recorded as sort-code/account-number attributes so counterparties
 * another provider creates for this same real account match and merge into it. Idempotent.
 */
private suspend fun ensureSourceAccounts(setup: ImportSetup) {
    for (account in setup.accountsById.values) {
        resolveOwnAccountKey(setup, account)
    }
}

/**
 * The own account's bank details (sort code, account number), taken from the account itself (filled
 * directly from the accounts response or from the account-identifiers endpoint) or, failing that, from
 * its owners. Either element is null when absent.
 */
private fun ApiImportAccount.bankDetails(): Pair<String?, String?> {
    val sortCode =
        sortCode?.takeIf { it.isNotBlank() } ?: owners.firstOrNull { !it.sortCode.isNullOrBlank() }?.sortCode
    val accountNumber =
        accountNumber?.takeIf { it.isNotBlank() } ?: owners.firstOrNull { !it.accountNumber.isNullOrBlank() }?.accountNumber
    return sortCode to accountNumber
}

/** Mutable progress counters shared between the setup, parallel import, and progress callback. */
private class ImportCounts(
    val totalResponses: Int,
) {
    var completedCount = 0
    var totalImported = 0
    var totalDuplicates = 0
    var totalErrors = 0
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

/** All state created during setup that is shared across the import steps. */
private data class ImportSetup(
    val strategy: ApiImportStrategy,
    val sessionId: ApiSessionId,
    val accountsById: Map<String, ApiImportAccount>,
    val accountApiSourceByExternalId: Map<String, AccountApiSource>,
    val transactionResponses: List<ApiResponse>,
    val requestsById: Map<ApiRequestId, ApiRequest>,
    val counterpartyIdField: String?,
    val nameMappings: CounterpartyNameMappings,
    val customTxFields: Map<String, String>,
    val uniqueIdTxFields: Set<String>,
    val accountResolver: BatchAccountResolver,
    val peopleResolver: BatchPeopleResolver,
    val currencyCache: CurrencyCache,
    val attributeTypeCache: AttributeTypeCache,
    val counts: ImportCounts,
    val progressMutex: Mutex,
    val onProgress: (ApiSessionImportProgress) -> Unit,
    val apiSessionRepository: ApiSessionReadRepository,
    val importEngine: ImportEngine,
    /** Detects pass-through (conduit) charges (e.g. Curve) from a transaction description; null disables it. */
    val passThroughDetector: PassThroughDetector? = null,
)

private suspend fun setupImportSession(
    apiSessionRepository: ApiSessionReadRepository,
    currencyRepository: CurrencyReadRepository,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId?,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
    onProgress: (ApiSessionImportProgress) -> Unit,
    passThroughDetector: PassThroughDetector? = null,
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

    val parsedAccountsById =
        accountOnlyResponses
            .flatMap { response -> parseAccounts(response.json, strategy) }
            .associateBy { it.id }
    // Fold in per-account bank details fetched from the account-identifiers endpoint (e.g. Starling),
    // whose responses live alongside the transaction/account traffic of either session.
    val identifierResponsePairs =
        responses.mapNotNull { response -> requestsById[response.requestId]?.let { it to response } } +
            accountsResponses.mapNotNull { response -> accountsRequestsById[response.requestId]?.let { it to response } }
    val accountsById = enrichAccountsWithIdentifiers(parsedAccountsById, strategy, identifierResponsePairs)
    val transactionResponses = responses.filter { requestsById[it.requestId]?.resolveAccountExternalId(strategy) != null }

    val currencyCache = CurrencyCache(currencyRepository)
    val attributeTypeCache = AttributeTypeCache(importEngine)
    val customTxFields = strategy.transactionMappings.customFields
    val uniqueIdTxFields = strategy.transactionMappings.uniqueIdentifierFields
    val counterpartyIdField = strategy.transactionMappings.counterpartyIdField
    val nameMappings = CounterpartyNameMappings.from(strategy)

    // Pre-create transaction attribute types before the concurrent section so that
    // no two coroutines race to write the same type, which causes SQLITE_BUSY.
    for (fieldName in customTxFields.keys) attributeTypeCache.getOrCreate(fieldName)

    val counts = ImportCounts(transactionResponses.size)
    val progressMutex = Mutex()

    onProgress(ApiSessionImportProgress(detail = counts.detailMessage(), progress = counts.progressFraction()))

    return ImportSetup(
        strategy = strategy,
        sessionId = sessionId,
        accountsById = accountsById,
        accountApiSourceByExternalId = accountApiSourceByExternalId,
        transactionResponses = transactionResponses,
        requestsById = requestsById,
        counterpartyIdField = counterpartyIdField,
        nameMappings = nameMappings,
        customTxFields = customTxFields,
        uniqueIdTxFields = uniqueIdTxFields,
        accountResolver = BatchAccountResolver(),
        peopleResolver = BatchPeopleResolver(),
        currencyCache = currencyCache,
        attributeTypeCache = attributeTypeCache,
        counts = counts,
        progressMutex = progressMutex,
        onProgress = onProgress,
        apiSessionRepository = apiSessionRepository,
        importEngine = importEngine,
        passThroughDetector = passThroughDetector,
    )
}

/** Adds each strategy custom account-field value as an attribute on its source account's intent. */
private suspend fun addCustomAccountFieldAttributes(setup: ImportSetup) {
    val customAccountFields = setup.strategy.accountMappings.customFields
    if (customAccountFields.isEmpty()) return
    for (account in setup.accountsById.values) {
        val rawJson = account.rawJson ?: continue
        for ((fieldName, jsonPath) in customAccountFields) {
            val value = rawJson.resolveJsonPath(jsonPath) ?: continue
            val typeId = setup.attributeTypeCache.getOrCreate(fieldName)
            setup.accountResolver.addSourceAttribute(account.id, NewAttribute(typeId = typeId, value = value))
        }
    }
}

/**
 * Builds the people + ownership intents for this import: account holders (from the accounts payload),
 * personal counterparties (from the transaction feed), and the global account holder (people-download
 * endpoint) linked to every own account. The engine resolves/creates the people and ownerships.
 */
private suspend fun buildPeopleAndOwnershipIntents(setup: ImportSetup) {
    val externalIdAttributeTypeId =
        setup.strategy.personExternalIdAttribute?.let { setup.attributeTypeCache.getOrCreate(it) }
    buildPeopleFromAccounts(setup, externalIdAttributeTypeId)
    buildPeopleFromCounterparties(setup, externalIdAttributeTypeId)
    buildGlobalHolderOwnerships(setup, externalIdAttributeTypeId)
}

/** Links each downloaded account's declared owners to that account's intent. */
private suspend fun buildPeopleFromAccounts(
    setup: ImportSetup,
    externalIdAttributeTypeId: AttributeTypeId?,
) {
    for (account in setup.accountsById.values) {
        if (account.owners.isEmpty()) continue
        val accountKey = resolveOwnAccountKey(setup, account)
        val accountSource = setup.accountApiSourceByExternalId[account.id]
        for (owner in account.owners) {
            // Source the owner at the account's accounts-endpoint origin (request + owners json node),
            // mirroring the old importPeopleFromAccounts behaviour.
            val ownerSource =
                if (accountSource != null) {
                    Source.Api(accountSource.sessionId, accountSource.requestId, owner.jsonPath)
                } else {
                    Source.Api(setup.sessionId)
                }
            setup.peopleResolver.linkOwner(owner, AccountRef.Local(accountKey), externalIdAttributeTypeId, ownerSource)
        }
    }
}

/**
 * For each personal counterparty in the transaction feed, links its owner to the counterparty's account
 * intent. Mirrors the old direct-write path: only flagged personal beneficiaries with a stable key
 * (counterparty id, bank identity, or at least a usable name) produce a person + ownership.
 */
private suspend fun buildPeopleFromCounterparties(
    setup: ImportSetup,
    externalIdAttributeTypeId: AttributeTypeId?,
) {
    for (response in setup.transactionResponses) {
        val request = setup.requestsById[response.requestId] ?: continue
        for (item in parseTransactionsWithPath(response.json, setup.strategy)) {
            linkCounterpartyOwner(setup, externalIdAttributeTypeId, request, item)
        }
    }
}

/** Links a transaction's personal counterparty owner to its counterparty account intent, if there is one. */
private suspend fun linkCounterpartyOwner(
    setup: ImportSetup,
    externalIdAttributeTypeId: AttributeTypeId?,
    request: ApiRequest,
    item: ApiTransactionPageItem,
) {
    val isBuiltIn = item.rawJson?.resolveBuiltInCounterpartyType(setup.strategy.builtInCounterpartyRules, item.amountSign) != null
    if (item.isZeroAmount || isBuiltIn) return
    val owner = item.personalCounterpartyOwner(setup.strategy.peopleMappings) ?: return
    val identity = owner.personalCounterpartyIdentity()
    val counterpartyId = item.rawJson?.resolveCounterpartyIdentity(setup.counterpartyIdField, setup.strategy.peopleMappings)
    val counterpartyName =
        identity?.name ?: owner.preferredName?.takeIf { it.isNotBlank() } ?: item.counterpartyName(setup.nameMappings)
    if (counterpartyId == null && identity == null && counterpartyName.isBlank()) return
    val counterpartyApiSource =
        AccountApiSource(setup.sessionId, request.id, item.counterpartyJsonPath(setup.strategy.peopleMappings))
    val accountKey =
        setup.accountResolver.resolveCounterpartyAccount(
            counterpartyId = counterpartyId,
            dedupeKey = identity?.dedupeKey ?: bankDedupeKeyFromCounterpartyId(counterpartyId),
            builtInType = null,
            name = counterpartyName,
            personalIdentity = identity,
            // This path only runs for a personal counterparty owner, so collapse middle-name variants.
            personalCounterparty = true,
            source = counterpartyApiSource.toSource(),
        )
    setup.peopleResolver.linkOwner(
        owner,
        AccountRef.Local(accountKey),
        externalIdAttributeTypeId,
        Source.Api(setup.sessionId, request.id, owner.jsonPath),
    )
}

/**
 * Links the global account holder (people-download endpoint, `ownsAllAccounts`) to every own account in
 * this import, so ownership is order-independent regardless of whether people import before or after the
 * accounts. The engine skips already-linked owners.
 */
private suspend fun buildGlobalHolderOwnerships(
    setup: ImportSetup,
    externalIdAttributeTypeId: AttributeTypeId?,
) {
    val config = setup.strategy.peopleDownload ?: return
    validatePeopleOwnershipConfig(config)
    if (!config.ownsAllAccounts || setup.accountsById.isEmpty()) return

    val ownAccountKeys = setup.accountsById.values.map { resolveOwnAccountKey(setup, it) }
    val peopleResponses =
        setup.apiSessionRepository.getResponsesBySession(setup.sessionId).filter { response ->
            val path = setup.requestsById[response.requestId]?.encodedPath() ?: return@filter false
            extractPathVariables(config.endpoint.path, path) != null
        }
    for (response in peopleResponses) {
        val requestId = setup.requestsById[response.requestId]?.id
        responseItemsArray(response.json, config.endpoint.responseArrayKey)
            ?.forEachIndexed { index, element ->
                val owner = (element as? JsonObject)?.toPersonOwner(config, index) ?: return@forEachIndexed
                val ownerSource = Source.Api(setup.sessionId, requestId, owner.jsonPath)
                for (accountKey in ownAccountKeys) {
                    setup.peopleResolver.linkOwner(owner, AccountRef.Local(accountKey), externalIdAttributeTypeId, ownerSource)
                }
            }
    }
}

/** Resolves (allocating if needed) the [LocalAccountKey] for a downloaded own account, with its bank details. */
private suspend fun resolveOwnAccountKey(
    setup: ImportSetup,
    account: ApiImportAccount,
): LocalAccountKey {
    val (sortCode, accountNumber) = account.bankDetails()
    return setup.accountResolver.resolveSourceAccount(
        externalId = account.id,
        name = account.displayName(),
        sortCode = sortCode,
        accountNumber = accountNumber,
        source = setup.accountApiSourceByExternalId[account.id]?.toSource() ?: Source.Api(setup.sessionId),
    )
}

suspend fun discoverApiCounterpartiesToCreate(
    apiSessionRepository: ApiSessionReadRepository,
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
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
                suggestedAccountName = suggestCounterpartyName(names),
                downloadedNames = names.distinct().sorted(),
            )
        }.sortedBy { it.suggestedAccountName }
}

/**
 * Allocates one counterparty account intent per distinct counterparty id, with its canonical name (the
 * user-confirmed name when provided, else the most common downloaded name). Each id's intent is allocated
 * once here so transaction-time [BatchAccountResolver.resolveCounterpartyAccount] reuses it by id rather
 * than re-deriving a (possibly different) per-transaction name; the engine then creates/reuses the account.
 */
private suspend fun precreateCounterparties(
    transactionResponses: List<ApiResponse>,
    sessionId: ApiSessionId,
    requestsById: Map<ApiRequestId, ApiRequest>,
    strategy: ApiImportStrategy,
    accountResolver: BatchAccountResolver,
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
                                apiSource = AccountApiSource(sessionId, request.id, item.counterpartyJsonPath(strategy.peopleMappings)),
                                dedupeKey =
                                    item.rawJson.personalCounterpartyIdentity(strategy.peopleMappings)?.dedupeKey
                                        ?: bankDedupeKeyFromCounterpartyId(counterpartyId),
                            )
                        }
                    }
                }.awaitAll()
                .flatten()
                .groupBy { it.counterpartyId }
        }

    for ((counterpartyId, candidates) in counterparties) {
        val accountName =
            counterpartyAccountNames[counterpartyId]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: suggestCounterpartyName(candidates.map { it.downloadedName })
        accountResolver.resolveCounterpartyAccount(
            counterpartyId = counterpartyId,
            dedupeKey = candidates.firstNotNullOfOrNull { it.dedupeKey },
            builtInType = null,
            name = accountName,
            source = candidates.first().apiSource.toSource(),
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
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
): Map<String, AccountId> = loadAccountExternalIdIndex(accountRepository, accountAttributeRepository)

private suspend fun loadAccountExternalIdIndex(
    accountRepository: AccountReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
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

/**
 * Suggests a single account name for a counterparty seen under several spellings (e.g. Monzo prefixes a
 * per-transaction reference number, yielding "120386070PAXOS TE", "991204771PAXOS TE", …). Prefers the
 * longest substring common to *all* the spellings ("PAXOS TE"), which strips such varying noise; falls
 * back to the first non-blank name when the names share nothing meaningful.
 */
internal fun suggestCounterpartyName(names: List<String>): String {
    val nonBlank = names.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) return "Unknown"
    val common = longestCommonSubstring(nonBlank).trim()
    return common.takeIf { it.length >= MIN_COMMON_NAME_LENGTH } ?: nonBlank.first()
}

/** Minimum length of a shared substring before it is trusted as a name rather than incidental overlap. */
private const val MIN_COMMON_NAME_LENGTH = 3

/**
 * Returns the leftmost longest substring contained in every one of [strings] (empty when there is none).
 * Any such substring must occur in the shortest string, so it enumerates that string's substrings from
 * longest to shortest and returns the first one present in all others. Inputs here are short and few.
 */
internal fun longestCommonSubstring(strings: List<String>): String {
    if (strings.isEmpty()) return ""
    val shortest = strings.minBy { it.length }
    for (length in shortest.length downTo 1) {
        for (start in 0..shortest.length - length) {
            val candidate = shortest.substring(start, start + length)
            if (strings.all { it.contains(candidate) }) return candidate
        }
    }
    return ""
}

private data class CounterpartyImportCandidate(
    val counterpartyId: String,
    val downloadedName: String,
    val apiSource: AccountApiSource,
    // Bank identity ("sortCode|accountNumber") when the counterparty carries one; lets the resolver
    // merge it into an account that shares those bank details (e.g. your own account at another
    // provider) instead of minting a duplicate.
    val dedupeKey: String?,
)

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

/** Whether [type] marks a counterparty as a person, per the single or multi-value personal config. */
private fun ApiPeopleMappings.isPersonalBeneficiaryType(type: String?): Boolean {
    if (type == null) return false
    if (type.equals(personalBeneficiaryAccountTypeValue, ignoreCase = true)) return true
    return personalBeneficiaryAccountTypeValues.any { it.equals(type, ignoreCase = true) }
}

/**
 * The JSON path to the counterparty within this transaction item, for linking an imported account
 * back to its API-traffic origin. Points at the nested counterparty object when one exists
 * ([ApiPeopleMappings.counterpartyObjectField] set, e.g. Monzo's "counterparty"); for providers whose
 * counterparty fields are flat on the item (blank field, e.g. Starling) it points at the item itself,
 * so the audit-trail "origin" expands a node that actually exists.
 */
private fun ApiTransactionPageItem.counterpartyJsonPath(peopleMappings: ApiPeopleMappings): JsonPath =
    if (peopleMappings.counterpartyObjectField.isBlank()) {
        jsonPath
    } else {
        JsonPath("${jsonPath.value}.${peopleMappings.counterpartyObjectField}")
    }

/** Whether this transaction's counterparty is flagged a person (vs a merchant/business) by the strategy. */
private fun ApiTransactionPageItem.isPersonalCounterparty(peopleMappings: ApiPeopleMappings): Boolean {
    val counterparty = rawJson?.resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return false
    return peopleMappings.isPersonalBeneficiaryType(counterparty.stringOrNull(peopleMappings.beneficiaryAccountTypeField))
}

private fun ApiTransactionPageItem.personalCounterpartyOwner(peopleMappings: ApiPeopleMappings): ApiImportAccountOwner? {
    val counterparty = rawJson?.resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val beneficiaryAccountType = counterparty.stringOrNull(peopleMappings.beneficiaryAccountTypeField)
    if (!peopleMappings.isPersonalBeneficiaryType(beneficiaryAccountType)) return null

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
        jsonPath = counterpartyJsonPath(peopleMappings),
    )
}

private fun JsonObject.personalCounterpartyIdentity(peopleMappings: ApiPeopleMappings): PersonalCounterpartyIdentity? {
    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val beneficiaryAccountType = counterparty.stringOrNull(peopleMappings.beneficiaryAccountTypeField)
    if (!peopleMappings.isPersonalBeneficiaryType(beneficiaryAccountType)) return null
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

/**
 * Returns [accounts] with each account's bank details (sort code + account number) filled from the
 * matching account-identifiers response, when [ApiImportStrategy.accountIdentifiersEndpoint] is set.
 * Each identifiers response is matched to its account via the `account.id` captured from the request
 * path, and the sort code / account number are read using the strategy's account field mappings.
 * A no-op for strategies without the endpoint, or when no identifiers responses are present.
 */
private fun enrichAccountsWithIdentifiers(
    accounts: Map<String, ApiImportAccount>,
    strategy: ApiImportStrategy,
    responsePairs: List<Pair<ApiRequest, ApiResponse>>,
): Map<String, ApiImportAccount> {
    val endpoint = strategy.accountIdentifiersEndpoint ?: return accounts
    val identifiersByAccountId =
        responsePairs
            .mapNotNull { (request, response) ->
                val accountExternalId =
                    extractPathVariables(endpoint.path, request.encodedPath())?.get("account.id") ?: return@mapNotNull null
                parseJsonObjectOrNull(response.json)?.let { accountExternalId to it }
            }.toMap()
    if (identifiersByAccountId.isEmpty()) return accounts

    val mappings = strategy.accountMappings
    return accounts.mapValues { (id, account) ->
        val identifiers = identifiersByAccountId[id] ?: return@mapValues account
        account.copy(
            sortCode = identifiers.stringOrNull(mappings.sortCodeField)?.takeIf { it.isNotBlank() } ?: account.sortCode,
            accountNumber = identifiers.stringOrNull(mappings.accountNumberField)?.takeIf { it.isNotBlank() } ?: account.accountNumber,
        )
    }
}

private fun parseJsonObjectOrNull(json: String): JsonObject? =
    try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to parse API identifiers response as a JSON object" }
        null
    }

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
    // Fee charged on the transaction (magnitude only; a fee is always money out of the own account).
    val feeAmountMinorUnits: Long? = null,
    val feeAmountDecimalMajor: BigDecimal? = null,
    val feeCurrencyCode: String? = null,
    val feeDescription: String? = null,
) {
    val isZeroAmount: Boolean get() = amountSign == 0
    val isIncoming: Boolean get() = amountSign > 0
    val hasFee: Boolean get() = feeAmountMinorUnits != null || feeAmountDecimalMajor != null
}

private data class CounterpartyNameMappings(
    val merchantNameField: String?,
    val counterpartyNameField: String?,
) {
    companion object {
        fun from(strategy: ApiImportStrategy): CounterpartyNameMappings =
            CounterpartyNameMappings(
                merchantNameField = strategy.transactionMappings.merchantNameField,
                counterpartyNameField = strategy.transactionMappings.counterpartyNameField,
            )
    }
}

private data class ApiImportPageContext(
    val index: Int,
    val response: ApiHttpResponse,
    val requestId: ApiRequestId,
    val ownAccountKey: LocalAccountKey,
)

private data class PreparedApiTransaction(
    val pageIndex: Int,
    val itemIndex: Int,
    val responseId: ApiResponseId,
    val requestId: ApiRequestId,
    val item: ApiTransactionPageItem,
    val source: AccountRef,
    val target: AccountRef,
    val timestamp: Instant,
    val description: String,
    val amount: Money,
    val attributes: List<NewAttribute>,
    val transactionApiId: String?,
    val uniqueKey: Map<String, String>?,
    val fee: ImportFee? = null,
    val passThrough: ImportPassThrough? = null,
)

/** The prepared transfers plus the count of items that failed to parse/prepare (recorded as errors). */
private data class PreparedTransfers(
    val orderedPrepared: List<PreparedApiTransaction>,
    val failedRecords: List<ResponseTransactionImportRecord>,
) {
    val errorCount: Int get() = failedRecords.size
}

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

/**
 * Concurrently parses + prepares every transaction in the session into [PreparedApiTransaction]s (with
 * [AccountRef.Local] account references, allocated through the [BatchAccountResolver]). No DB writes —
 * accounts are only allocated as intents; the engine creates them later. Returns the prepared transfers
 * in deterministic (page, item) order plus the per-item failures recorded as errors.
 */
private suspend fun prepareTransactionTransfers(setup: ImportSetup): PreparedTransfers {
    val pageContexts =
        setup.transactionResponses.mapIndexedNotNull { index, response ->
            val request = setup.requestsById[response.requestId] ?: return@mapIndexedNotNull null
            val account = setup.accountsById[request.resolveAccountExternalId(setup.strategy)] ?: return@mapIndexedNotNull null
            ApiImportPageContext(
                index = index,
                response = response.toApiHttpResponse(),
                requestId = request.id,
                ownAccountKey = resolveOwnAccountKey(setup, account),
            )
        }

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

    val failedRecords = mutableListOf<ResponseTransactionImportRecord>()
    val preparedTransactions = mutableListOf<PreparedApiTransaction>()
    preparations.forEach { preparation ->
        when (preparation) {
            is ApiTransactionPreparation.Prepared -> preparedTransactions += preparation.transaction
            is ApiTransactionPreparation.Failed -> failedRecords += preparation.record
        }
    }

    // Order deterministically so engine outcomes and source recording align.
    val orderedPrepared =
        preparedTransactions.sortedWith(compareBy<PreparedApiTransaction> { it.pageIndex }.thenBy { it.itemIndex })
    return PreparedTransfers(orderedPrepared = orderedPrepared, failedRecords = failedRecords)
}

/**
 * Assembles the single [ImportBatch] from the prepared transfers + the resolver's account/people/ownership
 * intents and runs the engine exactly once. The engine performs every write, including the API
 * bookkeeping (the response-transaction records, via an [ImportBatch] session mutation).
 */
private suspend fun runImportEngine(
    setup: ImportSetup,
    prepared: PreparedTransfers,
): ImportResult {
    val orderedPrepared = prepared.orderedPrepared
    val transactionIdAttributeName =
        setup.customTxFields.entries
            .firstOrNull { it.value == "id" }
            ?.key

    val importTransfers =
        orderedPrepared.map { p ->
            ImportTransfer(
                rowKey = ImportRowKey.ApiJsonPath(p.requestId, p.item.jsonPath.value),
                fromAccount = p.source,
                toAccount = p.target,
                source = Source.Api(setup.sessionId),
                timestamp = p.timestamp,
                description = p.description,
                amount = p.amount,
                attributes = p.attributes,
                uniqueKey = p.uniqueKey,
                apiId = p.transactionApiId,
                excludedFromBalances = !p.item.declineReason.isNullOrBlank(),
                fee = p.fee,
                passThrough = p.passThrough,
            )
        }

    val uniqueIdTxFields = setup.uniqueIdTxFields
    val batch =
        ImportBatch(
            transfers = importTransfers,
            dedupePolicy =
                DedupePolicy.ApiMultiKey(
                    reconcileWindow = RECONCILE_WINDOW,
                    reconciledExclusionAttributeTypeId = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID),
                    reconciledRelationshipTypeId = RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID),
                ),
            accountsToCreate = setup.accountResolver.intents(),
            peopleToCreate = setup.peopleResolver.intents(),
            ownerships = setup.peopleResolver.ownershipIntents(),
            apiIdExtractor =
                ExistingApiIdExtractor { transfer ->
                    transactionIdAttributeName?.let { name ->
                        transfer.attributes.firstOrNull { it.attributeType.name == name }?.value
                    }
                },
            uniqueKeyExtractor =
                if (uniqueIdTxFields.isEmpty()) {
                    null
                } else {
                    ExistingUniqueKeyExtractor { transfer ->
                        val key =
                            uniqueIdTxFields.associateWith { fieldName ->
                                transfer.attributes.firstOrNull { it.attributeType.name == fieldName }?.value
                            }
                        // Skip existing transfers missing any unique field (matches indexByUniqueTransactionFields).
                        if (key.values.any { it == null }) null else key.mapValues { it.value!! }
                    }
                },
        )

    val importResult =
        setup.importEngine.import(
            batch = batch,
            onProgress = { progress ->
                setup.onProgress(
                    ApiSessionImportProgress(
                        detail = progress.detail,
                        progress = progress.fraction?.let { 0.7f + (it * 0.25f) },
                    ),
                )
            },
        )

    // Map the engine's per-transfer outcomes (aligned to orderedPrepared) into response records.
    val responseRecords = prepared.failedRecords.toMutableList()
    orderedPrepared.forEachIndexed { index, p ->
        val outcome = importResult.orderedRowOutcomes[index]
        val state =
            when (outcome.status) {
                ImportStatus.IMPORTED -> ApiResponseTransactionState.IMPORTED
                else -> ApiResponseTransactionState.DUPLICATE
            }
        responseRecords +=
            p.responseRecord(
                state = state,
                transactionId = outcome.transferId,
                excludedFromBalances =
                    state == ApiResponseTransactionState.IMPORTED && !p.item.declineReason.isNullOrBlank(),
            )
    }

    setup.importEngine.insertApiResponseTransactions(
        responseRecords
            .sortedWith(compareBy<ResponseTransactionImportRecord> { it.pageIndex }.thenBy { it.itemIndex })
            .map { it.toInsert() },
    )

    return importResult
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
    val ownAccountRef = AccountRef.Local(context.ownAccountKey)
    val counterpartyApiSource =
        AccountApiSource(
            sessionId = setup.sessionId,
            requestId = context.requestId,
            jsonPath = item.counterpartyJsonPath(setup.strategy.peopleMappings),
        )
    val data = item.toTransferData(currency)
    // A pass-through (conduit) charge such as Curve, detected from the description (e.g. "CRV*…"). An
    // outgoing spend becomes the funding leg own -> conduit and the engine adds the spend leg conduit ->
    // merchant; an incoming refund/cancellation ("Refund: CRV*…") runs both legs the other way. Detection
    // + the conduit/merchant names come from user-editable config; routing here also avoids creating a
    // "CRV*…" junk counterparty account. Declined items (no real movement) and zero-value items (handled
    // as "Void") are never expanded into a spend leg.
    val passThroughMatch =
        if (!item.isZeroAmount && item.declineReason.isNullOrBlank()) {
            setup.passThroughDetector?.detect(data.description)
        } else {
            null
        }

    val counterpartyKey =
        if (passThroughMatch != null) {
            null
        } else if (item.isZeroAmount) {
            setup.accountResolver.resolveNamedAccount(
                "Void",
                source = counterpartyApiSource.toSource(),
            )
        } else {
            val builtInCounterpartyType =
                item.rawJson?.resolveBuiltInCounterpartyType(setup.strategy.builtInCounterpartyRules, item.amountSign)
            val counterpartyId = item.rawJson?.resolveCounterpartyIdentity(setup.counterpartyIdField, setup.strategy.peopleMappings)
            val personalCounterpartyIdentity = item.rawJson?.personalCounterpartyIdentity(setup.strategy.peopleMappings)
            setup.accountResolver.resolveCounterpartyAccount(
                counterpartyId = counterpartyId,
                dedupeKey = personalCounterpartyIdentity?.dedupeKey ?: bankDedupeKeyFromCounterpartyId(counterpartyId),
                builtInType = builtInCounterpartyType,
                name = builtInCounterpartyType ?: item.counterpartyName(setup.nameMappings),
                personalIdentity = personalCounterpartyIdentity,
                personalCounterparty = item.isPersonalCounterparty(setup.strategy.peopleMappings),
                source = counterpartyApiSource.toSource(),
            )
        }
    val counterpartyRef = counterpartyKey?.let { AccountRef.Local(it) }
    val transactionApiId = item.rawJson?.resolveJsonPath(setup.strategy.transactionMappings.idField)?.takeIf { it.isNotBlank() }
    val uniqueKey =
        if (setup.uniqueIdTxFields.isNotEmpty() && item.rawJson != null) {
            setup.uniqueIdTxFields.associateWith { fieldName ->
                setup.customTxFields[fieldName]?.let { item.rawJson.resolveJsonPath(it) } ?: ""
            }
        } else {
            null
        }

    // A fee is its own movement out of the own account into a consolidated per-strategy fee account,
    // linked to the main transfer via a `fee` relationship. Skipped for declined items (no real movement).
    val fee =
        if (item.hasFee && item.declineReason.isNullOrBlank()) {
            buildImportFee(item, ownAccountRef, currency, counterpartyApiSource, setup)
        } else {
            null
        }

    // When the provider's amount is GROSS (already includes the fee, e.g. Monzo's ATM withdrawal where
    // amount = withdrawal_amount + fee_amount), carve the fee out of the main transfer so main + fee sum
    // back to the original amount instead of double-charging the fee.
    val amount =
        if (fee != null &&
            setup.strategy.transactionMappings.feeIncludedInAmount &&
            fee.amount.asset.id == data.money.asset.id
        ) {
            Money((data.money.amount - fee.amount.amount).coerceAtLeast(BigInteger.ZERO), data.money.asset)
        } else {
            data.money
        }

    val passThrough =
        passThroughMatch?.let { match ->
            val source = counterpartyApiSource.toSource()
            val conduitRefs =
                match.accounts.map { account ->
                    AccountRef.Local(setup.accountResolver.resolveNamedAccount(account.conduitAccountName, source))
                }
            val merchantRef = AccountRef.Local(setup.accountResolver.resolveNamedAccount(match.merchantName, source))
            ConduitRouting(
                conduit = conduitRefs.first(),
                passThrough =
                    ImportPassThrough(
                        conduits = conduitRefs,
                        merchantTarget = merchantRef,
                        amount = amount,
                        spendDescriptions = match.hops.map { it.merchantText },
                        relationshipTypeId = RelationshipTypeId(match.accounts.first().relationshipTypeId),
                        incoming = data.isIncoming,
                    ),
            )
        }

    return PreparedApiTransaction(
        pageIndex = context.index,
        itemIndex = itemIndex,
        responseId = responseId,
        requestId = context.requestId,
        item = item,
        // Pass-through: funding leg own -> conduit (or conduit -> own for an incoming
        // refund/cancellation). Otherwise the normal own/counterparty pairing.
        source =
            when {
                passThrough != null -> if (data.isIncoming) passThrough.conduit else ownAccountRef
                data.isIncoming -> counterpartyRef!!
                else -> ownAccountRef
            },
        target =
            when {
                passThrough != null -> if (data.isIncoming) ownAccountRef else passThrough.conduit
                data.isIncoming -> ownAccountRef
                else -> counterpartyRef!!
            },
        timestamp = item.created,
        description = data.description,
        amount = amount,
        attributes =
            buildApiTransferAttributes(
                item = item,
                customTxFields = setup.customTxFields,
                attributeTypeCache = setup.attributeTypeCache,
            ),
        transactionApiId = transactionApiId,
        uniqueKey = uniqueKey,
        fee = fee,
        passThrough = passThrough?.passThrough,
    )
}

/** Conduit routing for a pass-through API transaction: the funding-leg conduit target + the spend leg. */
private data class ConduitRouting(
    val conduit: AccountRef,
    val passThrough: ImportPassThrough,
)

/**
 * Builds the [ImportFee] for a transaction that carries a fee: resolves the fee currency (falling back
 * to the transaction currency), gets-or-creates the consolidated "<strategy> Fees" account, and pays the
 * fee from the own account into it. Returns null when the fee currency is unknown or the fee is zero.
 */
private suspend fun buildImportFee(
    item: ApiTransactionPageItem,
    ownAccountRef: AccountRef,
    transactionCurrency: Currency,
    apiSource: AccountApiSource,
    setup: ImportSetup,
): ImportFee? {
    // Only fall back to the transaction currency when no fee currency was configured. If one WAS
    // provided but can't be resolved, skip the fee rather than persisting it under the wrong currency
    // (which would also let a feeIncludedInAmount carve-out subtract a mismatched-currency amount).
    val feeCurrency =
        when (val code = item.feeCurrencyCode?.takeIf { it.isNotBlank() }) {
            null -> transactionCurrency
            else -> setup.currencyCache.getCurrency(code) ?: return null
        }
    val feeMoney =
        when {
            item.feeAmountMinorUnits != null -> Money(item.feeAmountMinorUnits.absoluteValue, feeCurrency)
            item.feeAmountDecimalMajor != null -> Money.fromDisplayValue(item.feeAmountDecimalMajor, feeCurrency)
            else -> return null
        }
    if (feeMoney.isZero()) return null
    val feeAccountKey = setup.accountResolver.resolveNamedAccount("${setup.strategy.name} Fees", source = apiSource.toSource())
    // Point the fee's audit trail at the JSON object that holds the fee (e.g. `atm_fees_detailed`)
    // rather than the whole transaction or the bare amount leaf, by extending the transaction path with
    // the fee field's parent (its last `.`-segment dropped). A flat fee field (no parent) falls back to
    // the transaction node.
    val feeNodePath =
        setup.strategy.transactionMappings.feeAmountField
            ?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
    val feeJsonPath =
        if (feeNodePath != null) "${item.jsonPath.value}.$feeNodePath" else item.jsonPath.value
    return ImportFee(
        source = ownAccountRef,
        target = AccountRef.Local(feeAccountKey),
        amount = feeMoney,
        description = item.feeDescription?.ifBlank { null } ?: "Fee",
        relationshipTypeId = RelationshipTypeId(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID),
        rowKey = ImportRowKey.ApiJsonPath(apiSource.requestId, feeJsonPath),
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

private fun PreparedApiTransaction.responseRecord(
    state: ApiResponseTransactionState,
    transactionId: TransferId?,
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

/**
 * Resolves a transaction's decline reason, used downstream to exclude it from balances. Two shapes
 * are supported: a dedicated reason field that is only present when declined (Monzo's
 * `decline_reason`), and an always-present status field whose value flags the decline (Starling's
 * `status` == "DECLINED"). The status, when matched, doubles as the human-readable reason.
 */
private fun resolveDeclineReason(
    obj: JsonObject,
    mappings: ApiTransactionMappings,
): String? {
    val reason = mappings.declineReasonField?.let { obj.resolveJsonPath(it) }
    if (!reason.isNullOrBlank()) return reason
    val status = mappings.declineStatusField?.let { obj.resolveJsonPath(it) }
    return status?.takeIf { it in mappings.declinedStatusValues }
}

private fun parseTransactionsWithPath(
    json: String,
    strategy: ApiImportStrategy,
): List<ApiTransactionPageItem> =
    try {
        val mappings = strategy.transactionMappings
        responseItemsArray(json, strategy.transactionsEndpoint.responseArrayKey)
            ?.mapIndexedNotNull { index, element ->
                val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                val created = obj.resolveJsonPath(mappings.timestampField)?.let { parseApiTimestamp(it, mappings.timestampFormat) }
                val amount = parseAmount(obj, mappings)
                val currency = obj.resolveJsonPath(mappings.currencyField)
                val declineReason = resolveDeclineReason(obj, mappings)
                val localAmount = mappings.localAmountField?.let { obj.resolveJsonPath(it) }?.toLongOrNull()
                val localCurrency = mappings.localCurrencyField?.let { obj.resolveJsonPath(it) }
                val fee = parseFeeAmount(obj, mappings)
                val feeCurrency = mappings.feeCurrencyField?.let { obj.resolveJsonPath(it) }
                val feeDescription = mappings.feeDescriptionField?.let { obj.resolveJsonPath(it) }
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
                        feeAmountMinorUnits = fee?.minorUnits,
                        feeAmountDecimalMajor = fee?.decimalMajor,
                        feeCurrencyCode = feeCurrency?.uppercase(),
                        feeDescription = feeDescription,
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
 * Parses the optional fee amount per the strategy's [ApiAmountFormat], as a magnitude (sign is
 * irrelevant — a fee is always money out of the own account). Returns null when no fee field is
 * configured, the field is absent/unparseable, or the fee is zero.
 */
private fun parseFeeAmount(
    obj: JsonObject,
    mappings: ApiTransactionMappings,
): ParsedAmount? {
    val field = mappings.feeAmountField ?: return null
    val raw = obj.resolveJsonPath(field) ?: return null
    return when (mappings.amountFormat) {
        ApiAmountFormat.MINOR_UNITS_INTEGER -> {
            val value = raw.toLongOrNull() ?: return null
            if (value == 0L) return null
            ParsedAmount(minorUnits = value.absoluteValue, decimalMajor = null, sign = 0)
        }
        ApiAmountFormat.DECIMAL_MAJOR_UNITS -> {
            val decimal = runCatching { BigDecimal(raw) }.getOrNull() ?: return null
            if (decimal.compareTo(BigDecimal.ZERO) == 0) return null
            ParsedAmount(minorUnits = null, decimalMajor = decimal.abs(), sign = 0)
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
internal fun arrayItemJsonPath(
    responseArrayKey: String,
    index: Int,
): JsonPath = if (responseArrayKey.isBlank()) JsonPath("$[$index]") else JsonPath("$.$responseArrayKey[$index]")

/**
 * Extracts the items array from a response body; a blank [responseArrayKey] means the body is the
 * array. A bare JSON object body (e.g. a single-resource endpoint like Starling's account holder) is
 * wrapped as a one-element array so single-object responses parse like any other.
 */
internal fun responseItemsArray(
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
            // Supports a dot-path key so nested envelopes (e.g. Crypto.com "result.data") resolve.
            root.resolveJsonPathElement(responseArrayKey) as? JsonArray
        }
    } catch (e: SerializationException) {
        logger.error(e) { "Failed to parse API response array (key='$responseArrayKey')" }
        null
    }

/**
 * Whether a response passed its envelope status check. Strategies with a [successCodeField]
 * (Crypto.com "code") require the value at that path to equal [successCodeOkValue] (e.g. "0");
 * a null field means no check (bank APIs rely on the HTTP status alone).
 */
internal fun responseCodeOk(
    json: String,
    successCodeField: String?,
    successCodeOkValue: String?,
): Boolean {
    if (successCodeField == null) return true
    // Fail closed: a configured status field with no expected value must never pass (an absent code
    // would otherwise equal a null expected value and let an error envelope through).
    val expected = successCodeOkValue ?: return false
    return try {
        val actual =
            (Json.parseToJsonElement(json).resolveJsonPathElement(successCodeField) as? JsonPrimitive)?.contentOrNull
        actual == expected
    } catch (e: SerializationException) {
        false
    }
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

internal data class ApiDateWindow(
    val start: Instant,
    val end: Instant,
)

/**
 * Produces the date windows to fetch, anchored to fixed epoch boundaries so earlier windows yield
 * stable, cacheable URLs across re-imports; only the final window (ending [now]) shifts.
 */
internal fun dateWindows(
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
) {
    fun toSource(): Source = Source.Api(sessionId, requestId, jsonPath)
}

/**
 * Pure, in-memory builder of [ImportAccountIntent]s. Replaces the DB-writing `AccountCache`: it never
 * touches the database. It allocates one stable [LocalAccountKey] per distinct account and accumulates
 * an intent (with its match key + attributes) the first time it sees that account; callers wrap the
 * returned key in [AccountRef.Local]. All DB-existing dedupe (by name, by attribute, by bank key) and
 * all account/attribute writes are performed by the [ImportEngine] from the assembled [ImportBatch].
 *
 * Only IN-BATCH dedupe lives here, via the in-memory indices below — so two transactions that name the
 * same counterparty share one intent (and thus one created account). Cross-key in-batch merging mirrors
 * the old cache: a source account and a counterparty that share a sort-code/account-number bank key
 * collapse to a single key. Concurrency-safe via [mutex]; counterparty keys are allocated from the
 * concurrent transaction-preparation section, own/fee/void keys from the serial sections.
 */
private class BatchAccountResolver {
    private val mutex = Mutex()
    private var keyCounter = 0
    private val intents = LinkedHashMap<LocalAccountKey, ImportAccountIntent>()

    // In-batch dedupe indices (key value -> the allocated LocalAccountKey).
    private val byExternalId = mutableMapOf<String, LocalAccountKey>()
    private val byPersonalKey = mutableMapOf<String, LocalAccountKey>()
    private val byBuiltInType = mutableMapOf<String, LocalAccountKey>()
    private val byName = mutableMapOf<String, LocalAccountKey>()

    // Collapses counterparties that carry no stable id and no bank identity (e.g. Monzo transfers with
    // only an ephemeral anonuser id) onto one account when they share a normalised name. People key on
    // [counterpartyNameKey] (case + middle-name insensitive); businesses key on their full normalised
    // name so unrelated orgs never collide.
    private val byNameKey = mutableMapOf<String, LocalAccountKey>()

    /** The accumulated intents, in allocation order, for [ImportBatch.accountsToCreate]. */
    suspend fun intents(): List<ImportAccountIntent> = mutex.withLock { intents.values.toList() }

    private fun allocateKey(): LocalAccountKey = LocalAccountKey("acct-${++keyCounter}")

    private fun externalIdAttr(value: String) = NewAttribute(typeId = ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID, value = value)

    private fun bankAttrs(
        sortCode: String,
        accountNumber: String,
    ): List<NewAttribute> =
        listOf(
            NewAttribute(typeId = ACCOUNT_SORT_CODE_ATTR_TYPE_ID, value = sortCode),
            NewAttribute(typeId = ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID, value = accountNumber),
        )

    /** Adds attributes (deduped by type+value) onto an already-allocated intent. */
    private fun mergeAttributes(
        key: LocalAccountKey,
        newAttrs: List<NewAttribute>,
    ) {
        if (newAttrs.isEmpty()) return
        val intent = intents[key] ?: return
        val existing = intent.attributes
        val merged =
            existing +
                newAttrs.filter { attr -> existing.none { it.typeId == attr.typeId && it.value == attr.value } }
        if (merged.size != existing.size) intents[key] = intent.copy(attributes = merged)
        // Register any bank attributes on the personal-key index so a later personal-counterparty
        // match (or a source account with the same bank key) collapses onto this same intent.
        val sortCode = merged.firstOrNull { it.typeId == ACCOUNT_SORT_CODE_ATTR_TYPE_ID }?.value
        val accountNumber = merged.firstOrNull { it.typeId == ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID }?.value
        if (!sortCode.isNullOrBlank() && !accountNumber.isNullOrBlank()) {
            byPersonalKey.getOrPut(personalCounterpartyKey(sortCode, accountNumber)) { key }
        }
    }

    /**
     * Resolves the [LocalAccountKey] for a source/own account identified by its external id, allocating an
     * intent with [AccountMatchKey.ByExternalId] on first sight. Bank details (when known) are written as
     * sort-code/account-number attributes so cross-provider bank reconciliation works. If a counterparty
     * with the same bank key was already allocated in this batch, the source account collapses onto it.
     */
    suspend fun resolveSourceAccount(
        externalId: String,
        name: String,
        source: Source,
        sortCode: String? = null,
        accountNumber: String? = null,
    ): LocalAccountKey =
        mutex.withLock {
            val normalizedName = name.ifBlank { "Unknown" }
            val bankKey =
                if (!sortCode.isNullOrBlank() && !accountNumber.isNullOrBlank()) {
                    personalCounterpartyKey(sortCode, accountNumber)
                } else {
                    null
                }
            byExternalId[externalId]?.let { key ->
                if (bankKey != null) {
                    mergeAttributes(key, listOf(externalIdAttr(externalId)) + bankAttrs(sortCode!!, accountNumber!!))
                    byPersonalKey.getOrPut(bankKey) { key }
                }
                return@withLock key
            }
            // In-batch adoption: a counterparty already created for this same real bank account becomes the
            // source account's key (cross-provider order independence is otherwise handled DB-side by the
            // engine's personal-key index). The external-id attribute is added so this provider matches by id.
            if (bankKey != null) {
                byPersonalKey[bankKey]?.let { key ->
                    byExternalId[externalId] = key
                    mergeAttributes(key, listOf(externalIdAttr(externalId)) + bankAttrs(sortCode!!, accountNumber!!))
                    return@withLock key
                }
            }
            val key = allocateKey()
            val attributes =
                buildList {
                    add(externalIdAttr(externalId))
                    if (bankKey != null) addAll(bankAttrs(sortCode!!, accountNumber!!))
                }
            intents[key] =
                ImportAccountIntent(
                    key = key,
                    match = AccountMatchKey.ByExternalId(ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID, externalId),
                    name = normalizedName,
                    openingDate = Clock.System.now(),
                    attributes = attributes,
                    source = source,
                    // A source/own account adopts (and re-points) a pre-existing account sharing its bank
                    // identity, taking it over from whichever provider created it first.
                    adoptOnBankMatch = true,
                )
            byExternalId[externalId] = key
            if (bankKey != null) byPersonalKey.getOrPut(bankKey) { key }
            key
        }

    /** Merges a custom account-field attribute onto a source account already allocated by external id. */
    suspend fun addSourceAttribute(
        externalId: String,
        attribute: NewAttribute,
    ) = mutex.withLock {
        val key = byExternalId[externalId] ?: return@withLock
        mergeAttributes(key, listOf(attribute))
    }

    /**
     * Resolves the [LocalAccountKey] for a name-only account (no external id): the per-strategy "Fees"
     * account and the per-strategy "Void" zero-amount account. Matched by [AccountMatchKey.ByName].
     */
    suspend fun resolveNamedAccount(
        name: String,
        source: Source,
    ): LocalAccountKey =
        mutex.withLock {
            val normalizedName = name.ifBlank { "Unknown" }
            byName[normalizedName]?.let { return@withLock it }
            val key = allocateKey()
            intents[key] =
                ImportAccountIntent(
                    key = key,
                    match = AccountMatchKey.ByName(normalizedName),
                    name = normalizedName,
                    openingDate = Clock.System.now(),
                    source = source,
                )
            byName[normalizedName] = key
            key
        }

    /**
     * Resolves the [LocalAccountKey] for a counterparty account, allocating an intent with the right
     * match key on first sight and merging onto an existing in-batch key when the identity matches.
     * Mirrors the old cache's precedence: built-in type, then counterparty id, then bank key, then name.
     */
    suspend fun resolveCounterpartyAccount(
        counterpartyId: String?,
        builtInType: String?,
        name: String,
        source: Source,
        dedupeKey: String? = null,
        personalIdentity: PersonalCounterpartyIdentity? = null,
        personalCounterparty: Boolean = false,
    ): LocalAccountKey =
        mutex.withLock {
            val normalizedName = name.ifBlank { "Unknown" }
            if (builtInType != null) {
                // All built-in-type transactions (e.g. every ATM withdrawal) consolidate into one account
                // WITHIN a batch via byBuiltInType; counterpartyId is ignored so locations never split it.
                byBuiltInType[builtInType]?.let { return@withLock it }
                val key = allocateKey()
                // Cross-import matching: only when a stable counterparty id exists do we persist the
                // built-in-type attribute and match by it (so the account survives renames). Without an id
                // the account is matched by its consolidated name, so a renamed built-in account is NOT
                // reconciled against on a later import — matching the historical behaviour.
                val match =
                    if (counterpartyId != null) {
                        AccountMatchKey.ByBuiltInType(BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID, builtInType)
                    } else {
                        AccountMatchKey.ByName(normalizedName)
                    }
                val attributes =
                    buildList {
                        if (counterpartyId != null) {
                            add(NewAttribute(typeId = BUILT_IN_COUNTERPARTY_TYPE_ATTR_TYPE_ID, value = builtInType))
                            add(externalIdAttr(counterpartyId))
                        }
                    }
                intents[key] =
                    ImportAccountIntent(
                        key = key,
                        match = match,
                        name = normalizedName,
                        openingDate = Clock.System.now(),
                        attributes = attributes,
                        source = source,
                    )
                byBuiltInType[builtInType] = key
                if (counterpartyId != null) byExternalId.getOrPut(counterpartyId) { key }
                if (match is AccountMatchKey.ByName) byName.getOrPut(normalizedName) { key }
                return@withLock key
            }
            if (counterpartyId != null) byExternalId[counterpartyId]?.let { return@withLock it }
            if (dedupeKey != null) byPersonalKey[dedupeKey]?.let { return@withLock it }
            // A counterparty with no stable id and no bank identity is matched by name, so every
            // transaction to the same real person (each carrying a throwaway ephemeral id) collapses
            // onto one account instead of fragmenting per transaction. Middle-name collapsing is applied
            // only to people (so "NIKOLAY IVANOV METCHEV" == "Nikolay Metchev"); businesses key on their
            // full name so unrelated orgs ("The Coffee Shop Ltd", "The Bike Shop Ltd") never collide.
            val nameKey =
                when {
                    counterpartyId != null || dedupeKey != null || name.isBlank() -> null
                    personalCounterparty -> counterpartyNameKey(normalizedName)
                    else -> normalizeNameKey(normalizedName)
                }
            if (nameKey != null) byNameKey[nameKey]?.let { return@withLock it }

            val key = allocateKey()
            val identity =
                personalIdentity
                    ?: dedupeKey?.let { bankIdentityFromDedupeKey(it) }?.let { (sortCode, accountNumber) ->
                        PersonalCounterpartyIdentity(normalizedName, sortCode, accountNumber)
                    }
            val match =
                when {
                    counterpartyId != null -> AccountMatchKey.ByExternalId(ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID, counterpartyId)
                    dedupeKey != null -> AccountMatchKey.ByPersonalCounterparty(dedupeKey)
                    // Persist the normalised name key as an attribute and match on it, so an id-less,
                    // bank-less counterparty reconciles onto the same account across separate imports
                    // (not just within this batch) — the engine indexes existing accounts by this attribute.
                    nameKey != null -> AccountMatchKey.ByExternalId(ACCOUNT_COUNTERPARTY_NAME_KEY_ATTR_TYPE_ID, nameKey)
                    else -> AccountMatchKey.ByName(normalizedName)
                }
            val attributes =
                buildList {
                    if (counterpartyId != null) add(externalIdAttr(counterpartyId))
                    if (nameKey != null) add(NewAttribute(typeId = ACCOUNT_COUNTERPARTY_NAME_KEY_ATTR_TYPE_ID, value = nameKey))
                    if (identity != null) addAll(bankAttrs(identity.sortCode, identity.accountNumber))
                }
            intents[key] =
                ImportAccountIntent(
                    key = key,
                    match = match,
                    name = normalizedName,
                    openingDate = Clock.System.now(),
                    attributes = attributes,
                    source = source,
                )
            if (counterpartyId != null) byExternalId[counterpartyId] = key
            if (dedupeKey != null) byPersonalKey[dedupeKey] = key
            if (nameKey != null) byNameKey[nameKey] = key
            if (match is AccountMatchKey.ByName) byName.getOrPut(normalizedName) { key }
            key
        }
}

/**
 * Pure, in-memory builder of [ImportPersonIntent]s + [ImportOwnershipIntent]s. Replaces the DB-writing
 * people/ownership machinery (`MutablePeopleIndex` / `resolveOrCreatePerson` / `importOwnersForAccount`):
 * the engine resolves/creates people (by external id or normalised name key), writes person attributes,
 * and creates ownerships, skipping already-linked / duplicate links. Only in-batch dedupe lives here, so
 * owners that map to the same person share one [LocalPersonKey] (and thus one created person).
 */
private class BatchPeopleResolver {
    private val mutex = Mutex()
    private var keyCounter = 0
    private val intents = LinkedHashMap<LocalPersonKey, ImportPersonIntent>()
    private val ownerships = mutableListOf<ImportOwnershipIntent>()
    private val seenOwnerships = mutableSetOf<Pair<LocalPersonKey, AccountRef>>()

    // In-batch dedupe: a stable identity string (external id, bank key, or name key) -> the person key.
    private val byIdentity = mutableMapOf<String, LocalPersonKey>()

    suspend fun intents(): List<ImportPersonIntent> = mutex.withLock { intents.values.toList() }

    suspend fun ownershipIntents(): List<ImportOwnershipIntent> = mutex.withLock { ownerships.toList() }

    /**
     * Resolves (allocating if needed) the person for [owner] and records an ownership of [account].
     * Mirrors the old resolveOrCreatePerson: a usable display name is required (falling back to the bank
     * key or provider id); the provider's external id is matched/stored only when [externalIdAttributeTypeId]
     * is set. Returns silently when no usable identity exists.
     */
    suspend fun linkOwner(
        owner: ApiImportAccountOwner,
        account: AccountRef,
        externalIdAttributeTypeId: AttributeTypeId?,
        source: Source,
    ) = mutex.withLock {
        val personKey = resolvePerson(owner, externalIdAttributeTypeId, source) ?: return@withLock
        if (seenOwnerships.add(personKey to account)) {
            ownerships += ImportOwnershipIntent(personKey = personKey, account = account, source = source)
        }
    }

    /** Allocates the person intent for [owner] without recording an ownership (no linkable account). */
    suspend fun linkPersonOnly(
        owner: ApiImportAccountOwner,
        externalIdAttributeTypeId: AttributeTypeId?,
        source: Source,
    ) = mutex.withLock {
        resolvePerson(owner, externalIdAttributeTypeId, source)
        Unit
    }

    private fun resolvePerson(
        owner: ApiImportAccountOwner,
        externalIdAttributeTypeId: AttributeTypeId?,
        source: Source,
    ): LocalPersonKey? {
        val bankKey = owner.bankKey()
        val name = owner.preferredName?.trim().orEmpty()
        val rawExternalId = owner.userId.trim().ifBlank { null }
        val displayName = name.ifBlank { bankKey ?: rawExternalId ?: return null }
        val externalId = if (externalIdAttributeTypeId != null) rawExternalId else null

        // API people are matched ignoring middle names (a counterparty "Ada B Lovelace" is the same
        // person as "Ada Lovelace"): collapse to first+last before keying, so the ByNameKey the engine
        // matches against an existing person derives the same key. normalizeNameKey then trims+lowercases
        // to align with how the engine indexes existing people by their full name.
        val nameKey = normalizeNameKey(firstLastName(displayName))

        // Dedupe identity: prefer the provider external id, then the bank key, then the name key.
        val identityKey =
            when {
                externalId != null -> "ext:$externalId"
                bankKey != null -> "bank:$bankKey"
                else -> "name:$nameKey"
            }
        return byIdentity.getOrPut(identityKey) {
            val key = LocalPersonKey("person-${++keyCounter}")
            val nameParts = displayName.split(" ", limit = 2)
            val match =
                if (externalId != null && externalIdAttributeTypeId != null) {
                    // Fall back to name matching (and backfill the provider id) when no existing person
                    // carries this id, so the same person across providers isn't duplicated.
                    PersonMatchKey.ByExternalId(externalIdAttributeTypeId, externalId, nameKeyFallback = nameKey)
                } else {
                    PersonMatchKey.ByNameKey(nameKey)
                }
            intents[key] =
                ImportPersonIntent(
                    key = key,
                    match = match,
                    firstName = nameParts[0],
                    lastName = nameParts.getOrNull(1)?.ifBlank { null },
                    attributes =
                        if (externalId != null && externalIdAttributeTypeId != null) {
                            listOf(NewAttribute(typeId = externalIdAttributeTypeId, value = externalId))
                        } else {
                            emptyList()
                        },
                    source = source,
                )
            key
        }
    }
}

/**
 * The name-merge key for a counterparty account with no stable id or bank identity: first+last name,
 * lower-cased and whitespace-collapsed, so casing and middle-name variants of one person ("NIKOLAY
 * METCHEV", "Nikolay Ivanov Metchev") resolve to a single account. Mirrors the person name key.
 */
private fun counterpartyNameKey(name: String): String = normalizeNameKey(firstLastName(name))

/** Collapses a full name to "first last" (dropping any middle parts), or the single word when there's one. */
private fun firstLastName(fullName: String): String {
    val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> fullName.trim()
        parts.size == 1 -> parts.single()
        else -> "${parts.first()} ${parts.last()}"
    }
}

private class CurrencyCache(
    private val currencyRepository: CurrencyReadRepository,
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

private fun ApiImportAccount.displayName(): String = description.ifBlank { id }

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

/** The amount, description and direction of a transaction, independent of account identity. */
private data class TransferData(
    val money: Money,
    val description: String,
    val isIncoming: Boolean,
)

private fun ApiTransactionPageItem.toTransferData(currency: Currency): TransferData {
    val money =
        when {
            amountMinorUnits != null -> Money(amountMinorUnits.absoluteValue, currency)
            amountDecimalMajor != null -> Money.fromDisplayValue(amountDecimalMajor, currency)
            else -> Money(0L, currency)
        }
    return TransferData(
        money = money,
        description =
            description.ifBlank {
                merchantName?.takeIf { it.isNotBlank() } ?: counterpartyName?.takeIf { it.isNotBlank() }
                    ?: "Unknown"
            },
        isIncoming = isIncoming,
    )
}

private fun strategyAttributeNameForJsonPath(
    customTxFields: Map<String, String>,
    strategyPath: String,
): String? = customTxFields.entries.firstOrNull { it.value == strategyPath }?.key

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** Resolves a dot-notation path (e.g. "merchant.name") against this JSON object. */
private fun JsonObject.resolveJsonPath(dotPath: String): String? = (resolveJsonPathElement(dotPath) as? JsonPrimitive)?.contentOrNull

/**
 * Resolves a dot-notation path to a [JsonElement], supporting array indexing so exchange responses
 * whose items live inside nested arrays are addressable — e.g. "result.data", "data[0].price",
 * "legs[1].amount". Each segment is an optional object key followed by zero or more `[n]` indices.
 */
internal fun JsonElement.resolveJsonPathElement(dotPath: String): JsonElement? {
    if (dotPath.isEmpty()) return this
    var current: JsonElement = this
    for (rawPart in dotPath.split(".")) {
        val bracketStart = rawPart.indexOf('[')
        val key = if (bracketStart >= 0) rawPart.substring(0, bracketStart) else rawPart
        if (key.isNotEmpty()) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        if (bracketStart >= 0) {
            var rest = rawPart.substring(bracketStart)
            while (rest.startsWith("[")) {
                val end = rest.indexOf(']')
                if (end < 0) return null
                val index = rest.substring(1, end).toIntOrNull() ?: return null
                current = (current as? JsonArray)?.getOrNull(index) ?: return null
                rest = rest.substring(end + 1)
            }
            // Reject a malformed segment with trailing text after the brackets (e.g. "data[0]foo").
            if (rest.isNotEmpty()) return null
        }
    }
    return current
}

/**
 * Parses an API timestamp string per its [TimestampFormat] — ISO-8601 (bank APIs) or epoch
 * milliseconds/seconds (most exchanges), including fractional seconds (Kraken).
 */
internal fun parseApiTimestamp(
    value: String,
    format: TimestampFormat,
): Instant? =
    when (format) {
        TimestampFormat.ISO_8601 -> runCatching { Instant.parse(value) }.getOrNull()
        TimestampFormat.EPOCH_MS -> value.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }
        TimestampFormat.EPOCH_S -> value.toLongOrNull()?.let { Instant.fromEpochSeconds(it) }
        TimestampFormat.EPOCH_S_FLOAT ->
            value.toDoubleOrNull()?.let {
                val seconds = it.toLong()
                Instant.fromEpochSeconds(seconds, ((it - seconds) * 1_000_000_000L).toLong())
            }
    }

private fun JsonObject.resolveCounterpartyIdentity(
    counterpartyIdField: String?,
    peopleMappings: ApiPeopleMappings,
): String? {
    // The bank sub-entity (sort code + account number) is the most reliable account identity for
    // providers like Starling, where a single real account can otherwise be split across several
    // counterparty ids. When configured, it wins over the explicit id; the id (and then the name)
    // remain the fallback for counterparties without bank details.
    if (peopleMappings.preferBankIdentity) {
        bankIdentity(peopleMappings)?.let { return it }
    }

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

    bankIdentity(peopleMappings)?.let { return it }

    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val serviceUserNumber = counterparty.stringOrNull(peopleMappings.counterpartyServiceUserNumberField)?.takeIf { it.isNotBlank() }
    if (serviceUserNumber != null) {
        return "service_user:$serviceUserNumber"
    }

    val userId = counterparty.stringOrNull(peopleMappings.counterpartyUserIdField)?.takeIf { it.isNotBlank() }
    // An ephemeral user id (e.g. Monzo's per-transaction "anonuser_…") is not a stable identity, so it
    // must not key the counterparty account — leaving it to be matched by name keeps the same real
    // person from fragmenting into one account per transaction.
    if (userId != null && peopleMappings.ephemeralCounterpartyIdPrefixes.none { userId.startsWith(it) }) {
        return "user:$userId"
    }

    return null
}

/**
 * The counterparty's bank identity ("bank:<sortCode>:<accountNumber>") when both the sort code and
 * account number are present, else null. Resolved relative to [ApiPeopleMappings.counterpartyObjectField]
 * (blank means the transaction item itself, for providers with flat counterparty fields).
 */
private fun JsonObject.bankIdentity(peopleMappings: ApiPeopleMappings): String? {
    val counterparty = resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val sortCode = counterparty.stringOrNull(peopleMappings.counterpartySortCodeField)?.takeIf { it.isNotBlank() } ?: return null
    val accountNumber =
        counterparty.stringOrNull(peopleMappings.counterpartyAccountNumberField)?.takeIf { it.isNotBlank() } ?: return null
    return "bank:$sortCode:$accountNumber"
}

/**
 * Derives the bank dedupe key ("sortCode|accountNumber") from a counterparty id of the form
 * "bank:<sortCode>:<accountNumber>" (produced by [bankIdentity] / [resolveCounterpartyIdentity]), or
 * null for any other id. This lets a counterparty resolved purely by bank identity merge with an
 * account carrying the same sort code + account number even when it is not flagged a personal
 * beneficiary — so every representation of one real bank account collapses to a single account.
 */
private fun bankDedupeKeyFromCounterpartyId(counterpartyId: String?): String? {
    if (counterpartyId == null || !counterpartyId.startsWith("bank:")) return null
    val (sortCode, accountNumber) = counterpartyId.removePrefix("bank:").split(":").takeIf { it.size == 2 } ?: return null
    return if (sortCode.isNotBlank() && accountNumber.isNotBlank()) "$sortCode|$accountNumber" else null
}

/**
 * Splits a bank dedupe key ("sortCode|accountNumber", as produced by [PersonalCounterpartyIdentity.dedupeKey]
 * and [bankDedupeKeyFromCounterpartyId]) back into its sort code + account number, or null if it isn't one.
 * Lets a bank-identified counterparty persist its identity as sort-code/account-number attributes even when
 * it wasn't flagged a personal beneficiary, so a later import of the other provider can bank-match it.
 */
private fun bankIdentityFromDedupeKey(dedupeKey: String?): Pair<String, String>? {
    val parts = dedupeKey?.split("|")?.takeIf { it.size == 2 } ?: return null
    val (sortCode, accountNumber) = parts
    return if (sortCode.isNotBlank() && accountNumber.isNotBlank()) sortCode to accountNumber else null
}

private fun JsonObject.resolveJsonObjectPath(dotPath: String): JsonObject? {
    // A blank path means "this object" — used by providers whose counterparty fields are flat on the
    // transaction item rather than nested under a sub-object.
    if (dotPath.isBlank()) return this
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
        // A present-but-JSON-null field (e.g. Monzo sends `atm_fees_detailed: null` on every
        // non-ATM transaction) does not count as existing, otherwise every transaction matches.
        PredicateOp.EXISTS -> resolveJsonElementPath(predicate.path).let { it != null && it != JsonNull }
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
    private val importEngine: ImportEngine,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, AttributeTypeId>()

    suspend fun getOrCreate(name: String): AttributeTypeId =
        mutex.withLock { cache.getOrPut(name) { importEngine.getOrCreateAttributeType(name) } }
}
