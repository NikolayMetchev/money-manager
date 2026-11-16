package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransactionKind
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.repository.ObservationReadRepository

interface ObservationWriteRepository : ObservationReadRepository {
    /** A new, empty transaction of [kind] for observations to land on; the materializer writes its row. */
    suspend fun allocateTransactionId(kind: TransactionKind): TransactionId

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

    /**
     * Writes the materializer's [writes] in one database transaction: inserts a row under its existing `transaction_id`,
     * updates one as a new revision (its attributes too, in that same revision), or deletes one. Each insert and update
     * records its source against the revision it made.
     */
    suspend fun applyMaterialization(writes: List<MaterializedWrite>)
}
