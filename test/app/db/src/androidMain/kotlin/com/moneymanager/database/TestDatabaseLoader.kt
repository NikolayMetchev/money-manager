package com.moneymanager.database

import androidx.test.platform.app.InstrumentationRegistry

actual fun copyDatabaseFromResources(resourcePath: String): DbLocation {
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Load database from assets (in Android, test resources are in assets)
    val assetManager = context.assets
    val inputStream =
        try {
            // Try without leading slash
            assetManager.open(resourcePath.trimStart('/'))
        } catch (e: Exception) {
            // Try with leading slash removed differently
            assetManager.open(resourcePath.removePrefix("/"))
        }

    // Create unique database name
    val dbName = "test-${System.currentTimeMillis()}.db"

    // Clean up if exists
    context.deleteDatabase(dbName)

    // Copy to database directory
    val dbFile = context.getDatabasePath(dbName)
    dbFile.parentFile?.mkdirs()

    inputStream.use { input ->
        dbFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }

    return DbLocation(dbName)
}
