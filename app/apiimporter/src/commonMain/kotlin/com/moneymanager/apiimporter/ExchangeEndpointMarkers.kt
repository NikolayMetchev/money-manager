package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoRegistry
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiLedgerTrades
import com.moneymanager.domain.model.apistrategy.ApiTradeMappings
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.ApiValueSet
import com.moneymanager.domain.model.apistrategy.InstrumentSplitMode
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.domain.model.apistrategy.TransferDirection
import com.moneymanager.domain.model.codesByName
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.resolveAssetCode
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.EXCLUDED_ATTRIBUTE_TYPE_NAME
import com.moneymanager.domain.model.rules.Record
import com.moneymanager.domain.model.rules.RuleAttribute
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.model.rules.RuleOutcome
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.rules.excludedAttributeValue
import com.moneymanager.domain.model.rules.ownedAttributeValues
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.EXCHANGE_TXN_ID_ATTR
import com.moneymanager.importengineapi.ExistingApiIdExtractor
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOrderIntent
import com.moneymanager.importengineapi.ImportReportedBalance
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalOrderKey
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.ObservationOwnerKey
import com.moneymanager.importengineapi.ObservationSlot
import com.moneymanager.importengineapi.createCrypto
import com.moneymanager.importengineapi.dedupePolicy
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.internalTransferBridges
import com.moneymanager.importengineapi.recordApiDownloadCoverage
import com.moneymanager.importengineapi.tradeDedupePolicy
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiRequestSigner
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A key identifying an endpoint uniquely enough to disambiguate two [ApiDataEndpoint]s that share the
 * same [ApiEndpointConfig.path] but differ only by a static query param baked into the signed body
 * (Kraken's `Ledgers` endpoint fetched once with `type=deposit` and once with `type=withdrawal`, neither
 * of which appears in the request URL since Kraken signs params into the POST body, not the query
 * string). Used both for the incremental-download marker and for matching a stored response back to the
 * data endpoint that produced it.
 */
internal fun endpointDedupeKey(endpoint: ApiEndpointConfig): String {
    val staticParams =
        endpoint.queryParams
            .filter { it.value != null }
            .sortedBy { it.name }
            .joinToString("&") { "${it.name}=${it.value}" }
    return if (staticParams.isEmpty()) endpoint.path else "${endpoint.path}?$staticParams"
}

/**
 * The recorded URL for a page — deliberately built from the plain endpoint path plus out-of-band markers
 * (never the signed URL/query string), so it stays byte-identical across retries of the same logical
 * page regardless of a fresh nonce/signature/timestamp (a signed query string, e.g. Binance's, changes
 * every request; a URL built from it would never resume-skip, nor match back to its endpoint on import).
 * The marker is never sent on the wire (see [ApiClient.send]'s `recordUrl`).
 */
internal fun markerUrl(
    baseUrl: String,
    path: String,
    endpointKey: String,
    window: ApiDateWindow?,
    page: String?,
    fanOutValue: String?,
): String {
    val sb = StringBuilder(buildEndpointUrl(baseUrl, path)).append("?ep=").append(endpointKey)
    window?.let {
        sb
            .append("&ws=")
            .append(it.start.toEpochMilliseconds())
            .append("&we=")
            .append(it.end.toEpochMilliseconds())
    }
    page?.let { sb.append("&pg=").append(it) }
    fanOutValue?.let { sb.append("&fv=").append(it) }
    return sb.toString()
}

/** The start of the date window recorded in an exchange marker URL (`&ws=<epoch ms>`), if it has one. */
internal fun windowStartFromMarker(url: String): Instant? = markerInstant(url, "ws")

/** The end of the date window recorded in an exchange marker URL (`&we=<epoch ms>`), if it has one. */
internal fun windowEndFromMarker(url: String): Instant? = markerInstant(url, "we")

private fun markerInstant(
    url: String,
    param: String,
): Instant? =
    Regex("[?&]$param=(\\d+)")
        .find(url)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?.let { Instant.fromEpochMilliseconds(it) }
