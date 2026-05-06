@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model.apistrategy

import kotlin.uuid.Uuid

@JvmInline
value class ApiImportStrategyId(
    val id: Uuid,
) {
    override fun toString() = id.toString()
}
