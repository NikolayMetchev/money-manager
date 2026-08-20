package com.moneymanager.database.json

import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject

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
    fun encode(export: ApiStrategyExport): String = json.encodeToString(FlatApiStrategyExportSerializer, export)

    /** Decodes an API strategy export from a JSON string. Unknown keys are ignored for forward compatibility. */
    fun decode(jsonString: String): ApiStrategyExport = json.decodeFromString(FlatApiStrategyExportSerializer, jsonString)
}

/**
 * Writes/reads an [ApiStrategyExport] with the config's fields inlined alongside `version`/`name`
 * rather than nested under `config`, which is the shape every published and synced export file uses.
 */
private object FlatApiStrategyExportSerializer : JsonTransformingSerializer<ApiStrategyExport>(ApiStrategyExport.serializer()) {
    private val OUTER = setOf("version", "name")

    override fun transformSerialize(element: JsonElement): JsonElement =
        JsonObject(element.jsonObject.filterKeys { it in OUTER } + element.jsonObject.getValue("config").jsonObject)

    override fun transformDeserialize(element: JsonElement): JsonElement =
        JsonObject(
            element.jsonObject.filterKeys { it in OUTER } +
                ("config" to JsonObject(element.jsonObject.filterKeys { it !in OUTER })),
        )
}
