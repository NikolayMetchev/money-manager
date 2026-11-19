package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Transfer

/** A staged CSV whose rows report at most one independent transfer using existing accounts and assets. */
data class CsvTransferSync(
    val id: CsvImportId,
    val fileChecksum: String,
    val rows: Map<Long, CsvTransferSyncRow>,
    val application: CsvImportMutation.RecordApplication,
    val records: CsvImportMutation.RecordRows,
    val version: ImportPlanVersion,
    val dedupePolicy: DedupePolicy,
)

data class CsvTransferSyncRow(
    val transfer: Transfer,
    val statementAccount: AccountId?,
)

data class ImportPlanVersion(
    val engine: Long,
    val database: Long,
)

/** A preview bound to the engine and database state that produced it. */
interface PreparedImport {
    val preview: ImportPlan
    val files: Int
    val changedFiles: Int
}

class StaleImportPlanException : IllegalStateException("The database changed after this preview was prepared. Preview the import again.")
