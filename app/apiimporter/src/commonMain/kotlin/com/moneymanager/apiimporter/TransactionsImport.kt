package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.transactionIdAttributeName
import com.moneymanager.importengineapi.uniqueIdentifierAttributeNames
import com.moneymanager.tasks.Progress
import kotlinx.serialization.json.JsonObject

suspend fun importApiSessionTransactions(
    apiSessionRepository: ApiSessionReadRepository,
    currencyRepository: CurrencyReadRepository,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId? = null,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    // The database's asset aliases, so a currency the feed writes as an alias (`POUND STERLING`) resolves.
    assetAliases: Map<String, String> = emptyMap(),
    merchantMappings: MerchantMappings = MerchantMappings.NONE,
    onProgress: suspend (Progress) -> Unit = {},
): ApiSessionImportResult {
    val setup =
        setupImportSession(
            apiSessionRepository = apiSessionRepository,
            currencyRepository = currencyRepository,
            sessionId = sessionId,
            accountsSessionId = accountsSessionId,
            strategy = strategy,
            importEngine = importEngine,
            assetAliases = assetAliases,
            onProgress = onProgress,
            passThroughDetector = passThroughAccounts.takeIf { it.isNotEmpty() }?.let { PassThroughDetector(it) },
            merchantMappings = merchantMappings,
        )
    onProgress(Progress(detail = "Preparing import session...", fraction = 0.05f))
    // Build the import model: allocate account/counterparty/people/ownership intents and the transfers, then hand
    // the batch to the engine, which performs every entity write. Attribute types are the exception: the caches
    // create them through the engine as they are first needed, so a failure in preparation can leave some behind.
    ensureSourceAccounts(setup)
    precreateCounterparties(
        transactionResponses = setup.transactionResponses,
        sessionId = setup.sessionId,
        requestsById = setup.requestsById,
        strategy = setup.strategy,
        accountResolver = setup.accountResolver,
        counterpartyIdField = setup.counterpartyIdField,
        counterpartyAccountNames = counterpartyAccountNames,
    )
    onProgress(Progress(detail = "Counterparties prepared.", fraction = 0.2f))
    val preparedTransfers = prepareTransactionTransfers(setup)
    onProgress(Progress(detail = "Transactions prepared. Processing people...", fraction = 0.6f))
    addCustomAccountFieldAttributes(setup)
    buildPeopleAndOwnershipIntents(setup)
    onProgress(Progress(detail = "Saving to database...", fraction = 0.7f))
    val importResult = runImportEngine(setup, preparedTransfers)
    val folded = importEngine.syncSessionMatches(apiSessionRepository, sessionId)
    onProgress(Progress(detail = "Import finalized.", fraction = 0.98f))
    return ApiSessionImportResult(
        accountCount = setup.accountsById.size,
        transactionCount = importResult.transfersImported - folded.transfers,
        personCount = importResult.peopleCreated,
        duplicateCount = importResult.duplicates + folded.total,
        errorCount = preparedTransfers.errorCount,
    )
}

/**
 * Allocates an `ImportAccountIntent` for every downloaded account up front, so a counterparty match
 * (e.g. a self-transfer to another of the user's own Monzo accounts) can resolve onto it even though it
 * has no transaction feed of its own — and so it merges with any counterparty another provider already
 * created for the same real account via a shared bank identity. Its own bank details (e.g. from
 * Starling's identifiers endpoint) are recorded as sort-code/account-number attributes for that match.
 * Idempotent. An account nothing ends up referencing by the end of the import (own feed empty, and no
 * other account's feed mentions it either — e.g. Monzo's read-only `uk_rewards` pseudo-account) is
 * dropped by [BatchAccountResolver.pruneUnreferencedSourceAccounts] before the batch is built.
 */
internal suspend fun ensureSourceAccounts(setup: ImportSetup) {
    for (account in setup.accountsById.values) {
        resolveOwnAccountKey(setup, account)
    }
}

/**
 * The own account's bank details (sort code, account number), taken from the account itself (filled
 * directly from the accounts response or from the account-identifiers endpoint) or, failing that, from
 * its owners. Either element is null when absent.
 *
 * The pair is preferred from a SINGLE source — the account's own fields, else the first owner supplying
 * both — before falling back to mixing an account-level sort code with an owner-level account number.
 * A mixed pair is a bank identity that belongs to no real account, and now that the pair also seeds an
 * attribute group key, a bogus one would persist as a group and be upserted on every later import
 * rather than merely mis-keying a single match.
 */
