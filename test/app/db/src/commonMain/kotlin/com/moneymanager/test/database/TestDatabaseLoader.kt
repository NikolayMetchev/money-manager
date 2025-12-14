package com.moneymanager.test.database

import com.moneymanager.database.DbLocation

/**
 * Copies a database file from test resources to a test location.
 * This is used to load pre-existing databases with specific schema states.
 *
 * @param resourcePath Path to the resource file (e.g., "/money_manager.db")
 * @return DbLocation pointing to the copied database file
 */
expect fun copyDatabaseFromResources(resourcePath: String): DbLocation
