package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** The native CLI runs on a desktop OS, so it records the OS family and host name like the desktop app. */
@OptIn(ExperimentalNativeApi::class)
actual fun getDeviceInfo(): DeviceInfo =
    DeviceInfo.Native(
        osName = Platform.osFamily.name,
        machineName = runCatching { machineName() }.getOrNull() ?: "Unknown",
    )

/** The host name, or null when the OS won't report one. */
internal expect fun machineName(): String?
