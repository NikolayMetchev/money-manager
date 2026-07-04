@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.service

import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExport
import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExportMapper
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Converts API import strategies to/from the portable [ApiStrategyExport] format. An API strategy is
 * fully portable (no account/currency/category references, no embedded credentials), so — unlike the
 * CSV path — there are no references to resolve.
 */
class ApiStrategyExportService {
    /** Converts an [ApiImportStrategy] to its portable export format. */
    fun toExport(
        strategy: ApiImportStrategy,
        appVersion: AppVersion,
    ): ApiStrategyExport = ApiStrategyExportMapper.toExport(strategy, appVersion.value)

    /**
     * Builds a (not-yet-saved) [ApiImportStrategy] from an export, with a fresh id and timestamps.
     * The write repository recomputes `config_json` from these fields, so it is left blank here.
     */
    fun createStrategyFromExport(export: ApiStrategyExport): ApiImportStrategy {
        val now = Clock.System.now()
        return ApiImportStrategy(
            id = ApiImportStrategyId(Uuid.random()),
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
            createdAt = now,
            updatedAt = now,
        )
    }
}
