package com.moneymanager.csvimporter

import com.moneymanager.csv.CsvParseResult

@Suppress("unused", "UNUSED_PARAMETER")
internal actual fun querySqlite(
    bytes: ByteArray,
    queries: Map<String, String>,
): Pair<String, CsvParseResult>? = throw SqliteReadException("SQLite file imports are available on desktop and Android")
