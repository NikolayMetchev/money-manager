package com.moneymanager.database

class InMemoryMoneyManagerDatabaseFactory(private val sqlDriverFactory: SqlDriverFactory) :
    MoneyManagerDatabaseFactory {
    override fun createMoneyManager(listener: DefaultLocationMissingListener) =
        MoneyManagerDatabase(sqlDriverFactory.createInMemorySqlDriver())
}
