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

private val logger = Logger.withTag("ApiImportAccounts")

internal data class ApiImportAccount(
    /** The provider's id, as requests carry it (`{account.id}`). */
    val id: String,
    /** The id stored on (and matched against) the account: the rules' [RuleAction.ExternalId], else [id]. */
    val externalId: String = id,
    val description: String,
    /** The name the account rules give it, or null when they name none. */
    val ruleName: String? = null,
    val attributes: List<RuleAttribute> = emptyList(),
    val owners: List<ApiImportAccountOwner> = emptyList(),
    val source: AccountApiSource? = null,
    val sortCode: String? = null,
    val accountNumber: String? = null,
    val rawJson: JsonObject? = null,
)

internal data class ApiImportAccountOwner(
    val userId: String,
    val preferredName: String?,
    val sortCode: String?,
    val accountNumber: String?,
    val jsonPath: JsonPath,
)

internal data class PersonalCounterpartyIdentity(
    val name: String,
    val sortCode: String,
    val accountNumber: String,
) {
    // Built with the shared helper so this dedupe key, the engine's match key and the attribute group key
    // seeded by [bankAttrs] are the same string by construction rather than by coincidence.
    val dedupeKey: String
        get() = personalCounterpartyKey(sortCode, accountNumber)
}

internal fun ApiImportAccountOwner.personalCounterpartyIdentity(): PersonalCounterpartyIdentity? {
    val name = preferredName?.trim().orEmpty()
    val sortCode = sortCode?.trim().orEmpty()
    val accountNumber = accountNumber?.trim().orEmpty()
    if (name.isBlank() || sortCode.isBlank() || accountNumber.isBlank()) return null
    return PersonalCounterpartyIdentity(name = name, sortCode = sortCode, accountNumber = accountNumber)
}

internal fun ApiImportAccountOwner.bankKey(): String? {
    val identity = personalCounterpartyIdentity() ?: return null
    return identity.dedupeKey
}

/**
 * The JSON path to the counterparty within this transaction item, for linking an imported account
 * back to its API-traffic origin. Points at the nested counterparty object when one exists
 * ([ApiPeopleMappings.counterpartyObjectField] set, e.g. Monzo's "counterparty"); for providers whose
 * counterparty fields are flat on the item (blank field, e.g. Starling) it points at the item itself,
 * so the audit-trail "origin" expands a node that actually exists.
 */
internal fun ApiTransactionPageItem.counterpartyJsonPath(peopleMappings: ApiPeopleMappings): JsonPath =
    if (peopleMappings.counterpartyObjectField.isBlank()) {
        jsonPath
    } else {
        JsonPath("${jsonPath.value}.${peopleMappings.counterpartyObjectField}")
    }

