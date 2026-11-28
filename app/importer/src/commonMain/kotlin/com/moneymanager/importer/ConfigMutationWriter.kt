package com.moneymanager.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.write.AccountMappingWriteRepository
import com.moneymanager.domain.repository.write.ApiImportStrategyWriteRepository
import com.moneymanager.domain.repository.write.ApiSessionWriteRepository
import com.moneymanager.domain.repository.write.CsvImportStrategyWriteRepository
import com.moneymanager.domain.repository.write.CsvImportWriteRepository
import com.moneymanager.domain.repository.write.HolidayCalendarWriteRepository
import com.moneymanager.domain.repository.write.ImportDirectoryWriteRepository
import com.moneymanager.domain.repository.write.ImportRecordWriteRepository
import com.moneymanager.domain.repository.write.ObservationWriteRepository
import com.moneymanager.domain.repository.write.PassThroughAccountWriteRepository
import com.moneymanager.domain.repository.write.PersonOverrideWriteRepository
import com.moneymanager.domain.repository.write.ReconciliationLinkWriteRepository
import com.moneymanager.domain.repository.write.SettingsWriteRepository
import com.moneymanager.domain.repository.write.TradeWriteRepository
import com.moneymanager.importengineapi.AccountMappingMutation
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.ApiStrategyMutation
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.CsvStrategyMutation
import com.moneymanager.importengineapi.HolidayCalendarMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportDirectoryMutation
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.PassThroughMutation
import com.moneymanager.importengineapi.ReconciliationLinkMutation
import com.moneymanager.importengineapi.TradeAttributeMutation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Applies a batch's configuration, staging, session and settings mutations (strategies, mappings, CSV
 * imports and their records, import directories, API credentials and sessions, settings, calendars,
 * reconciliation links, hand-set trade attributes and observation decisions), returning the ids it created.
 */
