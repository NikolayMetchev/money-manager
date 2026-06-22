package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransactionId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifImportId

/**
 * Per-row outcome of dedupe + write, for status write-back by the caller.
 *
 * @property status IMPORTED (newly created), DUPLICATE (skipped), or UPDATED (existing transfer updated).
 * @property transferId The created transfer id for IMPORTED, or the existing transfer id for
 *   DUPLICATE/UPDATED.
 */
data class RowOutcome(
    val status: ImportStatus,
    val transferId: TransferId?,
)

/**
 * Outcome of a central import run.
 *
 * @property createdTransferIds Map from each imported row's [ImportRowKey] to the created [TransferId].
 * @property rowOutcomes Per-row classification + resulting transfer id for every non-error transfer row.
 * @property createdAccountIds Real ids of accounts created (or matched/reused) for each
 *   [LocalAccountKey] in the batch's `accountsToCreate`.
 * @property createdCategoryIds Real ids of categories created for each [LocalCategoryKey] in `categories`.
 * @property createdPersonIds Real ids of people created (or matched) for each [LocalPersonKey] in `peopleToCreate`.
 */
data class ImportResult(
    val accountsCreated: Int = 0,
    val peopleCreated: Int = 0,
    val ownershipsCreated: Int = 0,
    val transfersImported: Int = 0,
    val duplicates: Int = 0,
    val updated: Int = 0,
    val errors: Int = 0,
    val excluded: Int = 0,
    val createdTransferIds: Map<ImportRowKey, TransferId> = emptyMap(),
    val rowOutcomes: Map<ImportRowKey, RowOutcome> = emptyMap(),
    // Per-transfer outcomes aligned to the input ImportBatch.transfers order. Use this (instead of the
    // keyed rowOutcomes) when row keys are not unique per transfer (e.g. API pages, QIF splits).
    val orderedRowOutcomes: List<RowOutcome> = emptyList(),
    val createdAccountIds: Map<LocalAccountKey, AccountId> = emptyMap(),
    val createdCategoryIds: Map<LocalCategoryKey, Long> = emptyMap(),
    val createdPersonIds: Map<LocalPersonKey, PersonId> = emptyMap(),
    val createdCurrencyIds: Map<LocalCurrencyKey, CurrencyId> = emptyMap(),
    /** Resolved (get-or-create) attribute-type ids for each name in [ImportBatch.attributeTypeNames]. */
    val attributeTypeIds: Map<String, AttributeTypeId> = emptyMap(),
    /** Resolved (get-or-create) relationship-type ids for each name in [ImportBatch.relationshipTypeNames]. */
    val relationshipTypeIds: Map<String, RelationshipTypeId> = emptyMap(),
    // Generated ids for config/staging/session Create mutations, keyed by the mutation's `key`.
    val createdCsvStrategyIds: Map<String, CsvImportStrategyId> = emptyMap(),
    val createdApiStrategyIds: Map<String, ApiImportStrategyId> = emptyMap(),
    val createdCsvMappingIds: Map<String, Long> = emptyMap(),
    val createdCsvImportIds: Map<String, CsvImportId> = emptyMap(),
    val createdQifImportIds: Map<String, QifImportId> = emptyMap(),
    val apiCredentialIds: Map<String, MonzoCredentialId> = emptyMap(),
    val apiSessionIds: Map<String, ApiSessionId> = emptyMap(),
    val apiRequestIds: Map<String, ApiRequestId> = emptyMap(),
    val apiResponseIds: Map<String, ApiResponseId> = emptyMap(),
    val apiResponseTransactionIds: Map<String, ApiResponseTransactionId> = emptyMap(),
)

/**
 * Progress emitted during a central import run.
 *
 * @property fraction Overall completion in [0, 1] for the current phase, when known.
 * @property processed Number of transfers written so far during the write phase, when known.
 * @property total Total number of transfers to write during the write phase, when known.
 */
data class ImportProgress(
    val detail: String,
    val fraction: Float? = null,
    val processed: Int? = null,
    val total: Int? = null,
)
