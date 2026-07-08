@file:OptIn(ExperimentalTime::class)

package com.moneymanager.domain.model.qif

import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class QifImportId
    @OptIn(ExperimentalUuidApi::class)
    constructor(
        val id: Uuid,
    ) {
        @OptIn(ExperimentalUuidApi::class)
        override fun toString() = id.toString()
    }

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
