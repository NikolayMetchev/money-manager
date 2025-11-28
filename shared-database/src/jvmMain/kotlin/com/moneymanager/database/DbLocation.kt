package com.moneymanager.database

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

val DEFAULT_DATABASE_PATH: DbLocation =
    com.moneymanager.database.DbLocation(
        Paths.get(
            System.getProperty("user.home"),
            ".moneymanager",
            "default.db",
        ),
    )

/**
 * Special marker for in-memory database on JVM.
 * A null path indicates an in-memory database.
 */
val IN_MEMORY_DATABASE: DbLocation =
    DbLocation(null)

actual data class DbLocation(val path: Path?) {
    actual fun exists() = path?.exists() ?: true // in-memory databases always "exist"

    override fun toString() = path?.toString() ?: ":memory:"

    fun isInMemory() = path == null
}
