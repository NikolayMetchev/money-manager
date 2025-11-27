package database

import com.moneymanager.database.AndroidSqlDriverFactory
import com.moneymanager.database.InMemoryMoneyManagerDatabaseFactory
import com.moneymanager.database.MoneyManagerDatabaseFactory
import com.moneymanager.di.TestScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(TestScope::class)
actual interface TestDatabaseModule {
    @Provides
    @SingleIn(TestScope::class)
    fun provideMoneyManagerDatabaseFactory(params: DbTestComponentParams): MoneyManagerDatabaseFactory {
        return InMemoryMoneyManagerDatabaseFactory(AndroidSqlDriverFactory(params.context))
    }
}
