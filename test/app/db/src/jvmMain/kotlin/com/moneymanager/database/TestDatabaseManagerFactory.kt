package com.moneymanager.database

actual fun createTestDatabaseManager(): DatabaseManager = JvmDatabaseManager()
