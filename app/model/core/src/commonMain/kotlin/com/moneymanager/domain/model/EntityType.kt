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
     * A transfer source. Dormant for now (transfers still use the dedicated `transfer_source` tables);
     * carried only as the read-side label on a transfer [SourceRecord]. Phase F wires the DB routing.
     */
    TRANSFER(7),
}
