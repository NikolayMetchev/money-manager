package com.moneymanager.database.importer

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOwnershipIntent
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.importengineapi.PersonMatchKey
import com.moneymanager.importengineapi.PersonMergeRequest
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
            val dup = person("Alicia", "Smith", listOf(shared, onlyDup))

            repositories.importEngine.import(
                ImportBatch.manualEdits(personMerges = listOf(PersonMergeRequest(deletedId = dup, survivingId = keep))),
            )

            assertNull(repositories.personRepository.getPersonById(dup).first())
            assertEquals(setOf(shared, onlyDup), owned(keep))
            assertEquals(
                listOf("Alicia Smith"),
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
            val dup = person("Alicia", "Smith")
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
                                    match = PersonMatchKey.ByNameKey("alicia smith"),
                                    firstName = "Alicia",
                                    lastName = "Smith",
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
            val dup = person("Alicia", "Smith", listOf(shared, onlyDup))
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
}
