@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.apistrategy.export

import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
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
            baseUrl = export.baseUrl,
            authType = export.authType,
            accountsEndpoint = export.accountsEndpoint,
            transactionsEndpoint = export.transactionsEndpoint,
            accountMappings = export.accountMappings,
            transactionMappings = export.transactionMappings,
            peopleMappings = export.peopleMappings,
            accountIdentifiersEndpoint = export.accountIdentifiersEndpoint,
            ancestorEndpoints = export.ancestorEndpoints,
            builtInCounterpartyRules = export.builtInCounterpartyRules,
            signing = export.signing,
            peopleDownload = export.peopleDownload,
            personExternalIdAttribute = export.personExternalIdAttribute,
            requestSigning = export.requestSigning,
            dataEndpoints = export.dataEndpoints,
            syntheticAccount = export.syntheticAccount,
            internalTransferReconcile = export.internalTransferReconcile,
            createdAt = now,
            updatedAt = now,
        )

    fun toExport(
        strategy: ApiImportStrategy,
        version: String,
    ): ApiStrategyExport =
        ApiStrategyExport(
            version = version,
            name = strategy.name,
            baseUrl = strategy.baseUrl,
            authType = strategy.authType,
            accountsEndpoint = strategy.accountsEndpoint,
            transactionsEndpoint = strategy.transactionsEndpoint,
            accountMappings = strategy.accountMappings,
            transactionMappings = strategy.transactionMappings,
            peopleMappings = strategy.peopleMappings,
            accountIdentifiersEndpoint = strategy.accountIdentifiersEndpoint,
            ancestorEndpoints = strategy.ancestorEndpoints,
            builtInCounterpartyRules = strategy.builtInCounterpartyRules,
            signing = strategy.signing,
            peopleDownload = strategy.peopleDownload,
            personExternalIdAttribute = strategy.personExternalIdAttribute,
            requestSigning = strategy.requestSigning,
            dataEndpoints = strategy.dataEndpoints,
            syntheticAccount = strategy.syntheticAccount,
            internalTransferReconcile = strategy.internalTransferReconcile,
        )
}
