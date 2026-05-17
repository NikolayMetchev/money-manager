package com.moneymanager.di.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.JvmDatabaseManager
import com.moneymanager.di.AppComponentParams

@Suppress("ktlint:standard:function-naming")
actual fun createDatabaseManager(@Suppress("unused") params: AppComponentParams): DatabaseManager = JvmDatabaseManager()
