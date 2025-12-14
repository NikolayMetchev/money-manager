package com.moneymanager.test.database

import com.moneymanager.database.DbLocation
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.RepositorySet
import com.moneymanager.di.AppComponent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

abstract class DbTest {
    protected lateinit var database: MoneyManagerDatabaseWrapper
    protected lateinit var testDbLocation: DbLocation
    protected lateinit var repositories: RepositorySet

    @BeforeTest
    fun setup() =
        runTest {
            testDbLocation = createTestDatabaseLocation()
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager
            database = databaseManager.openDatabase(testDbLocation)
            repositories = RepositorySet(database)
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }
}
