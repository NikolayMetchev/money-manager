package com.moneymanager.domain.model.apistrategy.export

import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import kotlinx.serialization.Serializable

/**
 * Portable export format for API import strategies. Unlike CSV strategies, an API strategy's
 * configuration is entirely made of URLs, JSON field paths and enums — it holds no database entity
 * references (account/currency/category ids), and credentials live in separate tables — so it is
 * fully portable as-is with no reference resolution needed.
 *
 * Mirrors the persisted config (see `ApiStrategyConfigJson`) plus [version] and [name].
 *
 * @property version App version that created this export (for compatibility tracking)
 * @property name Strategy name (unique)
 */
@Serializable
data class ApiStrategyExport(
    val version: String,
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
)
