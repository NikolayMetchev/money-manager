package com.moneymanager.domain.model.csvstrategy

import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.SortedConditionListSerializer
import kotlinx.serialization.Serializable

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
    @Serializable(with = SortedConditionListSerializer::class)
    val conditions: List<Condition>,
    // Swaps apply sequentially and can chain (A<->B then B<->C differs from the reverse) - order is
    // semantic, keeps default insertion-order serialization.
    val columnSwaps: List<ColumnPairSwap> = emptyList(),
    val flipSourceAndTarget: Boolean = false,
)
