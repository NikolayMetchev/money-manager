package com.moneymanager.domain.repository.write

import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.repository.DeviceReadRepository

/**
 * Repository for managing device records.
 * Handles deduplication of devices to ensure the same physical device
 * doesn't create multiple database records.
 */
interface DeviceWriteRepository : DeviceReadRepository {
    /**
     * Gets or creates a device record for the given device info.
     * If a matching device already exists, returns its ID.
     * Otherwise, creates a new device and returns the new ID.
     *
     * @param deviceInfo The device information
     * @return The device ID (existing or newly created)
     */
    fun getOrCreateDevice(deviceInfo: DeviceInfo): DeviceId
}
