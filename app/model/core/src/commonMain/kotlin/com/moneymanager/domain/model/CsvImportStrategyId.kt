package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class CsvImportStrategyId(
    val id: Uuid,
) {
    override fun toString() = id.toString()
}
