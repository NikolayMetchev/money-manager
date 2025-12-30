package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.Serializable

/**
 * Maps a CSV column to a transaction attribute type.
 * Unused columns in CSV imports can be captured as attributes using these mappings.
 *
 * @property columnName The name of the CSV column to read the value from.
 * @property attributeTypeName The name of the attribute type to create/use for this column.
 *                            This will be resolved to or created as an AttributeType in the database.
 */
@Serializable
data class AttributeColumnMapping(
    val columnName: String,
    val attributeTypeName: String,
)
