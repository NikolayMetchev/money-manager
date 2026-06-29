@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.mapper

import com.moneymanager.database.sql.audit.SelectAttributeAuditByPerson
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.PersonAttributeAuditEntry
import com.moneymanager.domain.model.PersonId
import tech.mappie.api.ObjectMappie
import kotlin.time.Instant

object PersonAttributeAuditEntryMapper :
    ObjectMappie<SelectAttributeAuditByPerson, PersonAttributeAuditEntry>(),
    IdConversions,
    AuditTypeConversions {
    override fun map(from: SelectAttributeAuditByPerson): PersonAttributeAuditEntry =
        PersonAttributeAuditEntry(
            id = from.id,
            auditTimestamp = Instant.fromEpochMilliseconds(from.audit_timestamp),
            personId = PersonId(from.person_id),
            revisionId = from.revision_id,
            attributeType =
                AttributeType(
                    id = AttributeTypeId(from.attribute_type_id),
                    name = from.attribute_type_name,
                ),
            auditType = toAuditType(from.audit_type),
            value = from.attribute_value,
        )
}
