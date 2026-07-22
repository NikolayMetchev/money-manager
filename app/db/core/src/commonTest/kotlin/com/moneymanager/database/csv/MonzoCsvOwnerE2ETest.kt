@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkImportProgress
import com.moneymanager.csvimporter.CsvBulkResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * End-to-end test for Monzo-to-Monzo transfers: Monzo's CSV export names the OTHER Monzo user in the
 * `Name` column with `Type` "Monzo-to-Monzo" — a peer's own Monzo account, not a merchant. The account
 * created for it must be clearly named ("Monzo <name>"), and the transfer's counterparty must become a
 * Person who owns that account, exactly like Santander's regex-based personal-counterparty detection.
 */
class MonzoCsvOwnerE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private val headers =
        listOf(
            "Transaction ID",
            "Date",
            "Time",
            "Type",
            "Name",
            "Emoji",
            "Category",
            "Amount",
            "Currency",
            "Local amount",
            "Local currency",
            "Notes and #tags",
            "Address",
            "Receipt",
            "Description",
            "Category split",
            "Money Out",
            "Money In",
        )

    private fun row(
        transactionId: String,
        type: String,
        name: String,
        amount: String,
        description: String,
    ): List<String> =
        listOf(
            transactionId,
            "19/11/2023",
            "21:15:00",
            type,
            name,
            "",
            "Transfers",
            amount,
            "GBP",
            amount,
            "GBP",
            "",
            "",
            "",
            description,
            "",
            "",
            "",
        )

    private suspend fun stage(
        fileName: String,
        rows: List<List<String>>,
    ): CsvImport {
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = headers,
                rows = rows,
                fileChecksum = "checksum-$fileName",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun applyAll(imports: List<CsvImport>): CsvBulkResult {
        val progress = mutableListOf<BulkImportProgress>()
        val attributeMatchers =
            AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())
        val result =
            bulkApplyCsv(
                imports = imports,
                sourceAccountOverride =
                    repositories.importEngine.createAccount(
                        Account(id = AccountId(0), name = "Monzo", openingDate = now),
                        Source.Manual,
                    ),
                strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                currencies = repositories.currencyRepository.getAllCurrencies().first(),
                accountMappingRepository = repositories.accountMappingRepository,
                accountRepository = repositories.accountRepository,
                csvImportRepository = repositories.csvImportRepository,
                maintenance = maintenance,
                importEngine = repositories.importEngine,
                onProgress = { progress += it },
                cryptoRepository = repositories.cryptoRepository,
                attributeAccountMatchers = attributeMatchers,
            )
        assertBulkProgress(progress, imports.size)
        return result
    }

    @Test
    fun monzoToMonzoTransfer_createsMonzoPrefixedAccountOwnedByThatPerson() =
        runTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")

            val export =
                stage(
                    "MonzoDataExport.csv",
                    listOf(row("tx_1", "Monzo-to-Monzo", "Olga Zakharenko", "6.00", "Olga Zakharenko")),
                )
            val result = applyAll(listOf(export))
            assertEquals(1, result.filesImported)
            assertEquals(1, result.transfersCreated)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            val olgaAccount = accounts.singleOrNull { it.name == "Monzo Olga Zakharenko" }
            assertTrue(olgaAccount != null, "Expected an account named 'Monzo Olga Zakharenko', got: ${accounts.map { it.name }}")
            assertTrue(accounts.none { it.name == "Olga Zakharenko" }, "The bare-name account must not also exist")

            val people = repositories.personRepository.getAllPeople().first()
            val olga = people.singleOrNull { it.firstName == "Olga" && it.lastName == "Zakharenko" }
            assertTrue(olga != null, "Expected a Person 'Olga Zakharenko', got: $people")

            val ownerships = repositories.personAccountOwnershipRepository.getOwnershipsByPerson(olga.id).first()
            assertTrue(
                ownerships.any { it.accountId == olgaAccount.id },
                "Olga must own the 'Monzo Olga Zakharenko' account",
            )
        }

    @Test
    fun ordinaryFasterPayment_toSomeoneNamedLikeAPerson_isNotTreatedAsAnOwnedAccount() =
        runTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")

            // Same name, but an ordinary payment (not Monzo-to-Monzo) — must resolve the plain-name
            // account and create no Person/ownership, exactly like today's behaviour.
            val export =
                stage(
                    "MonzoDataExport.csv",
                    listOf(row("tx_1", "Faster payment", "Olga Zakharenko", "-6.00", "To Olga")),
                )
            val result = applyAll(listOf(export))
            assertEquals(1, result.filesImported)

            val accounts = repositories.accountRepository.getAllAccounts().first()
            assertTrue(accounts.any { it.name == "Olga Zakharenko" }, "Expected the plain-name account for a non-P2P payment")
            assertTrue(accounts.none { it.name == "Monzo Olga Zakharenko" })
            assertEquals(emptyList(), repositories.personRepository.getAllPeople().first())
        }
}
