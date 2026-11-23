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
 * Parses an API timestamp string per its [TimestampFormat] — ISO-8601 (bank APIs), epoch
 * milliseconds/seconds (most exchanges) including fractional seconds (Kraken), or a [pattern] string
 * (Binance withdrawal history's `"yyyy-MM-dd HH:mm:ss"`, always UTC; see [parsePatternedTimestamp]).
 */
internal fun parseApiTimestamp(
    value: String,
    format: TimestampFormat,
    pattern: String? = null,
): Instant? =
    when (format) {
        TimestampFormat.ISO_8601 -> {
            parseIsoInstant(value)
        }

        TimestampFormat.EPOCH_MS -> {
            value.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }
        }

        TimestampFormat.EPOCH_S -> {
            value.toLongOrNull()?.let { Instant.fromEpochSeconds(it) }
        }

        TimestampFormat.EPOCH_S_FLOAT -> {
            value.toDoubleOrNull()?.let {
                val seconds = it.toLong()
                Instant.fromEpochSeconds(seconds, ((it - seconds) * 1_000_000_000L).toLong())
            }
        }

        TimestampFormat.PATTERN -> {
            pattern?.let { parsePatternedTimestamp(value, it) }
        }
    }

/**
 * An ISO-8601 instant, also in the basic offset form (`+0000`, PayPal) that [Instant.parse] only accepts
 * extended (`+00:00`).
 */
internal fun parseIsoInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
        ?: basicOffset.find(value)?.let { offset ->
            val (hours, minutes) = offset.destructured
            runCatching { Instant.parse(value.substring(0, offset.range.first) + "$hours:$minutes") }.getOrNull()
        }

internal val basicOffset = Regex("([+-]\\d{2})(\\d{2})$")

/**
 * Parses [value] against a small vocabulary of date-time tokens in [pattern] — `yyyy`, `MM`, `dd`,
 * `HH`, `mm`, `ss` separated by fixed literal characters, interpreted as UTC. Deliberately not a general
 * date-time formatting engine (kotlinx-datetime's `DateTimeComponents.Format`/`byUnicodePattern` is
 * `@FormatStringsInDatetimeFormats`-experimental); every provider needing [TimestampFormat.PATTERN] so
 * far uses this exact shape (Binance: `"yyyy-MM-dd HH:mm:ss"`).
 */
internal fun parsePatternedTimestamp(
    value: String,
    pattern: String,
): Instant? {
    val fields = mapOf("yyyy" to 4, "MM" to 2, "dd" to 2, "HH" to 2, "mm" to 2, "ss" to 2)
    var year = 1970
    var month = 1
    var day = 1
    var hour = 0
    var minute = 0
    var second = 0
    var pi = 0
    var vi = 0
    while (pi < pattern.length) {
        val token = fields.keys.firstOrNull { pattern.startsWith(it, pi) }
        if (token != null) {
            val width = fields.getValue(token)
            if (vi + width > value.length) return null
            val digits = value.substring(vi, vi + width)
            val number = digits.toIntOrNull() ?: return null
            when (token) {
                "yyyy" -> year = number
                "MM" -> month = number
                "dd" -> day = number
                "HH" -> hour = number
                "mm" -> minute = number
                "ss" -> second = number
            }
            pi += width
            vi += width
        } else {
            if (vi >= value.length || value[vi] != pattern[pi]) return null
            pi += 1
            vi += 1
        }
    }
    if (vi != value.length) return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    val epochDay = daysFromCivil(year, month, day)
    val epochSeconds = epochDay * 86_400L + hour * 3_600L + minute * 60L + second
    return Instant.fromEpochSeconds(epochSeconds)
}

/**
 * Days since the epoch (1970-01-01) for a proleptic-Gregorian UTC date — Howard Hinnant's `days_from_civil`
 * algorithm. Avoids pulling in kotlinx-datetime just for [TimestampFormat.PATTERN]'s handful of exchange
 * date-time strings.
 */
internal fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}
