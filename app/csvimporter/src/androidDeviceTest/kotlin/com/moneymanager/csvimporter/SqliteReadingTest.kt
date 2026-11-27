package com.moneymanager.csvimporter

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SqliteReadingTest {
    @Test
    fun readsAnOriginalDatabaseAndRunsProjectionFunctions() =
        runTest {
            val file = File.createTempFile("sqlite-reading-test-", ".db")
            try {
                SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                    db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY, units TEXT NOT NULL)")
                    db.execSQL("INSERT INTO entries VALUES (?, ?)", arrayOf("wallet\"id", "1000000000000000001"))
                }
                val bytes = file.readBytes()
                assertContains(sqliteTables(bytes), "entries")
                assertEquals(
                    listOf(listOf("wallet\"id", "1000000000000000001")),
                    sqliteTablePage(bytes, "entries", offset = 0, limit = 10).rows,
                )
                val projected =
                    previewSqliteQuery(
                        bytes,
                        "SELECT decimal_ratio(units || '/1000000000000000000'), sqlite_json_quote(id) FROM entries",
                    )
                assertEquals(listOf(listOf("1.000000000000000001", "\"wallet\\\"id\"")), projected.rows)
            } finally {
                file.delete()
            }
        }
}
