package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.domain.model.rules.Extraction
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.SortedConditionListSerializer
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.serialization.SortedListSerializer
import com.moneymanager.domain.model.serialization.SortedStringListSerializer
import com.moneymanager.domain.model.serialization.SortedStringSetSerializer
import com.moneymanager.domain.model.serialization.SortedStringToLongMapSerializer
import com.moneymanager.domain.model.serialization.SortedStringToStringMapSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HTTP method for an API endpoint. */
@Serializable
enum class HttpMethodType {
    GET,
    POST,
}

/**
 * How a timestamp value is encoded in an API response.
 *
 * [ISO_8601] — an ISO-8601 string parsed via `Instant.parse` (bank APIs: Monzo/Wise/Starling).
 * [EPOCH_MS]/[EPOCH_S] — an integer count of milliseconds/seconds since the epoch (most exchanges).
 * [EPOCH_S_FLOAT] — a fractional count of seconds since the epoch (e.g. Kraken `1499.234`).
 */
@Serializable
enum class TimestampFormat {
    ISO_8601,
    EPOCH_MS,
    EPOCH_S,
    EPOCH_S_FLOAT,

    /**
     * A custom date-time pattern (e.g. Binance withdrawal history's `"yyyy-MM-dd HH:mm:ss"`, always
     * UTC), given per-mapping in `timestampPattern`. Not a Java/ICU pattern — see
     * `parseApiTimestamp` (`app:apiimporter`) for the small set of tokens supported.
     */
    PATTERN,
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
 *                           date window (see `ApiPaginationConfig.DateWindow`)
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
 * How a [ApiDateWindowing] window bound is encoded into its start/end request parameters.
 *
 * [EPOCH_MS] — integer milliseconds since the epoch (Crypto.com).
 * [EPOCH_S] — integer whole seconds since the epoch (Kraken `start`/`end`).
 * [ISO_8601] — an ISO-8601 instant string (Wise, Starling).
 */
@Serializable
enum class WindowBoundFormat {
    EPOCH_MS,
    EPOCH_S,
    ISO_8601,
}

/**
 * Splits an endpoint's history into fixed-length date windows, one request unit per window, each bounded
 * by [startParam]/[endParam] (also exposed to templating as `window.start`/`window.end`). Windows reach
 * back [lookbackDays] and are anchored to fixed [windowDays] boundaries, so earlier windows produce
 * stable, cacheable URLs; only the final window (ending "now") shifts across re-imports.
 *
 * @property rangeErrorSubstrings Case-insensitive error-body substrings meaning "this particular window
 *   is outside the range the provider will serve" (Binance `asset/transfer` only answers for the last 6
 *   months; its Simple Earn history caps the span at 30 days). A window failing with one of these is
 *   skipped and the newer ones still run, instead of abandoning the whole endpoint. Never encoded when
 *   left at the default (which covers the Binance phrasings), so it is still applied to configs written
 *   before it existed.
 */
@Serializable
data class ApiDateWindowing(
    val startParam: String = "intervalStart",
    val endParam: String = "intervalEnd",
    val windowDays: Int = 469,
    val lookbackDays: Int = 365 * 6,
    val boundFormat: WindowBoundFormat = WindowBoundFormat.EPOCH_MS,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedStringListSerializer::class)
    val rangeErrorSubstrings: List<String> = listOf("range is too large", "time range too large"),
)

/**
 * How one request unit (the whole endpoint, or one [ApiDateWindowing] window) is paged. Every scheme
 * stops on an empty page; [ApiPaginationConfig.limitValue] is the provider's page size.
 */
@Serializable
sealed interface ApiPaging {
    /** One request per unit — the response holds everything (or the provider caps it and there is no more). */
    @Serializable
    @SerialName("single")
    data object Single : ApiPaging

    /**
     * An integer [param] that starts at 0 and advances by the page size (Kraken `ofs`), or with
     * [pageNumbers] starts at 1 and advances by 1 (Binance fiat `page`). Ends on a short page, or once
     * [totalCountField] (a dot-path into the response envelope) items have been read.
     */
    @Serializable
    @SerialName("offset")
    data class Offset(
        val param: String,
        val pageNumbers: Boolean = false,
        val totalCountField: String? = null,
    ) : ApiPaging

    /**
     * Newest-first paging (Monzo): each page after the first sends [param] = the earliest
     * [positionField] timestamp of the previous page. An incremental download stops once a page reaches
     * back past what an earlier download covered.
     */
    @Serializable
    @SerialName("before")
    data class BeforeCursor(
        val param: String = "before",
        val positionField: String = "created",
    ) : ApiPaging

    /**
     * A forward sweep by ascending numeric id, for an endpoint whose time-range parameters are too short
     * to sweep a full history (Binance `myTrades` caps `startTime`/`endTime` to 24h apart): each page
     * after the first sends [param] = one past the largest [idField] seen, until a short page. Ignores
     * the incremental watermark — a time can't seed an id cursor — and every download walks the id space
     * from the start; the import deduper absorbs the overlap.
     */
    @Serializable
    @SerialName("forwardId")
    data class ForwardId(
        val param: String,
        val idField: String,
    ) : ApiPaging

    /**
     * An opaque next-page token the response supplies at [tokenField] (a dot-path into the envelope:
     * Coinbase `pagination.next_starting_after`, Bybit `result.nextPageCursor`), sent back as [param]
     * until it comes back blank. With [urlEncoded], the token arrives already percent-encoded and is
     * decoded before sending (it would otherwise be double-encoded). Outside a date window, an
     * incremental walk stops once a page's oldest [positionField] (ISO-8601 or epoch) predates what an
     * earlier download covered — so that relies on newest-first order.
     */
    @Serializable
    @SerialName("token")
    data class Token(
        val tokenField: String,
        val param: String,
        val urlEncoded: Boolean = false,
        val positionField: String? = null,
    ) : ApiPaging
}

/**
 * Pagination for an endpoint: an optional [window] split of its history, times a [paging] scheme within
 * each request unit. [limitValue] is the page size every scheme compares against; with [sendLimitParam]
 * it is also sent as [limitParam] (some providers' default page size differs unless told explicitly).
 * [extraParams] are added to every windowed request.
 *
 * Incremental downloads: when earlier sessions of the same credential already covered a period, the
 * download starts at that watermark minus [incrementalOverlapDays] rather than at the window's lookback
 * (or, for [ApiPaging.BeforeCursor]/[ApiPaging.Token] walks, stops there), so rows a provider posts with
 * a backdated timestamp after a download are still picked up. The import deduper absorbs the overlap.
 */
@Serializable
data class ApiPaginationConfig(
    val window: ApiDateWindowing? = null,
    val paging: ApiPaging = ApiPaging.Single,
    val limitParam: String = "limit",
    val limitValue: Int = 100,
    val sendLimitParam: Boolean = false,
    @Serializable(with = SortedQueryParamListSerializer::class)
    val extraParams: List<ApiQueryParam> = emptyList(),
    val incrementalOverlapDays: Int = 7,
)

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
)

