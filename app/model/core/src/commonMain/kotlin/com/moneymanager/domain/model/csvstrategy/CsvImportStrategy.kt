@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.csvstrategy

import kotlin.time.Instant

/**
 * Represents a reusable CSV import strategy that defines how to map CSV columns
 * to Transfer fields.
 *
 * @property id Unique identifier for this strategy
 * @property name Human-readable name for the strategy (must be unique)
 * @property identificationColumns Set of column names used to auto-identify this strategy
 *                                 when importing a CSV file. Matching is exact and order-independent.
 * @property fieldMappings Map of TransferField to FieldMapping defining how each field is populated
 * @property createdAt Timestamp when this strategy was created
 * @property updatedAt Timestamp when this strategy was last modified
 */
data class CsvImportStrategy(
    val id: CsvImportStrategyId,
    val name: String,
    val identificationColumns: Set<String>,
    val fieldMappings: Map<TransferField, FieldMapping>,
    val attributeMappings: List<AttributeColumnMapping> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * Returns true if this strategy has mappings for all required TransferFields.
     */
    fun isValid(): Boolean = TransferField.entries.all { it in fieldMappings }

    /**
     * Returns the set of TransferFields that are missing mappings.
     */
    fun missingFields(): Set<TransferField> = TransferField.entries.toSet() - fieldMappings.keys

    /**
     * Returns true if the given CSV column headings match this strategy's identification columns.
     * Matching is exact and order-independent.
     */
    fun matchesColumns(csvHeadings: Set<String>): Boolean = identificationColumns == csvHeadings
}
