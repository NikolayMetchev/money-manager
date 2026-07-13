@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.ApiSessionType
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.model.timeline.TimelineSourceKind
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

class ImportTimelineReadRepositoryImpl(
    database: MoneyManagerDatabase,
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : ImportTimelineReadRepository {
    private val queries = database.timelineSelectQueries

    override fun getCsvImportDateRanges(): Flow<List<ImportFileDateRange>> =
        queries
            .selectCsvImportDateRanges()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows ->
                rows.map { row ->
                    fileRange(
                        kind = TimelineSourceKind.CSV,
                        fileId = row.import_id,
                        fileName = row.file_name,
                        ignored = row.ignored,
                        strategyName = row.strategy_name,
                        earliestMs = row.earliest_ms,
                        latestMs = row.latest_ms,
                        transactionCount = row.transaction_count,
                    )
                }
            }

    override fun getQifImportDateRanges(): Flow<List<ImportFileDateRange>> =
        queries
            .selectQifImportDateRanges()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows ->
                rows.map { row ->
                    fileRange(
                        kind = TimelineSourceKind.QIF,
                        fileId = row.import_id,
                        fileName = row.file_name,
                        ignored = row.ignored,
                        strategyName = row.strategy_name,
                        earliestMs = row.earliest_ms,
                        latestMs = row.latest_ms,
                        transactionCount = row.transaction_count,
                    )
                }
            }

    override fun getApiSessionDateRanges(): Flow<List<ImportFileDateRange>> =
        queries
            .selectApiSessionDateRanges()
            .asFlow()
            .mapToList(coroutineContext)
            .map { rows ->
                rows.map { row ->
                    ImportFileDateRange(
                        kind = TimelineSourceKind.API,
                        fileId = row.session_id.toString(),
                        fileName = "Session ${row.session_id}",
                        strategyName = row.strategy_name,
                        apiSessionType = ApiSessionType.fromId(row.session_type_id),
                        earliest = Instant.fromEpochMilliseconds(requireNotNull(row.earliest_ms)),
                        latest = Instant.fromEpochMilliseconds(requireNotNull(row.latest_ms)),
                        transactionCount = row.transaction_count,
                    )
                }
            }

    override fun getManualDateRange(): Flow<ImportFileDateRange?> =
        queries
            .selectManualDateRange()
            .asFlow()
            .mapToOne(coroutineContext)
            .map { row ->
                val earliestMs = row.earliest_ms ?: return@map null
                val latestMs = row.latest_ms ?: return@map null
                ImportFileDateRange(
                    kind = TimelineSourceKind.MANUAL,
                    fileId = "",
                    fileName = "Manual entries",
                    strategyName = null,
                    earliest = Instant.fromEpochMilliseconds(earliestMs),
                    latest = Instant.fromEpochMilliseconds(latestMs),
                    transactionCount = requireNotNull(row.transaction_count),
                )
            }

    override fun getAllDateRanges(): Flow<List<ImportFileDateRange>> =
        combine(
            getCsvImportDateRanges(),
            getQifImportDateRanges(),
            getApiSessionDateRanges(),
            getManualDateRange(),
        ) { csv, qif, api, manual ->
            csv + qif + api + listOfNotNull(manual)
        }

    @Suppress("LongParameterList")
    private fun fileRange(
        kind: TimelineSourceKind,
        fileId: String,
        fileName: String,
        ignored: Long,
        strategyName: String?,
        earliestMs: Long?,
        latestMs: Long?,
        transactionCount: Long,
    ): ImportFileDateRange =
        ImportFileDateRange(
            kind = kind,
            fileId = fileId,
            fileName = fileName,
            strategyName = strategyName,
            ignored = ignored != 0L,
            earliest = Instant.fromEpochMilliseconds(requireNotNull(earliestMs)),
            latest = Instant.fromEpochMilliseconds(requireNotNull(latestMs)),
            transactionCount = transactionCount,
        )
}
