@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/** One historical revision of a crypto asset, with its provenance. Mirrors [CurrencyAuditEntry]. */
data class CryptoAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val cryptoId: CryptoId,
    val revisionId: Long,
    val code: String,
    val name: String,
    val scaleFactor: Long,
    val source: SourceRecord? = null,
)
