package com.moneymanager.database.qif

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.stageQifImport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.deleteAccountMapping
import com.moneymanager.regex.compileRegex
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.installBuiltInCsvStrategies
import com.moneymanager.test.database.reimports
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * End-to-end guard for QIF re-import. Stages QIF files as CSV imports, drives a real import through [bulkApplyCsv],
 * then re-imports via [Reimports.reimportAll][com.moneymanager.csvimporter.Reimports.reimportAll] and asserts (a) a re-import with no config change is a no-op (no double-import), and
 * (b) a global account mapping added AFTER the import is applied retroactively — the import-created
 * duplicate account is merged into the mapped target and deleted.
 */
class QifReimportE2ETest : DbTest() {
    private val now = Clock.System.now()

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private fun rec(
        date: String,
        amount: String,
        payee: String,
    ) = "D$date\nT$amount\nP$payee\n^\n"

    private suspend fun createImport(): CsvImport =
        createImport(
            fileName = "santander.qif",
            checksum = "santander-reimport-checksum",
            records =
                listOf(
                    rec("27/03/2021", "-7.32", "CARD PAYMENT TO VANGUARD,7.32 GBP, RATE 1.00/GBP ON 27-03-2021, 7.32"),
                    rec("28/03/2021", "-10.00", "CARD PAYMENT TO VANGUARD,10.00 GBP, RATE 1.00/GBP ON 28-03-2021, 10.00"),
                    rec(
                        "01/03/2021",
                        "-1352.23",
                        "DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 374693512504002, MANDATE NO 0013, 1352.23",
                    ),
                ),
        )

    private suspend fun createImport(
        fileName: String,
        checksum: String,
        records: List<String>,
    ): CsvImport {
        val id =
            repositories.importEngine.stageQifImport(
                fileName = fileName,
                content = "!Type:Bank\n" + records.joinToString(""),
                fileChecksum = checksum,
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun sourceAccountId(): AccountId =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.name == "Santander" }
            ?.id
            ?: repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Santander", openingDate = now))

    private suspend fun apply(qifImport: CsvImport) {
        if (repositories.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .isEmpty()
        ) {
            repositories.installBuiltInCsvStrategies()
        }
        bulkApplyCsv(
            imports = listOf(qifImport),
            sourceAccountOverride = sourceAccountId(),
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { },
        )
    }

    private suspend fun reimport() {
        val reimports = repositories.reimports(maintenance)
        reimports.reimportAll(
            reimports.planAll(
                repositories.csvImportRepository
                    .getAllImports()
                    .first(),
            ),
            accountId = sourceAccountId(),
            // null => the currency the file imported with, matching the import path exactly.
            currencyId = null,
            onProgress = { },
        )
    }

    @Test
    fun reimport_rerunsAnotherFilesDuplicateRecord() =
        runDbTest {
            val first = createImport()
            apply(first)
            val overlap =
                createImport(
                    "santander-later.qif",
                    "santander-later-checksum",
                    // Two payees, so the account the file was imported against is the one they share.
                    listOf(
                        rec("27/03/2021", "-7.32", "CARD PAYMENT TO VANGUARD,7.32 GBP, RATE 1.00/GBP ON 27-03-2021, 7.32"),
                        rec(
                            "01/03/2021",
                            "-1352.23",
                            "DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 374693512504002, MANDATE NO 0013, 1352.23",
                        ),
                    ),
                )
            apply(overlap)
            val duplicates = repositories.csvImportRepository.getImportRows(overlap.id, limit = 10, offset = 0)
            assertEquals(listOf(ImportStatus.DUPLICATE, ImportStatus.DUPLICATE), duplicates.map { it.importStatus })

            val reimports = repositories.reimports(maintenance)
            val plan = reimports.plan(repositories.csvImportRepository.getImport(first.id).first()!!)
            assertEquals(2, plan.rerun.affected.count, "the later file's duplicate records are re-run too")

            reimports.reimport(plan, accountId = sourceAccountId(), currencyId = null)

            val relinked = repositories.csvImportRepository.getImportRows(overlap.id, limit = 10, offset = 0)
            assertTrue(
                relinked.all {
                    it.importStatus == ImportStatus.DUPLICATE && it.transferId != null
                },
                "matched the re-created transfers again: $relinked",
            )
            assertEquals(3L, repositories.accountRepository.countTransfersByAccount(sourceAccountId()))
        }

    @Test
    fun reimportWithNoChange_isNoOp() =
        runDbTest {
            apply(createImport())
            val accountsBefore =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .map { it.name }
                    .toSet()
            val transfersOnSourceBefore = repositories.accountRepository.countTransfersByAccount(sourceAccountId())

            reimport()

            assertEquals(
                accountsBefore,
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .map { it.name }
                    .toSet(),
                "Re-import with no config change must not add or remove accounts",
            )
            assertEquals(
                transfersOnSourceBefore,
                repositories.accountRepository.countTransfersByAccount(sourceAccountId()),
                "Re-import with no config change must not double-import transfers",
            )
        }

    @Test
    fun reimport_appliesNewGlobalMapping_mergesDuplicateAccount() =
        runDbTest {
            apply(createImport())

            val vanguard =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "VANGUARD" }
            val vanguardTransfers = repositories.accountRepository.countTransfersByAccount(vanguard.id)
            assertEquals(2L, vanguardTransfers, "The two VANGUARD card payments should sit on the VANGUARD account")

            // A brokerage account the user maps VANGUARD onto AFTER the original import. Account mappings
            // match (containsMatchIn) against the raw resolved column value — the full Santander payee —
            // so the pattern is unanchored rather than the extracted "VANGUARD" account name.
            val brokerageId =
                repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Brokerage", openingDate = now))
            repositories.importEngine.createAccountMapping(
                valuePattern = compileRegex("VANGUARD", RegexOption.IGNORE_CASE),
                accountId = brokerageId,
            )

            reimport()

            // VANGUARD (import-created) is merged into Brokerage and deleted; its transfers move over.
            assertNull(
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .firstOrNull { it.name == "VANGUARD" },
                "VANGUARD should be merged away and deleted after re-import",
            )
            assertEquals(
                2L,
                repositories.accountRepository.countTransfersByAccount(brokerageId),
                "Brokerage should now hold the two payments the merge moved over",
            )

            // A second re-import with the mapping now settled changes nothing further.
            val accountsAfterFirst =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .map { it.name }
                    .toSet()
            reimport()
            assertEquals(
                accountsAfterFirst,
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .map { it.name }
                    .toSet(),
                "A settled re-import must be idempotent",
            )
            assertTrue(
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .any { it.name == "Brokerage" },
                "Brokerage should remain after the settled re-import",
            )
        }

