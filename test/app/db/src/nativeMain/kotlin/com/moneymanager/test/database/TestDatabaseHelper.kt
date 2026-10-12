package com.moneymanager.test.database

import com.moneymanager.domain.model.DbLocation
import com.moneymanager.files.absolutePath
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

actual fun createTestDatabaseLocation(): DbLocation {
    // absolutePath: native SystemTemporaryDirectory is relative when TMPDIR is unset.
    val directory = Path(absolutePath(SystemTemporaryDirectory), "moneymanager-test-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(directory)
    return DbLocation(Path(directory, "test.db").toString())
}

actual fun deleteTestDatabase(location: DbLocation) {
    val file = Path(location.path)
    runCatching {
        listOf(file, Path("${location.path}-wal"), Path("${location.path}-shm"))
            .forEach { SystemFileSystem.delete(it, mustExist = false) }
        file.parent?.let { SystemFileSystem.delete(it, mustExist = false) }
    }
}
