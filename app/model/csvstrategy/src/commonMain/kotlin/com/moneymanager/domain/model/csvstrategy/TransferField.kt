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
    TIMEZONE,

    /**
     * The credited asset/amount of a cross-asset conversion. When a strategy maps both [TO_CURRENCY]
     * and [TO_AMOUNT] and a row's [CURRENCY] differs from [TO_CURRENCY], the importer emits a `trade`
     * (a cross-asset exchange) instead of a single-asset transfer: [CURRENCY]/[AMOUNT] leave the source
     * account and [TO_CURRENCY]/[TO_AMOUNT] enter the target account.
     */
    TO_CURRENCY,
    TO_AMOUNT,
}
