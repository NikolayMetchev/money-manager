package com.moneymanager.domain.model

/**
 * Default database filename used across all platforms.
 */
const val DEFAULT_DATABASE_NAME = "money_manager.db"

expect class DbLocation {
    fun exists(): Boolean
}
