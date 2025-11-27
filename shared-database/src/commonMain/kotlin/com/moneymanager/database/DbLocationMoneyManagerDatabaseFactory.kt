package com.moneymanager.database

class DbLocationMoneyManagerDatabaseFactory(
    private val dbLocationFactory: DbLocationFactory,
    private val sqlDriverFactor: SqlDriverFactory,
) :
    MoneyManagerDatabaseFactory {
    override fun createMoneyManager(listener: DefaultLocationMissingListener): MoneyManagerDatabase =
        MoneyManagerDatabase(sqlDriverFactor.createSqlDriver(dbLocationFactory.createDbLocation(listener)))
}
