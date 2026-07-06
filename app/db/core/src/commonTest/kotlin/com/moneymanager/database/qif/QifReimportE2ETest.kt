@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.qif

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.qif.QifImport
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.deleteAccountMapping
import com.moneymanager.qifimporter.bulkApplyQif
import com.moneymanager.qifimporter.bulkReimportQif
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.installBuiltInCsvStrategies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * End-to-end guard for QIF re-import. Drives a real import through [bulkApplyQif], then re-imports via
 * [bulkReimportQif] and asserts (a) a re-import with no config change is a no-op (no double-import), and
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
        index: Long,
        date: String,
        amount: String,
        payee: String,
    ) = QifImportRecord(
        recordIndex = index,
        sectionType = "BANK",
        accountName = "Santander",
        supported = true,
        rawText = "",
        date = date,
        amount = amount,
        payee = payee,
    )

    private suspend fun createImport(): QifImport {
        val records =
            listOf(
                rec(0, "27/03/2021", "-7.32", "CARD PAYMENT TO VANGUARD,7.32 GBP, RATE 1.00/GBP ON 27-03-2021, 7.32"),
                rec(1, "28/03/2021", "-10.00", "CARD PAYMENT TO VANGUARD,10.00 GBP, RATE 1.00/GBP ON 28-03-2021, 10.00"),
                rec(
                    2,
                    "01/03/2021",
                    "-1352.23",
                    "DIRECT DEBIT PAYMENT TO AMERICAN EXPRESS REF 374693512504002, MANDATE NO 0013, 1352.23",
                ),
            )
        val id =
            repositories.qifImportRepository.createImport(
                fileName = "santander.qif",
                records = records,
                accountType = "BANK",
                fileChecksum = "santander-reimport-checksum",
                fileLastModified = now,
            )
        return repositories.qifImportRepository.getImport(id).first()!!
    }

    private suspend fun sourceAccountId(): AccountId =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .firstOrNull { it.name == "Santander" }
            ?.id
            ?: repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Santander", openingDate = now))

    private suspend fun apply(qifImport: QifImport) {
        if (repositories.csvImportStrategyRepository
                .getAllStrategies()
                .first()
                .isEmpty()
        ) {
            repositories.installBuiltInCsvStrategies()
        }
        bulkApplyQif(
            imports = listOf(qifImport),
            sourceAccountId = sourceAccountId(),
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            qifImportRepository = repositories.qifImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { _, _ -> },
        )
    }

    private suspend fun reimport() {
        bulkReimportQif(
            imports = repositories.qifImportRepository.getAllImports().first(),
            sourceAccountOverride = sourceAccountId(),
            // null => strategy's own currency, matching the import path exactly (no spurious value updates).
            currencyId = null,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            qifImportRepository = repositories.qifImportRepository,
            transactionRepository = repositories.transactionRepository,
            transferSourceRepository = repositories.transferSourceRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { _, _ -> },
        )
    }

    @Test
    fun reimportWithNoChange_isNoOp() =
        runTest {
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
        runTest {
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
                valuePattern = Regex("VANGUARD", RegexOption.IGNORE_CASE),
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
        runTest {
            apply(createImport())
            val brokerageId =
                repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Brokerage", openingDate = now))
            repositories.importEngine.createAccountMapping(
                valuePattern = Regex("VANGUARD", RegexOption.IGNORE_CASE),
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
