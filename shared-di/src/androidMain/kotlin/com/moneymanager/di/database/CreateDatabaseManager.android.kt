package com.moneymanager.di.database

import com.moneymanager.database.AndroidDatabaseManager
import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.AppComponentParams

@Suppress("ktlint:standard:function-naming")
actual fun createDatabaseManager(params: AppComponentParams): DatabaseManager = AndroidDatabaseManager(params.context)
