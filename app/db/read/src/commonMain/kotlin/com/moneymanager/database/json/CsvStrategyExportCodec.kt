package com.moneymanager.database.json

import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject

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
    fun encode(export: CsvStrategyExport): String = json.encodeToString(FlatCsvStrategyExportSerializer, export)

    /**
     * Decodes a strategy export from a JSON string.
     * Unknown keys are ignored for forward compatibility.
     */
    fun decode(jsonString: String): CsvStrategyExport = json.decodeFromString(FlatCsvStrategyExportSerializer, jsonString)
}

/**
 * Writes/reads a [CsvStrategyExport] with the config's fields inlined alongside the export's own
 * fields rather than nested under `config`, which is the shape every published and synced export file
 * uses.
 */
private object FlatCsvStrategyExportSerializer : JsonTransformingSerializer<CsvStrategyExport>(CsvStrategyExport.serializer()) {
    private val OUTER = setOf("version", "name", "accountMappings", "worksheetName")

    // The published key order, predating the config/export split. canonicalHash hashes the encoded
    // bytes, so reordering these would rehash every strategy and show each as changed on catalog and
    // Drive sync. A key missing here (a field added later) is written after all of them.
    private val PUBLISHED_KEY_ORDER =
        listOf(
            "version",
            "name",
            "identificationColumns",
            "fieldMappings",
            "attributeMappings",
            "rowPreprocessingRules",
            "companionTransactionRules",
            "contentMatchRules",
            "accountMappings",
            "fileNamePattern",
            "crossSourceReconcileWindowSeconds",
            "conversionConfig",
            "fundingAttributeMatch",
            "worksheetName",
            "tradeGroupConfig",
        )

    override fun transformSerialize(element: JsonElement): JsonElement {
        val flat = element.jsonObject.filterKeys { it != "config" } + element.jsonObject.getValue("config").jsonObject
        return JsonObject(
            flat.entries
                .sortedBy { (key, _) -> PUBLISHED_KEY_ORDER.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                .associate { it.key to it.value },
        )
    }

    override fun transformDeserialize(element: JsonElement): JsonElement =
        JsonObject(
            element.jsonObject.filterKeys { it in OUTER } +
                ("config" to JsonObject(element.jsonObject.filterKeys { it !in OUTER })),
        )
}
