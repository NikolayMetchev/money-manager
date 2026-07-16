@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.api

import com.moneymanager.apiimporter.importApiSessionExchange
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * End-to-end test of the generic signed-exchange import against the built-in Kraken config. Kraken
 * exercises engine capabilities Crypto.com doesn't: a keyed-object response body (`result.trades`/
 * `result.ledger`, not an array), an `error`-array success check, legacy asset-code aliasing (`XXBT`),
 * and a separate enrichment-only endpoint (`DepositStatus`) that supplies on-chain address/txid for a
 * transfer built from another endpoint (`Ledgers`), matched by `joinKeyField`/`refid`.
 */
class KrakenExchangeApiE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    // "result.trades" is an object keyed by trade txid (not an array); each trade still carries its
    // own "trade_id". Pair "XXBTZUSD" splits (QUOTE_SUFFIX "ZUSD") into legacy codes aliased to BTC/USD.
    private val tradesJson =
        """
        {"error":[],"result":{"trades":{
          "TCWJEG-FL4SZ-3FKGH6":{"ordertxid":"O1","pair":"XXBTZUSD","time":1700000000.100,"type":"buy",
           "price":"40000.54","cost":"20000.27","vol":"0.5","fee":"0.05","trade_id":"t1"},
          "TCWJEG-FL4SZ-3FKGH7":{"ordertxid":"O2","pair":"XXBTZUSD","time":1700000000.200,"type":"sell",
           "price":"41000.00","cost":"4100.00","vol":"0.1","fee":"0.52","trade_id":"t2"}
        },"count":2}}
        """.trimIndent()

    // "result.ledger" is also a keyed object; entries carry no id field of their own, so itemKeyField
    // splices the map key into "ledger_id".
    private val depositLedgerJson =
        """
        {"error":[],"result":{"ledger":{
          "LG1":{"refid":"REF1","time":1700000001.5,"asset":"XXBT","amount":"0.01","fee":"0.0000"}
        },"count":1}}
        """.trimIndent()

    private val withdrawalLedgerJson =
        """
        {"error":[],"result":{"ledger":{
          "LG2":{"refid":"REF2","time":1700000002.5,"asset":"ZUSD","amount":"100.00","fee":"0.10"}
        },"count":1}}
        """.trimIndent()

    // Enrichment-only: no money movement of its own, just on-chain address/txid keyed by refid.
    private val depositStatusJson =
        """
        {"error":[],"result":[
          {"method":"Bitcoin","asset":"XBT","refid":"REF1","txid":"onchain-abc","info":"bc1qsenderaddr","amount":"0.01","time":1700000001}
        ]}
        """.trimIndent()

    private suspend fun stageSessionAndImport(
        trades: String = tradesJson,
        deposits: String = depositLedgerJson,
        withdrawals: String = withdrawalLedgerJson,
        depositStatus: String = depositStatusJson,
    ): Int {
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
        stage("0/private/TradesHistory", trades)
        // Deposit vs withdrawal Ledgers share one path; the dedupe-key marker (path + static query
        // params baked into the signed body) disambiguates them, matching what a real download records.
        stage("0/private/Ledgers?type=deposit", deposits)
        stage("0/private/Ledgers?type=withdrawal", withdrawals)
        stage("0/private/DepositStatus", depositStatus)

        importApiSessionExchange(
            apiSessionRepository = repositories.apiSessionRepository,
            accountRepository = repositories.accountRepository,
            currencyRepository = repositories.currencyRepository,
            cryptoRepository = repositories.cryptoRepository,
            sessionId = sessionId,
            strategy = strategy,
            importEngine = repositories.importEngine,
        )
        return sessionId.id.toInt()
    }

    @Test
    fun `imports keyed-object trades and ledger transfers with asset-alias normalization and enrichment, idempotently`() =
        runTest {
            stageSessionAndImport()

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .firstOrNull { it.name == "Kraken" }
            assertNotNull(exchange, "the single Kraken account should exist")

            // "XXBT" normalized to the canonical "BTC" crypto asset (no legacy code left over).
            assertNotNull(repositories.cryptoRepository.getCryptoAssetByCode("BTC").first(), "BTC created")
            assertEquals(null, repositories.cryptoRepository.getCryptoAssetByCode("XXBT").first(), "no leftover XXBT asset")

            val trades = repositories.tradeRepository.getTradesByAccount(exchange.id).first()
            assertEquals(2, trades.size, "both trades imported from the keyed-object response")
            val buy = trades.first { it.to.asset.code == "BTC" }
            assertEquals("USD", buy.from.asset.code, "quote asset alias ZUSD -> USD applied")
            assertEquals("0.5", buy.to.toDisplayValue().toString())

            // Deposit (Ledgers, asset "XXBT" -> BTC), enriched by DepositStatus via refid.
            val deposit =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_001_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_002_000L),
                    ).first()
                    .first { it.targetAccountId == exchange.id && it.amount.asset.code == "BTC" }
            val wallet =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.id == deposit.sourceAccountId }
            assertTrue(wallet.name.contains("bc1qsenderaddr"), "enrichment address should key the wallet account: ${wallet.name}")
            val depositAttrs = repositories.transferAttributeRepository.getByTransaction(deposit.id).first()
            assertTrue(
                depositAttrs.any { it.attributeType.name == "blockchain-txid" && it.value == "onchain-abc" },
                "enrichment txid stored for cross-source reconciliation: $depositAttrs",
            )

            // Withdrawal (Ledgers, asset "ZUSD" -> USD), no enrichment staged for it.
            val withdrawal =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_002_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                    ).first()
                    .first { it.sourceAccountId == exchange.id && it.amount.asset.code == "USD" }
            assertEquals("100", withdrawal.amount.toDisplayValue().toString())

            val tradesBefore = trades.size

            // Re-import the same session: idempotent (exact-match trade guard + provider-id dedupe).
            stageSessionAndImport()
            assertEquals(
                tradesBefore,
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .size,
                "re-import must not double-book trades",
            )
        }
}
