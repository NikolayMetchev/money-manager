package database

import com.moneymanager.database.DEFAULT_DATABASE_PATH
import com.moneymanager.database.DbLocationFactory
import com.moneymanager.database.DbLocationMoneyManagerDatabaseFactory
import com.moneymanager.database.JvmSqlDriverFactory
import com.moneymanager.database.MoneyManagerDatabaseFactory
import com.moneymanager.di.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
actual interface DatabaseDriverFactoryModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideMoneyManagerDatabaseFactory(): MoneyManagerDatabaseFactory =
        DbLocationMoneyManagerDatabaseFactory(
            DbLocationFactory(
                DEFAULT_DATABASE_PATH,
            ),
            JvmSqlDriverFactory,
        )
}
