package com.moneymanager.domain.model

import kotlin.time.Instant

/** One historical revision of an exchange order, with its audited field values and provenance. */
data class ExchangeOrderAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val orderId: ExchangeOrderId,
    val revisionId: Long,
    val accountId: AccountId,
    val orderRef: String,
    val clientOid: String?,
    val side: String,
    val orderType: String?,
    val timeInForce: String?,
    val status: String?,
    val limitPrice: String?,
    val quantity: String?,
    val avgPrice: String?,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val source: SourceRecord? = null,
)
