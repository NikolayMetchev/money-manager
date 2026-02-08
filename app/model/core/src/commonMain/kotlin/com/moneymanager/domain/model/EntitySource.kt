@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * Represents the entity types that can have source tracking.
 */
enum class EntityType(val id: Long) {
    ACCOUNT(1),
    PERSON(2),
    CURRENCY(3),
    PERSON_ACCOUNT_OWNERSHIP(4),
    CATEGORY(5),
    ;

    companion object {
        fun fromId(id: Long): EntityType = entries.first { it.id == id }

        fun fromName(name: String): EntityType = valueOf(name)
    }
}

/**
 * Represents the source/provenance of an entity change.
 * Tracks which device made the change and when.
 */
data class EntitySource(
    val id: Long,
    val entityType: EntityType,
    val entityId: Long,
    val revisionId: Long,
    val sourceType: SourceType,
    val deviceId: Long,
    val deviceInfo: DeviceInfo?,
    val createdAt: Instant,
)
