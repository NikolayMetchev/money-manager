package com.moneymanager.domain.model.apistrategy

import kotlinx.serialization.Serializable

@Serializable
data class ApiPeopleMappings(
    // Blank resolves to the transaction item itself, for providers whose counterparty fields are flat
    // (e.g. Starling) rather than nested under an object (e.g. Monzo's "counterparty").
    val counterpartyObjectField: String = "counterparty",
    // Name of a person counterparty (one the bank feed's rules call a person), relative to
    // [counterpartyObjectField].
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
    // When true, a counterparty's bank sub-entity (sort code + account number) identifies the
    // counterparty account in preference to [ApiTransactionMappings.counterpartyIdField]. Set for
    // providers (e.g. Starling) whose per-counterparty id can still split a single real bank account
    // across entries — the same account may appear under several counterparty ids (as a payee and a
    // sender). The id remains the fallback when no bank details are present, then the name.
    val preferBankIdentity: Boolean = false,
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
