@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database

import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.database.sql.csvImportStrategy.CsvImportStrategyWriteQueries
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy

/**
 * Inserts a [CsvImportStrategy] row, serialising its config sections to JSON. Shared by the runtime
 * repository (user-created strategies) and the seeder (built-in strategies) so the column/encoder list
 * lives in one place. created_at/updated_at are filled by the table's DEFAULT (current time).
 */
fun CsvImportStrategyWriteQueries.insertStrategy(strategy: CsvImportStrategy) {
    insert(
        id = strategy.id.id.toString(),
        name = strategy.name,
        identification_columns_json = FieldMappingJsonCodec.encodeColumns(strategy.identificationColumns),
        field_mappings_json = FieldMappingJsonCodec.encode(strategy.fieldMappings),
        attribute_mappings_json = FieldMappingJsonCodec.encodeAttributeMappings(strategy.attributeMappings),
        row_rules_json = FieldMappingJsonCodec.encodeRowRules(strategy.rowPreprocessingRules),
        companion_rules_json = FieldMappingJsonCodec.encodeCompanionRules(strategy.companionTransactionRules),
        content_match_rules_json = FieldMappingJsonCodec.encodeContentRules(strategy.contentMatchRules),
    )
}
