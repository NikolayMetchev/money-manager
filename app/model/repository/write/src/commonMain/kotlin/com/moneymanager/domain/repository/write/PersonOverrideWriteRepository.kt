package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.domain.repository.PersonOverrideReadRepository

interface PersonOverrideWriteRepository : PersonOverrideReadRepository {
    /** Sets [field] to [value], as the `transfer` column stores it. */
    suspend fun setTransferOverride(
        transferId: TransferId,
        field: TransferField,
        value: String,
    )

    suspend fun clearTransferOverride(
        transferId: TransferId,
        field: TransferField,
    )

    /** Records a person's decision about one attribute, replacing any earlier one about it. */
    suspend fun setTransferAttributeOverride(
        transferId: TransferId,
        override: AttributeOverride,
    )

    suspend fun clearTransferAttributeOverride(
        transferId: TransferId,
        typeId: AttributeTypeId,
        groupKey: String,
    )

    /** Like [setTransferAttributeOverride]; a trade attribute's group key must be `""`. */
    suspend fun setTradeAttributeOverride(
        tradeId: TradeId,
        override: AttributeOverride,
    )

    suspend fun clearTradeAttributeOverride(
        tradeId: TradeId,
        typeId: AttributeTypeId,
    )

    /** Pins an observation, replacing its earlier pin. */
    suspend fun pin(pin: ObservationPin)

    suspend fun unpin(observationId: ObservationId)

    suspend fun split(split: ObservationSplit)

    suspend fun unsplit(split: ObservationSplit)
}
