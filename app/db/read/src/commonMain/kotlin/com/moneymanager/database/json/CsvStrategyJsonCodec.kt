package com.moneymanager.database.json

import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.serialization.UuidSerializersModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Codec for the JSON representation of a
 * [com.moneymanager.domain.model.csvstrategy.CsvImportStrategy] configuration stored in
 * `csv_import_strategy.config_json` (id, name, worksheet name and timestamps live elsewhere).
 */
object CsvStrategyJsonCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            serializersModule = UuidSerializersModule
        }

    private val serializer = CsvStrategyConfig.serializer(FieldMapping.serializer())

    fun encode(config: CsvStrategyConfig<FieldMapping>): String =
        json.encodeToString(
            JsonObject.serializer(),
            StrategyConfigMigrations.stampCsv(json.encodeToJsonElement(serializer, config).jsonObject),
        )

    fun decode(jsonString: String): CsvStrategyConfig<FieldMapping> =
        json.decodeFromJsonElement(serializer, StrategyConfigMigrations.upgradeCsv(json.parseToJsonElement(jsonString).jsonObject))
}
