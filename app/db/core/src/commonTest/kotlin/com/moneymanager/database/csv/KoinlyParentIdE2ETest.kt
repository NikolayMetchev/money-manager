package com.moneymanager.database.csv

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration

/** End-to-end cover for the built-in Koinly strategy over the newer export, which adds the optional `Parent ID` and `Method Name` columns. */
class KoinlyParentIdE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val headers =
        listOf(
            "ID (read-only)",
            "Parent ID (read-only)",
            "Date (UTC)",
            "Type",
            "Tag",
            "From Wallet (read-only)",
            "From Wallet ID",
            "From Amount",
            "From Currency",
            "To Wallet (read-only)",
            "To Wallet ID",
            "To Amount",
            "To Currency",
            "Fee Amount",
            "Fee Currency",
            "Net Worth Amount",
            "Net Worth Currency",
            "Fee Worth Amount",
            "Fee Worth Currency",
            "Net Value (read-only)",
            "Fee Value (read-only)",
            "Value Currency (read-only)",
            "Deleted",
            "From Source (read-only)",
            "To Source (read-only)",
            "Negative Balances (read-only)",
            "Missing Rates (read-only)",
            "Missing Cost Basis (read-only)",
            "Synced To Accounting At (UTC read-only)",
            "TxSrc",
            "TxDest",
            "TxHash",
            "Method Name (read-only)",
            "Description",
        )

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private fun row(vararg cells: Pair<String, String>): List<String> {
        val byName = cells.toMap() + ("Deleted" to "false")
        return headers.map { byName[it].orEmpty() }
    }

    /** A staking claim: the gas-cost withdrawal is the parent, the reward deposits are its children. */
    private val claimFee =
        row(
            "ID (read-only)" to "P1",
            "Date (UTC)" to "2024-02-29 09:55:40",
            "Type" to "withdrawal",
            "Tag" to "cost",
            "From Wallet (read-only)" to "Cosmos (ATOM);cosmos",
            "From Amount" to "0.003819",
            "From Currency" to "ATOM;3464",
            "Method Name (read-only)" to "MsgWithdrawDelegatorReward",
        )
    private val rewardOne = rewardRow("C1", "NTRN;3593305", "0.000232")
    private val rewardTwo = rewardRow("C2", "STRD;6818810", "0.000292")
    private val plainDeposit =
        row(
            "ID (read-only)" to "D1",
            "Date (UTC)" to "2024-03-01 10:00:00",
            "Type" to "deposit",
            "Tag" to "reward",
            "To Wallet (read-only)" to "Cosmos (ATOM);cosmos",
            "To Amount" to "1.0",
            "To Currency" to "ATOM;3464",
            "Description" to "Staking",
        )

    private fun rewardRow(
        id: String,
        asset: String,
        amount: String,
    ) = row(
        "ID (read-only)" to id,
        "Parent ID (read-only)" to "P1",
        "Date (UTC)" to "2024-02-29 09:55:40",
        "Type" to "deposit",
        "Tag" to "reward",
        "To Wallet (read-only)" to "Cosmos (ATOM);cosmos",
        "To Amount" to amount,
        "To Currency" to asset,
        "Method Name (read-only)" to "MsgWithdrawDelegatorReward",
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

    private suspend fun applyAll(imports: List<CsvImport>) =
        bulkApplyCsv(
            imports = imports,
            sourceAccountOverride = null,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
            tradeRepository = repositories.tradeRepository,
        )

    private suspend fun descriptions(): Set<String> =
        repositories.accountRepository
            .getAllAccounts()
            .first()
            .flatMap { repositories.transactionRepository.getTransactionsByAccount(it.id).first() }
            .map { it.description }
            .toSet()

    @Test
    fun `a row's method names it when it has no description`() =
        runDbTest {
            val result = applyAll(listOf(stage("transactions.csv", listOf(claimFee, rewardOne, rewardTwo, plainDeposit))))
            assertEquals(1, result.filesImported)

            // The method beats the tag and type; a row with its own description keeps it.
            assertEquals(setOf("MsgWithdrawDelegatorReward", "Staking"), descriptions())
        }
}
