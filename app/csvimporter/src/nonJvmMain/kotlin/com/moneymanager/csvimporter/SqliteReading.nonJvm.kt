package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseResult

internal actual fun querySqlite(
    bytes: ByteArray,
    queries: Map<String, String>,
): Pair<String, CsvParseResult>? = throw SqliteReadException("SQLite file imports require the JVM")
