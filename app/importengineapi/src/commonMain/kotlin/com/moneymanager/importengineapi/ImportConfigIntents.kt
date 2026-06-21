@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.model.qif.QifImportRecord
import kotlin.time.Instant

/*
 * Intents for the import-configuration and staging tables (strategies, account mappings, CSV/QIF
 * staging rows, settings, device). The engine applies these so callers never hold the corresponding
 * write repository. Create variants carry a String `key` whose generated id is read back from the
 * matching map on ImportResult.
 */

/** A write on the CSV import-strategy table. */
sealed interface CsvStrategyMutation {
    data class Create(
        val key: String,
        val strategy: CsvImportStrategy,
        val source: Source,
    ) : CsvStrategyMutation

    data class Update(
        val strategy: CsvImportStrategy,
        val source: Source,
    ) : CsvStrategyMutation

    data class Delete(
        val id: CsvImportStrategyId,
    ) : CsvStrategyMutation
}

/** A write on the API import-strategy table. */
sealed interface ApiStrategyMutation {
    data class Create(
        val key: String,
        val strategy: ApiImportStrategy,
        val source: Source,
    ) : ApiStrategyMutation

    data class Update(
        val strategy: ApiImportStrategy,
        val source: Source,
    ) : ApiStrategyMutation

    data class Delete(
        val id: ApiImportStrategyId,
    ) : ApiStrategyMutation
}

/** A write on the CSV account-mapping table. */
sealed interface CsvMappingMutation {
    data class Create(
        val key: String,
        val strategyId: CsvImportStrategyId,
        val columnName: String,
        val valuePattern: Regex,
        val accountId: AccountId,
    ) : CsvMappingMutation

    data class CreateBatch(
        val mappings: List<CsvAccountMapping>,
    ) : CsvMappingMutation

    data class Update(
        val mapping: CsvAccountMapping,
    ) : CsvMappingMutation

    data class Delete(
        val id: Long,
    ) : CsvMappingMutation

    data class DeleteForStrategy(
        val strategyId: CsvImportStrategyId,
    ) : CsvMappingMutation
}

/** A write on the CSV staging tables (the stored file + per-row status write-back). */
sealed interface CsvImportMutation {
    data class Create(
        val key: String,
        val fileName: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val fileChecksum: String,
        val fileLastModified: Instant,
    ) : CsvImportMutation

    data class Delete(
        val id: CsvImportId,
    ) : CsvImportMutation

    data class UpdateRowTransferId(
        val id: CsvImportId,
        val rowIndex: Long,
        val transferId: TransferId,
    ) : CsvImportMutation

    data class UpdateRowTransferIds(
        val id: CsvImportId,
        val rowTransferMap: Map<Long, TransferId>,
    ) : CsvImportMutation

    data class UpdateRowStatus(
        val id: CsvImportId,
        val rowIndex: Long,
        val status: String,
        val transferId: TransferId? = null,
    ) : CsvImportMutation

    data class UpdateRowStatuses(
        val id: CsvImportId,
        val status: String,
        val rowTransferMap: Map<Long, TransferId?>,
    ) : CsvImportMutation

    data class SaveError(
        val id: CsvImportId,
        val rowIndex: Long,
        val errorMessage: String,
    ) : CsvImportMutation

    data class ClearError(
        val id: CsvImportId,
        val rowIndex: Long,
    ) : CsvImportMutation

    data class ClearErrors(
        val id: CsvImportId,
        val rowIndexes: Collection<Long>,
    ) : CsvImportMutation

    data class RecordApplication(
        val id: CsvImportId,
        val strategyId: CsvImportStrategyId,
        val strategyName: String,
        val appliedAt: Instant,
    ) : CsvImportMutation
}

/** A write on the QIF staging tables (the stored file + per-record status write-back). */
sealed interface QifImportMutation {
    data class Create(
        val key: String,
        val fileName: String,
        val records: List<QifImportRecord>,
        val accountType: String,
        val fileChecksum: String,
        val fileLastModified: Instant,
    ) : QifImportMutation

    data class Delete(
        val id: QifImportId,
    ) : QifImportMutation

    data class UpdateRecordStatuses(
        val id: QifImportId,
        val status: String,
        val recordTransferMap: Map<Long, TransferId?>,
    ) : QifImportMutation

    data class SaveError(
        val id: QifImportId,
        val recordIndex: Long,
        val errorMessage: String,
    ) : QifImportMutation

    data class ClearErrors(
        val id: QifImportId,
        val recordIndexes: Collection<Long>,
    ) : QifImportMutation

    data class RecordApplication(
        val id: QifImportId,
        val strategyId: CsvImportStrategyId,
        val strategyName: String,
        val appliedAt: Instant,
    ) : QifImportMutation
}

/** Settings writes; non-null fields are applied. */
data class ImportSettings(
    val defaultCurrencyId: CurrencyId? = null,
    val lastQifAccountId: AccountId? = null,
)
