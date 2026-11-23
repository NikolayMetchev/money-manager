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

/**
 * Pure, in-memory builder of [ImportPersonIntent]s + [ImportOwnershipIntent]s. Replaces the DB-writing
 * people/ownership machinery (`MutablePeopleIndex` / `resolveOrCreatePerson` / `importOwnersForAccount`):
 * the engine resolves/creates people (by external id or normalised name key), writes person attributes,
 * and creates ownerships, skipping already-linked / duplicate links. Only in-batch dedupe lives here, so
 * owners that map to the same person share one [LocalPersonKey] (and thus one created person).
 */
internal class BatchPeopleResolver {
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
internal fun counterpartyNameKey(name: String): String = normalizeNameKey(firstLastName(name))

/** Collapses a full name to "first last" (dropping any middle parts), or the single word when there's one. */
internal fun firstLastName(fullName: String): String {
    val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> fullName.trim()
        parts.size == 1 -> parts.single()
        else -> "${parts.first()} ${parts.last()}"
    }
}
