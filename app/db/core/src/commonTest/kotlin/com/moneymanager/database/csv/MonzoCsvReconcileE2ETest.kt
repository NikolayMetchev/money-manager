package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkApplyResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.database.assertBulkProgress
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.tasks.Progress
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * End-to-end test for cross-source reconciliation between two Monzo CSV exports of the same account
 * pair (e.g. the personal account's export and the joint account's own export). Monzo issues a
 * separate `Transaction ID` per account side of one transfer, so the two rows can never share a
 * unique key — only the [com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig.crossSourceReconcileWindow]
 * fallback (same accounts+amount within the window) pairs them, keeping the movement counted once.
 */
class MonzoCsvReconcileE2ETest : DbTest() {
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
        counterpartyName: String,
        amount: String,
        description: String,
    ): List<String> =
        listOf(
            transactionId,
            "19/11/2023",
            "21:15:00",
            "Faster payment",
            counterpartyName,
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

    private suspend fun applyAll(
        imports: List<CsvImport>,
        sourceAccountOverride: AccountId?,
    ): BulkApplyResult {
        val progress = mutableListOf<Progress>()
        val attributeMatchers =
            AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())
        val result =
            bulkApplyCsv(
                imports = imports,
                sourceAccountOverride = sourceAccountOverride,
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

    private suspend fun transfersBetween(
        sourceId: AccountId,
        targetId: AccountId,
    ): List<Transfer> =
        repositories.transactionRepository
            .getTransactionsByAccount(sourceId)
            .first()
            .filter { it.sourceAccountId == sourceId && it.targetAccountId == targetId }

    @Test
    fun personalAndJointExports_reconcileTheSharedTransfer_insteadOfDoubleCounting() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val personalId =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Monzo", openingDate = now),
                    Source.Manual,
                )
            val jointId =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Monzo Joint", openingDate = now),
                    Source.Manual,
                )

            // Personal account's own export: money leaving Monzo, going to the joint account.
            val personalExport =
                stage("MonzoDataExport_personal.csv", listOf(row("tx_personal_1", "Monzo Joint", "-1000.00", "To Joint")))
            val personalResult = applyAll(listOf(personalExport), sourceAccountOverride = personalId)
            assertEquals(1, personalResult.filesImported)
            assertEquals(1, personalResult.transfersCreated)

            // Joint account's own export: the same movement, viewed from the joint side (money arriving),
            // under a DIFFERENT Transaction ID — Monzo never shares one id across the two accounts' exports.
            val jointExport =
                stage("MonzoDataExport_joint.csv", listOf(row("tx_joint_1", "Monzo", "1000.00", "From Monzo")))
            val jointResult = applyAll(listOf(jointExport), sourceAccountOverride = jointId)
            assertEquals(1, jointResult.filesImported)

            repositories.maintenanceService.refreshMaterializedViews()

            // The two rows are records of one movement: one transfer, observed by each export's row, so it counts once.
            val legs = transfersBetween(personalId, jointId)
            assertEquals(1, legs.size, "the personal- and joint-side rows are one transfer")
            val transfer = legs.single()
            assertTrue(transfer.attributes.none { it.attributeType.name == "excluded" }, "nothing is excluded")
            val balances =
                repositories.transactionRepository
                    .getAccountBalances()
                    .first()
                    .associate { it.accountId to it.balance.toDisplayValue() }
            assertEquals(0, balances.getValue(personalId).compareTo(BigDecimal("-1000")), "the personal account paid once")
            assertEquals(0, balances.getValue(jointId).compareTo(BigDecimal("1000")), "the joint account received once")

            // Each row keeps its own Transaction ID on its own observation: the match never rewrites a record.
            val idType =
                repositories.attributeTypeRepository
                    .getByName("Monzo Transaction ID")
                    .first()!!
                    .id
            val txnIds =
                repositories.observationRepository
                    .getObservationsOfTransaction(transfer.id)
                    .flatMap { it.content.attributes }
                    .filter { it.typeId == idType }
                    .map { it.value }
                    .toSet()
            assertEquals(setOf("tx_personal_1", "tx_joint_1"), txnIds)
        }

    @Test
    fun sameTransactionIdReimported_staysAnOrdinaryDuplicate_notReconciled() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val personalId =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Monzo", openingDate = now),
                    Source.Manual,
                )
            val jointId =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Monzo Joint", openingDate = now),
                    Source.Manual,
                )

            val export = stage("MonzoDataExport.csv", listOf(row("tx_1", "Monzo Joint", "-1000.00", "To Joint")))
            assertEquals(1, applyAll(listOf(export), sourceAccountOverride = personalId).transfersCreated)

            // Re-importing the identical file (same Transaction ID) must stay a plain duplicate.
            val reimport = stage("MonzoDataExport-again.csv", listOf(row("tx_1", "Monzo Joint", "-1000.00", "To Joint")))
            val result = applyAll(listOf(reimport), sourceAccountOverride = personalId)
            assertEquals(0, result.transfersCreated)
            assertEquals(1, result.duplicatesSkipped)
            assertEquals(1, transfersBetween(personalId, jointId).size, "no second (excluded) leg for an exact re-import")
        }
}
