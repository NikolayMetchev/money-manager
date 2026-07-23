package com.moneymanager.domain.model.csv

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceInfo
import kotlin.time.Instant

data class CsvImport(
    val id: CsvImportId,
    val tableName: String,
    val originalFileName: String,
    val importTimestamp: Instant,
    val rowCount: Int,
    val columnCount: Int,
    val columns: List<CsvColumn>,
    val deviceInfo: DeviceInfo,
    val fileChecksum: String,
    val fileLastModified: Instant,
    val applicationCount: Int = 0,
    /** Number of rows that failed to import (rows currently logged in csv_import_error). */
    val errorCount: Int = 0,
    val lastAppliedStrategyId: CsvImportStrategyId? = null,
    val lastAppliedStrategyName: String? = null,
    val lastAppliedAt: Instant? = null,
    /** User dismissed this file: hidden from the actionable lists and skipped by "Import all". */
    val ignored: Boolean = false,
)
