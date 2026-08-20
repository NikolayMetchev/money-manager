package com.moneymanager.domain.model.apistrategy.export

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import kotlin.time.Instant

/**
 * Pure domain-to-export mapping for API strategies (fully portable, no references to resolve).
 * Shared by the DB-backed export service and the DB-free catalog generator.
 */
object ApiStrategyExportMapper {
    /**
     * Inverse of [toExport]: builds a (not-yet-persisted) [ApiImportStrategy] from a portable export,
     * with the supplied [id] and timestamps. Fully portable — no reference resolution needed.
     */
    fun fromExport(
        export: ApiStrategyExport,
        id: ApiImportStrategyId,
        now: Instant,
    ): ApiImportStrategy =
        ApiImportStrategy(
            id = id,
            name = export.name,
            config = export.config,
            createdAt = now,
            updatedAt = now,
        )

    fun toExport(
        strategy: ApiImportStrategy,
        version: String,
    ): ApiStrategyExport = ApiStrategyExport(version = version, name = strategy.name, config = strategy.config)
}
