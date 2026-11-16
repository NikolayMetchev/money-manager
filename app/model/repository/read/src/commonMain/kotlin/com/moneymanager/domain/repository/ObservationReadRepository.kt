package com.moneymanager.domain.repository

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner

/** Read side of observations: what each owner says about each of its slots, and which transaction it is on. */
interface ObservationReadRepository {
    suspend fun getObservation(id: ObservationId): Observation?

    /** Every observation [owner] owns, by slot. */
    suspend fun getObservationsOf(owner: ObservationOwner): List<Observation>

    /**
     * The provenance row that created [transactionId] (its first revision's writer): a hand-made transaction's
     * observations are owned by it (`ObservationOwner.Manual`).
     */
    suspend fun getCreationSourceId(transactionId: TransactionId): Long?

    /** Every observation assigned to [transactionId], by id. */
    suspend fun getObservationsOfTransaction(transactionId: TransactionId): List<Observation>

    /** Every observation naming one of [accountIds] (either end of a transfer, either side of a trade), by id. */
    suspend fun getObservationsOnAccounts(accountIds: Collection<AccountId>): List<Observation>

    /** The `transfer` or `trade` row [transactionId] has, with its attributes, or null when it has none. */
    suspend fun getMaterialized(transactionId: TransactionId): MaterializedTransaction?

    /** The accounts among [accountIds] that some observation names. */
    suspend fun getAccountsWithObservations(accountIds: Collection<AccountId>): Set<AccountId>
}
