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

internal fun JsonObject.resolveJsonObjectPath(dotPath: String): JsonObject? {
    // A blank path means "this object" — used by providers whose counterparty fields are flat on the
    // transaction item rather than nested under a sub-object.
    if (dotPath.isBlank()) return this
    var current: JsonElement = this
    for (part in dotPath.split(".")) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return current as? JsonObject
}

/** Every attribute action in these rules, in order. */
internal fun List<StrategyRule>.attributeActions(): List<RuleAction.Attribute> =
    flatMap {
        it.actions
    }.filterIsInstance<RuleAction.Attribute>()

/** What the first of the rules' [side] candidates that applies to the record names, with that candidate. */
internal fun RuleOutcome.namedAccountWithNaming(
    side: AccountSide,
    engine: RuleEngine,
): Pair<NamedAccount, AccountNaming>? =
    accountsFor(side).firstNotNullOfOrNull { naming -> engine.evaluator.name(naming) { record.text(it) }?.let { it to naming } }

/** What the first of the rules' own-account candidates that applies to the record names. */
internal fun RuleOutcome.ownAccount(engine: RuleEngine): NamedAccount? = namedAccountWithNaming(AccountSide.OWN, engine)?.first

/** Whether [condition] holds for this item (see [JsonRecord]). */
internal fun JsonObject.matches(condition: Condition): Boolean = RuleEvaluator().matches(condition, JsonRecord(this))

/** The value [expr] reads from this item: its first non-blank path, cleaned through its extraction. */
internal fun JsonObject.resolve(expr: ValueExpr): String = RuleEvaluator().resolve(expr) { resolveJsonPath(it) }

/**
 * Whether [item] carries [fee] at all: its conditions hold and, when the fee is charged on one asset,
 * [item] is in [itemAsset]. Shared by the bank-feed and exchange pipelines.
 */
internal fun RuleEvaluator.carriesFee(
    fee: FeeRule,
    item: Record,
    itemAsset: String?,
): Boolean =
    all(fee.conditions, item) &&
        fee.chargedOnAsset?.let { itemAsset != null && resolve(it) { path -> item.text(path) }.equals(itemAsset, ignoreCase = true) } !=
        false

internal fun JsonObject.carriesFee(
    fee: FeeRule,
    itemAsset: String?,
): Boolean = RuleEvaluator().carriesFee(fee, JsonRecord(this), itemAsset)

/**
 * An API item as a rule [Record]: paths are dot-paths (with `[n]` array indexing for scalars). A field
 * holding JSON `null` does not exist (Monzo sends `atm_fees_detailed: null` on every non-ATM
 * transaction), and a blank path addresses the item itself.
 */
internal class JsonRecord(
    private val obj: JsonObject,
) : Record {
    override fun exists(path: String): Boolean = obj.resolveJsonPathElement(path).let { it != null && it != JsonNull }

    override fun text(path: String): String? = obj.resolveJsonPath(path)

    override fun arrayTexts(path: String): List<String>? =
        (obj.resolveJsonPathElement(path) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    override fun objectSize(path: String): Int? = (if (path.isBlank()) obj else obj.resolveJsonPathElement(path) as? JsonObject)?.size
}

internal class AttributeTypeCache(
    private val importEngine: ImportEngine,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, AttributeTypeId>()

    suspend fun getOrCreate(name: String): AttributeTypeId =
        mutex.withLock { cache.getOrPut(name) { importEngine.getOrCreateAttributeType(name) } }
}
