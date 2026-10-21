package com.moneymanager.test.database

import com.moneymanager.domain.model.DbLocation

expect fun createTestDatabaseLocation(): DbLocation

expect fun deleteTestDatabase(location: DbLocation)

/** Whether a test database is a file, with its sidecars beside it; the browser's live in its SQLite worker's store. */
expect val testDatabasesAreFiles: Boolean
