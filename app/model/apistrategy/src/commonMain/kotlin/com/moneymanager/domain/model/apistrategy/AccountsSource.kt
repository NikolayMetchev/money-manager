package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * @property rules What each account item decides, run over its raw JSON: its name (the first
 *                 [com.moneymanager.domain.model.rules.AccountSide.OWN] account candidate that names one,
 *                 else [descriptionField]), its stored external id ([RuleAction.ExternalId], else [idField]'s
 *                 value) and its attributes. External ids are matched across every provider, so an id that
 *                 is only unique within the provider (PayPal's per-currency balances carry nothing but
 *                 `currency`) needs a provider prefix (`paypal:{currency}`); requests still use the raw id.
 *                 When two accounts' rule-given names collide, the short tail of each id is appended.
 *                 First claim wins, so order is semantic.
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
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rules: List<StrategyRule> = emptyList(),
)
