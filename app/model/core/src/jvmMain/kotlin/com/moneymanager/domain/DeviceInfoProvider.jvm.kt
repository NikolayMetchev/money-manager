package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo
import java.net.InetAddress

/**
 * JVM implementation of DeviceInfoProvider.
 * Captures OS name and machine hostname.
 */
actual fun getDeviceInfo(): DeviceInfo =
    DeviceInfo.Jvm(
        osName = System.getProperty("os.name") ?: "Unknown",
        machineName =
            runCatching {
                InetAddress.getLocalHost().hostName
            }.getOrDefault("Unknown"),
    )
