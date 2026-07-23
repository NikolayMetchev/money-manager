package com.moneymanager.domain.model.qif

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.QifImportId
import kotlin.time.Instant

/**
 * Metadata for an imported QIF file.
 *
 * @property recordCount Total number of parsed records (across all sections).
 * @property unsupportedCount Number of records that cannot be imported in v1 (investments, unknown sections).
 * @property accountType The dominant account/section type of the file (e.g. "BANK"), used for strategy matching.
 * @property errorCount Number of records that failed to import (records currently logged in qif_import_error).
 */
data class QifImport(
    val id: QifImportId,
    val originalFileName: String,
    val importTimestamp: Instant,
    val recordCount: Int,
    val unsupportedCount: Int,
    val accountType: String,
    val deviceInfo: DeviceInfo,
    val fileChecksum: String,
    val fileLastModified: Instant,
    val applicationCount: Int = 0,
    val errorCount: Int = 0,
    val lastAppliedStrategyId: CsvImportStrategyId? = null,
    val lastAppliedStrategyName: String? = null,
    val lastAppliedAt: Instant? = null,
    /** User dismissed this file: hidden from the actionable lists and skipped by "Import all". */
    val ignored: Boolean = false,
)
