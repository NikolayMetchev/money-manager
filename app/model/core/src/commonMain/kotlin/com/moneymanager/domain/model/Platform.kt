package com.moneymanager.domain.model

/**
 * Represents the platform/app that initiated a manual source action. [id] is the row id in the `platform`
 * lookup table, and [name] its `name`.
 */
enum class Platform(
    val id: Long,
) {
    /** Desktop JVM application (Windows, macOS, Linux) */
    JVM(1),

    /** Android mobile application */
    ANDROID(2),

    /** Kotlin/Native command-line client (Windows, macOS, Linux) */
    NATIVE(3),
}
