@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.DatabaseConfig
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonAttributeRepositoryImplTest : DbTest() {
    @Test
    fun `insertInCreationMode records audit without bumping person revision`() =
        runTest {
            val personId =
                repositories.personRepository.createPerson(
                    Person(
                        id = PersonId(0L),
                        firstName = "Alice",
                        middleName = null,
                        lastName = "Example",
                    ),
                )

            repositories.personAttributeRepository.insertInCreationMode(
                personId = personId,
                attributeTypeId = AttributeTypeId(DatabaseConfig.PERSON_EXTERNAL_ID_ATTR_TYPE_ID),
                value = "user_001",
            )

            val person = repositories.personRepository.getPersonById(personId).first()!!
            assertEquals(1L, person.revisionId)

            val attrs = repositories.personAttributeRepository.getByPerson(personId).first()
            assertEquals(1, attrs.size)
            assertEquals("person-external-id", attrs.single().attributeType.name)
            assertEquals("user_001", attrs.single().value)

            val auditEntries = repositories.auditRepository.getAttributeAuditByPerson(personId)
            assertEquals(1, auditEntries.size)
            assertEquals(AuditType.INSERT, auditEntries.single().auditType)
            assertEquals(1L, auditEntries.single().revisionId)
        }

    @Test
    fun `insert update delete attribute bumps person revision and records audit trail`() =
        runTest {
            val personId =
                repositories.personRepository.createPerson(
                    Person(
                        id = PersonId(0L),
                        firstName = "Bob",
                        middleName = null,
                        lastName = "Builder",
                    ),
                )

            val typeId = repositories.attributeTypeRepository.getOrCreate("nickname")
            val attrId = repositories.personAttributeRepository.insert(personId, typeId, "Bobby")
            repositories.personAttributeRepository.updateValue(attrId, "Bob")
            repositories.personAttributeRepository.delete(attrId)

            val person = repositories.personRepository.getPersonById(personId).first()!!
            assertEquals(4L, person.revisionId)

            val auditEntries = repositories.auditRepository.getAttributeAuditByPerson(personId)
            assertEquals(3, auditEntries.size)
            assertEquals(AuditType.DELETE, auditEntries[0].auditType)
            assertEquals(AuditType.UPDATE, auditEntries[1].auditType)
            assertEquals(AuditType.INSERT, auditEntries[2].auditType)
            assertTrue(auditEntries.all { it.attributeType.name == "nickname" })
        }
}
