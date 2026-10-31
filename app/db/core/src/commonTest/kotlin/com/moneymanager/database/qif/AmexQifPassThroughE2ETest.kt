package com.moneymanager.database.qif

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.stageQifImport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.installBuiltInCsvStrategies
import com.moneymanager.test.database.installBuiltInPassThroughs
import com.moneymanager.test.database.reimports
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A QIF card statement's conduit charges become pass-through chains, as every other source's do (#1181). Amex records
 * a PayPal purchase as `PAYPAL *GIFFGAFF …`; the PayPal pass-through definition splits it into the card's funding leg
 * into PayPal and PayPal's spend leg to the merchant, so it reconciles with a PDF or CSV record of the same purchase
 * and reaches PayPal's own export. The QIF used to be applied by its own pipeline, which ran no detector.
 */
class AmexQifPassThroughE2ETest : DbTest() {
    private val now = Clock.System.now()

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private suspend fun amexStatement(): CsvImport {
        val id =
            repositories.importEngine.stageQifImport(
                fileName = "amex.qif",
                content =
                    "!Type:Bank\n" +
                        "D14/01/2020\nT-10.00\nPPAYPAL *GIFFGAFF 710795 000000000000\n" +
                        "M RETAIL GOODS Process Date 15/01/2020\nN99361810\n^\n" +
                        "D19/01/2020\nT-16.10\nPSAINSBURY'S\nM GOODS Process Date 19/01/2020\nN99361811\n^\n",
                fileChecksum = "amex-checksum",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun apply(qifImport: CsvImport) {
        val result =
            bulkApplyCsv(
                imports = listOf(qifImport),
                sourceAccountOverride = null,
                strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                currencies = repositories.currencyRepository.getAllCurrencies().first(),
                accountMappingRepository = repositories.accountMappingRepository,
                accountRepository = repositories.accountRepository,
                csvImportRepository = repositories.csvImportRepository,
                maintenance = maintenance,
                importEngine = repositories.importEngine,
                onProgress = { },
                passThroughAccounts = repositories.passThroughAccountRepository.getAll().first(),
            )
        assertEquals(1, result.filesImported, "${result.files}")
    }

    /** Every transfer the statement left, as (source, target, pass-through link to), sorted. */
    private suspend fun transfers(): List<Triple<String, String, String?>> {
        val accounts = repositories.accountRepository.getAllAccounts().first()
        val names = accounts.associate { it.id to it.name }
        // Every leg of the statement touches the card or PayPal.
        val transfers =
            accounts
                .filter { it.name == "American Express" || it.name == "PayPal" }
                .flatMap { repositories.transactionRepository.getTransactionsByAccount(it.id).first() }
                .distinctBy { it.id }
        val byId = transfers.associateBy { it.id }

        fun Transfer.label() = "${names.getValue(sourceAccountId)} -> ${names.getValue(targetAccountId)}"
        return transfers
            .map { t ->
                val linked =
                    repositories.transferRelationshipRepository
                        .getByTransfer(t.id)
                        .first()
                        .filter { it.relationshipType.name == "pass-through" && it.id1 == t.id }
                        .firstNotNullOfOrNull { byId[it.id2]?.label() }
                Triple(names.getValue(t.sourceAccountId), names.getValue(t.targetAccountId), linked)
            }.sortedWith(compareBy({ it.first }, { it.second }))
    }

    private val expected =
        listOf(
            Triple("American Express", "PayPal", "PayPal -> GIFFGAFF 710795 000000000000"),
            Triple("American Express", "SAINSBURY'S", null),
            Triple("PayPal", "GIFFGAFF 710795 000000000000", null),
        )

    @Test
    fun payPalCharge_isAChainThroughPayPal() =
        runDbTest {
            repositories.installBuiltInCsvStrategies()
            repositories.installBuiltInPassThroughs()
            apply(amexStatement())
            assertEquals(expected, transfers())
        }

    @Test
    fun reimport_rebuildsTheChain() =
        runDbTest {
            repositories.installBuiltInCsvStrategies()
            repositories.installBuiltInPassThroughs()
            val statement = amexStatement()
            apply(statement)
            val reimports = repositories.reimports(maintenance)
            reimports.reimport(
                reimports.plan(repositories.csvImportRepository.getImport(statement.id).first()!!),
                accountId = null,
                currencyId = null,
            )
            assertEquals(expected, transfers())
        }
}
