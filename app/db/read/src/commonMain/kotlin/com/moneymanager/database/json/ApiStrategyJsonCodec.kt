package com.moneymanager.database.json

import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Codec for the portable JSON representation of an
 * [com.moneymanager.domain.model.apistrategy.ApiImportStrategy] configuration stored in
 * `api_import_strategy.config_json` (id, name and timestamps live in their own columns).
 */
object ApiStrategyJsonCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(config: ApiStrategyConfig): String =
        json.encodeToString(
            JsonObject.serializer(),
            StrategyConfigMigrations.stampApi(json.encodeToJsonElement(ApiStrategyConfig.serializer(), config).jsonObject),
        )

    fun decode(jsonString: String): ApiStrategyConfig =
        json.decodeFromJsonElement(
            ApiStrategyConfig.serializer(),
            StrategyConfigMigrations.upgradeApi(json.parseToJsonElement(jsonString).jsonObject),
        )
}
