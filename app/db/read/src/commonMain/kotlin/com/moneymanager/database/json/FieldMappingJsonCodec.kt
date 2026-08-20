package com.moneymanager.database.json

import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.ContentMatchRule
import com.moneymanager.domain.model.csvstrategy.ConversionConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.serialization.UuidSerializersModule
import kotlinx.serialization.json.Json

/**
 * Codec for the JSON-valued columns of a CSV import strategy. One named encode/decode pair per
 * column, so a call site names the column it is reading; the pairs taking/returning a nullable value
 * back a nullable column.
 */
object FieldMappingJsonCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            serializersModule = UuidSerializersModule
        }

    fun encode(mappings: Map<TransferField, FieldMapping>): String = json.encodeToString(mappings)

    fun decode(jsonString: String): Map<TransferField, FieldMapping> = json.decodeFromString(jsonString)

    fun encodeColumns(columns: Set<String>): String = json.encodeToString(columns)

    fun decodeColumns(jsonString: String): Set<String> = json.decodeFromString(jsonString)

    fun encodeAttributeMappings(mappings: List<AttributeColumnMapping>): String = json.encodeToString(mappings)

    fun decodeAttributeMappings(jsonString: String): List<AttributeColumnMapping> = json.decodeFromString(jsonString)

    fun encodeRowRules(rules: List<RowPreprocessingRule>): String = json.encodeToString(rules)

    fun decodeRowRules(jsonString: String): List<RowPreprocessingRule> = json.decodeFromString(jsonString)

    fun encodeCompanionRules(rules: List<CompanionTransactionRule>): String = json.encodeToString(rules)

    fun decodeCompanionRules(jsonString: String): List<CompanionTransactionRule> = json.decodeFromString(jsonString)

    fun encodeContentRules(rules: List<ContentMatchRule>): String = json.encodeToString(rules)

    fun decodeContentRules(jsonString: String): List<ContentMatchRule> = json.decodeFromString(jsonString)

    fun encodeConversionConfig(config: ConversionConfig?): String? = config?.let { json.encodeToString(it) }

    fun decodeConversionConfig(jsonString: String?): ConversionConfig? = jsonString?.let { json.decodeFromString(it) }

    fun encodeAttributeAccountMatch(match: AttributeAccountMatch?): String? = match?.let { json.encodeToString(it) }

    fun decodeAttributeAccountMatch(jsonString: String?): AttributeAccountMatch? = jsonString?.let { json.decodeFromString(it) }
}
