package com.moneymanager.domain.model

import kotlinx.serialization.Serializable

data class AttributeType(
    val id: AttributeTypeId,
    val name: String,
)

@Serializable
@JvmInline
value class AttributeTypeId(val id: Long) {
    override fun toString() = id.toString()
}
