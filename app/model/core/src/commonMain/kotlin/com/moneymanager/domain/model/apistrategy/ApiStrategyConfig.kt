package com.moneymanager.domain.model.apistrategy

import kotlinx.serialization.Serializable

/**
 * Authentication mechanism for an API.
 */
@Serializable
enum class ApiAuthType {
    /** HTTP Bearer token authentication (Authorization: Bearer <token>). */
    BEARER_TOKEN,

    /**
     * Proactive per-request signing driven by [ApiImportStrategy.requestSigning] — an HMAC signature
     * computed over a configurable message and placed in a header/query/body field on every request.
     * Used by exchange APIs (Crypto.com, and later Binance/Kraken) that authenticate with an
     * api-key + secret rather than a bearer token.
     */
    SIGNED,
}

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
 *                         - "ancestor[N].<field>" — a field on the N-th ancestor resource item
 *                           (e.g. "ancestor[0].id"); "parent.id" aliases the last ancestor's id
 *                         - "window.start" / "window.end" — the ISO-8601 bounds of the current
 *                           date window (see [ApiPaginationConfig.DateWindow])
 */
@Serializable
data class ApiQueryParam(
    val name: String,
    val value: String? = null,
    val dynamicSource: String? = null,
)

/** Selects which pagination scheme an endpoint uses. */
@Serializable
enum class PaginationMode {
    /** Before-cursor paging (cursor extracted from the earliest item of each page). */
    CURSOR,

    /** Fixed-length date windows, one request per window. */
    DATE_WINDOW,
}

/**
 * Pagination strategy for an API endpoint. A single flat shape carries the parameters for both
 * schemes; [mode] selects which set applies. A flat (rather than sealed) shape keeps this model
 * module free of the serialization-json artifact and stays backward compatible: legacy configs
 * persisted before [mode] existed decode with the default [PaginationMode.CURSOR] and their
 * original cursor fields.
 *
 * Cursor fields: the cursor is the minimum [cursorResponseField] across a page, sent as [cursorParam].
 *
 * Date-window fields: history is fetched in [windowDays]-long windows back to [lookbackDays] ago,
 * each request bounded by [startParam]/[endParam] (also exposed to templating as window.start/end),
 * with [extraParams] appended. Windows are anchored to fixed boundaries so earlier windows produce
 * stable, cacheable URLs; only the final window (ending "now") shifts across re-imports.
 */
