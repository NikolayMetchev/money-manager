package com.moneymanager

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Handles database file configuration and path management
 */
object DatabaseConfig {
    /**
     * Gets the default database directory path (~/.dbmanager)
     */
    fun getDefaultDatabaseDirectory(): Path {
        val userHome = System.getProperty("user.home")
        return Paths.get(userHome, ".dbmanager")
    }

    /**
     * Gets the default database file path (~/.dbmanager/default.db)
     */
    fun getDefaultDatabasePath(): Path {
        return getDefaultDatabaseDirectory().resolve("default.db")
    }

    /**
     * Checks if the database file exists at the given path
     */
    fun databaseFileExists(path: Path): Boolean {
        return path.exists() && path.isRegularFile()
    }

    /**
     * Ensures the database directory exists, creating it if necessary
     */
    fun ensureDirectoryExists(path: Path) {
        val directory = path.parent
        if (directory != null && !Files.exists(directory)) {
            Files.createDirectories(directory)
        }
    }

    /**
     * Gets the absolute path string for use with JDBC connection
     */
    fun getJdbcPath(path: Path): String {
        return path.toAbsolutePath().toString()
    }
}
