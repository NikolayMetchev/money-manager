package com.moneymanager.database.repository

import com.moneymanager.database.mapper.deviceInfoOrNull
import com.moneymanager.domain.model.DeviceInfo

/**
 * Builds a [DeviceInfo] from the stored device columns (platform/os/machine/make/model), failing on a
 * platform without device details. Shared by the QIF/CSV import read repositories.
 */
fun createDeviceInfo(
    platformName: String,
    osName: String?,
    machineName: String?,
    deviceMake: String?,
    deviceModel: String?,
): DeviceInfo =
    requireNotNull(deviceInfoOrNull(platformName, osName, machineName, deviceMake, deviceModel)) {
        "Unknown platform: $platformName"
    }
