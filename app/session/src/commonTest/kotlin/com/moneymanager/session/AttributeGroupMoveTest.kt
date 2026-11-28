package com.moneymanager.session

import com.moneymanager.apiimporter.ApiBulkReimportResult
import com.moneymanager.apiimporter.ApiSessionReimportFailure
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.database.di.toAppServices
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.importengineapi.createApiSession
import com.moneymanager.importengineapi.ensureApiCredential
import com.moneymanager.importengineapi.markApiSessionImported
import com.moneymanager.tasks.TaskManager
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.addAccountAttributes
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Moving a sort code and account number from one account to another re-imports the files that named the old one,
 * so their rows land on the new account.
 */
class AttributeGroupMoveTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()
    private val headers = listOf("TransactionDate", "Description", "Value", "AccountBalance", "AccountName", "AccountNumber")
    private val deposit = listOf("20200505", "Transfer from $BANK_NUMBER", "22.50", "122.50", " Main Account", MARCUS_NUMBER)

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private suspend fun stage(rows: List<List<String>>): CsvImport {
        val fileName = "Transactions $MARCUS_NUMBER 2020-05-10 11_04.csv"
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = headers,
                rows = rows,
                fileChecksum = "checksum-$fileName-${rows.hashCode()}",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun importFile(file: CsvImport) =
        bulkApplyCsv(
            imports = listOf(file),
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
            attributeAccountMatchers = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first()),
            tradeRepository = repositories.tradeRepository,
        )

    private suspend fun account(
        name: String,
        attributes: List<NewAttribute> = emptyList(),
    ): AccountId {
        val id = repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now))
        if (attributes.isNotEmpty()) repositories.accountRepository.addAccountAttributes(accountId = id, newAttributes = attributes)
        return id
    }

    private fun numberType(
        value: String,
        groupKey: String = "",
    ) = NewAttribute(AttributeTypeId(WellKnownIds.ACCOUNT_ACCOUNT_NUMBER_ATTR_TYPE_ID), value, groupKey)

    private fun sortCode() = NewAttribute(AttributeTypeId(WellKnownIds.ACCOUNT_SORT_CODE_ATTR_TYPE_ID), SORT_CODE, GROUP)

    /** Every non-zero balance, by account name. */
    private suspend fun balances(): Map<String, String> {
        repositories.maintenanceService.refreshMaterializedViews()
        val names =
            repositories.accountRepository
                .getAllAccounts()
                .first()
                .associate { it.id to it.name }
        return repositories.transactionRepository
            .getAccountBalances()
            .first()
            .filter { it.balance.amount != BigInteger.ZERO }
            .associate { names.getValue(it.accountId) to it.balance.toDisplayValue().toString() }
    }

    private suspend fun move(
        imports: SessionImports,
        from: AccountId,
        groupKey: String,
        to: AccountId,
    ): AttributeGroupMoveResult {
        val result = CompletableDeferred<AttributeGroupMoveResult>()
        val manager = TaskManager(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        manager.start("move", "Move", "Starting") {
            try {
                imports.moveAttributeGroup(this, from, groupKey, to).also { result.complete(it) }.summary()
            } catch (expected: Exception) {
                result.completeExceptionally(expected)
                throw expected
            }
        }
        return result.await()
    }

    private fun sessionImports() = SessionImports(repositories.toAppServices(repositories.importEngine), inMemoryCredentialVault(), null)

    @Test
    fun `moving the group re-imports the files so their rows land on the new account`() =
        runDbTest {
            account("Marcus account", listOf(numberType(MARCUS_NUMBER)))
            val old = account("Old", listOf(numberType(BANK_NUMBER, GROUP), sortCode()))
            val new = account("New")
            importFile(stage(listOf(deposit)))
            assertEquals(mapOf("Marcus account" to "22.5", "Old" to "-22.5"), balances())

            val result = move(sessionImports(), old, GROUP, new)

            assertEquals(2, result.attributesMoved)
            assertEquals(1, result.files?.filesImported)
            assertEquals(mapOf("Marcus account" to "22.5", "New" to "-22.5"), balances())
            val moved = repositories.accountAttributeRepository.getByAccount(new).first()
            assertEquals(setOf(BANK_NUMBER, SORT_CODE), moved.map { it.value }.toSet())
            assertEquals(setOf(GROUP), moved.map { it.groupKey }.toSet())
            assertEquals(emptyList(), repositories.accountAttributeRepository.getByAccount(old).first())
        }

    @Test
    fun `an account with no such group is refused and nothing moves`() =
        runDbTest {
            val old = account("Old", listOf(numberType(BANK_NUMBER, GROUP)))
            val new = account("New")

            assertFailsWith<IllegalArgumentException> { move(sessionImports(), old, "missing", new) }

            assertEquals(
                1,
                repositories.accountAttributeRepository
                    .getByAccount(old)
                    .first()
                    .size,
            )
        }

    @Test
    fun `a strategy update offers only previously applied files and API sessions for the updated strategies`() =
        runDbTest {
            account("Marcus account", listOf(numberType(MARCUS_NUMBER)))
            account("Bank", listOf(numberType(BANK_NUMBER)))
            val importedFile = stage(listOf(deposit))
            importFile(importedFile)
            stage(listOf(deposit, deposit))
            val strategyName =
                checkNotNull(
                    repositories.csvImportRepository
                        .getImport(importedFile.id)
                        .first()
                        ?.lastAppliedStrategyName,
                )

            val apiStrategies = repositories.apiImportStrategyRepository.getAllStrategies().first()
            val binance = apiStrategies.single { it.name == "Binance" }
            val kraken = apiStrategies.single { it.name == "Kraken" }
            val engine = repositories.importEngine
            val imports = sessionImports()
            val deviceId = repositories.toAppServices(engine).deviceId
            val binanceConnection = engine.ensureApiCredential(binance.id, now)
            val krakenConnection = engine.ensureApiCredential(kraken.id, now)
            val importedSession = engine.createApiSession(deviceId, now, binanceConnection)
            engine.markApiSessionImported(importedSession, binance.revisionId, now)
            engine.createApiSession(deviceId, now, binanceConnection)
            val unrelatedSession = engine.createApiSession(deviceId, now, krakenConnection)
            engine.markApiSessionImported(unrelatedSession, kraken.revisionId, now)

            val offer =
                imports.findStrategyReimports(
                    setOf(StrategyKey(StrategyKind.CSV, strategyName), StrategyKey(StrategyKind.API, binance.name)),
                )
            assertEquals(listOf(importedFile.id), offer.files.map { it.id })
            assertEquals(setOf(importedSession), offer.sessions)
            assertTrue(imports.findStrategyReimports(setOf(StrategyKey(StrategyKind.API, "Missing"))).isEmpty)
        }

    @Test
    fun `an API session whose connection is gone is reported as failed`() =
        runDbTest {
            val services = repositories.toAppServices(repositories.importEngine)
            val sessionId = repositories.importEngine.createApiSession(services.deviceId, now)
            val imports = sessionImports()

            val result = imports.apis.reimportSessions(setOf(sessionId), imports.directories.reimports.reads)

            assertEquals(0, result.sessionsReimported)
            assertEquals(listOf(sessionId), result.failures.map { it.sessionId })
        }

    @Test
    fun `the summary names the files and the API sessions it re-imported`() {
        val result =
            AttributeGroupMoveResult(
                attributesMoved = 2,
                files =
                    BulkReimportResult(
                        filesImported = 3,
                        transfersCreated = 0,
                        duplicatesSkipped = 0,
                        filesSkippedNoStrategy = 0,
                        filesFailed = 0,
                    ),
                apiSessions =
                    ApiBulkReimportResult(
                        sessionsReimported = 1,
                        failures = listOf(ApiSessionReimportFailure(ApiSessionId(7), "gone")),
                    ),
            )

        val summary = result.summary()

        assertTrue(summary.startsWith("Moved 2 attributes. Re-imported 3 files"), summary)
        assertTrue(summary.endsWith("Re-imported 1 API session · 1 failed"), summary)
        assertEquals("Moved 1 attribute", AttributeGroupMoveResult(1, null, null).summary())
    }

    private companion object {
        const val MARCUS_NUMBER = "40001234"
        const val BANK_NUMBER = "40005678"
        const val SORT_CODE = "090128"
        const val GROUP = "group-1"
    }
}
