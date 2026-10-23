package com.moneymanager.domain.model.qif

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.StagedImport
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
    override val id: QifImportId,
    override val originalFileName: String,
    override val importTimestamp: Instant,
    val recordCount: Int,
    val unsupportedCount: Int,
    val accountType: String,
    val deviceInfo: DeviceInfo,
    val fileChecksum: String,
    val fileLastModified: Instant,
    override val applicationCount: Int = 0,
    override val errorCount: Int = 0,
    override val lastAppliedStrategyId: CsvImportStrategyId? = null,
    override val lastAppliedStrategyName: String? = null,
    override val lastAppliedAt: Instant? = null,
    override val ignored: Boolean = false,
) : StagedImport<QifImportId>
