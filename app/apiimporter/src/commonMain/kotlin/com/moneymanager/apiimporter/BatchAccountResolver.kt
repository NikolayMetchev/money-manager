package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
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
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.NamedAccount
import com.moneymanager.domain.model.rules.Record
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleAttribute
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleEvaluator
import com.moneymanager.domain.model.rules.RuleOutcome
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.AttributeSlot
import com.moneymanager.importengineapi.DedupePolicy
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ExistingUniqueKeyExtractor
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportConversion
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportFee
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPassThrough
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PassThroughDetector
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.bankKeysFrom
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.normalizeNameKey
import com.moneymanager.importengineapi.personalCounterpartyKey
import com.moneymanager.importengineapi.recordApiDownloadCoverage
import com.moneymanager.importengineapi.transactionIdAttributeName
import com.moneymanager.importengineapi.uniqueIdentifierAttributeNames
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiHttpResponse
import com.moneymanager.rest.ScaParams
import com.moneymanager.tasks.Progress
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal data class AccountApiSource(
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
internal class BatchAccountResolver {
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

    // Keys allocated by [resolveSourceAccount], tracked so [pruneUnreferencedSourceAccounts] can drop
    // ones no prepared transfer ever referenced (e.g. Monzo's read-only `uk_rewards` pseudo-account,
    // whose feed is always empty). Counterparty-only keys are never in here, so pruning can't touch them.
    private val sourceAccountKeys = mutableSetOf<LocalAccountKey>()

    // The fee account intent of each own account, by that account.
    private val feeAccounts = mutableMapOf<AccountRef, LocalAccountKey>()

    /** The accumulated intents, in allocation order, for [ImportBatch.accountsToCreate]. */
    suspend fun intents(): List<ImportAccountIntent> = mutex.withLock { intents.values.toList() }

    private fun allocateKey(): LocalAccountKey = LocalAccountKey("acct-${++keyCounter}")

    private fun externalIdAttr(value: String) = NewAttribute(typeId = ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID, value = value)

    /**
     * The sort-code + account-number pair for one bank identity, always emitted TOGETHER under one shared
     * grouping tag. This is the sole point that produces bank details, so the invariant "an API import
     * never stores a sort code and account number separately" holds here: both halves carry the same tag,
     * and the engine maps stored `group_key`s by tag, so both land under the same UUID — even if bank-key
     * derivation ever returned null, because the grouping follows the tag, not the derived value. An account
     * can legitimately own several identities (Crypto.com moved its fiat destination to a new sort code);
     * the tag keeps each identity's two halves bound so the engine never pairs one identity's sort code
     * with another's account number.
     *
     * The tag is the identity's natural key. It is transient — it only groups the attributes within this
     * batch and is never persisted; the engine translates it to an opaque UUID on write.
     */
    private fun bankAttrs(
        sortCode: String,
        accountNumber: String,
    ): List<NewAttribute> {
        val group = personalCounterpartyKey(sortCode, accountNumber)
        return listOf(
            NewAttribute(typeId = ACCOUNT_SORT_CODE_ATTR_TYPE_ID, value = sortCode, groupKey = group),
            NewAttribute(typeId = ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID, value = accountNumber, groupKey = group),
        )
    }

    /** Adds attributes (deduped by type+value+group) onto an already-allocated intent. */
    private fun mergeAttributes(
        key: LocalAccountKey,
        newAttrs: List<NewAttribute>,
    ) {
        if (newAttrs.isEmpty()) return
        val intent = intents[key] ?: return
        val existing = intent.attributes
        val merged =
            existing +
                newAttrs.filter { attr ->
                    existing.none { it.typeId == attr.typeId && it.value == attr.value && it.groupKey == attr.groupKey }
                }
        if (merged.size != existing.size) intents[key] = intent.copy(attributes = merged)
        // Register EVERY bank identity on the personal-key index so a later personal-counterparty match (or
        // a source account with any of these bank keys) collapses onto this same intent. This is where one
        // intent accumulates a second identity, so scanning per group matters twice over: keying off the
        // first sort code alone would leave the second identity unregistered, and pairing across groups
        // would invent a key belonging to no real account.
        bankKeysFrom(merged.map { AttributeSlot(it.typeId.id, it.value, it.groupKey) })
            .forEach { bankKey -> byPersonalKey.getOrPut(bankKey) { key } }
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
                sourceAccountKeys += key
                return@withLock key
            }
            // In-batch adoption: a counterparty already created for this same real bank account becomes the
            // source account's key (cross-provider order independence is otherwise handled DB-side by the
            // engine's personal-key index). The external-id attribute is added so this provider matches by id.
            if (bankKey != null) {
                byPersonalKey[bankKey]?.let { key ->
                    byExternalId[externalId] = key
                    mergeAttributes(key, listOf(externalIdAttr(externalId)) + bankAttrs(sortCode!!, accountNumber!!))
                    sourceAccountKeys += key
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
            sourceAccountKeys += key
            key
        }

    /**
     * Removes source-account intents that ended up referenced by no prepared transfer leg (e.g. Monzo's
     * read-only `uk_rewards` pseudo-account, whose transaction feed is always empty) from [referenced].
     * Counterparty intents are never candidates — only keys [resolveSourceAccount] allocated are tracked
     * in [sourceAccountKeys] — so this can't prune an account real money moved through. Cleans every
     * dedupe index too, so a pruned key can't be resurrected by a later lookup. Returns the pruned keys
     * so callers can drop ownerships/attributes that referenced them.
     */
    suspend fun pruneUnreferencedSourceAccounts(referenced: Set<LocalAccountKey>): Set<LocalAccountKey> =
        mutex.withLock {
            val toPrune = sourceAccountKeys - referenced
            if (toPrune.isEmpty()) return@withLock emptySet()
            intents.keys.removeAll(toPrune)
            byExternalId.values.removeAll(toPrune)
            byPersonalKey.values.removeAll(toPrune)
            byBuiltInType.values.removeAll(toPrune)
            byName.values.removeAll(toPrune)
            byNameKey.values.removeAll(toPrune)
            sourceAccountKeys -= toPrune
            toPrune
        }

    /** Merges a custom account-field attribute onto a source account already allocated by external id. */
    suspend fun addSourceAttribute(
        externalId: String,
        attribute: NewAttribute,
    ) = mutex.withLock {
        val key = byExternalId[externalId] ?: return@withLock
        mergeAttributes(key, listOf(attribute))
    }

    /** The fee account of [account] ([AccountMatchKey.FeeAccountOf]), one intent per account. */
    suspend fun resolveFeeAccount(
        account: AccountRef,
        source: Source,
    ): LocalAccountKey =
        mutex.withLock {
            feeAccounts.getOrPut(account) {
                allocateKey().also { key ->
                    intents[key] =
                        ImportAccountIntent(
                            key = key,
                            match = AccountMatchKey.FeeAccountOf(account),
                            openingDate = Clock.System.now(),
                            source = source,
                        )
                }
            }
        }

    /**
     * Resolves the [LocalAccountKey] for a name-only account (no external id), such as the per-strategy "Void"
     * zero-amount account. Matched by [AccountMatchKey.ByName].
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
                    // A name-keyed counterparty's only identity is its name, so it takes over the same-named
                    // account a CSV export created for it (one with no identity of its own) rather than forking
                    // "Tesco (tesco)" beside it: the two records of each payment then land on one account and join.
                    adoptOnBankMatch = nameKey != null,
                )
            if (counterpartyId != null) byExternalId[counterpartyId] = key
            if (dedupeKey != null) byPersonalKey[dedupeKey] = key
            if (nameKey != null) byNameKey[nameKey] = key
            if (match is AccountMatchKey.ByName) byName.getOrPut(normalizedName) { key }
            key
        }
}
