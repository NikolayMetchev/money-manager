package com.moneymanager.database.repository

import com.moneymanager.domain.model.DeviceInfo

/**
 * Builds a [DeviceInfo] from the stored device columns (platform/os/machine/make/model).
 *
 * Shared by the device write repository and the QIF/CSV import read repositories. Lives in the read
 * module (package `com.moneymanager.database.repository`) so both sides can call it unqualified.
 */
fun createDeviceInfo(
    platformName: String,
    osName: String?,
    machineName: String?,
    deviceMake: String?,
    deviceModel: String?,
): DeviceInfo =
    when (platformName) {
        "JVM" ->
            DeviceInfo.Jvm(
                osName = osName ?: "Unknown",
                machineName = machineName ?: "Unknown",
            )
        "ANDROID" ->
            DeviceInfo.Android(
                deviceMake = deviceMake ?: "Unknown",
                deviceModel = deviceModel ?: "Unknown",
            )
        else -> throw IllegalArgumentException("Unknown platform: $platformName")
    }
