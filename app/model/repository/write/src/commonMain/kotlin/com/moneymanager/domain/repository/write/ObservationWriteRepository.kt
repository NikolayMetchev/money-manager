package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.ObservationChange
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.repository.ObservationReadRepository

interface ObservationWriteRepository : ObservationReadRepository {
    /** Creates each new record and its observations in one transaction. */
    suspend fun insertRecordObservations(
        contentHash: String,
        observations: List<RecordObservationInsert>,
    ): Map<ImportRecordKey, ImportRecordId>

    /** Commits observation and effective row changes together, clearing reclaimed reservations and resolving provisional ids. */
    suspend fun applyObservationChanges(
        newTransactions: Collection<TransactionId>,
        observations: List<ObservationChange>,
        rows: List<MaterializedWrite>,
        reclaimed: Collection<ObservationId> = emptyList(),
    ): Map<TransactionId, TransactionId>

    /** Records what [owner] says about [slot], on [transactionId]; an owner's slot already recorded fails. */
    suspend fun insertObservation(
        owner: ObservationOwner,
        slot: String,
        transactionId: TransactionId,
        content: ObservationContent,
    ): ObservationId

    /** Replaces what an observation says. Its kind can't change: a transfer stays a transfer. */
    suspend fun updateContent(
        id: ObservationId,
        content: ObservationContent,
    )

    /** Assigns an observation to another transaction (of its kind). */
    suspend fun assignTransaction(
        id: ObservationId,
        transactionId: TransactionId,
    )

    suspend fun deleteObservation(id: ObservationId)

    /** Deletes every observation on [transactionIds], whoever owns it: the transactions are being deleted. */
    suspend fun deleteObservationsOfTransactions(transactionIds: Collection<TransactionId>)

    /**
     * Reserves every observation [sessionId]'s records (and their groups) own, for its re-run (#1429): its transactions keep
     * their ids while it runs, as a staged file's do (`CsvImportWriteRepository.reserveForRerun`).
     */
    suspend fun reserveApiSession(sessionId: ApiSessionId)

    /** Ends [id]'s reservation: its record said it again. */
    suspend fun unreserve(id: ObservationId)

    /**
     * Releases transactions a re-run reserved but nothing took back (#1430): deletes every observation on [transactionIds]
     * and takes them out of every relationship they were kept in.
     */
    suspend fun releaseTransactions(transactionIds: Collection<TransactionId>)

    /**
     * Moves every observation end on [deletedAccountId] to [survivingAccountId] and records which, under [mergeId],
     * so [restoreAccountReferences] can move exactly those back.
     */
    suspend fun moveAccountReferences(
        mergeId: MergeId,
        deletedAccountId: AccountId,
        survivingAccountId: AccountId,
    )

    /** Moves the ends [moveAccountReferences] recorded under [mergeId] to [restoredAccountId], and forgets them. */
    suspend fun restoreAccountReferences(
        mergeId: MergeId,
        restoredAccountId: AccountId,
    )
}

data class RecordObservationInsert(
    val record: ImportRecordKey,
    val slot: String,
    val transactionId: TransactionId,
    val content: ObservationContent,
)
