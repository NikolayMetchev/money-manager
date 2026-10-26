package com.moneymanager.domain.model.timeline

import com.moneymanager.domain.model.AccountId
import kotlin.time.Instant

enum class TimelineSourceKind {
    /** A staged file: CSV, QIF, Excel or PDF. */
    CSV,
    API,
    MANUAL,
}

/**
 * Transaction date coverage of one imported file, API session, or the aggregate of all
 * manually created transactions. Only files that actually produced transactions have a range.
 */
data class ImportFileDateRange(
    val kind: TimelineSourceKind,
    /** CSV import uuid or API session id as a string; empty for the manual aggregate. */
    val fileId: String,
    /** Original file name; API sessions and the manual aggregate use a synthetic label. */
    val fileName: String,
    /** Latest applied strategy name (CSV) or API strategy name; null for manual/legacy API. */
    val strategyName: String?,
    val ignored: Boolean = false,
    val earliest: Instant,
    val latest: Instant,
    val transactionCount: Long,
    /** Set only for account-grouped ranges: one of the two accounts a transfer leg touched. */
    val accountId: AccountId? = null,
)
