package com.moneymanager.data

import com.moneymanager.database.MoneyManagerDatabase

object Database {
    private var database: MoneyManagerDatabase? = null

    fun initialize(driverFactory: DatabaseDriverFactory) {
        if (database == null) {
            database = MoneyManagerDatabase(driverFactory.createDriver())
        }
    }

    fun getInstance(): MoneyManagerDatabase {
        return database ?: throw IllegalStateException("Database not initialized. Call Database.initialize() first.")
    }
}
