package com.moneymanager.di.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.AppComponentParams

/**
 * Creates a platform-specific DatabaseManager implementation.
 * This is an expect/actual function that returns the appropriate
 * DatabaseManager for each platform.
 */
@Suppress("ktlint:standard:function-naming")
expect fun createDatabaseManager(params: AppComponentParams): DatabaseManager
