package com.moneymanager.domain.repository

import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.domain.model.observation.TransferField

/** Read side of what a person decided: overrides of transaction values, and pins and splits of observations. */
interface PersonOverrideReadRepository {
    /** The transfer fields a person set, each as the `transfer` column stores it. */
    suspend fun getTransferOverrides(transferId: TransferId): Map<TransferField, String>

    /** A person's set and removed transfer attributes, by type and group. */
    suspend fun getTransferAttributeOverrides(transferId: TransferId): List<AttributeOverride>

    /** A person's set and removed trade attributes, by type. */
    suspend fun getTradeAttributeOverrides(tradeId: TradeId): List<AttributeOverride>

    /** The pins among [observationIds]. */
    suspend fun getPins(observationIds: Collection<ObservationId>): List<ObservationPin>

    /** The pins fixing an observation to any of [transactionIds]. */
    suspend fun getPinsOnTransactions(transactionIds: Collection<TransactionId>): List<ObservationPin>

    /** The splits naming any of [observationIds]. */
    suspend fun getSplits(observationIds: Collection<ObservationId>): Set<ObservationSplit>
}
