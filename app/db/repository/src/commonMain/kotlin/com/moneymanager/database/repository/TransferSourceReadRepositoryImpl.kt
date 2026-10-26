package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferSourceReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Read-only implementation of TransferSourceReadRepository using SQLDelight.
 * Transfers are stored in the unified entity_source store as entity_type_id = 7 (TRANSFER),
 * keyed by the transfer id, so this is [EntitySourceReader] pinned to [EntityType.TRANSFER].
 */
class TransferSourceReadRepositoryImpl(
    database: MoneyManagerDatabase,
) : TransferSourceReadRepository {
    private val sources = EntitySourceReader(database)
    private val queries = database.entitySourceSelectQueries

    override suspend fun getSourcesForTransaction(transactionId: TransferId): List<SourceRecord> =
        withContext(Dispatchers.Default) {
            sources.sourcesFor(EntityType.TRANSFER, transactionId.id)
        }

    override suspend fun getSourceByRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): SourceRecord? =
        withContext(Dispatchers.Default) {
            sources
                .sourcesFor(EntityType.TRANSFER, transactionId.id)
                .firstOrNull { it.revisionId == revisionId }
        }

    override suspend fun getTransfersCreatedBy(importSource: Source): Map<TransferId, Source> =
        withContext(Dispatchers.Default) {
            when (importSource) {
                is Source.Csv -> {
                    queries
                        .selectCreatedTransferRowsByCsvImport(importSource.importId.id.toString())
                        .awaitAsList()
                        .associate { TransferId(it.entity_id) to Source.Csv(importSource.importId, it.csv_row_index) }
                }

                is Source.Qif -> {
                    queries
                        .selectCreatedTransferRecordsByQifImport(importSource.importId.id.toString())
                        .awaitAsList()
                        .associate { TransferId(it.entity_id) to Source.Qif(importSource.importId, it.qif_record_index) }
                }

                is Source.Api -> {
                    queries
                        .selectCreatedTransferNodesByApiSession(importSource.sessionId.id)
                        .awaitAsList()
                        .associate {
                            TransferId(it.entity_id) to
                                Source.Api(importSource.sessionId, ApiRequestId(it.api_request_id), JsonPath(it.json_path))
                        }
                }

                else -> {
                    emptyMap()
                }
            }
        }

    override suspend fun getImportRecordsMatchedTo(
        transferIds: Set<TransferId>,
        tradeIds: Set<TradeId>,
    ): List<Source> =
        withContext(Dispatchers.Default) {
            (transferIds.map { it.id } + tradeIds.map { it.id })
                .distinct()
                .chunked(ID_CHUNK)
                .flatMap { queries.selectImportRecordsMatchedTo(it).awaitAsList() }
                .map {
                    when (it.source_type_id) {
                        SourceType.CSV_IMPORT.id.toLong() -> Source.Csv(CsvImportId(Uuid.parse(it.import_id)), it.record_index)
                        else -> Source.Qif(QifImportId(Uuid.parse(it.import_id)), it.record_index)
                    }
                }.distinct()
        }

    override suspend fun getHandEditedTransferIds(ids: Collection<TransferId>): Set<TransferId> =
        withContext(Dispatchers.Default) {
            val edited = mutableSetOf<TransferId>()
            for (chunk in ids.mapTo(mutableSetOf()) { it.id }.chunked(ID_CHUNK)) {
                queries.selectHandEditedTransferIds(chunk).awaitAsList().mapTo(edited) { TransferId(it) }
            }
            edited
        }
}

// Keeps each IN list well under SQLite's bound-parameter limit.
private const val ID_CHUNK = 500
