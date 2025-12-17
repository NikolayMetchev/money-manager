@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model.csvstrategy

import kotlin.uuid.Uuid

@JvmInline
value class CsvImportStrategyId(val id: Uuid) {
    override fun toString() = id.toString()
}
