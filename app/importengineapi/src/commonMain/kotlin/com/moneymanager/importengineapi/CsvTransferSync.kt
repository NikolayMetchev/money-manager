package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.observation.ImportGroupKey

/** Proposed bookings from a staged CSV, using existing accounts and assets. */
data class CsvTransferSync(
    val id: CsvImportId,
    val fileChecksum: String,
    val rows: Map<Long, CsvTransferSyncRow>,
    val application: CsvImportMutation.RecordApplication,
    val records: CsvImportMutation.RecordRows,
    val version: ImportPlanVersion,
    val dedupePolicy: DedupePolicy,
    val groupedTrades: List<CsvGroupedTradeSync> = emptyList(),
)

data class CsvTransferSyncRow(
    val transfer: Transfer,
    val statementAccount: AccountId?,
)

data class CsvGroupedTradeSync(
    val key: ImportGroupKey,
    val rowIndexes: Set<Long>,
    val trade: Trade,
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
