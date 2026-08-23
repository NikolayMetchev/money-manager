package com.moneymanager.database.repository

import com.moneymanager.database.mapper.toSourceRecord
import com.moneymanager.database.sql.read.MoneyManagerDatabase
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecord

/** Identifies the single revision of a single entity a [SourceRecord] belongs to. */
internal data class EntityRevision(
    val entityId: Long,
    val revisionId: Long,
)

/**
 * Reads provenance out of the unified `entity_source` store.
 *
 * Audit history used to carry its source columns in the audit query itself, which meant every
 * per-entity audit query repeated the same source join and the same flat column list, and every
 * audit mapper repeated the same fourteen-field construction. Instead this reads the sources for
 * an audit screen in one extra indexed query, which the caller attaches by [EntityRevision] —
 * `entity_source`'s UNIQUE (entity_type_id, entity_id, revision_id) makes that pairing exactly
 * what the removed LEFT JOIN produced.
 *
 * A pre-joined SQL view was the other candidate and was rejected: SQLite cannot flatten a joined
 * view used as the right operand of a LEFT JOIN, so every audit query would materialize the whole
 * view instead of seeking into it.
 */
internal class EntitySourceReader(
    database: MoneyManagerDatabase,
) {
    private val queries = database.entitySourceSelectQueries

    /** Every recorded source for one entity, newest revision first. */
    fun sourcesFor(
        entityType: EntityType,
        entityId: Long,
    ): List<SourceRecord> =
        queries
            .selectEntitySources(entityType.id, entityId)
            .executeAsList()
            .mapNotNull { it.toSourceRecord(entityType) }

    /**
     * The sources for [entityIds] keyed by the entity revision they belong to, for attaching to
     * audit rows. Audit screens pass a single id; only ownership-by-account passes several (one per
     * owner of the account), so a query per id is cheaper than a dynamic IN list.
     */
    fun sourcesByRevision(
        entityType: EntityType,
        entityIds: Collection<Long>,
    ): Map<EntityRevision, SourceRecord> =
        entityIds
            .flatMap { sourcesFor(entityType, it) }
            .associateBy { EntityRevision(it.entityId, it.revisionId) }
}
