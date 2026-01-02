package com.moneymanager.test.database

import com.moneymanager.di.AppComponentParams
import com.moneymanager.domain.model.DbLocation

expect fun createTestDatabaseLocation(): DbLocation

expect fun deleteTestDatabase(location: DbLocation)

/**
 * Creates AppComponentParams for testing.
 * On JVM, returns an empty params.
 * On Android, uses ApplicationProvider to get test context.
 */
expect fun createTestAppComponentParams(): AppComponentParams
