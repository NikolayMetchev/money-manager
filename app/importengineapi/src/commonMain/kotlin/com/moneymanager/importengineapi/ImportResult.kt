package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus

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
