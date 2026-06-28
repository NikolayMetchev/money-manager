@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.domain.model.importdirectory

import kotlin.uuid.Uuid

@JvmInline
value class ImportDirectoryId(
    val id: Uuid,
) {
    override fun toString() = id.toString()
}
