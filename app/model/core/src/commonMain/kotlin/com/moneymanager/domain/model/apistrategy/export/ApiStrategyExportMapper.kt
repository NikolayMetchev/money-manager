package com.moneymanager.domain.model.apistrategy.export

import com.moneymanager.domain.model.apistrategy.ApiImportStrategy

/**
 * Pure domain-to-export mapping for API strategies (fully portable, no references to resolve).
 * Shared by the DB-backed export service and the DB-free catalog generator.
 */
object ApiStrategyExportMapper {
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
        )
}
