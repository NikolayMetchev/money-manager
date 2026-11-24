package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.recordApiDownloadCoverage
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiHttpResponse
import com.moneymanager.rest.ScaParams
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Download progress counted in accounts: account [accountIndex] of [accountCount], on [page]. */
internal fun apiDownloadProgress(
    accountIndex: Int,
    accountCount: Int,
    page: Int,
    downloadedResponsePageCount: Int,
): Progress =
    Progress.counted(
        "Downloading account $accountIndex/$accountCount, page $page. " +
            "$downloadedResponsePageCount response page(s) downloaded so far.",
        done = accountIndex - 1,
        total = accountCount,
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
internal suspend fun fetchAncestorContexts(
    token: String,
    apiClient: ApiClient,
    strategy: ApiImportStrategy,
    existingRequestsByUrl: Map<String, ApiRequest>,
    existingResponsesByRequestId: Map<ApiRequestId, ApiResponse>,
    sca: ScaParams?,
): List<List<JsonObject>> {
    var contexts: List<List<JsonObject>> = listOf(emptyList())
    strategy.config.downloadedAccounts().ancestorEndpoints.forEach { endpoint ->
        val next = mutableListOf<List<JsonObject>>()
        for (ancestors in contexts) {
            val url = buildEndpointRequestUrl(strategy.config.baseUrl, endpoint, ImportUrlContext(ancestorItems = ancestors))
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
 * provided).
 *
 * Incremental on two levels: pages whose URL is already stored in [sessionId] are skipped, which lets
 * an interrupted download resume; and [watermarks] — how far earlier sessions of the same credential
 * already reached, keyed by [transactionsEndpointKey] — moves each account's start forward, clamping
 * the date-window sweep and ending cursor paging early. Pass empty [watermarks] (or
 * [forceFullDownload]) to fetch the whole configured history from scratch.
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
    importEngine: ImportEngine,
    watermarks: Map<String, Instant> = emptyMap(),
    forceFullDownload: Boolean = false,
    onProgress: (Progress) -> Unit = {},
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
    val feed = strategy.config.bankFeed().endpoint
    val now = Clock.System.now()
    var earliestIncrementalStart: Instant? = null

    accountEntries.forEachIndexed { index, (account, ancestorVars) ->
        val endpointKey = transactionsEndpointKey(strategy, account.id)
        val walk =
            walkPages(
                pagination = feed.pagination,
                now = now,
                since = if (forceFullDownload) null else watermarks[endpointKey],
                fetch = { page ->
                    onProgress(
                        apiDownloadProgress(
                            accountIndex = index + 1,
                            accountCount = accountEntries.size,
                            page = page.windowIndex + 1,
                            downloadedResponsePageCount = transactionResponseCount,
                        ),
                    )
                    val ctx =
                        ImportUrlContext(
                            account = account,
                            ancestorVars = ancestorVars,
                            windowStart = page.window?.start?.toString(),
                            windowEnd = page.window?.end?.toString(),
                        )
                    val url = buildBankFeedUrl(strategy, feed, ctx, page)
                    // An interrupted or rate-limited request may have no response or a saved error body.
                    // Only a response containing the feed array can satisfy a retry from the cache.
                    val cached = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }?.json
                    val cachedItems = cached?.let { responseItemsArray(it, feed.responseArrayKey) }
                    if (cached != null && cachedItems != null) {
                        PageOutcome.Fetched(cached, cachedItems.filterIsInstance<JsonObject>())
                    } else {
                        val body = fetchResponse(url = url, token = token, apiClient = apiClient, sca = sca).body
                        transactionResponseCount += 1
                        PageOutcome.Fetched(body, responseItemsArray(body, feed.responseArrayKey).orEmpty().filterIsInstance<JsonObject>())
                    }
                },
                onUnitComplete = { coveredUntil -> importEngine.recordApiDownloadCoverage(sessionId, endpointKey, coveredUntil) },
            )
        walk.incrementalStart?.let { earliestIncrementalStart = minOf(it, earliestIncrementalStart ?: it) }
    }

    return ApiTransactionsDownloadResult(
        accountCount = accountEntries.size,
        transactionResponseCount = transactionResponseCount,
        incrementalSince = earliestIncrementalStart,
    )
}

/**
 * The incremental-watermark key for one account's transactions feed. Per-account, because each account
 * pages independently and one account's coverage says nothing about another's.
 */
internal fun transactionsEndpointKey(
    strategy: ApiImportStrategy,
    accountExternalId: String,
): String = "${strategy.config.bankFeed().endpoint.path}|$accountExternalId"

/**
 * Downloads the per-account identifiers endpoint (the account's own sort code + account number) into
 * [sessionId], one request per account. Only applies to strategies that configure
 * [com.moneymanager.domain.model.apistrategy.ApiAccountsSource.Downloaded.identifiersEndpoint] (e.g.
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
    val endpoint = strategy.config.downloadedAccounts().identifiersEndpoint ?: return ApiAccountIdentifiersDownloadResult(0, skipped = true)

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
        val url = buildEndpointRequestUrl(strategy.config.baseUrl, endpoint, ctx)
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
    val config = strategy.config.peopleDownload ?: return ApiPeopleDownloadResult(personCount = 0, skipped = true)
    val existingRequestsByUrl = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.url }
    val existingResponsesByRequestId = apiSessionRepository.getResponsesBySession(sessionId).associateBy { it.requestId }

    val url = buildEndpointRequestUrl(strategy.config.baseUrl, config.endpoint, ImportUrlContext())
    val existingResponse = existingRequestsByUrl[url]?.let { existingResponsesByRequestId[it.id] }
    val json = existingResponse?.json ?: fetchResponse(url = url, token = token, apiClient = apiClient, sca = sca).body
    val count = responseItemsArray(json, config.endpoint.responseArrayKey)?.count { it is JsonObject } ?: 0
    return ApiPeopleDownloadResult(personCount = count, skipped = existingResponse != null)
}

internal suspend fun fetchResponse(
    url: String,
    token: String,
    apiClient: ApiClient,
    sca: ScaParams?,
): ApiHttpResponse {
    var response = apiClient.get(url = url, bearerToken = token, sca = sca)
    var retries = 0
    while (response.statusCode == 429 && retries < 3) {
        val retryAfter = response.retryAfterSeconds?.takeIf { it in 0L..300L } ?: break
        delay(retryAfter.seconds)
        response = apiClient.get(url = url, bearerToken = token, sca = sca)
        retries += 1
    }
    if (response.statusCode == 200) return response
    if (response.statusCode == HTTP_UNAUTHORIZED) throw ApiAuthException("HTTP ${response.statusCode}: ${response.body}")
    throw ApiSessionImportException("HTTP ${response.statusCode}: ${response.body}")
}

open class ApiSessionImportException(
    message: String,
) : Exception(message)

/** The provider rejected the access token (HTTP 401): it expired or was revoked, so a person must create a new one. */
class ApiAuthException(
    message: String,
) : ApiSessionImportException(message)

internal const val HTTP_UNAUTHORIZED = 401
