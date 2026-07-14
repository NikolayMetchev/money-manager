@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository.write

import com.moneymanager.database.write.MoneyManagerDatabaseWrapper
import com.moneymanager.database.write.recordSource
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.domain.repository.write.DeviceWriteRepository
import com.moneymanager.domain.repository.write.TransferSourceWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of TransferSourceWriteRepository using SQLDelight.
 * Manages transfer source records for tracking provenance. Transfers are stored in the unified
 * entity_source store as entity_type_id = 7 (TRANSFER), keyed by the transfer id.
 */
class TransferSourceWriteRepositoryImpl(
    private val database: MoneyManagerDatabaseWrapper,
    private val deviceRepository: DeviceWriteRepository,
    reader: TransferSourceReadRepository,
) : TransferSourceWriteRepository,
    TransferSourceReadRepository by reader {
    override suspend fun recordManualSource(
        transactionId: TransferId,
        revisionId: Long,
        deviceInfo: DeviceInfo,
    ): Unit =
        withContext(Dispatchers.Default) {
            val deviceId = deviceRepository.getOrCreateDevice(deviceInfo)

            database.recordSource(
                deviceId = deviceId,
                entityType = EntityType.TRANSFER,
                entityId = transactionId.id,
                revisionId = revisionId,
                source = Source.Manual,
            )
        }
}
