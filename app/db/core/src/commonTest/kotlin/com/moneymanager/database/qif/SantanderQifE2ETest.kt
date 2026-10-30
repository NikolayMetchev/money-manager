package com.moneymanager.database.qif

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.stageQifImport
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.tasks.Progress
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.installBuiltInCsvStrategies
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * End-to-end regression guard for the Santander QIF import. Drives the full pipeline (auto-detect the
 * installed Santander strategy, parse Payee sub-components, pre-create counterparty accounts, run the
 * import engine) and asserts the account explosion is gone: one source account + one account per
 * distinct clean counterparty (not one per transaction), with person-to-person payees additionally
 * modelled as People + ownership links. Re-importing the same file produces only duplicates.
 */
class SantanderQifE2ETest : DbTest() {
    private val now = Clock.System.now()

    // Maintenance is a thin domain interface; the entities asserted here don't depend on view refresh.
    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private suspend fun createImport(): CsvImport {
        // Two VANGUARD rows prove merchant consolidation; three person payees prove Person creation.
        val records =
            listOf(
                rec("27/03/2021", "-7.32", "CARD PAYMENT TO VANGUARD,7.32 GBP, RATE 1.00/GBP ON 27-03-2021, 7.32"),
                rec("28/03/2021", "-10.00", "CARD PAYMENT TO VANGUARD,10.00 GBP, RATE 1.00/GBP ON 28-03-2021, 10.00"),
                rec(
                    "01/03/2021",
                    "-1352.23",
                    "DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 3746-935125-04002, MANDATE NO 0013, 1352.23",
                ),
                rec("02/03/2021", "250.00", "FASTER PAYMENTS RECEIPT REF.OLGA FROM ZAKHARENKO O, 250.00"),
                rec("03/03/2021", "1000.00", "FASTER PAYMENTS RECEIPT  FROM NIKOLAY METCHEV, 1000.00"),
                rec(
                    "04/03/2021",
                    "-27.00",
                    "BILL PAYMENT VIA FASTER PAYMENT TO JASMINA KRUMOVA REFERENCE CLEANING , MANDATE NO 1, 27.00",
                ),
            )
        val id =
            repositories.importEngine.stageQifImport(
                fileName = "santander.qif",
                content = "!Type:Bank\n" + records.joinToString(""),
                fileChecksum = "santander-checksum",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private fun rec(
        date: String,
        amount: String,
        payee: String,
    ) = "D$date\nT$amount\nP$payee\n^\n"

    private suspend fun apply(qifImport: CsvImport) {
        // Strategies are no longer seeded; the pipeline auto-detects among the installed ones.
        if (repositories.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .isEmpty()
        ) {
            repositories.installBuiltInCsvStrategies()
        }
        val sourceId =
            repositories.accountRepository
                .getAllAccounts()
                .first()
                .firstOrNull { it.name == "Santander" }
                ?.id
                ?: repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Santander", openingDate = now))
        val progress = mutableListOf<Progress>()
        bulkApplyCsv(
            imports = listOf(qifImport),
            sourceAccountOverride = sourceId,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { progress += it },
        )
        assertBulkProgress(progress, filesTotal = 1)
    }

    @Test
    fun santanderImport_consolidatesCounterparties_andModelsPeople() =
        runDbTest {
            apply(createImport())

            val accounts =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .map { it.name }
                    .toSet()
            // One source account + one account per DISTINCT clean counterparty — not one per record.
            assertEquals(
                setOf("Santander", "VANGUARD", "AMERICAN EXPRESS", "ZAKHARENKO O", "NIKOLAY METCHEV", "JASMINA KRUMOVA"),
                accounts,
            )

            // Person-to-person payees become People; merchants/institutions do not.
            val people =
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .map { it.firstName to it.lastName }
                    .toSet()
            assertEquals(
                setOf("ZAKHARENKO" to "O", "NIKOLAY" to "METCHEV", "JASMINA" to "KRUMOVA"),
                people,
            )

            // Each person owns their counterparty account.
            assertEquals(
                3,
                repositories.personAccountOwnershipRepository
                    .getAllOwnerships()
                    .first()
                    .size,
            )

            // Each person's creation source links back to the exact originating row of the staged QIF file,
            // so the audit UI can navigate to that row (not just the file).
            for (person in repositories.personRepository.getAllPeople().first()) {
                val insert =
                    repositories.auditRepository
                        .getAuditHistoryForPerson(person.id)
                        .first { it.auditType == AuditType.INSERT }
                val source = insert.source?.source
                assertIs<Source.Csv>(source, "person ${person.fullName} should have the staged QIF file as its source")
                assertNotNull(source.rowIndex, "person ${person.fullName} should link to a specific row")
            }
        }

    @Test
    fun reimportingSameFile_producesOnlyDuplicates() =
        runDbTest {
            val qifImport = createImport()
            apply(qifImport)
            val accountsAfterFirst =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .size
            val peopleAfterFirst =
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .size

            // Re-apply the same import: the engine dedups transfers, accounts (by name) and people (by name key).
            apply(repositories.csvImportRepository.getImport(qifImport.id).first()!!)

            assertEquals(
                accountsAfterFirst,
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .size,
            )
            assertEquals(
                peopleAfterFirst,
                repositories.personRepository
                    .getAllPeople()
                    .first()
                    .size,
            )
            assertEquals(
                3,
                repositories.personAccountOwnershipRepository
                    .getAllOwnerships()
                    .first()
                    .size,
            )
        }
}
