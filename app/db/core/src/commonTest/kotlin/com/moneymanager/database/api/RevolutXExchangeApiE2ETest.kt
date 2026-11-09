package com.moneymanager.database.api

import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * The built-in Revolut X strategy reads one ledger, `/transactions`, twice: buys and sells as trades, and
 * every other movement as a transfer of its Revolut X leg. These responses follow the documented shape
 * (each item a `source` and a `destination` leg of net amounts), so the balances prove the rules pick the
 * right leg and direction for every type, book failed and reverted movements excluded, and drop pending ones.
 */
class RevolutXExchangeApiE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private fun leg(
        amount: String,
        currency: String,
        accountType: String,
    ) = """{"amount":"$amount","currency":"$currency","account":{"type":"$accountType"}}"""

    private fun item(
        id: String,
        type: String,
        createdMs: Long,
        source: String? = null,
        destination: String? = null,
        status: String = "completed",
    ) = buildString {
        append("""{"id":"$id","type":"$type","status":"$status","created_date":$createdMs,"processed_date":$createdMs""")
        source?.let { append(""","source":$it""") }
        destination?.let { append(""","destination":$it""") }
        append("}")
    }

    private suspend fun importApi() {
        val strategy = assertNotNull(repositories.apiImportStrategyRepository.getStrategyByName("Revolut X").first())
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-os", "test-machine"))
        val sessionId = repositories.apiSessionRepository.createSession(deviceId, now, null)

        suspend fun stage(
            types: String,
            items: List<String>,
        ) {
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId,
                    "GET",
                    "https://revx.revolut.com/api/1.0/transactions?ep=1.0/transactions?types=$types" +
                        "&ws=1735689600000&we=1738281599999&pg=0",
                    emptyMap(),
                )
            repositories.apiSessionRepository.insertResponse(
                requestId,
                sessionId,
                """{"data":[${items.joinToString(",")}],"metadata":{"timestamp":1738281599999,"next_cursor":""}}""",
            )
        }
        stage(
            "buy,sell",
            listOf(
                item("t-buy", "buy", 1_736_000_100_000, leg("500", "EUR", "revolut_x"), leg("0.01", "BTC", "revolut_x")),
                item("t-sell", "sell", 1_736_000_200_000, leg("0.004", "BTC", "revolut_x"), leg("250", "EUR", "revolut_x")),
                item("t-failed", "buy", 1_736_000_300_000, leg("100", "EUR", "revolut_x"), leg("0.002", "BTC", "revolut_x"), "failed"),
            ),
        )
        stage(
            "receive,reward,un_stake,send,stake",
            listOf(
                item("d-in", "receive", 1_736_000_000_000, leg("1000", "EUR", "revolut"), leg("1000", "EUR", "revolut_x")),
                // A top-up from the Revolut app is a `send` too, into Revolut X.
                item("d-topup", "send", 1_736_000_050_000, leg("5", "EUR", "revolut"), leg("5", "EUR", "revolut_x")),
                item(
                    "d-out",
                    "send",
                    1_736_000_400_000,
                    leg("0.002", "BTC", "revolut_x"),
                    leg("0.002", "BTC", "external_crypto_wallet"),
                ),
                item("d-stake", "stake", 1_736_000_500_000, source = leg("0.001", "BTC", "revolut_x")),
                item("d-unstake", "un_stake", 1_736_000_600_000, destination = leg("0.0004", "BTC", "revolut_x")),
                item("d-reward", "reward", 1_736_000_700_000, destination = leg("0.00001", "BTC", "revolut_x")),
                item(
                    "d-reverted",
                    "send",
                    1_736_000_750_000,
                    leg("0.003", "BTC", "revolut_x"),
                    leg("0.003", "BTC", "external_crypto_wallet"),
                    "reverted",
                ),
                item(
                    "d-pending",
                    "send",
                    1_736_000_800_000,
                    leg("50", "EUR", "revolut_x"),
                    leg("50", "EUR", "external_bank_account"),
                    "pending",
                ),
            ),
        )
        importApiSessionExchange(
            apiSessionRepository = repositories.apiSessionRepository,
            accountRepository = repositories.accountRepository,
            currencyRepository = repositories.currencyRepository,
            cryptoRepository = repositories.cryptoRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
        )
    }

    /** Each named account's counted balance per asset, zero balances dropped. */
    private suspend fun balances(): Map<String, Map<String, String>> {
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
            .groupBy { names.getValue(it.accountId) }
            .mapValues { (_, rows) -> rows.associate { it.balance.asset.code to it.balance.toDisplayValue().toString() } }
    }

    private fun List<TransferAttribute>.excludedValues() =
        filter { it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }.map { it.value }

    @Test
    fun everyMovementLandsOnTheRightAccountAndOnlyCompletedOnesCount() =
        runDbTest {
            importApi()

            val balances = balances()
            // 1000 + 5 in, -500 +250 from the trades; 0.01 bought, -0.004 sold, -0.002 sent, -0.001 staked,
            // +0.0004 unstaked, +0.00001 rewarded. The failed buy and the reverted send are booked
            // excluded, so they change nothing; the pending send is skipped.
            assertEquals(mapOf("EUR" to "755", "BTC" to "0.00341"), balances["Revolut X"])
            assertEquals(mapOf("EUR" to "-1005"), balances["Revolut"])
            assertEquals(mapOf("BTC" to "0.002"), balances["Revolut X Funding"])
            assertEquals(mapOf("BTC" to "0.0006"), balances["Revolut X Staking"])
            assertEquals(mapOf("BTC" to "-0.00001"), balances["Revolut X Staking Rewards"])
            val revolutX =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .single { it.name == "Revolut X" }
            val trades =
                repositories.tradeRepository
                    .getTradesByAccount(revolutX.id)
                    .first()
                    .sortedBy { it.timestamp }
                    .map { trade ->
                        trade.description to
                            repositories.tradeRepository
                                .getAttributes(trade.id)
                                .first()
                                .filter { it.attributeType.id.id == WellKnownIds.EXCLUDED_ATTR_TYPE_ID }
                                .map { it.value }
                    }
            assertEquals(
                listOf("Buy BTC/EUR" to emptyList(), "Sell BTC/EUR" to emptyList(), "Buy BTC/EUR" to listOf("failed")),
                trades,
                "the failed buy is booked excluded, with its status as the reason",
            )
            val transfers = repositories.transactionRepository.getTransactionsByAccount(revolutX.id).first()
            assertEquals(
                listOf(listOf("reverted")),
                transfers.map { it.attributes.excludedValues() }.filter { it.isNotEmpty() },
                "only the reverted send is excluded, with its status as the reason",
            )
            assertEquals(7, transfers.size, "the pending send is not imported")
        }
}
