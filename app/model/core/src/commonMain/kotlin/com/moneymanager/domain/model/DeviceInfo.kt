package com.moneymanager.domain.model

/**
 * Device information captured when a manual source action is recorded.
 * Platform-specific implementations contain different metadata.
 */
sealed interface DeviceInfo {
    val platform: Platform

    /**
     * Device info for JVM desktop applications.
     *
     * @property osName Operating system name (e.g., "Windows 11", "macOS 14.0", "Linux")
     * @property machineName Hostname of the machine
     */
    data class Jvm(
        val osName: String,
        val machineName: String,
    ) : DeviceInfo {
        override val platform: Platform = Platform.JVM
    }

    /**
     * Device info for Android applications.
     *
     * @property deviceMake Device manufacturer (e.g., "Samsung", "Google")
     * @property deviceModel Device model name (e.g., "Pixel 8", "Galaxy S24")
     */
    data class Android(
        val deviceMake: String,
        val deviceModel: String,
    ) : DeviceInfo {
        override val platform: Platform = Platform.ANDROID
    }
}
