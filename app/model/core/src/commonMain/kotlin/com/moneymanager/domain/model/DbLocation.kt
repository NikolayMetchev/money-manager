package com.moneymanager.domain.model

import com.moneymanager.files.appDataDirectory
import kotlinx.io.files.Path

/**
 * Default database filename used across all platforms.
 */
const val DEFAULT_DATABASE_NAME = "money_manager.db"

/**
 * Where a database lives: a file path on desktop and native, a database name on Android (resolved against
 * the app's databases directory). [toString] is the persisted form, so `DbLocation(it.toString())`
 * restores the last-opened database from local settings.
 */
data class DbLocation(
    val path: String,
) {
    override fun toString() = path
}

/** `~/.moneymanager/money_manager.db`, the default database on platforms with a home directory. */
fun defaultFileDatabaseLocation(): DbLocation = DbLocation(Path(appDataDirectory(), DEFAULT_DATABASE_NAME).toString())
