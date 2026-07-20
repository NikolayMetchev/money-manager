package com.moneymanager.domain.model.apistrategy.export

import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.apistrategy.ApiPeopleMappings
import com.moneymanager.domain.model.apistrategy.ApiPersonImportConfig
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiSigningConfig
import com.moneymanager.domain.model.apistrategy.ApiSyntheticAccount
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.BuiltInCounterpartyRule
import com.moneymanager.domain.model.apistrategy.SortedDataEndpointListSerializer
import com.moneymanager.domain.model.serialization.SortedStringListSerializer
import com.moneymanager.domain.model.serialization.SortedStringSetSerializer
import com.moneymanager.domain.model.serialization.SortedStringToLongMapSerializer
import com.moneymanager.domain.model.serialization.SortedStringToStringMapSerializer
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
    // Order is semantic (referenced by position via "ancestor[N]." dynamicSource expressions) - keeps
    // default insertion-order serialization.
    val ancestorEndpoints: List<ApiEndpointConfig> = emptyList(),
    // First-match-wins (see resolveBuiltInCounterpartyType) - order is semantic, keeps default
    // insertion-order serialization.
    val builtInCounterpartyRules: List<BuiltInCounterpartyRule> = emptyList(),
    val signing: ApiSigningConfig? = null,
    val peopleDownload: ApiPersonImportConfig? = null,
    val personExternalIdAttribute: String? = null,
    val requestSigning: ApiRequestSigningConfig? = null,
    @Serializable(with = SortedDataEndpointListSerializer::class)
    val dataEndpoints: List<ApiDataEndpoint> = emptyList(),
    val syntheticAccount: ApiSyntheticAccount? = null,
    val internalTransferReconcile: ApiInternalTransferReconcile? = null,
    @Serializable(with = SortedStringToStringMapSerializer::class)
    val assetAliases: Map<String, String> = emptyMap(),
    val tokenPageUrl: String? = null,
    // Ordered, numbered steps shown to the user - not order-insensitive, keeps default serialization.
    val connectInstructions: List<String> = emptyList(),
    val rateLimitMillis: Long? = null,
    @Serializable(with = SortedStringListSerializer::class)
    val rateLimitErrorSubstrings: List<String> = emptyList(),
    val rateLimitBackoffMillis: Long = 5_000L,
    val maxRateLimitRetries: Int = 5,
    @Serializable(with = SortedStringSetSerializer::class)
    val assetSuffixesToStrip: Set<String> = emptySet(),
    @Serializable(with = SortedStringToLongMapSerializer::class)
    val minorUnitDivisorOverrides: Map<String, Long> = emptyMap(),
)
