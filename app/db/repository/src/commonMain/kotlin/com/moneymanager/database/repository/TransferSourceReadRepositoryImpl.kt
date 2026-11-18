package com.moneymanager.database.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferSourceReadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    override suspend fun getTradesCreatedBy(importSource: Source): Map<TradeId, Source> =
        withContext(Dispatchers.Default) {
            val records =
                when (importSource) {
                    is Source.Csv -> {
                        queries
                            .selectCreatedTradeRowsByCsvImport(importSource.importId.id.toString())
                            .awaitAsList()
                            .map { TradeId(it.entity_id) to Source.Csv(importSource.importId, it.csv_row_index) }
                    }

                    is Source.Api -> {
                        queries
                            .selectCreatedTradeNodesByApiSession(importSource.sessionId.id)
                            .awaitAsList()
                            .map {
                                TradeId(it.entity_id) to
                                    Source.Api(importSource.sessionId, ApiRequestId(it.api_request_id), JsonPath(it.json_path))
                            }
                    }

                    else -> {
                        emptyList()
                    }
                }
            // The first record of each trade: the rows are in creation order.
            records.groupBy({ it.first }, { it.second }).mapValues { (_, sources) -> sources.first() }
        }
}
