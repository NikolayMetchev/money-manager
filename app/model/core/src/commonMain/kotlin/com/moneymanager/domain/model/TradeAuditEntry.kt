@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/** One historical revision of a trade, with its provenance. */
data class TradeAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val tradeId: TradeId,
    val revisionId: Long,
    val source: SourceRecord? = null,
)