/**
 * JSON field names used to extract account data from an API response item. Defaults match the
 * Monzo response shape so existing strategies behave identically.
 *
 * @property idField Field containing the external account identifier (e.g. "id")
 * @property descriptionField Field containing the account description or name (e.g. "description")
 * @property ownerNameField Nested field path to owner names; when set the names are extracted from
 *                          the owners array at this sub-path and used as a fallback for blank
 *                          descriptions. Example: "preferred_name".
 * @property ownersArrayField Field holding the owners array; null when the API has no owners array.
 * @property ownerUserIdField Field within an owner object holding its stable external id.
 * @property ownerNameFallbackField Field within an owner object holding a display name fallback.
 * @property sortCodeField Account/owner field holding a bank sort code.
 * @property accountNumberField Account/owner field holding a bank account number.
 * @property currencyField Optional account-level currency field (e.g. Wise "currency"); exposed to
 *                         templating as account.currency.
 * @property idExtraction Rewrites the [idField] value into the account's stored external id. External ids
 *                        are matched across every provider, so an id that is only unique within the
 *                        provider (PayPal's per-currency balances carry nothing but `currency`) needs a
 *                        provider prefix (`paypal:$0`). Requests still use the raw id.
 * @property descriptionExtraction Rewrites the [descriptionField] value into the account name
 *                                 (`PayPal $0`). Kept when its pattern doesn't match.
 */
@Serializable
data class ApiAccountMappings(
    val idField: String = "id",
    val descriptionField: String = "description",
    val ownerNameField: String? = null,
    val ownersArrayField: String? = "owners",
    val ownerUserIdField: String = "user_id",
    val ownerNameFallbackField: String = "name",
    val sortCodeField: String = "sort_code",
    val accountNumberField: String = "account_number",
    val currencyField: String? = null,
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val customFields: Map<String, String> = emptyMap(),
    @Serializable(with = SortedStringSetSerializer::class)
    val uniqueIdentifierFields: Set<String> = emptySet(),
    /**
     * A fixed display name for every account this strategy creates, overriding [descriptionField]
     * (some providers' account "description" is an internal identifier never meant for display, e.g.
     * Monzo's is the account holder's own user id). A joint account (more than one resolved owner)
     * gets " Joint" appended so it's distinguishable from the holder's individual account.
     */
    val staticAccountName: String? = null,
    /**
     * Rules appending a distinguishing suffix to [staticAccountName] for accounts of a recognisable
     * sub-kind (e.g. Monzo's `type == "uk_rewards"` -> "Monzo Rewards"), evaluated in order with the
     * first match winning. Only used when [staticAccountName] is set.
     */
    val accountNameRules: List<ApiAccountNameRule> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val idExtraction: Extraction? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val descriptionExtraction: Extraction? = null,
)

/** How a transaction amount value is encoded in the API response. */
@Serializable
enum class ApiAmountFormat {
    /** Integer amount already expressed in minor currency units (e.g. Monzo: 1234 == 12.34). */
    MINOR_UNITS_INTEGER,

    /** Decimal amount in major currency units (e.g. Wise: "12.34"); converted via Money. */
    DECIMAL_MAJOR_UNITS,
}

/**
 * How a ledger's trade-type rows — the rows [ApiTransactionMappings.excludeWhen] drops as transfers —
 * group back into trades: rows whose [key] agrees are one trade's legs.
 *
 * Each group's signed amounts are authoritative: a trade another endpoint reports with the same id (or,
 * failing that, the same second and asset pair) takes its leg amounts from the group — Kraken's
 * TradesHistory `cost` is a display-rounded price*volume for synthetic crypto/crypto pairs and can
 * disagree with what the ledger actually settled — and a group no trade claims is booked as its own
 * trade when its legs net to one asset out and one asset in, so no movement the ledger reports is ever
 * silently dropped.
 *
 * @property key The trade a row belongs to — Kraken Ledgers `refid`; Coinbase `buy.id`, `sell.id` or
 *   `trade.id` (the first non-blank of its paths).
 * @property unpairedCounterAmountPath Dot-path to an amount object (`{amount, currency}`) giving the other
 *   side of a group of only one leg — a purchase paid for straight from a card or bank, so no ledger row
 *   records the money leaving (Coinbase `native_amount`). Such a group is booked as a trade against that
 *   amount, plus a transfer of it between [unpairedFundingAccountName] and the exchange account so the
 *   exchange's balance of that asset still nets to zero. Null drops single-leg groups.
 * @property unpairedFundingAccountName The account such a trade is funded from (a purchase) or paid out
 *   to (a sale); null uses the strategy-wide "<account> Funding".
 */
@Serializable
data class ApiLedgerTrades(
    val key: ValueExpr,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unpairedCounterAmountPath: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unpairedFundingAccountName: String? = null,
)

/**
 * JSON field names used to extract transaction data from an API response item. Defaults match the
 * Monzo response shape so existing strategies behave identically.
 *
 * @property amountField Dot-path to the amount value (e.g. "amount" or "amount.value")
 * @property timestampField Dot-path to the ISO-8601 timestamp (e.g. "created", "date")
 * @property currencyField Dot-path to the ISO-4217 currency code (e.g. "currency", "amount.currency")
 * @property descriptionField Dot-path to the transaction description
 * @property amountFormat How [amountField] is encoded; see [ApiAmountFormat]
 * @property direction Which way the money moves. Null takes the endpoint's own: a bank feed's signed
 *                  amount ([Direction.AmountSign]), a deposit endpoint's in, a withdrawal endpoint's
 *                  out. [Direction.Field] reads it from a field (Starling's `direction` = `IN`; Bybit
 *                  Earn orders listing both directions), skipping a bank item whose field is missing.
 *                  [Direction.AmountSign] on a deposit/withdrawal endpoint follows the raw amount's
 *                  sign instead: Kraken books a failed deposit or withdrawal as a second ledger entry of
 *                  the opposite sign, so the movement nets to zero rather than double-booking.
 * @property idField Dot-path to the transaction's stable id, used for de-duplication
 * @property merchantNameField Optional dot-path to a merchant name; preferred counterparty name
 * @property counterpartyNameField Optional dot-path to a counterparty name; fallback for merchant
 * @property declinedWhen Conditions that each mark a transaction as declined — imported but excluded
 *                        from balances. The first that holds wins, and the value at its path becomes the
 *                        decline reason: Monzo's `decline_reason` NOT_BLANK, Starling's `status` IN
 *                        `DECLINED`. Order is semantic (it picks the reason).
 * @property foreignAmount What the counterparty was paid when the account settled in another currency
 *                       (a card abroad), parsed with [amountFormat]: the movement is booked in that
 *                       currency and the account's conversion as a trade (see [ForeignAmount]).
 * @property fee A fee charged on the transaction, imported as its own transfer linked to it via a `fee`
 *            relationship (see [FeeRule]); its amount is encoded using [amountFormat]. Monzo's
 *            `atm_fees_detailed` is a fee [FeeRule.includedInAmount]: `amount = withdrawal + fee`.
 * @property ledgerTrades How a ledger's trade-type rows group into trades (see [ApiLedgerTrades]).
 */
