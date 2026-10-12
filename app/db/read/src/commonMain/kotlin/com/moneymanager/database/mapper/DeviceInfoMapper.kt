package com.moneymanager.database.mapper

import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Platform

/**
 * Builds a [DeviceInfo] from a `device_view` row's columns. Null for a platform with no device details (the
 * SYSTEM device) or one this build doesn't know, so audit rows from a newer client still render.
 */
fun deviceInfoOrNull(
    platformName: String?,
    osName: String?,
    machineName: String?,
    deviceMake: String?,
    deviceModel: String?,
): DeviceInfo? =
    when (Platform.entries.firstOrNull { it.name.equals(platformName, ignoreCase = true) }) {
        Platform.JVM -> DeviceInfo.Jvm(osName = osName ?: UNKNOWN, machineName = machineName ?: UNKNOWN)
        Platform.NATIVE -> DeviceInfo.Native(osName = osName ?: UNKNOWN, machineName = machineName ?: UNKNOWN)
        Platform.ANDROID -> DeviceInfo.Android(deviceMake = deviceMake ?: UNKNOWN, deviceModel = deviceModel ?: UNKNOWN)
        null -> null
    }

private const val UNKNOWN = "Unknown"
