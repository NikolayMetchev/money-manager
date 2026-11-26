package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.serialization.SortedStringListSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
enum class HttpMethodType {
    GET,
    POST,
}

/**
 * A single query parameter for an API endpoint.
 *
 * @property name Parameter name (e.g. "account_id", "limit", "currency")
 * @property value Static value; null when the value is derived at runtime via [dynamicSource]
 * @property dynamicSource Identifies the runtime source for the parameter value, resolved against
 *                         the import context. Supported expressions:
 *                         - "account.id" — the current account's external API id
 *                         - "account.<field>" — a dot-path field on the current account's raw JSON
 *                           (e.g. "account.currency")
 *                         - "ancestor`N`.<field>" — a field on the N-th ancestor resource item
 *                           (e.g. "ancestor[0].id"); "parent.id" aliases the last ancestor's id
 *                         - "window.start" / "window.end" — the ISO-8601 bounds of the current
 *                           date window (see [ApiDateWindowing])
 */
@Serializable
data class ApiQueryParam(
    val name: String,
    val value: String? = null,
    val dynamicSource: String? = null,
) : Comparable<ApiQueryParam> {
    override fun compareTo(other: ApiQueryParam): Int = compareValuesBy(this, other, { it.name }, { it.value }, { it.dynamicSource })
}

/**
 * Configuration for a single API endpoint.
 *
 * @property path URL path relative to the strategy's base URL. May contain `{expression}`
 *               placeholders resolved against the import context using the same vocabulary as
 *               [ApiQueryParam.dynamicSource] (e.g. "/v4/profiles/{ancestor[0].id}/balances").
 * @property responseArrayKey JSON key holding the items array (e.g. "accounts"). Supports a dot-path
 *                            for nested envelopes (e.g. "result.data" for Crypto.com). Blank means the
 *                            response body itself is the array (a bare JSON array).
 * @property queryParams Static and dynamic query/body parameters sent with every request. For a POST
 *                       endpoint these become the signed request body (see [method]).
 * @property pagination Optional pagination strategy; null means only one page is fetched
 * @property method HTTP method; defaults to GET (bank APIs). Exchange private endpoints use POST.
 * @property successCodeField Optional dot-path to a numeric/string status code in the response
 *                            envelope (e.g. Crypto.com "code"). When set, a response whose value at
 *                            this path differs from [successCodeOkValue] is treated as an error.
 * @property successCodeOkValue The [successCodeField] value that means success (e.g. "0").
 * @property errorArrayField Optional dot-path to an errors array in the response envelope (Kraken
 *                           "error"). When set, a response is treated as an error if the array at this
 *                           path is present and non-empty (success = empty or absent array).
 * @property responseObjectValues When true, the element at [responseArrayKey] is a JSON *object*
 *                                whose values are the items (Kraken `result.trades`/`result.ledger`,
 *                                keyed by trade/ledger id), rather than a JSON array.
 * @property itemKeyField When [responseObjectValues] is set, the map key of each entry is spliced into
 *                        that entry's JSON object under this field name before mapping (e.g. Kraken's
 *                        ledger id, which appears only as the object key, not as a value field).
 * @property nestedItemsKey Dot-path to an array *inside* each element at [responseArrayKey] whose elements
 *                          are the real items (Binance `asset/dribblet`, where one dust conversion holds a
 *                          `userAssetDribbletDetails` array with one entry per asset swept into BNB). The
 *                          outer elements are then only containers and never mapped themselves. Because
 *                          flattening changes how many items a page appears to hold, it must not be combined
 *                          with [ApiPaging.Offset] paging, whose loop compares an item count
 *                          against the page size.
 */
@Serializable
data class ApiEndpointConfig(
    val path: String,
    val responseArrayKey: String,
    @Serializable(with = SortedQueryParamListSerializer::class)
    val queryParams: List<ApiQueryParam> = emptyList(),
    val pagination: ApiPaginationConfig? = null,
    val method: HttpMethodType = HttpMethodType.GET,
    val successCodeField: String? = null,
    val successCodeOkValue: String? = null,
    val errorArrayField: String? = null,
    val responseObjectValues: Boolean = false,
    val itemKeyField: String? = null,
    val nestedItemsKey: String? = null,
    /**
     * Relative rate-limit cost of one request to this endpoint (e.g. Kraken's ledger/trade-history
     * calls cost 2 counter units against 1 for other endpoints). Multiplies the strategy's
     * [ApiStrategyConfig.rateLimitMillis] delay so a mixed-cost exchange doesn't have to pace every
     * endpoint as slowly as its most expensive one.
     */
    val requestCostWeight: Int = 1,
    /**
     * When set, this endpoint is fetched once per value in a runtime-derived set (e.g. Binance
     * `myTrades`'s required `symbol`, which has no account-wide feed) rather than once. See [ApiFanOut].
     */
    val fanOut: ApiFanOut? = null,
    /**
     * When true, the request is sent without [ApiStrategyConfig.requestSigning] applied — for a public
     * endpoint used only as a [ApiStrategyConfig.valueEndpoints] value source (e.g. Binance
     * `exchangeInfo`), which rejects the api-key/signature params a private endpoint expects.
     */
    val unsigned: Boolean = false,
    /**
     * When false, the response is used to resolve values but never written to `api_response` (e.g.
     * Binance `exchangeInfo`'s multi-megabyte symbol list) — every other endpoint is persisted so its
     * audit trail and incremental-resume skip keep working.
     */
    val storeResponse: Boolean = true,
    /** Earlier bank-feed path templates whose stored responses still use this endpoint's mappings. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedStringListSerializer::class)
    val historicalPaths: List<String> = emptyList(),
)

@Serializable
enum class ApiEndpointKind {
    /** A bank-style single-asset transaction feed, fetched once per account of [ApiAccountsSource.Downloaded]. */
    BANK_TRANSACTIONS,

    /** Executed exchange fills — cross-asset trades (two legs). */
    TRADES,

    /** Order history — reference metadata joined onto [TRADES] by order id (no money movement). */
    ORDERS,

    /** Incoming deposits — single-asset transfers into the account. */
    DEPOSITS,

    /** Outgoing withdrawals — single-asset transfers out of the account. */
    WITHDRAWALS,
}

/**
 * One data endpoint of a strategy that has several (exchanges). Each pairs an [endpoint] with the
 * [kind] of record it yields and the mappings needed to interpret it.
 *
 * @property transactionMappings Field mappings for [ApiEndpointKind.BANK_TRANSACTIONS]/DEPOSITS/WITHDRAWALS.
 * @property tradeMappings Field mappings for [ApiEndpointKind.TRADES]/ORDERS.
 * @property enrichesTransfers When true, this endpoint produces no transfers/trades of its own; its
 *                             items are indexed by [transactionMappings]' `idField` and used only to
 *                             enrich transfers built from other endpoints whose mapping sets a matching
 *                             `joinKeyField` (Kraken DepositStatus/WithdrawStatus supplying on-chain
 *                             address/txid/network for Ledgers-sourced deposits/withdrawals).
 */
@Serializable
data class ApiDataEndpoint(
    val endpoint: ApiEndpointConfig,
    val kind: ApiEndpointKind,
    val transactionMappings: ApiTransactionMappings? = null,
    val tradeMappings: ApiTradeMappings? = null,
    val enrichesTransfers: Boolean = false,
)