@Serializable
data class ApiTransactionMappings(
    val amountField: String = "amount",
    val timestampField: String = "created",
    val timestampFormat: TimestampFormat = TimestampFormat.ISO_8601,
    /** Pattern string for [TimestampFormat.PATTERN]; ignored for every other [timestampFormat]. */
    val timestampPattern: String? = null,
    val currencyField: String = "currency",
    val descriptionField: String = "description",
    val amountFormat: ApiAmountFormat = ApiAmountFormat.MINOR_UNITS_INTEGER,
    val direction: Direction? = null,
    val idField: String = "id",
    val merchantNameField: String? = null,
    val counterpartyNameField: String? = null,
    val counterpartyIdField: String? = null,
    val declinedWhen: List<Condition> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val foreignAmount: ForeignAmount? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fee: FeeRule? = null,
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val customFields: Map<String, String> = emptyMap(),
    @Serializable(with = SortedStringSetSerializer::class)
    val uniqueIdentifierFields: Set<String> = emptySet(),
    /**
     * Dot-path to a blockchain wallet address on a deposit/withdrawal item (e.g. "address" for a
     * withdrawal destination, "source_address" for a deposit sender). When set and non-blank, the
     * counterparty is modelled as a per-wallet account keyed by this address (so the same wallet
     * reconciles across sources) rather than the generic funding account.
     */
    val counterpartyAddressField: String? = null,
    /** Optional dot-path to the blockchain network of the wallet (e.g. "network_id"), used to label it. */
    val counterpartyNetworkField: String? = null,
    /**
     * Dot-path to the on-chain transaction id (e.g. "txid"). When set, it is stored as a cross-source
     * unique identifier on the transfer so the same on-chain movement seen from another source (another
     * exchange or a wallet import) can be reconciled to it.
     */
    val txidField: String? = null,
    /**
     * Dot-path to a field whose value can alias the counterparty to a named, already-owned account
     * (e.g. "address"): a Crypto.com Exchange internal deposit has `address` = "INTERNAL_DEPOSIT",
     * meaning the funds came from the Crypto.com App account. See [counterpartyAccountAliases].
     */
    val counterpartyAliasField: String? = null,
    /**
     * Maps a [counterpartyAliasField] value to the name of an owned account that is the real
     * counterparty (e.g. {"INTERNAL_DEPOSIT": "Crypto.com"}). When matched, the transfer is booked
     * directly against that account instead of a wallet/funding account, so the same movement recorded
     * by another strategy (the CSV "Crypto.com" App export) reconciles to it regardless of import order.
     */
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val counterpartyAccountAliases: Map<String, String> = emptyMap(),
    /**
     * Dot-path to a field on this item whose value is looked up against the id index built from any
     * [ApiDataEndpoint.enrichesTransfers] endpoint (e.g. Kraken Ledgers `refid`, matched against
     * DepositStatus/WithdrawStatus `refid`). A hit fills in [counterpartyAddressField]/[txidField]/
     * [counterpartyNetworkField] from the enrichment item. Null disables enrichment for this mapping.
     */
    val joinKeyField: String? = null,
    /**
     * Conditions that, when all hold (and there is at least one), make the item produce no transfer and
     * no enrichment. Used when a single endpoint's response mixes record kinds that must be dropped —
     * e.g. Kraken's Ledgers `type=all` includes `"trade"` entries that duplicate the trades already
     * sourced from `TradesHistory`.
     */
    @Serializable(with = SortedConditionListSerializer::class)
    val excludeWhen: List<Condition> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ledgerTrades: ApiLedgerTrades? = null,
    /**
     * Conditions that must all hold (logical AND) against the item's raw JSON for it to be imported at
     * all — the include-form counterpart of [excludeWhen] (e.g. Binance's
     * `status == 1` on deposits, `status == 6` on withdrawals). Empty imposes no filter.
     */
    @Serializable(with = SortedConditionListSerializer::class)
    val itemFilters: List<Condition> = emptyList(),
    /**
     * Dot-paths joined (in list order, hyphen-separated) into the transfer's de-duplication id instead of
     * [idField], for an endpoint whose rows carry no id at all (Binance Simple Earn `rewardsRecord`, whose
     * rows are only asset + project + type + time + amount). Order is semantic (produces a readable, stable
     * composite key) - keeps default insertion-order serialization. Empty (the default) uses [idField] alone.
     */
    val compositeIdFields: List<String> = emptyList(),
)

@Serializable
data class ApiPeopleMappings(
    // Blank resolves to the transaction item itself, for providers whose counterparty fields are flat
    // (e.g. Starling) rather than nested under an object (e.g. Monzo's "counterparty").
    val counterpartyObjectField: String = "counterparty",
    val beneficiaryAccountTypeField: String = "beneficiary_account_type",
    val personalBeneficiaryAccountTypeValue: String = "Personal",
    // Additional values of [beneficiaryAccountTypeField] that mark a counterparty as a person, for
    // providers that classify person-like counterparties under several types (e.g. Starling's
    // PAYEE/SENDER). A counterparty is personal when its type matches [personalBeneficiaryAccountTypeValue]
    // or any of these.
    @Serializable(with = SortedStringSetSerializer::class)
    val personalBeneficiaryAccountTypeValues: Set<String> = emptySet(),
    val counterpartyNameField: String = "name",
    val counterpartyUserIdField: String = "user_id",
    val counterpartySortCodeField: String = "sort_code",
    val counterpartyAccountNumberField: String = "account_number",
    val counterpartyServiceUserNumberField: String = "service_user_number",
    // Field (relative to [counterpartyObjectField]) holding the counterparty's own account id, used as
    // a fallback identity when [ApiTransactionMappings.counterpartyIdField] has no value on this
    // transaction (e.g. Monzo's `counterparty.id` is absent, but `counterparty.account_id` is always
    // present). Checked before falling back to a person's [counterpartyUserIdField], since that fallback
    // is not stable across a self-transfer (Monzo reports the account holder's own user id there).
    val counterpartyAccountIdField: String = "account_id",
    // Prefixes marking a resolved counterparty id as ephemeral — a throwaway id that changes per
    // transaction and so must NOT identify the counterparty account, or the same real counterparty
    // fragments into one account per transaction (e.g. Monzo issues a fresh "anonuser_…" user id for
    // every bank transfer to the same person). When the id begins with one of these it is discarded
    // and the counterparty is matched by name instead. Empty = treat every id as stable.
    @Serializable(with = SortedStringSetSerializer::class)
    val ephemeralCounterpartyIdPrefixes: Set<String> = emptySet(),
    // When true, a counterparty's bank sub-entity (sort code + account number) identifies the
    // counterparty account in preference to [ApiTransactionMappings.counterpartyIdField]. Set for
    // providers (e.g. Starling) whose per-counterparty id can still split a single real bank account
    // across entries — the same account may appear under several counterparty ids (as a payee and a
    // sender). The id remains the fallback when no bank details are present, then the name.
    val preferBankIdentity: Boolean = false,
)

