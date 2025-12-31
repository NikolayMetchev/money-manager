package com.moneymanager.test.database

import androidx.test.platform.app.InstrumentationRegistry
import com.moneymanager.database.DbLocation

actual fun copyDatabaseFromResources(resourcePath: String): DbLocation {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    // Load database from classpath resources (works with commonTest/resources)
    val normalizedPath = resourcePath.trimStart('/')
    val inputStream =
        Thread.currentThread().contextClassLoader?.getResourceAsStream(normalizedPath)
            ?: TestDatabaseLoader::class.java.getResourceAsStream(resourcePath)
            ?: TestDatabaseLoader::class.java.classLoader?.getResourceAsStream(normalizedPath)
            ?: error(
                "Test database not found at $resourcePath. " +
                    "Tried classpath paths: $normalizedPath, $resourcePath",
            )

    // Create unique database name
    val dbName = "test-${System.currentTimeMillis()}.db"

    // Clean up if exists
    targetContext.deleteDatabase(dbName)

    // Copy to database directory
    val dbFile = targetContext.getDatabasePath(dbName)
    dbFile.parentFile?.mkdirs()

    inputStream.use { input ->
        dbFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }

    return DbLocation(dbName)
}

// Helper class for accessing class loader
private object TestDatabaseLoader
