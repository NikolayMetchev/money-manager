@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import kotlin.time.Instant

data class ApiImportStrategyAuditEntry(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val strategyId: ApiImportStrategyId,
    val revisionId: Long,
    val name: String,
    val config: ApiStrategyConfig,
    val createdAt: Instant,
    val updatedAt: Instant,
    val source: EntitySource? = null,
)
