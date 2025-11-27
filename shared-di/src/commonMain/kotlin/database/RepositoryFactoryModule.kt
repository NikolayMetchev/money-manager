package database

import com.moneymanager.database.DbRepositoryFactory
import com.moneymanager.database.MoneyManagerDatabaseFactory
import com.moneymanager.database.RepositoryFactory
import com.moneymanager.di.AppScope
import com.moneymanager.di.TestScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@ContributesTo(TestScope::class)
interface RepositoryFactoryModule {
    @Provides
    fun provideRepositoryFactory(factory: MoneyManagerDatabaseFactory): RepositoryFactory {
        return DbRepositoryFactory(factory)
    }
}