internal fun ApiImportAccount.bankDetails(): Pair<String?, String?> {
    val ownSortCode = sortCode?.takeIf { it.isNotBlank() }
    val ownAccountNumber = accountNumber?.takeIf { it.isNotBlank() }
    if (ownSortCode != null && ownAccountNumber != null) return ownSortCode to ownAccountNumber

    val completeOwner =
        owners.firstOrNull { !it.sortCode.isNullOrBlank() && !it.accountNumber.isNullOrBlank() }
    if (completeOwner != null) return completeOwner.sortCode to completeOwner.accountNumber

    // Last resort: neither the account nor any single owner has a complete pair, so fill each half
    // independently and accept that they may come from different objects.
    return (ownSortCode ?: owners.firstOrNull { !it.sortCode.isNullOrBlank() }?.sortCode) to
        (ownAccountNumber ?: owners.firstOrNull { !it.accountNumber.isNullOrBlank() }?.accountNumber)
}

/** All state created during setup that is shared across the import steps. */
internal data class ImportSetup(
    val strategy: ApiImportStrategy,
    val sessionId: ApiSessionId,
    val accountsById: Map<String, ApiImportAccount>,
    val accountApiSourceByExternalId: Map<String, AccountApiSource>,
    val transactionResponses: List<ApiResponse>,
    val requestsById: Map<ApiRequestId, ApiRequest>,
    val counterpartyIdField: String?,
    /** The attribute types the bank feed's rules key an item by (unique-identifier attributes), in rule order. */
    val uniqueAttributeNames: List<String>,
    /** The attribute type whose value is the item's own `id`, so an existing transfer's api id can be read back. */
    val transactionIdAttributeName: String?,
    val accountResolver: BatchAccountResolver,
    val peopleResolver: BatchPeopleResolver,
    val currencyCache: CurrencyCache,
    val attributeTypeCache: AttributeTypeCache,
    val onProgress: suspend (Progress) -> Unit,
    val apiSessionRepository: ApiSessionReadRepository,
    val importEngine: ImportEngine,
    /** Detects pass-through (conduit) charges (e.g. Curve) from a transaction description; null disables it. */
    val passThroughDetector: PassThroughDetector? = null,
    /** The global account mappings a pass-through merchant is routed by before an account is created for it. */
    val merchantMappings: MerchantMappings = MerchantMappings.NONE,
)

internal suspend fun setupImportSession(
    apiSessionRepository: ApiSessionReadRepository,
    currencyRepository: CurrencyReadRepository,
    sessionId: ApiSessionId,
    accountsSessionId: ApiSessionId?,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
    assetAliases: Map<String, String>,
    onProgress: suspend (Progress) -> Unit,
    passThroughDetector: PassThroughDetector?,
    merchantMappings: MerchantMappings,
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
    requireDistinctExternalIds(parsedAccountsById.values, strategy)
    // Fold in per-account bank details fetched from the account-identifiers endpoint (e.g. Starling),
    // whose responses live alongside the transaction/account traffic of either session.
    val identifierResponsePairs =
        responses.mapNotNull { response -> requestsById[response.requestId]?.let { it to response } } +
            accountsResponses.mapNotNull { response -> accountsRequestsById[response.requestId]?.let { it to response } }
    val accountsById = enrichAccountsWithIdentifiers(parsedAccountsById, strategy, identifierResponsePairs)
    val transactionResponses = responses.filter { requestsById[it.requestId]?.resolveAccountExternalId(strategy) != null }

    val currencyCache = CurrencyCache(currencyRepository, assetAliases)
    val attributeTypeCache = AttributeTypeCache(importEngine)
    val transactionRules = strategy.config.bankFeedMappings().rules
    val counterpartyIdField = strategy.config.bankFeedMappings().counterpartyIdField

    // Pre-create attribute types before the concurrent section so that no two coroutines race to write
    // the same type, which causes SQLITE_BUSY.
    for (rules in listOf(
        transactionRules,
        strategy.config
            .downloadedAccounts()
            .mappings.rules,
    )) {
        for (attribute in rules.attributeActions()) attributeTypeCache.getOrCreate(attribute.typeName)
    }

    onProgress(Progress(detail = "Reading downloaded API responses..."))

    return ImportSetup(
        strategy = strategy,
        sessionId = sessionId,
        accountsById = accountsById,
        accountApiSourceByExternalId = accountApiSourceByExternalId,
        transactionResponses = transactionResponses,
        requestsById = requestsById,
        counterpartyIdField = counterpartyIdField,
        uniqueAttributeNames = strategy.uniqueIdentifierAttributeNames(),
        transactionIdAttributeName = strategy.transactionIdAttributeName(),
        accountResolver = BatchAccountResolver(),
        peopleResolver = BatchPeopleResolver(),
        currencyCache = currencyCache,
        attributeTypeCache = attributeTypeCache,
        onProgress = onProgress,
        apiSessionRepository = apiSessionRepository,
        importEngine = importEngine,
        passThroughDetector = passThroughDetector,
        merchantMappings = merchantMappings,
    )
}

