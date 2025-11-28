package com.moneymanager.di.database

import com.moneymanager.database.DbRepositoryFactory
import com.moneymanager.database.RepositoryFactory
import com.moneymanager.di.TestScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Test DI module that provides the repository factory.
 * Contributes to TestScope only.
 */
@ContributesTo(TestScope::class)
interface TestRepositoryFactoryModule {
    /**
     * Provides the repository factory for tests.
     */
    @Provides
    @SingleIn(TestScope::class)
    fun provideRepositoryFactory(): RepositoryFactory {
        return DbRepositoryFactory()
    }
}
