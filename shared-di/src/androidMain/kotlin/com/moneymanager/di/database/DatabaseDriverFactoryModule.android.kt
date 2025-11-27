package com.moneymanager.di.database

import com.moneymanager.database.AndroidSqlDriverFactory
import com.moneymanager.database.DEFAULT_DATABASE_NAME
import com.moneymanager.database.DbLocation
import com.moneymanager.database.DbLocationFactory
import com.moneymanager.database.DbLocationMoneyManagerDatabaseFactory
import com.moneymanager.database.MoneyManagerDatabaseFactory
import com.moneymanager.di.AppComponentParams
import com.moneymanager.di.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
actual interface DatabaseDriverFactoryModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideMoneyManagerDatabaseFactory(params: AppComponentParams): MoneyManagerDatabaseFactory {
        val defaultLocation = DbLocation(DEFAULT_DATABASE_NAME)
        return DbLocationMoneyManagerDatabaseFactory(
            DbLocationFactory(defaultLocation),
            AndroidSqlDriverFactory(params.context),
        )
    }
}
