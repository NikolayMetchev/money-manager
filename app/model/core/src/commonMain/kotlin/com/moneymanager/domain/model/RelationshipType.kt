package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

data class RelationshipType(
    val id: RelationshipTypeId,
    val name: String,
)

@Serializable
@JvmInline
value class RelationshipTypeId(
    val id: Long,
) {
    override fun toString() = id.toString()
}
