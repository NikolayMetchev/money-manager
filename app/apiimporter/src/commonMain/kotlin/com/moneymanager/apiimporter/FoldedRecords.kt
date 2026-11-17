package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine

/** The transfers and trades a session's import booked that its match sync then folded into another record's transaction. */
internal data class FoldedRecords(
    val transfers: Int,
    val trades: Int,
) {
    val total: Int get() = transfers + trades
}

/**
 * Re-matches every stored observation once [sessionId]'s items are booked (#1429): an item is an observation like a staged
 * file's row, so one that another source or an overlapping session already records joins that transaction rather than
 * standing beside it as a reconciled twin.
 */
internal suspend fun ImportEngine.syncSessionMatches(
    apiSessionRepository: ApiSessionReadRepository,
    sessionId: ApiSessionId,
): FoldedRecords {
    val transfers = apiSessionRepository.getTransferIdsCreatedBySession(sessionId)
    val trades = apiSessionRepository.getTradeIdsCreatedBySession(sessionId)
    val gone = import(ImportBatch(syncMatches = true)).matchSync.deleted
    return FoldedRecords(
        transfers = transfers.count { it in gone },
        trades = trades.count { it in gone },
    )
}
