package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.Serializable

/**
 * Represents the target fields in a Transfer that can be mapped from CSV columns.
 */
@Serializable
enum class TransferField {
    SOURCE_ACCOUNT,
    TARGET_ACCOUNT,
    TIMESTAMP,
    DESCRIPTION,
    AMOUNT,
    CURRENCY,
}
