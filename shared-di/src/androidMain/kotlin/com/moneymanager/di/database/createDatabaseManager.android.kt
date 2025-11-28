package com.moneymanager.di.database

import com.moneymanager.database.AndroidDatabaseManager
import com.moneymanager.database.DatabaseManager
import com.moneymanager.di.AppComponentParams

actual fun createDatabaseManager(params: AppComponentParams): DatabaseManager {
    return AndroidDatabaseManager(params.context)
}
