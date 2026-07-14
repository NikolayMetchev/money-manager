package com.moneymanager.domain.model.qif

import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import kotlinx.serialization.Serializable

/**
 * A single parsed QIF record, used both as input to an import (with [transferId]/[importStatus]
 * null) and as the stored/displayed record afterwards.
 *
 * Field values are kept as raw strings; date and amount interpretation is the import
 * strategy's responsibility.
 *
 * @property recordIndex Global 0-based index across the whole file; the provenance key.
 * @property sectionType The QIF section type name (e.g. "BANK", "INVESTMENT").
 * @property accountName The owning account name, when known.
 * @property supported Whether this record is importable as a transaction in v1.
 * @property rawText The verbatim QIF text of the record (newline-joined).
 * @property splits Parsed split sub-records.
 * @property transferId The transfer created from this record, null if not yet imported.
 * @property importStatus The import status, null if not yet processed.
 * @property errorMessage The error message if import failed, null otherwise.
 */
data class QifImportRecord(
    val recordIndex: Long,
    val sectionType: String,
    val accountName: String?,
    val supported: Boolean,
    val rawText: String,
    val date: String? = null,
    val amount: String? = null,
    val payee: String? = null,
    val memo: String? = null,
    val category: String? = null,
    val transferAccount: String? = null,
    val checkNumber: String? = null,
    val clearedStatus: String? = null,
    val splits: List<QifRecordSplit> = emptyList(),
    val transferId: TransferId? = null,
    val importStatus: ImportStatus? = null,
    val errorMessage: String? = null,
)

/**
 * A single split line group within a [QifImportRecord].
 */
@Serializable
data class QifRecordSplit(
    val category: String? = null,
    val transferAccount: String? = null,
    val memo: String? = null,
    val amount: String? = null,
)
