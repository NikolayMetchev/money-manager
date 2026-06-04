package com.moneymanager.domain.model.apistrategy

import kotlinx.serialization.Serializable

/**
 * Authentication mechanism for an API.
 */
@Serializable
enum class ApiAuthType {
    /** HTTP Bearer token authentication (Authorization: Bearer <token>). */
    BEARER_TOKEN,
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
 * @property responseArrayKey Top-level JSON object key holding the items array (e.g. "accounts").
 *                            Blank means the response body itself is the array (a bare JSON array).
 * @property queryParams Static and dynamic query parameters appended to every request
 * @property pagination Optional pagination strategy; null means only one page is fetched
 */
@Serializable
data class ApiEndpointConfig(
    val path: String,
    val responseArrayKey: String,
    val queryParams: List<ApiQueryParam> = emptyList(),
    val pagination: ApiPaginationConfig? = null,
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
 * @property declineReasonField Optional dot-path to a decline reason for declined transactions
 * @property localAmountField Optional dot-path to a local/original amount (foreign transactions)
 * @property localCurrencyField Optional dot-path to a local/original currency code
 */
@Serializable
data class ApiTransactionMappings(
    val amountField: String = "amount",
    val timestampField: String = "created",
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
    val localAmountField: String? = null,
    val localCurrencyField: String? = null,
    val customFields: Map<String, String> = emptyMap(),
    val uniqueIdentifierFields: Set<String> = emptySet(),
)

@Serializable
data class ApiPeopleMappings(
    val counterpartyObjectField: String = "counterparty",
    val beneficiaryAccountTypeField: String = "beneficiary_account_type",
    val personalBeneficiaryAccountTypeValue: String = "Personal",
    val counterpartyNameField: String = "name",
    val counterpartyUserIdField: String = "user_id",
    val counterpartySortCodeField: String = "sort_code",
    val counterpartyAccountNumberField: String = "account_number",
    val counterpartyServiceUserNumberField: String = "service_user_number",
    val fallbackCounterpartyAccountIdSuffix: String = ".account_id",
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
    val accountNamePrefix: String,
    val counterpartyPrefix: String,
    val peopleMappings: ApiPeopleMappings,
    val ancestorEndpoints: List<ApiEndpointConfig> = emptyList(),
    val builtInCounterpartyRules: List<BuiltInCounterpartyRule> = emptyList(),
)
