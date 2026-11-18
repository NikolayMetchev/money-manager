package com.moneymanager.database.api

import com.moneymanager.apiimporter.ApiImportReads
import com.moneymanager.apiimporter.bulkReimportApiSessions
import com.moneymanager.apiimporter.executeApiReimport
import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.apiimporter.planApiReimport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.ensureApiCredential
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.markApiSessionImported
import com.moneymanager.importengineapi.updateApiStrategy
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.reimports
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The motivating scenario for API re-import: Kraken is imported BEFORE the "Monzo" bridge is
 * configured, so a fiat withdrawal books against the generic "Kraken Funding" account instead of
 * reconciling against the real bank account. Adding the bridge afterwards and pressing Re-import must
 * retroactively rewrite the withdrawal onto Monzo and exclude the pre-existing bank-side credit it
 * duplicates — without creating any new duplicate movement.
 */
class KrakenReimportE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val testMaintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private val tradesJson = """{"error":[],"result":{"trades":{},"count":0}}"""
    private val depositLedgerJson = """{"error":[],"result":{"ledger":{},"count":0}}"""

    // A single fiat GBP withdrawal, no bridge configured on first import.
    private val withdrawalLedgerJson =
        """
        {"error":[],"result":{"ledger":{
          "LG1":{"refid":"REF1","time":1700000003.5,"asset":"ZGBP","amount":"-1998.05","fee":"0.0000"}
        },"count":1}}
        """.trimIndent()
    private val withdrawStatusJson =
        """
        {"error":[],"result":[
          {"method":"Banking Circle UK EMI (FPS)","asset":"ZGBP","refid":"REF1","txid":"010F27426","info":"Monzo","amount":"1998.0500","fee":"1.9500","time":1700000003,"status":"Success","key":"My Monzo"}
        ]}
        """.trimIndent()

    private suspend fun stageAndImport(credentialId: ApiCredentialId? = null): ApiSessionId {
        val strategy = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()
        assertNotNull(strategy, "built-in Kraken strategy should be installed")
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
        // A download always runs under the strategy's credential, which is how its records are known to be Kraken's.
        val sessionId =
            repositories.apiSessionRepository.createSession(
                deviceId,
                now,
                null,
                credentialId ?: repositories.importEngine.ensureApiCredential(strategy.id, now),
            )

        suspend fun stage(
            markerKey: String,
            json: String,
        ) {
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId,
                    "POST",
                    "https://api.kraken.com/$markerKey?ep=$markerKey",
                    emptyMap(),
                )
            repositories.apiSessionRepository.insertResponse(requestId, sessionId, json)
        }
        stage("0/private/TradesHistory", tradesJson)
        stage("0/private/Ledgers?type=deposit", depositLedgerJson)
        stage("0/private/Ledgers?type=withdrawal", withdrawalLedgerJson)
        stage("0/private/WithdrawStatus", withdrawStatusJson)

        importApiSessionExchange(
            apiSessionRepository = repositories.apiSessionRepository,
            accountRepository = repositories.accountRepository,
            currencyRepository = repositories.currencyRepository,
            cryptoRepository = repositories.cryptoRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
        )
        return sessionId
    }

    @Test
    fun `re-import rewrites a withdrawal onto a bridge configured after the fact - excluding the pre-existing bank leg`() =
        runDbTest {
            val sessionId = stageAndImport()

            val fundingBefore =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken Funding" }
            val withdrawalBefore =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .first { it.amount.asset.code == "GBP" }
            assertEquals(
                fundingBefore.id,
                withdrawalBefore.targetAccountId,
                "before the bridge, the withdrawal books against the generic funding account",
            )

            // Seed the real bank account with the matching credit the withdrawal duplicates.
            val gbp = repositories.currencyRepository.getCurrencyByCode("GBP").first()
            assertNotNull(gbp, "GBP currency should exist after the withdrawal import")
            val monzoId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Monzo", openingDate = now),
                    Source.SampleGenerator,
                )
            val monzoCredit =
                createRecordedTransfer(
                    Transfer(
                        id = TransferId(0),
                        timestamp = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        description = "Kraken FPS credit",
                        sourceAccountId = fundingBefore.id,
                        targetAccountId = monzoId,
                        amount = Money.fromDisplayValue("1998.05", gbp),
                    ),
                )

            // Configure the bridge — as if the user just added it in the strategy editor.
            val strategyBeforeBridge = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()!!
            repositories.importEngine.updateApiStrategy(
                strategyBeforeBridge.copy(
                    config =
                        strategyBeforeBridge.config.copy(
                            internalTransferReconcile =
                                ApiInternalTransferReconcile(
                                    bridges = listOf(ApiAccountBridge(otherAccountName = "Monzo")),
                                    window = SettlementWindow.Elapsed(86_400),
                                ),
                        ),
                ),
            )
            val strategyWithBridge = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()!!
            assertTrue(strategyWithBridge.revisionId > strategyBeforeBridge.revisionId, "updating the strategy should bump its revision")

            val session = repositories.apiSessionRepository.getSessionById(sessionId)!!
            val reimports = repositories.reimports(testMaintenance)
            val apiReads =
                ApiImportReads(
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    currencyRepository = repositories.currencyRepository,
                    cryptoRepository = repositories.cryptoRepository,
                    importRecordRepository = repositories.importRecordRepository,
                )
            val plan = planApiReimport(sessionId, repositories.apiSessionRepository, reimports.reads)
            assertTrue(plan.createdTransfers.contains(withdrawalBefore.id), "the plan should target the withdrawal this session created")
            assertEquals(1, plan.createdTransfers.size, "the withdrawal created by this session should be deleted before re-run")

            executeApiReimport(
                plan = plan,
                session = session,
                strategy = strategyWithBridge,
                apiReads = apiReads,
                reads = reimports.reads,
                maintenance = testMaintenance,
                importEngine = repositories.importEngine,
            )

            val withdrawalsAfter =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .filter { it.amount.asset.code == "GBP" }
            val rewritten = withdrawalsAfter.first { it.id != monzoCredit && it.sourceAccountId != it.targetAccountId }
            assertEquals(monzoId, rewritten.targetAccountId, "the withdrawal should now be rewritten onto Monzo")

            // The planned deletion really executed, and the bank's record and the rewritten withdrawal are one movement: one
            // GBP transaction, counted once, holding both records.
            assertEquals(
                setOf(rewritten.id),
                withdrawalsAfter.map { it.id }.toSet(),
                "after the re-import the bank's credit and the rewritten withdrawal are one transaction",
            )
            // The session's item said the same slot again, so its transaction took its id back (#1429), as a staged file's does.
            assertEquals(withdrawalBefore.id, rewritten.id, "the rewritten withdrawal should keep the id the session gave it")
            assertTrue(rewritten.attributes.none { it.attributeType.id.id == -1L }, "the withdrawal counts")
            assertEquals(
                2,
                repositories.observationRepository.getObservationsOfTransaction(rewritten.id).size,
                "the session's record and the bank's are both kept",
            )

            // Re-importing again is idempotent: same shape, no crash on the second pass.
            val plan2 = planApiReimport(sessionId, repositories.apiSessionRepository, reimports.reads)
            assertEquals(1, plan2.createdTransfers.size, "the second re-import should delete exactly the one leg it re-creates")
            executeApiReimport(
                plan = plan2,
                session = session,
                strategy = strategyWithBridge,
                apiReads = apiReads,
                reads = reimports.reads,
                maintenance = testMaintenance,
                importEngine = repositories.importEngine,
            )
            val withdrawalsFinal =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .filter { it.amount.asset.code == "GBP" }
            assertEquals(
                1,
                withdrawalsFinal.count { it.targetAccountId == monzoId && it.attributes.none { attr -> attr.attributeType.id.id == -1L } },
                "a second re-import must remain idempotent — still exactly one movement counted into Monzo's balance",
            )
        }

    @Test
    fun `re-import replays a hand edit of a transaction the session created`() =
        runDbTest {
            val sessionId = stageAndImport()
            val withdrawal =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .first { it.amount.asset.code == "GBP" }
            val noteType = repositories.importEngine.getOrCreateAttributeType("note")
            repositories.importEngine.import(
                ImportBatch.manualEdits(
                    transfers =
                        listOf(
                            ImportTransfer(
                                source = Source.Manual,
                                operation = ImportOperation.UPDATE,
                                existingId = withdrawal.id,
                                fromAccount = AccountRef.Existing(withdrawal.sourceAccountId),
                                toAccount = AccountRef.Existing(withdrawal.targetAccountId),
                                timestamp = withdrawal.timestamp,
                                description = "Rent to savings",
                                amount = withdrawal.amount,
                                attributes = listOf(NewAttribute(noteType, "moved by hand")),
                            ),
                        ),
                ),
            )

            val session = repositories.apiSessionRepository.getSessionById(sessionId)!!
            val strategy = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()!!
            val reimports = repositories.reimports(testMaintenance)
            val plan = planApiReimport(sessionId, repositories.apiSessionRepository, reimports.reads)
            val result =
                executeApiReimport(
                    plan = plan,
                    session = session,
                    strategy = strategy,
                    apiReads =
                        ApiImportReads(
                            apiSessionRepository = repositories.apiSessionRepository,
                            accountRepository = repositories.accountRepository,
                            accountAttributeRepository = repositories.accountAttributeRepository,
                            currencyRepository = repositories.currencyRepository,
                            cryptoRepository = repositories.cryptoRepository,
                            importRecordRepository = repositories.importRecordRepository,
                        ),
                    reads = reimports.reads,
                    maintenance = testMaintenance,
                    importEngine = repositories.importEngine,
                )

            // The session's items take their old ids back, so the hand edit is an override on the kept id: nothing is replayed.
            assertTrue(result.plan.releasedOverrides.isEmpty(), "released: ${result.plan.releasedOverrides}")
            val recreated =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .single { it.amount.asset.code == "GBP" }
            assertEquals(withdrawal.id, recreated.id, "the edited transfer keeps its id")
            assertEquals("Rent to savings", recreated.description)
            assertEquals(listOf("moved by hand"), recreated.attributes.filter { it.attributeType.name == "note" }.map { it.value })
        }

    @Test
    fun `re-importing every session of a connection rewrites each onto a bridge configured after the fact`() =
        runDbTest {
            val strategyBeforeBridge = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()!!
            val credentialId = repositories.importEngine.ensureApiCredential(strategyBeforeBridge.id, now)
            val sessionId = stageAndImport(credentialId)
            // As the API screens do once a session imports, so re-import all finds it.
            repositories.importEngine.markApiSessionImported(sessionId, strategyBeforeBridge.revisionId, now)
            val monzoId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "Monzo", openingDate = now),
                    Source.SampleGenerator,
                )
            val funding =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken Funding" }
            val gbp = assertNotNull(repositories.currencyRepository.getCurrencyByCode("GBP").first())
            createRecordedTransfer(
                Transfer(
                    id = TransferId(0),
                    timestamp = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                    description = "Kraken FPS credit",
                    sourceAccountId = funding.id,
                    targetAccountId = monzoId,
                    amount = Money.fromDisplayValue("1998.05", gbp),
                ),
            )
            repositories.importEngine.updateApiStrategy(
                strategyBeforeBridge.copy(
                    config =
                        strategyBeforeBridge.config.copy(
                            internalTransferReconcile =
                                ApiInternalTransferReconcile(
                                    bridges = listOf(ApiAccountBridge(otherAccountName = "Monzo")),
                                    window = SettlementWindow.Elapsed(86_400),
                                ),
                        ),
                ),
            )
            val reimports = repositories.reimports(testMaintenance)

            val result =
                bulkReimportApiSessions(
                    credentialId = credentialId,
                    strategy = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()!!,
                    apiReads =
                        ApiImportReads(
                            apiSessionRepository = repositories.apiSessionRepository,
                            accountRepository = repositories.accountRepository,
                            accountAttributeRepository = repositories.accountAttributeRepository,
                            currencyRepository = repositories.currencyRepository,
                            cryptoRepository = repositories.cryptoRepository,
                            importRecordRepository = repositories.importRecordRepository,
                        ),
                    reads = reimports.reads,
                    maintenance = testMaintenance,
                    importEngine = repositories.importEngine,
                )

            assertEquals(1, result.sessionsReimported, "the connection's one imported session should be re-imported")
            assertEquals(emptyList(), result.failures)
            val intoMonzo =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .filter { it.amount.asset.code == "GBP" && it.targetAccountId == monzoId }
            // The re-imported withdrawal lands on the bridged account, as the bank's credit: one movement, holding both records.
            val movement = intoMonzo.single()
            assertTrue(movement.attributes.none { attr -> attr.attributeType.id.id == -1L }, "it counts")
            assertEquals(2, repositories.observationRepository.getObservationsOfTransaction(movement.id).size, "both records")
        }
}
