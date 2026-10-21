package com.moneymanager.domain.model

/**
 * Device information captured when a manual source action is recorded.
 * Platform-specific implementations contain different metadata.
 */
sealed interface DeviceInfo {
    val platform: Platform

    /**
     * A host, identified by its OS and machine name. The desktop app, the native CLI and the browser app all
     * run on one, so they share how a device row is stored and only differ in [platform].
     *
     * @property osName Operating system name (e.g., "Windows 11", "macOS 14.0", "Linux")
     * @property machineName Hostname of the machine
     */
    sealed interface Host : DeviceInfo {
        val osName: String
        val machineName: String
    }

    /** Device info for the JVM desktop application. */
    data class Jvm(
        override val osName: String,
        override val machineName: String,
    ) : Host {
        override val platform: Platform = Platform.JVM
    }

    /** Device info for the Kotlin/Native command-line client. */
    data class Native(
        override val osName: String,
        override val machineName: String,
    ) : Host {
        override val platform: Platform = Platform.NATIVE
    }

    /**
     * Device info for the browser app. A page can't read the host name, so [machineName] is the browser
     * ("Chrome"), which keeps two browsers on one OS separate devices, as the desktop app and the CLI are.
     */
    data class Web(
        override val osName: String,
        override val machineName: String,
    ) : Host {
        override val platform: Platform = Platform.WEB
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
