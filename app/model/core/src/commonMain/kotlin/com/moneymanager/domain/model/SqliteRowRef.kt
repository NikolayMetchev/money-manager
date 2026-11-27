package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

/** The key of a raw record inside an imported SQLite file. Composite primary keys are supported. */
@Serializable
data class SqliteRowRef(
    val table: String,
    val key: Map<String, String>,
    val schema: String = "main",
) {
    init {
        require(schema == "main" && table.isNotBlank() && key.isNotEmpty())
    }

    val label: String get() = "$schema.$table · " + key.entries.joinToString { "${it.key}=${it.value}" }

    companion object {
        const val COLUMN: String = "source_rows"
    }
}
