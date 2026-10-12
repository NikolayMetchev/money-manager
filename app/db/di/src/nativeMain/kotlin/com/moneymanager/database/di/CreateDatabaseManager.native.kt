package com.moneymanager.database.di

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.NativeDatabaseManager
import com.moneymanager.di.params.AppComponentParams

@Suppress("ktlint:standard:function-naming")
actual fun createDatabaseManager(params: AppComponentParams): DatabaseManager = NativeDatabaseManager()
