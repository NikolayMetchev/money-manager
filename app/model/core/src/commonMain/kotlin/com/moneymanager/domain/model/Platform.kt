package com.moneymanager.domain.model

/**
 * Represents the platform/app that initiated a manual source action.
 */
enum class Platform {
    /** Desktop JVM application (Windows, macOS, Linux) */
    JVM,

    /** Android mobile application */
    ANDROID,
    ;

    companion object {
        fun fromName(name: String): Platform = valueOf(name.uppercase())

        fun fromNameOrNull(name: String): Platform? = entries.find { it.name.equals(name, ignoreCase = true) }
    }
}
