@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.csv

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@JvmInline
value class CsvImportId
    @OptIn(ExperimentalUuidApi::class)
    constructor(val id: Uuid) {
        @OptIn(ExperimentalUuidApi::class)
        override fun toString() = id.toString()
    }

data class CsvImport(
    val id: CsvImportId,
    val tableName: String,
    val originalFileName: String,
    val importTimestamp: Instant,
    val rowCount: Int,
    val columnCount: Int,
    val columns: List<CsvColumn>,
)