@Serializable
data class ApiPaginationConfig(
    val mode: PaginationMode = PaginationMode.CURSOR,
    val limitParam: String = "limit",
    val limitValue: Int = 100,
    val cursorParam: String = "before",
    val cursorResponseField: String = "created",
    val startParam: String = "intervalStart",
    val endParam: String = "intervalEnd",
    val windowDays: Int = 469,
    val lookbackDays: Int = 365 * 6,
    val extraParams: List<ApiQueryParam> = emptyList(),
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
 */
@Serializable
data class ApiEndpointConfig(
    val path: String,
    val responseArrayKey: String,
    val queryParams: List<ApiQueryParam> = emptyList(),
    val pagination: ApiPaginationConfig? = null,
    val method: HttpMethodType = HttpMethodType.GET,
    val successCodeField: String? = null,
    val successCodeOkValue: String? = null,
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
    val customFields: Map<String, String> = emptyMap(),
    val uniqueIdentifierFields: Set<String> = emptySet(),
)

/** How a transaction amount value is encoded in the API response. */
@Serializable
enum class ApiAmountFormat {
    /** Integer amount already expressed in minor currency units (e.g. Monzo: 1234 == 12.34). */
    MINOR_UNITS_INTEGER,

    /** Decimal amount in major currency units (e.g. Wise: "12.34"); converted via Money. */
    DECIMAL_MAJOR_UNITS,
}

/** Where the sign (incoming vs outgoing) of a transaction comes from. */
@Serializable
enum class ApiSignSource {
    /** The amount value itself is signed (negative == outgoing). */
    EMBEDDED,

    /** A separate field (see [ApiTransactionMappings.signField]) carries the direction. */
    FIELD,
}

/**
 * JSON field names used to extract transaction data from an API response item. Defaults match the
 * Monzo response shape so existing strategies behave identically.
 *
 * @property amountField Dot-path to the amount value (e.g. "amount" or "amount.value")
 * @property timestampField Dot-path to the ISO-8601 timestamp (e.g. "created", "date")
 * @property currencyField Dot-path to the ISO-4217 currency code (e.g. "currency", "amount.currency")
 * @property descriptionField Dot-path to the transaction description
 * @property amountFormat How [amountField] is encoded; see [ApiAmountFormat]
 * @property signSource Where the transaction direction comes from; see [ApiSignSource]
 * @property signField Dot-path to the direction field when [signSource] is [ApiSignSource.FIELD]
 * @property creditValues Values of [signField] that mean "incoming/positive" (e.g. {"CREDIT"})
 * @property idField Dot-path to the transaction's stable id, used for de-duplication
 * @property merchantNameField Optional dot-path to a merchant name; preferred counterparty name
 * @property counterpartyNameField Optional dot-path to a counterparty name; fallback for merchant
 * @property declineReasonField Optional dot-path to a decline reason for declined transactions.
 *                              Present-and-non-blank means declined (Monzo's `decline_reason` shape).
 * @property declineStatusField Optional dot-path to a status field whose value flags declines
 *                              (Starling's `status` shape, where the field is always present). When
 *                              the resolved value is in [declinedStatusValues] the transaction is
 *                              treated as declined — imported but excluded from balances — exactly
 *                              like a non-blank [declineReasonField].
 * @property declinedStatusValues Values of [declineStatusField] that mean "declined" (e.g. {"DECLINED"}).
 * @property localAmountField Optional dot-path to a local/original amount (foreign transactions)
 * @property localCurrencyField Optional dot-path to a local/original currency code
 * @property feeAmountField Optional dot-path to a fee amount charged on the transaction. When present
 *                          and non-zero, the fee is imported as its own transfer linked to the main
 *                          transaction via a `fee` relationship. Encoded using [amountFormat].
 * @property feeCurrencyField Optional dot-path to the fee's currency code; defaults to the
 *                            transaction currency when absent.
 * @property feeDescriptionField Optional dot-path to a description for the fee transfer; defaults to
 *                               a generic "Fee" label when absent.
 * @property feeIncludedInAmount Whether [amountField] is GROSS (already includes the fee). When true the
 *                               fee is carved OUT of the main transfer (main = amount - fee) so the two
 *                               sum back to the original amount — Monzo's `atm_fees_detailed` shape, where
 *                               `amount = withdrawal_amount + fee_amount`. When false (default) the fee is
 *                               an additional movement on top of a net amount.
 */
@Serializable
data class ApiTransactionMappings(
    val amountField: String = "amount",
    val timestampField: String = "created",
    val timestampFormat: TimestampFormat = TimestampFormat.ISO_8601,
    val currencyField: String = "currency",
    val descriptionField: String = "description",
    val amountFormat: ApiAmountFormat = ApiAmountFormat.MINOR_UNITS_INTEGER,
    val signSource: ApiSignSource = ApiSignSource.EMBEDDED,
    val signField: String? = null,
    val creditValues: Set<String> = emptySet(),
    val idField: String = "id",
    val merchantNameField: String? = null,
    val counterpartyNameField: String? = null,
    val counterpartyIdField: String? = null,
    val declineReasonField: String? = null,
    val declineStatusField: String? = null,
    val declinedStatusValues: Set<String> = emptySet(),
    val localAmountField: String? = null,
    val localCurrencyField: String? = null,
    val feeAmountField: String? = null,
    val feeCurrencyField: String? = null,
    val feeDescriptionField: String? = null,
    val feeIncludedInAmount: Boolean = false,
    val customFields: Map<String, String> = emptyMap(),
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
    val counterpartyAccountAliases: Map<String, String> = emptyMap(),
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
    val personalBeneficiaryAccountTypeValues: Set<String> = emptySet(),
    val counterpartyNameField: String = "name",
    val counterpartyUserIdField: String = "user_id",
    val counterpartySortCodeField: String = "sort_code",
    val counterpartyAccountNumberField: String = "account_number",
    val counterpartyServiceUserNumberField: String = "service_user_number",
    val fallbackCounterpartyAccountIdSuffix: String = ".account_id",
    // Prefixes marking a resolved counterparty id as ephemeral — a throwaway id that changes per
    // transaction and so must NOT identify the counterparty account, or the same real counterparty
    // fragments into one account per transaction (e.g. Monzo issues a fresh "anonuser_…" user id for
    // every bank transfer to the same person). When the id begins with one of these it is discarded
    // and the counterparty is matched by name instead. Empty = treat every id as stable.
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

/** Comparison operator for a [RulePredicate]. */
@Serializable
enum class PredicateOp {
    /** The path resolves to any present value. */
    EXISTS,

    /** The resolved string equals [RulePredicate.value]. */
    EQUALS,

    /** The resolved string equals [RulePredicate.value], ignoring case. */
    EQUALS_IGNORE_CASE,

    /** The resolved string starts with [RulePredicate.value]. */
    STARTS_WITH,

    /** The path resolves to an array with any element starting with [RulePredicate.value]. */
    ARRAY_ANY_STARTS_WITH,

    /** The path resolves to an object that is empty (or absent). */
    OBJECT_EMPTY,

    /** The path resolves to a non-empty object. */
    OBJECT_NON_EMPTY,
}

/**
 * A single condition evaluated against a transaction's raw JSON.
 *
 * @property path Dot-path into the transaction object (e.g. "metadata.mcc")
 * @property op The comparison to apply
 * @property value Comparison operand for ops that need one (EQUALS, STARTS_WITH, …)
 */
@Serializable
data class RulePredicate(
    val path: String,
    val op: PredicateOp,
    val value: String? = null,
)

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
    val predicates: List<RulePredicate> = emptyList(),
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
// Proactive request signing (ApiAuthType.SIGNED) — a generic, provider-agnostic HMAC recipe.
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

    /** SHA-256 of the concatenation of [parts], emitted as raw bytes (Kraken's nested hash). */
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
    val algorithm: SigningAlgorithm,
    val secretEncoding: SecretEncoding = SecretEncoding.UTF8,
    val signatureEncoding: SignatureEncoding = SignatureEncoding.HEX,
    val message: List<SigPart>,
    val apiKey: FieldPlacement,
    val nonce: NonceSpec,
    val signature: FieldPlacement,
    val requestId: RequestIdSpec? = null,
    /** Where the API method name is written on the request (Crypto.com body field "method"); null omits it. */
    val method: FieldPlacement? = null,
    val bodyFormat: BodyFormat = BodyFormat.NONE,
    /** For [BodyFormat.JSON_ENVELOPE], the key under which request params are nested (Crypto.com "params"). */
    val paramsEnvelopeKey: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Multiple data endpoints + entity kinds — exchanges expose several endpoints (trades, deposits,
// withdrawals, order history), each mapping to a different kind of imported record.
// ---------------------------------------------------------------------------------------------

/** What kind of imported record an [ApiDataEndpoint] produces. */
@Serializable
enum class ApiEndpointKind {
    /** A bank-style single-asset transaction feed (the legacy [ApiImportStrategy.transactionsEndpoint] shape). */
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

/** Fixed movement direction for deposit/withdrawal endpoints (relative to the owning account). */
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
 * @property fixedDirection For DEPOSITS/WITHDRAWALS, the movement direction (amounts are unsigned).
 * @property counterpartyAccountName For DEPOSITS/WITHDRAWALS, the fixed external/funding account name
 *                                   the money comes from / goes to (e.g. "Crypto.com Exchange Funding").
 */
@Serializable
data class ApiDataEndpoint(
    val endpoint: ApiEndpointConfig,
    val kind: ApiEndpointKind,
    val transactionMappings: ApiTransactionMappings? = null,
    val tradeMappings: ApiTradeMappings? = null,
    val fixedDirection: TransferDirection? = null,
    val counterpartyAccountName: String? = null,
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
 * @property instrumentField Dot-path to the pair symbol (e.g. "instrument_name").
 * @property splitMode How [instrumentField] is split into base/quote assets.
 * @property instrumentSeparator Separator for [InstrumentSplitMode.SEPARATOR] (default "_").
 * @property baseAssetField/quoteAssetField Dot-paths for [InstrumentSplitMode.EXPLICIT_FIELDS].
 * @property quoteAssets Known quote assets for [InstrumentSplitMode.QUOTE_SUFFIX] (longest match wins).
 * @property sideField Dot-path to the buy/sell side; [buyValues] enumerates the values meaning BUY.
 * @property baseQuantityField Dot-path to the base-asset quantity traded.
 * @property priceField Dot-path to the price (quote per base); quote amount = quantity × price.
 * @property quoteQuantityField Dot-path to an explicit quote amount (used instead of price when present).
 * @property feeField/feeCurrencyField Optional dot-paths to a trade fee and its asset code.
 * @property timestampField/timestampFormat Dot-path + encoding of the fill timestamp.
 * @property idField Dot-path to the stable trade id (used for de-duplication).
 * @property orderIdField Optional dot-path to the owning order id (joins ORDERS metadata onto the trade).
 */
@Serializable
data class ApiTradeMappings(
    val instrumentField: String,
    val splitMode: InstrumentSplitMode = InstrumentSplitMode.SEPARATOR,
    val instrumentSeparator: String = "_",
    val baseAssetField: String? = null,
    val quoteAssetField: String? = null,
    val quoteAssets: List<String> = emptyList(),
    val sideField: String,
    val buyValues: Set<String> = setOf("BUY", "buy"),
    val baseQuantityField: String,
    val priceField: String? = null,
    val quoteQuantityField: String? = null,
    val feeField: String? = null,
    val feeCurrencyField: String? = null,
    val timestampField: String,
    val timestampFormat: TimestampFormat = TimestampFormat.EPOCH_MS,
    val idField: String,
    val orderIdField: String? = null,
    val descriptionField: String? = null,
    /** Order-only fields surfaced as trade attributes when ORDERS metadata is joined in. */
    val orderTypeField: String? = null,
    val orderStatusField: String? = null,
)

/**
 * Declares that this strategy imports into a single fixed account holding all assets, instead of
 * enumerating accounts from an accounts endpoint (exchanges). When set, the accounts endpoint is not
 * fetched; the account is matched/created by [externalId] with display [name].
 */
@Serializable
data class ApiSyntheticAccount(
    val name: String,
    val externalId: String,
)

/** A bridge to another (already-imported) account this strategy's transfers should reconcile against. */
@Serializable
data class ApiAccountBridge(
    /** Display name of the other owned account (e.g. the CSV "Crypto.com" App account). */
    val otherAccountName: String,
)

/**
 * Reconciles internal transfers between this strategy's account and another owned account that records
 * the same real movement at its own end (e.g. money moved from the Crypto.com App into the Exchange:
 * the App CSV records a withdrawal out, the Exchange API records a deposit in). On a match within
 * [windowSeconds] of the same asset and (within [amountTolerancePercent]) amount, the two half-transfers
 * are collapsed into one internal transfer and linked via the `reconciled` relationship.
 */
@Serializable
data class ApiInternalTransferReconcile(
    val bridges: List<ApiAccountBridge>,
    val windowSeconds: Long,
    /**
     * Allowed amount difference as a percentage, held as a decimal string (e.g. "2" or "0.5") so it is
     * parsed to `BigDecimal` for the monetary comparison rather than an imprecise `Double`.
     */
    val amountTolerancePercent: String = "0",
)

/**
 * Decoded, domain-level view of an API import strategy's full configuration.
 * Stored as JSON in the database; decoded by the db layer for use across all layers.
 *
 * @property ancestorEndpoints Resource endpoints fetched before accounts whose items supply context
 *                             ids/fields for templating descendant endpoint paths and params
 *                             (e.g. Wise "profiles"). Empty for flat two-level APIs like Monzo.
 * @property builtInCounterpartyRules Declarative rules routing matching transactions to a single
 *                                    consolidated built-in counterparty account (e.g. ATM).
 */
data class ApiStrategyConfig(
    val baseUrl: String,
    val authType: ApiAuthType,
    val accountsEndpoint: ApiEndpointConfig,
    val transactionsEndpoint: ApiEndpointConfig,
    val accountMappings: ApiAccountMappings,
    val transactionMappings: ApiTransactionMappings,
    val peopleMappings: ApiPeopleMappings,
    val accountIdentifiersEndpoint: ApiEndpointConfig? = null,
    val ancestorEndpoints: List<ApiEndpointConfig> = emptyList(),
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
    /** Proactive per-request signing recipe; required when [authType] is [ApiAuthType.SIGNED]. */
    val requestSigning: ApiRequestSigningConfig? = null,
    /**
     * Multiple data endpoints (trades/orders/deposits/withdrawals) for exchange strategies. When
     * non-empty this supersedes [transactionsEndpoint] for the download/import loop.
     */
    val dataEndpoints: List<ApiDataEndpoint> = emptyList(),
    /** When set, import into one fixed account holding all assets instead of enumerating accounts. */
    val syntheticAccount: ApiSyntheticAccount? = null,
    /** Reconcile internal transfers against another owned account (e.g. the Crypto.com App account). */
    val internalTransferReconcile: ApiInternalTransferReconcile? = null,
)
