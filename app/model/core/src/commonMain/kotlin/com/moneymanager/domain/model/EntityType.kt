package com.moneymanager.domain.model

/**
 * Represents the entity types that can have source tracking.
 */
enum class EntityType(
    val id: Long,
) {
    ACCOUNT(1),
    PERSON(2),
    CURRENCY(3),
    PERSON_ACCOUNT_OWNERSHIP(4),
    CATEGORY(5),
    API_IMPORT_STRATEGY(6),

    /**
     * A transfer source. Transfers share the unified `entity_source` store: a transfer's provenance is
     * an `entity_source` row with `entity_type_id = 7` and `entity_id` = the transfer id.
     */
    TRANSFER(7),
}
