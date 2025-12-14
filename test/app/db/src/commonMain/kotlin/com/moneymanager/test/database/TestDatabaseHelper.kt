package com.moneymanager.test.database

import com.moneymanager.database.DbLocation
import com.moneymanager.di.AppComponentParams

expect fun createTestDatabaseLocation(): DbLocation

expect fun deleteTestDatabase(location: DbLocation)

/**
 * Creates AppComponentParams for testing.
 * On JVM, returns an empty params.
 * On Android, uses ApplicationProvider to get test context.
 */
expect fun createTestAppComponentParams(): AppComponentParams
