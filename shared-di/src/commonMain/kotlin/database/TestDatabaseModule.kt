package database

import com.moneymanager.di.TestScope
import dev.zacsweers.metro.ContributesTo

@ContributesTo(TestScope::class)
expect interface TestDatabaseModule
