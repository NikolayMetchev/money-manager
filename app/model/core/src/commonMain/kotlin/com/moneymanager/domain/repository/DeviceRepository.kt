package com.moneymanager.domain.repository

import com.moneymanager.domain.model.DeviceInfo

/**
 * Repository for managing device records.
 * Handles deduplication of devices to ensure the same physical device
 * doesn't create multiple database records.
 */
interface DeviceRepository {
    /**
     * Gets or creates a device record for the given device info.
     * If a matching device already exists, returns its ID.
     * Otherwise, creates a new device and returns the new ID.
     *
     * @param deviceInfo The device information
     * @return The device ID (existing or newly created)
     */
    fun getOrCreateDevice(deviceInfo: DeviceInfo): Long

    /**
     * Gets device info by ID.
     *
     * @param id The device ID
     * @return The device info or null if not found
     */
    suspend fun getDeviceById(id: Long): DeviceInfo?
}
