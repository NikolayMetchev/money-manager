package com.moneymanager.database.json

import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Portable JSON representation of an [com.moneymanager.domain.model.apistrategy.ApiImportStrategy]
 * configuration (excludes id, name, createdAt, updatedAt which are stored in separate columns).
 */
@Serializable
data class ApiStrategyConfigJson(
    val baseUrl: String,
    val authType: ApiAuthType,
    val accountsEndpoint: ApiEndpointConfig,
    val transactionsEndpoint: ApiEndpointConfig,
    val accountMappings: ApiAccountMappings,
    val transactionMappings: ApiTransactionMappings,
    val accountNamePrefix: String,
    val counterpartyPrefix: String,
)

/**
 * Codec for encoding/decoding API import strategy configuration to/from JSON.
 */
object ApiStrategyJsonCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(config: ApiStrategyConfigJson): String = json.encodeToString(config)

    fun decode(jsonString: String): ApiStrategyConfigJson = json.decodeFromString(jsonString)
}
