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
 * @property name Parameter name (e.g. "account_id", "limit")
 * @property value Static value; null when the value is derived at runtime via [dynamicSource]
 * @property dynamicSource Identifies the runtime source for the parameter value.
 *                         Use "account.id" to inject the current account's external API ID.
 */
@Serializable
data class ApiQueryParam(
    val name: String,
    val value: String? = null,
    val dynamicSource: String? = null,
)

/**
 * Pagination strategy for an API endpoint that uses a before-cursor scheme.
 *
 * The cursor is extracted from the earliest item in each page (identified by [cursorResponseField])
 * and passed as the [cursorParam] query parameter on the next request.
 *
 * @property limitParam Name of the query parameter that controls page size (e.g. "limit")
 * @property limitValue Maximum number of items to request per page (e.g. 100)
 * @property cursorParam Name of the query parameter used for the before-cursor (e.g. "before")
 * @property cursorResponseField Field name within each item used to extract the next cursor value
 *                               (e.g. "created"). The minimum value across all items in the page
 *                               is used as the cursor for the next page.
 */
@Serializable
data class ApiPaginationConfig(
    val limitParam: String = "limit",
    val limitValue: Int = 100,
    val cursorParam: String = "before",
    val cursorResponseField: String = "created",
)

/**
 * Configuration for a single API endpoint.
 *
 * @property path URL path relative to the strategy's [ApiImportStrategy.baseUrl]
 *               (e.g. "/accounts" or "/transactions")
 * @property responseArrayKey Top-level JSON object key that holds the items array
 *                            (e.g. "accounts" or "transactions")
 * @property queryParams Static and dynamic query parameters appended to every request
 * @property pagination Optional before-cursor pagination; null means only one page is fetched
 */
@Serializable
data class ApiEndpointConfig(
    val path: String,
    val responseArrayKey: String,
    val queryParams: List<ApiQueryParam> = emptyList(),
    val pagination: ApiPaginationConfig? = null,
)

/**
 * JSON field names used to extract account data from an API response item.
 *
 * @property idField Field containing the external account identifier (e.g. "id")
 * @property descriptionField Field containing the account description or name (e.g. "description")
 * @property ownerNameField Nested field path (dot-notation) to owner names; when set the names are
 *                          extracted from an "owners" array at this sub-path and joined with ", "
 *                          as a fallback when [descriptionField] is blank.
 *                          Example: "preferred_name" extracts `owners[*].preferred_name`.
 */
@Serializable
data class ApiAccountMappings(
    val idField: String = "id",
    val descriptionField: String = "description",
    val ownerNameField: String? = null,
    val customFields: Map<String, String> = emptyMap(),
    val uniqueIdentifierFields: Set<String> = emptySet(),
)

/**
 * JSON field names used to extract transaction data from an API response item.
 *
 * @property amountField Field containing the amount in minor currency units (e.g. "amount")
 * @property timestampField Field containing the ISO-8601 timestamp (e.g. "created")
 * @property currencyField Field containing the ISO-4217 currency code (e.g. "currency")
 * @property descriptionField Field containing the transaction description (e.g. "description")
 * @property merchantNameField Optional dot-notation path to a merchant name
 *                             (e.g. "merchant.name"); used as the counterparty name when present
 * @property counterpartyNameField Optional dot-notation path to a counterparty name
 *                                 (e.g. "counterparty.name"); fallback when merchant is absent
 * @property declineReasonField Optional field containing the decline reason for declined
 *                               transactions (e.g. "decline_reason")
 * @property localAmountField Optional field containing the local/original amount in minor units
 *                            (e.g. "local_amount"). When set together with [localCurrencyField],
 *                            the local amount and currency are used when the local currency differs
 *                            from the account currency (i.e. for foreign-currency transactions).
 * @property localCurrencyField Optional field containing the local/original ISO-4217 currency code
 *                              (e.g. "local_currency"). See [localAmountField].
 */
@Serializable
data class ApiTransactionMappings(
    val amountField: String = "amount",
    val timestampField: String = "created",
    val currencyField: String = "currency",
    val descriptionField: String = "description",
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
