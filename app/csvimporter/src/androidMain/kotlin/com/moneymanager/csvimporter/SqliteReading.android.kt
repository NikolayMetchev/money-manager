package com.moneymanager.csvimporter

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.moneymanager.csv.CsvParseResult
import java.io.IOException

internal actual fun sqliteReader(): SqliteReader? = ::querySnapshot

private fun querySnapshot(
    bytes: ByteArray,
    queries: Map<String, String>,
): Pair<String, CsvParseResult>? =
    try {
        withSqliteSnapshot(bytes) { snapshot ->
            SQLiteDatabase
                .openDatabase(
                    snapshot.toString(),
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use { database ->
                    database.execPerConnectionSQL("PRAGMA trusted_schema=OFF", null)
                    database.execPerConnectionSQL("PRAGMA query_only=ON", null)
                    readProjection(database, queries)
                }
        }
    } catch (failure: SQLiteException) {
        throw SqliteReadException(failure.message ?: "Could not query the SQLite database", failure)
    } catch (failure: IOException) {
        throw SqliteReadException(failure.message ?: "Could not open the SQLite snapshot", failure)
    }

private fun readProjection(
    database: SQLiteDatabase,
    queries: Map<String, String>,
): Pair<String, CsvParseResult>? {
    sqliteProjectionFunctions.forEach { (name, operation) ->
        database.setCustomScalarFunction(name) { value -> value?.let(operation) }
    }
    database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
    val (name, sql) =
        matchSqliteProjection(queries) { probe ->
            try {
                database.rawQuery(probe, null).use { it.moveToFirst() }
                true
            } catch (failure: SQLiteException) {
                if (!isSchemaMismatch(failure.message)) throw failure
                false
            }
        } ?: return null
    return database.rawQuery(sql, null).use { cursor ->
        val headers = cursor.columnNames.toList()
        val rows =
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        headers.indices.map { index ->
                            if (cursor.getType(index) == Cursor.FIELD_TYPE_BLOB) {
                                cursor.getBlob(index).toHexString()
                            } else {
                                cursor.getString(index).orEmpty()
                            }
                        },
                    )
                }
            }
        name to CsvParseResult(headers, rows)
    }
}
