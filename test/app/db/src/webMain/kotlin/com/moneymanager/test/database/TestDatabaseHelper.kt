package com.moneymanager.test.database

import com.moneymanager.database.sqliteRequest
import com.moneymanager.domain.model.DbLocation
import kotlin.random.Random

actual fun createTestDatabaseLocation(): DbLocation = DbLocation("/moneymanager-test-${Random.nextLong().toULong()}.db")

actual fun deleteTestDatabase(location: DbLocation) {
    runCatching { MemorySqliteChannel.post(sqliteRequest("delete", location.path)) }
}

actual val testDatabasesAreFiles: Boolean = false
