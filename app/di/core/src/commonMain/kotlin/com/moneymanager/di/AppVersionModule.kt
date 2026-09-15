package com.moneymanager.di

import com.moneymanager.di.scope.AppScope
import com.moneymanager.domain.model.AppVersion
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object AppVersionModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideAppVersion(): AppVersion = readAppVersion()
}
