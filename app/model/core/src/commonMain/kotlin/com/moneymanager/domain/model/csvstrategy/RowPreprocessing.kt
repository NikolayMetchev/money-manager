package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.Serializable

/**
 * Operators supported by [RowCondition].
 */
@Serializable
enum class RowConditionOperator {
    /** The column's value equals [RowCondition.value] (case-sensitive, trimmed). */
    EQUALS_VALUE,

    /** The column's value equals the value of [RowCondition.otherColumnName] (trimmed). */
    EQUALS_COLUMN,

    /** The column's value differs from the value of [RowCondition.otherColumnName] (trimmed). */
    NOT_EQUALS_COLUMN,

    /** The column's value is blank. */
    IS_BLANK,

    /** The column's value is not blank. */
    IS_NOT_BLANK,
}

/**
 * A single predicate evaluated against a CSV row's values.
 *
 * @property columnName The column whose value is tested
 * @property operator How the value is compared
 * @property value Comparison literal for [RowConditionOperator.EQUALS_VALUE]
 * @property otherColumnName Comparison column for the column-to-column operators
 */
@Serializable
data class RowCondition(
    val columnName: String,
    val operator: RowConditionOperator,
    val value: String? = null,
    val otherColumnName: String? = null,
)

/**
 * A pair of columns whose values are exchanged when a [RowPreprocessingRule] applies.
 */
@Serializable
data class ColumnPairSwap(
    val firstColumn: String,
    val secondColumn: String,
)

/**
 * A row-level preprocessing rule applied before field mappings run.
 *
 * When all [conditions] hold for a row, each [ColumnPairSwap] exchanges the two columns'
 * values, and [flipSourceAndTarget] optionally swaps the resolved source/target accounts.
 * This supports exports like Wise's, where a Direction column decides which side of the
 * row (Source* or Target* columns) describes the user's own account.
 */
@Serializable
data class RowPreprocessingRule(
    val conditions: List<RowCondition>,
    val columnSwaps: List<ColumnPairSwap> = emptyList(),
    val flipSourceAndTarget: Boolean = false,
)
