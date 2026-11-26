package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.serialization.SortedStringListSerializer
import com.moneymanager.domain.model.serialization.SortedStringToIntMapSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

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
 */
@Serializable
data class ApiStrategyConfig(
    val baseUrl: String,
    val accounts: ApiAccountsSource,
    @Serializable(with = SortedDataEndpointListSerializer::class)
    val dataEndpoints: List<ApiDataEndpoint> = emptyList(),
    val peopleMappings: ApiPeopleMappings = ApiPeopleMappings(),
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
    /** How far apart this feed and another source may record one movement (see [ApiReconcileWindows]). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reconcileWindows: ApiReconcileWindows = ApiReconcileWindows(),
    /**
     * How raw asset codes in API responses are cleaned up: Earn-holding suffixes stripped (Kraken `XETH.F` → `XETH`).
     * The assets' own aliases then apply (Kraken `XXBT` → `BTC`; see `AssetAlias`).
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
     * Overrides a currency's ISO minor unit (`Currency.fractionDigits`) as the decimal places for
     * interpreting a [ApiAmountFormat.MINOR_UNITS_INTEGER] amount (a bank API's raw integer in its own
     * minor units). A provider whose minor-unit width doesn't follow the ISO 4217 standard for a given
     * currency can be corrected here. Decimal places rather than a divisor,
     * so the conversion is an exact decimal-point shift.
     */
    @Serializable(with = SortedStringToIntMapSerializer::class)
    val minorUnitDecimalPlaces: Map<String, Int> = emptyMap(),
) {
    /** Whether requests are signed with an api key + secret rather than sent with a bearer token. */
    val isSigned: Boolean get() = requestSigning != null

    /** Whether a credential needs a secret besides its token: an api secret to sign with, or a client secret. */
    val needsApiSecret: Boolean get() = isSigned || tokenExchange != null

    /**
     * Whether the api secret is an asymmetric private key (an Ed25519 signature or a signed JWT) rather than a
     * shared secret: a key file the person made or downloaded, so the connect screen calls it a private key and
     * offers to load the file.
     */
    val secretIsPrivateKey: Boolean
        get() = requestSigning?.let { it.jwt != null || it.algorithm == SigningAlgorithm.ED25519 } == true

    /** The bank transaction feed, when this is a bank strategy (accounts enumerated from an endpoint). */
    val bankTransactions: ApiDataEndpoint?
        get() = dataEndpoints.firstOrNull { it.kind == ApiEndpointKind.BANK_TRANSACTIONS }

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
