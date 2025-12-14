package com.moneymanager.domain.model.csv

import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@JvmInline
value class CsvColumnId
    @OptIn(ExperimentalUuidApi::class)
    constructor(val id: Uuid) {
        @OptIn(ExperimentalUuidApi::class)
        override fun toString() = id.toString()
    }

data class CsvColumn(
    val id: CsvColumnId,
    val columnIndex: Int,
    val originalName: String,
)
