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

    // Kraken reports a withdrawal's "amount" as negative; direction is derived from that sign
    // (directionFromAmountSign), not just from which Ledgers endpoint the entry came from.
    private val withdrawalLedgerJson =
        """
        {"error":[],"result":{"ledger":{
          "LG2":{"refid":"REF2","time":1700000002.5,"asset":"ZUSD","amount":"-100.00","fee":"0.0000"}
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
        withdrawStatus: String? = null,
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
        withdrawStatus?.let { stage("0/private/WithdrawStatus", it) }

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

    @Test
    fun `a fiat withdrawal's WithdrawStatus enrichment books against the funding account, not a bogus wallet account`() =
        runTest {
            // Real shape observed from Kraken's WithdrawStatus for a fiat withdrawal: "info" carries the
            // user's own bank-transfer label ("Monzo"), not a blockchain address — unlike the crypto case
            // (depositStatusJson above), where "info" IS a real on-chain address. Minting a wallet account
            // from a fiat "info" value used to collide by name with the user's real "Monzo" bank account.
            val fiatWithdrawalLedgerJson =
                """
                {"error":[],"result":{"ledger":{
                  "LG3":{"refid":"REF3","time":1700000003.5,"asset":"ZGBP","amount":"-1998.05","fee":"0.0000"}
                },"count":1}}
                """.trimIndent()
            val withdrawStatusJson =
                """
                {"error":[],"result":[
                  {"method":"Banking Circle UK EMI (FPS)","asset":"ZGBP","refid":"REF3","txid":"010F27426","info":"Monzo","amount":"1998.0500","fee":"1.9500","time":1700000003,"status":"Success","key":"My Monzo"}
                ]}
                """.trimIndent()

            stageSessionAndImport(withdrawals = fiatWithdrawalLedgerJson, withdrawStatus = withdrawStatusJson)

            val allAccounts = repositories.accountRepository.getAllAccounts().first()
            assertEquals(
                null,
                allAccounts.firstOrNull { it.name == "Monzo" },
                "no wallet account should be minted from a fiat withdrawal's bank-label \"info\": $allAccounts",
            )

            val exchange = allAccounts.first { it.name == "Kraken" }
            val funding = allAccounts.first { it.name == "Kraken Funding" }
            val withdrawal =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_000L),
                    ).first()
                    .first { it.sourceAccountId == exchange.id && it.amount.asset.code == "GBP" }
            assertEquals(funding.id, withdrawal.targetAccountId, "fiat withdrawal should book against the funding account")
        }

    // Kraken marks some crypto/crypto pairs as "synthetic_pair" and reports them slash-separated
    // ("SUI/BTC") instead of concatenated ("XXBTZGBP"); others use the concatenated form but with the
    // short quote code instead of the legacy long one ("ETHWETH" is ETHW/ETH, not a fiat pair). Both
    // used to fall through splitInstrument's suffix match (only "XXBT"/"XETH" were listed) and get
    // silently dropped — the trade's assets never moved, permanently corrupting the BTC/ETH balance.
    @Test
    fun `imports crypto-quoted pairs instead of silently dropping them`() =
        runTest {
            val trades =
                """
                {"error":[],"result":{"trades":{
                  "T1":{"ordertxid":"O1","pair":"SUI/BTC","time":1700000000.100,"type":"sell",
                   "price":"0.0000129","cost":"0.36347453","vol":"28176.32013","fee":"0.00145390","trade_id":"t1"},
                  "T2":{"ordertxid":"O2","pair":"ETHWETH","time":1700000000.200,"type":"sell",
                   "price":"0.00143","cost":"0.04445562","vol":"31.06611900","fee":"0.00004446","trade_id":"t2"}
                },"count":2}}
                """.trimIndent()

            stageSessionAndImport(trades = trades)

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val trs = repositories.tradeRepository.getTradesByAccount(exchange.id).first()
            assertEquals(2, trs.size, "both crypto-quoted trades should be imported, not dropped")

            val suiBtc = trs.first { it.from.asset.code == "SUI" }
            assertEquals("BTC", suiBtc.to.asset.code, "SUI/BTC slash-separated pair resolves base SUI / quote BTC")
            assertEquals("28176.32013", suiBtc.from.toDisplayValue().toString())

            val ethwEth = trs.first { it.from.asset.code == "ETHW" }
            assertEquals("ETH", ethwEth.to.asset.code, "ETHWETH concatenated pair resolves base ETHW / quote ETH (short code)")
        }

    // "XBTPYUSD" (XBT quoted in PayPal USD) used to match the shorter listed suffix "USD" before
    // "PYUSD" was added, splitting as base "XBTPY" / quote "USD" and auto-creating a bogus "XBTPY"
    // crypto asset that siphoned BTC balance away from the real "BTC" asset.
    @Test
    fun `splits a multi-character stablecoin quote suffix correctly`() =
        runTest {
            val trades =
                """
                {"error":[],"result":{"trades":{
                  "T1":{"ordertxid":"O1","pair":"XBTPYUSD","time":1700000000.100,"type":"buy",
                   "price":"29984.1","cost":"342.05623","vol":"0.00360168","fee":"0.47888","trade_id":"t1"}
                },"count":1}}
                """.trimIndent()

            stageSessionAndImport(trades = trades)

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val trade =
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .single()
            assertEquals("BTC", trade.to.asset.code, "base asset resolves to BTC, not a bogus XBTPY asset")
            assertEquals("PYUSD", trade.from.asset.code, "quote asset resolves to PYUSD, not plain USD")
            assertEquals("0.00360168", trade.to.toDisplayValue().toString())

            assertEquals(null, repositories.cryptoRepository.getCryptoAssetByCode("XBTPY").first(), "no bogus XBTPY asset created")
        }

    // Kraken books a failed/cancelled deposit or withdrawal as two ledger rows sharing one refid with
    // opposite-signed amounts (the debit and its reversal), netting to zero. The endpoint's fixed
    // direction ignored the sign and treated both as real outgoing withdrawals, double-booking a
    // reversed withdrawal attempt as an actual loss of funds.
    @Test
    fun `nets a reversed withdrawal to zero instead of double-booking it`() =
        runTest {
            val withdrawals =
                """
                {"error":[],"result":{"ledger":{
                  "LG1":{"refid":"REFX","time":1700000002.0,"asset":"TRUMP","amount":"33.827200","fee":"0.0000"},
                  "LG2":{"refid":"REFX","time":1700000002.5,"asset":"TRUMP","amount":"-33.827200","fee":"0.0000"}
                },"count":2}}
                """.trimIndent()

            stageSessionAndImport(withdrawals = withdrawals, deposits = """{"error":[],"result":{"ledger":{},"count":0}}""")

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_001_900L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                    ).first()
                    .filter { it.amount.asset.code == "TRUMP" }
            assertEquals(2, transfers.size, "both ledger rows of the reversed withdrawal are recorded")
            val netTrump =
                transfers.sumOf {
                    val signed = if (it.targetAccountId == exchange.id) 1 else -1
                    signed * it.amount.toDisplayValue().toDouble()
                }
            assertEquals(0.0, netTrump, "a debit + its reversal must net to zero, not double-book as a withdrawal")
        }

    // Ledgers is now requested with type=all (not just deposit/withdrawal/reward/staking), so any other
    // balance-affecting ledger type (transfer, margin, adjustment, rollover, credit, settled, sale,
    // nft_rebate, ...) is no longer silently invisible to the importer. type=all also returns "trade"
    // entries, which duplicate TradesHistory and must be excluded rather than double-booked.
    @Test
    fun `imports non-standard ledger types from type=all and excludes duplicate trade entries`() =
        runTest {
            val deposits =
                """
                {"error":[],"result":{"ledger":{
                  "LG1":{"refid":"REF1","time":1700000001.5,"asset":"XXBT","amount":"0.05","fee":"0.0000","type":"transfer"},
                  "LG2":{"refid":"REF2","time":1700000002.5,"asset":"XXBT","amount":"0.36347453","fee":"0.00145390","type":"trade"}
                },"count":2}}
                """.trimIndent()

            stageSessionAndImport(deposits = deposits, withdrawals = """{"error":[],"result":{"ledger":{},"count":0}}""")

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_001_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                    ).first()
                    .filter { it.amount.asset.code == "BTC" }
            // The type=transfer row's amount ("0.05") is booked in full; the type=trade row's amount
            // ("0.36347453") is excluded as a TradesHistory duplicate, but its fee ("0.00145390") is
            // still real BTC that left the account and must still be booked — see the next test.
            val mainTransfer = transfers.singleOrNull { it.amount.toDisplayValue().toString() == "0.05" }
            assertNotNull(mainTransfer, "the type=transfer ledger row is imported; the type=trade row's amount is excluded")
            assertEquals(exchange.id, mainTransfer.targetAccountId)
        }

    // Kraken sometimes charges a same-asset settlement fee on a trade-type ledger row that never appears
    // in TradesHistory's own (quote-currency) fee field — confirmed against real account data by summing
    // every raw ledger row's (amount - fee) and matching it exactly to Kraken's own running "balance"
    // checkpoint. Because feeAmountField previously wasn't read by the exchange engine at all, and the
    // row's excluded main amount discarded the whole row anyway, this fee was silently dropped, leaving a
    // small but real balance short by exactly the missed fee — see ledgerMappings' feeAmountField.
    @Test
    fun `books a same-asset settlement fee from an excluded trade-type ledger row without duplicating its amount`() =
        runTest {
            val deposits =
                """
                {"error":[],"result":{"ledger":{
                  "LG1":{"refid":"REF1","time":1700000002.5,"asset":"XXBT","amount":"0.36347453","fee":"0.00145390","type":"trade"}
                },"count":1}}
                """.trimIndent()

            stageSessionAndImport(deposits = deposits, withdrawals = """{"error":[],"result":{"ledger":{},"count":0}}""")

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val fees =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken Fees" }
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_002_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                    ).first()
                    .filter { it.amount.asset.code == "BTC" }
            assertEquals(1, transfers.size, "only the fee is booked; the trade-duplicate amount is excluded")
            val feeTransfer = transfers.single()
            assertEquals("0.0014539", feeTransfer.amount.toDisplayValue().toString())
            assertEquals(exchange.id, feeTransfer.sourceAccountId)
            assertEquals(fees.id, feeTransfer.targetAccountId)
        }

    // TradesHistory's own "fee" is a quote-currency report that can duplicate (or, for a base-asset
    // settlement fee, wrongly report the fee in the wrong asset entirely) the fee the Ledgers `type=all`
    // feed already books authoritatively (see the two tests above). The trade path must never book a fee
    // of its own from TradesHistory — confirmed here by a trade whose "fee" is nonzero but which has no
    // matching ledger group staged at all, so no fee transfer should exist regardless.
    @Test
    fun `a trade's TradesHistory fee never books its own fee transfer`() =
        runTest {
            val trades =
                """
                {"error":[],"result":{"trades":{
                  "T1":{"ordertxid":"O1","pair":"XXBTZUSD","time":1700000000.100,"type":"buy",
                   "price":"40000.00","cost":"20000.00","vol":"0.5","fee":"52.00","trade_id":"t1"}
                },"count":1}}
                """.trimIndent()

            stageSessionAndImport(trades = trades)

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val fees =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken Fees" }
            val feeTransfers =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_699_999_999_000L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_001_000L),
                    ).first()
                    .filter { it.sourceAccountId == exchange.id && it.targetAccountId == fees.id }
            assertEquals(emptyList(), feeTransfers, "no fee transfer should be booked from TradesHistory's own fee field")
        }

    // Kraken's Ledgers `type=all` also returns the two legs of every trade (excluded from booking a
    // transfer since TradesHistory already supplies the trade — see excludeField/excludeValues), but
    // those legs are the actual settled amounts; TradesHistory's own cost/vol can disagree (confirmed
    // against real account data on synthetic crypto/crypto pairs). A ledger row's refid equals the
    // matching TradesHistory trade's own response-object key (spliced over "trade_id" by itemKeyField),
    // so the two are joined by that shared identifier.
    @Test
    fun `overrides a trade's leg amounts with its matching ledger group when they disagree`() =
        runTest {
            val trades =
                """
                {"error":[],"result":{"trades":{
                  "TMATCH1":{"ordertxid":"O1","pair":"XXBTZUSD","time":1700000000.100,"type":"sell",
                   "price":"40000.00","cost":"20000.00","vol":"0.500000","fee":"0.00","trade_id":"t1"}
                },"count":1}}
                """.trimIndent()
            val deposits =
                """
                {"error":[],"result":{"ledger":{
                  "LG1":{"refid":"TMATCH1","time":1700000000.100,"asset":"XXBT","amount":"-0.500123","fee":"0.0000","type":"trade"},
                  "LG2":{"refid":"TMATCH1","time":1700000000.100,"asset":"ZUSD","amount":"20000.45","fee":"0.0000","type":"trade"}
                },"count":2}}
                """.trimIndent()

            stageSessionAndImport(trades = trades, deposits = deposits, withdrawals = """{"error":[],"result":{"ledger":{},"count":0}}""")

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val trade =
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .single()
            assertEquals("BTC", trade.from.asset.code)
            assertEquals(
                "0.500123",
                trade.from.toDisplayValue().toString(),
                "BTC leg overridden by the ledger's amount, not TradesHistory's vol",
            )
            assertEquals(
                "20000.45",
                trade.to.toDisplayValue().toString(),
                "USD leg overridden by the ledger's amount, not TradesHistory's cost",
            )
        }

    // A Kraken "synthetic_pair" crypto/crypto trade's own key never appears as a ledger refid at all (its
    // ledger legs share a DIFFERENT, unrelated refid) — the fallback join is the same second plus the
    // same {base, quote} asset pair, which is unambiguous in practice.
    @Test
    fun `overrides a synthetic-pair trade's leg amounts via the timestamp and asset-pair fallback`() =
        runTest {
            val trades =
                """
                {"error":[],"result":{"trades":{
                  "STVCTCR-ERSZJ-HBXQB2":{"ordertxid":"O1","pair":"SUI/BTC","time":1700000000.778,"type":"sell",
                   "price":"0.0000129","cost":"0.3634745296","vol":"28176.32013","fee":"0.00145390","trade_id":"t1"}
                },"count":1}}
                """.trimIndent()
            val deposits =
                """
                {"error":[],"result":{"ledger":{
                  "LG1":{"refid":"T4BBJL-WDAFB-KWYHT6","time":1700000000.100,"asset":"SUI","amount":"-28176.32013","fee":"0.00000","type":"trade"},
                  "LG2":{"refid":"T4BBJL-WDAFB-KWYHT6","time":1700000000.900,"asset":"XXBT","amount":"0.3636702500","fee":"0.0014538981","type":"trade"}
                },"count":2}}
                """.trimIndent()

            stageSessionAndImport(trades = trades, deposits = deposits, withdrawals = """{"error":[],"result":{"ledger":{},"count":0}}""")

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val trade =
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .single { it.from.asset.code == "SUI" }
            assertEquals("BTC", trade.to.asset.code)
            assertEquals(
                "0.36367025",
                trade.to.toDisplayValue().toString(),
                "BTC leg overridden by the ledger's actually-settled amount via the timestamp+asset fallback",
            )
        }

    // A ledger trade-type group matching no TradesHistory trade at all (Kraken omitted a fill from
    // TradesHistory entirely) must still be booked — the ledger's own balance is ground truth, so no
    // movement it reports may be silently dropped.
    @Test
    fun `books a ledger trade group with no matching TradesHistory trade at all as its own trade`() =
        runTest {
            val deposits =
                """
                {"error":[],"result":{"ledger":{
                  "LG1":{"refid":"TORPHAN1","time":1700000005.0,"asset":"XXBT","amount":"-0.01","fee":"0.0000","type":"trade"},
                  "LG2":{"refid":"TORPHAN1","time":1700000005.0,"asset":"ZUSD","amount":"410.00","fee":"0.0000","type":"trade"}
                },"count":2}}
                """.trimIndent()

            stageSessionAndImport(
                trades = """{"error":[],"result":{"trades":{},"count":0}}""",
                deposits = deposits,
                withdrawals = """{"error":[],"result":{"ledger":{},"count":0}}""",
            )

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val trade =
                repositories.tradeRepository
                    .getTradesByAccount(exchange.id)
                    .first()
                    .single()
            assertEquals("BTC", trade.from.asset.code, "the ledger-only group is booked as a trade from its two legs")
            assertEquals("0.01", trade.from.toDisplayValue().toString())
            assertEquals("USD", trade.to.asset.code)
            assertEquals("410", trade.to.toDisplayValue().toString())
        }

    // Kraken books each spot<->Earn move as two same-refid `type=transfer, subtype=autoallocation`
    // ledger rows at the same timestamp (a debit from spot, a credit into Earn, or vice versa). After
    // ".F" suffix-stripping both legs are the same asset/amount/account-pair, differing only in
    // direction — the direction-symmetric fuzzy-dedupe fallback used to treat the second leg (which
    // carries its own distinct ledger_id) as a duplicate of the first, silently dropping half of every
    // autoallocation and leaving the exchange balance overstated by the full deposited amount.
    @Test
    fun `books both legs of an earn autoallocation pair instead of fuzzy-deduping one against the other`() =
        runTest {
            val deposits =
                """
                {"error":[],"result":{"ledger":{
                  "LG1":{"refid":"RAUTO","time":1700000004.0,"asset":"XETH","amount":"-5.0000000000","fee":"0.0000","type":"transfer","subtype":"autoallocation"},
                  "LG2":{"refid":"RAUTO","time":1700000004.0,"asset":"XETH.F","amount":"5.0000000000","fee":"0.0000","type":"transfer","subtype":"autoallocation"}
                },"count":2}}
                """.trimIndent()

            stageSessionAndImport(deposits = deposits, withdrawals = """{"error":[],"result":{"ledger":{},"count":0}}""")

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val transfers =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_003_900L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_004_100L),
                    ).first()
                    .filter { it.amount.asset.code == "ETH" }
            assertEquals(2, transfers.size, "both autoallocation legs must be booked, not fuzzy-deduped against each other")
            val net =
                transfers.sumOf {
                    val signed = if (it.targetAccountId == exchange.id) 1 else -1
                    signed * it.amount.toDisplayValue().toDouble()
                }
            assertEquals(0.0, net, "the spot debit and its Earn-side credit must net to zero on the exchange account")
        }

    // Kraken can split one order into several fills that, after ms-truncation, are byte-identical (same
    // timestamp, pair, vol, cost) — createTrade's exact-tuple idempotency used to treat the second
    // identical fill as a re-import of the first and silently drop it, when both are genuinely distinct
    // real trades. N identical fills must book as N trades, while re-importing the same N fills must
    // still dedupe to those same N rows rather than creating N more. The fills' float timestamps differ
    // only below the millisecond (as Kraken's real ones do), so occurrence counting must group them by
    // the persisted ms value, not the full-precision Instant.
    @Test
    fun `books each of several byte-identical fills as its own trade, but stays idempotent on re-import`() =
        runTest {
            val trades =
                """
                {"error":[],"result":{"trades":{
                  "T1":{"ordertxid":"O1","pair":"XXBTZUSD","time":1700000000.500684,"type":"sell",
                   "price":"40000.00","cost":"219.60","vol":"0.1012","fee":"0.00","trade_id":"t1"},
                  "T2":{"ordertxid":"O1","pair":"XXBTZUSD","time":1700000000.500309,"type":"sell",
                   "price":"40000.00","cost":"219.60","vol":"0.1012","fee":"0.00","trade_id":"t2"}
                },"count":2}}
                """.trimIndent()
            val noLedger = """{"error":[],"result":{"ledger":{},"count":0}}"""

            stageSessionAndImport(trades = trades, deposits = noLedger, withdrawals = noLedger)

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val tradesAfterFirst = repositories.tradeRepository.getTradesByAccount(exchange.id).first()
            assertEquals(2, tradesAfterFirst.size, "two byte-identical fills must book as two trades, not collapse to one")

            // Re-import the same session: still exactly two trades (multiset idempotency), not four.
            stageSessionAndImport(trades = trades, deposits = noLedger, withdrawals = noLedger)
            val tradesAfterSecond = repositories.tradeRepository.getTradesByAccount(exchange.id).first()
            assertEquals(2, tradesAfterSecond.size, "re-importing the same two identical fills must not create two more")
        }

    // A failed/cancelled withdrawal's reversal ledger row refunds the fee too: the debit row carries
    // fee "0.03" and the reversal row carries fee "-0.03". abs()-ing the negative fee used to book the
    // refund as a second charge instead of crediting it back, leaving the Fees account permanently
    // overstated (and the exchange balance understated) by double the fee on every reversed withdrawal.
    @Test
    fun `nets a refunded ledger fee to zero instead of booking it as a second charge`() =
        runTest {
            val withdrawals =
                """
                {"error":[],"result":{"ledger":{
                  "LG1":{"refid":"REFFEE","time":1700000002.0,"asset":"XXBT","amount":"-33.827200","fee":"0.03","type":"withdrawal"},
                  "LG2":{"refid":"REFFEE","time":1700000002.5,"asset":"XXBT","amount":"33.827200","fee":"-0.03","type":"withdrawal"}
                },"count":2}}
                """.trimIndent()

            stageSessionAndImport(withdrawals = withdrawals, deposits = """{"error":[],"result":{"ledger":{},"count":0}}""")

            val exchange =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken" }
            val fees =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Kraken Fees" }
            val feeTransfers =
                repositories.transactionRepository
                    .getTransactionsByDateRange(
                        startDate = Instant.fromEpochMilliseconds(1_700_000_001_900L),
                        endDate = Instant.fromEpochMilliseconds(1_700_000_003_000L),
                    ).first()
                    .filter {
                        (it.sourceAccountId == exchange.id && it.targetAccountId == fees.id) ||
                            (it.sourceAccountId == fees.id && it.targetAccountId == exchange.id)
                    }
            assertEquals(2, feeTransfers.size, "both the original fee charge and its refund are recorded")
            val netFee =
                feeTransfers.sumOf {
                    val signed = if (it.targetAccountId == fees.id) 1 else -1
                    signed * it.amount.toDisplayValue().toDouble()
                }
            assertEquals(0.0, netFee, "a fee charge and its refund must net to zero, not double-charge")
        }
}
