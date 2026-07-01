@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.service

import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.accountmapping.export.AccountMappingsExport
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.createAccountMappings
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * Result of parsing an account-mappings export file, before resolution.
 *
 * @property export The parsed export data
 * @property unresolvedAccountNames Account names referenced by the export that don't exist in this database
 */
data class AccountMappingParseResult(
    val export: AccountMappingsExport,
    val unresolvedAccountNames: List<String>,
)

/**
 * Service for converting the global set of account mappings between domain models and the portable
 * export format, and for importing an export back (resolving account names, creating missing accounts).
 */
class AccountMappingExportService(
    private val accountRepository: AccountReadRepository,
    private val importEngine: ImportEngine,
) {
    // Entities created while importing mappings are a manual user action on this device.
    private val source = Source.Manual

    /**
     * Converts persisted account mappings to their portable export format.
     * Resolves account IDs to human-readable account names.
     */
    suspend fun toExport(
        mappings: List<AccountMapping>,
        appVersion: AppVersion,
    ): AccountMappingsExport {
        val accountsById = accountRepository.getAllAccounts().first().associateBy { it.id }
        return AccountMappingsExport(
            version = appVersion.value,
            mappings =
                mappings.map { mapping ->
                    val account =
                        accountsById[mapping.accountId]
                            ?: error("Missing account for id ${mapping.accountId.id} in AccountMapping")
                    AccountMappingExport(
                        columnName = mapping.columnName,
                        valuePattern = mapping.valuePattern.pattern,
                        accountName = account.name,
                    )
                },
        )
    }

    /**
     * Parses an export and identifies account names that don't resolve against this database.
     */
    suspend fun parseExport(export: AccountMappingsExport): AccountMappingParseResult {
        val accountsByName = accountRepository.getAllAccounts().first().associateBy { it.name }
        val unresolved =
            export.mappings
                .map { it.accountName }
                .distinct()
                .filter { it !in accountsByName }
        return AccountMappingParseResult(export = export, unresolvedAccountNames = unresolved)
    }

    /**
     * Imports the [export], creating any accounts requested via [resolutions] (keyed by the export's
     * account name), resolving every mapping to an account id, and persisting the mappings through the
     * import engine. Mappings whose account cannot be resolved are skipped. Returns the number persisted.
     */
    suspend fun importMappings(
        export: AccountMappingsExport,
        resolutions: Map<String, Resolution>,
    ): Int {
        // Create any new accounts the user requested, in one engine batch (the sole writer).
        val accountIntents =
            resolutions
                .filterValues { it is Resolution.CreateNew }
                .map { (name, resolution) ->
                    ImportAccountIntent(
                        key = LocalAccountKey(name),
                        source = source,
                        match = AccountMatchKey.AlwaysCreate,
                        name = (resolution as Resolution.CreateNew).name,
                        openingDate = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    )
                }
        if (accountIntents.isNotEmpty()) {
            importEngine.import(ImportBatch(accountsToCreate = accountIntents))
        }

        // Build a name -> account lookup covering both existing and just-created accounts.
        val accountsByName =
            accountRepository
                .getAllAccounts()
                .first()
                .associateBy { it.name }
                .toMutableMap()
        for ((name, resolution) in resolutions) {
            if (resolution is Resolution.MapToExisting) {
                accountsByName.values.find { it.id.id == resolution.id }?.let { accountsByName[name] = it }
            }
        }

        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val mappings =
            export.mappings.mapNotNull { mappingExport ->
                val account = accountsByName[mappingExport.accountName] ?: return@mapNotNull null
                AccountMapping(
                    id = 0,
                    columnName = mappingExport.columnName,
                    valuePattern = Regex(mappingExport.valuePattern, RegexOption.IGNORE_CASE),
                    accountId = account.id,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        if (mappings.isNotEmpty()) {
            importEngine.createAccountMappings(mappings)
        }
        return mappings.size
    }
}