internal fun ApiTransactionPageItem.personalCounterpartyOwner(peopleMappings: ApiPeopleMappings): ApiImportAccountOwner? {
    if (!isPersonCounterparty) return null
    val counterparty = rawJson?.resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null

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

internal fun ApiTransactionPageItem.personalCounterpartyIdentity(peopleMappings: ApiPeopleMappings): PersonalCounterpartyIdentity? {
    if (!isPersonCounterparty) return null
    val counterparty = rawJson?.resolveJsonObjectPath(peopleMappings.counterpartyObjectField) ?: return null
    val sortCode = counterparty.stringOrNull(peopleMappings.counterpartySortCodeField)?.takeIf { it.isNotBlank() } ?: return null
    val accountNumber = counterparty.stringOrNull(peopleMappings.counterpartyAccountNumberField)?.takeIf { it.isNotBlank() } ?: return null
    val name = counterparty.stringOrNull(peopleMappings.counterpartyNameField)?.takeIf { it.isNotBlank() } ?: return null
    return PersonalCounterpartyIdentity(name = name, sortCode = sortCode, accountNumber = accountNumber)
}

internal fun parseAccounts(
    json: String,
    strategy: ApiImportStrategy,
): List<ApiImportAccount> =
    try {
        responseItemsArray(
            json,
            strategy.config
                .downloadedAccounts()
                .endpoint.responseArrayKey,
        )?.mapIndexedNotNull { index, element ->
            val account = element as? JsonObject ?: return@mapIndexedNotNull null
            parseAccount(account, strategy, accountsItemJsonPath(strategy, index))
        }
            ?: emptyList()
    } catch (e: SerializationException) {
        logger.e(e) { "Failed to parse accounts response for strategy '${strategy.name}'" }
        emptyList()
    }

/**
 * Fails when the account rules' [RuleAction.ExternalId] gives two distinct provider accounts the same
 * stored id: they would be imported as one account.
 */
internal fun requireDistinctExternalIds(
    accounts: Collection<ApiImportAccount>,
    strategy: ApiImportStrategy,
) {
    val collision = accounts.groupBy { it.externalId }.entries.firstOrNull { (_, group) -> group.map { it.id }.distinct().size > 1 }
    if (collision != null) {
        throw ApiSessionImportException(
            "Strategy '${strategy.name}' rewrites accounts ${collision.value.map { it.id }.distinct()} to the same ID " +
                "'${collision.key}'; fix its account ID rewrite.",
        )
    }
}

internal fun accountsItemJsonPath(
    strategy: ApiImportStrategy,
    index: Int,
): JsonPath =
    arrayItemJsonPath(
        strategy.config
            .downloadedAccounts()
            .endpoint.responseArrayKey,
        index,
    )

/**
 * Returns [accounts] with each account's bank details (sort code + account number) filled from the
 * matching account-identifiers response, when the strategy config's
 * [com.moneymanager.domain.model.apistrategy.ApiAccountsSource.Downloaded.identifiersEndpoint] is set.
 * Each identifiers response is matched to its account via the `account.id` captured from the request
 * path, and the sort code / account number are read using the strategy's account field mappings.
 * A no-op for strategies without the endpoint, or when no identifiers responses are present.
 */
internal fun enrichAccountsWithIdentifiers(
    accounts: Map<String, ApiImportAccount>,
    strategy: ApiImportStrategy,
    responsePairs: List<Pair<ApiRequest, ApiResponse>>,
): Map<String, ApiImportAccount> {
    val endpoint = strategy.config.downloadedAccounts().identifiersEndpoint ?: return accounts
    val identifiersByAccountId =
        responsePairs
            .mapNotNull { (request, response) ->
                val accountExternalId =
                    extractPathVariables(endpoint.path, request.encodedPath())?.get("account.id") ?: return@mapNotNull null
                parseJsonObjectOrNull(response.json)?.let { accountExternalId to it }
            }.toMap()
    if (identifiersByAccountId.isEmpty()) return accounts

    val mappings = strategy.config.downloadedAccounts().mappings
    return accounts.mapValues { (id, account) ->
        val identifiers = identifiersByAccountId[id] ?: return@mapValues account
        account.copy(
            sortCode = identifiers.stringOrNull(mappings.sortCodeField)?.takeIf { it.isNotBlank() } ?: account.sortCode,
            accountNumber = identifiers.stringOrNull(mappings.accountNumberField)?.takeIf { it.isNotBlank() } ?: account.accountNumber,
        )
    }
}

internal fun parseJsonObjectOrNull(json: String): JsonObject? =
    try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: SerializationException) {
        logger.e(e) { "Failed to parse API identifiers response as a JSON object" }
        null
    }

internal fun parseAccount(
    account: JsonObject,
    strategy: ApiImportStrategy,
    accountJsonPath: JsonPath,
): ApiImportAccount? {
    val mappings = strategy.config.downloadedAccounts().mappings
    val id = account.resolveJsonPath(mappings.idField) ?: return null
    val engine = RuleEngine()
    val outcome = engine.evaluate(mappings.rules, JsonRecord(account))
    return ApiImportAccount(
        id = id,
        externalId = outcome.externalId ?: id,
        description = account.resolveJsonPath(mappings.descriptionField).orEmpty(),
        ruleName = outcome.ownAccount(engine)?.name?.ifBlank { null },
        attributes = outcome.attributes,
        owners = parseAccountOwners(account, strategy, accountJsonPath),
        sortCode = account.stringOrNull(mappings.sortCodeField),
        accountNumber = account.stringOrNull(mappings.accountNumberField),
        rawJson = account,
    )
}

internal fun parseAccountOwners(
    account: JsonObject,
    strategy: ApiImportStrategy,
    accountJsonPath: JsonPath,
): List<ApiImportAccountOwner> {
    val mappings = strategy.config.downloadedAccounts().mappings
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

internal fun parseAccountsWithPaths(
    json: String,
    strategy: ApiImportStrategy,
): List<Pair<ApiImportAccount, JsonPath>> =
    responseItemsArray(
        json,
        strategy.config
            .downloadedAccounts()
            .endpoint.responseArrayKey,
    )?.mapIndexedNotNull { index, element ->
        val account = element as? JsonObject ?: return@mapIndexedNotNull null
        val jsonPath = accountsItemJsonPath(strategy, index)
        parseAccount(account, strategy, jsonPath)?.let { it to jsonPath }
    }
        ?: emptyList()
