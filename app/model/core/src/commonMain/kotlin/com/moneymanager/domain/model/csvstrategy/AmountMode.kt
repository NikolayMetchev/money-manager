package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.Serializable

/**
 * Represents the mode for parsing amounts from CSV.
 */
@Serializable
enum class AmountMode {
    /**
     * Single column containing the amount, with sign indicating direction.
     * Positive = credit/inflow, Negative = debit/outflow.
     */
    SINGLE_COLUMN,

    /**
     * Separate columns for credit and debit amounts.
     * Only one column has a value per row.
     */
    CREDIT_DEBIT_COLUMNS,
}
