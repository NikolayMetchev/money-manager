package com.moneymanager.observationmatcher

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.importengineapi.TradeDedupePolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The trade rules and stages over the built-in fixtures' shapes (Binance's export and API, Crypto.com's two exports,
 * Kraken's identical fills): each case is imported one source after the other in both orders, as the importer will
 * feed the matcher ([ObservationMatcher.scopeOf], then [ObservationMatcher.match], then the ids stored), and all at
 * once. Every order must end in the same transactions and the same exclusions.
 */
class TradeStagesTest {
    @Test
    fun `binance fills itemise the export's gross trade in either order`() {
        // The API reports the order as two fills to the millisecond; the export as one trade to the second.
        val fill1 = trade(1, API_SESSION, "f1", API, millis = 120, fromAmount = 4, toAmount = 20_000)
        val fill2 = trade(2, API_SESSION, "f2", API, millis = 462, fromAmount = 6, toAmount = 30_000)
        val gross = trade(3, CSV_FILE, "gross", CSV, toAmount = 50_000)

        val outcome = assertOrderFree(listOf(fill1, fill2), listOf(gross))

        assertEquals(setOf(setOf("f1"), setOf("f2"), setOf("gross")), outcome.transactions)
        assertEquals(setOf(Excluded("trade aggregation", setOf(setOf("gross")), setOf(setOf("f1"), setOf("f2")))), outcome.exclusions)
    }

    @Test
    fun `a fill that alone records the trade is a one-for-one match and not an aggregation`() {
        val apiTrade = trade(1, API_SESSION, "api", API, millis = 462)
        val csvTrade = trade(2, CSV_FILE, "csv", CSV)
        val otherFill = trade(3, API_SESSION, "other", API, millis = 900, fromAmount = 1, toAmount = 1)

        val outcome = assertOrderFree(listOf(apiTrade, otherFill), listOf(csvTrade))

        assertEquals(setOf(setOf("api", "csv"), setOf("other")), outcome.transactions)
        assertEquals(emptySet(), outcome.exclusions)
    }

    @Test
    fun `trades outside the window or of other amounts stay apart`() {
        val apiTrade = trade(1, API_SESSION, "api", API)
        val late = trade(2, CSV_FILE, "late", CSV, millis = 6_000)
        val otherAmount = trade(3, CSV_FILE, "amount", CSV, toAmount = 51)

        val outcome = assertOrderFree(listOf(apiTrade), listOf(late, otherAmount))

        assertEquals(setOf(setOf("api"), setOf("late"), setOf("amount")), outcome.transactions)
        assertEquals(emptySet(), outcome.exclusions)
    }

    @Test
    fun `binance's dust sweep is booked once whichever source came first`() {
        // The export books the sweep as transfers through the conversion account, the API as one trade per asset.
        val doge = leg(1, "doge-out", DOGE, 5, LegGroupSide.DEBIT)
        val shib = leg(2, "shib-out", SHIB, 7, LegGroupSide.DEBIT)
        val bnb = leg(3, "bnb-in", BNB, 3, LegGroupSide.CREDIT)
        val dogeTrade = trade(4, API_SESSION, "doge-trade", API, fromAsset = DOGE, fromAmount = 5, toAsset = BNB, toAmount = 2)
        val shibTrade = trade(5, API_SESSION, "shib-trade", API, fromAsset = SHIB, fromAmount = 7, toAsset = BNB, toAmount = 1)

        val outcome = assertOrderFree(listOf(doge, shib, bnb), listOf(dogeTrade, shibTrade))

        assertEquals(
            setOf(
                Excluded(
                    "conversion group",
                    setOf(setOf("doge-out"), setOf("shib-out"), setOf("bnb-in")),
                    setOf(setOf("doge-trade"), setOf("shib-trade")),
                ),
            ),
            outcome.exclusions,
        )
        assertEquals(setOf(setOf("doge-trade"), setOf("shib-trade")), outcome.counted)
    }

