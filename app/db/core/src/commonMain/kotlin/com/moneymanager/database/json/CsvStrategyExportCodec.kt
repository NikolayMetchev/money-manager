package com.moneymanager.database.json

import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Codec for encoding/decoding CSV strategy exports to/from JSON.
 * Used for file-based import/export of strategies.
 */
object CsvStrategyExportCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Encodes a strategy export to a pretty-printed JSON string.
     */
    fun encode(export: CsvStrategyExport): String = json.encodeToString(export)

    /**
     * Decodes a strategy export from a JSON string.
     * Unknown keys are ignored for forward compatibility.
     */
    fun decode(jsonString: String): CsvStrategyExport = json.decodeFromString(jsonString)
}
