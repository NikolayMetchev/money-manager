@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@JvmInline
value class ImportDirectoryId(
    val id: Uuid,
) {
    override fun toString() = id.toString()
}
