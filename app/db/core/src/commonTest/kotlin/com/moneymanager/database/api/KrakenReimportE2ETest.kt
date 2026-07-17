@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.api

import com.moneymanager.apiimporter.executeApiReimport
import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.apiimporter.planApiReimport
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.apistrategy.ApiAccountBridge
import com.moneymanager.domain.model.apistrategy.ApiInternalTransferReconcile
import com.moneymanager.importengineapi.updateApiStrategy
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
          "LG1":{"refid":"REF1","time":1700000003.5,"asset":"ZGBP","amount":"1998.05","fee":"1.95"}
        },"count":1}}
        """.trimIndent()
    private val withdrawStatusJson =
        """
        {"error":[],"result":[
          {"method":"Banking Circle UK EMI (FPS)","asset":"ZGBP","refid":"REF1","txid":"010F27426","info":"Monzo","amount":"1998.0500","fee":"1.9500","time":1700000003,"status":"Success","key":"My Monzo"}
        ]}
        """.trimIndent()

    private suspend fun stageAndImport(): ApiSessionId {
        val strategy = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()
        assertNotNull(strategy, "built-in Kraken strategy should be installed")
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
        val sessionId = repositories.apiSessionRepository.createSession("apikey", deviceId, now, null)

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
    fun `re-import rewrites a withdrawal onto a bridge configured after the fact, excluding the pre-existing bank leg`() =
        runTest {
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
                repositories.transactionRepository
                    .createTransfers(
                        transfers =
                            listOf(
                                Transfer(
                                    id = TransferId(0),
                                    timestamp = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                                    description = "Kraken FPS credit",
                                    sourceAccountId = fundingBefore.id,
                                    targetAccountId = monzoId,
                                    amount = Money.fromDisplayValue("1998.05", gbp),
                                ),
                            ),
                        sources = listOf(Source.SampleGenerator),
                    ).first()

            // Configure the bridge — as if the user just added it in the strategy editor.
            val strategyBeforeBridge = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()!!
            repositories.importEngine.updateApiStrategy(
                strategyBeforeBridge.copy(
                    internalTransferReconcile =
                        ApiInternalTransferReconcile(
                            bridges = listOf(ApiAccountBridge(otherAccountName = "Monzo")),
                            windowSeconds = 86_400,
                        ),
                ),
            )
            val strategyWithBridge = repositories.apiImportStrategyRepository.getStrategyByName("Kraken").first()!!
            assertTrue(strategyWithBridge.revisionId > strategyBeforeBridge.revisionId, "updating the strategy should bump its revision")

            val session = repositories.apiSessionRepository.getSessionById(sessionId)!!
            val plan = planApiReimport(sessionId, repositories.apiSessionRepository)
            assertTrue(plan.transferIds.contains(withdrawalBefore.id), "the plan should target the withdrawal this session created")

            val result =
                executeApiReimport(
                    plan = plan,
                    session = session,
                    strategy = strategyWithBridge,
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    cryptoRepository = repositories.cryptoRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    transactionRepository = repositories.transactionRepository,
                    transferRelationshipRepository = repositories.transferRelationshipRepository,
                    tradeRepository = repositories.tradeRepository,
                    maintenance = testMaintenance,
                    importEngine = repositories.importEngine,
                )
            assertEquals(1, result.transfersDeleted, "the withdrawal created by this session should be deleted before re-run")

            val withdrawalsAfter =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .filter { it.amount.asset.code == "GBP" }
            val rewritten = withdrawalsAfter.first { it.sourceAccountId != it.targetAccountId }
            assertEquals(monzoId, rewritten.targetAccountId, "the withdrawal should now be rewritten onto Monzo")

            val monzoCreditAfter = repositories.transactionRepository.getTransactionsByIds(setOf(monzoCredit))[monzoCredit]
            assertNotNull(monzoCreditAfter, "the seeded Monzo credit should still exist")
            assertTrue(
                monzoCreditAfter.attributes.any { it.attributeType.id.id == -1L },
                "the Monzo credit should now be excluded (reconciled) rather than double-counting the withdrawal",
            )
            assertTrue(
                repositories.transferRelationshipRepository.getByTransfers(setOf(monzoCredit, rewritten.id)).isNotEmpty(),
                "the rewritten withdrawal and the Monzo credit should be linked",
            )

            // Two legs target Monzo (the seeded credit + the rewritten withdrawal), but only one of them
            // counts toward the balance — the other is excluded (reconciled), so no double-count.
            assertEquals(2, withdrawalsAfter.count { it.targetAccountId == monzoId }, "both legs should still exist")
            assertEquals(
                1,
                withdrawalsAfter.count { it.targetAccountId == monzoId && it.attributes.none { attr -> attr.attributeType.id.id == -1L } },
                "re-import must not double-count the withdrawal against Monzo's balance",
            )

            // Re-importing again is idempotent: same shape, no crash on the second pass.
            val plan2 = planApiReimport(sessionId, repositories.apiSessionRepository)
            val result2 =
                executeApiReimport(
                    plan = plan2,
                    session = session,
                    strategy = strategyWithBridge,
                    apiSessionRepository = repositories.apiSessionRepository,
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    cryptoRepository = repositories.cryptoRepository,
                    accountAttributeRepository = repositories.accountAttributeRepository,
                    transactionRepository = repositories.transactionRepository,
                    transferRelationshipRepository = repositories.transferRelationshipRepository,
                    tradeRepository = repositories.tradeRepository,
                    maintenance = testMaintenance,
                    importEngine = repositories.importEngine,
                )
            assertEquals(1, result2.transfersDeleted, "the second re-import should delete exactly the one leg it re-creates")
            val withdrawalsFinal =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .filter { it.amount.asset.code == "GBP" }
            assertEquals(2, withdrawalsFinal.count { it.targetAccountId == monzoId }, "still both legs after a second re-import")
            assertEquals(
                1,
                withdrawalsFinal.count { it.targetAccountId == monzoId && it.attributes.none { attr -> attr.attributeType.id.id == -1L } },
                "a second re-import must remain idempotent — still exactly one movement counted into Monzo's balance",
            )
        }
}
