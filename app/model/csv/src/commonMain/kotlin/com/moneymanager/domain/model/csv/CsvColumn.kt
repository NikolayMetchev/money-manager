package com.moneymanager.domain.model.csv

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

@JvmInline
value class CsvColumnId
    constructor(
        val id: Uuid,
    ) {
        override fun toString() = id.toString()
    }

data class CsvColumn(
    val id: CsvColumnId,
    val columnIndex: Int,
    val originalName: String,
)
