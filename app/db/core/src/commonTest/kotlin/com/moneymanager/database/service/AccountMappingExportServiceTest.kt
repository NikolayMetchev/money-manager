package com.moneymanager.database.service

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.accountmapping.export.AccountMappingsExport
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.regex.compileRegex
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

class AccountMappingExportServiceTest : DbTest() {
    private val service get() = repositories.accountMappingExportService

    private suspend fun account(name: String): AccountId =
        repositories.accountRepository.createAccount(Account(AccountId(0), name = name, openingDate = Clock.System.now()), Source.Manual)

    private suspend fun mappingsByPattern() =
        repositories.accountMappingRepository
            .getAllMappings()
            .first()
            .associate { it.valuePattern.pattern to it.accountId }

    @Test
    fun exportNamesEachMappingsAccount() =
        runDbTest {
            val tesco = account("Tesco")
            repositories.importEngine.createAccountMapping(compileRegex("TESCO.*"), tesco)

            val export = service.toExport(repositories.accountMappingRepository.getAllMappings().first(), AppVersion("9.9"))

            assertEquals(AccountMappingsExport("9.9", listOf(AccountMappingExport("TESCO.*", "Tesco"))), export)
        }

    @Test
    fun exportRefusesAMappingWhoseAccountIsGone() =
        runDbTest {
            val tesco = account("Tesco")
            repositories.importEngine.createAccountMapping(compileRegex("TESCO.*"), tesco)
            val orphan =
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .single()
                    .copy(accountId = AccountId(999_999))

            assertFailsWith<IllegalStateException> { service.toExport(listOf(orphan), AppVersion("1")) }
        }

    @Test
    fun parseListsEachUnknownAccountOnce() =
        runDbTest {
            account("Tesco")
            val export =
                AccountMappingsExport(
                    "1",
                    listOf(
                        AccountMappingExport("TESCO", "Tesco"),
                        AccountMappingExport("AMZN", "Amazon"),
                        AccountMappingExport("AMAZON", "Amazon"),
                    ),
                )

            val parsed = service.parseExport(export)

            assertEquals(export, parsed.export)
            assertEquals(listOf("Amazon"), parsed.unresolvedAccountNames)
        }

    @Test
    fun importCreatesMapsAndSkipsAccountsPerResolution() =
        runDbTest {
            val tesco = account("Tesco")
            val groceries = account("Groceries")
            val export =
                AccountMappingsExport(
                    "1",
                    listOf(
                        AccountMappingExport("TESCO", "Tesco"),
                        AccountMappingExport("AMZN", "Amazon"),
                        AccountMappingExport("SAINSBURY", "Sainsburys"),
                        AccountMappingExport("LIDL", "Unresolved"),
                        // A legacy export repeated a pattern per column: the first one wins.
                        AccountMappingExport("TESCO", "Amazon"),
                    ),
                )

            val count =
                service.importMappings(
                    export,
                    mapOf(
                        "Amazon" to CsvResolution.CreateNew("Amazon"),
                        "Sainsburys" to CsvResolution.MapToExisting(groceries.id),
                    ),
                )

            assertEquals(3, count)
            val amazon =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .single { it.name == "Amazon" }
                    .id
            assertEquals(mapOf("TESCO" to tesco, "AMZN" to amazon, "SAINSBURY" to groceries), mappingsByPattern())
            val stored = repositories.accountMappingRepository.getAllMappings().first()
            assertEquals(true, stored.all { it.valuePattern.containsMatchIn(it.valuePattern.pattern.lowercase()) })
        }

    @Test
    fun importWithNothingResolvableWritesNothing() =
        runDbTest {
            val count = service.importMappings(AccountMappingsExport("1", listOf(AccountMappingExport("X", "Nobody"))), emptyMap())

            assertEquals(0, count)
            assertEquals(emptyMap(), mappingsByPattern())
        }
}
