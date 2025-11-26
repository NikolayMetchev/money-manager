package com.moneymanager.di.database

import com.moneymanager.domain.di.AppComponentParams
import com.moneymanager.domain.di.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import java.nio.file.Path
import java.nio.file.Paths

@ContributesTo(AppScope::class)
interface DatabasePathModule {
    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabasePath(params: AppComponentParams): Path {
        val pathString =
            params.databasePath ?: run {
                val userHome = System.getProperty("user.home")
                "$userHome/.moneymanager/default.db"
            }
        return Paths.get(pathString)
    }
}
