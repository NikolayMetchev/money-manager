package com.moneymanager.database

class DbLocationMoneyManagerDatabaseFactory(
    private val dbLocationFactory: DbLocationFactory,
    private val sqlDriverFactory: SqlDriverFactory,
) :
    MoneyManagerDatabaseFactory {
    override fun createMoneyManager(listener: DefaultLocationMissingListener): MoneyManagerDatabase =
        MoneyManagerDatabase(sqlDriverFactory.createSqlDriver(dbLocationFactory.createDbLocation(listener)))
}
