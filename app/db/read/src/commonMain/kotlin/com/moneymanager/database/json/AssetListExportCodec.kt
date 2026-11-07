package com.moneymanager.database.json

import com.moneymanager.domain.model.AssetListExport
import kotlinx.serialization.json.Json

/** Codec for the assets artifact exported from the Assets screen and synced with the library. */
object AssetListExportCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(export: AssetListExport): String = json.encodeToString(export)

    /** Unknown keys are ignored for forward compatibility. */
    fun decode(jsonString: String): AssetListExport = json.decodeFromString(jsonString)
}
