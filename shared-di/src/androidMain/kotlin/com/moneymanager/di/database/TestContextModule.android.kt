package com.moneymanager.di.database

import android.content.Context
import com.moneymanager.di.TestScope
import com.moneymanager.di.database.DbTestComponentParams
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(TestScope::class)
actual interface TestContextModule {
    @Provides
    @SingleIn(TestScope::class)
    fun provideContext(params: DbTestComponentParams): Context = params.context
}