/** Sign gate for a [BuiltInCounterpartyRule]; restricts the rule to incoming or outgoing transactions. */
@Serializable
enum class RuleSign { ANY, NEGATIVE, POSITIVE }

/**
 * A declarative rule that classifies a transaction as a well-known built-in counterparty (e.g.
 * "ATM"), so such transactions consolidate into a single account regardless of merchant details.
 * All [predicates] must match (logical AND) and the [onlyWhenSign] gate must hold.
 *
 * @property name Built-in type name; stored as the account's built-in-type attribute and used as
 *                the default counterparty account name.
 * @property onlyWhenSign Restricts the rule to incoming/outgoing transactions; ANY disables the gate.
 * @property predicates Conditions that must all hold for the rule to match.
 */
@Serializable
data class BuiltInCounterpartyRule(
    val name: String,
    val onlyWhenSign: RuleSign = RuleSign.ANY,
    @Serializable(with = SortedConditionListSerializer::class)
    val predicates: List<Condition> = emptyList(),
)

/**
 * A declarative rule appending [suffix] to a downloaded own account's [ApiAccountMappings.staticAccountName]
 * when all [predicates] match its raw account JSON (e.g. Monzo's `type == "uk_rewards"` -> "Rewards", so
 * that account is named "Monzo Rewards" instead of colliding with the main "Monzo" account). Evaluated
 * in list order; the first matching rule wins, so list order is meaningful (unlike [predicates] within
 * a single rule, which are all required). Only used when [ApiAccountMappings.staticAccountName] is set.
 *
 * @property suffix Appended (space-separated) to the static name when this rule matches.
 * @property predicates Conditions that must all hold (logical AND) for this rule to match.
 */
@Serializable
data class ApiAccountNameRule(
    val suffix: String,
    @Serializable(with = SortedConditionListSerializer::class)
    val predicates: List<Condition> = emptyList(),
)

/**
 * Strong Customer Authentication (request-signing) parameters for a provider that protects some
 * endpoints behind a challenge-response signature (e.g. Wise balance statements). When a request is
 * rejected with [triggerStatus] and returns a one-time token in [challengeHeader], the importer signs
 * the token with the credential's private key and retries with the signature in [signatureHeader].
 *
 * @property challengeHeader Response header carrying the one-time token (e.g. "x-2fa-approval")
 * @property signatureHeader Request header for the Base64 signature on retry (e.g. "X-Signature")
 * @property triggerStatus HTTP status that signals a signing challenge (e.g. 403)
 * @property statementCountries ISO 3166-1 alpha-2 country codes whose accounts can retrieve
 *                             statements/transactions via the API. Empty means no restriction; when
 *                             non-empty the UI disables transaction download for other locales (Wise
 *                             only supports statements for US/CA/AU/NZ/SG/MY).
 */
@Serializable
data class ApiSigningConfig(
    val challengeHeader: String = "x-2fa-approval",
    val signatureHeader: String = "X-Signature",
    val triggerStatus: Int = 403,
    @Serializable(with = SortedStringSetSerializer::class)
    val statementCountries: Set<String> = emptySet(),
)

/**
 * Configuration for downloading and importing the account holder(s) — the person whose credentials
 * are used — from a dedicated endpoint (e.g. Wise `/v1/profiles`). Present only for providers that
 * expose owner identity separately from the accounts; null disables the "Download People" feature.
 *
 * @property endpoint The people/profiles endpoint to fetch
 * @property externalIdField Dot-path to the person's stable external id (e.g. "id")
 * @property firstNameField Dot-path to the first name (e.g. "details.firstName")
 * @property lastNameField Optional dot-path to the last name (e.g. "details.lastName")
 * @property preferredNameField Optional dot-path to a preferred name
 * @property fallbackNameField Optional dot-path to a single display name used when the name fields
 *                             are blank (e.g. business profiles: "details.name")
 * @property accountOwnerAncestorExpr When set, links each imported person to the accounts fetched
 *                                    under the matching ancestor value (e.g. "ancestor[0].id" links a
 *                                    profile to the balances fetched under that profile id).
 * @property ownsAllAccounts When true, the holder(s) returned by [endpoint] are linked to every
 *                           account imported in the session, regardless of ancestor/id. Use for flat
 *                           providers with a single global account holder and no ancestor hierarchy
 *                           (e.g. Starling). Mutually exclusive with [accountOwnerAncestorExpr].
 */
