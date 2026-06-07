package com.moneymanager.database.json

import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.serialization.UuidSerializersModule
import kotlinx.serialization.json.Json

/**
 * Codec for encoding/decoding field mappings to/from JSON using kotlinx.serialization.
 */
object FieldMappingJsonCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            serializersModule = UuidSerializersModule
        }

    /**
     * Encodes field mappings to JSON string.
     */
    fun encode(mappings: Map<TransferField, FieldMapping>): String = json.encodeToString(mappings)

    /**
     * Decodes field mappings from JSON string.
     */
    fun decode(jsonString: String): Map<TransferField, FieldMapping> = json.decodeFromString(jsonString)

    /**
     * Encodes identification columns to JSON array string.
     */
    fun encodeColumns(columns: Set<String>): String = json.encodeToString(columns)

    /**
     * Decodes identification columns from JSON array string.
     */
    fun decodeColumns(jsonString: String): Set<String> = json.decodeFromString(jsonString)

    /**
     * Encodes attribute column mappings to JSON array string.
     */
    fun encodeAttributeMappings(mappings: List<AttributeColumnMapping>): String = json.encodeToString(mappings)

    /**
     * Decodes attribute column mappings from JSON array string.
     */
    fun decodeAttributeMappings(jsonString: String): List<AttributeColumnMapping> = json.decodeFromString(jsonString)

    /**
     * Encodes row preprocessing rules to JSON array string.
     */
    fun encodeRowRules(rules: List<RowPreprocessingRule>): String = json.encodeToString(rules)

    /**
     * Decodes row preprocessing rules from JSON array string.
     */
    fun decodeRowRules(jsonString: String): List<RowPreprocessingRule> = json.decodeFromString(jsonString)
}
