package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * A native build runs on a desktop OS, so it records the same OS + machine shape as the JVM app. A
 * platform of its own would need a `platform` row that databases created by the desktop app don't have.
 */
@OptIn(ExperimentalNativeApi::class)
actual fun getDeviceInfo(): DeviceInfo =
    DeviceInfo.Jvm(
        osName = Platform.osFamily.name,
        machineName = runCatching { machineName() }.getOrNull() ?: "Unknown",
    )

/** The host name, or null when the OS won't report one. */
internal expect fun machineName(): String?
