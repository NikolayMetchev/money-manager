@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.SourceRecord
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
    val source: SourceRecord? = null,
)
