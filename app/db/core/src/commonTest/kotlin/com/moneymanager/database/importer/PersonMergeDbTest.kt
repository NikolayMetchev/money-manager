package com.moneymanager.database.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.PersonMergeRequest
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PersonMergeDbTest : DbTest() {
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private suspend fun account(name: String): AccountId {
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = baseTime), Source.Manual)
        return repositories.accountRepository
            .getAllAccounts()
            .first()
            .first { it.name == name }
            .id
    }

    private suspend fun person(
        first: String,
        last: String,
        owns: List<AccountId> = emptyList(),
        attributes: List<NewAttribute> = emptyList(),
    ): PersonId {
        val key = LocalPersonKey(first)
        val result =
            repositories.importEngine.import(
                ImportBatch(
                    peopleToCreate =
                        listOf(
                            ImportPersonIntent(
                                key = key,
                                match = PersonMatchKey.ByNameKey("${first.lowercase()} ${last.lowercase()}"),
                                firstName = first,
                                lastName = last,
                                attributes = attributes,
                                source = Source.Manual,
                            ),
                        ),
                    ownerships =
                        owns.map {
                            ImportOwnershipIntent(
                                personKey = key,
                                account = AccountRef.Existing(it),
                                source = Source.Manual,
                            )
                        },
                ),
            )
        return result.createdPersonIds.getValue(key)
    }

    private suspend fun owned(id: PersonId) =
        repositories.personAccountOwnershipRepository
            .getOwnershipsByPerson(id)
            .first()
            .map {
                it.accountId
            }.toSet()

    @Test
    fun mergeMovesAccountsAndLeavesAnAlias() =
        runDbTest {
            val shared = account("Shared")
            val onlyDup = account("OnlyDup")
            val keep = person("Alice", "Smith", listOf(shared))
            val dup = person("Alicia", "Smyth", listOf(shared, onlyDup))

            repositories.importEngine.import(
                ImportBatch.manualEdits(personMerges = listOf(PersonMergeRequest(deletedId = dup, survivingId = keep))),
            )

            assertNull(repositories.personRepository.getPersonById(dup).first())
            assertEquals(setOf(shared, onlyDup), owned(keep))
            assertEquals(
                listOf("Alicia Smyth"),
                repositories.personRepository
                    .getAliasesByPerson(keep)
                    .first()
                    .map { it.alias },
            )
        }

    @Test
    fun importNamingTheMergedAwayPersonFindsTheSurvivor() =
        runDbTest {
            val acct = account("Acct")
            val keep = person("Alice", "Smith")
            val dup = person("Alicia", "Smyth")
            repositories.importEngine.import(
                ImportBatch.manualEdits(personMerges = listOf(PersonMergeRequest(deletedId = dup, survivingId = keep))),
            )

            val key = LocalPersonKey("again")
            val result =
                repositories.importEngine.import(
                    ImportBatch(
                        peopleToCreate =
                            listOf(
                                ImportPersonIntent(
                                    key = key,
                                    match = PersonMatchKey.ByNameKey("alicia smyth"),
                                    firstName = "Alicia",
                                    lastName = "Smyth",
                                    source = Source.Manual,
                                ),
                            ),
                        ownerships =
                            listOf(
                                ImportOwnershipIntent(personKey = key, account = AccountRef.Existing(acct), source = Source.Manual),
                            ),
                    ),
                )

            assertEquals(0, result.peopleCreated)
            assertEquals(
                1,
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .size,
            )
            assertEquals(setOf(acct), owned(keep))
        }

    @Test
    fun unmergeRestoresThePersonTheirAccountsAndAliases() =
        runDbTest {
            val shared = account("Shared")
            val onlyDup = account("OnlyDup")
            val keep = person("Alice", "Smith", listOf(shared))
            val dup = person("Alicia", "Smyth", listOf(shared, onlyDup))
            repositories.importEngine.import(
                ImportBatch.manualEdits(personMerges = listOf(PersonMergeRequest(deletedId = dup, survivingId = keep))),
            )
            val merge =
                repositories.personRepository
                    .getReversibleMerges()
                    .first()
                    .single()

            repositories.importEngine.import(ImportBatch.manualEdits(personUnmerges = listOf(merge.id)))

            assertTrue(repositories.personRepository.getPersonById(dup).first() != null)
            assertEquals(setOf(shared, onlyDup), owned(dup))
            assertEquals(setOf(shared), owned(keep))
            assertTrue(
                repositories.personRepository
                    .getAliasesByPerson(keep)
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                repositories.personRepository
                    .getReversibleMerges()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun mergeThenMergeTheSurvivor_undoesInReverseOrder() =
        runDbTest {
            val acct = account("Acct")
            val a = person("Ann", "Lee", listOf(acct))
            val b = person("Anne", "Leigh")
            val c = person("Anna", "Lea")
            repositories.importEngine.import(
                ImportBatch.manualEdits(personMerges = listOf(PersonMergeRequest(deletedId = a, survivingId = b))),
            )
            repositories.importEngine.import(
                ImportBatch.manualEdits(personMerges = listOf(PersonMergeRequest(deletedId = b, survivingId = c))),
            )
            assertEquals(setOf(acct), owned(c))

            val merges = repositories.personRepository.getReversibleMerges().first()
            val first = merges.first { it.deletedPersonId == a }
            val second = merges.first { it.deletedPersonId == b }
            repositories.importEngine.import(ImportBatch.manualEdits(personUnmerges = listOf(second.id)))
            repositories.importEngine.import(ImportBatch.manualEdits(personUnmerges = listOf(first.id)))

            assertEquals(setOf(acct), owned(a))
            assertTrue(owned(b).isEmpty())
            assertTrue(owned(c).isEmpty())
        }

    private suspend fun attributes(id: PersonId) =
        repositories.personAttributeRepository
            .getByPerson(id)
            .first()
            .map { Triple(it.attributeType.name, it.value, it.groupKey) }
            .toSet()

    @Test
    fun updatingAPersonChangesTheirNameAndAttributes() =
        runDbTest {
            val email = repositories.importEngine.getOrCreateAttributeType("email")
            val phone = repositories.importEngine.getOrCreateAttributeType("phone")
            val nick = repositories.importEngine.getOrCreateAttributeType("nick")
            val id = person("Alice", "Smith", attributes = listOf(NewAttribute(email, "a@x"), NewAttribute(phone, "1")))
            val stored = repositories.personRepository.getPersonById(id).first()!!
            val attrs = repositories.personAttributeRepository.getByPerson(id).first()

            repositories.importEngine.import(
                ImportBatch.manualEdits().copy(
                    peopleToCreate =
                        listOf(
                            ImportPersonIntent(
                                key = LocalPersonKey("edit"),
                                source = Source.Manual,
                                operation = ImportOperation.UPDATE,
                                existingId = id,
                                person = stored.copy(firstName = "Alicia"),
                                deletedAttributeIds = setOf(attrs.first { it.attributeType.id == phone }.id),
                                updatedAttributes = mapOf(attrs.first { it.attributeType.id == email }.id to NewAttribute(email, "b@x")),
                                attributes = listOf(NewAttribute(nick, "Al")),
                            ),
                        ),
                ),
            )

            assertEquals(
                "Alicia",
                repositories.personRepository
                    .getPersonById(id)
                    .first()!!
                    .firstName,
            )
            assertEquals(setOf(Triple("email", "b@x", ""), Triple("nick", "Al", "")), attributes(id))
        }

    @Test
    fun updatingOnlyAPersonsAttributesLeavesTheirName() =
        runDbTest {
            val email = repositories.importEngine.getOrCreateAttributeType("email")
            val id = person("Alice", "Smith")

            repositories.importEngine.import(
                ImportBatch.manualEdits().copy(
                    peopleToCreate =
                        listOf(
                            ImportPersonIntent(
                                key = LocalPersonKey("edit"),
                                source = Source.Manual,
                                operation = ImportOperation.UPDATE,
                                existingId = id,
                                attributes = listOf(NewAttribute(email, "a@x")),
                            ),
                        ),
                ),
            )

            assertEquals(
                "Alice",
                repositories.personRepository
                    .getPersonById(id)
                    .first()!!
                    .firstName,
            )
            assertEquals(setOf(Triple("email", "a@x", "")), attributes(id))
        }

    @Test
    fun mergeKeepsBothIdentitiesAndUnmergeRestoresThem() =
        runDbTest {
            val same = repositories.importEngine.getOrCreateAttributeType("same")
            val onlyDup = repositories.importEngine.getOrCreateAttributeType("only-dup")
            val clash = repositories.importEngine.getOrCreateAttributeType("clash")
            val keep =
                person("Alice", "Smith", attributes = listOf(NewAttribute(same, "s"), NewAttribute(clash, "keep")))
            val dup =
                person(
                    "Alicia",
                    "Smyth",
                    attributes = listOf(NewAttribute(same, "s"), NewAttribute(onlyDup, "d"), NewAttribute(clash, "dup")),
                )

            repositories.importEngine.import(
                ImportBatch.manualEdits(personMerges = listOf(PersonMergeRequest(deletedId = dup, survivingId = keep))),
            )

            val merged = attributes(keep)
            assertEquals(4, merged.size)
            assertTrue(Triple("same", "s", "") in merged)
            assertTrue(Triple("only-dup", "d", "") in merged)
            assertTrue(merged.any { it.first == "clash" && it.second == "dup" && it.third.isNotEmpty() })

            val merge =
                repositories.personRepository
                    .getReversibleMerges()
                    .first()
                    .single()
            repositories.importEngine.import(ImportBatch.manualEdits(personUnmerges = listOf(merge.id)))

            assertEquals(setOf(Triple("same", "s", ""), Triple("clash", "keep", "")), attributes(keep))
            assertEquals(
                setOf(Triple("same", "s", ""), Triple("only-dup", "d", ""), Triple("clash", "dup", "")),
                attributes(dup),
            )
        }
}
