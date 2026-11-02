package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.reconciliation.IGNORED_IN_RECONCILIATION
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LegMatcherTest {
    private val ada = CryptoAsset(id = CryptoId(1), code = "ADA", name = "Cardano")
    private val spam = CryptoAsset(id = CryptoId(2), code = "CLAIM-USDT.INFO", name = "spam")

    private val wallet = AccountId(100) // shadow "Koinly · Binance"
    private val sourceRewards = AccountId(101) // shadow "Koinly: reward"
    private val binance = AccountId(1)
    private val binanceEarn = AccountId(2)
    private val stakingRewards = AccountId(3)
    private val links = listOf(ReconciliationLink(wallet, binance), ReconciliationLink(wallet, binanceEarn))

    private val t0 = Instant.parse("2021-05-15T00:56:32Z")
    private var nextId = 1L

    private fun leg(
        account: AccountId,
        counterparty: AccountId?,
        amount: String,
        at: Instant,
        asset: CryptoAsset = ada,
        excluded: Boolean = false,
        movement: String? = null,
        reason: String? = if (excluded) "deleted" else null,
        matchIds: Set<Long> = emptySet(),
        transactionId: Long? = null,
    ) = (transactionId ?: nextId++).let { id ->
        ReconciliationLeg(
            transactionId = id,
            kind = if (counterparty == null) LegTransactionKind.TRADE else LegTransactionKind.TRANSFER,
            timestamp = at,
            description = "",
            accountId = account,
            counterpartyAccountId = counterparty,
            amount = Money.fromDisplayValue(BigDecimal(amount), asset),
            excludedReason = reason,
            movementKey = movement ?: "m$id",
            matchIds = matchIds,
        )
    }

    private fun ada(amount: String) = Money.fromDisplayValue(BigDecimal(amount), ada)

    @Test
    fun `exact second matches and unmatched legs land on the right side`() {
        val source = listOf(leg(wallet, sourceRewards, "0.00051544", t0), leg(wallet, sourceRewards, "1", t0 + 1.hours))
        val real = listOf(leg(binance, stakingRewards, "0.00051544", t0), leg(binance, stakingRewards, "2", t0 + 30.minutes))

        val result = reconcile(source, links, real)

        assertEquals(1, result.matched.size)
        assertEquals(listOf(ada("1")), result.missingInMm.map { it.leg.amount })
        assertEquals(listOf(ada("2")), result.missingInSource.map { it.leg.amount })
    }

    @Test
    fun `fuzzy pass pairs the nearest leg within the window and never reuses one`() {
        val source = listOf(leg(wallet, sourceRewards, "5", t0), leg(wallet, sourceRewards, "5", t0 + 1.hours))
        val real = listOf(leg(binance, stakingRewards, "5", t0 + 2.hours), leg(binance, stakingRewards, "5", t0 + 30.hours))

        val result = reconcile(source, links, real)

        assertEquals(1, result.nearMatches.size)
        assertEquals(
            t0 + 1.hours,
            result.nearMatches
                .single()
                .sourceLeg.timestamp,
        )
        assertEquals(1, result.missingInMm.size)
        // Outside the source's date range, so out of scope rather than missing from the source.
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `identical movements pair one to one`() {
        val source = List(3) { leg(wallet, sourceRewards, "1", t0) }
        val real = List(2) { leg(binance, stakingRewards, "1", t0) }

        val result = reconcile(source, links, real)

        assertEquals(2, result.matched.size)
        assertEquals(1, result.missingInMm.size)
    }

    @Test
    fun `moves between accounts linked to one wallet are internal and ignored`() {
        val source = listOf(leg(wallet, sourceRewards, "1", t0))
        val real =
            listOf(
                leg(binance, binanceEarn, "-7", t0),
                leg(binanceEarn, binance, "7", t0),
                leg(binance, stakingRewards, "1", t0),
            )

        val result = reconcile(source, links, real)

        assertEquals(1, result.matched.size)
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `excluded real legs and legs on unlinked shadow accounts are out of scope`() {
        val unlinkedWallet = AccountId(102)
        val source = listOf(leg(wallet, sourceRewards, "1", t0), leg(unlinkedWallet, sourceRewards, "3", t0))
        val real = listOf(leg(binance, stakingRewards, "1", t0, excluded = true))

        val result = reconcile(source, links, real)

        assertEquals(listOf(wallet), result.missingInMm.map { it.walletAccountId })
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `assets never seen on the linked accounts are flagged`() {
        val source = listOf(leg(wallet, sourceRewards, "1", t0), leg(wallet, sourceRewards, "1000", t0, asset = spam))
        val real = listOf(leg(binance, stakingRewards, "2", t0))

        val flags = reconcile(source, links, real).missingInMm.associate { it.leg.amount.asset.code to it.assetUnknownToMm }

        assertEquals(mapOf("ADA" to false, "CLAIM-USDT.INFO" to true), flags)
    }

    @Test
    fun `a fee booked separately on one side matches the other side's gross leg`() {
        // Source: trade principal + its fee, same second. Real: one gross debit half a minute later.
        val source =
            listOf(leg(wallet, null, "-985.32", t0, movement = "row7"), leg(wallet, sourceRewards, "-14.68", t0, movement = "row7"))
        val real = listOf(leg(binance, null, "-1000", t0 + 35.seconds))

        val result = reconcile(source, links, real)

        assertEquals(1, result.matches.size)
        assertEquals(
            2,
            result.matches
                .single()
                .sourceLegs.size,
        )
        assertTrue(result.missingInMm.isEmpty())
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `unrelated movements in the same second are never summed together`() {
        // Two separate rewards stamped the same second must not pass for one real +3.
        val source = listOf(leg(wallet, sourceRewards, "1", t0), leg(wallet, sourceRewards, "2", t0))
        val real = listOf(leg(binance, stakingRewards, "3", t0))

        val result = reconcile(source, links, real)

        assertTrue(result.matches.isEmpty())
        assertEquals(2, result.missingInMm.size)
        assertEquals(1, result.missingInSource.size)
    }

    @Test
    fun `per-fill fees on one side match a single trade fee on the other`() {
        // Koinly books one fee for the order; Bybit charges every fill separately, each its own transaction.
        val usdt = CryptoAsset(id = CryptoId(3), code = "USDT", name = "Tether")
        val source =
            listOf(
                leg(wallet, null, "-1345.880771", t0, asset = usdt, movement = "trade"),
                leg(wallet, null, "968.4", t0, movement = "trade"),
                leg(wallet, sourceRewards, "-0.9684", t0, movement = "fee"),
            )
        val real =
            listOf(
                leg(binance, null, "-1345.880771", t0, asset = usdt, movement = "trade"),
                leg(binance, null, "968.4", t0, movement = "trade"),
                leg(binance, stakingRewards, "-0.4928", t0),
                leg(binance, stakingRewards, "-0.0111", t0),
                leg(binance, stakingRewards, "-0.4645", t0),
            )

        val result = reconcile(source, links, real)

        assertEquals(3, result.matches.size)
        assertTrue(result.missingInMm.isEmpty())
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `excluded source legs do not widen the date range`() {
        // A deleted row is out of scope, so the real leg only it covered isn't reported missing either.
        val source = listOf(leg(wallet, sourceRewards, "1", t0), leg(wallet, sourceRewards, "5", t0 + 30.hours, excluded = true))
        val real = listOf(leg(binance, stakingRewards, "1", t0), leg(binance, stakingRewards, "5", t0 + 30.hours))

        val result = reconcile(source, links, real)

        assertEquals(1, result.matched.size)
        assertTrue(result.missingInSource.isEmpty())
    }

    @Test
    fun `a stored match pairs legs whose amounts and times disagree`() {
        val source = listOf(leg(wallet, sourceRewards, "5", t0, matchIds = setOf(7)))
        val real =
            listOf(
                leg(binance, stakingRewards, "4.9", t0 + 3.hours, matchIds = setOf(7)),
                leg(binanceEarn, stakingRewards, "0.1", t0 + 40.hours, matchIds = setOf(7)),
            )

        val result = reconcile(source, links, real)

        val match = result.matched.single()
        assertEquals(7L, match.matchId)
        assertEquals(2, match.realLegs.size)
        assertTrue(result.nearMatches.isEmpty() && result.missingInMm.isEmpty() && result.missingInSource.isEmpty())
        assertTrue(result.unclaimedReal.isEmpty())
    }

    @Test
    fun `a stored match with one side gone falls back to normal matching`() {
        val source = listOf(leg(wallet, sourceRewards, "5", t0, matchIds = setOf(7)))
        val real = listOf(leg(binance, stakingRewards, "5", t0 + 1.hours))

        val result = reconcile(source, links, real)

        assertEquals(null, result.nearMatches.single().matchId)
    }

    @Test
    fun `a source transfer between two linked wallets can be in a stored match on each`() {
        val otherWallet = AccountId(200)
        val coinbase = AccountId(20)
        val twoWallets = links + ReconciliationLink(otherWallet, coinbase)
        val transferId = 500L
        val source =
            listOf(
                leg(wallet, otherWallet, "-5", t0, matchIds = setOf(1, 2), transactionId = transferId),
                leg(otherWallet, wallet, "5", t0, matchIds = setOf(1, 2), transactionId = transferId),
            )
        val real =
            listOf(
                leg(binance, stakingRewards, "-5", t0 + 2.hours, matchIds = setOf(1)),
                leg(coinbase, stakingRewards, "5", t0 + 5.hours, matchIds = setOf(2)),
            )

        val result = reconcile(source, twoWallets, real)

        assertEquals(setOf(1L, 2L), result.matched.map { it.matchId }.toSet())
        assertTrue(result.missingInMm.isEmpty() && result.missingInSource.isEmpty())
    }

    @Test
    fun `ignored source legs are collected and never matched`() {
        val source =
            listOf(
                leg(wallet, sourceRewards, "5", t0, reason = IGNORED_IN_RECONCILIATION),
                leg(wallet, sourceRewards, "6", t0 + 1.hours),
            )
        val real = listOf(leg(binance, stakingRewards, "5", t0))

        val result = reconcile(source, links, real)

        assertEquals(listOf(ada("5")), result.ignored.map { it.leg.amount })
        assertTrue(result.matches.isEmpty())
        assertEquals(listOf(ada("6")), result.missingInMm.map { it.leg.amount })
    }

    @Test
    fun `unclaimed real legs exclude matched ones but reach past the source's range`() {
        val source = listOf(leg(wallet, sourceRewards, "5", t0), leg(wallet, sourceRewards, "6", t0 + 20.hours))
        val real =
            listOf(
                leg(binance, stakingRewards, "5", t0),
                leg(binance, stakingRewards, "7", t0 + 10.hours),
                leg(binance, stakingRewards, "8", t0 + 100.hours),
            )

        val result = reconcile(source, links, real)

        assertEquals(listOf(ada("7"), ada("8")), result.unclaimedReal.map { it.leg.amount })
        assertEquals(listOf(ada("7")), result.missingInSource.map { it.leg.amount })
    }
}