    @Test
    fun reimport_reversesMerge_whenMappingRemoved() =
        runDbTest {
            apply(createImport())
            val brokerageId =
                repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Brokerage", openingDate = now))
            repositories.importEngine.createAccountMapping(
                valuePattern = compileRegex("VANGUARD", RegexOption.IGNORE_CASE),
                accountId = brokerageId,
            )
            val mappingId =
                repositories.accountMappingRepository
                    .getAllMappings()
                    .first()
                    .first { it.valuePattern.pattern == "VANGUARD" }
                    .id

            // First re-import consolidates VANGUARD into Brokerage (the merge to be reversed).
            reimport()
            assertNull(
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .firstOrNull { it.name == "VANGUARD" },
                "VANGUARD should be merged away first",
            )
            assertEquals(2L, repositories.accountRepository.countTransfersByAccount(brokerageId))

            // User removes the mapping. Re-import must now SPLIT VANGUARD back out (reverse the merge):
            // the deleted account is recreated and its transfers move back off Brokerage.
            repositories.importEngine.deleteAccountMapping(mappingId)
            reimport()

            val vanguard =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .firstOrNull { it.name == "VANGUARD" }
            assertNotNull(vanguard, "VANGUARD should be recreated when the mapping no longer routes it to Brokerage")
            assertEquals(
                2L,
                repositories.accountRepository.countTransfersByAccount(vanguard.id),
                "The two payments should move back onto the recreated VANGUARD account",
            )
            assertEquals(
                0L,
                repositories.accountRepository.countTransfersByAccount(brokerageId),
                "Brokerage should no longer hold VANGUARD's transfers after the reversal",
            )

            // Re-importing again with the mapping still absent changes nothing (idempotent).
            reimport()
            assertEquals(
                2L,
                repositories.accountRepository.countTransfersByAccount(
                    repositories.accountRepository
                        .getAllAccounts()
                        .first()
                        .first { it.name == "VANGUARD" }
                        .id,
                ),
                "A settled reversal must be idempotent",
            )
        }
}
