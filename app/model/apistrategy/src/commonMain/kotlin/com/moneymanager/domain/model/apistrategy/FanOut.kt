package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.serialization.SortedStringListSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * A set of string values resolved once per download, from a static list, from field(s) of items
 * already fetched by another endpoint, from a union of other sets, or from the cross product of two
 * sets joined by [ApiValueSet.CrossProduct.template]. Used to drive [ApiFanOut.values] and
 * [ApiFanOut.validAgainst].
 */
@Serializable
sealed interface ApiValueSet {
    /** A fixed, hand-written list of values. Order carries no meaning (a plain set of candidates). */
    @Serializable
    data class Static(
        @Serializable(with = SortedStringListSerializer::class)
        val values: List<String>,
    ) : ApiValueSet

    /**
     * Every value of [fields] (dot-paths, read independently and unioned) across the items already
     * fetched by the [ApiStrategyConfig.valueEndpoints] entry keyed by [endpointPath] this session
     * (e.g. Binance's held balances from `getUserAsset`). [endpointPath] must match how the engine
     * keys that endpoint's items - its plain path, unless two endpoints there share a path (as with
     * two [ApiStrategyConfig.dataEndpoints] differing only by a static query param), in which case it
     * is the path plus `?name=value` for each such param, sorted by name and `&`-joined (matching
     * `endpointDedupeKey` in `app:apiimporter`). [fields] order carries no meaning.
     */
    @Serializable
    data class FromValueEndpoint(
        val endpointPath: String,
        @Serializable(with = SortedStringListSerializer::class)
        val fields: List<String>,
    ) : ApiValueSet

    /**
     * Like [FromValueEndpoint], but reads from the items already downloaded (this session) by the
     * [ApiStrategyConfig.dataEndpoints] entry keyed by [endpointPath] instead (same keying rule) — so
     * an asset that was fully disposed of before this download (no balance left, but present in a
     * deposit/withdrawal/trade already fetched) still contributes a value. A fan-out endpoint is
     * always resolved after every non-fan-out data endpoint has downloaded, so these items are
     * available.
     */
    @Serializable
    data class FromDataEndpoint(
        val endpointPath: String,
        @Serializable(with = SortedStringListSerializer::class)
        val fields: List<String>,
    ) : ApiValueSet

    /** The union of [sets]. Commutative - list order carries no meaning. */
    @Serializable
    data class Union(
        val sets: List<ApiValueSet>,
    ) : ApiValueSet

    /**
     * Every combination of a [left] value and a [right] value, joined via [template] (occurrences of
     * `{left}`/`{right}` substituted) — e.g. Binance spot symbols: held/seen assets x quote assets,
     * `template = "{left}{right}"` producing "BTCUSDT".
     */
    @Serializable
    data class CrossProduct(
        val left: ApiValueSet,
        val right: ApiValueSet,
        val template: String = "{left}{right}",
    ) : ApiValueSet
}

/**
 * Fans an endpoint's whole download (window/offset/cursor loop) out over [values], resolved once per
 * download and sent one at a time as the [param] query/body parameter. When [validAgainst] is set, a
 * resolved value not present in it is dropped (e.g. intersecting candidate symbols against Binance
 * `exchangeInfo`'s real symbol universe, so a nonexistent pair is never requested). A value that still
 * fails at request time (e.g. HTTP 400) is skipped without aborting the rest of the fan-out.
 */
@Serializable
data class ApiFanOut(
    /**
     * The query/body parameter each value is sent as; null when the value is only substituted into the
     * endpoint path via a `{fanOut}` placeholder (Coinbase `v2/accounts/{fanOut}/transactions`).
     */
    val param: String?,
    val values: ApiValueSet,
    val validAgainst: ApiValueSet? = null,
    /**
     * Keeps resolved values as the provider spelled them. By default every value is uppercased (asset
     * and symbol codes); an opaque id, such as a lowercase account UUID, must not be. Omitted from JSON
     * when false so existing strategies keep their hash.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val preserveCase: Boolean = false,
)
