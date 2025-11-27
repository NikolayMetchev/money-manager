package com.moneymanager.di.database

import com.moneymanager.database.RepositoryFactory
import com.moneymanager.di.TestScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph(TestScope::class)
interface DbTestComponent {
    val repositoryFactory: RepositoryFactory

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides params: DbTestComponentParams,
        ): DbTestComponent
    }
}
