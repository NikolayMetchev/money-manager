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
 * A parsed transaction page item. The amount is normalized into exactly one of [amountMinorUnits]
 * (integer minor-units format) or [amountDecimalMajor] (decimal major-units format, magnitude only),
 * with [amountSign] (-1/0/1) carrying the direction independently of how the amount was encoded.
 */
internal data class ApiTransactionPageItem(
    val amountMinorUnits: Long?,
    val amountDecimalMajor: BigDecimal?,
    val amountSign: Int,
    val created: Instant,
    val currencyCode: String,
    val description: String,
    val jsonPath: JsonPath,
    /** What the bank feed's rules decided for this item. */
    val outcome: RuleOutcome,
    /** The counterparty name the rules give (never a built-in one), or null when they name none. */
    val counterpartyAccountName: String? = null,
    /** The built-in counterparty (e.g. "ATM") the rules route this item to, consolidating every match. */
    val builtInCounterparty: String? = null,
    /** Whether the item is itself a fee (its fee included in the amount is all of it), paid into the own account's fee account. */
    val feeAccountCounterparty: Boolean = false,
    val rawJson: JsonObject? = null,
    // What the counterparty was paid when the own account settled in another currency (magnitude only).
    val foreignAmount: ParsedAmount? = null,
    val foreignCurrencyCode: String? = null,
    // Fee charged on the transaction (magnitude only; a fee is always money out of the own account).
    val feeRule: FeeRule? = null,
    val feeAmountMinorUnits: Long? = null,
    val feeAmountDecimalMajor: BigDecimal? = null,
    val feeCurrencyCode: String? = null,
    val feeDescription: String? = null,
    // What the own account holds after the item, as the feed reports it (signed).
    val balance: ParsedAmount? = null,
    val balanceCurrencyCode: String? = null,
    // When the feed's running balance counts the item (`RuleAction.Balance.at`); null: the item's own time.
    val balanceAt: Instant? = null,
) {
    val isZeroAmount: Boolean get() = amountSign == 0
    val isIncoming: Boolean get() = amountSign > 0
    val hasFee: Boolean get() = feeAmountMinorUnits != null || feeAmountDecimalMajor != null

    /** Whether the counterparty is a fixed account (built-in or the fee account), not one the counterparty id identifies. */
    val hasFixedCounterparty: Boolean get() = builtInCounterparty != null || feeAccountCounterparty

    /** Why the item was declined (imported, but excluded from balances), or null when it wasn't. */
    val declineReason: String? get() = if (outcome.excluded) outcome.excludeReason?.ifBlank { null } ?: "excluded" else null

    val isPersonCounterparty: Boolean get() = outcome.counterpartyIsPerson
}

internal data class ApiImportPageContext(
    val index: Int,
    val response: ApiHttpResponse,
    val requestId: ApiRequestId,
    val ownAccountKey: LocalAccountKey,
)

internal data class PreparedApiTransaction(
    val pageIndex: Int,
    val itemIndex: Int,
    val responseId: ApiResponseId,
    val requestId: ApiRequestId,
    val item: ApiTransactionPageItem,
    val source: AccountRef,
    val target: AccountRef,
    /** The account whose feed this is: its own end. */
    val ownAccount: AccountRef,
    val timestamp: Instant,
    val description: String,
    val amount: Money,
    val attributes: List<NewAttribute>,
    val transactionApiId: String?,
    val uniqueKey: Map<String, String>?,
    val fee: ImportFee? = null,
    val passThrough: ImportPassThrough? = null,
    val conversion: ImportConversion? = null,
    /** What [ownAccount] holds after the item, as the feed reports it. */
    val reportedBalance: Money? = null,
)

/** Every [LocalAccountKey] this prepared transaction moves money through (main legs, fee, pass-through). */
internal fun PreparedApiTransaction.referencedLocalAccountKeys(): Set<LocalAccountKey> =
    buildSet {
        (source as? AccountRef.Local)?.let { add(it.key) }
        (target as? AccountRef.Local)?.let { add(it.key) }
        fee?.let { fee ->
            (fee.source as? AccountRef.Local)?.let { add(it.key) }
            (fee.target as? AccountRef.Local)?.let { add(it.key) }
        }
        passThrough?.let { passThrough ->
            passThrough.conduits.forEach { conduit -> (conduit as? AccountRef.Local)?.let { add(it.key) } }
            (passThrough.merchantTarget as? AccountRef.Local)?.let { add(it.key) }
        }
    }

/** The prepared transfers plus the count of items that failed to parse/prepare (recorded as errors). */
internal data class PreparedTransfers(
    val orderedPrepared: List<PreparedApiTransaction>,
    val failedRecords: List<ResponseTransactionImportRecord>,
) {
    val errorCount: Int get() = failedRecords.size
}

internal data class ResponseTransactionImportRecord(
    val pageIndex: Int,
    val itemIndex: Int,
    val responseId: ApiResponseId,
    val jsonPath: JsonPath,
    val state: ApiResponseTransactionState,
    val errorMessage: String?,
) {
    fun toInsert(): ApiResponseTransactionInsert =
        ApiResponseTransactionInsert(
            responseId = responseId,
            jsonPath = jsonPath,
            state = state,
            errorMessage = errorMessage,
        )
}

internal sealed class ApiTransactionPreparation {
    data class Prepared(
        val transaction: PreparedApiTransaction,
    ) : ApiTransactionPreparation()

    data class Failed(
        val record: ResponseTransactionImportRecord,
    ) : ApiTransactionPreparation()
}
