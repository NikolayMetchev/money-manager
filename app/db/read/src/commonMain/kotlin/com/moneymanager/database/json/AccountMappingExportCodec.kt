package com.moneymanager.database.json

import com.moneymanager.domain.model.accountmapping.export.AccountMappingsExport
import kotlinx.serialization.json.Json

/**
 * Codec for encoding/decoding the global account-mapping export to/from JSON.
 * Used for file-based import/export of account mappings, separately from strategies.
 */
object AccountMappingExportCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Encodes an account-mappings export to a pretty-printed JSON string.
     */
    fun encode(export: AccountMappingsExport): String = json.encodeToString(export)

    /**
     * Decodes an account-mappings export from a JSON string.
     * Unknown keys are ignored for forward compatibility.
     */
    fun decode(jsonString: String): AccountMappingsExport = json.decodeFromString(jsonString)
}
