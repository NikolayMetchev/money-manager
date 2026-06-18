package com.moneymanager.domain.model

/**
 * Default database filename used across all platforms.
 */
const val DEFAULT_DATABASE_NAME = "money_manager.db"

expect class DbLocation {
    fun exists(): Boolean
}

/**
 * Rebuilds a [DbLocation] from its [DbLocation.toString] form (an absolute path on JVM, a database
 * name on Android). Used to restore the last-opened database persisted in local settings.
 */
expect fun dbLocationFromString(value: String): DbLocation
