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

private val logger = Logger.withTag("ApiResponseItems")

/** JSON path for the [index]-th item of a response array, accounting for a blank (root-array) key. */
internal fun arrayItemJsonPath(
    responseArrayKey: String,
    index: Int,
): JsonPath = if (responseArrayKey.isBlank()) JsonPath("$[$index]") else JsonPath("$.$responseArrayKey[$index]")

/**
 * The real JSON path of an item inside a keyed-object response (see [responseItemsWithKeys]). A [key] that
 * already starts with an index (`[0].details[1]`, produced by nested-array flattening) is appended without
 * the joining dot, so the path stays resolvable.
 */
internal fun keyedItemJsonPath(
    responseArrayKey: String,
    key: String,
): JsonPath {
    val separator = if (key.startsWith("[")) "" else "."
    return if (responseArrayKey.isBlank()) JsonPath("$$separator$key") else JsonPath("$.$responseArrayKey$separator$key")
}

/**
 * Extracts the items array from a response body; a blank [responseArrayKey] means the body is the
 * array. A bare JSON object body (e.g. a single-resource endpoint like Starling's account holder) is
 * wrapped as a one-element array so single-object responses parse like any other.
 *
 * When [responseObjectValues] is set, the element at [responseArrayKey] is itself a JSON object keyed
 * by id (Kraken `result.trades`/`result.ledger`) rather than an array; its values become the items. When
 * [itemKeyField] is also set, each entry's map key is spliced into that entry's object under this field
 * name first, since the id sometimes appears only as the key (Kraken ledger entries).
 *
 * When [nestedItemsKey] is set, each element at [responseArrayKey] is only a container: the items are the
 * elements of its nested array at that path (Binance `asset/dribblet`), each paired with its real
 * `[outer].<key>[inner]` path so the audit trail points at the row that was actually mapped.
 */
internal fun responseItemsArray(
    json: String,
    responseArrayKey: String,
    responseObjectValues: Boolean = false,
    itemKeyField: String? = null,
    nestedItemsKey: String? = null,
): JsonArray? =
    responseItemsWithKeys(json, responseArrayKey, responseObjectValues, itemKeyField, nestedItemsKey)
        ?.let { items -> JsonArray(items.map { (_, item) -> item }) }

/**
 * Like [responseItemsArray], but pairs each item with the real JSON-path key it lives at: the
 * object's own key for a keyed-object response ([responseObjectValues] — e.g. Kraken's
 * `result.trades`/`result.ledger`), or null for a plain array (callers fall back to an index-based
 * path via [arrayItemJsonPath]). Without this, an audit entry for a keyed-object item stored a
 * fabricated array-index path (e.g. `result.trades[3]`) that doesn't exist in the actual response, so
 * the "view origin" link couldn't locate it.
 */
internal fun responseItemsWithKeys(
    json: String,
    responseArrayKey: String,
    responseObjectValues: Boolean = false,
    itemKeyField: String? = null,
    nestedItemsKey: String? = null,
): List<Pair<String?, JsonElement>>? =
    try {
        val root = Json.parseToJsonElement(json)
        val resolved = if (responseArrayKey.isBlank()) root else root.resolveJsonPathElement(responseArrayKey)
        if (nestedItemsKey != null) {
            (resolved as? JsonArray)?.flatMapIndexed { outerIndex, container ->
                val nested = (container as? JsonObject)?.resolveJsonPathElement(nestedItemsKey) as? JsonArray
                nested.orEmpty().mapIndexed { innerIndex, item ->
                    "[$outerIndex].$nestedItemsKey[$innerIndex]" to item
                }
            }
        } else if (responseObjectValues) {
            (resolved as? JsonObject)?.entries?.map { (key, value) ->
                val item =
                    if (itemKeyField != null && value is JsonObject) {
                        JsonObject(value.toMutableMap().apply { put(itemKeyField, JsonPrimitive(key)) })
                    } else {
                        value
                    }
                key to item
            }
        } else if (responseArrayKey.isBlank()) {
            when (root) {
                is JsonArray -> root.map { null to it }
                is JsonObject -> listOf(null to root)
                else -> null
            }
        } else {
            (resolved as? JsonArray)?.map { null to it }
        }
    } catch (e: SerializationException) {
        logger.e(e) { "Failed to parse API response array (key='$responseArrayKey')" }
        null
    }

/**
 * Whether a response passed its envelope status check. Strategies with a [successCodeField]
 * (Crypto.com "code") require the value at that path to equal [successCodeOkValue] (e.g. "0").
 * Strategies with an [errorArrayField] (Kraken "error") require the array at that path to be absent or
 * empty. When both are null, no envelope check is applied (bank APIs rely on the HTTP status alone).
 */
internal fun responseCodeOk(
    json: String,
    successCodeField: String?,
    successCodeOkValue: String?,
    errorArrayField: String? = null,
): Boolean {
    val root =
        try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            return false
        }
    if (errorArrayField != null) {
        val errors = root.resolveJsonPathElement(errorArrayField) as? JsonArray
        if (!errors.isNullOrEmpty()) return false
    }
    if (successCodeField == null) return true
    // Fail closed: a configured status field with no expected value must never pass (an absent code
    // would otherwise equal a null expected value and let an error envelope through).
    val expected = successCodeOkValue ?: return false
    val actual = (root.resolveJsonPathElement(successCodeField) as? JsonPrimitive)?.contentOrNull
    return actual == expected
}

internal const val MILLIS_PER_DAY = 86_400_000L

// '}' is matched via a character class because Android's regex engine rejects a bare '}'
// while the JVM flags an escaped '\}' as redundant.
internal val PATH_TEMPLATE_REGEX = Regex("\\{([^}]+)[}]")
