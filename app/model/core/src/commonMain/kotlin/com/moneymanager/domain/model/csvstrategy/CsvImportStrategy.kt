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
     * SOURCE_ACCOUNT is optional because it can be chosen at import time.
     */
    fun isValid(): Boolean = requiredFields.all { it in fieldMappings }

    /**
     * Returns the set of TransferFields that are missing mappings.
     * SOURCE_ACCOUNT is excluded because it is optional (chosen at import time).
     */
    fun missingFields(): Set<TransferField> = requiredFields - fieldMappings.keys

    companion object {
        /**
         * TransferFields that must be present in a strategy. SOURCE_ACCOUNT is excluded because
         * it can be chosen by the user at import time rather than being baked into the strategy.
         */
        val requiredFields: Set<TransferField> = TransferField.entries.toSet() - TransferField.SOURCE_ACCOUNT
    }

    /**
     * Returns true if the given CSV column headings match this strategy's identification columns.
     * Matching is exact and order-independent.
     */
    fun matchesColumns(csvHeadings: Set<String>): Boolean = identificationColumns == csvHeadings
}
