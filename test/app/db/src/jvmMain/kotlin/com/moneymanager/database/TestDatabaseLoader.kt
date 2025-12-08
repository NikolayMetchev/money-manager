package com.moneymanager.database

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

actual fun copyDatabaseFromResources(resourcePath: String): DbLocation {
    // Load database from resources - try multiple class loaders
    val resourceStream =
        Thread.currentThread().contextClassLoader?.getResourceAsStream(resourcePath.trimStart('/'))
            ?: object {}.javaClass.getResourceAsStream(resourcePath)
            ?: object {}.javaClass.classLoader?.getResourceAsStream(resourcePath.trimStart('/'))
            ?: throw IllegalStateException(
                "Test database not found at $resourcePath. " +
                    "Tried paths: ${resourcePath.trimStart('/')}, $resourcePath",
            )

    // Create temp directory and file
    val tempDir = Files.createTempDirectory("moneymanager-test")
    val dbPath = Paths.get(tempDir.toString(), "test-${System.currentTimeMillis()}.db")

    // Copy resource to temp location
    resourceStream.use { input ->
        Files.copy(input, dbPath, StandardCopyOption.REPLACE_EXISTING)
    }

    return DbLocation(dbPath)
}
