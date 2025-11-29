package com.moneymanager.database

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

val DEFAULT_DATABASE_PATH: DbLocation =
    DbLocation(
        Paths.get(
            System.getProperty("user.home"),
            ".moneymanager",
            DEFAULT_DATABASE_NAME,
        ),
    )

/**
 * Special marker for in-memory database on JVM.
 * A null path indicates an in-memory database.
 */
actual val IN_MEMORY_DATABASE: DbLocation =
    DbLocation(null)

actual data class DbLocation(val path: Path?) {
    actual fun exists() = path?.exists() ?: true // in-memory databases always "exist"

    override fun toString() = path?.toString() ?: ":memory:"

    actual fun isInMemory() = path == null
}
