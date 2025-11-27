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

actual data class DbLocation(val path: Path) {
    actual fun exists() = path.exists()

    override fun toString() = path.toString()
}
