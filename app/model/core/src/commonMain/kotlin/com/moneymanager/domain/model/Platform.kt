package com.moneymanager.domain.model

/**
 * Represents the platform/app that initiated a manual source action.
 */
enum class Platform {
    /** Desktop JVM application (Windows, macOS, Linux) */
    JVM,

    /** Android mobile application */
    ANDROID,
}
