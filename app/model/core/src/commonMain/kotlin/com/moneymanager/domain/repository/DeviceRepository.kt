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
    suspend fun getOrCreateDevice(deviceInfo: DeviceInfo): Long

    /**
     * Gets device info by ID.
     *
     * @param id The device ID
     * @return The device info or null if not found
     */
    suspend fun getDeviceById(id: Long): DeviceInfo?

    /**
     * The current device's ID. Set when the app initializes by calling
     * getOrCreateDevice with the current device info.
     */
    val currentDeviceId: Long?

    /**
     * Initializes and stores the current device ID.
     * Should be called once at app startup.
     *
     * @param deviceInfo The current device's info
     */
    suspend fun initCurrentDevice(deviceInfo: DeviceInfo)
}