internal class ConfigMutationWriter(
    private val accountAttributeRepository: AccountAttributeWriteRepository,
    private val accountMappingRepository: AccountMappingWriteRepository,
    private val apiImportStrategyRepository: ApiImportStrategyWriteRepository,
    private val apiSessionRepository: ApiSessionWriteRepository,
    private val csvImportRepository: CsvImportWriteRepository,
    private val csvImportStrategyRepository: CsvImportStrategyWriteRepository,
    private val holidayCalendarRepository: HolidayCalendarWriteRepository,
    private val importDirectoryRepository: ImportDirectoryWriteRepository,
    private val importRecordRepository: ImportRecordWriteRepository,
    private val observationRepository: ObservationWriteRepository,
    private val passThroughAccountRepository: PassThroughAccountWriteRepository,
    private val personOverrideRepository: PersonOverrideWriteRepository,
    private val reconciliationLinkRepository: ReconciliationLinkWriteRepository,
    private val settingsRepository: SettingsWriteRepository,
    private val tradeRepository: TradeWriteRepository,
    private val handEdits: HandEdits,
) {
    class ConfigOutcome(
        val csvStrategyIds: Map<String, CsvImportStrategyId>,
        val apiStrategyIds: Map<String, ApiImportStrategyId>,
        val accountMappingIds: Map<String, Long>,
        val csvImportIds: Map<String, CsvImportId>,
        val importDirectoryIds: Map<String, ImportDirectoryId>,
        val apiCredentialIds: Map<String, ApiCredentialId>,
        val apiSessionIds: Map<String, ApiSessionId>,
        val apiRequestIds: Map<String, ApiRequestId>,
        val apiResponseIds: Map<String, ApiResponseId>,
    )

    // Fail fast rather than silently overwrite a generated id when two create mutations share a read-back
    // key in one batch — a duplicate key would otherwise drop the earlier id from the ImportResult map.
    private fun <K, V> MutableMap<K, V>.putUnique(
        key: K,
        value: V,
        label: String,
    ) {
        require(key !in this) { "Duplicate $label read-back key in ImportBatch: $key" }
        this[key] = value
    }

    private suspend fun applyReconciliationLinkMutations(mutations: List<ReconciliationLinkMutation>) {
        val repository = reconciliationLinkRepository
        val sourceByShadow = accountAttributeRepository.shadowSources()
        for (m in mutations) {
            when (m) {
                is ReconciliationLinkMutation.SetLinks -> {
                    require(m.shadowAccountId in sourceByShadow) { "Account ${m.shadowAccountId} is not a shadow account" }
                    val shadowTargets = m.realAccountIds.filter { it in sourceByShadow }
                    require(shadowTargets.isEmpty()) { "Cannot link a shadow account to shadow account(s) $shadowTargets" }
                    repository.setLinks(m.shadowAccountId, m.realAccountIds)
                    // Unlinking a wallet completely is a decision automatic linking must respect;
                    // linking it again withdraws that decision.
                    val declinedTypeId = AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_AUTO_LINK_DECLINED_ATTR_TYPE_ID)
                    val declined =
                        accountAttributeRepository.getByAccount(m.shadowAccountId).first().filter {
                            it.attributeType.id ==
                                declinedTypeId
                        }
                    when {
                        m.realAccountIds.isEmpty() && declined.isEmpty() -> {
                            accountAttributeRepository.insert(m.shadowAccountId, declinedTypeId, "true")
                        }

                        m.realAccountIds.isNotEmpty() -> {
                            declined.forEach { accountAttributeRepository.delete(it.id) }
                        }
                    }
                }
            }
        }
    }

    /** Brings the file's import records to [mutation]'s hashes, writing only the rows whose hash is new or changed. */
    private suspend fun recordRows(
        records: ImportRecordWriteRepository,
        mutation: CsvImportMutation.RecordRows,
    ) {
        val stored = records.getCsvImportRecords(mutation.id).associateBy { (it.key as ImportRecordKey.CsvRow).rowIndex }
        val inserts = mutableMapOf<ImportRecordKey, String>()
        val hashes = mutableMapOf<ImportRecordId, String>()
        for ((rowIndex, hash) in mutation.contentHashes) {
            val record = stored[rowIndex]
            when {
                record == null -> inserts[ImportRecordKey.CsvRow(mutation.id, rowIndex)] = hash
                record.contentHash != hash -> hashes[record.id] = hash
            }
        }
        records.writeRecords(inserts, hashes)
    }

    private suspend fun recordItems(mutation: ApiSessionMutation.RecordItems) {
        val records = importRecordRepository
        val hashes =
            records
                .getApiSessionRecords(mutation.sessionId)
                .filter { it.contentHash != mutation.contentHash }
                .associate { it.id to mutation.contentHash }
        records.writeRecords(emptyMap(), hashes)
    }

    suspend fun applyConfigMutations(batch: ImportBatch): ConfigOutcome {
        if (batch.reconciliationLinkMutations.isNotEmpty()) applyReconciliationLinkMutations(batch.reconciliationLinkMutations)
        for (m in batch.tradeAttributeMutations) {
            when (m) {
                is TradeAttributeMutation.Set -> tradeRepository.setAttributeByHand(m.tradeId, m.typeId, m.value, Source.Manual)
                is TradeAttributeMutation.Remove -> tradeRepository.setAttributeByHand(m.tradeId, m.typeId, null, Source.Manual)
            }
            val value = (m as? TradeAttributeMutation.Set)?.value
            personOverrideRepository.setTradeAttributeOverride(m.tradeId, m.typeId.tradeOverride(value))
        }
        if (batch.observationDecisions.isNotEmpty()) handEdits.applyDecisions(batch.observationDecisions)
        for (m in batch.passThroughMutations) {
            when (m) {
                is PassThroughMutation.Create -> passThroughAccountRepository.create(m.account)
                is PassThroughMutation.Update -> passThroughAccountRepository.update(m.account)
                is PassThroughMutation.Delete -> passThroughAccountRepository.delete(m.id)
            }
        }
        for (m in batch.holidayCalendarMutations) {
            val repository = holidayCalendarRepository
            when (m) {
                is HolidayCalendarMutation.Upsert -> repository.upsert(m.calendar)
                is HolidayCalendarMutation.Delete -> repository.delete(m.code)
            }
        }
        val csvStrategyIds = mutableMapOf<String, CsvImportStrategyId>()
        for (m in batch.csvStrategyMutations) {
            when (m) {
                is CsvStrategyMutation.Create -> {
                    csvStrategyIds.putUnique(m.key, csvImportStrategyRepository.createStrategy(m.strategy, m.source), "CsvStrategy")
                }

                is CsvStrategyMutation.Update -> {
                    val previous = csvImportStrategyRepository.getStrategyById(m.strategy.id).first()
                    require(previous == null || m.strategy.keepsImportSettingsOf(previous)) {
                        "\"${m.strategy.name}\" can't stop setting its source account or currency: the files imported with it re-import with them"
                    }
                    csvImportStrategyRepository.updateStrategy(m.strategy, m.source)
                }

                is CsvStrategyMutation.Delete -> {
                    csvImportStrategyRepository.deleteStrategy(m.id)
                }
            }
        }

        val apiStrategyIds = mutableMapOf<String, ApiImportStrategyId>()
        for (m in batch.apiStrategyMutations) {
            when (m) {
                is ApiStrategyMutation.Create -> {
                    apiStrategyIds.putUnique(m.key, apiImportStrategyRepository.createStrategy(m.strategy, m.source), "ApiStrategy")
                }

                is ApiStrategyMutation.Update -> {
                    apiImportStrategyRepository.updateStrategy(m.strategy, m.source)
                }

                is ApiStrategyMutation.Delete -> {
                    apiImportStrategyRepository.deleteStrategy(m.id)
                }
            }
        }

        val accountMappingIds = mutableMapOf<String, Long>()
        for (m in batch.accountMappingMutations) {
            when (m) {
                is AccountMappingMutation.Create -> {
                    accountMappingIds.putUnique(
                        m.key,
                        accountMappingRepository.createMapping(m.valuePattern, m.accountId, m.strategyId),
                        "AccountMapping",
                    )
                }

                is AccountMappingMutation.CreateBatch -> {
                    accountMappingRepository.createMappings(m.mappings)
                }

                is AccountMappingMutation.Update -> {
                    accountMappingRepository.updateMapping(m.mapping)
                }

                is AccountMappingMutation.Delete -> {
                    accountMappingRepository.deleteMapping(m.id)
                }
            }
        }

        val csvImportIds = mutableMapOf<String, CsvImportId>()
        for (m in batch.csvImportMutations) {
            when (m) {
                is CsvImportMutation.Create -> {
                    csvImportIds.putUnique(
                        m.key,
                        csvImportRepository.createImport(
                            m.fileName,
                            m.headers,
                            m.rows,
                            m.fileChecksum,
                            m.fileLastModified,
                            m.xlsxBytes,
                            m.xlsxWorksheetName,
                            m.pdf,
                            m.sqlite,
                        ),
                        "CsvImport",
                    )
                }

                is CsvImportMutation.Restage -> {
                    csvImportRepository.restageImport(m.id, m.headers, m.rows, m.worksheetName, m.pdfRulesHash, m.sqliteQuery)
                }

                is CsvImportMutation.Repopulate -> {
                    csvImportRepository.repopulateImport(
                        m.id,
                        m.headers,
                        m.rows,
                        m.fileChecksum,
                        m.fileLastModified,
                        m.carriedRows,
                        m.rowIndexRemap,
                        m.xlsxBytes,
                        m.pdf,
                        m.sqlite,
                    )
                }

                is CsvImportMutation.Delete -> {
                    csvImportRepository.deleteImport(m.id)
                }

                is CsvImportMutation.SetIgnored -> {
                    csvImportRepository.setImportIgnored(m.id, m.ignored)
                }

                is CsvImportMutation.Rename -> {
                    csvImportRepository.setOriginalFileName(m.id, m.fileName)
                }

                is CsvImportMutation.ResetToUnimported -> {
                    csvImportRepository.resetToUnimported(m.id)
                }

                is CsvImportMutation.UpdateRowStatuses -> {
                    csvImportRepository.updateRowStatusesBatch(m.id, m.status, m.rowIndexes)
                }

                is CsvImportMutation.Reserve -> {
                    csvImportRepository.reserveForRerun(m.id, RECORD_UNHASHED)
                }

                is CsvImportMutation.SaveErrors -> {
                    csvImportRepository.saveErrors(m.id, m.errors)
                }

                is CsvImportMutation.ClearError -> {
                    csvImportRepository.clearError(m.id, m.rowIndex)
                }

                is CsvImportMutation.ClearErrors -> {
                    csvImportRepository.clearErrors(m.id, m.rowIndexes)
                }

                is CsvImportMutation.RecordApplication -> {
                    csvImportRepository.recordImportApplication(
                        m.id,
                        m.strategyId,
                        m.strategyName,
                        m.appliedAt,
                        m.accountId,
                        m.currencyId,
                    )
                }

                is CsvImportMutation.RecordRows -> {
                    recordRows(importRecordRepository, m)
                }
            }
        }

        val importDirectoryIds = mutableMapOf<String, ImportDirectoryId>()
        for (m in batch.importDirectoryMutations) {
            when (m) {
                is ImportDirectoryMutation.Create -> {
                    importDirectoryIds.putUnique(
                        m.key,
                        importDirectoryRepository.createDirectory(m.directory, m.source),
                        "ImportDirectory",
                    )
                }

                is ImportDirectoryMutation.Update -> {
                    importDirectoryRepository.updateDirectory(m.directory, m.source)
                }

                is ImportDirectoryMutation.Delete -> {
                    importDirectoryRepository.deleteDirectory(m.id)
                }

                is ImportDirectoryMutation.RecordFileImported -> {
                    importDirectoryRepository.recordFileImported(
                        m.directoryId,
                        m.fileRef,
                        m.fileName,
                        m.lastModified,
                        m.checksum,
                        m.remoteContentHash,
                        m.csvImportId,
                        m.importedAt,
                    )
                }

                is ImportDirectoryMutation.ForgetFile -> {
                    importDirectoryRepository.forgetFile(m.directoryId, m.fileRef)
                }
            }
        }

        val apiCredentialIds = mutableMapOf<String, ApiCredentialId>()
        val apiSessionIds = mutableMapOf<String, ApiSessionId>()
        val apiRequestIds = mutableMapOf<String, ApiRequestId>()
        val apiResponseIds = mutableMapOf<String, ApiResponseId>()
        for (m in batch.apiSessionMutations) {
            when (m) {
                is ApiSessionMutation.EnsureCredential -> {
                    apiCredentialIds.putUnique(
                        m.key,
                        apiSessionRepository.ensureCredential(m.strategyId, m.createdAt),
                        "ApiCredential",
                    )
                }

                is ApiSessionMutation.UpdateCredentialStrategy -> {
                    apiSessionRepository.updateCredentialStrategy(
                        m.credentialId,
                        m.strategyId,
                    )
                }

                is ApiSessionMutation.CreateSession -> {
                    apiSessionIds.putUnique(
                        m.key,
                        apiSessionRepository.createSession(m.deviceId, m.createdAt, expiresAt = null, m.credentialId),
                        "ApiSession",
                    )
                }

                is ApiSessionMutation.InsertRequest -> {
                    apiRequestIds.putUnique(
                        m.key,
                        apiSessionRepository.insertRequest(m.sessionId, m.method, m.url, m.headers),
                        "ApiRequest",
                    )
                }

                is ApiSessionMutation.RecordDownloadCoverage -> {
                    apiSessionRepository.recordDownloadCoverage(m.sessionId, m.endpointKey, m.coversUntil)
                }

                is ApiSessionMutation.InsertResponse -> {
                    apiResponseIds.putUnique(
                        m.key,
                        apiSessionRepository.insertResponse(m.requestId, m.sessionId, m.json),
                        "ApiResponse",
                    )
                }

                is ApiSessionMutation.DeleteSession -> {
                    accountAttributeRepository.releaseApiSession(m.id)
                    deleteSessionRecords(m.id)
                    apiSessionRepository.deleteSession(m.id)
                }

                is ApiSessionMutation.InsertResponseTransactions -> {
                    apiSessionRepository.insertResponseTransactions(m.transactions)
                }

                is ApiSessionMutation.DeleteResponseTransactionsBySession -> {
                    apiSessionRepository.deleteResponseTransactionsBySession(m.sessionId)
                }

                is ApiSessionMutation.ReleaseAdoptedAttributes -> {
                    accountAttributeRepository.releaseApiSession(m.sessionId)
                }

                is ApiSessionMutation.RecordItems -> {
                    recordItems(m)
                }

                is ApiSessionMutation.Reserve -> {
                    observationRepository.reserveApiSession(m.sessionId)
                }

                is ApiSessionMutation.ResetToUnimported -> {
                    accountAttributeRepository.releaseApiSession(m.sessionId)
                    deleteSessionRecords(m.sessionId)
                    apiSessionRepository.deleteResponseTransactionsBySession(m.sessionId)
                    apiSessionRepository.deleteSessionImports(m.sessionId)
                }

                is ApiSessionMutation.MarkSessionImported -> {
                    apiSessionRepository.markSessionImported(m.id, m.revisionId, m.importedAt, m.importDurationMillis)
                }
            }
        }

        batch.settings?.let { s ->
            s.defaultCurrencyId?.let { settingsRepository.setDefaultCurrencyId(it) }
            s.defaultLocation?.let { settingsRepository.setDefaultLocation(it.code) }
            s.setupWizardCompleted?.let { settingsRepository.setSetupWizardCompleted(it) }
        }

        return ConfigOutcome(
            csvStrategyIds = csvStrategyIds,
            apiStrategyIds = apiStrategyIds,
            accountMappingIds = accountMappingIds,
            csvImportIds = csvImportIds,
            importDirectoryIds = importDirectoryIds,
            apiCredentialIds = apiCredentialIds,
            apiSessionIds = apiSessionIds,
            apiRequestIds = apiRequestIds,
            apiResponseIds = apiResponseIds,
        )
    }

    /** Deletes [sessionId]'s import records, which restrict the session's deletion, and their observations first. */
    private suspend fun deleteSessionRecords(sessionId: ApiSessionId) {
        val records = importRecordRepository
        val observations = observationRepository
        for (record in records.getApiSessionRecords(sessionId)) {
            observations.getObservationsOf(ObservationOwner.Record(record.id)).forEach { observations.deleteObservation(it.id) }
            records.deleteRecord(record.id)
        }
    }
}
