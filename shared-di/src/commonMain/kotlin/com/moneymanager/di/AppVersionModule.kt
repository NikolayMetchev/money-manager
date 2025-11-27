package com.moneymanager.di

import com.moneymanager.domain.model.AppVersion
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
interface AppVersionModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideAppVersion(): AppVersion {
        return readAppVersion()
    }
}
