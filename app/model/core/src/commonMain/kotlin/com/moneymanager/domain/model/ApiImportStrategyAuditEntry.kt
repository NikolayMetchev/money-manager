@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import kotlin.time.Instant

/**
 * Represents an audit entry for an API import strategy.
 * Captures the state of a strategy at a specific point in time during an INSERT, UPDATE, or DELETE operation.
 *
 * @property id Unique identifier for this audit entry
 * @property auditTimestamp When this audit entry was created
 * @property auditType The type of operation that triggered this audit (INSERT, UPDATE, DELETE)
 * @property strategyId The ID of the strategy that was audited
 * @property revisionId The revision of the strategy at the time of the audit
 * @property name The strategy name at the time of the audit
 * @property configJson The JSON configuration at the time of the audit
 * @property createdAt The strategy creation timestamp at the time of the audit
 * @property updatedAt The strategy last-modified timestamp at the time of the audit
 */
data class ApiImportStrategyAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val strategyId: ApiImportStrategyId,
    val revisionId: Long,
    val name: String,
    val configJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val source: EntitySource? = null,
)
