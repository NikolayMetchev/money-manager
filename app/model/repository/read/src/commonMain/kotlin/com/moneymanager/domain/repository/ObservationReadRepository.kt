package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner

/** Read side of observations: what each owner says about each of its slots, and which transaction it is on. */
interface ObservationReadRepository {
    suspend fun getObservation(id: ObservationId): Observation?

    /** Every observation [owner] owns, by slot. */
    suspend fun getObservationsOf(owner: ObservationOwner): List<Observation>

    /** Every observation assigned to [transactionId], by id. */
    suspend fun getObservationsOfTransaction(transactionId: TransactionId): List<Observation>
}