@Serializable
data class ApiPersonImportConfig(
    val endpoint: ApiEndpointConfig,
    val externalIdField: String = "id",
    val firstNameField: String,
    val lastNameField: String? = null,
    val preferredNameField: String? = null,
    val fallbackNameField: String? = null,
    val accountOwnerAncestorExpr: String? = null,
    val ownsAllAccounts: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Proactive request signing (ApiStrategyConfig.requestSigning) — a generic, provider-agnostic HMAC recipe.
// One config shape expresses Crypto.com, Binance and Kraken signing without any per-provider code.
// ---------------------------------------------------------------------------------------------

/** HMAC hash function used to sign a request. */
@Serializable
enum class SigningAlgorithm {
    HMAC_SHA256,
    HMAC_SHA512,
}

/** How the API secret is decoded before use as the HMAC key. */
@Serializable
enum class SecretEncoding {
    /** The secret is used as raw UTF-8 bytes (Crypto.com, Binance). */
    UTF8,

    /** The secret is Base64-decoded to bytes first (Kraken). */
    BASE64,
}

/** How the computed HMAC digest is encoded for transmission. */
@Serializable
enum class SignatureEncoding {
    HEX,
    BASE64,
}

/** How a set of request parameters is serialised into part of the signed message. */
@Serializable
enum class ParamStringFormat {
    /** Keys sorted ascending, concatenated with no separators: `k1v1k2v2…` (Crypto.com). */
    SORTED_CONCAT,

    /** `k1=v1&k2=v2` query-string form (Binance). */
    QUERY_STRING,
}

/** Where a signing field (api key, nonce, signature) is placed on the outgoing request. */
@Serializable
enum class SigFieldLocation {
    HEADER,
    QUERY,
    BODY_FIELD,
}

/** How the nonce value is generated. */
@Serializable
enum class NonceFormat {
    EPOCH_MS,
    EPOCH_US,
    EPOCH_NS,

    /** A strictly increasing counter (rarely needed; timestamp forms are preferred). */
    INCREMENTING,
}

/** How the per-request `id` (Crypto.com) is generated. */
@Serializable
enum class RequestIdFormat {
    INCREMENTING,
    EPOCH_MS,
}

/** How request parameters are marshalled into the HTTP request body. */
@Serializable
enum class BodyFormat {
    /** No body; parameters live in the query string (GET, or Binance signed GET/POST). */
    NONE,

    /** A JSON object `{...}` wrapping the signing fields and params (Crypto.com). */
    JSON_ENVELOPE,

    /** `application/x-www-form-urlencoded` body (Kraken). */
    FORM_URLENCODED,

    /** Params go only in the query string, signature appended there (Binance). */
    QUERY_ONLY,
}

/** Placement of one signing field on the request. */
@Serializable
data class FieldPlacement(
    val location: SigFieldLocation,
    val name: String,
)

/** Nonce generation + placement. */
@Serializable
data class NonceSpec(
    val format: NonceFormat = NonceFormat.EPOCH_MS,
    val placement: FieldPlacement,
)

/** Per-request id generation + placement (Crypto.com `id`). */
@Serializable
data class RequestIdSpec(
    val format: RequestIdFormat = RequestIdFormat.INCREMENTING,
    val placement: FieldPlacement,
)

/**
 * One ordered fragment of the message that gets HMAC-signed. The signer concatenates the bytes each
 * part produces, in list order, then signs the result. This is expressive enough for all three target
 * exchanges (see [ApiRequestSigningConfig]).
 */
@Serializable
sealed interface SigPart {
    /** A fixed literal string. */
    @Serializable
    data class Literal(
        val text: String,
    ) : SigPart

    /** The API method name (Crypto.com: the endpoint path minus base, e.g. "private/get-trades"). */
    @Serializable
    data object Method : SigPart

    /** The per-request id (Crypto.com `id`). */
    @Serializable
    data object RequestId : SigPart

    /** The API key. */
    @Serializable
    data object ApiKey : SigPart

    /** The nonce value. */
    @Serializable
    data object Nonce : SigPart

    /** The request URI path (Kraken). */
    @Serializable
    data object Path : SigPart

    /** The request query string without the leading `?` (Binance). */
    @Serializable
    data object QueryString : SigPart

    /** The raw request body (Binance body, Kraken form-encoded post data). */
    @Serializable
    data object Body : SigPart

    /** The request parameters serialised via [format] (Crypto.com sorted-concat). */
    @Serializable
    data class ParamString(
        val format: ParamStringFormat = ParamStringFormat.SORTED_CONCAT,
    ) : SigPart

    /**
     * SHA-256 of the concatenation of [parts], emitted as raw bytes (Kraken's nested hash). Order is
     * semantic (bytes are concatenated in list order) - keeps default insertion-order serialization.
     */
    @Serializable
    data class Sha256(
        val parts: List<SigPart>,
    ) : SigPart
}

/**
 * A complete, provider-agnostic recipe for signing a request. The engine computes
 * `sig = HMAC(secretEncoding(secret))` over the bytes produced by [message], encodes it via
 * [signatureEncoding], and places api key / nonce / (optional) id / signature per their placements.
 *
 * The three target exchanges map to three literal values of this type — no code branches per provider:
 * - **Crypto.com**: HMAC_SHA256, UTF8, HEX, message = [Method, RequestId, ApiKey, ParamString(SORTED_CONCAT), Nonce];
 *   api key/nonce/id/sig in BODY_FIELD; [bodyFormat] JSON_ENVELOPE with [paramsEnvelopeKey] = "params".
 * - **Binance**: HMAC_SHA256, UTF8, HEX, message = [QueryString, Body]; api key in HEADER `X-MBX-APIKEY`,
 *   nonce = `timestamp` in QUERY, sig in QUERY `signature`; [bodyFormat] QUERY_ONLY.
 * - **Kraken**: HMAC_SHA512, BASE64 secret, BASE64 sig, message = [Path, Sha256([Nonce, Body])]; api key in
 *   HEADER `API-Key`, nonce in BODY_FIELD, sig in HEADER `API-Sign`; [bodyFormat] FORM_URLENCODED.
 */
@Serializable
data class ApiRequestSigningConfig(
    val algorithm: SigningAlgorithm = SigningAlgorithm.HMAC_SHA256,
    val secretEncoding: SecretEncoding = SecretEncoding.UTF8,
    val signatureEncoding: SignatureEncoding = SignatureEncoding.HEX,
    // Order is semantic (bytes are concatenated in list order before signing) - keeps default
    // insertion-order serialization.
    // The HMAC recipe below is required unless [jwt] is set; defaulted (and never encoded when left at
    // the default) only so a JWT-signed strategy needn't carry an unused one.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val message: List<SigPart> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val apiKey: FieldPlacement? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val nonce: NonceSpec? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val signature: FieldPlacement? = null,
    val requestId: RequestIdSpec? = null,
    /** Where the API method name is written on the request (Crypto.com body field "method"); null omits it. */
    val method: FieldPlacement? = null,
    val bodyFormat: BodyFormat = BodyFormat.NONE,
    /** For [BodyFormat.JSON_ENVELOPE], the key under which request params are nested (Crypto.com "params"). */
    val paramsEnvelopeKey: String? = null,
    /**
     * Extra parameters added to (and therefore covered by the signature of) **every** signed request.
     * For a provider whose tolerance for a stale nonce is itself a request parameter — Binance's
     * `recvWindow`, which defaults to a mere 5s and is what rejects a request with
     * `-1021 Timestamp for this request is outside of the recvWindow`.
     *
     * Omitted from JSON when empty (@EncodeDefault NEVER) so adding it does not change the canonical
     * hash of every existing API strategy — only one that actually sets it rehashes.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedQueryParamListSerializer::class)
    val signedParams: List<ApiQueryParam> = emptyList(),
    /**
     * When set, the clock used for [nonce] is corrected by the provider's own clock before the first
     * signed request. A provider that rejects a nonce too far from its server time is comparing against
     * *its* clock, and an NTP-synced machine can still sit a second or more away from it — which eats
     * most of the tolerance before the request is even sent, and, if the local clock runs fast instead,
     * cannot be compensated by widening that tolerance at all.
     *
     * Same NEVER-encode rationale as [signedParams].
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val serverTimeSync: ApiServerTimeSync? = null,
    /**
     * Fixed headers sent on **every** HMAC-signed request — for a provider whose stale-nonce tolerance is a
     * header rather than a request parameter (Bybit's `X-BAPI-RECV-WINDOW`, which is also part of the signed
     * message, so a strategy repeats its value as a [SigPart.Literal] in [message]). Same NEVER-encode
     * rationale as [signedParams].
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val staticHeaders: Map<String, String> = emptyMap(),
    /**
     * When set, requests are authenticated with a freshly signed JWT instead of an HMAC signature, and
     * [message]/[apiKey]/[nonce]/[signature]/[algorithm] are ignored. Same NEVER-encode rationale as
     * [signedParams].
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val jwt: JwtSigningConfig? = null,
)

/** Asymmetric algorithm a [JwtSigningConfig] signs with. */
@Serializable
enum class JwtAlgorithm {
    /** ECDSA over P-256 with SHA-256; the api secret is a P-256 private key (PEM, or its base64 DER). */
    ES256,

    /** Ed25519; the api secret is a base64 32-byte seed or 64-byte seed + public key, or a PEM key. */
    EdDSA,

    /**
     * Whichever of the above the api secret is a key for — for a provider that issues both kinds (Coinbase
     * CDP keys are Ed25519 by default, ECDSA on request). Pair with an `alg` header of `{alg}`.
     */
    DETECT,
}

/**
 * One header or claim of a signed JWT. [template] may reference `{alg}` (the JWS name of the algorithm
 * the token is signed with), `{apiKey}`, `{nonceHex}` (a fresh random hex string), `{now}`/`{exp}` (epoch seconds, now and now + [JwtSigningConfig.ttlSeconds]),
 * `{method}` (the HTTP verb), `{host}` and `{path}` (the request URI path, without the query string).
 * When [numeric] is true the rendered value is emitted as a JSON number rather than a string.
 */
@Serializable
data class JwtField(
    val name: String,
    val template: String,
    val numeric: Boolean = false,
)

/**
 * Per-request JWT authentication: the engine renders [header] and [claims], signs
 * `base64url(header).base64url(claims)` with the api secret per [algorithm], and places the token
 * (prefixed by [prefix]) per [placement]. Coinbase CDP keys use ES256 with
 * `kid`/`nonce` header fields and `iss`/`sub`/`nbf`/`exp`/`uri` claims, sent as `Authorization: Bearer`.
 *
 * [header]/[claims] order is semantic (it's the serialised order) - keeps default insertion-order
 * serialization.
 */
@Serializable
data class JwtSigningConfig(
    val algorithm: JwtAlgorithm = JwtAlgorithm.ES256,
    val header: List<JwtField> = emptyList(),
    val claims: List<JwtField> = emptyList(),
    val ttlSeconds: Long = 120,
    val placement: FieldPlacement = FieldPlacement(SigFieldLocation.HEADER, "Authorization"),
    val prefix: String = "Bearer ",
)

/**
 * Locates a provider's current server time, so signed requests can be stamped with the provider's clock
 * rather than the local one (see [ApiRequestSigningConfig.serverTimeSync]).
 *
 * @property path Unsigned endpoint path relative to the strategy's base URL (Binance `api/v3/time`).
 * @property field Dot-path to the epoch-millisecond value in the response (Binance `serverTime`).
 */
@Serializable
data class ApiServerTimeSync(
    val path: String,
    val field: String,
)

// ---------------------------------------------------------------------------------------------
// Endpoint fan-out — call one endpoint once per value in a runtime-derived set, for a provider whose
// API has no account-wide feed for some resource and instead scopes it per-symbol/per-asset (Binance
// `myTrades` requires a `symbol`). Generic: any exchange needing this reuses the same shape.
// ---------------------------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------------------------
// Multiple data endpoints + entity kinds — exchanges expose several endpoints (trades, deposits,
// withdrawals, order history), each mapping to a different kind of imported record.
// ---------------------------------------------------------------------------------------------

/** What kind of imported record an [ApiDataEndpoint] produces. */
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

/** Which way an exchange transfer moves relative to the exchange account. */
@Serializable
enum class TransferDirection {
    IN,
    OUT,
}

/**
 * One data endpoint of a strategy that has several (exchanges). Each pairs an [endpoint] with the
 * [kind] of record it yields and the mappings needed to interpret it.
 *
 * @property transactionMappings Field mappings for [ApiEndpointKind.BANK_TRANSACTIONS]/DEPOSITS/WITHDRAWALS.
 * @property tradeMappings Field mappings for [ApiEndpointKind.TRADES]/ORDERS.
 * @property counterpartyAccountName For DEPOSITS/WITHDRAWALS, the fixed account the money comes from /
 *                                   goes to when the row carries neither a counterparty alias nor a wallet
 *                                   address (e.g. "Binance Earn" for a Simple Earn subscription, "Binance
 *                                   Bank" for a fiat on-ramp). Null falls back to the strategy-wide
 *                                   "<synthetic account> Funding".
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
    val counterpartyAccountName: String? = null,
    val enrichesTransfers: Boolean = false,
)

/** How a trading pair symbol is split into its base and quote assets. */
@Serializable
enum class InstrumentSplitMode {
    /** Split on a separator character (Crypto.com "BTC_USD"). */
    SEPARATOR,

    /** Explicit base/quote fields on the item (some order endpoints). */
    EXPLICIT_FIELDS,

    /** Strip a known quote-asset suffix from a separator-less symbol (Binance "BTCUSDT"). */
    QUOTE_SUFFIX,
}

/**
 * Field mappings for a trade (fill) endpoint. A fill exchanges a base asset for a quote asset. The
 * engine derives two [com.moneymanager.domain.model.Money] legs: base = [baseQuantityField] of the base
 * asset; quote = [quoteQuantityField] (or [baseQuantityField] × [priceField]) of the quote asset. For a
 * BUY the quote leg leaves and the base leg arrives; a SELL reverses them. Both legs sit on the single
 * exchange account, so the movement is a cross-asset [com.moneymanager.domain.model.Trade].
 *
 * @property instrumentField Dot-path to the pair symbol (e.g. "instrument_name"); unused (null) with
 *   [InstrumentSplitMode.EXPLICIT_FIELDS].
 * @property splitMode How [instrumentField] is split into base/quote assets.
 * @property instrumentSeparator Separator for [InstrumentSplitMode.SEPARATOR] (default "_").
 * @property baseAssetField/quoteAssetField Dot-paths for [InstrumentSplitMode.EXPLICIT_FIELDS].
 * @property fixedBaseAsset/fixedQuoteAsset Constant asset codes used when the matching field is null.
 * @property quoteAssets Known quote assets for [InstrumentSplitMode.QUOTE_SUFFIX] (longest match wins).
 * @property sideField Dot-path to the buy/sell side; [buyValues] enumerates the values meaning BUY.
 * @property baseQuantityField Dot-path to the base-asset quantity traded.
 * @property priceField Dot-path to the price (quote per base); quote amount = quantity × price.
 * @property quoteQuantityField Dot-path to an explicit quote amount (used instead of price when present).
 * @property fee The trade's fee and its asset (see [FeeRule]); the asset defaults to the quote asset.
 * @property timestampField/timestampFormat Dot-path + encoding of the fill timestamp.
 * @property idField Dot-path to the stable trade id (used for de-duplication).
 * @property orderIdField Optional dot-path to the owning order id (joins ORDERS metadata onto the trade).
 */
@Serializable
data class ApiTradeMappings(
    val instrumentField: String? = null,
    val splitMode: InstrumentSplitMode = InstrumentSplitMode.SEPARATOR,
    val instrumentSeparator: String = "_",
    val baseAssetField: String? = null,
    val quoteAssetField: String? = null,
    /**
     * A constant base asset code for [InstrumentSplitMode.EXPLICIT_FIELDS], used when [baseAssetField] is
     * null because the item never names the asset it acquired (Binance dust conversions always credit BNB).
     * Mirrors [fixedSideBuy]: what the endpoint *is* carries the information, not the row.
     */
    val fixedBaseAsset: String? = null,
    /** A constant quote asset code, the [fixedBaseAsset] counterpart for [quoteAssetField]. */
    val fixedQuoteAsset: String? = null,
    @Serializable(with = SortedStringListSerializer::class)
    val quoteAssets: List<String> = emptyList(),
    /** Dot-path to the buy/sell side; null when [fixedSideBuy] fixes the direction instead. */
    val sideField: String? = null,
    @Serializable(with = SortedStringSetSerializer::class)
    val buyValues: Set<String> = setOf("BUY", "buy"),
    /**
     * Fixes every trade from this endpoint to BUY (true) or SELL (false), for an endpoint whose side is
     * implied by which endpoint it is rather than carried on each item (e.g. Binance Convert always
     * acquires `toAsset` with `fromAsset`; Binance fiat buy/sell are two separate endpoints). Overrides
     * [sideField] when set.
     */
    val fixedSideBuy: Boolean? = null,
    val baseQuantityField: String,
    val priceField: String? = null,
    val quoteQuantityField: String? = null,
    /** The trade's fee; its currency defaults to the quote asset. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fee: FeeRule? = null,
    val timestampField: String,
    val timestampFormat: TimestampFormat = TimestampFormat.EPOCH_MS,
    /** Pattern string for [TimestampFormat.PATTERN]; ignored for every other [timestampFormat]. */
    val timestampPattern: String? = null,
    val idField: String,
    /**
     * Dot-paths joined (in list order, hyphen-separated) into the trade's de-duplication id instead of
     * [idField], for an endpoint whose own id is scoped to another field rather than globally unique
     * (Binance `myTrades`' `id` is scoped per `symbol`, so two different pairs can share the same
     * numeric id). Order is semantic (produces a readable, stable composite key) - keeps default
     * insertion-order serialization. Empty (the default) uses [idField] alone.
     */
    val compositeIdFields: List<String> = emptyList(),
    val orderIdField: String? = null,
    val descriptionField: String? = null,
    /** Order-only fields surfaced as trade attributes when ORDERS metadata is joined in. */
    val orderTypeField: String? = null,
    val orderStatusField: String? = null,
    // Order-only fields for persisting the order itself (ORDERS endpoints; ignored for TRADES).
    val limitPriceField: String? = null,
    val avgPriceField: String? = null,
    val updateTimestampField: String? = null,
    val clientOidField: String? = null,
    val timeInForceField: String? = null,
    /**
     * Conditions that must all hold (logical AND) against the item's raw JSON for it to be imported at
     * all. Empty imposes no filter.
     */
    @Serializable(with = SortedConditionListSerializer::class)
    val itemFilters: List<Condition> = emptyList(),
)

/**
 * Where a strategy's own accounts come from.
 */
@Serializable
sealed interface ApiAccountsSource {
    /**
     * Enumerated from [endpoint] (bank APIs), read via [mappings].
     *
     * @property identifiersEndpoint Optional per-account endpoint fetched after accounts that returns an
     *   account's own bank details (sort code + account number) when the accounts response omits them
     *   (Starling's `/accounts/{account.id}/identifiers`), read with [mappings]' sort code/account number
     *   fields.
     * @property ancestorEndpoints Resource endpoints fetched before accounts whose items supply context
     *   ids/fields for templating descendant endpoint paths and params (Wise "profiles"). Order is
     *   semantic: they're referenced by position via `ancestor[N].` expressions.
     */
    @Serializable
    @SerialName("downloaded")
    data class Downloaded(
        val endpoint: ApiEndpointConfig,
        val mappings: ApiAccountMappings = ApiAccountMappings(),
        val identifiersEndpoint: ApiEndpointConfig? = null,
        val ancestorEndpoints: List<ApiEndpointConfig> = emptyList(),
    ) : ApiAccountsSource

    /**
     * One fixed account holding all assets (exchanges): nothing is enumerated; the account is
     * matched/created by [externalId] with display [name].
     */
    @Serializable
    @SerialName("single")
    data class Single(
        val name: String,
        val externalId: String,
    ) : ApiAccountsSource
}

/** A bridge to another (already-imported) account this strategy's transfers should reconcile against. */
@Serializable
data class ApiAccountBridge(
    /** Display name of the other owned account (e.g. the CSV "Crypto.com" App account). */
    val otherAccountName: String,
) : Comparable<ApiAccountBridge> {
    override fun compareTo(other: ApiAccountBridge): Int = otherAccountName.compareTo(other.otherAccountName)
}

/**
 * Serializes bridge lists sorted by [ApiAccountBridge]'s natural order. Each bridge is matched
 * independently against the existing accounts (no first-match short-circuit), so list order carries
 * no meaning.
 */
object SortedAccountBridgeListSerializer : SortedListSerializer<ApiAccountBridge>(ApiAccountBridge.serializer())

/**
 * Reconciles internal transfers between this strategy's account and another owned account that records
 * the same real movement at its own end (e.g. money moved from the Crypto.com App into the Exchange:
 * the App CSV records a withdrawal out, the Exchange API records a deposit in). On a match within
 * [windowSeconds] of the same asset and (within [amountTolerancePercent]) amount, the two half-transfers
 * are collapsed into one internal transfer and linked via the `reconciled` relationship.
 */
@Serializable
data class ApiInternalTransferReconcile(
    @Serializable(with = SortedAccountBridgeListSerializer::class)
    val bridges: List<ApiAccountBridge>,
    val windowSeconds: Long,
    /**
     * Allowed amount difference as a percentage, held as a decimal string (e.g. "2" or "0.5") so it is
     * parsed to `BigDecimal` for the monetary comparison rather than an imprecise `Double`.
     */
    val amountTolerancePercent: String = "0",
)

/**
 * Trades a long-lived client id + secret for a short-lived bearer token before a download (the OAuth2
 * client-credentials grant, e.g. PayPal). The credential's token is the client id and its api secret the
 * client secret; they are sent as HTTP Basic auth (RFC 6749's default client authentication) with
 * [formParams] as an `application/x-www-form-urlencoded` POST to [path], and [accessTokenField] of the JSON
 * response is used as the bearer token for every request of that download.
 */
@Serializable
data class ApiTokenExchange(
    /** Token endpoint, relative to [ApiStrategyConfig.baseUrl]. */
    val path: String = "/v1/oauth2/token",
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val formParams: Map<String, String> = mapOf("grant_type" to "client_credentials"),
    /** Dot-path of the access token in the token response. */
    val accessTokenField: String = "access_token",
) {
    fun isValidForSave(): Boolean = path.isNotBlank() && accessTokenField.isNotBlank()
}

/**
 * Decoded, domain-level view of an API import strategy's full configuration — and the portable JSON
 * shape it is persisted (`api_import_strategy.config_json`) and exported in. Holds no database entity
 * references (only URLs, JSON field paths and enums), so it is fully portable as-is.
 *
 * @property accounts Where the strategy's own accounts come from: enumerated from an endpoint (banks), or
 *                   one fixed account holding every asset (exchanges).
 * @property dataEndpoints The endpoints whose items are imported. A bank strategy has one
 *                        [ApiEndpointKind.BANK_TRANSACTIONS] feed (fetched once per account); an exchange
 *                        several (trades, orders, deposits, withdrawals).
 * @property builtInCounterpartyRules Declarative rules routing matching transactions to a single
 *                                    consolidated built-in counterparty account (e.g. ATM).
 */
@Serializable
data class ApiStrategyConfig(
    val baseUrl: String,
    val accounts: ApiAccountsSource,
    @Serializable(with = SortedDataEndpointListSerializer::class)
    val dataEndpoints: List<ApiDataEndpoint> = emptyList(),
    val peopleMappings: ApiPeopleMappings = ApiPeopleMappings(),
    // First-match-wins (see resolveBuiltInCounterpartyType) - order is semantic, keeps default
    // insertion-order serialization.
    val builtInCounterpartyRules: List<BuiltInCounterpartyRule> = emptyList(),
    val signing: ApiSigningConfig? = null,
    val peopleDownload: ApiPersonImportConfig? = null,
    /**
     * Name of the person-attribute type that stores this provider's external id for an imported
     * person (e.g. "monzo-external-id", "wise-external-id"). Per-provider so the same person can carry
     * a distinct id from each provider; people are matched across providers by name, with the missing
     * provider id backfilled. Null disables external-id storage.
     */
    val personExternalIdAttribute: String? = null,
    /**
     * Proactive per-request signing (an api key + secret; exchanges). Null authenticates with a bearer
     * token instead (bank APIs).
     */
    val requestSigning: ApiRequestSigningConfig? = null,
    /**
     * Exchanges the credential's client id + secret for a bearer token before each download (OAuth2
     * client credentials). Null sends the credential's token itself as the bearer. Omitted from JSON when
     * null (@EncodeDefault NEVER) so adding it did not change the canonical hash of every existing API
     * strategy.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tokenExchange: ApiTokenExchange? = null,
    /**
     * Endpoints fetched (unpaged loop aside — each still runs its own pagination) before
     * [dataEndpoints], purely to supply values for an [ApiFanOut] (e.g. Binance `getUserAsset` for held
     * balances, `exchangeInfo` for the valid symbol universe). Produce no transfers/trades of their own.
     * Referenced by path from [ApiValueSet.FromValueEndpoint.endpointPath], so list order carries no
     * meaning.
     */
    @Serializable(with = SortedValueEndpointListSerializer::class)
    val valueEndpoints: List<ApiEndpointConfig> = emptyList(),
    /** Reconcile internal transfers against another owned account (e.g. the Crypto.com App account). */
    val internalTransferReconcile: ApiInternalTransferReconcile? = null,
    /**
     * How raw asset codes in API responses map onto canonical codes: Earn-holding suffixes stripped
     * (Kraken `XETH.F` → `XETH`), then legacy codes aliased (Kraken `XXBT` → `BTC`, `ZUSD` → `USD`).
     * Applied to every asset code resolved from a trade or transfer item before currency/crypto-asset
     * lookup; without it a suffixed code fails resolution and its transfer is dropped.
     */
    val assetCodes: AssetCodeRules = AssetCodeRules(),
    /** Deep-link to the provider's own page for creating/managing API tokens; null shows no button. */
    val tokenPageUrl: String? = null,
    /**
     * Ordered, numbered steps shown to the user for obtaining credentials from this provider (e.g.
     * "Open the developer portal…", "Create a token with these scopes…"). Empty shows no instructions —
     * a user-defined strategy simply gets none, which is why connecting never requires them.
     */
    val connectInstructions: List<String> = emptyList(),
    /** Delay between exchange-download requests; null uses the download function's own default. */
    val rateLimitMillis: Long? = null,
    /** Case-insensitive substrings marking an error response as transient rate-limiting to retry. */
    @Serializable(with = SortedStringListSerializer::class)
    val rateLimitErrorSubstrings: List<String> = emptyList(),
    /** Base backoff before the first retry of a rate-limited request; doubles each subsequent retry. */
    val rateLimitBackoffMillis: Long = 5_000L,
    /** Maximum retries for a request repeatedly classified as rate-limited before giving up. */
    val maxRateLimitRetries: Int = 5,
    /**
     * Overrides [com.moneymanager.domain.model.IsoMinorUnitDivisors]' per-currency divisor for
     * interpreting a [ApiAmountFormat.MINOR_UNITS_INTEGER] amount (a bank API's raw integer in its own
     * minor units). A provider whose minor-unit width doesn't follow the ISO 4217 standard for a given
     * currency can be corrected here instead of the global table.
     */
    @Serializable(with = SortedStringToLongMapSerializer::class)
    val minorUnitDivisorOverrides: Map<String, Long> = emptyMap(),
) {
    /** Whether requests are signed with an api key + secret rather than sent with a bearer token. */
    val isSigned: Boolean get() = requestSigning != null

    /** Whether a credential needs a secret besides its token: an api secret to sign with, or a client secret. */
    val needsApiSecret: Boolean get() = isSigned || tokenExchange != null

    /** The bank transaction feed, when this is a bank strategy (accounts enumerated from an endpoint). */
    val bankTransactions: ApiDataEndpoint?
        get() = dataEndpoints.firstOrNull { it.kind == ApiEndpointKind.BANK_TRANSACTIONS }

    /** This config with the bank feed's transaction mappings replaced by [transform]'s result. */
    fun mapBankTransactionMappings(transform: ApiTransactionMappings.() -> ApiTransactionMappings): ApiStrategyConfig =
        copy(
            dataEndpoints =
                dataEndpoints.map { endpoint ->
                    if (endpoint.kind == ApiEndpointKind.BANK_TRANSACTIONS) {
                        endpoint.copy(transactionMappings = transform(endpoint.transactionMappings ?: ApiTransactionMappings()))
                    } else {
                        endpoint
                    }
                },
        )
}
