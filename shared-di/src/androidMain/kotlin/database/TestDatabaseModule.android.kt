package database

import android.content.Context
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
    fun provideMoneyManagerDatabaseFactory(context: Context): MoneyManagerDatabaseFactory {
        return InMemoryMoneyManagerDatabaseFactory(AndroidSqlDriverFactory(context))
    }
}
