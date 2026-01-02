package com.moneymanager.test.database

import com.moneymanager.di.AppComponentParams
import com.moneymanager.domain.model.DbLocation
import java.nio.file.Files
import java.nio.file.Paths

actual fun createTestDatabaseLocation(): DbLocation {
    val tempDir = Files.createTempDirectory("moneymanager-test")
    val dbPath = Paths.get(tempDir.toString(), "test-${System.currentTimeMillis()}.db")
    return DbLocation(dbPath)
}

actual fun deleteTestDatabase(location: DbLocation) {
    try {
        Files.deleteIfExists(location.path)
        Files.deleteIfExists(location.path.parent)
    } catch (e: Exception) {
        // Ignore cleanup errors
    }
}

actual fun createTestAppComponentParams(): AppComponentParams = AppComponentParams()
