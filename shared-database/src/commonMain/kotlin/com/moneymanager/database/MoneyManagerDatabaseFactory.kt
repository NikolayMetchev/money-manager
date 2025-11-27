package com.moneymanager.database

interface MoneyManagerDatabaseFactory {
    fun createMoneyManager(listener: DefaultLocationMissingListener): MoneyManagerDatabase
}
