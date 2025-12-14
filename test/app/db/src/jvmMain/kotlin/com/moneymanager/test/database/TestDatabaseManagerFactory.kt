package com.moneymanager.test.database

import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.JvmDatabaseManager

actual fun createTestDatabaseManager(): DatabaseManager = JvmDatabaseManager()
