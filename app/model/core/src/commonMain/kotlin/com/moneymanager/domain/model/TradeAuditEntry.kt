@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/** One historical revision of a trade, with its audited field values and provenance. */
data class TradeAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val tradeId: TradeId,
    val revisionId: Long,
    val timestamp: Instant,
    val description: String,
    val fromAccountId: AccountId,
    val fromAmount: Money,
    val toAccountId: AccountId,
    val toAmount: Money,
    val source: SourceRecord? = null,
)
