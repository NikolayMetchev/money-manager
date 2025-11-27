package com.moneymanager.di.database

import com.moneymanager.di.TestScope
import dev.zacsweers.metro.ContributesTo

/**
 * JVM does not need Context, so this module is empty.
 */
@ContributesTo(TestScope::class)
actual interface TestContextModule