    @Test
    fun `a sweep the api reports only in part excludes the trade by its leg`() {
        val doge = leg(1, "doge-out", DOGE, 5, LegGroupSide.DEBIT)
        val shib = leg(2, "shib-out", SHIB, 7, LegGroupSide.DEBIT)
        val bnb = leg(3, "bnb-in", BNB, 3, LegGroupSide.CREDIT)
        val dogeTrade = trade(4, API_SESSION, "doge-trade", API, fromAsset = DOGE, fromAmount = 5, toAsset = BNB, toAmount = 2)

        val outcome = assertOrderFree(listOf(doge, shib, bnb), listOf(dogeTrade))

        assertEquals(setOf(Excluded("conversion trade", setOf(setOf("doge-trade")), setOf(setOf("doge-out")))), outcome.exclusions)
        assertEquals(setOf(setOf("doge-out"), setOf("shib-out"), setOf("bnb-in")), outcome.counted)
    }

    @Test
    fun `a sweep whose amounts differ or whose trade an order claims is left alone`() {
        val doge = leg(1, "doge-out", DOGE, 5, LegGroupSide.DEBIT)
        val bnb = leg(2, "bnb-in", BNB, 3, LegGroupSide.CREDIT)
        val otherAmount = trade(3, API_SESSION, "other", API, fromAsset = DOGE, fromAmount = 6, toAsset = BNB, toAmount = 2)
        val ordered =
            trade(4, "session-2", "ordered", API, fromAsset = DOGE, fromAmount = 5, toAsset = BNB, toAmount = 2, orderLinked = true)
        // A trade of the same debit an hour later is outside both windows.
        val late = trade(5, "session-3", "late", API, millis = 3_600_000, fromAsset = DOGE, fromAmount = 5, toAsset = BNB, toAmount = 2)

        // The group stage doesn't look at orders (it never did): leave the ordered trade out of that one.
        val outcome = assertOrderFree(listOf(doge, bnb), listOf(otherAmount, late))
        assertEquals(emptySet(), outcome.exclusions)

        val partial = leg(6, "shib-out", SHIB, 7, LegGroupSide.DEBIT)
        val orderedOutcome = assertOrderFree(listOf(doge, partial, bnb), listOf(ordered))
        assertEquals(emptySet(), orderedOutcome.exclusions)
    }

    @Test
    fun `crypto_com's fiat and crypto exports of one viban purchase are one trade`() {
        // Both exports carry the purchase to the millisecond with every amount, under different descriptions.
        val fiat = trade(1, "fiat.csv", "fiat", "Crypto.com Fiat", description = "Buy BTC")
        val crypto = trade(2, "crypto.csv", "crypto", "Crypto.com Crypto", description = "Crypto purchase")

        val outcome = assertOrderFree(listOf(fiat), listOf(crypto))

        assertEquals(setOf(setOf("fiat", "crypto")), outcome.transactions)
        assertEquals(listOf("trade tuple"), outcome.rules)
    }

    @Test
    fun `kraken's identical fills stay two however often they are downloaded`() {
        val first = listOf(trade(1, "session-1", "a1", KRAKEN), trade(2, "session-1", "a2", KRAKEN))
        val again = listOf(trade(3, "session-2", "b1", KRAKEN), trade(4, "session-2", "b2", KRAKEN))

        val outcome = assertOrderFree(first, again)

        assertEquals(2, outcome.transactions.size)
        outcome.transactions.forEach { transaction ->
            assertEquals(1, transaction.count { it.startsWith("a") }, "$transaction")
            assertEquals(1, transaction.count { it.startsWith("b") }, "$transaction")
        }
    }

