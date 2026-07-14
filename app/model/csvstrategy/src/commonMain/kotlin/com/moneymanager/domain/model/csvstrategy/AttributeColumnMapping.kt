package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.Serializable

/**
 * Maps a CSV column to a transaction attribute type.
 * Unused columns in CSV imports can be captured as attributes using these mappings.
 *
 * @property columnName The name of the CSV column to read the value from.
 * @property attributeTypeName The name of the attribute type to create/use for this column.
 *                            This will be resolved to or created as an AttributeType in the database.
 * @property isUniqueIdentifier If true, this column is used to detect duplicate transactions across
 *                             multiple CSV imports. When multiple columns are marked as unique identifiers,
 *                             all must match for a duplicate to be detected. The duplicate-detection key
 *                             always uses the whole column value, independent of [extraction].
 * @property extraction When set, the attribute value is a capture group extracted from the column
 *                      (see [ColumnExtraction]) rather than the whole column value; if the pattern
 *                      does not match, the attribute is omitted for that row.
 * @property emitWhenMatched When set (and [extraction] matches), this fixed value is emitted as the
 *                           attribute value instead of the extracted text. Lets a label such as
 *                           `CARD_PAYMENT` be tagged whenever a transaction-type pattern matches.
 */
@Serializable
data class AttributeColumnMapping(
    val columnName: String,
    val attributeTypeName: String,
    val isUniqueIdentifier: Boolean = false,
    val extraction: ColumnExtraction? = null,
    val emitWhenMatched: String? = null,
) : Comparable<AttributeColumnMapping> {
    // Natural order for canonical export serialization: (columnName, attributeTypeName) is the
    // mapping's identity; entries tying on both sort stably, which is deterministic enough because
    // their stored order is itself part of the strategy's persisted config.
    override fun compareTo(other: AttributeColumnMapping): Int = compareValuesBy(this, other, { it.columnName }, { it.attributeTypeName })
}
