package com.moneymanager.database.json

import com.moneymanager.domain.model.passthrough.export.PassThroughExport
import kotlinx.serialization.json.Json

/**
 * Codec for encoding/decoding a pass-through account definition export to/from JSON.
 * Used for file-based import/export and the shared strategy library on remote storage.
 */
object PassThroughExportCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Encodes a pass-through export to a pretty-printed JSON string. */
    fun encode(export: PassThroughExport): String = json.encodeToString(export)

    /** Decodes a pass-through export from a JSON string. Unknown keys are ignored for forward compatibility. */
    fun decode(jsonString: String): PassThroughExport = json.decodeFromString(jsonString)
}
