package com.moneymanager.database.json

import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.serialization.UuidSerializersModule
import kotlinx.serialization.json.Json

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

    fun encode(config: CsvStrategyConfig<FieldMapping>): String = json.encodeToString(config)

    fun decode(jsonString: String): CsvStrategyConfig<FieldMapping> = json.decodeFromString(jsonString)
}
