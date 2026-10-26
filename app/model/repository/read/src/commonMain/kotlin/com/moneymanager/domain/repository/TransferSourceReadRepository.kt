package com.moneymanager.domain.repository

import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId

/**
 * Read-only access to transfer source records.
 * Tracks the provenance of each transfer modification.
 */
interface TransferSourceReadRepository {
    /**
     * Gets all sources for a specific transaction (across all revisions).
     *
     * @param transactionId The transaction ID
     * @return List of sources ordered by revision descending
     */
    suspend fun getSourcesForTransaction(transactionId: TransferId): List<SourceRecord>

    /**
     * Gets the source for a specific revision of a transaction.
     *
     * @param transactionId The transaction ID
     * @param revisionId The revision ID
     * @return The source or null if not found
     */
    suspend fun getSourceByRevision(
        transactionId: TransferId,
        revisionId: Long,
    ): SourceRecord?

    /**
     * The still-existing transfers [importSource] created (not merely updated), each with the record it
     * came from: [Source.Csv] with its row, [Source.Qif] with its record, [Source.Api] with its request
     * and JSON path. [importSource] names a whole import or session; any other source created none.
     */
    suspend fun getTransfersCreatedBy(importSource: Source): Map<TransferId, Source>

    /**
     * The CSV rows ([Source.Csv]) and QIF records ([Source.Qif]) currently linked to any of [transferIds] or
     * [tradeIds] without having created it: those that matched it as a duplicate or updated it. Read from
     * their provenance, so no import's staged rows are read.
     */
    suspend fun getImportRecordsMatchedTo(
        transferIds: Set<TransferId>,
        tradeIds: Set<TradeId>,
    ): List<Source>

    /** Those of [ids] a person has edited by hand since they were created. */
    suspend fun getHandEditedTransferIds(ids: Collection<TransferId>): Set<TransferId>
}
