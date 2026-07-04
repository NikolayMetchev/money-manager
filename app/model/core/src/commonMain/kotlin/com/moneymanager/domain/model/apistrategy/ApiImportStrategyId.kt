@file:OptIn(ExperimentalUuidApi::class)

package com.moneymanager.domain.model.apistrategy

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class ApiImportStrategyId(
    val id: Uuid,
) {
    override fun toString() = id.toString()
}
