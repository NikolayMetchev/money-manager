package com.moneymanager.test.database

import com.moneymanager.domain.model.DbLocation
import java.nio.file.Files
import java.nio.file.Paths

actual fun createTestDatabaseLocation(): DbLocation {
    val tempDir = Files.createTempDirectory("moneymanager-test")
    val dbPath = Paths.get(tempDir.toString(), "test-${System.currentTimeMillis()}.db")
    return DbLocation(dbPath.toString())
}

actual fun deleteTestDatabase(location: DbLocation) {
    try {
        val file = Paths.get(location.path)
        Files.deleteIfExists(file)
        Files.deleteIfExists(file.parent)
    } catch (e: Exception) {
        // Ignore cleanup errors
    }
}

actual val testDatabasesAreFiles: Boolean = true
