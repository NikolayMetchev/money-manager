package com.moneymanager.csvimporter

internal actual val querySqlite: SqliteReader = { _, _ ->
    throw SqliteReadException(
        "SQLite file imports are available on desktop and Android",
    )
}
