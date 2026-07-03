package com.moneymanager.strategycatalog

import kotlinx.serialization.json.Json

/** Codec for the catalog's index.json, shared by the site generator and the in-app client. */
object CatalogManifestCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(manifest: CatalogManifest): String = json.encodeToString(manifest)

    fun decode(jsonString: String): CatalogManifest = json.decodeFromString(jsonString)
}
