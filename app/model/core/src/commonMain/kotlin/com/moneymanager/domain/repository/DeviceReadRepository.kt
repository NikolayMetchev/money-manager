package com.moneymanager.domain.repository

import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo

/**
 * Read-only access to device records.
 */
interface DeviceReadRepository {
    /**
     * Gets device info by ID.
     *
     * @param id The device ID
     * @return The device info or null if not found
     */
    suspend fun getDeviceById(id: DeviceId): DeviceInfo?
}
