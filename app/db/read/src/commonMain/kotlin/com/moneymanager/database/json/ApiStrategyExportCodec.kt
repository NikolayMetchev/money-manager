package com.moneymanager.database.json

import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExport
import kotlinx.serialization.json.Json

/**
 * Codec for encoding/decoding API strategy exports to/from JSON.
 * Used for file-based and remote-library import/export of API strategies.
 */
object ApiStrategyExportCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Encodes an API strategy export to a pretty-printed JSON string. */
    fun encode(export: ApiStrategyExport): String = json.encodeToString(export)

    /** Decodes an API strategy export from a JSON string. Unknown keys are ignored for forward compatibility. */
    fun decode(jsonString: String): ApiStrategyExport = json.decodeFromString(jsonString)
}