    @Test
    fun `permuting the input never changes the trade stages' result`() {
        val scope =
            listOf(
                trade(1, API_SESSION, "f1", API, millis = 120, fromAmount = 4, toAmount = 20_000),
                trade(2, API_SESSION, "f2", API, millis = 462, fromAmount = 6, toAmount = 30_000),
                trade(3, CSV_FILE, "gross", CSV, toAmount = 50_000),
                leg(4, "doge-out", DOGE, 5, LegGroupSide.DEBIT),
                leg(5, "shib-out", SHIB, 7, LegGroupSide.DEBIT),
                leg(6, "bnb-in", BNB, 3, LegGroupSide.CREDIT),
                trade(7, API_SESSION, "doge-1", API, fromAsset = DOGE, fromAmount = 5, toAsset = BNB, toAmount = 2),
                trade(8, "session-2", "doge-2", API, millis = 1_000, fromAsset = DOGE, fromAmount = 5, toAsset = BNB, toAmount = 2),
                trade(9, "k-1", "k1", KRAKEN),
                trade(10, "k-1", "k2", KRAKEN),
                trade(11, "k-2", "k3", KRAKEN),
            )
        val matcher = matcher()
        val expected = matcher.match(scope)
        val rotations = scope.indices.map { scope.drop(it) + scope.take(it) }
        for (permutation in rotations + rotations.map { it.reversed() } + (1..30).map { scope.shuffled(Random(it)) }) {
            assertEquals(expected, matcher.match(permutation))
        }
    }

    /** What an import order ends in, by record keys: the transactions, the exclusions and the rules that joined. */
    private data class Outcome(
        val transactions: Set<Set<String>>,
        val exclusions: Set<Excluded>,
        val rules: List<String>,
    ) {
        /** The transactions balances count. */
        val counted get() = transactions - exclusions.flatMapTo(mutableSetOf()) { it.excluded }
    }

    private data class Excluded(
        val rule: String,
        val excluded: Set<Set<String>>,
        val by: Set<Set<String>>,
    )

    /** Imports [first] then [second], [second] then [first], and both at once, and checks they end the same. */
    private fun assertOrderFree(
        first: List<MatchObservation>,
        second: List<MatchObservation>,
    ): Outcome {
        val forward = importInTurn(listOf(first, second))
        assertEquals(forward.copy(rules = emptyList()), importInTurn(listOf(second, first)).copy(rules = emptyList()))
        assertEquals(forward.copy(rules = emptyList()), importInTurn(listOf(first + second)).copy(rules = emptyList()))
        return forward
    }

    /**
     * Imports each batch in turn: matches the scope it needs against what is stored, gives new transactions ids, and
     * keeps the exclusions of every scope (a later scope replaces those of the observations it covers).
     */
    private fun importInTurn(batches: List<List<MatchObservation>>): Outcome {
        val matcher = matcher()
        val stored = linkedMapOf<ObservationRef, MatchObservation>()
        val links = mutableListOf<StageExclusion>()
        val rules = mutableListOf<String>()
        var nextObservation = 1L
        var nextTransaction = 1_000L
        for (batch in batches) {
            val scope = matcher.scopeOf(changed = batch, removed = emptyList(), stored = stored.values)
            val result = matcher.match(scope)
            rules += result.joins.map { it.rule }
            val ids = mutableMapOf<TransactionTarget, TransactionId>()

            fun idOf(
                target: TransactionTarget,
                trade: Boolean,
            ): TransactionId =
                ids.getOrPut(target) {
                    when (target) {
                        is TransactionTarget.Existing -> target.id
                        is TransactionTarget.New -> if (trade) TradeId(nextTransaction++) else TransferId(nextTransaction++)
                    }
                }
            for (observation in scope) {
                val target = result.assignments.getValue(observation.ref)
                stored[observation.ref] =
                    MatchObservation(
                        ref = observation.ref,
                        storedId = observation.storedId ?: ObservationId(nextObservation++),
                        current = idOf(target, observation.trade != null),
                        family = observation.family,
                        container = observation.container,
                        recordKey = observation.recordKey,
                        content = observation.content,
                        identity = observation.identity,
                        orderLinked = observation.orderLinked,
                        legGroup = observation.legGroup,
                    )
            }
            val covered = scope.mapTo(mutableSetOf()) { it.ref }
            links.removeAll { link -> link.matched.any { it in covered } }
            links += result.exclusions
        }
        val members = stored.values.groupBy({ it.current }, { it.recordKey }).mapValues { it.value.toSet() }

        fun transactionsOf(refs: Set<ObservationRef>) = refs.mapTo(mutableSetOf()) { members.getValue(stored.getValue(it).current) }
        return Outcome(
            transactions = members.values.toSet(),
            exclusions =
                links.mapTo(mutableSetOf()) {
                    Excluded(
                        it.rule,
                        transactionsOf(it.excluded),
                        transactionsOf(it.matched - it.excluded),
                    )
                },
            rules = rules,
        )
    }