/** Adds the attributes the account rules set on each downloaded account to its source account's intent. */
internal suspend fun addCustomAccountFieldAttributes(setup: ImportSetup) {
    for (account in setup.accountsById.values) {
        for (attribute in account.attributes) {
            val typeId = setup.attributeTypeCache.getOrCreate(attribute.typeName)
            setup.accountResolver.addSourceAttribute(account.externalId, NewAttribute(typeId = typeId, value = attribute.value))
        }
    }
}

/**
 * Builds the people + ownership intents for this import: account holders (from the accounts payload),
 * personal counterparties (from the transaction feed), and the global account holder (people-download
 * endpoint) linked to every own account. The engine resolves/creates the people and ownerships.
 */
internal suspend fun buildPeopleAndOwnershipIntents(setup: ImportSetup) {
    val externalIdAttributeTypeId =
        setup.strategy.config.personExternalIdAttribute
            ?.let { setup.attributeTypeCache.getOrCreate(it) }
    buildPeopleFromAccounts(setup, externalIdAttributeTypeId)
    buildPeopleFromCounterparties(setup, externalIdAttributeTypeId)
    buildGlobalHolderOwnerships(setup, externalIdAttributeTypeId)
}

/** Links each downloaded account's declared owners to that account's intent. */
internal suspend fun buildPeopleFromAccounts(
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
internal suspend fun buildPeopleFromCounterparties(
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
internal suspend fun linkCounterpartyOwner(
    setup: ImportSetup,
    externalIdAttributeTypeId: AttributeTypeId?,
    request: ApiRequest,
    item: ApiTransactionPageItem,
) {
    if (item.isZeroAmount || item.hasFixedCounterparty) return
    val owner = item.personalCounterpartyOwner(setup.strategy.config.peopleMappings) ?: return
    val identity = owner.personalCounterpartyIdentity()
    val counterpartyId = item.counterpartyIdentity(setup.counterpartyIdField, setup.strategy.config.peopleMappings)
    val counterpartyName =
        identity?.name ?: owner.preferredName?.takeIf { it.isNotBlank() } ?: item.counterpartyName()
    if (counterpartyId == null && identity == null && counterpartyName.isBlank()) return
    val counterpartyApiSource =
        AccountApiSource(setup.sessionId, request.id, item.counterpartyJsonPath(setup.strategy.config.peopleMappings))
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
internal suspend fun buildGlobalHolderOwnerships(
    setup: ImportSetup,
    externalIdAttributeTypeId: AttributeTypeId?,
) {
    val config = setup.strategy.config.peopleDownload ?: return
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

/**
 * Resolves (allocating if needed) the [LocalAccountKey] for a downloaded own account, with its bank
 * details. When the account rules give two accounts the same name and they have no bank details to
 * disambiguate them, the import engine's own-account matching falls back to reusing an existing
 * same-named account from this batch (a heuristic meant for merchant/counterparty dedup, e.g. one
 * provider minting two external ids for one merchant) — which would wrongly merge two genuinely
 * distinct own accounts. The sibling accounts let [displayName] detect that and append a short
 * disambiguator only when it's actually needed.
 */
internal suspend fun resolveOwnAccountKey(
    setup: ImportSetup,
    account: ApiImportAccount,
): LocalAccountKey {
    val (sortCode, accountNumber) = account.bankDetails()
    return setup.accountResolver.resolveSourceAccount(
        externalId = account.externalId,
        name = account.displayName(setup.accountsById.values),
        sortCode = sortCode,
        accountNumber = accountNumber,
        source = setup.accountApiSourceByExternalId[account.id]?.toSource() ?: Source.Api(setup.sessionId),
    )
}
