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

internal class CurrencyCache(
    private val currencyRepository: CurrencyReadRepository,
    private val assetAliases: Map<String, String>,
) {
    private val mutex = Mutex()
    private var currenciesByCode: Map<String, Currency>? = null
    private var codeByName: Lazy<Map<String, String>> = lazyOf(emptyMap())

    // A code, else a currency's name, as a CSV row's is read (see [resolveAssetCode]).
    suspend fun getCurrency(code: String): Currency? {
        val currencies = loadCurrencies()
        return currencies[resolveAssetCode(code.trim().uppercase(), assetAliases, codeByName) { it in currencies }]
    }

    private suspend fun loadCurrencies(): Map<String, Currency> =
        mutex.withLock {
            val currencies = currenciesByCode
            if (currencies != null) return@withLock currencies

            currencyRepository.getAllCurrencies().first().associateBy { it.code.uppercase() }.also {
                currenciesByCode = it
                codeByName = lazy { codesByName(it.values.map { currency -> currency.code to currency.name }) }
            }
        }
}

/**
 * Display name for a downloaded own account: the name its rules give, else its description (else its id).
 * Two genuinely distinct accounts can land on the identical rule-given name (e.g. two personal accounts
 * with no bank details to tell them apart) — appending a short id suffix only in that case keeps every
 * account uniquely named without cluttering the common single-account case (see [resolveOwnAccountKey]).
 */
internal fun ApiImportAccount.displayName(siblingAccounts: Collection<ApiImportAccount>): String {
    val name = ruleName ?: return description.ifBlank { id }
    val collides = siblingAccounts.any { it.id != id && it.ruleName == name }
    return if (collides) "$name (${id.takeLast(6)})" else name
}

/** The counterparty's account name: the rules' name, else the description, else "Unknown". */
internal fun ApiTransactionPageItem.counterpartyName(): String = counterpartyAccountName ?: description.ifBlank { "Unknown" }

/** The amount, description and direction of a transaction, independent of account identity. */
internal data class TransferData(
    val money: Money,
    val description: String,
    val isIncoming: Boolean,
)

// Most currencies have two decimal places, so a currency created without its minor unit is read as having two.
internal const val DEFAULT_MINOR_UNIT_DECIMALS = 2

/**
 * Converts a raw integer a bank API reports in its own ISO 4217 minor units (e.g. Monzo's `1234`
 * meaning £12.34) into [Money] at the app's storage precision. Constructing `Money` directly from
 * the raw integer would treat it as already being in the app's own (much higher) storage scale,
 * silently truncating every amount toward zero — the provider's minor-unit width and the app's
 * storage scale are unrelated. The width is the currency's ISO minor unit
 * ([Currency.fractionDigits], from the catalog's asset list), else 2; [decimalPlacesOverrides] lets a strategy
 * correct a provider that doesn't follow the ISO standard for a given currency.
 */
internal fun minorUnitsToMoney(
    rawMinorUnits: Long,
    currency: Currency,
    decimalPlacesOverrides: Map<String, Int>,
): Money {
    val decimalPlaces = decimalPlacesOverrides[currency.code.uppercase()] ?: currency.fractionDigits ?: DEFAULT_MINOR_UNIT_DECIMALS
    // The editor rejects negatives, but hand-edited or imported JSON can carry one, and a negative shift would
    // silently scale the amount up instead of down.
    require(decimalPlaces >= 0) { "Minor-unit decimal places for ${currency.code} must not be negative: $decimalPlaces" }
    return Money.fromDisplayValue(BigDecimal(rawMinorUnits).movePointLeft(decimalPlaces), currency)
}

internal fun ApiTransactionPageItem.toTransferData(
    currency: Currency,
    minorUnitDecimalPlaces: Map<String, Int>,
): TransferData {
    val money =
        when {
            amountMinorUnits != null -> minorUnitsToMoney(amountMinorUnits.absoluteValue, currency, minorUnitDecimalPlaces)
            amountDecimalMajor != null -> Money.fromDisplayValue(amountDecimalMajor, currency)
            else -> Money(0L, currency)
        }
    return TransferData(
        money = money,
        description = description.ifBlank { counterpartyAccountName ?: "Unknown" },
        isIncoming = isIncoming,
    )
}
