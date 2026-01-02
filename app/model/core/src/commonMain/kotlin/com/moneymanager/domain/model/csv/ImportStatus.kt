package com.moneymanager.domain.model.csv

/**
 * Status of a CSV row import operation.
 * Tracks whether a transaction was newly imported, detected as duplicate, or updated.
 */
enum class ImportStatus {
    /**
     * New transaction created from this CSV row.
     */
    IMPORTED,

    /**
     * Existing transaction found with identical values - no action taken.
     */
    DUPLICATE,

    /**
     * Existing transaction found with different values - transaction updated.
     */
    UPDATED,
}
