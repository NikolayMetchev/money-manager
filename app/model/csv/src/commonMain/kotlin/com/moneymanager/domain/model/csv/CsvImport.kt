package com.moneymanager.domain.model.csv

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.StagedImport
import kotlin.time.Instant

data class CsvImport(
    override val id: CsvImportId,
    val tableName: String,
    override val originalFileName: String,
    override val importTimestamp: Instant,
    val rowCount: Int,
    val columnCount: Int,
    val columns: List<CsvColumn>,
    val deviceInfo: DeviceInfo,
    val fileChecksum: String,
    val fileLastModified: Instant,
    override val applicationCount: Int = 0,
    /** Number of rows that failed to import (rows currently logged in csv_import_error). */
    override val errorCount: Int = 0,
    override val lastAppliedStrategyId: CsvImportStrategyId? = null,
    override val lastAppliedStrategyName: String? = null,
    override val lastAppliedAt: Instant? = null,
    /** The source account the last application ran with; null when its strategy read the account per row. */
    val lastAppliedAccountId: AccountId? = null,
    /** The currency the last application ran with; null when its strategy read the currency per row. */
    val lastAppliedCurrencyId: CurrencyId? = null,
    override val ignored: Boolean = false,
    /** When the file was last unimported (its transactions deleted and applications cleared), if ever. */
    val lastUnimportedAt: Instant? = null,
) : StagedImport<CsvImportId>
