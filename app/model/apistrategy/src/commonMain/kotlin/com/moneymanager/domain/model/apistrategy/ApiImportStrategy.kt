package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.ApiImportStrategyId
import kotlin.time.Instant

/**
 * Represents a reusable API import strategy that defines how to communicate with an external API
 * and map its responses to accounts, transactions, and people.
 *
 * @property id Unique identifier for this strategy
 * @property name Human-readable name for the strategy (must be unique)
 * @property baseUrl Base URL of the API (e.g. "https://api.monzo.com")
 * @property authType Authentication mechanism used by the API
 * @property accountsEndpoint Configuration for the accounts endpoint
 * @property transactionsEndpoint Configuration for the transactions endpoint
 * @property accountMappings JSON field paths for mapping account fields
 * @property transactionMappings JSON field paths for mapping transaction fields
 * @property peopleMappings JSON field paths/values for people and personal counterparties
 * @property accountIdentifiersEndpoint Optional per-account endpoint fetched after accounts that
 *                                      returns the account's own bank details (sort code + account
 *                                      number) when they are not present on the accounts response
 *                                      itself (e.g. Starling's `/accounts/{account.id}/identifiers`).
 *                                      The sort code / account number fields within its response are
 *                                      read using [accountMappings] sortCode/accountNumber fields.
 * @property ancestorEndpoints Resource endpoints fetched before accounts whose items supply context
 *                             for templating descendant endpoint paths/params (e.g. Wise "profiles")
 * @property builtInCounterpartyRules Declarative rules routing matching transactions to a single
 *                                    consolidated built-in counterparty account (e.g. ATM)
 * @property createdAt Timestamp when this strategy was created
 * @property updatedAt Timestamp when this strategy was last modified
 */
data class ApiImportStrategy(
    val id: ApiImportStrategyId,
    val name: String,
    val baseUrl: String,
    val authType: ApiAuthType,
    val accountsEndpoint: ApiEndpointConfig,
    val transactionsEndpoint: ApiEndpointConfig,
    val accountMappings: ApiAccountMappings,
    val transactionMappings: ApiTransactionMappings,
    val peopleMappings: ApiPeopleMappings = ApiPeopleMappings(),
    val accountIdentifiersEndpoint: ApiEndpointConfig? = null,
    val ancestorEndpoints: List<ApiEndpointConfig> = emptyList(),
    val builtInCounterpartyRules: List<BuiltInCounterpartyRule> = emptyList(),
    val signing: ApiSigningConfig? = null,
    val peopleDownload: ApiPersonImportConfig? = null,
    val personExternalIdAttribute: String? = null,
    val requestSigning: ApiRequestSigningConfig? = null,
    val dataEndpoints: List<ApiDataEndpoint> = emptyList(),
    val syntheticAccount: ApiSyntheticAccount? = null,
    val internalTransferReconcile: ApiInternalTransferReconcile? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revisionId: Long = 1,
    val configJson: String = "",
)
