package com.moneymanager.database.json

import com.moneymanager.domain.model.CurrencyListExport
import kotlinx.serialization.json.Json

/** Codec for the currency-list artifact published in the strategy catalog and synced with the library. */
object CurrencyListExportCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(export: CurrencyListExport): String = json.encodeToString(export)

    /** Unknown keys are ignored for forward compatibility. */
    fun decode(jsonString: String): CurrencyListExport = json.decodeFromString(jsonString)
}