    private fun matcher() =
        ObservationMatcher(
            TradeRules.forPolicies(TRADE_POLICIES),
            TradeStages.forPolicies(TRADE_POLICIES, mapOf(LegGroupRuleRef(CSV, DUST) to 5.seconds)),
        )

    @Suppress("LongParameterList")
    private fun trade(
        record: Long,
        container: String,
        recordKey: String,
        family: String,
        millis: Long = 0,
        fromAsset: Long = BTC,
        fromAmount: Long = 10,
        toAsset: Long = USDT,
        toAmount: Long = 50,
        description: String = "Trade",
        orderLinked: Boolean = false,
    ) = MatchObservation(
        ref = ObservationRef(ObservationOwner.Record(ImportRecordId(record)), "trade"),
        storedId = null,
        current = null,
        family = family,
        container = container,
        recordKey = recordKey,
        content =
            ObservationContent(
                ObservedValues.Trade(
                    timestamp = START + millis.milliseconds,
                    description = description,
                    fromAccountId = SPOT,
                    fromAssetId = ObservedAssetId(fromAsset),
                    fromAmount = BigInteger(fromAmount),
                    toAccountId = SPOT,
                    toAssetId = ObservedAssetId(toAsset),
                    toAmount = BigInteger(toAmount),
                ),
            ),
        orderLinked = orderLinked,
    )

    /** A leg of the export's dust sweep: debits leave the spot wallet for the conversion account, credits return. */
    private fun leg(
        record: Long,
        recordKey: String,
        asset: Long,
        amount: Long,
        side: LegGroupSide,
    ) = MatchObservation(
        ref = ObservationRef(ObservationOwner.Record(ImportRecordId(record)), "main"),
        storedId = null,
        current = null,
        family = CSV,
        container = CSV_FILE,
        recordKey = recordKey,
        content =
            ObservationContent(
                ObservedValues.Transfer(
                    timestamp = START,
                    description = "Small assets exchange BNB",
                    sourceAccountId = if (side == LegGroupSide.DEBIT) SPOT else CONVERSIONS,
                    targetAccountId = if (side == LegGroupSide.DEBIT) CONVERSIONS else SPOT,
                    assetId = ObservedAssetId(asset),
                    amount = BigInteger(amount),
                ),
            ),
        legGroup = LegGroupMembership(DUST, "sweep-1", side, "conversion"),
    )

    private companion object {
        const val API = "Binance API"
        const val CSV = "Binance CSV"
        const val KRAKEN = "Kraken API"
        const val DUST = "dust"
        const val API_SESSION = "session-1"
        const val CSV_FILE = "export.csv"
        const val BTC = 1L
        const val USDT = 2L
        const val DOGE = 3L
        const val SHIB = 4L
        const val BNB = 5L
        val SPOT = AccountId(10)
        val CONVERSIONS = AccountId(20)
        val START = Instant.parse("2026-03-02T09:00:00Z")

        // As the importer builds them today: the exchange API at 5 s, the export at its trade groups' window.
        val TRADE_POLICIES =
            mapOf(
                API to TradeDedupePolicy.Fuzzy(5.seconds),
                CSV to TradeDedupePolicy.Fuzzy(5.seconds),
                KRAKEN to TradeDedupePolicy.Fuzzy(5.seconds),
            )
    }
}
