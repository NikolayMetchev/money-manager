@file:OptIn(ExperimentalTime::class)

package com.moneymanager.domain.model.timeline

import com.moneymanager.domain.model.AccountId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class TimelineSourceKind {
    CSV,
    QIF,
    API,
    MANUAL,
}

/**
 * Transaction date coverage of one imported file (CSV/QIF), API session, or the aggregate of all
 * manually created transactions. Only files that actually produced transactions have a range.
 */
data class ImportFileDateRange(
    val kind: TimelineSourceKind,
    /** CSV/QIF import uuid or API session id as a string; empty for the manual aggregate. */
    val fileId: String,
    /** Original file name; API sessions and the manual aggregate use a synthetic label. */
    val fileName: String,
    /** Latest applied strategy name (CSV/QIF) or API strategy name; null for manual/legacy API. */
    val strategyName: String?,
    val ignored: Boolean = false,
    val earliest: Instant,
    val latest: Instant,
    val transactionCount: Long,
    /** Set only for account-grouped ranges: one of the two accounts a transfer leg touched. */
    val accountId: AccountId? = null,
)
