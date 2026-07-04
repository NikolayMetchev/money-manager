@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model.csvstrategy

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class CsvImportStrategyId(
    val id: Uuid,
) {
    override fun toString() = id.toString()
}
